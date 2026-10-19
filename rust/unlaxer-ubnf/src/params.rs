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
//!
//! A value parameter `=p` (#704) is sugar over the existing owned captures: the instance
//! body becomes `CAPTURE_SCOPE(CAPTURE(F(=p), arg) body)` and each use of `p` becomes
//! `SAME_AS(F(=p))`. See docs/call-by-value-arguments.md.
use crate::ast::*;
use crate::{diagnostic, Diagnostic, DiagnosticKind};
use std::collections::{HashMap, HashSet};

/// Total atomic elements in all expanded instances, aligned with the existing
/// 65536-visit traversal limits of the generators.
pub const MAX_EXPANDED_ELEMENTS: usize = 65_536;
/// Inline nesting of the expansion, aligned with [`crate::MAX_NESTING`]: the rule bodies
/// open at a call plus the nesting of the instance body or argument created there, as if
/// every instance were written at its call site. This bounds the expansion recursion
/// (call chain times body nesting) with one fixed budget on both hosts.
pub const MAX_EXPANDED_NESTING: usize = crate::MAX_NESTING;
/// Builtin call spellings that a parameterized rule must not reuse.
const RESERVED: [&str; 4] = ["CAPTURE", "SAME_AS", "CAPTURE_SCOPE", "ERROR"];
/// Placeholder prefix. NUL never occurs in an identifier, so no user name can match.
const CALL_PREFIX: &str = "\u{0}call";
/// The hidden by-name parameter that carries a value argument into its CAPTURE.
const VALUE_PREFIX: &str = "\u{0}value";

/// One declared parameter; `=name` declares a value parameter (#704).
pub(crate) struct Param {
    pub name: String,
    pub by_value: bool,
}

/// The owned capture name of value parameter `param` of `rule`. It contains `(`, `=`
/// and `)`, which no CAPTURE / SAME_AS name written in UBNF (an identifier) can contain,
/// so expansion never shares a binding with a user capture of any grammar.
fn capture_name(rule: &str, param: &str) -> String {
    format!("{rule}(={param})")
}

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
            if tokens.get(j).is_some_and(|t| t.kind == Kind::Symbol('=')) {
                j += 1;
            }
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
    /// For a rule with value parameters: the capture names, indexed like `params`, and the
    /// body `CAPTURE_SCOPE(CAPTURE(F(=p), <hidden p>) ... body)` that instances expand.
    values: Option<(Vec<Option<String>>, RuleBody)>,
}

/// Validate and expand one grammar. `params[i]` belongs to `grammar.rules[i]`.
pub(crate) fn expand(
    source: &str,
    grammar: GrammarDecl,
    declared: Vec<Option<Vec<Param>>>,
    calls: &[Call],
) -> Result<GrammarDecl> {
    if calls.is_empty() && declared.iter().all(Option::is_none) {
        return Ok(grammar);
    }
    let params: Vec<Option<Vec<String>>> = declared
        .iter()
        .map(|list| {
            list.as_ref()
                .map(|l| l.iter().map(|p| p.name.clone()).collect())
        })
        .collect();
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
        let values = declared[index]
            .as_deref()
            .filter(|list| list.iter().any(|p| p.by_value))
            .map(|list| value_template(rule, list));
        defs.insert(
            name,
            Def {
                rule: index,
                params: list,
                values,
            },
        );
    }
    let mut known: HashSet<&str> = tokens.clone();
    known.extend(rules.iter().map(|r| r.name.as_str()));
    // The value parameter names of each definition that owns its name (#704).
    let value_params: Vec<Vec<&str>> = rules
        .iter()
        .enumerate()
        .map(|(index, rule)| match defs.get(rule.name.as_str()) {
            Some(Def {
                rule: owner,
                params,
                values: Some((captures, _)),
            }) if *owner == index => params
                .iter()
                .zip(captures)
                .filter(|(_, capture)| capture.is_some())
                .map(|(name, _)| name.as_str())
                .collect(),
            _ => Vec::new(),
        })
        .collect();
    let mut check = Check {
        source,
        grammar: &grammar.name,
        calls,
        defs: &defs,
        known: &known,
        rules,
        edges: vec![Vec::new(); rules.len()],
        unknown: Vec::new(),
    };
    for (index, rule) in rules.iter().enumerate() {
        let owner = defs
            .get(rule.name.as_str())
            .filter(|d| d.rule == index)
            .map(|_| index);
        let values = &value_params[index];
        let scope = Scope {
            owner,
            params: params[index].as_deref().unwrap_or(&[]),
            call: None,
            rule: rule.span,
            index,
            scoped: (!values.is_empty()).then_some(index),
            values,
        };
        check.body(&rule.body, &scope)?;
    }
    let edges = check.edges;
    let unknown = check.unknown;
    reject_cycles(source, rules, &defs, &edges)?;

    let mut arg_captures = HashSet::new();
    for call in calls {
        for argument in &call.args {
            collect_captures(argument, &mut arg_captures);
        }
    }
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
        level: 0,
        arg_captures,
        scoped: Vec::new(),
        instance_captures: HashMap::new(),
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
    // A written reference that only an expansion name satisfies would reach the
    // instance (and its value bindings) from outside the call that created it.
    if let Some((rule, name, span)) = unknown
        .into_iter()
        .find(|(_, name, _)| expander.names.contains(*name))
    {
        return Err(error(
            source,
            span,
            format!(
                "E-PARAM-UNDEFINED: rule {} references '{name}', which only names a rule created by parameterized expansion",
                rules[rule].name
            ),
        ));
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
    /// Index of the rule being walked.
    index: usize,
    /// The rule with value parameters whose CAPTURE_SCOPE will enclose this position: the
    /// definition being walked, or the callee whose arguments are being walked (#704).
    scoped: Option<usize>,
    /// Value parameter names of the definition being walked.
    values: &'a [&'a str],
}

