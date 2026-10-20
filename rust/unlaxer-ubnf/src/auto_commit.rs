//! FIRST-set lint of cut-safe alternatives and the opt-in `@autoCommit` rewrite (#702).
//!
//! The same algorithm runs in the Java frontend (`org.unlaxer.dsl.bootstrap.AutoCommit`);
//! docs/auto-commit.md states it and argues why it is sound.
//!
//! A *candidate* is an alternative `e1 e2 … en` (n ≥ 2) of a choice. Its remainder can be
//! committed when `e1` is not nullable and an ordinary failure of the alternative after `e1`
//! consumed a code point `c ∈ FIRST(e1)` cannot be followed by a successful full-input parse:
//! every later alternative must fail on `c`, and every construct enclosing the choice that
//! could resume parsing (choice, optional, repetition, separated list, the continuation of a
//! sequence, every reference site of the rule) must fail as well, up to the root rule.
//! Anything the analysis does not model makes the candidate ineligible.
//!
//! The rewrite wraps every later element that is a plain rule or token reference as
//! `COMMIT(element)` (captures stay inside), which neither the Java nor the Rust CST sees.
//! It runs after parameterized rules are expanded and removes the annotation, so the
//! rewritten AST equals that of the grammar with the same `COMMIT` written by hand.
use crate::ast::*;
use crate::lexical::{LexicalExpression, Op};
use std::collections::{HashMap, HashSet};

/// The rule annotation that opts a rule into the rewrite.
pub const ANNOTATION: &str = "autoCommit";
/// The code of every lint finding.
pub const CODE: &str = "I-AUTO-COMMIT";
/// Walk states explored per candidate; a larger walk leaves the candidate ineligible.
pub const STATE_BUDGET: usize = 100_000;

/// One cut-safe alternative, located in the source.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Finding {
    pub rule: String,
    /// The alternative (sequence) whose remainder can be committed.
    pub span: Span,
    /// Code point offset right after the first element: the cut position.
    pub cut: usize,
    /// The references `@autoCommit` wraps, in order, with their element spans.
    pub commits: Vec<(String, Span)>,
    /// Whether the rule is annotated, so the frontend inserted these commits.
    pub inserted: bool,
}

impl Finding {
    pub fn severity(&self) -> &'static str {
        "info"
    }

    pub fn message(&self) -> String {
        let names = self
            .commits
            .iter()
            .map(|(name, _)| name.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        format!(
            "{CODE}: {}: alternative at CP {}..{} can commit {names} after CP {}",
            self.rule, self.span.codepoint_start, self.span.codepoint_end, self.cut
        )
    }
}

/// Lint every grammar of one source. The findings describe the source as written, before
/// any `@autoCommit` rewrite; positions are those of this single input.
pub fn lint(source: &str) -> Result<Vec<Finding>, crate::Diagnostic> {
    let file = crate::parser::parse(source, crate::lexer::lex(source)?)?;
    Ok(file.grammars.iter().flat_map(findings).collect())
}

/// Findings of one expanded grammar, in rule order and then source order.
pub fn findings(grammar: &GrammarDecl) -> Vec<Finding> {
    let Some(analysis) = Analysis::new(grammar) else {
        return Vec::new();
    };
    analysis
        .candidates()
        .into_iter()
        .map(|candidate| analysis.finding(&candidate))
        .collect()
}

/// Apply `@autoCommit` to every grammar; a file without the annotation is returned unchanged.
pub(crate) fn apply_file(mut file: UbnfFile) -> UbnfFile {
    file.grammars = file.grammars.into_iter().map(apply).collect();
    file
}

