//! Validation of COMMIT / LABEL / CATCH (#700), mirroring the Java `RaisingOperators`.
use std::collections::{BTreeSet, HashMap, HashSet, VecDeque};
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
    // Without COMMIT or LABEL nothing raises, so no repetition is analysed (or rejected).
    let raising = walk
        .sites
        .iter()
        .any(|site| {
            matches!(
                site.element.kind,
                ElementKind::Commit { .. } | ElementKind::Label { .. }
            )
        })
        .then(|| Raising::new(grammar, &walk));
    let mut problems = Vec::new();
    for site in &walk.sites {
        let problem = |code, subject: String| Problem {
            code,
            rule: site.rule.to_owned(),
            span: site.element.span,
            subject,
        };
        if site.catching.is_some() {
            if raising
                .as_ref()
                .is_some_and(|raising| raising.uncaught(site))
            {
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
/// repetition is rejected only when no reference context can catch any kind it may raise, and
/// only when every successful item consumes input. Each fixed point is a worklist over the
/// reference graph, so the work is linear in the references times the number of kinds.
struct Raising<'a> {
    rules: HashMap<&'a str, &'a RuleDecl>,
    /// References into each rule, and out of each rule (indices into the walk's references).
    into: HashMap<&'a str, Vec<usize>>,
    out: HashMap<&'a str, Vec<usize>>,
    /// Rules that may match the empty string; a token or an unknown reference counts as nullable.
    nullable: HashSet<&'a str>,
    /// Kinds a rule may raise: every COMMIT and LABEL in it and in the rules it references.
    raisable: HashMap<&'a str, BTreeSet<&'a str>>,
    /// Labels caught around some reference path to a rule.
    catchable: HashMap<&'a str, BTreeSet<&'a str>>,
    /// Rules on some reference path below a `@recovery` rule, recovery rules included.
    recovered: HashSet<&'a str>,
    /// Rules whose every outcome is a raised failure.
    always: HashSet<&'a str>,
    /// Rules that never fail ordinarily and consume input whenever they succeed.
    only: HashSet<&'a str>,
}

impl<'a> Raising<'a> {
    fn new(grammar: &'a GrammarDecl, walk: &Walk<'a>) -> Self {
        let mut order: Vec<&'a str> = Vec::new();
        let mut rules = HashMap::new();
        for rule in &grammar.rules {
            if !rules.contains_key(rule.name.as_str()) {
                rules.insert(rule.name.as_str(), rule);
                order.push(rule.name.as_str());
            }
        }
        let mut raising = Self {
            rules,
            into: HashMap::new(),
            out: HashMap::new(),
            nullable: HashSet::new(),
            raisable: HashMap::new(),
            catchable: HashMap::new(),
            recovered: HashSet::new(),
            always: HashSet::new(),
            only: HashSet::new(),
        };
        for name in &order {
            raising.into.insert(name, Vec::new());
            raising.out.insert(name, Vec::new());
            raising.raisable.insert(name, BTreeSet::new());
            raising.catchable.insert(name, BTreeSet::new());
        }
        for (index, reference) in walk.references.iter().enumerate() {
            if raising.rules.contains_key(reference.to) {
                raising.into.get_mut(reference.to).unwrap().push(index);
                raising.out.get_mut(reference.from).unwrap().push(index);
            }
        }
        // Nullable rules: re-examine the referrers of each rule that becomes nullable.
        let mut work: VecDeque<&'a str> = order.iter().copied().collect();
        while let Some(name) = work.pop_front() {
            if raising.nullable.contains(name) || !raising.nullable_body(&raising.rules[name].body)
            {
                continue;
            }
            raising.nullable.insert(name);
            work.extend(raising.into[name].iter().map(|&i| walk.references[i].from));
        }
        // Raisable kinds flow backwards along references.
        for site in &walk.sites {
            let kind = match &site.element.kind {
                ElementKind::Commit { .. } => COMMIT,
                ElementKind::Label { label, .. } => label,
                _ => continue,
            };
            raising.raisable.get_mut(site.rule).unwrap().insert(kind);
        }
        work.extend(order.iter().copied());
        while let Some(name) = work.pop_front() {
            let kinds = raising.raisable[name].clone();
            for &index in &raising.into[name] {
                let from = walk.references[index].from;
                let source = raising.raisable.get_mut(from).unwrap();
                let before = source.len();
                source.extend(kinds.iter().copied());
                if source.len() != before {
                    work.push_back(from);
                }
            }
        }
        // Catchable labels and recovery flow forwards along references.
        for name in &order {
            if recovery(raising.rules[name]).is_some() {
                raising.recovered.insert(name);
            }
        }
        work.extend(order.iter().copied());
        while let Some(name) = work.pop_front() {
            let labels = raising.catchable[name].clone();
            let recovered = raising.recovered.contains(name);
            for &index in &raising.out[name] {
                let reference = &walk.references[index];
                let target = raising.catchable.get_mut(reference.to).unwrap();
                let before = target.len();
                target.extend(reference.catching.iter().copied());
                target.extend(labels.iter().copied());
                let mut changed = target.len() != before;
                if recovered {
                    changed |= raising.recovered.insert(reference.to);
                }
                if changed {
                    work.push_back(reference.to);
                }
            }
        }
        // Always / only: re-examine the referrers of each rule whose status changes.
        work.extend(order.iter().copied());
        while let Some(name) = work.pop_front() {
            let rule = raising.rules[name];
            let recovery = recovery(rule);
            let mut changed = false;
            if !raising.always.contains(name)
                && raising.always_body(&rule.body)
                && (recovery.is_none() || !raising.raisable[name].contains(COMMIT))
            {
                changed |= raising.always.insert(name);
            }
            if !raising.only.contains(name)
                && raising.only_body(&rule.body)
                && (recovery.is_none() || recovery == Some(RecoveryMode::Sync))
            {
                changed |= raising.only.insert(name);
            }
            if changed {
                work.extend(raising.into[name].iter().map(|&i| walk.references[i].from));
            }
        }
        raising
    }

    fn uncaught(&self, site: &Site<'a>) -> bool {
        let Some(item) = repeated(site.element) else {
            return false;
        };
        if !self.only_raises(item) {
            return false;
        }
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

    /// Whether a body may match the empty string (an over-approximation).
    fn nullable_body(&self, body: &RuleBody) -> bool {
        body.alternatives.iter().any(|sequence| {
            sequence
                .elements
                .iter()
                .all(|annotated| self.nullable_element(&annotated.element))
        })
    }

    fn nullable_element(&self, element: &AtomicElement) -> bool {
        match &element.kind {
            ElementKind::Terminal(value) => value.is_empty(),
            ElementKind::RuleRef { namespace, name } => {
                namespace.is_some()
                    || !self.rules.contains_key(name.as_str())
                    || self.nullable.contains(name.as_str())
            }
            ElementKind::Error(_) => false,
            ElementKind::OwnedCapture { body, .. }
            | ElementKind::CaptureScope(body)
            | ElementKind::Group(body)
            | ElementKind::Commit { body, .. }
            | ElementKind::Label { body, .. }
            | ElementKind::Catch { choice: body, .. } => self.nullable_body(body),
            ElementKind::OneOrMore(item) => self.nullable_element(item),
            ElementKind::BoundedRepeat { element, min, .. } => {
                *min == 0 || self.nullable_element(element)
            }
            ElementKind::Separated { element, .. } => self.nullable_element(element),
            // optional, repetition, replay
            ElementKind::Optional(_) | ElementKind::Repeat(_) | ElementKind::Replay { .. } => true,
        }
    }

    /// Never fails ordinarily, and consumes input whenever it succeeds.
    fn only_raises(&self, element: &AtomicElement) -> bool {
        match &element.kind {
            ElementKind::Commit { body, .. } | ElementKind::Label { body, .. } => {
                !self.nullable_body(body)
            }
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
