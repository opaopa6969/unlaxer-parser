//! First-order parameterized rules (#701), expanded before any backend sees the AST.
//!
//! The parser records `Name(p1, ..., pn) ::= ...` definitions and `Name(e1, ..., en)`
//! calls in side tables; a call is a private placeholder reference that never leaves
//! this module. Expansion then runs in a fixed order:
//!
//! 1. definition checks (reserved names, annotations, duplicates, shadowing),
//! 2. use checks (arity, undefined names, captures),
//! 3. rejection of every cycle in the parameterized call graph,
//! 4. monomorphization into ordinary zero-arity rules under a size limit.
//!
//! Existing validators (left recursion, nullable repetition, ...) run afterwards on the
//! expanded grammar only. Checking the unexpanded grammar is unsound (lean4-peg CE-002:
//! `F(x) ::= x; S ::= F(S)`). See docs/parameterized-rules.md for the argument.
use crate::ast::*;
use crate::{diagnostic, Diagnostic, DiagnosticKind};
use std::collections::{HashMap, HashSet};

/// Total atomic elements in all expanded instances, aligned with the existing
/// 65536-visit traversal limits of the generators.
pub const MAX_EXPANDED_ELEMENTS: usize = 65_536;
/// Nesting of one expanded instance body or argument, aligned with [`crate::MAX_NESTING`].
pub const MAX_EXPANDED_NESTING: usize = crate::MAX_NESTING;
/// Instances under expansion at once (the parameterized call chain), so that the
/// recursive expansion has a fixed stack budget on both hosts.
pub const MAX_EXPANSION_DEPTH: usize = crate::MAX_NESTING;
/// Builtin call spellings that a parameterized rule must not reuse.
const RESERVED: [&str; 4] = ["CAPTURE", "SAME_AS", "CAPTURE_SCOPE", "ERROR"];
/// Placeholder prefix. NUL never occurs in an identifier, so no user name can match.
const CALL_PREFIX: &str = "\u{0}call";

pub(crate) struct Call {
    pub name: String,
    pub args: Vec<RuleBody>,
    pub span: Span,
}

pub(crate) fn placeholder(index: usize) -> String {
    format!("{CALL_PREFIX}{index}")
}

fn call_index(name: &str) -> Option<usize> {
    name.strip_prefix(CALL_PREFIX)?.parse().ok()
}

type Result<T> = std::result::Result<T, Diagnostic>;

fn error(source: &str, span: Span, message: String) -> Diagnostic {
    diagnostic(source, span, DiagnosticKind::InvalidValue, message)
}

fn position(source: &str, span: Span) -> String {
    let at = diagnostic(source, span, DiagnosticKind::InvalidValue, "");
    format!("line {}, column {}", at.line, at.column)
}

/// For each `grammar Name { ... }` in source order, the names defined there as
/// `Name(params) ::=` with the opening parenthesis adjacent. Only these names turn
/// an adjacent `Name(` of the same grammar into a call; every other spelling keeps its
/// previous meaning (a reference followed by a group), so grammars without
/// parameterized definitions parse exactly as before.
pub(crate) fn prescan(tokens: &[crate::lexer::Token]) -> Vec<HashSet<String>> {
    use crate::lexer::Kind;
    let mut grammars = Vec::new();
    let mut i = 0;
    while i < tokens.len() {
        let header = matches!(&tokens[i].kind, Kind::Identifier(word) if word == "grammar")
            && matches!(
                tokens.get(i + 1).map(|t| &t.kind),
                Some(Kind::Identifier(_))
            )
            && tokens
                .get(i + 2)
                .is_some_and(|t| t.kind == Kind::Symbol('{'));
        if !header {
            i += 1;
            continue;
        }
        // Braces are balanced inside a valid grammar (settings, repeats, bounds).
        let (start, mut end, mut depth) = (i + 3, i + 2, 0usize);
        while end < tokens.len() {
            match tokens[end].kind {
                Kind::Symbol('{') => depth += 1,
                Kind::Symbol('}') => {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
                Kind::Eof => break,
                _ => {}
            }
            end += 1;
        }
        grammars.push(definitions(&tokens[start..end.min(tokens.len())]));
        i = end + 1;
    }
    grammars
}

fn definitions(tokens: &[crate::lexer::Token]) -> HashSet<String> {
    use crate::lexer::Kind;
    let mut names = HashSet::new();
    for (i, token) in tokens.iter().enumerate() {
        let Kind::Identifier(name) = &token.kind else {
            continue;
        };
        let Some(open) = tokens.get(i + 1) else {
            continue;
        };
        if open.kind != Kind::Symbol('(') || open.span.byte_start != token.span.byte_end {
            continue;
        }
        let mut j = i + 2;
        loop {
            if !matches!(tokens.get(j).map(|t| &t.kind), Some(Kind::Identifier(_))) {
                j = usize::MAX;
                break;
            }
            j += 1;
            if tokens.get(j).is_some_and(|t| t.kind == Kind::Symbol(',')) {
                j += 1;
            } else {
                break;
            }
        }
        if j != usize::MAX
            && tokens.get(j).is_some_and(|t| t.kind == Kind::Symbol(')'))
            && tokens.get(j + 1).is_some_and(|t| t.kind == Kind::Define)
        {
            names.insert(name.clone());
        }
    }
    names
}

struct Def<'a> {
    rule: usize,
    params: &'a [String],
}