/// Insert the commits of annotated rules and remove the annotation.
pub fn apply(mut grammar: GrammarDecl) -> GrammarDecl {
    if !grammar.rules.iter().any(annotated) {
        return grammar;
    }
    let plans: Vec<Candidate> = match Analysis::new(&grammar) {
        Some(analysis) => analysis
            .candidates()
            .into_iter()
            .filter(|candidate| annotated(&grammar.rules[candidate.rule]))
            .collect(),
        None => Vec::new(),
    };
    for (index, rule) in grammar.rules.iter_mut().enumerate() {
        if !annotated(rule) {
            continue;
        }
        rule.annotations
            .retain(|a| !matches!(&a.kind, AnnotationKind::Simple { name } if name == ANNOTATION));
        let wraps: Vec<&Candidate> = plans.iter().filter(|c| c.rule == index).collect();
        if !wraps.is_empty() {
            rewrite_body(&mut rule.body, &mut 0, &wraps);
        }
    }
    grammar
}

fn annotated(rule: &RuleDecl) -> bool {
    rule.annotations
        .iter()
        .any(|a| matches!(&a.kind, AnnotationKind::Simple { name } if name == ANNOTATION))
}

/// Pre-order body numbering identical to [`Analysis::index_body`]. Commits are applied after
/// the children are visited, so the new COMMIT bodies are never numbered.
fn rewrite_body(body: &mut RuleBody, ordinal: &mut usize, wraps: &[&Candidate]) {
    let mine = *ordinal;
    *ordinal += 1;
    for sequence in &mut body.alternatives {
        for annotated in &mut sequence.elements {
            rewrite_element(&mut annotated.element, ordinal, wraps);
        }
    }
    for candidate in wraps.iter().filter(|c| c.ordinal == mine) {
        let sequence = &mut body.alternatives[candidate.alternative];
        for &k in &candidate.wraps {
            let inner = sequence.elements[k].clone();
            let span = inner.span;
            sequence.elements[k] = AnnotatedElement {
                element: AtomicElement {
                    kind: ElementKind::Commit {
                        body: RuleBody {
                            alternatives: vec![Sequence {
                                elements: vec![inner],
                                span,
                            }],
                            span,
                        },
                        message: None,
                    },
                    span,
                },
                capture: None,
                typeof_constraint: None,
                capture_span: None,
                typeof_span: None,
                span,
            };
        }
    }
}

fn rewrite_element(element: &mut AtomicElement, ordinal: &mut usize, wraps: &[&Candidate]) {
    match &mut element.kind {
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => rewrite_body(body, ordinal, wraps),
        ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
            rewrite_element(inner, ordinal, wraps)
        }
        ElementKind::Separated { element, separator } => {
            rewrite_element(element, ordinal, wraps);
            rewrite_element(separator, ordinal, wraps);
        }
        ElementKind::Replay { .. }
        | ElementKind::Terminal(_)
        | ElementKind::RuleRef { .. }
        | ElementKind::Error(_) => {}
    }
}

/// ASCII exactly, everything above as one flag (the runtime FirstSet representation).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
struct Chars {
    ascii: u128,
    non_ascii: bool,
}

impl Chars {
    const NONE: Self = Self {
        ascii: 0,
        non_ascii: false,
    };
    const ANY: Self = Self {
        ascii: u128::MAX,
        non_ascii: true,
    };

    fn of(c: char) -> Self {
        Self::range(c as u32, c as u32)
    }

    fn range(min: u32, max: u32) -> Self {
        let mut chars = Self::NONE;
        let mut c = min;
        while c <= max && c < 128 {
            chars.ascii |= 1u128 << c;
            c += 1;
        }
        chars.non_ascii = max >= 128;
        chars
    }

    fn union(self, other: Self) -> Self {
        Self {
            ascii: self.ascii | other.ascii,
            non_ascii: self.non_ascii || other.non_ascii,
        }
    }

    fn meets(self, other: Self) -> bool {
        self.ascii & other.ascii != 0 || self.non_ascii && other.non_ascii
    }
}

/// The code points a successful consuming match can start with, whether it can succeed
/// without consuming, and whether the construct is not modelled at all.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct First {
    chars: Chars,
    nullable: bool,
    unknown: bool,
}

