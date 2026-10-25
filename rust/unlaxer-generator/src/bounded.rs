//! Validate every bounded repetition before lowering, including dead definitions.
use unlaxer_ubnf::{AtomicElement, ElementKind, GrammarDecl, RuleBody};

pub(crate) fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    let mut pending = Vec::new();
    for rule in grammar.rules.iter().rev() {
        elements(&rule.body, &mut pending);
    }
    while let Some(node) = pending.pop() {
        match &node.kind {
            ElementKind::BoundedRepeat { element, min, max } => {
                if *min > i32::MAX as u32 || max.is_some_and(|n| n > i32::MAX as u32) {
                    return Err(format!(
                        "E-BOUNDED-OVERFLOW: repetition bound exceeds 2147483647 at CP {}..{}",
                        node.span.codepoint_start, node.span.codepoint_end
                    ));
                }
                pending.push(element);
            }
            ElementKind::Group(body)
            | ElementKind::Optional(body)
            | ElementKind::Repeat(body)
            | ElementKind::OwnedCapture { body, .. }
            | ElementKind::CaptureScope(body)
            | ElementKind::Commit { body, .. }
            | ElementKind::Label { body, .. }
            | ElementKind::Catch { choice: body, .. } => elements(body, &mut pending),
            ElementKind::OneOrMore(child) => pending.push(child),
            ElementKind::Separated { element, separator } => {
                pending.push(separator);
                pending.push(element);
            }
            _ => {}
        }
    }
    Ok(())
}

fn elements<'a>(body: &'a RuleBody, pending: &mut Vec<&'a AtomicElement>) {
    for alternative in body.alternatives.iter().rev() {
        for element in alternative.elements.iter().rev() {
            pending.push(&element.element);
        }
    }
}
