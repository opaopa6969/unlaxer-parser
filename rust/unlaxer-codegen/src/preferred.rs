//! Internal CST metadata for the source-compatible preferred-candidate parser.
use crate::{ir::*, GenerateError};

pub const DEPTH: &str = "#preferred-depth:";
pub const EXTRA: &str = "#preferred-extra:2";

/// Emit a second parser with validated source-site depth metadata; normal IR and CST stay unchanged.
pub fn parser(
    ir: &GrammarIr,
    calls: &[Vec<(usize, usize)>],
    marker: &str,
) -> Result<String, GenerateError> {
    crate::validate_ir(ir)?;
    if calls.len() != ir.rules.len() {
        return Err(error());
    }
    let mut copy = ir.clone();
    for (rule, calls) in copy.rules.iter_mut().zip(calls) {
        let mut iter = calls.iter();
        instrument(&mut rule.body, &mut iter)?;
        if iter.next().is_some() {
            return Err(error());
        }
    }
    // Tag every rule so explicitly selected alternate entries retain provenance too.
    for rule in &mut copy.rules {
        let body = if let Expression::NameResolutionScope { child, .. } = &mut rule.body {
            child.as_mut()
        } else {
            &mut rule.body
        };
        *body = Expression::Capture {
            name: marker.into(),
            expression: Box::new(body.clone()),
        };
    }
    Ok(crate::parser(&copy))
}
fn error() -> GenerateError {
    GenerateError("E-PREFERRED: source call sites do not match lowered references".into())
}
fn instrument(
    expr: &mut Expression,
    calls: &mut std::slice::Iter<'_, (usize, usize)>,
) -> Result<(), GenerateError> {
    use Expression::*;
    match expr {
        Reference(target) => {
            let Some((expected, depth)) = calls.next() else {
                return Err(error());
            };
            if target != expected || *depth == 0 || *depth > 4096 {
                return Err(error());
            }
            *expr = Capture {
                name: format!("{DEPTH}{depth}"),
                expression: Box::new(Reference(*target)),
            };
        }
        Capture { expression, .. } => instrument(expression, calls)?,
        OptionalExpr(child)
        | Delimited(child)
        | TextValue(child)
        | ValueBoundary(child)
        | CaptureBoundary { child, .. }
        | OwnedCapture { child, .. }
        | CaptureScope(child)
        | Repeat { child, .. }
        | RuleEffects { child, .. }
        | CaptureEquality { child, .. }
        | Recovery { child, .. }
        | TriviaScope { child, .. }
        | LexicalTriviaScope { child, .. }
        | LexicalContextScope { child, .. }
        | NamePredicate { child, .. }
        | NameResolutionScope { child, .. } => instrument(child, calls)?,
        Sequence(items)
        | Choice(items)
        | LongestChoice(items)
        | UniqueLongestChoice(items)
        | PredictiveChoice {
            alternatives: items,
            ..
        } => {
            for item in items {
                instrument(item, calls)?;
            }
        }
        Separated { child, separator } => {
            instrument(child, calls)?;
            instrument(separator, calls)?;
        }
        _ => {}
    }
    Ok(())
}

/// Inspect metadata captures structurally; user literal text may contain the reserved prefix.
pub(crate) fn contains_depth(expr: &Expression) -> bool {
    use Expression::*;
    match expr {
        Capture { name, expression } => name.starts_with(DEPTH) || contains_depth(expression),
        OptionalExpr(child)
        | Delimited(child)
        | TextValue(child)
        | ValueBoundary(child)
        | CaptureBoundary { child, .. }
        | OwnedCapture { child, .. }
        | CaptureScope(child)
        | Repeat { child, .. }
        | RuleEffects { child, .. }
        | CaptureEquality { child, .. }
        | Recovery { child, .. }
        | TriviaScope { child, .. }
        | LexicalTriviaScope { child, .. }
        | LexicalContextScope { child, .. }
        | NamePredicate { child, .. }
        | NameResolutionScope { child, .. } => contains_depth(child),
        Sequence(items)
        | Choice(items)
        | LongestChoice(items)
        | UniqueLongestChoice(items)
        | PredictiveChoice {
            alternatives: items,
            ..
        } => items.iter().any(contains_depth),
        Separated { child, separator } => contains_depth(child) || contains_depth(separator),
        _ => false,
    }
}
