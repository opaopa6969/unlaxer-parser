//! Versioned, validated semantic rule IR and generated-CST execution. No JSON/runtime dependency.
use crate::editor_cst::{DefectKind, EditorCst, Node, Status};
use crate::semantic::{self as model, SemanticModel, UNKNOWN};
use crate::{call_inference as inference, type_system as types, Span};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Emit {
    Scope,
    Type,
    Field,
    Symbol,
    Signature,
    Expression,
    Reference,
    Call,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    Capture,
    Field,
    Literal,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Selector {
    pub source: Source,
    pub name: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shape {
    pub captures: BTreeSet<String>,
    pub fields: BTreeSet<String>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Inventory {
    pub grammar: String,
    pub nodes: BTreeMap<String, Shape>,
}
#[derive(Debug, Clone)]
pub struct Rule {
    pub id: String,
    pub node: String,
    pub emit: Emit,
    pub depends_on: Vec<String>,
    pub selectors: BTreeMap<String, Selector>,
    pub owner: String,
    pub visibility: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SchemaError {
    pub code: &'static str,
    pub path: String,
}
impl std::fmt::Display for SchemaError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.path)
    }
}
impl std::error::Error for SchemaError {}
pub fn schema(code: &'static str, path: impl Into<String>) -> SchemaError {
    SchemaError {
        code,
        path: path.into(),
    }
}
/// Scalar and aggregate text limits apply to hand-authored IR as well as JSON.
pub fn scalar(value: &str, nonempty: bool) -> Result<(), SchemaError> {
    if nonempty && value.is_empty() {
        return Err(schema("EMPTY_NAME", ""));
    }
    if value.chars().count() > 1024 {
        return Err(schema("LIMIT", ""));
    }
    Ok(())
}
pub fn rule_scalars(rule: &Rule) -> Result<(), SchemaError> {
    scalar(&rule.id, true)?;
    scalar(&rule.node, true)?;
    scalar(&rule.owner, false)?;
    scalar(&rule.visibility, false)?;
    for value in &rule.depends_on {
        scalar(value, true)?;
    }
    for (key, selector) in &rule.selectors {
        scalar(key, true)?;
        scalar(&selector.name, true)?;
    }
    Ok(())
}
struct TextBudget(usize);
impl TextBudget {
    fn add(&mut self, value: &str) -> Result<(), SchemaError> {
        scalar(value, false)?;
        self.0 += value.len();
        if self.0 > 1048576 {
            return Err(schema("LIMIT", ""));
        }
        Ok(())
    }
}
#[derive(Debug, Clone)]
pub struct Program {
    inventory: Inventory,
    rules: Vec<Rule>,
    unknown_literals: BTreeSet<String>,
}
fn phase(emit: Emit) -> usize {
    match emit {
        Emit::Scope => 0,
        Emit::Type | Emit::Field => 1,
        Emit::Symbol | Emit::Signature | Emit::Expression | Emit::Reference => 2,
        Emit::Call => 3,
    }
}
impl Program {
    pub fn new(
        schema_version: i64,
        grammar: String,
        profile: String,
        unknown_literals: Vec<String>,
        rules: Vec<Rule>,
        inventory: Inventory,
    ) -> Result<Self, SchemaError> {
        if schema_version != 1 {
            return Err(schema("SCHEMA_VERSION", "schemaVersion"));
        }
        if profile != "portableNominal/1" {
            return Err(schema("UNSUPPORTED_PROFILE", "profile"));
        }
        if grammar != inventory.grammar {
            return Err(schema("GRAMMAR_MISMATCH", "grammar"));
        }
        if rules.len() > 256 {
            return Err(schema("LIMIT", "rules"));
        }
        scalar(&inventory.grammar, true)?;
        if inventory.nodes.len() > 4096 {
            return Err(schema("LIMIT", "inventory"));
        }
        let mut budget = TextBudget(0);
        budget.add(&grammar)?;
        budget.add(&profile)?;
        let mut slots = 0;
        for (name, shape) in &inventory.nodes {
            scalar(name, true)?;
            budget.add(name)?;
            slots += shape.captures.len() + shape.fields.len();
            if slots > 16384 {
                return Err(schema("LIMIT", "inventory"));
            }
            for value in shape.captures.iter().chain(shape.fields.iter()) {
                scalar(value, true)?;
                budget.add(value)?;
            }
        }
        for value in &unknown_literals {
            budget.add(value)?;
        }
        for rule in &rules {
            rule_scalars(rule)?;
            for value in [&rule.id, &rule.node, &rule.owner, &rule.visibility] {
                budget.add(value)?;
            }
            for value in &rule.depends_on {
                budget.add(value)?;
            }
            for (key, selector) in &rule.selectors {
                budget.add(key)?;
                budget.add(&selector.name)?;
            }
        }
        let mut by_id = BTreeMap::new();
        let mut bindings = BTreeSet::new();
        for rule in &rules {
            if rule.id.is_empty() || rule.node.is_empty() {
                return Err(schema("EMPTY_NAME", ""));
            }
            if by_id.insert(rule.id.clone(), rule).is_some() {
                return Err(schema("DUPLICATE_RULE", &rule.id));
            }
            if !bindings.insert((&rule.node, rule.emit)) {
                return Err(schema("AMBIGUOUS_BINDING", &rule.id));
            }
            let shape = inventory
                .nodes
                .get(&rule.node)
                .ok_or_else(|| schema("UNDEFINED_NODE", format!("{}.node", rule.id)))?;
            let required: &[&str] = match rule.emit {
                Emit::Scope => &[],
                Emit::Type => &["name", "kind"],
                Emit::Field | Emit::Symbol => &["name", "type"],
                Emit::Signature => &["name", "parameters", "result"],
                Emit::Expression => &["type"],
                Emit::Reference => &["name"],
                Emit::Call => &["name", "arguments"],
            };
            if required
                .iter()
                .any(|key| !rule.selectors.contains_key(*key))
                || rule.selectors.keys().any(|key| {
                    !(required.contains(&key.as_str())
                        || rule.emit == Emit::Type && key == "parents")
                })
            {
                return Err(schema("INVALID_SELECTOR_SET", &rule.id));
            }
            for (key, selector) in &rule.selectors {
                let path = format!("{}.{}", rule.id, key);
                if selector.name.is_empty() {
                    return Err(schema("EMPTY_NAME", ""));
                }
                if selector.source == Source::Field && !shape.fields.contains(&selector.name) {
                    return Err(schema("UNDEFINED_FIELD", path));
                }
                if selector.source != Source::Literal && !shape.captures.contains(&selector.name) {
                    return Err(schema(
                        if selector.source == Source::Field {
                            "UNMAPPED_FIELD"
                        } else {
                            "UNDEFINED_CAPTURE"
                        },
                        path,
                    ));
                }
            }
            if rule.emit == Emit::Symbol
                && !["after", "scopeStart"].contains(&rule.visibility.as_str())
                || rule.emit != Emit::Symbol && !rule.visibility.is_empty()
            {
                return Err(schema("INVALID_VISIBILITY", &rule.id));
            }
            if rule.emit != Emit::Field && !rule.owner.is_empty() {
                return Err(schema("INVALID_OWNER", &rule.id));
            }
            if rule.depends_on.iter().collect::<BTreeSet<_>>().len() != rule.depends_on.len() {
                return Err(schema("DUPLICATE_DEPENDENCY", &rule.id));
            }
        }
        for rule in &rules {
            if rule.emit == Emit::Field
                && by_id
                    .get(&rule.owner)
                    .is_none_or(|owner| owner.emit != Emit::Type)
            {
                return Err(schema("INVALID_OWNER", &rule.id));
            }
            if rule.depends_on.iter().any(|id| !by_id.contains_key(id)) {
                return Err(schema("UNDEFINED_DEPENDENCY", &rule.id));
            }
        }
        let mut remaining: BTreeSet<_> = by_id.keys().cloned().collect();
        while !remaining.is_empty() {
            let ready: Vec<_> = remaining
                .iter()
                .filter(|id| {
                    by_id[*id]
                        .depends_on
                        .iter()
                        .all(|dep| !remaining.contains(dep))
                })
                .cloned()
                .collect();
            if ready.is_empty() {
                return Err(schema("CYCLIC_RULES", "rules"));
            }
            for id in ready {
                remaining.remove(&id);
            }
        }
        for rule in &rules {
            if rule
                .depends_on
                .iter()
                .any(|dep| phase(by_id[dep].emit) > phase(rule.emit))
            {
                return Err(schema("RULE_PHASE", &rule.id));
            }
        }
        Ok(Self {
            inventory,
            rules,
            unknown_literals: unknown_literals.into_iter().collect(),
        })
    }
    pub fn inventory(&self) -> &Inventory {
        &self.inventory
    }
    pub fn rules(&self) -> &[Rule] {
        &self.rules
    }
    pub fn unknown_literals(&self) -> &BTreeSet<String> {
        &self.unknown_literals
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Diagnostic {
    pub code: String,
    pub uri: String,
    pub version: i64,
    pub span: Span,
    pub rule: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Site {
    pub call: String,
    pub argument: usize,
    pub span: Span,
}
#[derive(Debug, Clone)]
pub struct Analysis {
    status: Status,
    model: Option<SemanticModel>,
    diagnostics: Vec<Diagnostic>,
    sites: Vec<Site>,
}
impl Analysis {
    pub fn status(&self) -> Status {
        self.status
    }
    pub fn model(&self) -> Option<&SemanticModel> {
        self.model.as_ref()
    }
    pub fn diagnostics(&self) -> &[Diagnostic] {
        &self.diagnostics
    }
    pub fn sites(&self) -> &[Site] {
        &self.sites
    }
}
#[derive(Debug)]
struct Failure {
    code: String,
    span: Span,
    rule: String,
}
fn fail(code: impl Into<String>, span: Span, rule: impl Into<String>) -> Failure {
    Failure {
        code: code.into(),
        span,
        rule: rule.into(),
    }
}
fn contains(a: Span, b: Span) -> bool {
    a.start <= b.start && b.end <= a.end
}
fn overlaps(a: Span, b: Span) -> bool {
    a.start.max(b.start) < a.end.min(b.end)
}
fn width(span: Span) -> usize {
    span.end - span.start
}
fn trim(value: &str) -> &str {
    value.trim_matches(|c| c <= '\u{20}')
}
fn slice(source: &str, span: Span) -> String {
    source.chars().skip(span.start).take(width(span)).collect()
}
fn healthy(cst: &EditorCst, node: &Node) -> bool {
    !node.synthetic
        && node.captures.iter().all(|c| !c.synthetic)
        && !cst
            .defects()
            .iter()
            .any(|d| d.kind == DefectKind::Error && overlaps(node.span, d.span))
}
struct Binding<'a> {
    rule: &'a Rule,
    node: &'a Node,
    id: String,
}
struct Value {
    text: String,
    span: Span,
    synthetic: bool,
}
fn values(binding: &Binding<'_>, key: &str) -> Vec<Value> {
    let Some(selector) = binding.rule.selectors.get(key) else {
        return vec![];
    };
    if selector.source == Source::Literal {
        return vec![Value {
            text: selector.name.clone(),
            span: binding.node.span,
            synthetic: false,
        }];
    }
    binding
        .node
        .captures
        .iter()
        .filter(|c| c.name == selector.name)
        .map(|c| Value {
            text: c.text.clone(),
            span: c.span,
            synthetic: c.synthetic,
        })
        .collect()
}
fn one(binding: &Binding<'_>, key: &str) -> Result<Value, Failure> {
    let mut selected = values(binding, key);
    if selected.len() != 1 {
        return Err(fail(
            "SELECTOR_CARDINALITY",
            binding.node.span,
            format!("{}.{}", binding.rule.id, key),
        ));
    }
    let mut value = selected.remove(0);
    value.text = trim(&value.text).into();
    if value.text.chars().count() > 1024 {
        return Err(fail(
            "LIMIT",
            value.span,
            format!("{}.{}", binding.rule.id, key),
        ));
    }
    if value.synthetic || value.text.is_empty() {
        return Err(fail(
            "INCOMPLETE_BINDING",
            value.span,
            format!("{}.{}", binding.rule.id, key),
        ));
    }
    Ok(value)
}
fn names(binding: &Binding<'_>, key: &str) -> Result<Vec<String>, Failure> {
    values(binding, key)
        .into_iter()
        .map(|value| {
            let text = trim(&value.text);
            if text.chars().count() > 1024 {
                return Err(fail(
                    "LIMIT",
                    value.span,
                    format!("{}.{}", binding.rule.id, key),
                ));
            }
            if value.synthetic || text.is_empty() {
                Err(fail(
                    "INCOMPLETE_BINDING",
                    value.span,
                    format!("{}.{}", binding.rule.id, key),
                ))
            } else {
                Ok(text.into())
            }
        })
        .collect()
}
fn containing<'a>(
    scopes: &'a [model::Scope],
    target: Span,
    excluded: &str,
) -> Result<&'a model::Scope, Failure> {
    let mut candidates: Vec<_> = scopes
        .iter()
        .filter(|s| s.id != excluded && contains(s.span, target))
        .collect();
    candidates.sort_by_key(|s| width(s.span));
    if candidates.len() > 1
        && width(candidates[0].span) == width(candidates[1].span)
        && candidates[0].id != "$root"
        && candidates[1].id != "$root"
    {
        return Err(fail("AMBIGUOUS_SCOPE", target, ""));
    }
    candidates
        .iter()
        .find(|s| s.id != "$root")
        .copied()
        .or_else(|| candidates.last().copied())
        .ok_or_else(|| fail("INVALID_ROOT_SCOPE", target, ""))
}
fn convert(error: model::ModelError) -> Failure {
    fail(error.code, error.span, "")
}
/// The current grammar inventory must accompany the CST; a stale mapping is never inferred from class names.
pub fn analyze(
    program: &Program,
    current: &Inventory,
    uri: &str,
    version: i64,
    cst: &EditorCst,
) -> Result<Analysis, String> {
    if uri.is_empty() || version < 0 || uri.chars().count() > 4096 {
        return Err("invalid semantic snapshot".into());
    }
    let mut diagnostics = vec![];
    match execute(program, current, uri, version, cst, &mut diagnostics) {
        Ok((model, sites)) => Ok(Analysis {
            status: if diagnostics.iter().any(|d| d.code.starts_with("INFERENCE_")) {
                Status::Partial
            } else {
                cst.status()
            },
            model: Some(model),
            diagnostics,
            sites,
        }),
        Err(failure) => {
            diagnostics.push(Diagnostic {
                code: failure.code,
                uri: uri.into(),
                version,
                span: failure.span,
                rule: failure.rule,
            });
            Ok(Analysis {
                status: Status::Failed,
                model: None,
                diagnostics,
                sites: vec![],
            })
        }
    }
}
fn execute(
    program: &Program,
    current: &Inventory,
    uri: &str,
    version: i64,
    cst: &EditorCst,
    diagnostics: &mut Vec<Diagnostic>,
) -> Result<(SemanticModel, Vec<Site>), Failure> {
    let document = Span {
        start: 0,
        end: cst.source().chars().count(),
    };
    if program.inventory != *current {
        return Err(fail("INVENTORY_MISMATCH", document, ""));
    }
    if cst.status() == Status::Failed {
        return Err(fail(format!("PARSE_{}", cst.reason().name()), document, ""));
    }
    if cst.nodes().len() > 16384
        || cst.nodes().iter().map(|n| n.captures.len()).sum::<usize>() > 65536
    {
        return Err(fail("LIMIT", document, ""));
    }
    let mut capture_bytes = 0;
    for node in cst.nodes() {
        for capture in &node.captures {
            capture_bytes += capture.text.len();
            if capture_bytes > 4194304 {
                return Err(fail("LIMIT", document, ""));
            }
        }
    }
    let mut nodes: Vec<_> = cst.nodes().iter().collect();
    nodes.sort_by(|a, b| {
        (a.span.start, a.span.end, &a.rule).cmp(&(b.span.start, b.span.end, &b.rule))
    });
    let mut bindings = vec![];
    for node in nodes {
        let shape = current
            .nodes
            .get(&node.rule)
            .ok_or_else(|| fail("CST_INVENTORY", node.span, &node.rule))?;
        if !contains(document, node.span) {
            return Err(fail("CST_INVENTORY", node.span, &node.rule));
        }
        for capture in &node.captures {
            if !shape.captures.contains(&capture.name)
                || !contains(node.span, capture.span)
                || slice(cst.source(), capture.span) != capture.text
            {
                return Err(fail("CST_CAPTURE", node.span, &node.rule));
            }
        }
        for rule in &program.rules {
            if rule.node == node.rule {
                if bindings.len() >= 4096 {
                    return Err(fail("LIMIT", node.span, &rule.id));
                }
                bindings.push(Binding {
                    rule,
                    node,
                    id: format!("{}@{}:{}", rule.id, node.span.start, node.span.end),
                });
            }
        }
    }
    let mut identities = BTreeSet::new();
    for b in &bindings {
        if !identities.insert(&b.id) {
            return Err(fail("AMBIGUOUS_BINDING", b.node.span, &b.rule.id));
        }
    }
    let mut data = model::ModelData::default();
    data.scopes.push(model::Scope {
        id: "$root".into(),
        parent: None,
        span: document,
    });
    let mut scope_bindings: Vec<_> = bindings
        .iter()
        .filter(|b| b.rule.emit == Emit::Scope)
        .collect();
    scope_bindings.sort_by_key(|b| std::cmp::Reverse(width(b.node.span)));
    for b in scope_bindings {
        if width(b.node.span) == 0 {
            continue;
        }
        if data.scopes.len() >= 512 {
            return Err(fail("LIMIT", b.node.span, &b.rule.id));
        }
        if data
            .scopes
            .iter()
            .any(|s| s.id != "$root" && s.span == b.node.span)
        {
            return Err(fail("AMBIGUOUS_SCOPE", b.node.span, &b.rule.id));
        }
        let parent = containing(&data.scopes, b.node.span, "")?.id.clone();
        data.scopes.push(model::Scope {
            id: b.id.clone(),
            parent: Some(parent),
            span: b.node.span,
        });
    }
    let type_bindings: Vec<_> = bindings
        .iter()
        .filter(|b| b.rule.emit == Emit::Type && healthy(cst, b.node))
        .collect();
    if type_bindings.len() > 512 {
        return Err(fail("LIMIT", document, ""));
    }
    let mut fields: BTreeMap<String, Vec<model::Field>> = BTreeMap::new();
    for b in &bindings {
        if b.rule.emit == Emit::Field && healthy(cst, b.node) {
            let mut owners: Vec<_> = type_bindings
                .iter()
                .filter(|t| t.rule.id == b.rule.owner && contains(t.node.span, b.node.span))
                .collect();
            owners.sort_by_key(|t| width(t.node.span));
            let Some(owner) = owners.first() else {
                diagnostics.push(Diagnostic {
                    code: "INCOMPLETE_OWNER".into(),
                    uri: uri.into(),
                    version,
                    span: b.node.span,
                    rule: b.rule.id.clone(),
                });
                continue;
            };
            if owners.len() > 1 && owner.node.span == owners[1].node.span {
                return Err(fail("AMBIGUOUS_OWNER", b.node.span, &b.rule.id));
            }
            fields
                .entry(owner.id.clone())
                .or_default()
                .push(model::Field {
                    name: one(b, "name")?.text,
                    type_id: one(b, "type")?.text,
                    span: b.node.span,
                });
        }
    }
    for b in type_bindings {
        if containing(&data.scopes, b.node.span, "")?.id != "$root" {
            return Err(fail("TYPE_SCOPE_UNSUPPORTED", b.node.span, &b.rule.id));
        }
        let kind = one(b, "kind")?;
        let kind = match kind.text.to_ascii_uppercase().as_str() {
            "BUILTIN" => model::TypeKind::Builtin,
            "RECORD" => model::TypeKind::Record,
            "INTERFACE" => model::TypeKind::Interface,
            _ => return Err(fail("INVALID_TYPE_KIND", kind.span, &b.rule.id)),
        };
        data.types.push(model::Type {
            id: one(b, "name")?.text,
            kind,
            supertypes: names(b, "parents")?,
            fields: fields.remove(&b.id).unwrap_or_default(),
            span: b.node.span,
        });
    }
    let mut signature_scopes = BTreeMap::new();
    let mut expressions = BTreeMap::new();
    let mut references = BTreeMap::new();
    for b in &bindings {
        if healthy(cst, b.node) {
            let scope = containing(&data.scopes, b.node.span, "")?;
            match b.rule.emit {
                Emit::Symbol => data.symbols.push(model::Symbol {
                    id: b.id.clone(),
                    name: one(b, "name")?.text,
                    type_id: one(b, "type")?.text,
                    scope: scope.id.clone(),
                    declaration: b.node.span,
                    visible_from: if b.rule.visibility == "after" {
                        b.node.span.end
                    } else {
                        scope.span.start
                    },
                }),
                Emit::Signature => {
                    data.signatures.push(model::Signature {
                        id: b.id.clone(),
                        name: one(b, "name")?.text,
                        parameters: names(b, "parameters")?,
                        result: one(b, "result")?.text,
                        span: b.node.span,
                    });
                    signature_scopes.insert(b.id.clone(), scope.id.clone());
                }
                Emit::Expression => {
                    if references.contains_key(&(b.node.span.start, b.node.span.end))
                        || expressions
                            .insert((b.node.span.start, b.node.span.end), one(b, "type")?.text)
                            .is_some()
                    {
                        return Err(fail("AMBIGUOUS_EXPRESSION", b.node.span, &b.rule.id));
                    }
                }
                Emit::Reference => {
                    let key = (b.node.span.start, b.node.span.end);
                    if expressions.contains_key(&key)
                        || references.insert(key, one(b, "name")?.text).is_some()
                    {
                        return Err(fail("AMBIGUOUS_EXPRESSION", b.node.span, &b.rule.id));
                    }
                }
                _ => {}
            }
        }
    }
    let preliminary = SemanticModel::new(uri.into(), version, cst.source().into(), data.clone())
        .map_err(convert)?;
    for ((start, end), type_id) in &expressions {
        if type_id != UNKNOWN && !data.types.iter().any(|t| &t.id == type_id) {
            return Err(fail(
                "UNDEFINED_TYPE",
                Span {
                    start: *start,
                    end: *end,
                },
                "",
            ));
        }
    }
    let mut sites = vec![];
    for b in &bindings {
        if b.rule.emit == Emit::Call {
            let name = match one(b, "name") {
                Ok(name) => name,
                Err(failure) if failure.code == "INCOMPLETE_BINDING" => {
                    diagnostics.push(Diagnostic {
                        code: failure.code,
                        uri: uri.into(),
                        version,
                        span: failure.span,
                        rule: failure.rule,
                    });
                    continue;
                }
                Err(failure) => return Err(failure),
            };
            if b.rule.selectors["name"].source != Source::Literal
                && cst
                    .defects()
                    .iter()
                    .any(|d| d.kind == DefectKind::Error && overlaps(name.span, d.span))
            {
                diagnostics.push(Diagnostic {
                    code: "INCOMPLETE_BINDING".into(),
                    uri: uri.into(),
                    version,
                    span: name.span,
                    rule: format!("{}.name", b.rule.id),
                });
                continue;
            }
            let mut arguments = vec![];
            for value in values(b, "arguments") {
                let mut type_id = UNKNOWN.into();
                let damaged = value.synthetic
                    || cst
                        .defects()
                        .iter()
                        .any(|d| d.kind == DefectKind::Error && overlaps(value.span, d.span));
                let text = trim(&value.text);
                if !damaged && !program.unknown_literals.contains(text) {
                    type_id = if let Some(type_id) =
                        expressions.get(&(value.span.start, value.span.end))
                    {
                        type_id.clone()
                    } else {
                        let reference = references
                            .get(&(value.span.start, value.span.end))
                            .map(String::as_str)
                            .unwrap_or(text);
                        let resolved = preliminary
                            .visible_symbols_at(value.span.start)
                            .map_err(convert)?
                            .iter()
                            .find(|s| s.name == reference)
                            .map(|s| s.type_id.clone())
                            .unwrap_or_else(|| UNKNOWN.into());
                        if resolved == UNKNOWN {
                            diagnostics.push(Diagnostic {
                                code: "UNRESOLVED_REFERENCE".into(),
                                uri: uri.into(),
                                version,
                                span: value.span,
                                rule: b.rule.id.clone(),
                            });
                        }
                        resolved
                    };
                }
                sites.push(Site {
                    call: b.id.clone(),
                    argument: arguments.len(),
                    span: value.span,
                });
                arguments.push(model::Argument {
                    type_id,
                    span: value.span,
                });
            }
            let mut scope = Some(containing(&data.scopes, b.node.span, "")?);
            let mut candidates = vec![];
            while let Some(current) = scope {
                candidates = data
                    .signatures
                    .iter()
                    .filter(|s| s.name == name.text && signature_scopes[&s.id] == current.id)
                    .map(|s| s.id.clone())
                    .collect();
                if !candidates.is_empty() {
                    break;
                }
                scope = current
                    .parent
                    .as_ref()
                    .and_then(|parent| data.scopes.iter().find(|s| &s.id == parent));
            }
            if candidates.is_empty() {
                diagnostics.push(Diagnostic {
                    code: "UNRESOLVED_CALL".into(),
                    uri: uri.into(),
                    version,
                    span: name.span,
                    rule: b.rule.id.clone(),
                });
            }
            data.calls.push(model::Call {
                id: b.id.clone(),
                signatures: candidates,
                arguments,
                span: b.node.span,
            });
        }
    }
    let model =
        SemanticModel::new(uri.into(), version, cst.source().into(), data).map_err(convert)?;
    let types = type_system(&model);
    let inference = inference::CallInference::new(&types, 4096).expect("fixed budget");
    for call in &model.data().calls {
        let resolution = inference
            .infer(&signatures(&model, call), &call_input(&model, call))
            .expect("validated model");
        let code = match resolution.state {
            inference::State::Incompatible => Some("INCOMPATIBLE_CALL"),
            inference::State::Ambiguous => Some("AMBIGUOUS_CALL"),
            inference::State::Limit => Some("INFERENCE_LIMIT"),
            inference::State::Unsupported => Some("INFERENCE_UNSUPPORTED"),
            inference::State::Cycle => Some("INFERENCE_CYCLE"),
            inference::State::Invalid => Some("INFERENCE_INVALID"),
            _ => None,
        };
        if let Some(code) = code {
            diagnostics.push(Diagnostic {
                code: code.into(),
                uri: uri.into(),
                version,
                span: call.span,
                rule: call.id.clone(),
            });
        }
    }
    Ok((model, sites))
}
fn type_ref(name: &str) -> types::TypeRef {
    if name == UNKNOWN {
        types::TypeRef::unknown()
    } else {
        types::TypeRef::named(name.into(), vec![]).expect("validated model")
    }
}
fn type_system(model: &SemanticModel) -> types::TypeSystem<types::DeclaredProvider> {
    let definitions = model
        .data()
        .types
        .iter()
        .map(|t| types::Definition {
            name: t.id.clone(),
            parameters: vec![],
            variance: vec![],
            parents: t.supertypes.iter().map(|p| type_ref(p)).collect(),
            fields: BTreeMap::new(),
            structural: false,
        })
        .collect();
    types::TypeSystem::new(
        types::DeclaredProvider::new(
            types::Policy::Nominal,
            definitions,
            BTreeSet::from([types::Capability::Named]),
        )
        .expect("validated model"),
        vec![],
        4096,
    )
    .expect("fixed budget")
}
fn signatures(model: &SemanticModel, call: &model::Call) -> Vec<inference::Signature> {
    call.signatures
        .iter()
        .map(|id| {
            model
                .data()
                .signatures
                .iter()
                .find(|s| &s.id == id)
                .expect("validated model")
        })
        .map(|s| inference::Signature {
            id: s.id.clone(),
            variables: vec![],
            parameters: s.parameters.iter().map(|p| type_ref(p)).collect(),
            result: type_ref(&s.result),
            varargs: false,
            uri: model.uri().into(),
            version: model.version(),
            span: s.span,
        })
        .collect()
}
fn call_input(model: &SemanticModel, call: &model::Call) -> inference::Call {
    inference::Call {
        uri: model.uri().into(),
        version: model.version(),
        span: call.span,
        arguments: call
            .arguments
            .iter()
            .map(|a| inference::Argument {
                type_ref: type_ref(&a.type_id),
                span: a.span,
            })
            .collect(),
        expected_return: types::TypeRef::unknown(),
    }
}
#[derive(Debug, Clone)]
pub struct Query {
    pub resolution: inference::Resolution,
    pub expected: Vec<types::TypeRef>,
    pub completions: Vec<inference::Completion>,
    pub edit: Span,
}
pub fn query(
    analysis: &Analysis,
    uri: &str,
    version: i64,
    cursor: usize,
    prefix: &str,
) -> Result<Option<Query>, String> {
    let Some(model) = &analysis.model else {
        return Ok(None);
    };
    if model.uri() != uri || model.version() != version {
        return Err("STALE_SNAPSHOT".into());
    }
    if cursor > model.source().chars().count() {
        return Err("INVALID_CURSOR".into());
    }
    let sites: Vec<_> = analysis
        .sites
        .iter()
        .filter(|s| s.span.start <= cursor && cursor <= s.span.end)
        .collect();
    if sites.is_empty() {
        return Ok(None);
    }
    if sites.len() != 1 {
        return Err("AMBIGUOUS_CALL_SITE".into());
    }
    let site = sites[0];
    let start = cursor
        .checked_sub(prefix.chars().count())
        .ok_or("INVALID_PREFIX")?;
    let edit = Span { start, end: cursor };
    if start < site.span.start || slice(model.source(), edit) != prefix {
        return Err("INVALID_PREFIX".into());
    }
    let call = model
        .data()
        .calls
        .iter()
        .find(|c| c.id == site.call)
        .expect("validated analysis");
    let types = type_system(model);
    let inference = inference::CallInference::new(&types, 4096)?;
    let resolution = inference.expected_argument(
        &signatures(model, call),
        &call_input(model, call),
        site.argument,
    )?;
    let values = model
        .visible_symbols_at(cursor)
        .map_err(|e| e.to_string())?
        .iter()
        .filter(|s| s.name.starts_with(prefix))
        .map(|s| inference::Value {
            id: s.id.clone(),
            name: s.name.clone(),
            type_ref: type_ref(&s.type_id),
            uri: uri.into(),
            version,
            span: s.declaration,
        })
        .collect::<Vec<_>>();
    let expected = inference.expected_types(&resolution, site.argument);
    let completions = inference.complete(&values, &resolution, site.argument);
    Ok(Some(Query {
        resolution,
        expected,
        completions,
        edit,
    }))
}

/// Exact language/package identity stays outside portable JSON semantics and is checked at dispatch.
pub struct QueryProvider<F>
where
    F: Fn(&crate::language_queries::Request<'_>) -> crate::source::Result<EditorCst>,
{
    program: Program,
    inventory: Inventory,
    language: crate::source::Language,
    project: String,
    version: u64,
    parser: F,
}
impl<F> QueryProvider<F>
where
    F: Fn(&crate::language_queries::Request<'_>) -> crate::source::Result<EditorCst>,
{
    pub fn new(
        program: Program,
        inventory: Inventory,
        language: crate::source::Language,
        project: String,
        version: u64,
        parser: F,
    ) -> crate::source::Result<Self> {
        if project.is_empty()
            || [
                &language.id,
                &language.package_id,
                &language.version,
                &language.grammar,
                &language.entry,
            ]
            .iter()
            .any(|s| s.is_empty())
            || language.grammar != inventory.grammar
            || !inventory.nodes.contains_key(&language.entry)
            || program.inventory != inventory
        {
            return Err("invalid semantic provider binding");
        }
        Ok(Self {
            program,
            inventory,
            language,
            project,
            version,
            parser,
        })
    }
}
impl<F> crate::language_queries::Provider for QueryProvider<F>
where
    F: Fn(&crate::language_queries::Request<'_>) -> crate::source::Result<EditorCst>,
{
    fn capabilities(&self) -> std::collections::HashSet<crate::source::Operation> {
        [
            crate::source::Operation::Validate,
            crate::source::Operation::Completion,
        ]
        .into()
    }
    fn query(
        &self,
        request: &crate::language_queries::Request<'_>,
    ) -> crate::source::Result<crate::language_queries::Response> {
        use crate::{
            language_queries::{Item, Response, TextEdit},
            source::{Location, Operation, State},
        };
        if request.project.id != self.project
            || request.project.version != self.version
            || request.region.language != self.language
        {
            return Err("stale semantic provider binding");
        }
        let snapshot = request.region.source_map.output();
        snapshot.check(Span {
            start: request.cursor,
            end: request.cursor,
        })?;
        let response = |state, items| Response {
            snapshot: snapshot.clone(),
            project: self.project.clone(),
            project_version: self.version,
            state,
            items,
        };
        if !self.capabilities().contains(&request.operation) {
            return Ok(response(State::Unsupported, vec![]));
        }
        let cst = (self.parser)(request)?;
        if cst.source() != snapshot.text {
            return Err("stale semantic parser result");
        }
        let version = i64::try_from(snapshot.version).map_err(|_| "invalid semantic snapshot")?;
        let analysis = analyze(&self.program, &self.inventory, &snapshot.uri, version, &cst)
            .map_err(|_| "invalid semantic snapshot")?;
        let state = match analysis.status {
            Status::Complete => State::Complete,
            Status::Partial => State::Partial,
            Status::Failed => State::Failed,
        };
        let mut items = vec![];
        if request.operation == Operation::Validate {
            for diagnostic in &analysis.diagnostics {
                items.push(Item {
                    label: diagnostic.code.clone(),
                    detail: diagnostic.rule.clone(),
                    locations: vec![Location::new(snapshot.clone(), diagnostic.span)?],
                    edits: vec![],
                });
            }
        } else if let Some(value) = query(
            &analysis,
            &snapshot.uri,
            version,
            request.cursor,
            request
                .parameters
                .get("prefix")
                .map(String::as_str)
                .unwrap_or(""),
        )
        .map_err(|_| "invalid semantic query")?
        {
            let expected = value
                .expected
                .iter()
                .map(|t| t.name())
                .collect::<Vec<_>>()
                .join(",");
            for completion in value.completions {
                let symbol = completion.value;
                items.push(Item {
                    label: symbol.name.clone(),
                    detail: format!(
                        "{}; expected={}; {}:{}",
                        symbol.type_ref.name(),
                        expected,
                        completion.decision.status.name(),
                        completion.decision.rule
                    ),
                    locations: vec![Location::new(snapshot.clone(), symbol.span)?],
                    edits: vec![TextEdit {
                        location: Location::new(snapshot.clone(), value.edit)?,
                        replacement: symbol.name,
                    }],
                });
            }
        }
        Ok(response(state, items))
    }
}