/// Validate and expand one grammar. `params[i]` belongs to `grammar.rules[i]`.
pub(crate) fn expand(
    source: &str,
    grammar: GrammarDecl,
    params: Vec<Option<Vec<String>>>,
    calls: &[Call],
) -> Result<GrammarDecl> {
    if calls.is_empty() && params.iter().all(Option::is_none) {
        return Ok(grammar);
    }
    let rules = &grammar.rules;
    let tokens: HashSet<&str> = grammar.tokens.iter().map(|t| t.name.as_str()).collect();
    let mut rule_counts: HashMap<&str, usize> = HashMap::new();
    for rule in rules {
        *rule_counts.entry(rule.name.as_str()).or_default() += 1;
    }
    let mut defs: HashMap<&str, Def> = HashMap::new();
    for (index, rule) in rules.iter().enumerate() {
        let Some(list) = &params[index] else {
            continue;
        };
        let name = rule.name.as_str();
        if RESERVED.contains(&name) {
            return Err(error(
                source,
                rule.span,
                format!(
                "E-PARAM-RESERVED: parameterized rule name {name} is reserved by a builtin call"
            ),
            ));
        }
        if !rule.annotations.is_empty() {
            return Err(error(source, rule.span, format!(
                "E-PARAM-ANNOTATION: parameterized rule {name} cannot have annotations; only transparent rules take parameters"
            )));
        }
        if tokens.contains(name) || rule_counts[name] > 1 {
            return Err(error(
                source,
                rule.span,
                format!("E-PARAM-DUPLICATE: rule {name} is defined more than once"),
            ));
        }
        let mut seen = HashSet::new();
        for parameter in list {
            if !seen.insert(parameter.as_str()) {
                return Err(error(source, rule.span, format!(
                    "E-PARAM-DUPLICATE: parameter {parameter} is declared more than once in rule {name}"
                )));
            }
            if tokens.contains(parameter.as_str()) || rule_counts.contains_key(parameter.as_str()) {
                return Err(error(source, rule.span, format!(
                    "E-PARAM-SHADOW: parameter {parameter} of rule {name} has the same name as a rule or token"
                )));
            }
        }
        defs.insert(
            name,
            Def {
                rule: index,
                params: list,
            },
        );
    }
    let mut known: HashSet<&str> = tokens.clone();
    known.extend(rules.iter().map(|r| r.name.as_str()));
    let mut check = Check {
        source,
        grammar: &grammar.name,
        calls,
        defs: &defs,
        known: &known,
        rules,
        edges: vec![Vec::new(); rules.len()],
    };
    for (index, rule) in rules.iter().enumerate() {
        let scope = Scope {
            owner: defs
                .get(rule.name.as_str())
                .filter(|d| d.rule == index)
                .map(|_| index),
            params: params[index].as_deref().unwrap_or(&[]),
            call: None,
            rule: rule.span,
        };
        check.body(&rule.body, &scope)?;
    }
    let edges = check.edges;
    reject_cycles(source, rules, &defs, &edges)?;

    let mut expander = Expander {
        source,
        calls,
        rules,
        defs: &defs,
        known: &known,
        instances: Vec::new(),
        by_key: HashMap::new(),
        names: HashSet::new(),
        total: 0,
        depth: 0,
    };
    let empty = HashMap::new();
    let mut expanded = Vec::with_capacity(rules.len());
    for (index, rule) in rules.iter().enumerate() {
        if params[index].is_some() {
            continue;
        }
        expanded.push(RuleDecl {
            annotations: rule.annotations.clone(),
            name: rule.name.clone(),
            body: expander.body(&rule.body, &empty)?,
            span: rule.span,
        });
    }
    expanded.extend(
        expander
            .instances
            .into_iter()
            .map(|rule| rule.expect("instance body completed")),
    );
    Ok(GrammarDecl {
        rules: expanded,
        ..grammar
    })
}