impl First {
    const BOTTOM: Self = Self {
        chars: Chars::NONE,
        nullable: false,
        unknown: false,
    };
    const EPSILON: Self = Self {
        chars: Chars::NONE,
        nullable: true,
        unknown: false,
    };
    const UNKNOWN: Self = Self {
        chars: Chars::ANY,
        nullable: true,
        unknown: true,
    };

    fn consuming(chars: Chars) -> Self {
        Self {
            chars,
            nullable: false,
            unknown: false,
        }
    }

    fn or_empty(self) -> Self {
        Self {
            nullable: true,
            ..self
        }
    }

    fn choice(self, other: Self) -> Self {
        if self.unknown || other.unknown {
            return Self::UNKNOWN;
        }
        Self {
            chars: self.chars.union(other.chars),
            nullable: self.nullable || other.nullable,
            unknown: false,
        }
    }

    /// Chars at the start of a construct that this prefix precedes: the prefix's own start
    /// set, plus `after` when the prefix can match empty.
    fn before(self, after: Chars) -> Chars {
        if self.nullable {
            self.chars.union(after)
        } else {
            self.chars
        }
    }

    /// Whether the construct certainly fails at a position holding one of `chars`.
    fn excludes(self, chars: Chars) -> bool {
        !self.unknown && !self.nullable && !self.chars.meets(chars)
    }
}

#[derive(Clone, Copy)]
enum BodyParent {
    Rule(usize),
    Elem(usize),
}

#[derive(Clone, Copy)]
enum ElemParent {
    /// Sequence node and element index.
    Seq(usize, usize),
    /// Enclosing element node and slot (0 = repeated item / list item, 1 = separator).
    Elem(usize, usize),
}

enum Node<'a> {
    Body {
        body: &'a RuleBody,
        parent: BodyParent,
        rule: usize,
        ordinal: usize,
        blocked: bool,
    },
    Seq {
        seq: &'a Sequence,
        body: usize,
        index: usize,
    },
    Elem {
        elem: &'a AtomicElement,
        parent: ElemParent,
    },
}

struct Candidate {
    rule: usize,
    ordinal: usize,
    alternative: usize,
    wraps: Vec<usize>,
    span: Span,
    cut: usize,
    names: Vec<(String, Span)>,
}

struct Analysis<'a> {
    grammar: &'a GrammarDecl,
    rules: HashMap<&'a str, usize>,
    tokens: HashMap<&'a str, &'a TokenDecl>,
    ambiguous: HashSet<&'a str>,
    table: Vec<First>,
    opaque: Vec<bool>,
    reachable: Vec<bool>,
    nodes: Vec<Node<'a>>,
    /// Body node to its sequence nodes, in alternative order.
    alternatives: HashMap<usize, Vec<usize>>,
    sites: HashMap<&'a str, Vec<usize>>,
}

/// Settings that leave the code-point semantics of terminals and references unchanged.
fn plain_setting(setting: &GlobalSetting) -> bool {
    match (setting.key.as_str(), &setting.value) {
        ("package" | "ubnf" | "memoSafeToken" | "feature", SettingValue::String(_)) => true,
        ("whitespace", SettingValue::String(value)) => value.trim() == "none",
        _ => false,
    }
}

/// Annotations that leave the rule's recognition as written; anything else makes it opaque.
fn transparent(annotation: &Annotation) -> bool {
    match &annotation.kind {
        AnnotationKind::Root
        | AnnotationKind::Mapping { .. }
        | AnnotationKind::Doc { .. }
        | AnnotationKind::Skip => true,
        AnnotationKind::Simple { name } => name == ANNOTATION,
        _ => false,
    }
}

