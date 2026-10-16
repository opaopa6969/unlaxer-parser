//! Infer Java's synthetic nullable literal spine independently from the Java host.
use std::collections::HashSet;
use unlaxer_codegen::ir::{Cardinality, Field, GrammarIr, Kind};
use unlaxer_ubnf::ast::*;
fn mapping(rule: &RuleDecl) -> Option<&str> {
    rule.annotations.iter().find_map(|a| {
        if let AnnotationKind::Mapping { class_name, .. } = &a.kind {
            Some(class_name.as_str())
        } else {
            None
        }
    })
}
fn assoc(rule: &RuleDecl) -> bool {
    rule.annotations.iter().any(|a| {
        matches!(
            a.kind,
            AnnotationKind::LeftAssoc | AnnotationKind::RightAssoc
        )
    })
}
fn skipped(rule: &RuleDecl) -> bool {
    rule.annotations
        .iter()
        .any(|a| matches!(a.kind, AnnotationKind::Skip))
}
fn operands(rule: &RuleDecl) -> Vec<&AtomicElement> {
    let [seq] = rule.body.alternatives.as_slice() else {
        return vec![];
    };
    // Lowering has already checked the left/op/right skeleton. Literal delimiters
    // keep their source positions, so operands must be selected by capture identity.
    let Some(left) = seq
        .elements
        .iter()
        .find(|element| element.capture.as_deref() == Some("left"))
    else {
        return vec![];
    };
    // Other repetitions may contain only delimiters. The semantic tail owns both bindings.
    let Some(tail) = seq.elements.iter().find_map(|element| {
        let ElementKind::Repeat(body) = &element.element.kind else {
            return None;
        };
        let [tail] = body.alternatives.as_slice() else {
            return None;
        };
        (tail
            .elements
            .iter()
            .any(|element| element.capture.as_deref() == Some("op"))
            && tail
                .elements
                .iter()
                .any(|element| element.capture.as_deref() == Some("right")))
        .then_some(tail)
    }) else {
        return vec![];
    };
    let Some(right) = tail
        .elements
        .iter()
        .find(|element| element.capture.as_deref() == Some("right"))
    else {
        return vec![];
    };
    vec![&left.element, &right.element]
}
fn visit_body(
    g: &GrammarDecl,
    b: &RuleBody,
    seen: &mut HashSet<String>,
    mapped: &mut HashSet<String>,
    depth: usize,
    work: &mut usize,
) -> Result<bool, String> {
    *work += 1;
    if *work > 1_000_000 {
        return Err("E-ASSOC: operand analysis work exceeds 1000000".into());
    }
    if depth > 256 {
        return Err("E-ASSOC: operand analysis exceeds 256".into());
    }
    let mut raw = false;
    for seq in &b.alternatives {
        let mut all = true;
        for a in &seq.elements {
            all &= visit(g, &a.element, seen, mapped, depth + 1, work)?;
        }
        raw |= all;
    }
    Ok(raw)
}
fn visit(
    g: &GrammarDecl,
    a: &AtomicElement,
    seen: &mut HashSet<String>,
    mapped: &mut HashSet<String>,
    depth: usize,
    work: &mut usize,
) -> Result<bool, String> {
    *work += 1;
    if *work > 1_000_000 {
        return Err("E-ASSOC: operand analysis work exceeds 1000000".into());
    }
    if depth > 256 {
        return Err("E-ASSOC: operand analysis exceeds 256".into());
    }
    Ok(match &a.kind {
        ElementKind::RuleRef { name, .. } => {
            if let Some(rule) = g.rules.iter().find(|r| &r.name == name) {
                if skipped(rule) {
                    true
                } else if let Some(name) = mapping(rule) {
                    mapped.insert(name.to_owned());
                    false
                } else if !seen.insert(name.clone()) {
                    false
                } else {
                    let raw = visit_body(g, &rule.body, seen, mapped, depth + 1, work)?;
                    seen.remove(name);
                    raw
                }
            } else {
                true
            }
        }
        ElementKind::Group(b)
        | ElementKind::OwnedCapture { body: b, .. }
        | ElementKind::CaptureScope(b) => visit_body(g, b, seen, mapped, depth + 1, work)?,
        ElementKind::Optional(b) | ElementKind::Repeat(b) => {
            visit_body(g, b, seen, mapped, depth + 1, work)?;
            true
        }
        ElementKind::OneOrMore(a) | ElementKind::Separated { element: a, .. } => {
            visit(g, a, seen, mapped, depth + 1, work)?
        }
        ElementKind::BoundedRepeat { element, min, .. } => {
            visit(g, element, seen, mapped, depth + 1, work)? || *min == 0
        }
        _ => true,
    })
}
// The recursive side alone does not turn a mixed text/node operand into a
// synthetic spine. A transparent operand must itself reach both this mapping
// and another mapped class, matching SharedAssocSchema's per-operand contract.
fn transparent_recursive_operand(
    g: &GrammarDecl,
    rule: &RuleDecl,
    name: &str,
    work: &mut usize,
) -> Result<bool, String> {
    for operand in operands(rule) {
        let ElementKind::RuleRef { name: target, .. } = &operand.kind else {
            continue;
        };
        let Some(target) = g.rules.iter().find(|r| &r.name == target) else {
            continue;
        };
        if mapping(target).is_some() {
            continue;
        }
        let mut reachable = HashSet::new();
        visit(g, operand, &mut HashSet::new(), &mut reachable, 0, work)?;
        if reachable.contains(name) && reachable.len() > 1 {
            return Ok(true);
        }
    }
    Ok(false)
}
pub(crate) fn refine(g: &GrammarDecl, ir: &mut GrammarIr) -> Result<(), String> {
    let mut names = HashSet::new();
    let mut work = 0;
    for rule in &g.rules {
        if !assoc(rule) || skipped(rule) {
            continue;
        }
        let Some(name) = mapping(rule) else { continue };
        let family: Vec<_> = g
            .rules
            .iter()
            .filter(|r| !skipped(r) && mapping(r) == Some(name))
            .collect();
        let mut mapped = HashSet::new();
        let mut raw = false;
        for r in &family {
            for operand in operands(r) {
                raw |= visit(g, operand, &mut HashSet::new(), &mut mapped, 0, &mut work)?;
            }
        }
        let right = rule
            .annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::RightAssoc));
        let spine = if family.len() > 1 {
            !mapped.is_empty()
        } else {
            right && transparent_recursive_operand(g, rule, name, &mut work)?
        };
        if spine && raw {
            names.insert(crate::common_fields::rust_name(name));
        }
    }
    for rule in &mut ir.rules {
        let Some(mapping) = &mut rule.mapping else {
            continue;
        };
        if !names.contains(&mapping.name) {
            continue;
        }
        for field in &mut mapping.fields {
            refine_field(field)
        }
        for constraint in &mut rule.type_constraints {
            refine_field(&mut constraint.capture);
            refine_field(&mut constraint.reference);
        }
    }
    Ok(())
}
fn refine_field(field: &mut Field) {
    match field.name.as_str() {
        "left" => {
            field.kind = Kind::NullableNode;
            field.cardinality = Cardinality::One
        }
        "right" => {
            field.kind = Kind::Node;
            field.cardinality = Cardinality::Many
        }
        _ => (),
    }
}

/// Nominal Java operand type after the shared spine schema has joined its contributors.
pub(crate) fn nominal(g: &GrammarDecl, name: &str) -> Result<String, String> {
    let mut mapped = HashSet::new();
    let mut work = 0;
    for rule in g.rules.iter().filter(|r| mapping(r) == Some(name)) {
        for operand in operands(rule) {
            visit(g, operand, &mut HashSet::new(), &mut mapped, 0, &mut work)?;
        }
    }
    Ok(if mapped.len() == 1 && mapped.contains(name) {
        format!("node:{name}")
    } else {
        "node:#base".into()
    })
}
