//! Validation of COMMIT / LABEL / CATCH (#700), mirroring the Java `RaisingOperators`.
use std::collections::{BTreeSet, HashMap, HashSet};
use unlaxer_ubnf::{
    AnnotatedElement, AnnotationKind, AtomicElement, ElementKind, GrammarDecl, RecoveryMode,
    RuleBody, RuleDecl, Span,
};

/// The kind of a committed error among the raised kinds; labels are identifiers, so it never
/// collides with one.
const COMMIT: &str = "#commit";

/// One rejected operator, located by the offending element's span.
pub struct Problem {
    pub code: &'static str,
    pub rule: String,
    pub span: Span,
    pub subject: String,
}

impl Problem {
    pub fn message(&self) -> String {
        format!(
            "{}: {}: {} at CP {}..{}",
            self.code, self.rule, self.subject, self.span.codepoint_start, self.span.codepoint_end
        )
    }
}

pub fn problems(grammar: &GrammarDecl) -> Vec<Problem> {
    let mut walk = Walk::default();
    for rule in &grammar.rules {
        walk.body(&rule.name, &rule.body, &BTreeSet::new());
    }
    let raising = Raising::new(grammar, &walk);
    let mut problems = Vec::new();
    for site in &walk.sites {
        let problem = |code, subject: String| Problem {
            code,
            rule: site.rule.to_owned(),
            span: site.element.span,
            subject,
        };
        if site.catching.is_some() {
            if raising.uncaught(site) {
                problems.push(problem(
                    "E-RAISING-REPETITION",
                    "unbounded repetition of COMMIT or LABEL always ends with that failure".into(),
                ));
            }
            continue;
        }
        match &site.element.kind {
            ElementKind::Commit {
                message: Some(message),
                ..
            } if message.is_empty() => problems.push(problem(
                "E-COMMIT-EMPTY-MESSAGE",
                "COMMIT message must not be empty".into(),
            )),
            ElementKind::Catch { operands, .. } if operands.labels.is_empty() => problems.push(
                problem("E-CATCH-EMPTY", "CATCH requires at least one label".into()),
            ),
            ElementKind::Catch { operands, .. } => {
                let mut seen = BTreeSet::new();
                for label in &operands.labels {
                    if !seen.insert(label) {
                        problems.push(problem(
                            "E-CATCH-DUPLICATE-LABEL",
                            format!("duplicate CATCH label {label}"),
                        ));
                    } else if !walk.declared.contains(label.as_str()) {
                        problems.push(problem(
                            "E-CATCH-UNKNOWN-LABEL",
                            format!("CATCH label {label} is not raised by any LABEL"),
                        ));
                    }
                }
            }
            _ => {}
        }
    }
    problems
}

/// Generation rejects an invalid grammar before emitting anything.
pub fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    let problems = problems(grammar);
    if problems.is_empty() {
        return Ok(());
    }
    Err(problems
        .iter()
        .map(Problem::message)
        .collect::<Vec<_>>()
        .join("; "))
}

/// An operator site. `catching` is `None` except for an unbounded repetition of a COMMIT,
/// LABEL or local rule reference, where it holds the labels of the CATCH bodies enclosing it
/// within its rule.
struct Site<'a> {
    rule: &'a str,
    element: &'a AtomicElement,
    catching: Option<BTreeSet<&'a str>>,
}

/// A local rule reference in `from`, inside CATCH bodies of `from` catching `catching`.
struct Reference<'a> {
    from: &'a str,
    to: &'a str,
    catching: BTreeSet<&'a str>,
}

/// Collects operator sites, rule references and the labels declared anywhere; same walk as Java.
#[derive(Default)]
struct Walk<'a> {
    declared: HashSet<&'a str>,
    sites: Vec<Site<'a>>,
    references: Vec<Reference<'a>>,
}

impl<'a> Walk<'a> {
    fn body(&mut self, rule: &'a str, value: &'a RuleBody, catching: &BTreeSet<&'a str>) {
        for sequence in &value.alternatives {
            for annotated in &sequence.elements {
                self.element(rule, &annotated.element, catching);
            }
        }
    }

    fn site(&mut self, rule: &'a str, element: &'a AtomicElement) {
        self.sites.push(Site {
            rule,
            element,
            catching: None,
        });
    }