impl<'a> Analysis<'a> {
    /// `None` when the grammar is outside the modelled subset as a whole.
    fn new(grammar: &'a GrammarDecl) -> Option<Self> {
        if !grammar.settings.iter().all(plain_setting) {
            return None;
        }
        let trivia = grammar.rules.iter().flat_map(|r| &r.annotations).any(|a| {
            matches!(
                a.kind,
                AnnotationKind::Whitespace { .. }
                    | AnnotationKind::Interleave { .. }
                    | AnnotationKind::LexicalContext { .. }
            )
        });
        if trivia {
            return None;
        }
        let roots: Vec<usize> = (0..grammar.rules.len())
            .filter(|&r| {
                grammar.rules[r]
                    .annotations
                    .iter()
                    .any(|a| matches!(a.kind, AnnotationKind::Root))
            })
            .collect();
        let [root] = roots.as_slice() else {
            return None;
        };
        let mut rules = HashMap::new();
        let mut ambiguous = HashSet::new();
        for (index, rule) in grammar.rules.iter().enumerate() {
            if rules.insert(rule.name.as_str(), index).is_some() {
                ambiguous.insert(rule.name.as_str());
            }
        }
        let mut tokens = HashMap::new();
        for token in &grammar.tokens {
            if tokens.insert(token.name.as_str(), token).is_some()
                || rules.contains_key(token.name.as_str())
            {
                ambiguous.insert(token.name.as_str());
            }
        }
        let opaque: Vec<bool> = grammar
            .rules
            .iter()
            .map(|r| !r.annotations.iter().all(transparent))
            .collect();
        let mut analysis = Analysis {
            grammar,
            rules,
            tokens,
            ambiguous,
            table: vec![First::BOTTOM; grammar.rules.len()],
            opaque,
            reachable: vec![false; grammar.rules.len()],
            nodes: Vec::new(),
            alternatives: HashMap::new(),
            sites: HashMap::new(),
        };
        for (index, opaque) in analysis.opaque.iter().enumerate() {
            if *opaque {
                analysis.table[index] = First::UNKNOWN;
            }
        }
        loop {
            let mut changed = false;
            for index in 0..grammar.rules.len() {
                if analysis.opaque[index] {
                    continue;
                }
                let first = analysis.body(&grammar.rules[index].body);
                if first != analysis.table[index] {
                    analysis.table[index] = first;
                    changed = true;
                }
            }
            if !changed {
                break;
            }
        }
        if analysis.left_recursive() {
            return None;
        }
        analysis.reach(*root);
        for index in 0..grammar.rules.len() {
            if analysis.reachable[index] {
                let body = &grammar.rules[index].body;
                let mut ordinal = 0;
                analysis.index_body(body, BodyParent::Rule(index), index, &mut ordinal, false);
            }
        }
        Some(analysis)
    }

    fn rule_of(&self, name: &str) -> Option<usize> {
        if self.ambiguous.contains(name) {
            None
        } else {
            self.rules.get(name).copied()
        }
    }

    fn reference(&self, namespace: &Option<String>, name: &str) -> First {
        if namespace.is_some() || self.ambiguous.contains(name) {
            return First::UNKNOWN;
        }
        if let Some(&rule) = self.rules.get(name) {
            return self.table[rule];
        }
        match self.tokens.get(name) {
            Some(token) => self.token(token, &mut Vec::new()),
            None => First::UNKNOWN,
        }
    }

    fn token(&self, token: &TokenDecl, visiting: &mut Vec<String>) -> First {
        match &token.kind {
            TokenKind::Declarative { expression } => {
                if visiting.contains(&token.name) {
                    return First::UNKNOWN;
                }
                visiting.push(token.name.clone());
                let first = self.lexical(expression, visiting);
                visiting.pop();
                first
            }
            TokenKind::CharRange { min, max } => {
                First::consuming(Chars::range(*min as u32, *max as u32))
            }
            TokenKind::Any | TokenKind::Negation { .. } => First::consuming(Chars::ANY),
            TokenKind::Empty => First::EPSILON,
            _ => First::UNKNOWN,
        }
    }