#[derive(Clone, Copy)]
struct Scope<'a> {
    /// Rule index when walking a parameterized definition body.
    owner: Option<usize>,
    params: &'a [String],
    /// Innermost enclosing call, when walking an argument.
    call: Option<Span>,
    rule: Span,
}

struct Check<'a> {
    source: &'a str,
    grammar: &'a str,
    calls: &'a [Call],
    defs: &'a HashMap<&'a str, Def<'a>>,
    known: &'a HashSet<&'a str>,
    rules: &'a [RuleDecl],
    edges: Vec<Vec<(usize, Span)>>,
}

impl Check<'_> {
    fn body(&mut self, body: &RuleBody, scope: &Scope) -> Result<()> {
        for sequence in &body.alternatives {
            for element in &sequence.elements {
                if (scope.owner.is_some() || scope.call.is_some())
                    && (element.capture.is_some() || element.typeof_constraint.is_some())
                {
                    return Err(error(
                        self.source,
                        scope.call.unwrap_or(scope.rule),
                        "E-PARAM-CAPTURE: captures and @typeof are not allowed in parameterized rule bodies or call arguments".into(),
                    ));
                }
                self.atomic(&element.element, scope)?;
            }
        }
        Ok(())
    }

    fn atomic(&mut self, element: &AtomicElement, scope: &Scope) -> Result<()> {
        match &element.kind {
            ElementKind::RuleRef {
                namespace: None,
                name,
            } => {
                if let Some(index) = call_index(name) {
                    let call = &self.calls[index];
                    let Some(def) = self.defs.get(call.name.as_str()) else {
                        return Err(error(
                            self.source,
                            call.span,
                            format!(
                                "E-PARAM-UNDEFINED: parameterized rule {} is not defined in grammar {}",
                                call.name, self.grammar
                            ),
                        ));
                    };
                    if def.params.len() != call.args.len() {
                        return Err(error(
                            self.source,
                            call.span,
                            format!(
                                "E-PARAM-ARITY: rule {} expects {} argument(s), got {}; defined at {}",
                                call.name,
                                def.params.len(),
                                call.args.len(),
                                position(self.source, self.rules[def.rule].span)
                            ),
                        ));
                    }
                    if let Some(owner) = scope.owner {
                        self.edges[owner].push((def.rule, call.span));
                    }
                    let inner = Scope {
                        call: Some(call.span),
                        ..*scope
                    };
                    for argument in &call.args {
                        self.body(argument, &inner)?;
                    }
                } else if scope.params.contains(name) {
                    return Ok(());
                } else if let Some(def) = self.defs.get(name.as_str()) {
                    return Err(error(
                        self.source,
                        element.span,
                        format!(
                            "E-PARAM-ARITY: rule {name} expects {} argument(s), got 0; defined at {}",
                            def.params.len(),
                            position(self.source, self.rules[def.rule].span)
                        ),
                    ));
                } else if let Some(owner) = scope.owner {
                    if !self.known.contains(name.as_str()) {
                        return Err(error(
                            self.source,
                            element.span,
                            format!(
                                "E-PARAM-UNDEFINED: rule {} references undefined parameter, rule or token '{name}'",
                                self.rules[owner].name
                            ),
                        ));
                    }
                }
            }
            ElementKind::RuleRef { .. }
            | ElementKind::Terminal(_)
            | ElementKind::Replay { .. }
            | ElementKind::Error(_) => {}
            ElementKind::OwnedCapture { body, .. }
            | ElementKind::CaptureScope(body)
            | ElementKind::Group(body)
            | ElementKind::Optional(body)
            | ElementKind::Repeat(body) => self.body(body, scope)?,
            ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
                self.atomic(inner, scope)?
            }
            ElementKind::Separated { element, separator } => {
                self.atomic(element, scope)?;
                self.atomic(separator, scope)?;
            }
        }
        Ok(())
    }
}

