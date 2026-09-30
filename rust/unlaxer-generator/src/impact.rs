//! Read-only, source-level Rust AST and evaluator API comparison.
//! The schema is derived from the existing lowering IR and emitted modules in memory.
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write;
use unlaxer_codegen::ir::{Cardinality, GrammarIr, Mapping};
use unlaxer_codegen::{ast_field_type, evaluator_method_name, evaluator_parameter_type};
use unlaxer_ubnf::ast::GrammarDecl;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Span {
    pub start: usize,
    pub end: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Location {
    pub path: String,
    pub span: Span,
    pub line: usize,
    pub column: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Origin {
    pub rule: String,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Field {
    pub name: String,
    pub r#type: String,
    pub cardinality: &'static str,
    pub generated: Location,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Node {
    pub name: String,
    pub kind: &'static str,
    pub fields: Vec<Field>,
    pub parents: Vec<String>,
    pub variants: Vec<String>,
    pub origins: Vec<Origin>,
    pub generated: Location,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Parameter {
    pub name: String,
    pub r#type: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Method {
    pub name: String,
    pub return_type: String,
    pub parameters: Vec<Parameter>,
    pub required: bool,
    pub origins: Vec<Origin>,
    pub generated: Location,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Snapshot {
    pub grammar: String,
    pub ast_type: String,
    pub evaluator_type: String,
    pub nodes: Vec<Node>,
    pub methods: Vec<Method>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Change {
    pub kind: &'static str,
    pub subject: String,
    pub before: Option<String>,
    pub after: Option<String>,
    pub before_origins: Vec<Origin>,
    pub after_origins: Vec<Origin>,
    pub before_generated: Option<Location>,
    pub after_generated: Option<Location>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Diagnostic {
    pub side: &'static str,
    pub code: &'static str,
    pub message: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Report {
    pub ok: bool,
    pub has_changes: bool,
    pub before: Option<Snapshot>,
    pub after: Option<Snapshot>,
    pub changes: Vec<Change>,
    pub diagnostics: Vec<Diagnostic>,
}

impl Report {
    pub fn to_json(&self) -> String {
        format!(
            "{{\"schemaVersion\":1,\"scope\":\"ast-semantics\",\"target\":\"rust\",\"ok\":{},\"hasChanges\":{},\"before\":{},\"after\":{},\"changes\":{},\"diagnostics\":{}}}",
            self.ok,
            self.has_changes,
            self.before.as_ref().map_or_else(|| "null".into(), snapshot_json),
            self.after.as_ref().map_or_else(|| "null".into(), snapshot_json),
            array(&self.changes, change_json),
            array(&self.diagnostics, diagnostic_json),
        )
    }
}

/// Compare complete grammars without opening imports, loading providers, writing files,
/// running a compiler, or executing parser code. Either valid side survives a failed peer.
pub fn compare(before_source: &str, after_source: &str) -> Report {
    let before_result = snapshot(before_source);
    let after_result = snapshot(after_source);
    let mut diagnostics = Vec::new();
    let before = match before_result {
        Ok(snapshot) => Some(snapshot),
        Err(message) => {
            diagnostics.push(Diagnostic {
                side: "before",
                code: "I-SCHEMA",
                message,
            });
            None
        }
    };
    let after = match after_result {
        Ok(snapshot) => Some(snapshot),
        Err(message) => {
            diagnostics.push(Diagnostic {
                side: "after",
                code: "I-SCHEMA",
                message,
            });
            None
        }
    };
    let changes = match (&before, &after) {
        (Some(before), Some(after)) => changes(before, after),
        _ => Vec::new(),
    };
    Report {
        ok: diagnostics.is_empty(),
        has_changes: !changes.is_empty(),
        before,
        after,
        changes,
        diagnostics,
    }
}

fn snapshot(source: &str) -> Result<Snapshot, String> {
    let file = unlaxer_ubnf::parse(source).map_err(|error| error.to_string())?;
    let [grammar] = file.grammars.as_slice() else {
        return Err("Rust impact requires exactly one grammar".into());
    };
    let ir = crate::lowering::lower(grammar)?;
    let files = unlaxer_codegen::generate(&ir).map_err(|error| error.to_string())?;
    let ast = files
        .iter()
        .find(|file| file.relative_path == "ast.rs")
        .ok_or("missing generated ast.rs")?;
    let evaluator = files
        .iter()
        .find(|file| file.relative_path == "evaluator.rs")
        .ok_or("missing generated evaluator.rs")?;
    build_snapshot(grammar, &ir, &ast.content, &evaluator.content)
}

fn build_snapshot(
    grammar: &GrammarDecl,
    ir: &GrammarIr,
    ast: &str,
    evaluator: &str,
) -> Result<Snapshot, String> {
    if grammar.rules.len() != ir.rules.len() {
        return Err("lowered rule count differs from source".into());
    }
    let mut mapped = BTreeMap::<String, &Mapping>::new();
    let mut origins = BTreeMap::<String, Vec<Origin>>::new();
    for (source_rule, lowered_rule) in grammar.rules.iter().zip(&ir.rules) {
        if source_rule.name != lowered_rule.name {
            return Err("lowered rule order differs from source".into());
        }
        let Some(mapping) = &lowered_rule.mapping else {
            continue;
        };
        mapped.entry(mapping.name.clone()).or_insert(mapping);
        origins
            .entry(mapping.name.clone())
            .or_default()
            .push(Origin {
                rule: source_rule.name.clone(),
                span: Span {
                    start: source_rule.span.codepoint_start,
                    end: source_rule.span.codepoint_end,
                },
            });
    }
    for entries in origins.values_mut() {
        entries.sort_by(|a, b| a.rule.cmp(&b.rule));
    }
    let mut nodes = Vec::new();
    let mut methods = Vec::new();
    for (name, mapping) in mapped {
        let origin_list = origins.remove(&name).unwrap_or_default();
        let (node_line, node_location) = declaration(ast, "ast.rs", &format!("    r#{name} {{"))?;
        let mut fields = Vec::new();
        for field in &mapping.fields {
            let field_type = ast_field_type(field);
            let needle = format!("r#{}: {field_type}", field.name);
            let generated = fragment(ast, "ast.rs", &node_line, &node_location, &needle)?;
            fields.push(Field {
                name: field.name.clone(),
                r#type: field_type,
                cardinality: cardinality(field.cardinality),
                generated,
            });
        }
        nodes.push(Node {
            name: name.clone(),
            kind: "variant",
            fields,
            parents: Vec::new(),
            variants: Vec::new(),
            origins: origin_list.clone(),
            generated: node_location,
        });
        let method_name = evaluator_method_name(&name);
        let (method_line, method_location) =
            declaration(evaluator, "evaluator.rs", &format!("    fn {method_name}("))?;
        let mut parameters = vec![Parameter {
            name: "self".into(),
            r#type: "&mut Self".into(),
        }];
        for field in &mapping.fields {
            parameters.push(Parameter {
                name: field.name.clone(),
                r#type: evaluator_parameter_type(field),
            });
        }
        parameters.push(Parameter {
            name: "span".into(),
            r#type: "Span".into(),
        });
        let mut expected_method = format!("    fn {method_name}(&mut self");
        for field in &mapping.fields {
            write!(
                expected_method,
                ", r#{}: {}",
                field.name,
                evaluator_parameter_type(field)
            )
            .unwrap();
        }
        expected_method.push_str(", span: Span) -> Self::Output;");
        if method_line != expected_method {
            return Err(format!(
                "generated evaluator declaration differs for {method_name}"
            ));
        }
        methods.push(Method {
            name: method_name,
            return_type: "Self::Output".into(),
            parameters,
            required: true,
            origins: origin_list,
            generated: method_location,
        });
    }
    methods.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(Snapshot {
        grammar: grammar.name.clone(),
        ast_type: "Ast".into(),
        evaluator_type: "Semantics".into(),
        nodes,
        methods,
    })
}

fn cardinality(value: Cardinality) -> &'static str {
    match value {
        Cardinality::One => "one",
        Cardinality::Optional => "optional",
        Cardinality::Many => "many",
    }
}

fn declaration(content: &str, path: &str, prefix: &str) -> Result<(String, Location), String> {
    let mut offset = 0;
    for (index, segment) in content.split_inclusive('\n').enumerate() {
        let line = segment.trim_end_matches(['\r', '\n']);
        if line.starts_with(prefix) {
            return Ok((
                line.into(),
                Location {
                    path: path.into(),
                    span: Span {
                        start: offset,
                        end: offset + line.chars().count(),
                    },
                    line: index + 1,
                    column: 1,
                },
            ));
        }
        offset += segment.chars().count();
    }
    Err(format!("missing generated declaration {path}: {prefix}"))
}

fn fragment(
    content: &str,
    path: &str,
    line: &str,
    parent: &Location,
    needle: &str,
) -> Result<Location, String> {
    let byte = line
        .find(needle)
        .ok_or_else(|| format!("missing generated field {path}: {needle}"))?;
    let start = parent.span.start + line[..byte].chars().count();
    let end = start + needle.chars().count();
    // Do not manufacture locations outside the generated in-memory file.
    if end > content.chars().count() || end > parent.span.end {
        return Err(format!("invalid generated field location {path}: {needle}"));
    }
    Ok(Location {
        path: path.into(),
        span: Span { start, end },
        line: parent.line,
        column: line[..byte].chars().count() + 1,
    })
}

fn changes(before: &Snapshot, after: &Snapshot) -> Vec<Change> {
    let mut result = Vec::new();
    if before.ast_type != after.ast_type {
        result.push(Change::plain(
            "AST_TYPE_CHANGED",
            "ast",
            Some(&before.ast_type),
            Some(&after.ast_type),
        ));
    }
    if before.evaluator_type != after.evaluator_type {
        result.push(Change::plain(
            "EVALUATOR_TYPE_CHANGED",
            "evaluator",
            Some(&before.evaluator_type),
            Some(&after.evaluator_type),
        ));
    }
    let before_nodes: BTreeMap<_, _> = before.nodes.iter().map(|node| (&node.name, node)).collect();
    let after_nodes: BTreeMap<_, _> = after.nodes.iter().map(|node| (&node.name, node)).collect();
    for name in before_nodes
        .keys()
        .chain(after_nodes.keys())
        .collect::<BTreeSet<_>>()
    {
        let old = before_nodes.get(name).copied();
        let new = after_nodes.get(name).copied();
        compare_node(name, old, new, &mut result);
    }
    let before_methods: BTreeMap<_, _> = before
        .methods
        .iter()
        .map(|method| (&method.name, method))
        .collect();
    let after_methods: BTreeMap<_, _> = after
        .methods
        .iter()
        .map(|method| (&method.name, method))
        .collect();
    for name in before_methods
        .keys()
        .chain(after_methods.keys())
        .collect::<BTreeSet<_>>()
    {
        compare_method(
            name,
            before_methods.get(name).copied(),
            after_methods.get(name).copied(),
            &mut result,
        );
    }
    result.sort_by(|a, b| a.kind.cmp(b.kind).then_with(|| a.subject.cmp(&b.subject)));
    result
}

impl Change {
    fn plain(kind: &'static str, subject: &str, before: Option<&str>, after: Option<&str>) -> Self {
        Self {
            kind,
            subject: subject.into(),
            before: before.map(str::to_owned),
            after: after.map(str::to_owned),
            before_origins: Vec::new(),
            after_origins: Vec::new(),
            before_generated: None,
            after_generated: None,
        }
    }
    fn node(
        kind: &'static str,
        subject: &str,
        before: Option<&Node>,
        after: Option<&Node>,
        old: Option<String>,
        new: Option<String>,
    ) -> Self {
        Self {
            kind,
            subject: subject.into(),
            before: old,
            after: new,
            before_origins: before.map_or_else(Vec::new, |node| node.origins.clone()),
            after_origins: after.map_or_else(Vec::new, |node| node.origins.clone()),
            before_generated: before.map(|node| node.generated.clone()),
            after_generated: after.map(|node| node.generated.clone()),
        }
    }
    fn field(
        kind: &'static str,
        subject: String,
        nodes: (&Node, &Node),
        old: Option<&Field>,
        new: Option<&Field>,
        before: Option<String>,
        after: Option<String>,
    ) -> Self {
        Self {
            kind,
            subject,
            before,
            after,
            before_origins: nodes.0.origins.clone(),
            after_origins: nodes.1.origins.clone(),
            before_generated: old.map(|field| field.generated.clone()),
            after_generated: new.map(|field| field.generated.clone()),
        }
    }
    fn method(
        kind: &'static str,
        subject: &str,
        old: Option<&Method>,
        new: Option<&Method>,
        before: Option<String>,
        after: Option<String>,
    ) -> Self {
        Self {
            kind,
            subject: subject.into(),
            before,
            after,
            before_origins: old.map_or_else(Vec::new, |method| method.origins.clone()),
            after_origins: new.map_or_else(Vec::new, |method| method.origins.clone()),
            before_generated: old.map(|method| method.generated.clone()),
            after_generated: new.map(|method| method.generated.clone()),
        }
    }
}

fn names(items: &[String]) -> String {
    items.join(",")
}
fn rule_names(items: &[Origin]) -> String {
    items
        .iter()
        .map(|origin| origin.rule.as_str())
        .collect::<Vec<_>>()
        .join(",")
}
fn field_names(items: &[Field]) -> String {
    items
        .iter()
        .map(|field| field.name.as_str())
        .collect::<Vec<_>>()
        .join(",")
}

fn compare_node(name: &str, before: Option<&Node>, after: Option<&Node>, out: &mut Vec<Change>) {
    match (before, after) {
        (None, Some(after)) => out.push(Change::node(
            "NODE_ADDED",
            name,
            None,
            Some(after),
            None,
            Some(after.kind.into()),
        )),
        (Some(before), None) => out.push(Change::node(
            "NODE_REMOVED",
            name,
            Some(before),
            None,
            Some(before.kind.into()),
            None,
        )),
        (Some(before), Some(after)) => {
            if before.kind != after.kind {
                out.push(Change::node(
                    "NODE_KIND_CHANGED",
                    name,
                    Some(before),
                    Some(after),
                    Some(before.kind.into()),
                    Some(after.kind.into()),
                ));
            }
            if before.parents != after.parents {
                out.push(Change::node(
                    "NODE_PARENTS_CHANGED",
                    name,
                    Some(before),
                    Some(after),
                    Some(names(&before.parents)),
                    Some(names(&after.parents)),
                ));
            }
            if before.variants != after.variants {
                out.push(Change::node(
                    "NODE_VARIANTS_CHANGED",
                    name,
                    Some(before),
                    Some(after),
                    Some(names(&before.variants)),
                    Some(names(&after.variants)),
                ));
            }
            if rule_names(&before.origins) != rule_names(&after.origins) {
                out.push(Change::node(
                    "NODE_RULES_CHANGED",
                    name,
                    Some(before),
                    Some(after),
                    Some(rule_names(&before.origins)),
                    Some(rule_names(&after.origins)),
                ));
            }
            compare_fields(before, after, out);
        }
        (None, None) => {}
    }
}

fn compare_fields(before: &Node, after: &Node, out: &mut Vec<Change>) {
    let old: BTreeMap<_, _> = before
        .fields
        .iter()
        .map(|field| (&field.name, field))
        .collect();
    let new: BTreeMap<_, _> = after
        .fields
        .iter()
        .map(|field| (&field.name, field))
        .collect();
    if old.keys().eq(new.keys()) && field_names(&before.fields) != field_names(&after.fields) {
        out.push(Change::node(
            "FIELD_ORDER_CHANGED",
            &before.name,
            Some(before),
            Some(after),
            Some(field_names(&before.fields)),
            Some(field_names(&after.fields)),
        ));
    }
    for name in old.keys().chain(new.keys()).collect::<BTreeSet<_>>() {
        let previous = old.get(name).copied();
        let next = new.get(name).copied();
        let subject = format!("{}.{}", before.name, name);
        match (previous, next) {
            (None, Some(field)) => out.push(Change::field(
                "FIELD_ADDED",
                subject,
                (before, after),
                None,
                Some(field),
                None,
                Some(field.r#type.clone()),
            )),
            (Some(field), None) => out.push(Change::field(
                "FIELD_REMOVED",
                subject,
                (before, after),
                Some(field),
                None,
                Some(field.r#type.clone()),
                None,
            )),
            (Some(old), Some(new)) => {
                if old.r#type != new.r#type {
                    out.push(Change::field(
                        "FIELD_TYPE_CHANGED",
                        subject.clone(),
                        (before, after),
                        Some(old),
                        Some(new),
                        Some(old.r#type.clone()),
                        Some(new.r#type.clone()),
                    ));
                }
                if old.cardinality != new.cardinality {
                    out.push(Change::field(
                        "FIELD_CARDINALITY_CHANGED",
                        subject,
                        (before, after),
                        Some(old),
                        Some(new),
                        Some(old.cardinality.into()),
                        Some(new.cardinality.into()),
                    ));
                }
            }
            (None, None) => {}
        }
    }
}

fn method_signature(method: &Method) -> String {
    format!(
        "({})->{}",
        method
            .parameters
            .iter()
            .map(|parameter| format!("{}:{}", parameter.name, parameter.r#type))
            .collect::<Vec<_>>()
            .join(","),
        method.return_type
    )
}

fn compare_method(
    name: &str,
    before: Option<&Method>,
    after: Option<&Method>,
    out: &mut Vec<Change>,
) {
    match (before, after) {
        (None, Some(method)) => out.push(Change::method(
            "METHOD_ADDED",
            name,
            None,
            Some(method),
            None,
            Some(method_signature(method)),
        )),
        (Some(method), None) => out.push(Change::method(
            "METHOD_REMOVED",
            name,
            Some(method),
            None,
            Some(method_signature(method)),
            None,
        )),
        (Some(before), Some(after)) => {
            let old = method_signature(before);
            let new = method_signature(after);
            if old != new {
                out.push(Change::method(
                    "METHOD_SIGNATURE_CHANGED",
                    name,
                    Some(before),
                    Some(after),
                    Some(old),
                    Some(new),
                ));
            }
            if before.required != after.required {
                out.push(Change::method(
                    "METHOD_REQUIRED_CHANGED",
                    name,
                    Some(before),
                    Some(after),
                    Some(before.required.to_string()),
                    Some(after.required.to_string()),
                ));
            }
        }
        (None, None) => {}
    }
}

fn json_string(value: &str) -> String {
    let mut out = String::from("\"");
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            ch if ch < ' ' => {
                write!(out, "\\u{:04x}", ch as u32).unwrap();
            }
            ch => out.push(ch),
        }
    }
    out.push('"');
    out
}

fn optional(value: Option<&str>) -> String {
    value.map_or_else(|| "null".into(), json_string)
}
fn array<T>(items: &[T], convert: impl Fn(&T) -> String) -> String {
    format!(
        "[{}]",
        items.iter().map(convert).collect::<Vec<_>>().join(",")
    )
}
fn span_json(span: &Span) -> String {
    format!("{{\"start\":{},\"end\":{}}}", span.start, span.end)
}
fn location_json(location: &Location) -> String {
    format!(
        "{{\"path\":{},\"span\":{},\"line\":{},\"column\":{}}}",
        json_string(&location.path),
        span_json(&location.span),
        location.line,
        location.column
    )
}
fn origin_json(origin: &Origin) -> String {
    format!(
        "{{\"rule\":{},\"span\":{}}}",
        json_string(&origin.rule),
        span_json(&origin.span)
    )
}
fn field_json(field: &Field) -> String {
    format!(
        "{{\"name\":{},\"type\":{},\"cardinality\":{},\"generated\":{}}}",
        json_string(&field.name),
        json_string(&field.r#type),
        json_string(field.cardinality),
        location_json(&field.generated)
    )
}
fn node_json(node: &Node) -> String {
    format!("{{\"name\":{},\"kind\":{},\"fields\":{},\"parents\":{},\"variants\":{},\"origins\":{},\"generated\":{}}}", json_string(&node.name), json_string(node.kind), array(&node.fields, field_json), array(&node.parents, |value| json_string(value)), array(&node.variants, |value| json_string(value)), array(&node.origins, origin_json), location_json(&node.generated))
}
fn parameter_json(parameter: &Parameter) -> String {
    format!(
        "{{\"name\":{},\"type\":{}}}",
        json_string(&parameter.name),
        json_string(&parameter.r#type)
    )
}
fn method_json(method: &Method) -> String {
    format!("{{\"name\":{},\"returnType\":{},\"parameters\":{},\"required\":{},\"origins\":{},\"generated\":{}}}", json_string(&method.name), json_string(&method.return_type), array(&method.parameters, parameter_json), method.required, array(&method.origins, origin_json), location_json(&method.generated))
}
fn snapshot_json(snapshot: &Snapshot) -> String {
    format!(
        "{{\"grammar\":{},\"astType\":{},\"evaluatorType\":{},\"nodes\":{},\"methods\":{}}}",
        json_string(&snapshot.grammar),
        json_string(&snapshot.ast_type),
        json_string(&snapshot.evaluator_type),
        array(&snapshot.nodes, node_json),
        array(&snapshot.methods, method_json)
    )
}
fn change_json(change: &Change) -> String {
    format!("{{\"kind\":{},\"subject\":{},\"before\":{},\"after\":{},\"beforeOrigins\":{},\"afterOrigins\":{},\"beforeGenerated\":{},\"afterGenerated\":{}}}", json_string(change.kind), json_string(&change.subject), optional(change.before.as_deref()), optional(change.after.as_deref()), array(&change.before_origins, origin_json), array(&change.after_origins, origin_json), change.before_generated.as_ref().map_or_else(|| "null".into(), location_json), change.after_generated.as_ref().map_or_else(|| "null".into(), location_json))
}
fn diagnostic_json(diagnostic: &Diagnostic) -> String {
    format!(
        "{{\"side\":{},\"code\":{},\"message\":{}}}",
        json_string(diagnostic.side),
        json_string(diagnostic.code),
        json_string(&diagnostic.message)
    )
}
