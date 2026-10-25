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
    let mut enum_names = BTreeSet::new();
    for rule in &ir.rules {
        if rule.enum_literals.is_empty() {
            continue;
        }
        validate_enum_type(&rule.name)?;
        if !enum_names.insert(rule.name.as_str()) {
            return Err(fail("E-ENUM: duplicate enum type"));
        }
        let mut names = BTreeSet::new();
        let mut texts = BTreeSet::new();
        for literal in &rule.enum_literals {
            let name = enum_constant_name(literal)?;
            if !texts.insert(literal) {
                return Err(fail("E-ENUM: duplicate enum literal"));
            }
            if !names.insert(name) {
                return Err(fail("E-ENUM: enum constant name collision"));
            }
        }
        let mut actual = Vec::new();
        if !enum_body_literals(&rule.body, &mut actual)
            || actual
                != rule
                    .enum_literals
                    .iter()
                    .map(String::as_str)
                    .collect::<Vec<_>>()
        {
            return Err(fail("E-ENUM: declaration differs from literal rule body"));
        }
        if rule.skip || rule.operator.is_some() || rule.catalog.is_some() {
            return Err(fail("E-ENUM: conflicting enum metadata"));
        }
        if let Some(mapping) = &rule.mapping {
            if ir.rules.iter().any(|other| {
                other.name != rule.name
                    && other
                        .mapping
                        .as_ref()
                        .is_some_and(|m| m.name == mapping.name)
            }) {
                return Err(fail("E-ENUM: enum root wrapper name collision"));
            }
            if mapping.name != format!("{}Node", rule.name)
                || mapping.fields
                    != vec![Field {
                        name: "value".into(),
                        kind: Kind::Enum(rule.name.clone()),
                        cardinality: Cardinality::One,
                    }]
            {
                return Err(fail("E-ENUM: invalid enum root wrapper"));
            }
        }
    }
    let mut names = BTreeSet::new();
    let mut mappings = BTreeMap::new();
    let mut methods = BTreeMap::new();
    for rule in &ir.rules {
        if !rule.type_constraints.is_empty() && (rule.skip || !rule.enum_literals.is_empty()) {
            return Err(fail(
                "E-TYPEOF-MAPPING: @typeof requires a non-skipped @mapping rule",
            ));
        }
        if !rule.type_constraints.is_empty()
            && rule.mapping.is_none()
            && !transparent_constraint_projection(&rule.body)
        {
            return Err(fail(
                "E-TYPEOF-MAPPING: transparent guard must project a single mapped-sum alternative",
            ));
        }
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
        let mut constraint_fields = BTreeMap::new();
        for constraint in &rule.type_constraints {
            for value in [&constraint.capture, &constraint.reference] {
                if let Kind::Nested { leaf, containers } = &value.kind {
                    if value.cardinality != Cardinality::One
                        || containers.len() > 32
                        || containers.contains(&Cardinality::One)
                        || !matches!(
                            leaf.as_ref(),
                            Kind::Text | Kind::Integer | Kind::Node | Kind::Enum(_) | Kind::Value
                        )
                        || rule.operator.is_some()
                    {
                        return Err(fail("E-NESTED-SHAPE: invalid nested constraint schema"));
                    }
                    if let Kind::Enum(name) = leaf.as_ref() {
                        if !enum_names.contains(name.as_str()) {
                            return Err(fail("E-NESTED-SHAPE: unknown nested enum type"));
                        }
                    }
                }
                identifier(&value.name)?;
                if constraint_fields
                    .insert(&value.name, value)
                    .is_some_and(|old| old != value)
                {
                    return Err(fail("E-TYPEOF: inconsistent constraint field"));
                }
                if !captures.contains(&value.name) {
                    return Err(fail("E-TYPEOF-UNKNOWN-CAPTURE: undeclared capture"));
                }
                if value.kind == Kind::NullableNode
                    && !rule
                        .mapping
                        .as_ref()
                        .is_some_and(|m| m.fields.contains(value))
                {
                    return Err(fail("E-ASSOC: NullableNode constraint requires the bound associative left field"));
                }
                if let Kind::Enum(name) = &value.kind {
                    if !enum_names.contains(name.as_str()) {
                        return Err(fail("E-TYPEOF: unknown enum value type"));
                    }
                }
                if rule.mapping.as_ref().is_some_and(|mapping| {
                    mapping
                        .fields
                        .iter()
                        .any(|field| field.name == value.name && field != value)
                }) {
                    return Err(fail("E-TYPEOF: constraint differs from bound field"));
                }
            }
        }
        if let Some(mapping) = &rule.mapping {
            identifier(&mapping.name)?;
            if mapping
                .fields
                .iter()
                .any(|field| field.kind == Kind::NullableNode)
            {
                let expected = vec![
                    Field {
                        name: "left".into(),
                        kind: Kind::NullableNode,
                        cardinality: Cardinality::One,
                    },
                    Field {
                        name: "op".into(),
                        kind: Kind::Text,
                        cardinality: Cardinality::Many,
                    },
                    Field {
                        name: "right".into(),
                        kind: Kind::Node,
                        cardinality: Cardinality::Many,
                    },
                ];
                if mapping.fields != expected
                    || !rule.operator.is_some_and(|op| {
                        matches!(op.associativity, Associativity::Left | Associativity::Right)
                    })
                {
                    return Err(fail(
                        "E-ASSOC: NullableNode requires an associative literal spine",
                    ));
                }
            }

            if has_nested_values(ir)
                && (mapping.name == "NestedValue" || enum_names.contains("NestedValue"))
            {
                return Err(fail(
                    "E-NESTED-SHAPE: NestedValue is reserved for the nested mixed sum type",
                ));
            }
            if enum_names.contains(mapping.name.as_str()) {
                return Err(fail("E-ENUM: enum type name collision"));
            }
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
                if let Kind::Nested { leaf, containers } = &field.kind {
                    if field.cardinality != Cardinality::One
                        || containers.len() > 32
                        || !matches!(
                            **leaf,
                            Kind::Text | Kind::Integer | Kind::Node | Kind::Enum(_) | Kind::Value
                        )
                        || containers.contains(&Cardinality::One)
                        || rule.operator.is_some()
                    {
                        return Err(fail(
                            "E-NESTED-SHAPE: invalid or unsupported nested capture schema",
                        ));
                    }
                    if let Kind::Enum(name) = leaf.as_ref() {
                        if !enum_names.contains(name.as_str()) {
                            return Err(fail("E-NESTED-SHAPE: unknown nested enum type"));
                        }
                    }
                }
                if let Kind::Enum(name) = &field.kind {
                    if !enum_names.contains(name.as_str()) {
                        return Err(fail("E-ENUM: unknown enum field type"));
                    }
                }
                if matches!(field.name.as_str(), "span" | "semantics")
                    || !fields.insert(field.name.clone())
                {
                    return Err(fail(format!("duplicate or reserved field: {}", field.name)));
                }
            }
            fields.extend(
                rule.type_constraints
                    .iter()
                    .flat_map(|constraint| [&constraint.capture, &constraint.reference])
                    .map(|field| field.name.clone()),
            );
            if rule.enum_literals.is_empty() && fields != captures {
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

/// A mapped sum retains its checks but returns the selected child's AST, with no wrapper.
fn transparent_constraint_projection(root: &Expression) -> bool {
    use Expression::*;
    let mut pending = vec![root];
    while let Some(value) = pending.pop() {
        match value {
            Reference(_) => (),
            Sequence(children) if children.len() == 1 => pending.extend(children),
            Choice(children) | LongestChoice(children) | UniqueLongestChoice(children)
                if !children.is_empty() =>
            {
                pending.extend(children)
            }
            PredictiveChoice { alternatives, .. } if !alternatives.is_empty() => {
                pending.extend(alternatives)
            }
            OwnedCapture {
                child: expression, ..
            }
            | CaptureScope(expression)
            | Commit {
                child: expression, ..
            }
            | Label {
                child: expression, ..
            }
            | Capture { expression, .. }
            | Delimited(expression)
            | ValueBoundary(expression)
            | CaptureBoundary {
                child: expression, ..
            }
            | NamePredicate {
                child: expression, ..
            }
            | NameResolutionScope {
                child: expression, ..
            }
            | LexicalContextScope {
                child: expression, ..
            }
            | LexicalTriviaScope {
                child: expression, ..
            }
            | TriviaScope {
                child: expression, ..
            }
            | RuleEffects {
                child: expression, ..
            }
            | CaptureEquality {
                child: expression, ..
            }
            | Recovery {
                child: expression, ..
            } => pending.push(expression),
            _ => return false,
        }
    }
    true
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
                Sequence(children)
                | Choice(children)
                | LongestChoice(children)
                | UniqueLongestChoice(children) => {
                    pending.extend(children);
                }
                PredictiveChoice { alternatives, .. } => pending.extend(alternatives),
                OwnedCapture {
                    child: expression, ..
                }
                | CaptureScope(expression)
                | Commit {
                    child: expression, ..
                }
                | Label {
                    child: expression, ..
                }
                | Capture { expression, .. }
                | OptionalExpr(expression)
                | Delimited(expression)
                | TextValue(expression)
                | ValueBoundary(expression)
                | CaptureBoundary {
                    child: expression, ..
                }
                | RuleEffects {
                    child: expression, ..
                }
                | Recovery {
                    child: expression, ..
                }
                | TriviaScope {
                    child: expression, ..
                }
                | LexicalContextScope {
                    child: expression, ..
                }
                | LexicalTriviaScope {
                    child: expression, ..
                } => pending.push(expression),
                Repeat { child, .. } => pending.push(child),
                Separated { child, separator } => {
                    pending.push(child);
                    pending.push(separator);
                }
                CatchLabels {
                    child, alternative, ..
                } => {
                    pending.push(child);
                    pending.push(alternative);
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
        Recovery { child, tokens, .. } => {
            let distinct: BTreeSet<_> = tokens.iter().collect();
            if tokens.iter().any(String::is_empty) || distinct.len() != tokens.len() {
                return Err(fail("duplicate or empty recovery sync token"));
            }
            expression(child, count, captures)?;
        }
        LexicalToken { expression, .. } => expression.validate().map_err(fail)?,
        NamePredicate {
            child,
            snapshot,
            version,
            capture,
            kind,
        } => {
            name_requirement(snapshot, version)?;
            if !["type", "value", "resolved"].contains(&kind.as_str()) {
                return Err(fail("invalid name predicate kind"));
            }
            let mut local = BTreeSet::new();
            expression(child, count, &mut local)?;
            if !local.contains(capture) {
                return Err(fail("missing name predicate capture"));
            }
            captures.extend(local);
        }
        NameResolutionScope {
            child,
            requirements,
        } => {
            if requirements.len() > 64 {
                return Err(fail("name snapshot limit exceeds 64"));
            }
            let mut ids = BTreeSet::new();
            for (id, version) in requirements {
                name_requirement(id, version)?;
                if !ids.insert(id) {
                    return Err(fail("duplicate name requirement"));
                }
            }
            expression(child, count, captures)?;
        }
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
        CaseInsensitiveToken(s) if s.is_empty() => return Err(fail("empty CI literal")),
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
        Sequence(children)
        | Choice(children)
        | LongestChoice(children)
        | UniqueLongestChoice(children) => {
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
        OwnedCapture { child, .. }
        | CaptureScope(child)
        | Commit { child, .. }
        | Label { child, .. }
        | OptionalExpr(child)
        | Delimited(child)
        | TextValue(child)
        | ValueBoundary(child)
        | CaptureBoundary { child, .. }
        | TriviaScope { child, .. } => expression(child, count, captures)?,
        CatchLabels {
            child,
            alternative,
            labels,
        } => {
            if labels.is_empty() || labels.iter().any(String::is_empty) {
                return Err(fail("E-CATCH-EMPTY: CATCH requires nonempty labels"));
            }
            expression(child, count, captures)?;
            expression(alternative, count, captures)?;
        }
        LexicalContextScope { child, terminals } => {
            if terminals.len() > 256 {
                return Err(fail("lexical context exceeds 256 terminals"));
            }
            let mut seen = std::collections::HashSet::new();
            for (name, literal, definition) in terminals {
                definition.validate().map_err(fail)?;
                if definition.nullable()
                    || !seen.insert((name, literal))
                    || *literal && name.is_empty()
                {
                    return Err(fail("duplicate or nullable lexical context terminal"));
                }
            }
            expression(child, count, captures)?;
        }
        LexicalTriviaScope { child, definition } => {
            definition.validate().map_err(fail)?;
            if definition.nullable() {
                return Err(fail("nullable whitespace definition"));
            }
            expression(child, count, captures)?;
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

fn name_requirement(id: &str, version: &str) -> Result<(), GenerateError> {
    let bytes = id.as_bytes();
    if bytes.is_empty()
        || bytes.len() > 128
        || !bytes[0].is_ascii_alphabetic()
        || bytes
            .iter()
            .any(|c| !(c.is_ascii_alphanumeric() || b"_./-".contains(c)))
        || version.is_empty()
        || version.len() > 128
        || version.bytes().any(|c| !(33..=126).contains(&c))
    {
        return Err(fail("invalid name snapshot identity"));
    }
    Ok(())
}

fn enum_body_literals<'a>(expression: &'a Expression, out: &mut Vec<&'a str>) -> bool {
    match expression {
        Expression::Literal(text) | Expression::LexicalLiteral(text) => {
            out.push(text);
            true
        }
        Expression::Choice(children)
        | Expression::LongestChoice(children)
        | Expression::UniqueLongestChoice(children) => {
            children.iter().all(|child| enum_body_literals(child, out))
        }
        Expression::PredictiveChoice { alternatives, .. } => alternatives
            .iter()
            .all(|child| enum_body_literals(child, out)),
        Expression::Sequence(children) if children.len() == 1 => {
            enum_body_literals(&children[0], out)
        }
        Expression::Delimited(child)
        | Expression::TriviaScope { child, .. }
        | Expression::LexicalTriviaScope { child, .. }
        | Expression::LexicalContextScope { child, .. }
        | Expression::RuleEffects { child, .. }
        | Expression::NameResolutionScope { child, .. }
        | Expression::NamePredicate { child, .. }
        | Expression::Recovery { child, .. } => enum_body_literals(child, out),
        _ => false,
    }
}
