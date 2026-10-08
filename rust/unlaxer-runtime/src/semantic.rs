//! Immutable nominal types and argument completion; see docs/semantic-model.md.
use crate::Span;
use std::collections::{BTreeMap, BTreeSet, HashSet, VecDeque};

pub const UNKNOWN: &str = "?";
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Compatibility {
    Yes,
    Unknown,
    No,
}
impl Compatibility {
    pub fn name(self) -> &'static str {
        match self {
            Self::Yes => "YES",
            Self::Unknown => "UNKNOWN",
            Self::No => "NO",
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TypeKind {
    Builtin,
    Interface,
    Record,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Field {
    pub name: String,
    pub type_id: String,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Type {
    pub id: String,
    pub kind: TypeKind,
    pub supertypes: Vec<String>,
    pub fields: Vec<Field>,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Scope {
    pub id: String,
    pub parent: Option<String>,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Symbol {
    pub id: String,
    pub name: String,
    pub type_id: String,
    pub scope: String,
    pub declaration: Span,
    pub visible_from: usize,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Signature {
    pub id: String,
    pub name: String,
    pub parameters: Vec<String>,
    pub result: String,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Argument {
    pub type_id: String,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Call {
    pub id: String,
    pub signatures: Vec<String>,
    pub arguments: Vec<Argument>,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Completion {
    pub symbol: Symbol,
    pub compatibility: Compatibility,
    pub expected_types: Vec<String>,
}
impl Completion {
    pub fn reason(&self) -> String {
        format!(
            "{} -> {}: {}",
            self.symbol.type_id,
            self.expected_types.join(" | "),
            self.compatibility.name()
        )
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelError {
    pub code: &'static str,
    pub span: Span,
}
impl std::fmt::Display for ModelError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} at {}..{}", self.code, self.span.start, self.span.end)
    }
}
impl std::error::Error for ModelError {}
type Result<T> = std::result::Result<T, ModelError>;
fn error(code: &'static str, span: Span) -> ModelError {
    ModelError { code, span }
}
fn contains(parent: Span, child: Span) -> bool {
    parent.start <= child.start && child.end <= parent.end
}
fn name(value: &str, span: Span) -> Result<()> {
    if value.is_empty() {
        Err(error("EMPTY_NAME", span))
    } else {
        Ok(())
    }
}
#[derive(Debug, Clone, Default)]
pub struct ModelData {
    pub types: Vec<Type>,
    pub scopes: Vec<Scope>,
    pub symbols: Vec<Symbol>,
    pub signatures: Vec<Signature>,
    pub calls: Vec<Call>,
}
#[derive(Debug, Clone)]
pub struct SemanticModel {
    uri: String,
    version: i64,
    source: String,
    length: usize,
    root: String,
    data: ModelData,
}
impl SemanticModel {
    pub fn new(uri: String, version: i64, source: String, data: ModelData) -> Result<Self> {
        let length = source.chars().count();
        let document = Span {
            start: 0,
            end: length,
        };
        if uri.is_empty() || version < 0 || length > i32::MAX as usize {
            return Err(error("INVALID_DOCUMENT", document));
        }
        let mut model = Self {
            uri,
            version,
            source,
            length,
            root: String::new(),
            data,
        };
        model.index(
            model.data.types.iter().map(|x| (&x.id, x.span)),
            "DUPLICATE_TYPE",
        )?;
        model.index(
            model.data.scopes.iter().map(|x| (&x.id, x.span)),
            "DUPLICATE_SCOPE",
        )?;
        model.index(
            model.data.symbols.iter().map(|x| (&x.id, x.declaration)),
            "DUPLICATE_SYMBOL",
        )?;
        model.index(
            model.data.signatures.iter().map(|x| (&x.id, x.span)),
            "DUPLICATE_SIGNATURE",
        )?;
        model.index(
            model.data.calls.iter().map(|x| (&x.id, x.span)),
            "DUPLICATE_CALL",
        )?;
        for t in &model.data.types {
            if t.id == UNKNOWN {
                return Err(error("INVALID_TYPE", t.span));
            }
            let mut parents = HashSet::new();
            for parent in &t.supertypes {
                let target = model
                    .type_named(parent)
                    .ok_or_else(|| error("UNDEFINED_TYPE", t.span))?;
                if t.kind == TypeKind::Builtin || target.kind != TypeKind::Interface {
                    return Err(error("INVALID_SUPERTYPE", t.span));
                }
                if !parents.insert(parent) {
                    return Err(error("DUPLICATE_SUPERTYPE", t.span));
                }
            }
            let mut fields = HashSet::new();
            for field in &t.fields {
                model.check_span(field.span)?;
                name(&field.name, field.span)?;
                if !contains(t.span, field.span) {
                    return Err(error("FIELD_OUTSIDE_TYPE", field.span));
                }
                if !fields.insert(&field.name) {
                    return Err(error("DUPLICATE_FIELD", field.span));
                }
                model.check_type(&field.type_id, field.span)?;
            }
        }
        for t in &model.data.types {
            let mut pending: VecDeque<_> = t.supertypes.iter().collect();
            let mut seen = HashSet::new();
            while let Some(next) = pending.pop_front() {
                if next == &t.id {
                    return Err(error("CYCLIC_TYPE", t.span));
                }
                if seen.insert(next) {
                    pending.extend(&model.type_named(next).expect("validated type").supertypes);
                }
            }
        }
        let roots: Vec<_> = model
            .data
            .scopes
            .iter()
            .filter(|s| s.parent.is_none())
            .collect();
        if roots.len() != 1 || roots[0].span != document {
            return Err(error("INVALID_ROOT_SCOPE", document));
        }
        model.root = roots[0].id.clone();
        for scope in &model.data.scopes {
            if scope.parent.is_some() && scope.span.start == scope.span.end {
                return Err(error("EMPTY_SCOPE", scope.span));
            }
            let mut seen = HashSet::new();
            let mut current = scope;
            while let Some(id) = &current.parent {
                if !seen.insert(&current.id) {
                    return Err(error("CYCLIC_SCOPE", scope.span));
                }
                let parent = model
                    .scope_named(id)
                    .ok_or_else(|| error("UNDEFINED_SCOPE", scope.span))?;
                if !contains(parent.span, current.span) {
                    return Err(error("SCOPE_OUTSIDE_PARENT", current.span));
                }
                current = parent;
            }
            for sibling in &model.data.scopes {
                if scope.id != sibling.id
                    && scope.parent == sibling.parent
                    && scope.span.start.max(sibling.span.start)
                        < scope.span.end.min(sibling.span.end)
                {
                    return Err(error("OVERLAPPING_SCOPES", scope.span));
                }
            }
        }
        let mut declared = HashSet::new();
        for symbol in &model.data.symbols {
            name(&symbol.name, symbol.declaration)?;
            model.check_type(&symbol.type_id, symbol.declaration)?;
            let scope = model
                .scope_named(&symbol.scope)
                .ok_or_else(|| error("UNDEFINED_SCOPE", symbol.declaration))?;
            if !contains(scope.span, symbol.declaration)
                || symbol.visible_from < scope.span.start
                || symbol.visible_from > scope.span.end
            {
                return Err(error("SYMBOL_OUTSIDE_SCOPE", symbol.declaration));
            }
            if !declared.insert((&symbol.scope, &symbol.name)) {
                return Err(error("DUPLICATE_SYMBOL_NAME", symbol.declaration));
            }
        }
        for signature in &model.data.signatures {
            name(&signature.name, signature.span)?;
            model.check_type(&signature.result, signature.span)?;
            for t in &signature.parameters {
                model.check_type(t, signature.span)?;
            }
        }
        for call in &model.data.calls {
            let mut seen = HashSet::new();
            for id in &call.signatures {
                if model.signature_named(id).is_none() {
                    return Err(error("UNDEFINED_SIGNATURE", call.span));
                }
                if !seen.insert(id) {
                    return Err(error("DUPLICATE_CALL_SIGNATURE", call.span));
                }
            }
            let mut end = None;
            for argument in &call.arguments {
                model.check_span(argument.span)?;
                model.check_type(&argument.type_id, argument.span)?;
                if !contains(call.span, argument.span)
                    || end.is_some_and(|n| argument.span.start <= n)
                {
                    return Err(error("INVALID_ARGUMENT_SPAN", argument.span));
                }
                end = Some(argument.span.end);
            }
        }
        Ok(model)
    }
    fn check_span(&self, span: Span) -> Result<()> {
        if span.end < span.start {
            return Err(error("INVALID_SPAN", span));
        }
        if span.end > self.length {
            return Err(error("SPAN_OUTSIDE_DOCUMENT", span));
        }
        Ok(())
    }
    fn index<'a>(
        &self,
        items: impl Iterator<Item = (&'a String, Span)>,
        duplicate: &'static str,
    ) -> Result<()> {
        let mut seen = HashSet::new();
        for (id, span) in items {
            self.check_span(span)?;
            name(id, span)?;
            if !seen.insert(id) {
                return Err(error(duplicate, span));
            }
        }
        Ok(())
    }
    fn check_type(&self, id: &str, span: Span) -> Result<()> {
        if id != UNKNOWN && self.type_named(id).is_none() {
            Err(error("UNDEFINED_TYPE", span))
        } else {
            Ok(())
        }
    }
    fn type_named(&self, id: &str) -> Option<&Type> {
        self.data.types.iter().find(|t| t.id == id)
    }
    fn scope_named(&self, id: &str) -> Option<&Scope> {
        self.data.scopes.iter().find(|s| s.id == id)
    }
    fn signature_named(&self, id: &str) -> Option<&Signature> {
        self.data.signatures.iter().find(|s| s.id == id)
    }
    pub fn uri(&self) -> &str {
        &self.uri
    }
    pub fn version(&self) -> i64 {
        self.version
    }
    pub fn source(&self) -> &str {
        &self.source
    }
    pub fn data(&self) -> &ModelData {
        &self.data
    }
    pub fn is_assignable(&self, actual: &str, expected: &str) -> Result<Compatibility> {
        let zero = Span { start: 0, end: 0 };
        self.check_type(actual, zero)?;
        self.check_type(expected, zero)?;
        if actual == UNKNOWN || expected == UNKNOWN {
            return Ok(Compatibility::Unknown);
        }
        let mut pending = VecDeque::from([actual]);
        let mut seen = HashSet::new();
        while let Some(next) = pending.pop_front() {
            if next == expected {
                return Ok(Compatibility::Yes);
            }
            if seen.insert(next) {
                pending.extend(
                    self.type_named(next)
                        .expect("validated type")
                        .supertypes
                        .iter()
                        .map(String::as_str),
                );
            }
        }
        Ok(Compatibility::No)
    }
    fn at(&self, scope: &Scope, cursor: usize) -> bool {
        scope.span.start <= cursor
            && (cursor < scope.span.end || cursor == self.length && scope.span.end == self.length)
    }
    pub fn visible_symbols_at(&self, cursor: usize) -> Result<Vec<&Symbol>> {
        if cursor > self.length {
            return Err(error(
                "INVALID_CURSOR",
                Span {
                    start: cursor,
                    end: cursor,
                },
            ));
        }
        let mut scope = self.scope_named(&self.root).expect("validated root");
        while let Some(child) = self
            .data
            .scopes
            .iter()
            .find(|s| s.parent.as_deref() == Some(&scope.id) && self.at(s, cursor))
        {
            scope = child;
        }
        let mut visible = BTreeMap::new();
        loop {
            for symbol in &self.data.symbols {
                if symbol.scope == scope.id && symbol.visible_from <= cursor {
                    visible.entry(&symbol.name).or_insert(symbol);
                }
            }
            match &scope.parent {
                Some(id) => scope = self.scope_named(id).expect("validated parent"),
                None => break,
            }
        }
        Ok(visible.into_values().collect())
    }
    fn call(&self, id: &str, index: usize) -> Result<&Call> {
        let call = self
            .data
            .calls
            .iter()
            .find(|c| c.id == id)
            .ok_or_else(|| error("UNDEFINED_CALL", Span { start: 0, end: 0 }))?;
        if index >= call.arguments.len() {
            return Err(error("INVALID_ARGUMENT_INDEX", call.span));
        }
        Ok(call)
    }
    pub fn expected_types(&self, call_id: &str, index: usize) -> Result<Vec<String>> {
        let call = self.call(call_id, index)?;
        if call.signatures.is_empty() {
            return Ok(vec![UNKNOWN.to_owned()]);
        }
        let mut expected = BTreeSet::new();
        for id in &call.signatures {
            let signature = self.signature_named(id).expect("validated signature");
            if index >= signature.parameters.len()
                || call.arguments.len() > signature.parameters.len()
            {
                continue;
            }
            let mut viable = true;
            for (i, argument) in call.arguments.iter().enumerate() {
                if i != index
                    && self.is_assignable(&argument.type_id, &signature.parameters[i])?
                        == Compatibility::No
                {
                    viable = false;
                    break;
                }
            }
            if viable {
                expected.insert(signature.parameters[index].clone());
            }
        }
        Ok(expected.into_iter().collect())
    }
    pub fn complete_argument(
        &self,
        call_id: &str,
        index: usize,
        cursor: usize,
        version: i64,
        prefix: &str,
    ) -> Result<Vec<Completion>> {
        let call = self.call(call_id, index)?;
        if version != self.version {
            return Err(error("STALE_SNAPSHOT", call.span));
        }
        let slot = call.arguments[index].span;
        if cursor < slot.start || cursor > slot.end {
            return Err(error("CURSOR_OUTSIDE_ARGUMENT", slot));
        }
        let expected = self.expected_types(call_id, index)?;
        let mut result = Vec::new();
        for symbol in self.visible_symbols_at(cursor)? {
            if !symbol.name.starts_with(prefix) {
                continue;
            }
            let mut best = Compatibility::No;
            for t in &expected {
                best = best.min(self.is_assignable(&symbol.type_id, t)?);
            }
            if best != Compatibility::No {
                result.push(Completion {
                    symbol: symbol.clone(),
                    compatibility: best,
                    expected_types: expected.clone(),
                });
            }
        }
        result.sort_by(|a, b| {
            (&a.compatibility, &a.symbol.name, &a.symbol.id).cmp(&(
                &b.compatibility,
                &b.symbol.name,
                &b.symbol.id,
            ))
        });
        Ok(result)
    }
}