struct Check<'a> {
    source: &'a str,
    grammar: &'a str,
    calls: &'a [Call],
    defs: &'a HashMap<&'a str, Def<'a>>,
    known: &'a HashSet<&'a str>,
    rules: &'a [RuleDecl],
    edges: Vec<Vec<(usize, Span)>>,
    /// Undefined references outside parameterized definitions, in traversal order; checked
    /// against the expansion names once they are known.
    unknown: Vec<(usize, &'a str, Span)>,
}

impl<'a> Check<'a> {
    fn body(&mut self, body: &'a RuleBody, scope: &Scope) -> Result<()> {
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

    fn atomic(&mut self, element: &'a AtomicElement, scope: &Scope) -> Result<()> {
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
                        scoped: if def.values.is_some() {
                            Some(def.rule)
                        } else {
                            scope.scoped
                        },
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
                } else if !self.known.contains(name.as_str()) {
                    self.unknown.push((scope.index, name, element.span));
                }
            }
            ElementKind::Replay { name } if scope.values.contains(&name.as_str()) => {
                return Err(error(
                    self.source,
                    element.span,
                    format!(
                        "E-PARAM-SHADOW: SAME_AS({name}) in rule {} names value parameter {name}; write {name} to replay its value",
                        self.rules[scope.index].name
                    ),
                ));
            }
            ElementKind::OwnedCapture { name, .. } if scope.scoped.is_some() => {
                return Err(error(
                    self.source,
                    element.span,
                    format!(
                        "E-PARAM-CAPTURE: CAPTURE({name}, ...) cannot appear in the body or call arguments of rule {}, whose value parameters restore every capture when it returns",
                        self.rules[scope.scoped.unwrap()].name
                    ),
                ));
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
    /// Rule bodies open in the expansion recursion, across instance boundaries.
    level: usize,
    /// Code-point spans of the user CAPTUREs written inside call arguments (#704).
    arg_captures: HashSet<(usize, usize)>,
    /// Rules with value parameters whose call arguments are being expanded, innermost last.
    scoped: Vec<usize>,
    /// Per instance, the first argument CAPTURE its body reaches, directly or through the
    /// instances it references (instances are complete before they are referenced).
    instance_captures: HashMap<String, Option<Found>>,
}

/// An argument CAPTURE: its name and span.
type Found = (String, Span);

/// A substituted argument with its measured size and nesting, so that measuring a
/// body never walks into a replacement again.
struct Rep {
    element: AtomicElement,
    size: usize,
    depth: usize,
    /// A value parameter's SAME_AS, which takes the span of each use it replaces.
    at_use: bool,
    /// The first argument CAPTURE the replacement carries (#704).
    capture: Option<Found>,
}

type Env<'a> = HashMap<&'a str, Rep>;

impl Expander<'_> {
    fn body(&mut self, body: &RuleBody, env: &Env) -> Result<RuleBody> {
        // An error aborts the whole expansion, so the level is restored only on success.
        self.level += 1;
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
        self.level -= 1;
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
                    let inline = self.level.saturating_add(depth);
                    if inline > MAX_EXPANDED_NESTING {
                        return Err(error(self.source, call.span, format!(
                            "E-PARAM-LIMIT: argument of rule {} nests {inline} levels; the limit is {MAX_EXPANDED_NESTING}",
                            call.name
                        )));
                    }
                    self.charge(size, call.span)?;
                    let def = &self.defs[call.name.as_str()];
                    let scoped = def.values.is_some();
                    if scoped {
                        self.scoped.push(def.rule);
                    }
                    let mut args = Vec::with_capacity(call.args.len());
                    for argument in &call.args {
                        args.push(self.body(argument, env)?);
                    }
                    if scoped {
                        self.scoped.pop();
                    }
                    ElementKind::RuleRef {
                        namespace: None,
                        name: self.instance(&call.name, args, call.span)?,
                    }
                } else if let Some(replacement) = env.get(name.as_str()) {
                    // A by-name argument relayed into the arguments of a rule with value
                    // parameters would run inside its CAPTURE_SCOPE (#704).
                    if let (Some(&rule), Some((capture, span))) =
                        (self.scoped.last(), &replacement.capture)
                    {
                        return Err(scoped_capture(
                            self.source,
                            *span,
                            capture,
                            &self.rules[rule].name,
                        ));
                    }
                    if replacement.at_use {
                        return Ok(Spanned {
                            kind: replacement.element.kind.clone(),
                            span: element.span,
                        });
                    }
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

    fn first_capture_in(&self, body: &RuleBody) -> Option<Found> {
        body.alternatives
            .iter()
            .flat_map(|sequence| &sequence.elements)
            .find_map(|element| self.first_capture(&element.element))
    }

    /// The first argument CAPTURE in `element` in traversal order, entering referenced
    /// instances through their recorded result (ordinary rules are not entered).
    fn first_capture(&self, element: &AtomicElement) -> Option<Found> {
        match &element.kind {
            ElementKind::OwnedCapture { name, body } => {
                if self
                    .arg_captures
                    .contains(&(element.span.codepoint_start, element.span.codepoint_end))
                {
                    return Some((name.clone(), element.span));
                }
                self.first_capture_in(body)
            }
            ElementKind::RuleRef {
                namespace: None,
                name,
            } => self.instance_captures.get(name).cloned().flatten(),
            ElementKind::RuleRef { .. }
            | ElementKind::Terminal(_)
            | ElementKind::Replay { .. }
            | ElementKind::Error(_) => None,
            ElementKind::CaptureScope(body)
            | ElementKind::Group(body)
            | ElementKind::Optional(body)
            | ElementKind::Repeat(body) => self.first_capture_in(body),
            ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
                self.first_capture(inner)
            }
            ElementKind::Separated { element, separator } => self
                .first_capture(element)
                .or_else(|| self.first_capture(separator)),
        }
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
        let def = &self.defs[callee];
        let rule = &self.rules[def.rule];
        let empty = HashMap::new();
        let hidden: Vec<String> = match &def.values {
            Some((names, _)) => (0..names.len())
                .map(|index| format!("{VALUE_PREFIX}{index}"))
                .collect(),
            None => Vec::new(),
        };
        let mut env: Env = HashMap::with_capacity(args.len());
        for (index, argument) in args.into_iter().enumerate() {
            let element = replacement(argument);
            let (size, depth) = measure_atomic(&element, &empty);
            let capture = self.first_capture(&element);
            let argument = Rep {
                element,
                size,
                depth,
                at_use: false,
                capture,
            };
            let parameter = def.params[index].as_str();
            match def
                .values
                .as_ref()
                .and_then(|(names, _)| names[index].as_ref())
            {
                // The argument goes into the CAPTURE; every use of the parameter replays it.
                Some(capture) => {
                    env.insert(hidden[index].as_str(), argument);
                    env.insert(
                        parameter,
                        Rep {
                            element: Spanned {
                                kind: ElementKind::Replay {
                                    name: capture.clone(),
                                },
                                span: rule.span,
                            },
                            size: 1,
                            depth: 0,
                            at_use: true,
                            capture: None,
                        },
                    );
                }
                None => {
                    env.insert(parameter, argument);
                }
            }
        }
        let template = def.values.as_ref().map_or(&rule.body, |(_, body)| body);
        let (size, depth) = measure_body(template, &env);
        let inline = self.level.saturating_add(depth);
        if inline > MAX_EXPANDED_NESTING {
            return Err(error(self.source, span, format!(
                "E-PARAM-LIMIT: expansion of rule {callee} nests {inline} levels; the limit is {MAX_EXPANDED_NESTING}"
            )));
        }
        self.charge(size, span)?;
        self.by_key.insert(key, name.clone());
        self.names.insert(name.clone());
        let slot = self.instances.len();
        self.instances.push(None);
        let body = self.body(template, &env)?;
        let found = self.first_capture_in(&body);
        self.instance_captures.insert(name.clone(), found);
        self.instances[slot] = Some(RuleDecl {
            annotations: Vec::new(),
            name: name.clone(),
            body,
            span: rule.span,
        });
        Ok(name)
    }
}

