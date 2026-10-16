//! Family membership and borrowed common-field projections. No runtime/IR layout changes.
use std::collections::HashSet;
use std::fmt::Write;
use unlaxer_codegen::ir::{Field, GrammarIr, Kind};
use unlaxer_ubnf::ast::{
    AnnotationKind, AtomicElement, ElementKind, GrammarDecl, RuleBody, RuleDecl, TokenKind,
};

pub(crate) fn rust_name(name: &str) -> String {
    name.replace('.', "__")
}
fn mapping(rule: &RuleDecl) -> Option<(&str, &[String])> {
    rule.annotations.iter().find_map(|a| match &a.kind {
        AnnotationKind::Mapping { class_name, params } => {
            Some((class_name.as_str(), params.as_slice()))
        }
        _ => None,
    })
}
fn variants<'a>(grammar: &'a GrammarDecl, rule: &RuleDecl) -> Vec<&'a RuleDecl> {
    let Some((name, params)) = mapping(rule) else {
        return vec![];
    };
    if !params.is_empty() || name.contains('.') {
        return vec![];
    }
    let mut result = Vec::new();
    for sequence in &rule.body.alternatives {
        let [element] = sequence.elements.as_slice() else {
            return vec![];
        };
        let ElementKind::RuleRef { name: target, .. } = &element.element.kind else {
            return vec![];
        };
        let Some(target) = grammar.rules.iter().find(|r| &r.name == target) else {
            return vec![];
        };
        let Some((child, _)) = mapping(target) else {
            return vec![];
        };
        if child == name
            || child.contains('.') && !child.starts_with(&format!("{name}."))
            || target
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Skip))
        {
            return vec![];
        }
        if !result.contains(&target) {
            result.push(target);
        }
    }
    result
}
pub(crate) fn is_sum(grammar: &GrammarDecl, rule: &RuleDecl) -> bool {
    !variants(grammar, rule).is_empty()
}
pub(crate) struct Family {
    pub name: String,
    pub rule: String,
    pub variants: Vec<String>,
    pub fields: Vec<String>,
}
fn invalid(rule: &str, detail: &str) -> String {
    format!("E-COMMONFIELD-MISMATCH: {rule}: {detail}")
}
fn collect(
    grammar: &GrammarDecl,
    rule: &RuleDecl,
    visiting: &mut HashSet<String>,
    leaves: &mut Vec<String>,
) -> Result<(), String> {
    if visiting.len() >= 256 || !visiting.insert(rule.name.clone()) {
        return Err(invalid(&rule.name, "cyclic or over-deep sum family"));
    }
    let children = variants(grammar, rule);
    if children.is_empty() {
        let name = mapping(rule).unwrap().0.to_owned();
        if !leaves.contains(&name) {
            leaves.push(name);
        }
    } else {
        for child in children {
            collect(grammar, child, visiting, leaves)?;
        }
    }
    visiting.remove(&rule.name);
    Ok(())
}
pub(crate) fn families(grammar: &GrammarDecl) -> Result<Vec<Family>, String> {
    let mut result = Vec::new();
    let mut names = HashSet::new();
    {
        let mut normalized = std::collections::HashMap::new();
        for rule in &grammar.rules {
            if let Some((name, _)) = mapping(rule) {
                if name.split('.').count() > 2 {
                    return Err(invalid(&rule.name, "nested dotted mapping requires #547"));
                }
                if normalized
                    .insert(rust_name(name), name)
                    .is_some_and(|previous| previous != name)
                {
                    return Err(invalid(&rule.name, "normalized mapping name collision"));
                }
            }
        }
    }
    // Plain sums have the same finite family contract as common-field sums.
    for rule in &grammar.rules {
        if is_sum(grammar, rule) {
            collect(grammar, rule, &mut HashSet::new(), &mut Vec::new())?;
        }
    }
    for rule in &grammar.rules {
        let fields: Vec<_> = rule
            .annotations
            .iter()
            .filter_map(|a| match &a.kind {
                AnnotationKind::CommonField { fields } => Some(fields),
                _ => None,
            })
            .flatten()
            .cloned()
            .collect();
        if fields.is_empty() {
            continue;
        }
        if rule
            .annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::Skip))
            || !is_sum(grammar, rule)
        {
            return Err(invalid(&rule.name, "requires a mapped sum family"));
        }
        let name = mapping(rule).unwrap().0;
        if !names.insert(name) {
            return Err(invalid(&rule.name, "duplicate family declaration"));
        }
        let mut leaves = Vec::new();
        collect(grammar, rule, &mut HashSet::new(), &mut leaves)?;
        for sibling in &grammar.rules {
            if sibling
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Skip | AnnotationKind::Enum))
            {
                continue;
            }
            if let Some((child, _)) = mapping(sibling) {
                if child.starts_with(&format!("{name}.")) && !leaves.contains(&child.to_owned()) {
                    leaves.push(child.to_owned());
                }
            }
        }
        let mut seen = HashSet::new();
        for field in &fields {
            if !seen.insert(field)
                || [
                    "span",
                    "semantics",
                    "getClass",
                    "hashCode",
                    "toString",
                    "clone",
                    "finalize",
                    "notify",
                    "notifyAll",
                    "wait",
                ]
                .contains(&field.as_str())
            {
                return Err(invalid(
                    &rule.name,
                    &format!("duplicate or reserved field {field}"),
                ));
            }
        }
        for field in &fields {
            for contributor in &grammar.rules {
                if contributor
                    .annotations
                    .iter()
                    .any(|a| matches!(a.kind, AnnotationKind::Skip))
                {
                    continue;
                }
                if let Some((mapped, params)) = mapping(contributor) {
                    if leaves.iter().any(|leaf| leaf == mapped) {
                        let mut captured = HashSet::new();
                        capture_types(grammar, &contributor.body, field, &mut captured);
                        if !params.contains(field) || captured.is_empty() {
                            return Err(invalid(
                                &rule.name,
                                &format!("missing field {field} on {}", contributor.name),
                            ));
                        }
                    }
                }
            }
        }
        result.push(Family {
            name: name.into(),
            rule: rule.name.clone(),
            variants: leaves,
            fields,
        });
    }
    Ok(result)
}
// Match the Java field's nominal scalar contract, which is stricter than Rust's Node/Text storage.
fn atom_types(grammar: &GrammarDecl, atom: &AtomicElement, out: &mut HashSet<String>) {
    match &atom.kind {
        ElementKind::Replay { .. } | ElementKind::Terminal(_) => {
            out.insert("String".into());
        }
        ElementKind::RuleRef { name, .. } => {
            if let Some(rule) = grammar.rules.iter().find(|r| &r.name == name) {
                if rule
                    .annotations
                    .iter()
                    .any(|a| matches!(a.kind, AnnotationKind::Skip))
                {
                    out.insert("String".into());
                } else if rule
                    .annotations
                    .iter()
                    .any(|a| matches!(a.kind, AnnotationKind::Enum))
                {
                    out.insert(format!("enum:{name}"));
                } else if let Some((mapped, _)) = mapping(rule) {
                    out.insert(format!("node:{mapped}"));
                } else {
                    out.insert("String".into());
                }
            } else {
                let numeric=grammar.tokens.iter().any(|t| &t.name==name && matches!(&t.kind,TokenKind::Simple{parser_class} if ["NumberParser","DigitParser"].contains(&parser_class.rsplit('.').next().unwrap())));
                out.insert(if numeric { "int" } else { "String" }.into());
            }
        }
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => {
            for seq in &body.alternatives {
                for child in &seq.elements {
                    atom_types(grammar, &child.element, out);
                }
            }
        }
        ElementKind::OneOrMore(child)
        | ElementKind::BoundedRepeat { element: child, .. }
        | ElementKind::Separated { element: child, .. } => atom_types(grammar, child, out),
        ElementKind::Error(_) => {
            out.insert("Object".into());
        }
    }
}
fn capture_types(grammar: &GrammarDecl, body: &RuleBody, field: &str, out: &mut HashSet<String>) {
    for seq in &body.alternatives {
        for child in &seq.elements {
            if child.capture.as_deref() == Some(field) {
                atom_types(grammar, &child.element, out);
            }
            capture_nested_types(grammar, &child.element, field, out);
        }
    }
}
fn capture_nested_types(
    grammar: &GrammarDecl,
    atom: &AtomicElement,
    field: &str,
    out: &mut HashSet<String>,
) {
    match &atom.kind {
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => capture_types(grammar, body, field, out),
        ElementKind::OneOrMore(child)
        | ElementKind::BoundedRepeat { element: child, .. }
        | ElementKind::Separated { element: child, .. } => {
            capture_nested_types(grammar, child, field, out)
        }
        _ => {}
    }
}
pub(crate) fn emit(grammar: &GrammarDecl, ir: &GrammarIr) -> Result<String, String> {
    let mut out = String::new();
    for family in families(grammar)? {
        let name = rust_name(&family.name);
        let method = crate::lowering::method_name(&name).replacen("eval_", "common_", 1);
        let view = format!("{name}Fields");
        if ir.rules.iter().any(|r| {
            r.mapping.as_ref().is_some_and(|m| m.name == view)
                || !r.enum_literals.is_empty() && r.name == view
        }) {
            return Err(invalid(&family.rule, "view name collision"));
        }
        let mut fields = Vec::new();
        for name in &family.fields {
            let mut contract: Option<&Field> = None;
            let mut nominal = None;
            for variant in &family.variants {
                let lowered_mapping = ir
                    .rules
                    .iter()
                    .filter_map(|r| r.mapping.as_ref())
                    .find(|m| m.name == rust_name(variant))
                    .ok_or_else(|| invalid(&family.rule, "missing variant"))?;
                let actual = lowered_mapping
                    .fields
                    .iter()
                    .find(|f| &f.name == name)
                    .ok_or_else(|| {
                        invalid(&family.rule, &format!("missing field {name} on {variant}"))
                    })?;
                // Value is the normalized mixed contract; its source alternatives need not be nominally identical.
                let spine = lowered_mapping
                    .fields
                    .iter()
                    .any(|f| f.kind == Kind::NullableNode);
                if spine && matches!(name.as_str(), "left" | "right") {
                    let actual =
                        HashSet::from([crate::associative_spines::nominal(grammar, variant)?]);
                    if nominal.as_ref().is_some_and(|previous| previous != &actual) {
                        return Err(invalid(
                            &family.rule,
                            &format!("incompatible field {name} on {variant}"),
                        ));
                    }
                    nominal = Some(actual);
                } else if !matches!(actual.kind, Kind::Enum(_) | Kind::Value) {
                    for rule in grammar
                        .rules
                        .iter()
                        .filter(|r| mapping(r).is_some_and(|(n, _)| n == variant))
                    {
                        let mut actual = HashSet::new();
                        capture_types(grammar, &rule.body, name, &mut actual);
                        if actual.len() != 1
                            || nominal.as_ref().is_some_and(|previous| previous != &actual)
                        {
                            return Err(invalid(
                                &family.rule,
                                &format!("incompatible field {name} on {variant}"),
                            ));
                        }
                        nominal = Some(actual);
                    }
                }
                if contract.is_some_and(|previous| previous != actual) {
                    return Err(invalid(
                        &family.rule,
                        &format!("incompatible field {name} on {variant}"),
                    ));
                }
                contract = Some(actual);
            }
            fields.push(contract.unwrap());
        }
        write!(out,"\n#[allow(non_camel_case_types)]\n#[derive(Debug, Clone, Copy)]\npub struct {view}<'a> {{\n").unwrap();
        for field in &fields {
            writeln!(
                out,
                "    pub r#{}: &'a {},",
                field.name,
                unlaxer_codegen::ast_field_type(field)
            )
            .unwrap();
        }
        write!(out,"}}\n\n#[allow(non_snake_case)]\nimpl Ast {{\n    pub fn {method}(&self) -> Option<{view}<'_>> {{\n        match self {{\n").unwrap();
        for variant in &family.variants {
            write!(out, "            Self::r#{} {{ ", rust_name(variant)).unwrap();
            for field in &fields {
                write!(out, "r#{}, ", field.name).unwrap();
            }
            write!(out, ".. }} => Some({view} {{ ").unwrap();
            for field in &fields {
                write!(out, "r#{}, ", field.name).unwrap();
            }
            out.push_str("}),\n");
        }
        out.push_str("            #[allow(unreachable_patterns)]\n            _ => None,\n        }\n    }\n}\n");
    }
    Ok(out)
}