/// Depth-first search in definition order; edges keep body traversal order, so the
/// reported back edge is deterministic and identical in the Java frontend.
fn reject_cycles(
    source: &str,
    rules: &[RuleDecl],
    defs: &HashMap<&str, Def>,
    edges: &[Vec<(usize, Span)>],
) -> Result<()> {
    let mut state = vec![0u8; rules.len()];
    let mut roots: Vec<usize> = defs.values().map(|d| d.rule).collect();
    roots.sort_unstable();
    for root in roots {
        if state[root] != 0 {
            continue;
        }
        state[root] = 1;
        let mut stack = vec![(root, 0usize)];
        while let Some(top) = stack.last_mut() {
            let node = top.0;
            if top.1 == edges[node].len() {
                state[node] = 2;
                stack.pop();
                continue;
            }
            let (target, span) = edges[node][top.1];
            top.1 += 1;
            match state[target] {
                0 => {
                    state[target] = 1;
                    stack.push((target, 0));
                }
                1 => {
                    let from = stack.iter().position(|(n, _)| *n == target).unwrap();
                    let mut path: Vec<&str> = stack[from..]
                        .iter()
                        .map(|(n, _)| rules[*n].name.as_str())
                        .collect();
                    path.push(&rules[target].name);
                    return Err(error(
                        source,
                        span,
                        format!(
                        "E-PARAM-CYCLE: parameterized call cycle {}; expansion would not terminate",
                        path.join(" -> ")
                    ),
                    ));
                }
                _ => {}
            }
        }
    }
    Ok(())
}

struct Expander<'a> {
    source: &'a str,
    calls: &'a [Call],
    rules: &'a [RuleDecl],
    defs: &'a HashMap<&'a str, Def<'a>>,
    known: &'a HashSet<&'a str>,
    /// Pre-order: an instance takes its slot before its own callees.
    instances: Vec<Option<RuleDecl>>,
    by_key: HashMap<String, String>,
    names: HashSet<String>,
    /// Atomic elements materialized so far: call arguments and instance bodies.
    total: usize,
    /// Instances whose bodies are being expanded (the current call chain).
    depth: usize,
}

/// A substituted argument with its measured size and nesting, so that measuring a
/// body never walks into a replacement again.
struct Rep {
    element: AtomicElement,
    size: usize,
    depth: usize,
}

type Env<'a> = HashMap<&'a str, Rep>;