    fn element(&mut self, rule: &'a str, value: &'a AtomicElement, catching: &BTreeSet<&'a str>) {
        if repeated(value).is_some_and(|item| {
            matches!(
                item.kind,
                ElementKind::Commit { .. }
                    | ElementKind::Label { .. }
                    | ElementKind::RuleRef {
                        namespace: None,
                        ..
                    }
            )
        }) {
            self.sites.push(Site {
                rule,
                element: value,
                catching: Some(catching.clone()),
            });
        }
        match &value.kind {
            ElementKind::Catch { choice, operands } => {
                self.site(rule, value);
                // The stored choice is both operands in order. Only the body operand is
                // caught; the alternative runs after the catch.
                let mut inner = catching.clone();
                inner.extend(operands.labels.iter().map(String::as_str));
                for (index, sequence) in choice.alternatives.iter().enumerate() {
                    let scope = if index < operands.split {
                        &inner
                    } else {
                        catching
                    };
                    for annotated in &sequence.elements {
                        self.element(rule, &annotated.element, scope);
                    }
                }
            }
            ElementKind::Label { label, body: inner } => {
                self.declared.insert(label);
                self.site(rule, value);
                self.body(rule, inner, catching);
            }
            ElementKind::Commit { body: inner, .. } => {
                self.site(rule, value);
                self.body(rule, inner, catching);
            }
            ElementKind::RuleRef {
                namespace: None,
                name,
            } => self.references.push(Reference {
                from: rule,
                to: name,
                catching: catching.clone(),
            }),
            ElementKind::OwnedCapture { body: inner, .. }
            | ElementKind::CaptureScope(inner)
            | ElementKind::Group(inner)
            | ElementKind::Optional(inner)
            | ElementKind::Repeat(inner) => self.body(rule, inner, catching),
            ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
                self.element(rule, inner, catching)
            }
            ElementKind::Separated {
                element: item,
                separator,
            } => {
                self.element(rule, item, catching);
                self.element(rule, separator, catching);
            }
            _ => {}
        }
    }
}

/// Where a raised failure can be caught (#722), as in Java. A labeled failure `L` is caught
/// only by a CATCH listing `L` whose body operand encloses the raise, in the same rule or
/// around a reference on some path to the rule; a committed error only by a `@recovery` rule,
/// the raising rule itself or one on some path to it. The analysis is conservative: a
/// repetition is rejected only when no reference context can catch any kind it may raise.
struct Raising<'a> {
    rules: Vec<&'a RuleDecl>,
    known: HashSet<&'a str>,
    /// Kinds a rule may raise: every COMMIT and LABEL in it and in the rules it references.
    raisable: HashMap<&'a str, BTreeSet<&'a str>>,
    /// Labels caught around some reference path to a rule.
    catchable: HashMap<&'a str, BTreeSet<&'a str>>,
    /// Rules on some reference path below a `@recovery` rule, recovery rules included.
    recovered: HashSet<&'a str>,
    /// Rules whose every outcome is a raised failure.
    always: HashSet<&'a str>,
    /// Rules that never fail ordinarily: they succeed or raise.
    only: HashSet<&'a str>,
}

impl<'a> Raising<'a> {
    fn new(grammar: &'a GrammarDecl, walk: &Walk<'a>) -> Self {
        let mut known = HashSet::new();
        let rules: Vec<&RuleDecl> = grammar
            .rules
            .iter()
            .filter(|rule| known.insert(rule.name.as_str()))
            .collect();
        let mut raising = Self {
            rules,
            known,
            raisable: HashMap::new(),
            catchable: HashMap::new(),
            recovered: HashSet::new(),
            always: HashSet::new(),
            only: HashSet::new(),
        };
        for rule in &raising.rules {
            raising.raisable.insert(&rule.name, BTreeSet::new());
            raising.catchable.insert(&rule.name, BTreeSet::new());
            if recovery(rule).is_some() {
                raising.recovered.insert(&rule.name);
            }
        }
        for site in &walk.sites {
            let kind = match &site.element.kind {
                ElementKind::Commit { .. } => COMMIT,
                ElementKind::Label { label, .. } => label,
                _ => continue,
            };
            raising.raisable.get_mut(site.rule).unwrap().insert(kind);
        }
        let mut changed = true;
        while changed {
            changed = false;
            for reference in &walk.references {
                if let Some(target) = raising.raisable.get(reference.to).cloned() {
                    let source = raising.raisable.get_mut(reference.from).unwrap();
                    let before = source.len();
                    source.extend(target);
                    changed |= source.len() != before;
                }
            }
        }
        changed = true;
        while changed {
            changed = false;
            for reference in &walk.references {
                if !raising.known.contains(reference.to) {
                    continue;
                }
                let mut added = reference.catching.clone();
                added.extend(raising.catchable[reference.from].iter().copied());
                let target = raising.catchable.get_mut(reference.to).unwrap();
                let before = target.len();
                target.extend(added);
                changed |= target.len() != before;
                if raising.recovered.contains(reference.from) {
                    changed |= raising.recovered.insert(reference.to);
                }
            }
        }
        changed = true;
        while changed {
            changed = false;
            for index in 0..raising.rules.len() {
                let rule = raising.rules[index];
                let recovery = recovery(rule);
                if raising.always_body(&rule.body)
                    && (recovery.is_none()
                        || !raising.raisable[rule.name.as_str()].contains(COMMIT))
                {
                    changed |= raising.always.insert(&rule.name);
                }
                if raising.only_body(&rule.body)
                    && (recovery.is_none() || recovery == Some(RecoveryMode::Sync))
                {
                    changed |= raising.only.insert(&rule.name);
                }
            }
        }
        raising
    }

