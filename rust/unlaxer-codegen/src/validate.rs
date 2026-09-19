use crate::*;
use std::collections::{BTreeMap, BTreeSet};

fn fail(message: impl Into<String>) -> GenerateError {
    GenerateError(message.into())
}

fn identifier(name: &str) -> Result<(), GenerateError> {
    let mut chars = name.chars();
    if !chars.next().is_some_and(|c| c.is_ascii_alphabetic())
        || !chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
        || matches!(name, "Self" | "self" | "super" | "crate")
    {
        return Err(fail(format!("unsupported identifier: {name}")));
    }
    Ok(())
}

pub(super) fn validate(ir: &GrammarIr) -> Result<(), GenerateError> {
    if ir.root >= ir.rules.len() {
        return Err(fail("root rule index out of range"));
    }
    let mut names = BTreeSet::new();
    let mut mappings = BTreeMap::new();
    let mut methods = BTreeMap::new();
    for rule in &ir.rules {
        // Rule names are diagnostic labels, not emitted Rust identifiers.
        // References use numeric indices, so frontend identifiers such as
        // `_Root` and Rust keywords must remain valid here.
        if rule.name.is_empty() {
            return Err(fail("empty rule name"));
        }
        if !names.insert(&rule.name) {
            return Err(fail(format!("duplicate rule: {}", rule.name)));
        }
        let mut captures = BTreeSet::new();
        expression(&rule.body, ir.rules.len(), &mut captures)?;
        if let Some(mapping) = &rule.mapping {
            identifier(&mapping.name)?;
            if mappings
                .insert(&mapping.name, mapping)
                .is_some_and(|old| old != mapping)
            {
                return Err(fail(format!(
                    "conflicting mapping schema: {}",
                    mapping.name
                )));
            }
            if methods
                .insert(method_name(&mapping.name), &mapping.name)
                .is_some_and(|old| old != &mapping.name)
            {
                return Err(fail(format!(
                    "semantic method name collision: {}",
                    mapping.name
                )));
            }
            let mut fields = BTreeSet::new();
            for field in &mapping.fields {
                identifier(&field.name)?;
                if matches!(field.name.as_str(), "span" | "semantics")
                    || !fields.insert(field.name.clone())
                {
                    return Err(fail(format!("duplicate or reserved field: {}", field.name)));
                }
            }
            if fields != captures {
                return Err(fail(format!(
                    "mapping fields differ from captures: {}",
                    rule.name
                )));
            }
        }
    }
    if mappings.is_empty() {
        return Err(fail("at least one mapped rule is required"));
    }
    Ok(())
}

fn expression(
    expr: &Expression,
    count: usize,
    captures: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    use Expression::*;
    match expr {
        Reference(id) if *id >= count => {
            return Err(fail(format!("rule reference out of range: {id}")))
        }
        QuotedToken(q) if !matches!(q, '\'' | '"') => {
            return Err(fail("quoted token must use single or double quote"))
        }
        CharRangeToken { min, max } if min > max || u32::from(*max) > 0xffff => {
            return Err(fail("character range must be ordered BMP scalars"))
        }
        Literal(s) if s.is_empty() => return Err(fail("empty literal")),
        Capture {
            name,
            expression: child,
        } => {
            identifier(name)?;
            captures.insert(name.clone());
            expression(child, count, captures)?;
        }
        Sequence(children) | Choice(children) => {
            if children.is_empty() {
                return Err(fail("empty sequence/choice"));
            }
            for child in children {
                expression(child, count, captures)?;
            }
        }
        OptionalExpr(child) | Delimited(child) | TextValue(child) | ValueBoundary(child) => {
            expression(child, count, captures)?
        }
        Repeat { child, min, max } => {
            if *min > i32::MAX as usize
                || max.is_some_and(|max| max < *min || max > i32::MAX as usize)
            {
                return Err(fail("invalid repetition bounds"));
            }
            expression(child, count, captures)?;
        }
        Separated { child, separator } => {
            expression(child, count, captures)?;
            let mut separator_captures = BTreeSet::new();
            expression(separator, count, &mut separator_captures)?;
            if !separator_captures.is_empty() {
                return Err(fail("captures in separator are unsupported"));
            }
        }
        _ => {}
    }
    Ok(())
}