impl Expander<'_> {
    fn body(&mut self, body: &RuleBody, env: &Env) -> Result<RuleBody> {
        let mut alternatives = Vec::with_capacity(body.alternatives.len());
        for sequence in &body.alternatives {
            let mut elements = Vec::with_capacity(sequence.elements.len());
            for element in &sequence.elements {
                elements.push(AnnotatedElement {
                    element: self.atomic(&element.element, env)?,
                    capture: element.capture.clone(),
                    typeof_constraint: element.typeof_constraint.clone(),
                    capture_span: element.capture_span,
                    typeof_span: element.typeof_span,
                    span: element.span,
                });
            }
            alternatives.push(Sequence {
                elements,
                span: sequence.span,
            });
        }
        Ok(RuleBody {
            alternatives,
            span: body.span,
        })
    }

    fn atomic(&mut self, element: &AtomicElement, env: &Env) -> Result<AtomicElement> {
        let kind = match &element.kind {
            ElementKind::RuleRef {
                namespace: None,
                name,
            } => {
                if let Some(index) = call_index(name) {
                    let call = &self.calls[index];
                    // Bound the arguments before materializing them or their key: a
                    // shared callee can still receive exponentially growing arguments.
                    let (mut size, mut depth) = (0usize, 0usize);
                    for argument in &call.args {
                        let (n, d) = measure_body(argument, env);
                        size = size.saturating_add(n);
                        depth = depth.max(d);
                    }
                    if depth > MAX_EXPANDED_NESTING {
                        return Err(error(self.source, call.span, format!(
                            "E-PARAM-LIMIT: argument of rule {} nests {depth} levels; the limit is {MAX_EXPANDED_NESTING}",
                            call.name
                        )));
                    }
                    self.charge(size, call.span)?;
                    let mut args = Vec::with_capacity(call.args.len());
                    for argument in &call.args {
                        args.push(self.body(argument, env)?);
                    }
                    ElementKind::RuleRef {
                        namespace: None,
                        name: self.instance(&call.name, args, call.span)?,
                    }
                } else if let Some(replacement) = env.get(name.as_str()) {
                    return Ok(replacement.element.clone());
                } else {
                    return Ok(element.clone());
                }
            }
            ElementKind::RuleRef { .. }
            | ElementKind::Terminal(_)
            | ElementKind::Replay { .. }
            | ElementKind::Error(_) => return Ok(element.clone()),
            ElementKind::OwnedCapture { name, body } => ElementKind::OwnedCapture {
                name: name.clone(),
                body: self.body(body, env)?,
            },
            ElementKind::CaptureScope(body) => ElementKind::CaptureScope(self.body(body, env)?),
            ElementKind::Group(body) => ElementKind::Group(self.body(body, env)?),
            ElementKind::Optional(body) => ElementKind::Optional(self.body(body, env)?),
            ElementKind::Repeat(body) => ElementKind::Repeat(self.body(body, env)?),
            ElementKind::OneOrMore(inner) => {
                ElementKind::OneOrMore(Box::new(self.atomic(inner, env)?))
            }
            ElementKind::BoundedRepeat {
                element: inner,
                min,
                max,
            } => ElementKind::BoundedRepeat {
                element: Box::new(self.atomic(inner, env)?),
                min: *min,
                max: *max,
            },
            ElementKind::Separated { element, separator } => ElementKind::Separated {
                element: Box::new(self.atomic(element, env)?),
                separator: Box::new(self.atomic(separator, env)?),
            },
        };
        Ok(Spanned {
            kind,
            span: element.span,
        })
    }

    fn charge(&mut self, size: usize, span: Span) -> Result<()> {
        self.total = self.total.saturating_add(size);
        if self.total > MAX_EXPANDED_ELEMENTS {
            return Err(error(self.source, span, format!(
                "E-PARAM-LIMIT: expanded parameterized rules exceed {MAX_EXPANDED_ELEMENTS} elements"
            )));
        }
        Ok(())
    }

    fn instance(&mut self, callee: &str, args: Vec<RuleBody>, span: Span) -> Result<String> {
        let mut key = format!("{callee}(");
        for (index, argument) in args.iter().enumerate() {
            if index > 0 {
                key.push(',');
            }
            write_body(&mut key, argument);
        }
        key.push(')');
        if let Some(name) = self.by_key.get(&key) {
            return Ok(name.clone());
        }
        let name = format!("{callee}_{:016x}", fnv1a64(key.as_bytes()));
        if self.known.contains(name.as_str()) || self.names.contains(&name) {
            return Err(error(self.source, span, format!(
                "E-PARAM-NAME-COLLISION: expanded rule name {name} collides with another rule, token or expansion"
            )));
        }
        if self.depth + 1 > MAX_EXPANSION_DEPTH {
            return Err(error(self.source, span, format!(
                "E-PARAM-LIMIT: expansion of rule {callee} is nested {} calls deep; the limit is {MAX_EXPANSION_DEPTH}",
                self.depth + 1
            )));
        }
        let def = &self.defs[callee];
        let rule = &self.rules[def.rule];
        let empty = HashMap::new();
        let env: Env = def
            .params
            .iter()
            .map(String::as_str)
            .zip(args.into_iter().map(|argument| {
                let element = replacement(argument);
                let (size, depth) = measure_atomic(&element, &empty);
                Rep {
                    element,
                    size,
                    depth,
                }
            }))
            .collect();
        let (size, depth) = measure_body(&rule.body, &env);
        if depth > MAX_EXPANDED_NESTING {
            return Err(error(self.source, span, format!(
                "E-PARAM-LIMIT: expansion of rule {callee} nests {depth} levels; the limit is {MAX_EXPANDED_NESTING}"
            )));
        }
        self.charge(size, span)?;
        self.by_key.insert(key, name.clone());
        self.names.insert(name.clone());
        let slot = self.instances.len();
        self.instances.push(None);
        self.depth += 1;
        let body = self.body(&rule.body, &env)?;
        self.depth -= 1;
        self.instances[slot] = Some(RuleDecl {
            annotations: Vec::new(),
            name: name.clone(),
            body,
            span: rule.span,
        });
        Ok(name)
    }
}

/// A single plain element is substituted as itself; anything else keeps its
/// grouping, exactly as if the argument had been written in parentheses.
fn replacement(argument: RuleBody) -> AtomicElement {
    if let [sequence] = argument.alternatives.as_slice() {
        if let [element] = sequence.elements.as_slice() {
            if element.capture.is_none() && element.typeof_constraint.is_none() {
                return element.element.clone();
            }
        }
    }
    Spanned {
        span: argument.span,
        kind: ElementKind::Group(argument),
    }
}

