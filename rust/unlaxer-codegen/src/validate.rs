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

pub(super) fn valid_custom_token_path(path: &str) -> bool {
    let parts: Vec<_> = path.split("::").collect();
    parts.len() >= 2
        && parts.iter().enumerate().all(|(index, part)| {
            let mut chars = part.bytes();
            *part != "_"
                && chars
                    .next()
                    .is_some_and(|first| first.is_ascii_alphabetic() || first == b'_')
                && chars.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
                && (index == 0 && matches!(*part, "crate" | "self" | "super")
                    || !matches!(
                        *part,
                        "as" | "async"
                            | "await"
                            | "break"
                            | "const"
                            | "continue"
                            | "crate"
                            | "dyn"
                            | "else"
                            | "enum"
                            | "extern"
                            | "false"
                            | "fn"
                            | "for"
                            | "if"
                            | "impl"
                            | "in"
                            | "let"
                            | "loop"
                            | "match"
                            | "mod"
                            | "move"
                            | "mut"
                            | "pub"
                            | "ref"
                            | "return"
                            | "self"
                            | "Self"
                            | "static"
                            | "struct"
                            | "super"
                            | "trait"
                            | "true"
                            | "type"
                            | "unsafe"
                            | "use"
                            | "where"
                            | "while"
                            | "abstract"
                            | "become"
                            | "box"
                            | "do"
                            | "final"
                            | "gen"
                            | "macro"
                            | "override"
                            | "priv"
                            | "try"
                            | "typeof"
                            | "unsized"
                            | "virtual"
                            | "yield"
                            | "union"
                    ))
        })
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
        if rule.skip && rule.mapping.is_some() {
            return Err(fail(format!("skipped rule has mapping: {}", rule.name)));
        }
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
    if mappings.is_empty() && !root_reaches_projection_boundary(ir) {
        return Err(fail("at least one mapped rule is required"));
    }
    Ok(())
}

/// A parser-only grammar is valid only when the root can reach a projection
/// boundary. Mapped rules stop traversal; their children are private to that AST.
fn root_reaches_projection_boundary(ir: &GrammarIr) -> bool {
    use Expression::*;
    let mut pending_rules = vec![ir.root];
    let mut visited = BTreeSet::new();
    while let Some(id) = pending_rules.pop() {
        if !visited.insert(id) {
            continue;
        }
        let rule = &ir.rules[id];
        if rule.skip {
            return true;
        }
        if rule.mapping.is_some() {
            continue;
        }
        let mut pending = vec![&rule.body];
        while let Some(expression) = pending.pop() {
            match expression {
                CaptureEquality { .. } => return true,
                Reference(id) => pending_rules.push(*id),
                Sequence(children) | Choice(children) | LongestChoice(children) => {
                    pending.extend(children);
                }
                PredictiveChoice { alternatives, .. } => pending.extend(alternatives),
                Capture { expression, .. }
                | OptionalExpr(expression)
                | Delimited(expression)
                | TextValue(expression)
                | ValueBoundary(expression)
                | RuleEffects {
                    child: expression, ..
                }
                | TriviaScope {
                    child: expression, ..
                } => pending.push(expression),
                Repeat { child, .. } => pending.push(child),
                Separated { child, separator } => {
                    pending.push(child);
                    pending.push(separator);
                }
                _ => {}
            }
        }
    }
    false
}

fn expression(
    expr: &Expression,
    count: usize,
    captures: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    use Expression::*;
    match expr {
        LexicalToken { expression, .. } => expression.validate().map_err(fail)?,
        CaptureEquality { child, name } => {
            let mut local = BTreeSet::new();
            expression(child, count, &mut local)?;
            if !local.contains(name) {
                return Err(fail(format!("missing capture-equality target: {name}")));
            }
            captures.extend(local);
        }
        RuleEffects { child, effects } => {
            let mut local = BTreeSet::new();
            expression(child, count, &mut local)?;
            for target in effects
                .declares
                .iter()
                .map(|decl| &decl.symbol_capture)
                .chain(effects.backref.iter())
            {
                if !local.contains(target) {
                    return Err(fail(format!("missing rule-effect capture: {target}")));
                }
            }
            captures.extend(local);
        }
        Reference(id) if *id >= count => {
            return Err(fail(format!("rule reference out of range: {id}")))
        }
        QuotedToken(q) if !matches!(q, '\'' | '"') => {
            return Err(fail("quoted token must use single or double quote"))
        }
        CustomToken(path) if !valid_custom_token_path(path) => {
            return Err(fail(format!("invalid custom token function path: {path}")))
        }
        CharRangeToken { min, max } if min > max || u32::from(*max) > 0xffff => {
            return Err(fail("character range must be ordered BMP scalars"))
        }
        Literal(s) if s.is_empty() => return Err(fail("empty literal")),
        Capture {
            name,
            expression: child,
        } => {
            // Captures are emitted as string labels, not Rust field names. Mapping
            // fields are validated separately and retain the stricter identifier rules.
            let mut chars = name.chars();
            if !chars
                .next()
                .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
                || !chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
            {
                return Err(fail(format!("unsupported capture name: {name}")));
            }
            captures.insert(name.clone());
            expression(child, count, captures)?;
        }
        Sequence(children) | Choice(children) | LongestChoice(children) => {
            if children.is_empty() {
                return Err(fail("empty sequence/choice"));
            }
            for child in children {
                expression(child, count, captures)?;
            }
        }
        PredictiveChoice {
            alternatives,
            predictors,
        } => {
            if alternatives.is_empty() || alternatives.len() != predictors.len() {
                return Err(fail("predictive choice alternatives/predictors mismatch"));
            }
            for child in alternatives {
                expression(child, count, captures)?;
            }
        }
        OptionalExpr(child)
        | Delimited(child)
        | TextValue(child)
        | ValueBoundary(child)
        | TriviaScope { child, .. } => expression(child, count, captures)?,
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
