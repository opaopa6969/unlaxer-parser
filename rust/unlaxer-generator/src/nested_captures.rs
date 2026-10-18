use std::collections::{BTreeMap, HashSet};
use unlaxer_codegen::{BoundaryKind, Cardinality, Expression, Kind};
use unlaxer_ubnf::{
    AnnotationKind, AtomicElement, ElementKind, GrammarDecl, RuleBody, RuleDecl, TokenKind,
};

pub fn enabled(rule: &RuleDecl) -> bool {
    rule.annotations
        .iter()
        .any(|a| matches!(&a.kind, AnnotationKind::Simple { name } if name == "nestedCaptures"))
}
fn error(rule: &RuleDecl, detail: &str) -> String {
    format!(
        "E-NESTED-SHAPE: {}: {detail} at CP {}..{}",
        rule.name, rule.span.codepoint_start, rule.span.codepoint_end
    )
}
pub fn sites(rule: &RuleDecl) -> Result<BTreeMap<String, &AtomicElement>, String> {
    fn body<'a>(
        rule: &RuleDecl,
        value: &'a RuleBody,
        inside: bool,
        depth: usize,
        result: &mut BTreeMap<String, &'a AtomicElement>,
    ) -> Result<(), String> {
        if depth > 64 {
            return Err(error(rule, "capture traversal depth exceeds 64"));
        }
        for sequence in &value.alternatives {
            for element in &sequence.elements {
                if element.typeof_constraint.is_some() {
                    return Err(error(rule, "typeof requires remaining #583 implementation"));
                }
                if let Some(name) = &element.capture {
                    if inside || result.insert(name.clone(), &element.element).is_some() {
                        return Err(error(rule, "one outer capture site required"));
                    }
                }
                atom(
                    rule,
                    &element.element,
                    inside || element.capture.is_some(),
                    depth + 1,
                    result,
                )?;
            }
        }
        Ok(())
    }
    fn atom<'a>(
        rule: &RuleDecl,
        value: &'a AtomicElement,
        inside: bool,
        depth: usize,
        result: &mut BTreeMap<String, &'a AtomicElement>,
    ) -> Result<(), String> {
        if depth > 64 {
            return Err(error(rule, "capture traversal depth exceeds 64"));
        }
        match &value.kind {
            ElementKind::OwnedCapture { body: v, .. }
            | ElementKind::CaptureScope(v)
            | ElementKind::Group(v) => body(rule, v, inside, depth + 1, result)?,
            ElementKind::Optional(v) | ElementKind::Repeat(v) => {
                body(rule, v, true, depth + 1, result)?
            }
            ElementKind::OneOrMore(v) | ElementKind::BoundedRepeat { element: v, .. } => {
                atom(rule, v, true, depth + 1, result)?
            }
            ElementKind::Separated { element, separator } => {
                atom(rule, element, true, depth + 1, result)?;
                atom(rule, separator, true, depth + 1, result)?;
            }
            _ => (),
        }
        Ok(())
    }
    let mut result = BTreeMap::new();
    body(rule, &rule.body, false, 0, &mut result)?;
    Ok(result)
}
pub fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    let mut checked_mappings = HashSet::new();
    let mut validated = Vec::new();
    for rule in &grammar.rules {
        if !enabled(rule) {
            continue;
        }
        if rule
            .annotations
            .iter()
            .filter(|a| matches!(&a.kind,AnnotationKind::Simple{name} if name=="nestedCaptures"))
            .count()
            != 1
        {
            return Err(error(rule, "duplicate annotation"));
        }
        if grammar.settings.iter().any(|s| s.key == "tokenStream") {
            return Err(error(
                rule,
                "token stream requires remaining #583 implementation",
            ));
        }
        let mapping = rule
            .annotations
            .iter()
            .find_map(|a| {
                if let AnnotationKind::Mapping { class_name, params } = &a.kind {
                    Some((class_name, params))
                } else {
                    None
                }
            })
            .ok_or_else(|| error(rule, "@mapping required"))?;
        if rule.annotations.iter().any(|a| {
            matches!(
                a.kind,
                AnnotationKind::LeftAssoc
                    | AnnotationKind::RightAssoc
                    | AnnotationKind::Skip
                    | AnnotationKind::Enum
                    | AnnotationKind::Eval { .. }
            )
        }) {
            return Err(error(
                rule,
                "combination requires remaining #583 implementation",
            ));
        }
        if !mapping.1.is_empty()
            && rule
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::CommonField { .. }))
        {
            return Err(error(rule, "commonField requires a mapped sum family"));
        }
        let values = sites(rule)?;
        if values.len() != mapping.1.len() || !mapping.1.iter().all(|p| values.contains_key(p)) {
            return Err(error(rule, "mapping must name each unique outer capture"));
        }
        for value in values.values() {
            shape(grammar, value, 0).map_err(|e| error(rule, &e))?;
        }
        validated.push((rule, mapping, values));
    }
    // Cross-rule comparison only follows bounded validation of every contributor.
    for (rule, mapping, values) in validated {
        if !checked_mappings.insert(mapping.0.clone()) {
            continue;
        }
        for candidate in &grammar.rules {
            // Skipped rules do not contribute a public mapping, matching Java's schema.
            if candidate
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Skip))
            {
                continue;
            }
            let Some(params) = candidate.annotations.iter().find_map(|a| match &a.kind {
                AnnotationKind::Mapping { class_name, params } if class_name == mapping.0 => {
                    Some(params)
                }
                _ => None,
            }) else {
                continue;
            };
            if std::ptr::eq(candidate, rule) {
                continue;
            }
            if !enabled(candidate) || params != mapping.1 {
                return Err(error(
                    rule,
                    &format!(
                        "shared nested mapping requires identical opt-in and parameters: {}",
                        candidate.name
                    ),
                ));
            }
            let other_sites = sites(candidate)?;
            for name in mapping.1 {
                let own = values[name];
                let Some(other) = other_sites.get(name) else {
                    return Err(error(rule, "shared nested mapping is missing a capture"));
                };
                if shape(grammar, own, 0).map_err(|e| error(rule, &e))?
                    != shape(grammar, other, 0).map_err(|e| error(rule, &e))?
                    || nominal_leaf(grammar, own) != nominal_leaf(grammar, other)
                {
                    return Err(error(
                        rule,
                        &format!(
                            "shared nested mapping requires identical shape for {name} on {}",
                            candidate.name
                        ),
                    ));
                }
            }
        }
    }
    Ok(())
}
// Rust stores every mapped node in Ast; sharing must still respect Java's nominal leaf type.
fn nominal_leaf(grammar: &GrammarDecl, atom: &AtomicElement) -> Option<String> {
    match &atom.kind {
        ElementKind::RuleRef { name, .. } => grammar
            .rules
            .iter()
            .find(|r| r.name == *name)
            .and_then(|r| {
                r.annotations.iter().find_map(|a| match &a.kind {
                    AnnotationKind::Mapping { class_name, .. } => Some(class_name.clone()),
                    _ => None,
                })
            }),
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => {
            let [sequence] = body.alternatives.as_slice() else {
                return None;
            };
            let [element] = sequence.elements.as_slice() else {
                return None;
            };
            nominal_leaf(grammar, &element.element)
        }
        ElementKind::OneOrMore(child)
        | ElementKind::BoundedRepeat { element: child, .. }
        | ElementKind::Separated { element: child, .. } => nominal_leaf(grammar, child),
        _ => None,
    }
}
fn parts(shape: Kind) -> (Kind, Vec<Cardinality>) {
    if let Kind::Nested { leaf, containers } = shape {
        (*leaf, containers)
    } else {
        (shape, Vec::new())
    }
}
fn wrap(container: Cardinality, inner: Kind) -> Kind {
    let (leaf, mut containers) = parts(inner);
    containers.insert(0, container);
    Kind::Nested {
        leaf: Box::new(leaf),
        containers,
    }
}
pub fn shape(grammar: &GrammarDecl, atom: &AtomicElement, depth: usize) -> Result<Kind, String> {
    if depth > 32 {
        return Err("schema depth exceeds 32".into());
    }
    Ok(match &atom.kind {
        ElementKind::Separated { element, .. } => {
            wrap(Cardinality::Many, shape(grammar, element, depth + 1)?)
        }
        ElementKind::Optional(v) => wrap(Cardinality::Optional, body_shape(grammar, v, depth + 1)?),
        ElementKind::Repeat(v) => wrap(Cardinality::Many, body_shape(grammar, v, depth + 1)?),
        ElementKind::OneOrMore(v) | ElementKind::BoundedRepeat { element: v, .. } => {
            wrap(Cardinality::Many, shape(grammar, v, depth + 1)?)
        }
        ElementKind::OwnedCapture { body: v, .. }
        | ElementKind::CaptureScope(v)
        | ElementKind::Group(v) => body_shape(grammar, v, depth + 1)?,
        ElementKind::Terminal(_) | ElementKind::Replay { .. } => Kind::Text,
        ElementKind::RuleRef { name, .. } => {
            let token = grammar.tokens.iter().find(|t| &t.name == name);
            if let Some(token) = token {
                if matches!(&token.kind,TokenKind::Simple{parser_class} if matches!(parser_class.rsplit('.').next(),Some("NumberParser"|"DigitParser")))
                {
                    Kind::Integer
                } else {
                    Kind::Text
                }
            } else {
                let target = grammar
                    .rules
                    .iter()
                    .find(|r| &r.name == name)
                    .ok_or("unknown nested rule")?;
                if target
                    .annotations
                    .iter()
                    .any(|a| matches!(a.kind, AnnotationKind::Enum))
                {
                    Kind::Enum(target.name.clone())
                } else if !target
                    .annotations
                    .iter()
                    .any(|a| matches!(a.kind, AnnotationKind::Skip))
                    && target
                        .annotations
                        .iter()
                        .any(|a| matches!(a.kind, AnnotationKind::Mapping { .. }))
                {
                    Kind::Node
                } else {
                    return Err(
                        "unmapped/skip aliases require remaining #583 implementation".into(),
                    );
                }
            }
        }
        _ => return Err("unsupported nested element".into()),
    })
}
fn body_shape(grammar: &GrammarDecl, body: &RuleBody, depth: usize) -> Result<Kind, String> {
    let mut result = None;
    for sequence in &body.alternatives {
        let current = if let [element] = sequence.elements.as_slice() {
            shape(grammar, &element.element, depth + 1)?
        } else {
            for element in &sequence.elements {
                if matches!(
                    shape(grammar, &element.element, depth + 1)?,
                    Kind::Nested { .. } | Kind::Node | Kind::Enum(_)
                ) {
                    return Err("multi-element container group needs explicit mapped node".into());
                }
            }
            Kind::Text
        };
        if result.as_ref().is_some_and(|old| {
            old != &current || matches!(current, Kind::Nested { .. } | Kind::Node | Kind::Enum(_))
        }) {
            return Err(
                "heterogeneous/container choice requires remaining #583 implementation".into(),
            );
        }
        result = Some(current);
    }
    result.ok_or_else(|| "empty body".into())
}
pub fn field(grammar: &GrammarDecl, rule: &RuleDecl, name: &str) -> Result<Kind, String> {
    let values = sites(rule)?;
    let (leaf, containers) = parts(shape(grammar, values[name], 0)?);
    Ok(Kind::Nested {
        leaf: Box::new(leaf),
        containers,
    })
}
pub fn boundary(expression: Expression) -> Expression {
    let (kind, child) = match expression {
        Expression::Sequence(mut v) if v.len() == 1 => {
            let Expression::CaptureBoundary { kind, child } = boundary(v.remove(0)) else {
                unreachable!()
            };
            (kind, Expression::Sequence(vec![*child]))
        }
        Expression::OwnedCapture { name, child } => {
            let Expression::CaptureBoundary { kind, child } = boundary(*child) else {
                unreachable!()
            };
            (kind, Expression::OwnedCapture { name, child })
        }
        Expression::CaptureScope(v) => {
            let Expression::CaptureBoundary { kind, child } = boundary(*v) else {
                unreachable!()
            };
            (kind, Expression::CaptureScope(child))
        }
        Expression::Delimited(v) => {
            let Expression::CaptureBoundary { kind, child } = boundary(*v) else {
                unreachable!()
            };
            (kind, Expression::Delimited(child))
        }
        Expression::OptionalExpr(v) => (
            BoundaryKind::Optional,
            Expression::OptionalExpr(Box::new(boundary(*v))),
        ),
        Expression::Repeat { child, min, max } => (
            BoundaryKind::List,
            Expression::Repeat {
                child: Box::new(boundary(*child)),
                min,
                max,
            },
        ),
        Expression::Separated { child, separator } => (
            BoundaryKind::List,
            Expression::Separated {
                child: Box::new(boundary(*child)),
                separator: Box::new(Expression::CaptureBoundary {
                    kind: BoundaryKind::Separator,
                    child: separator,
                }),
            },
        ),
        other => (BoundaryKind::Value, other),
    };
    Expression::CaptureBoundary {
        kind,
        child: Box::new(child),
    }
}