fn scoped_capture(source: &str, span: Span, capture: &str, rule: &str) -> Diagnostic {
    error(source, span, format!(
        "E-PARAM-CAPTURE: CAPTURE({capture}, ...) cannot appear in the body or call arguments of rule {rule}, whose value parameters restore every capture when it returns"
    ))
}

/// The spans of the user CAPTUREs written in `body` (generated names never occur here).
fn collect_captures(body: &RuleBody, out: &mut HashSet<(usize, usize)>) {
    for element in body.alternatives.iter().flat_map(|s| &s.elements) {
        collect_captures_atomic(&element.element, out);
    }
}

fn collect_captures_atomic(element: &AtomicElement, out: &mut HashSet<(usize, usize)>) {
    match &element.kind {
        ElementKind::OwnedCapture { body, .. } => {
            out.insert((element.span.codepoint_start, element.span.codepoint_end));
            collect_captures(body, out);
        }
        ElementKind::CaptureScope(body)
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => collect_captures(body, out),
        ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
            collect_captures_atomic(inner, out)
        }
        ElementKind::Separated { element, separator } => {
            collect_captures_atomic(element, out);
            collect_captures_atomic(separator, out);
        }
        ElementKind::RuleRef { .. }
        | ElementKind::Terminal(_)
        | ElementKind::Replay { .. }
        | ElementKind::Error(_) => {}
    }
}

