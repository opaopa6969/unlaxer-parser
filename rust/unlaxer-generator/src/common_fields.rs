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
    if rule
        .annotations
        .iter()
        .any(|a| matches!(a.kind, AnnotationKind::Skip))
    {
        return None;
    }
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
    // Shared by every source type check of this grammar: the recursive analysis
    // is solved lazily once, and contributors keep declaration order.
    let recursive = crate::preferred_plan::RecursiveShapes::new(grammar);
    let mut contributors = std::collections::HashMap::<&str, Vec<&RuleDecl>>::new();
    for rule in &grammar.rules {
        if let Some((name, _)) = mapping(rule) {
            contributors.entry(name).or_default().push(rule);
        }
    }
    let has_enum = grammar.rules.iter().any(|r| {
        r.annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::Enum))
    });
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
        let view = format!("{}Fields", rust_name(name));
        if grammar.rules.iter().any(|r| {
            mapping(r).is_some_and(|(n, _)| rust_name(n) == view)
                || r.name == view
                    && r.annotations
                        .iter()
                        .any(|a| matches!(a.kind, AnnotationKind::Enum))
        }) {
            return Err(invalid(&rule.name, "view name collision"));
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
        // Java CommonFieldSchema checks one field at a time and, within it, one
        // leaf at a time: duplicate/reserved name, missing captures of that leaf,
        // then the leaf's source type. A non-schema error from the type analysis
        // (for example an incompatible shared mapping) must not be hidden by a
        // schema error of a later field or leaf.
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
            let mut contract = None;
            for leaf in &leaves {
                let rules = contributors
                    .get(leaf.as_str())
                    .map_or(&[][..], Vec::as_slice);
                for contributor in rules {
                    let params = mapping(contributor).unwrap().1;
                    let mut captured = HashSet::new();
                    capture_types(grammar, &contributor.body, field, &mut captured);
                    if !params.contains(field) || captured.is_empty() {
                        return Err(invalid(
                            &rule.name,
                            &format!("missing field {field} on {}", contributor.name),
                        ));
                    }
                }
                // Source types are compared before eval/enum/ordinary lowering.
                // Waiting for emit(ir) loses this error when lowering also fails.
                let actual = source_contract(grammar, leaf, rules, field, &recursive, has_enum)?;
                if contract
                    .as_ref()
                    .is_some_and(|previous| previous != &actual)
                {
                    return Err(invalid(
                        &rule.name,
                        &format!("incompatible field {field} on {leaf}"),
                    ));
                }
                contract = Some(actual);
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

/// `rules` are the non-skipped contributors of `mapped`, in declaration order.
fn source_contract(
    grammar: &GrammarDecl,
    mapped: &str,
    rules: &[&RuleDecl],
    field: &str,
    recursive: &crate::preferred_plan::RecursiveShapes<'_>,
    has_enum: bool,
) -> Result<(String, unlaxer_codegen::Cardinality), String> {
    let mut contract = None;
    let mut params = None;
    for rule in rules {
        let current = mapping(rule).unwrap().1;
        if params.is_some_and(|previous| previous != current) {
            return Err(format!(
                "Incompatible shared mapping schema {mapped}: parameter names/order differ on {}",
                rule.name
            ));
        }
        params = Some(current);
        let mut sites = Vec::new();
        let mut pending: Vec<_> = rule
            .body
            .alternatives
            .iter()
            .flat_map(|s| &s.elements)
            .collect();
        while let Some(item) = pending.pop() {
            if item.capture.as_deref() == Some(field) {
                sites.push(&item.element);
            }
            source_children(&item.element, &mut pending);
        }
        let (semantic, mut cardinality) =
            crate::preferred_plan::capture_contract(grammar, rule, field, recursive)?
                .unwrap_or((false, unlaxer_codegen::Cardinality::One));
        let types: HashSet<_> = sites
            .iter()
            .map(|site| {
                // SemanticCardinality normalizes repeated text bindings: a group
                // and its child can have the same capture name and text type.
                if !semantic && sites.len() > 1 {
                    Ok(text_site_nominal(grammar, site))
                } else {
                    source_nominal(grammar, site, has_enum)
                }
            })
            .collect::<Result<_, String>>()?;
        let mut nominal = if types.len() != 1 {
            "Object".into()
        } else {
            types.into_iter().next().unwrap()
        };
        if nominal == "String" && semantic {
            nominal = "Object".into();
        }
        if crate::nested_captures::enabled(rule) {
            // Nested inference normalizes containers and transparent groups. Its
            // leaf kind (including numeric/enum) and mapped nominal are the Java
            // contract; the ordinary source spelling is not part of that type.
            nominal = format!(
                "{:?}:{:?}",
                crate::nested_captures::field(grammar, rule, field)?,
                crate::nested_captures::nominal_leaf(grammar, sites[0])
            );
            // All container cardinalities are already present in the shape.
            cardinality = unlaxer_codegen::Cardinality::One;
        }
        if let Some((previous, count)) = &mut contract {
            if *count != cardinality {
                return Err(format!("Incompatible shared mapping schema {mapped}: capture cardinality differs for {field}"));
            }
            if *previous != nominal {
                *previous = "Object".into();
            }
        } else {
            contract = Some((nominal, cardinality));
        }
    }
    contract.ok_or_else(|| invalid(mapped, "missing variant"))
}

fn source_children<'a>(atom: &'a AtomicElement, out: &mut Vec<&'a unlaxer_ubnf::AnnotatedElement>) {
    match &atom.kind {
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => {
            out.extend(body.alternatives.iter().flat_map(|s| &s.elements));
        }
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => source_children(a, out),
        _ => (),
    }
}

fn source_nominal(
    grammar: &GrammarDecl,
    atom: &AtomicElement,
    has_enum: bool,
) -> Result<String, String> {
    match &atom.kind {
        ElementKind::Optional(body) | ElementKind::Repeat(body) => {
            let [sequence] = body.alternatives.as_slice() else {
                return Ok("Object".into());
            };
            let [item] = sequence.elements.as_slice() else {
                return Ok("Object".into());
            };
            return source_nominal(grammar, &item.element, has_enum);
        }
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => return source_nominal(grammar, a, has_enum),
        _ => (),
    }
    // Ordinary enum aliases may carry trivia annotations or captures. They are
    // not subject to the stricter nested-capture helper expansion contract.
    if has_enum {
        let value = source_value_type(grammar, atom, &mut HashSet::new())?;
        if value.starts_with("enum:") {
            return Ok(value);
        }
    }
    if matches!(
        atom.kind,
        ElementKind::OwnedCapture { .. }
            | ElementKind::CaptureScope(_)
            | ElementKind::Commit { .. }
            | ElementKind::Label { .. }
            | ElementKind::Catch { .. }
            | ElementKind::Group(_)
    ) {
        return Ok("Object".into());
    }
    let mut types = HashSet::new();
    atom_types(grammar, atom, &mut types);
    Ok(if types.len() == 1 {
        types.into_iter().next().unwrap()
    } else {
        "Object".into()
    })
}

fn text_site_nominal(grammar: &GrammarDecl, atom: &AtomicElement) -> String {
    match &atom.kind {
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => {
            if let [sequence] = body.alternatives.as_slice() {
                if let [item] = sequence.elements.as_slice() {
                    return text_site_nominal(grammar, &item.element);
                }
            }
            "String".into()
        }
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => text_site_nominal(grammar, a),
        _ => {
            let mut types = HashSet::new();
            atom_types(grammar, atom, &mut types);
            if types.len() == 1 {
                types.into_iter().next().unwrap()
            } else {
                "Object".into()
            }
        }
    }
}

// EnumSchema.valueType: sequences discard raw text, choices retain it. An
// active recursive alias is an unresolved value, joined with its productive
// alternatives rather than prematurely assuming text.
fn source_value_type(
    grammar: &GrammarDecl,
    atom: &AtomicElement,
    seen: &mut HashSet<String>,
) -> Result<String, String> {
    match &atom.kind {
        ElementKind::RuleRef { name, .. } => {
            if seen.len() >= 128 {
                return Err("E-ENUM: enum value analysis exceeds 128 rules".into());
            }
            let Some(rule) = grammar.rules.iter().find(|r| &r.name == name) else {
                return Ok("#text".into());
            };
            if rule
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Skip))
            {
                return Ok("#text".into());
            }
            if !seen.insert(name.clone()) {
                return Ok("#unknown".into());
            }
            let result = if rule
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Enum))
            {
                Ok(format!("enum:{name}"))
            } else if mapping(rule).is_some() {
                Ok("#node".into())
            } else {
                source_body_type(grammar, &rule.body, seen)
            };
            seen.remove(name);
            result
        }
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => source_body_type(grammar, body, seen),
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => source_value_type(grammar, a, seen),
        _ => Ok("#text".into()),
    }
}

fn source_body_type(
    grammar: &GrammarDecl,
    body: &RuleBody,
    seen: &mut HashSet<String>,
) -> Result<String, String> {
    fn joined(mut values: HashSet<String>, empty: &str) -> String {
        if values.len() > 1 {
            values.remove("#unknown");
        }
        match values.len() {
            0 => empty.into(),
            1 => values.into_iter().next().unwrap(),
            _ => "#join".into(),
        }
    }
    let mut choices = HashSet::new();
    for sequence in &body.alternatives {
        let mut nodes = HashSet::new();
        for item in &sequence.elements {
            let value = source_value_type(grammar, &item.element, seen)?;
            if value != "#text" {
                nodes.insert(value);
            }
        }
        choices.insert(joined(nodes, "#text"));
    }
    Ok(joined(choices, "#text"))
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
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
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
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
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
                }
                // families() already compared source field types. Comparing the
                // descendants again would reject ordinary group captures whose
                // Java type is Object and whose Rust representation is Text.
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