    fn lexical(&self, expression: &LexicalExpression, visiting: &mut Vec<String>) -> First {
        match expression.op {
            Op::LITERAL => match expression.text.chars().next() {
                Some(c) => First::consuming(Chars::of(c)),
                None => First::EPSILON,
            },
            Op::ANY | Op::XID_IDENTIFIER | Op::EXCEPT => First::consuming(Chars::ANY),
            Op::RANGE => {
                First::consuming(Chars::range(expression.min as u32, expression.max as u32))
            }
            Op::SEQUENCE => sequence(
                expression
                    .children
                    .iter()
                    .map(|child| self.lexical(child, visiting)),
            ),
            Op::CHOICE => expression
                .children
                .iter()
                .map(|child| self.lexical(child, visiting))
                .fold(First::BOTTOM, First::choice),
            Op::REPEAT => {
                let first = self.lexical(&expression.children[0], visiting);
                if expression.min == 0 {
                    first.or_empty()
                } else {
                    first
                }
            }
            Op::REF => {
                if expression.text.contains('.')
                    || self.ambiguous.contains(expression.text.as_str())
                {
                    return First::UNKNOWN;
                }
                match self.tokens.get(expression.text.as_str()) {
                    Some(token) if matches!(token.kind, TokenKind::Declarative { .. }) => {
                        self.token(token, visiting)
                    }
                    _ => First::UNKNOWN,
                }
            }
            _ => First::UNKNOWN,
        }
    }

    fn body(&self, body: &RuleBody) -> First {
        body.alternatives
            .iter()
            .map(|sequence| self.elements(&sequence.elements))
            .fold(First::BOTTOM, First::choice)
    }

    fn elements(&self, elements: &[AnnotatedElement]) -> First {
        let mut acc = First::EPSILON;
        for annotated in elements {
            if !acc.nullable {
                break;
            }
            let first = self.element(&annotated.element);
            if first.unknown {
                return First::UNKNOWN;
            }
            acc = First {
                chars: acc.chars.union(first.chars),
                nullable: first.nullable,
                unknown: false,
            };
        }
        acc
    }

    fn element(&self, element: &AtomicElement) -> First {
        match &element.kind {
            ElementKind::Terminal(value) => match value.chars().next() {
                Some(c) => First::consuming(Chars::of(c)),
                None => First::EPSILON,
            },
            ElementKind::RuleRef { namespace, name } => self.reference(namespace, name),
            ElementKind::Group(body) => self.body(body),
            ElementKind::Optional(body) | ElementKind::Repeat(body) => self.body(body).or_empty(),
            ElementKind::OneOrMore(inner) => self.element(inner),
            ElementKind::BoundedRepeat { element, min, .. } => {
                let first = self.element(element);
                if *min == 0 {
                    first.or_empty()
                } else {
                    first
                }
            }
            ElementKind::Separated { element, .. } => self.element(element),
            _ => First::UNKNOWN,
        }
    }

    /// Whether some rule can call itself again without consuming input.
    fn left_recursive(&self) -> bool {
        let calls: Vec<Vec<usize>> = self
            .grammar
            .rules
            .iter()
            .map(|rule| {
                let mut out = Vec::new();
                self.left_body(&rule.body, &mut out);
                out
            })
            .collect();
        // 0 = unvisited, 1 = on the stack, 2 = done.
        let mut state = vec![0u8; calls.len()];
        for start in 0..calls.len() {
            if state[start] != 0 {
                continue;
            }
            let mut stack = vec![(start, 0usize)];
            state[start] = 1;
            while let Some(&mut (rule, ref mut next)) = stack.last_mut() {
                if let Some(&callee) = calls[rule].get(*next) {
                    *next += 1;
                    match state[callee] {
                        0 => {
                            state[callee] = 1;
                            stack.push((callee, 0));
                        }
                        1 => return true,
                        _ => {}
                    }
                } else {
                    state[rule] = 2;
                    stack.pop();
                }
            }
        }
        false
    }

    fn left_body(&self, body: &RuleBody, out: &mut Vec<usize>) {
        for sequence in &body.alternatives {
            for annotated in &sequence.elements {
                self.left_element(&annotated.element, out);
                let first = self.element(&annotated.element);
                if !first.unknown && !first.nullable {
                    break;
                }
            }
        }
    }

