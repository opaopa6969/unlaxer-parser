//! Validation of COMMIT / LABEL / CATCH (#700), mirroring the Java `RaisingOperators`.
use std::collections::{BTreeSet, HashSet};
use unlaxer_ubnf::{AtomicElement, ElementKind, GrammarDecl, RuleBody, Span};

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
    let mut declared = HashSet::new();
    let mut sites = Vec::new();
    for rule in &grammar.rules {
        body(&rule.name, &rule.body, &mut declared, &mut sites);
    }
    let mut problems = Vec::new();
    for (rule, element) in sites {
        let problem = |code, subject: String| Problem {
            code,
            rule: rule.to_owned(),
            span: element.span,
            subject,
        };
        if raising_repetition(element) {
            problems.push(problem(
                "E-RAISING-REPETITION",
                "unbounded repetition of COMMIT or LABEL always ends with that failure".into(),
            ));
            continue;
        }
        match &element.kind {
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
                    } else if !declared.contains(label.as_str()) {
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

fn body<'a>(
    rule: &'a str,
    value: &'a RuleBody,
    declared: &mut HashSet<&'a str>,
    sites: &mut Vec<(&'a str, &'a AtomicElement)>,
) {
    for sequence in &value.alternatives {
        for annotated in &sequence.elements {
            element(rule, &annotated.element, declared, sites);
        }
    }
}

/// An unbounded repetition whose whole body is one COMMIT or LABEL: the attempt that finds
/// no further item raises instead of ending the repetition, so it can never succeed.
fn raising_repetition(element: &AtomicElement) -> bool {
    let item = match &element.kind {
        ElementKind::Repeat(body) => match body.alternatives.as_slice() {
            [sequence] => match sequence.elements.as_slice() {
                [item] => Some(&item.element),
                _ => None,
            },
            _ => None,
        },
        ElementKind::OneOrMore(item)
        | ElementKind::BoundedRepeat {
            element: item,
            max: None,
            ..
        } => Some(&**item),
        _ => None,
    };
    item.is_some_and(|item| {
        matches!(
            item.kind,
            ElementKind::Commit { .. } | ElementKind::Label { .. }
        )
    })
}

fn element<'a>(
    rule: &'a str,
    value: &'a AtomicElement,
    declared: &mut HashSet<&'a str>,
    sites: &mut Vec<(&'a str, &'a AtomicElement)>,
) {
    if raising_repetition(value) {
        sites.push((rule, value));
    }
    match &value.kind {
        ElementKind::Catch { choice, .. } => {
            sites.push((rule, value));
            // The stored choice is both operands in order.
            body(rule, choice, declared, sites);
        }
        ElementKind::Label { label, body: inner } => {
            declared.insert(label);
            sites.push((rule, value));
            body(rule, inner, declared, sites);
        }
        ElementKind::Commit { body: inner, .. } => {
            sites.push((rule, value));
            body(rule, inner, declared, sites);
        }
        ElementKind::OwnedCapture { body: inner, .. }
        | ElementKind::CaptureScope(inner)
        | ElementKind::Group(inner)
        | ElementKind::Optional(inner)
        | ElementKind::Repeat(inner) => body(rule, inner, declared, sites),
        ElementKind::OneOrMore(inner) | ElementKind::BoundedRepeat { element: inner, .. } => {
            element(rule, inner, declared, sites)
        }
        ElementKind::Separated {
            element: item,
            separator,
        } => {
            element(rule, item, declared, sites);
            element(rule, separator, declared, sites);
        }
        _ => {}
    }
}