/// (atomic elements, nesting in bodies) after substitution, saturating. Calls count
/// as one element (they become references); arguments are measured at their call.
fn measure_body(body: &RuleBody, env: &Env) -> (usize, usize) {
    let (mut size, mut depth) = (0usize, 0);
    for sequence in &body.alternatives {
        for element in &sequence.elements {
            let (n, d) = measure_atomic(&element.element, env);
            size = size.saturating_add(n);
            depth = depth.max(d);
        }
    }
    (size, depth + 1)
}

fn measure_atomic(element: &AtomicElement, env: &Env) -> (usize, usize) {
    match &element.kind {
        ElementKind::RuleRef {
            namespace: None,
            name,
        } if call_index(name).is_none() && env.contains_key(name.as_str()) => {
            let rep = &env[name.as_str()];
            (rep.size, rep.depth)
        }
        ElementKind::RuleRef { .. }
        | ElementKind::Terminal(_)
        | ElementKind::Replay { .. }
        | ElementKind::Error(_) => (1, 0),
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => {
            let (n, d) = measure_body(body, env);
            (n.saturating_add(1), d)
        }
        ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
            let (n, d) = measure_atomic(inner, env);
            (n.saturating_add(1), d)
        }
        ElementKind::Separated { element, separator } => {
            let (a, da) = measure_atomic(element, env);
            let (b, db) = measure_atomic(separator, env);
            (a.saturating_add(b).saturating_add(1), da.max(db))
        }
    }
}

/// Injective, host-independent spelling of call-free arguments (docs/parameterized-rules.md).
fn write_body(out: &mut String, body: &RuleBody) {
    for (i, sequence) in body.alternatives.iter().enumerate() {
        if i > 0 {
            out.push('|');
        }
        for (j, element) in sequence.elements.iter().enumerate() {
            if j > 0 {
                out.push(' ');
            }
            write_atomic(out, &element.element);
        }
    }
}

fn write_codepoints(out: &mut String, text: &str) {
    for (i, c) in text.chars().enumerate() {
        if i > 0 {
            out.push('.');
        }
        out.push_str(&format!("{:x}", c as u32));
    }
}

fn wrap(out: &mut String, tag: &str, body: &RuleBody) {
    out.push_str(tag);
    out.push('(');
    write_body(out, body);
    out.push(')');
}

fn write_atomic(out: &mut String, element: &AtomicElement) {
    match &element.kind {
        ElementKind::Terminal(text) => {
            out.push_str("t(");
            write_codepoints(out, text);
            out.push(')');
        }
        ElementKind::Error(text) => {
            out.push_str("e(");
            write_codepoints(out, text);
            out.push(')');
        }
        ElementKind::RuleRef { namespace, name } => {
            out.push_str("r(");
            if let Some(namespace) = namespace {
                out.push_str(namespace);
                out.push('.');
            }
            out.push_str(name);
            out.push(')');
        }
        ElementKind::Replay { name } => {
            out.push_str("y(");
            out.push_str(name);
            out.push(')');
        }
        ElementKind::Group(body) => wrap(out, "g", body),
        ElementKind::Optional(body) => wrap(out, "o", body),
        ElementKind::Repeat(body) => wrap(out, "z", body),
        ElementKind::CaptureScope(body) => wrap(out, "k", body),
        ElementKind::OwnedCapture { name, body } => {
            out.push_str("c(");
            out.push_str(name);
            out.push(',');
            write_body(out, body);
            out.push(')');
        }
        ElementKind::OneOrMore(inner) => {
            out.push_str("p(");
            write_atomic(out, inner);
            out.push(')');
        }
        ElementKind::BoundedRepeat {
            element: inner,
            min,
            max,
        } => {
            out.push_str(&format!("b({min},"));
            match max {
                Some(max) if *max != i32::MAX as u32 => out.push_str(&max.to_string()),
                _ => out.push('*'),
            }
            out.push(',');
            write_atomic(out, inner);
            out.push(')');
        }
        ElementKind::Separated { element, separator } => {
            out.push_str("s(");
            write_atomic(out, element);
            out.push(',');
            write_atomic(out, separator);
            out.push(')');
        }
    }
}

fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}