/// `CAPTURE_SCOPE(CAPTURE(F(=p), <hidden p>) ... body)` for a rule with value parameters,
/// written once per definition so that measuring and expanding an instance treat it as an
/// ordinary body. A one-alternative body is spliced after the captures, a choice is
/// grouped. Every synthetic node takes the span of the definition body.
fn value_template(rule: &RuleDecl, list: &[Param]) -> (Vec<Option<String>>, RuleBody) {
    let span = rule.body.span;
    let plain = |kind: ElementKind| AnnotatedElement {
        element: Spanned { kind, span },
        capture: None,
        typeof_constraint: None,
        capture_span: None,
        typeof_span: None,
        span,
    };
    let sequence = |elements: Vec<AnnotatedElement>| RuleBody {
        alternatives: vec![Sequence { elements, span }],
        span,
    };
    let mut names = Vec::with_capacity(list.len());
    let mut elements = Vec::new();
    for (index, param) in list.iter().enumerate() {
        if !param.by_value {
            names.push(None);
            continue;
        }
        let name = capture_name(&rule.name, &param.name);
        elements.push(plain(ElementKind::OwnedCapture {
            name: name.clone(),
            body: sequence(vec![plain(ElementKind::RuleRef {
                namespace: None,
                name: format!("{VALUE_PREFIX}{index}"),
            })]),
        }));
        names.push(Some(name));
    }
    match rule.body.alternatives.as_slice() {
        [only] => elements.extend(only.elements.iter().cloned()),
        _ => elements.push(plain(ElementKind::Group(rule.body.clone()))),
    }
    (
        names,
        sequence(vec![plain(ElementKind::CaptureScope(sequence(elements)))]),
    )
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