    fn uncaught(&self, site: &Site<'a>) -> bool {
        let Some(item) = repeated(site.element) else {
            return false;
        };
        let mut kinds = BTreeSet::new();
        match &item.kind {
            ElementKind::Commit { body, .. } => {
                kinds.insert(COMMIT);
                kinds.extend(self.raised_in(body));
            }
            ElementKind::Label { label, body } => {
                kinds.insert(label.as_str());
                kinds.extend(self.raised_in(body));
            }
            // A rule that always raises is reported inside it; here the repetition makes it certain.
            ElementKind::RuleRef { name, .. }
                if self.only.contains(name.as_str()) && !self.always.contains(name.as_str()) =>
            {
                kinds.extend(self.raisable[name.as_str()].iter().copied());
            }
            _ => return false,
        }
        let catching = site.catching.as_ref().unwrap();
        !kinds.iter().any(|kind| {
            if *kind == COMMIT {
                self.recovered.contains(site.rule)
            } else {
                catching.contains(kind) || self.catchable[site.rule].contains(kind)
            }
        })
    }

    /// The kinds raised in a body: its COMMITs and LABELs and those of the rules it references.
    fn raised_in(&self, body: &'a RuleBody) -> BTreeSet<&'a str> {
        let mut walk = Walk::default();
        walk.body("", body, &BTreeSet::new());
        let mut kinds = BTreeSet::new();
        for site in &walk.sites {
            match &site.element.kind {
                ElementKind::Commit { .. } => {
                    kinds.insert(COMMIT);
                }
                ElementKind::Label { label, .. } => {
                    kinds.insert(label.as_str());
                }
                _ => {}
            }
        }
        for reference in &walk.references {
            if let Some(referenced) = self.raisable.get(reference.to) {
                kinds.extend(referenced.iter().copied());
            }
        }
        kinds
    }

    fn only_raises(&self, element: &AtomicElement) -> bool {
        match &element.kind {
            ElementKind::Commit { .. } | ElementKind::Label { .. } => true,
            ElementKind::RuleRef {
                namespace: None,
                name,
            } if self.only.contains(name.as_str()) => true,
            _ => self.always_raises(element),
        }
    }

    fn always_raises(&self, element: &AtomicElement) -> bool {
        if let Some(item) = repeated(element) {
            return self.only_raises(item);
        }
        matches!(&element.kind, ElementKind::RuleRef { namespace: None, name }
            if self.always.contains(name.as_str()))
    }

    fn only_body(&self, body: &RuleBody) -> bool {
        match sole(body) {
            Some([single]) => self.only_raises(&single.element),
            Some([first, ..]) => self.always_raises(&first.element),
            _ => false,
        }
    }

    fn always_body(&self, body: &RuleBody) -> bool {
        sole(body).is_some_and(|elements| self.always_raises(&elements[0].element))
    }
}

fn recovery(rule: &RuleDecl) -> Option<RecoveryMode> {
    rule.annotations
        .iter()
        .find_map(|annotation| match &annotation.kind {
            AnnotationKind::Recovery { mode, .. } => Some(*mode),
            _ => None,
        })
}

/// The elements of a body with exactly one nonempty alternative.
fn sole(body: &RuleBody) -> Option<&[AnnotatedElement]> {
    match body.alternatives.as_slice() {
        [sequence] if !sequence.elements.is_empty() => Some(&sequence.elements),
        _ => None,
    }
}

/// The item of an unbounded repetition (`{ X }`, `X*`, `X+`, `X{n,}`).
fn repeated(element: &AtomicElement) -> Option<&AtomicElement> {
    match &element.kind {
        ElementKind::Repeat(body) => match sole(body) {
            Some([item]) => Some(&item.element),
            _ => None,
        },
        ElementKind::OneOrMore(item)
        | ElementKind::BoundedRepeat {
            element: item,
            max: None,
            ..
        } => Some(item),
        _ => None,
    }
}