    fn left_element(&self, element: &AtomicElement, out: &mut Vec<usize>) {
        match &element.kind {
            ElementKind::RuleRef {
                namespace: None,
                name,
            } => {
                if let Some(rule) = self.rule_of(name) {
                    out.push(rule);
                }
            }
            ElementKind::OwnedCapture { body, .. }
            | ElementKind::CaptureScope(body)
            | ElementKind::Commit { body, .. }
            | ElementKind::Label { body, .. }
            | ElementKind::Catch { choice: body, .. }
            | ElementKind::Group(body)
            | ElementKind::Optional(body)
            | ElementKind::Repeat(body) => self.left_body(body, out),
            ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
                self.left_element(inner, out)
            }
            ElementKind::Separated { element, separator } => {
                self.left_element(element, out);
                let first = self.element(element);
                if first.unknown || first.nullable {
                    self.left_element(separator, out);
                }
            }
            _ => {}
        }
    }

    fn reach(&mut self, root: usize) {
        let mut work = vec![root];
        self.reachable[root] = true;
        while let Some(rule) = work.pop() {
            let mut names = Vec::new();
            references(&self.grammar.rules[rule].body, &mut names);
            for name in names {
                if let Some(callee) = self.rule_of(name) {
                    if !self.reachable[callee] {
                        self.reachable[callee] = true;
                        work.push(callee);
                    }
                }
            }
        }
    }

    fn index_body(
        &mut self,
        body: &'a RuleBody,
        parent: BodyParent,
        rule: usize,
        ordinal: &mut usize,
        blocked: bool,
    ) {
        let id = self.nodes.len();
        self.nodes.push(Node::Body {
            body,
            parent,
            rule,
            ordinal: *ordinal,
            blocked,
        });
        *ordinal += 1;
        for (index, sequence) in body.alternatives.iter().enumerate() {
            let seq = self.nodes.len();
            self.nodes.push(Node::Seq {
                seq: sequence,
                body: id,
                index,
            });
            self.alternatives.entry(id).or_default().push(seq);
            for (k, annotated) in sequence.elements.iter().enumerate() {
                self.index_element(
                    &annotated.element,
                    ElemParent::Seq(seq, k),
                    rule,
                    ordinal,
                    blocked,
                );
            }
        }
    }

    fn index_element(
        &mut self,
        element: &'a AtomicElement,
        parent: ElemParent,
        rule: usize,
        ordinal: &mut usize,
        blocked: bool,
    ) {
        let id = self.nodes.len();
        self.nodes.push(Node::Elem {
            elem: element,
            parent,
        });
        match &element.kind {
            ElementKind::RuleRef {
                namespace: None,
                name,
            } => self.sites.entry(name.as_str()).or_default().push(id),
            ElementKind::OwnedCapture { body, .. }
            | ElementKind::CaptureScope(body)
            | ElementKind::Commit { body, .. }
            | ElementKind::Label { body, .. }
            | ElementKind::Catch { choice: body, .. } => {
                self.index_body(body, BodyParent::Elem(id), rule, ordinal, true)
            }
            ElementKind::Group(body) | ElementKind::Optional(body) | ElementKind::Repeat(body) => {
                self.index_body(body, BodyParent::Elem(id), rule, ordinal, blocked)
            }
            ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
                self.index_element(inner, ElemParent::Elem(id, 0), rule, ordinal, blocked)
            }
            ElementKind::Separated {
                element: item,
                separator,
            } => {
                self.index_element(item, ElemParent::Elem(id, 0), rule, ordinal, blocked);
                self.index_element(separator, ElemParent::Elem(id, 1), rule, ordinal, blocked);
            }
            _ => {}
        }
    }

    fn candidates(&self) -> Vec<Candidate> {
        let mut out = Vec::new();
        for (id, node) in self.nodes.iter().enumerate() {
            let Node::Body {
                body,
                rule,
                ordinal,
                blocked: false,
                ..
            } = node
            else {
                continue;
            };
            if self.opaque[*rule] {
                continue;
            }
            for (alternative, sequence) in body.alternatives.iter().enumerate() {
                let elements = &sequence.elements;
                if elements.len() < 2 {
                    continue;
                }
                let first = self.element(&elements[0].element);
                if first.unknown || first.nullable {
                    continue;
                }
                let wraps: Vec<usize> = (1..elements.len())
                    .filter(|&k| self.wrappable(&elements[k]))
                    .collect();
                if wraps.is_empty() {
                    continue;
                }
                let seq = self.alternatives[&id][alternative];
                if !self.safe(seq, first.chars) {
                    continue;
                }
                out.push(Candidate {
                    rule: *rule,
                    ordinal: *ordinal,
                    alternative,
                    span: sequence.span,
                    cut: elements[0].span.codepoint_end,
                    names: wraps
                        .iter()
                        .map(|&k| match &elements[k].element.kind {
                            ElementKind::RuleRef { name, .. } => (name.clone(), elements[k].span),
                            _ => unreachable!("only references are wrapped"),
                        })
                        .collect(),
                    wraps,
                });
            }
        }
        out
    }

    fn finding(&self, candidate: &Candidate) -> Finding {
        let rule = &self.grammar.rules[candidate.rule];
        Finding {
            rule: rule.name.clone(),
            span: candidate.span,
            cut: candidate.cut,
            commits: candidate.names.clone(),
            inserted: annotated(rule),
        }
    }

    /// A plain reference to one rule or token of this grammar, without `@typeof`.
    fn wrappable(&self, annotated: &AnnotatedElement) -> bool {
        annotated.typeof_constraint.is_none()
            && matches!(&annotated.element.kind, ElementKind::RuleRef { namespace: None, name }
                if !self.ambiguous.contains(name.as_str())
                    && (self.rules.contains_key(name.as_str()) || self.tokens.contains_key(name.as_str())))
    }

    /// Whether an ordinary failure of alternative `seq`, at a position holding one of `chars`,
    /// always ends in a failed full-input parse of the root rule.
    fn safe(&self, seq: usize, chars: Chars) -> bool {
        let mut seen = HashSet::new();
        let mut work = vec![(false, seq, chars)];
        while let Some(state) = work.pop() {
            if !seen.insert(state) {
                continue;
            }
            if seen.len() > STATE_BUDGET || !self.step(state, &mut work) {
                return false;
            }
        }
        true
    }

    /// One transition. `next == false`: the node failed ordinarily at its start, holding one of
    /// `chars`. `next == true`: the node succeeded and the next code point is one of `chars`.
    fn step(
        &self,
        (next, node, chars): (bool, usize, Chars),
        work: &mut Vec<(bool, usize, Chars)>,
    ) -> bool {
        match self.nodes[node] {
            Node::Body { body, parent, .. } => match parent {
                BodyParent::Rule(rule) => {
                    if self.opaque[rule] {
                        return false;
                    }
                    let name = self.grammar.rules[rule].name.as_str();
                    for &site in self.sites.get(name).map(Vec::as_slice).unwrap_or_default() {
                        work.push((next, site, chars));
                    }
                    true
                }
                BodyParent::Elem(element) => {
                    let Node::Elem { elem, .. } = self.nodes[element] else {
                        unreachable!("a body parent is an element")
                    };
                    match elem.kind {
                        ElementKind::Group(_) => work.push((next, element, chars)),
                        ElementKind::Optional(_) => work.push((true, element, chars)),
                        ElementKind::Repeat(_) => {
                            if next && !self.body(body).excludes(chars) {
                                return false;
                            }
                            work.push((true, element, chars));
                        }
                        _ => return false,
                    }
                    true
                }
            },
            Node::Seq { body, index, .. } => {
                if !next {
                    let Node::Body { body: choice, .. } = self.nodes[body] else {
                        unreachable!("a sequence parent is a body")
                    };
                    for later in &choice.alternatives[index + 1..] {
                        if !self.elements(&later.elements).excludes(chars) {
                            return false;
                        }
                    }
                }
                work.push((next, body, chars));
                true
            }
            Node::Elem { parent, .. } => match parent {
                ElemParent::Seq(seq, k) => {
                    let Node::Seq { seq: sequence, .. } = self.nodes[seq] else {
                        unreachable!("an element parent is a sequence")
                    };
                    let elements = &sequence.elements;
                    if !next {
                        let prefix = self.elements(&elements[..k]);
                        if prefix.unknown {
                            return false;
                        }
                        work.push((false, seq, prefix.before(chars)));
                        return true;
                    }
                    if k + 1 < elements.len() {
                        let prefix = self.elements(&elements[..=k]);
                        if prefix.unknown {
                            return false;
                        }
                        let mut nullable = true;
                        for annotated in &elements[k + 1..] {
                            let first = self.element(&annotated.element);
                            if first.unknown || first.chars.meets(chars) {
                                return false;
                            }
                            if !first.nullable {
                                nullable = false;
                                break;
                            }
                        }
                        work.push((false, seq, prefix.before(chars)));
                        if !nullable {
                            return true;
                        }
                    }
                    work.push((true, seq, chars));
                    true
                }
                ElemParent::Elem(owner, slot) => {
                    let Node::Elem { elem, .. } = self.nodes[owner] else {
                        unreachable!("an element parent is an element")
                    };
                    match &elem.kind {
                        ElementKind::OneOrMore(inner) => {
                            self.repeat(next, owner, inner, 1, chars, work)
                        }
                        ElementKind::BoundedRepeat {
                            element: inner,
                            min,
                            ..
                        } => self.repeat(next, owner, inner, *min, chars, work),
                        ElementKind::Separated {
                            element: item,
                            separator,
                        } => {
                            let separator = self.element(separator);
                            match (next, slot) {
                                (false, 0) => {
                                    if separator.unknown {
                                        return false;
                                    }
                                    work.push((false, owner, chars));
                                    work.push((true, owner, separator.before(chars)));
                                }
                                (false, _) => work.push((true, owner, chars)),
                                (true, 0) => {
                                    if !separator.excludes(chars) {
                                        return false;
                                    }
                                    work.push((true, owner, chars));
                                }
                                (true, _) => {
                                    if !self.element(item).excludes(chars) || separator.unknown {
                                        return false;
                                    }
                                    work.push((true, owner, separator.before(chars)));
                                }
                            }
                            true
                        }
                        _ => false,
                    }
                }
            },
        }
    }

    fn repeat(
        &self,
        next: bool,
        owner: usize,
        inner: &AtomicElement,
        min: u32,
        chars: Chars,
        work: &mut Vec<(bool, usize, Chars)>,
    ) -> bool {
        let first = self.element(inner);
        if first.unknown || next && !first.excludes(chars) {
            return false;
        }
        work.push((true, owner, chars));
        // The repetition fails as a whole when fewer than `min` items matched: after a failed
        // item (`!next`) the count may be anything below, after a successful one at least 1.
        let below_min = if next { min >= 2 } else { min >= 1 };
        if below_min {
            work.push((false, owner, first.chars.union(chars)));
        }
        true
    }
}

fn sequence(firsts: impl Iterator<Item = First>) -> First {
    let mut acc = First::EPSILON;
    for first in firsts {
        if !acc.nullable {
            break;
        }
        if first.unknown {
            return First::UNKNOWN;
        }
        acc = First {
            chars: acc.chars.union(first.chars),
            nullable: first.nullable,
            unknown: false,
        };
    }
    acc
}

fn references<'b>(body: &'b RuleBody, out: &mut Vec<&'b str>) {
    for sequence in &body.alternatives {
        for annotated in &sequence.elements {
            element_references(&annotated.element, out);
        }
    }
}

fn element_references<'b>(element: &'b AtomicElement, out: &mut Vec<&'b str>) {
    match &element.kind {
        ElementKind::RuleRef {
            namespace: None,
            name,
        } => out.push(name),
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => references(body, out),
        ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
            element_references(inner, out)
        }
        ElementKind::Separated { element, separator } => {
            element_references(element, out);
            element_references(separator, out);
        }
        _ => {}
    }
}
