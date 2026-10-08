//! Immutable offline project symbols. See docs/project-symbol-index.md.
use crate::semantic::{Compatibility, Scope, SemanticModel, Symbol, Type, UNKNOWN};
use crate::Span;
use std::collections::{BTreeMap, BTreeSet, VecDeque};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModuleRef {
    pub dependency: String,
    pub module: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Import {
    pub name: String,
    pub target: ModuleRef,
    pub symbol: String,
    pub scope: String,
    pub span: Span,
    pub visible_from: usize,
}
#[derive(Debug, Clone)]
pub struct Module {
    pub id: String,
    pub model: SemanticModel,
    pub exports: BTreeSet<String>,
    pub imports: Vec<Import>,
}
#[derive(Debug, Clone)]
pub struct Dependency {
    pub id: String,
    pub version: String,
    pub sha256: String,
    pub modules: Vec<Module>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Identity {
    pub project: String,
    pub dependency: String,
    pub dependency_version: String,
    pub module: String,
    pub symbol: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Definition {
    pub identity: Identity,
    pub uri: String,
    pub version: i64,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub name: String,
    pub type_id: String,
    pub definition: Definition,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Diagnostic {
    pub code: &'static str,
    pub uri: String,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Binding {
    pub name: String,
    pub candidates: Vec<Candidate>,
    pub diagnostics: Vec<Diagnostic>,
}
impl Binding {
    pub fn status(&self) -> &'static str {
        if self.candidates.len() > 1
            || self
                .diagnostics
                .iter()
                .any(|d| d.code == "AMBIGUOUS_IMPORT")
        {
            "AMBIGUOUS"
        } else if !self.diagnostics.is_empty() || self.candidates.is_empty() {
            "UNRESOLVED"
        } else {
            "RESOLVED"
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Edit {
    pub uri: String,
    pub version: i64,
    pub span: Span,
    pub text: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Completion {
    pub candidate: Candidate,
    pub compatibility: Compatibility,
    pub reason: String,
    pub edit: Edit,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectError(pub Diagnostic);
impl std::fmt::Display for ProjectError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{} at {}:{}..{}",
            self.0.code, self.0.uri, self.0.span.start, self.0.span.end
        )
    }
}
impl std::error::Error for ProjectError {}
type Result<T> = std::result::Result<T, ProjectError>;
fn error(code: &'static str, uri: &str, span: Span) -> ProjectError {
    ProjectError(Diagnostic {
        code,
        uri: uri.into(),
        span,
    })
}
fn zero() -> Span {
    Span { start: 0, end: 0 }
}
type Key = (String, String);

#[derive(Debug, Clone)]
pub struct ProjectSymbolIndex {
    project: String,
    version: i64,
    project_modules: Vec<Module>,
    dependencies: Vec<Dependency>,
    modules: BTreeMap<Key, Module>,
    libraries: BTreeMap<String, Dependency>,
    types: BTreeMap<String, Type>,
}
impl ProjectSymbolIndex {
    pub fn new(
        project: String,
        version: i64,
        modules: Vec<Module>,
        dependencies: Vec<Dependency>,
    ) -> Result<Self> {
        if project.is_empty() || version < 0 {
            return Err(error("INVALID_PROJECT", "", zero()));
        }
        let mut result = Self {
            project,
            version,
            project_modules: modules.clone(),
            dependencies: dependencies.clone(),
            modules: BTreeMap::new(),
            libraries: BTreeMap::new(),
            types: BTreeMap::new(),
        };
        for module in modules {
            result.add("", module)?;
        }
        for dependency in dependencies {
            if dependency.id.is_empty()
                || !dependency
                    .version
                    .as_bytes()
                    .first()
                    .is_some_and(u8::is_ascii_digit)
                || !dependency
                    .version
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._+-".contains(&b))
                || dependency.sha256.len() != 64
                || !dependency
                    .sha256
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            {
                return Err(error("INVALID_DEPENDENCY_LOCK", "", zero()));
            }
            if result
                .libraries
                .insert(dependency.id.clone(), dependency.clone())
                .is_some()
            {
                return Err(error("DUPLICATE_DEPENDENCY", "", zero()));
            }
            for module in dependency.modules {
                result.add(&dependency.id, module)?;
            }
        }
        Ok(result)
    }
    fn add(&mut self, dependency: &str, module: Module) -> Result<()> {
        let model = &module.model;
        if module.id.is_empty() {
            return Err(error("EMPTY_MODULE_ID", model.uri(), zero()));
        }
        let key = (dependency.to_string(), module.id.clone());
        if self.modules.contains_key(&key) {
            return Err(error("DUPLICATE_MODULE", model.uri(), zero()));
        }
        if self
            .modules
            .iter()
            .any(|((owner, _), m)| owner == dependency && m.model.uri() == model.uri())
        {
            return Err(error("DUPLICATE_DOCUMENT", model.uri(), zero()));
        }
        for id in &module.exports {
            let symbol = model
                .data()
                .symbols
                .iter()
                .find(|s| &s.id == id)
                .ok_or_else(|| error("UNDEFINED_EXPORT", model.uri(), zero()))?;
            if model
                .data()
                .scopes
                .iter()
                .find(|s| s.id == symbol.scope)
                .unwrap()
                .parent
                .is_some()
            {
                return Err(error("NON_ROOT_EXPORT", model.uri(), symbol.declaration));
            }
        }
        for imported in &module.imports {
            if imported.name.is_empty()
                || imported.symbol.is_empty()
                || imported.target.module.is_empty()
            {
                return Err(error("INVALID_IMPORT", model.uri(), imported.span));
            }
            let scope = model.data().scopes.iter().find(|s| s.id == imported.scope);
            if !scope.is_some_and(|s| {
                s.span.start <= imported.span.start
                    && imported.span.start <= imported.span.end
                    && imported.span.end <= s.span.end
                    && s.span.start <= imported.visible_from
                    && imported.visible_from <= s.span.end
            }) {
                return Err(error("IMPORT_OUTSIDE_SCOPE", model.uri(), imported.span));
            }
        }
        for t in &model.data().types {
            if let Some(previous) = self.types.get(&t.id) {
                if previous.kind != t.kind
                    || previous.supertypes != t.supertypes
                    || previous
                        .fields
                        .iter()
                        .map(|f| (&f.name, &f.type_id))
                        .collect::<Vec<_>>()
                        != t.fields
                            .iter()
                            .map(|f| (&f.name, &f.type_id))
                            .collect::<Vec<_>>()
                {
                    return Err(error("CONFLICTING_TYPE", model.uri(), t.span));
                }
            } else {
                self.types.insert(t.id.clone(), t.clone());
            }
        }
        self.modules.insert(key, module);
        Ok(())
    }
    pub fn project(&self) -> &str {
        &self.project
    }
    pub fn version(&self) -> i64 {
        self.version
    }
    pub fn modules(&self) -> &[Module] {
        &self.project_modules
    }
    pub fn dependencies(&self) -> &[Dependency] {
        &self.dependencies
    }
    pub fn with_modules(&self, version: i64, modules: Vec<Module>) -> Result<Self> {
        if version <= self.version {
            return Err(error("NON_INCREASING_VERSION", "", zero()));
        }
        for next in &modules {
            for previous in &self.project_modules {
                if previous.model.uri() != next.model.uri() {
                    continue;
                }
                if next.model.version() < previous.model.version()
                    || next.model.source() != previous.model.source()
                        && next.model.version() == previous.model.version()
                {
                    return Err(error("STALE_DOCUMENT", next.model.uri(), zero()));
                }
            }
        }
        Self::new(
            self.project.clone(),
            version,
            modules,
            self.dependencies.clone(),
        )
    }
    fn query_module(
        &self,
        module: &str,
        project_version: i64,
        document_version: i64,
        cursor: usize,
    ) -> Result<&Module> {
        let m = self
            .modules
            .get(&(String::new(), module.into()))
            .ok_or_else(|| error("UNDEFINED_MODULE", "", zero()))?;
        if project_version != self.version || document_version != m.model.version() {
            return Err(error("STALE_SNAPSHOT", m.model.uri(), zero()));
        }
        if cursor > m.model.source().chars().count() {
            return Err(error(
                "INVALID_CURSOR",
                m.model.uri(),
                Span {
                    start: cursor,
                    end: cursor,
                },
            ));
        }
        Ok(m)
    }
    fn candidate(&self, key: &Key, module: &Module, symbol: &Symbol, name: &str) -> Candidate {
        let dependency_version = self
            .libraries
            .get(&key.0)
            .map(|d| d.version.clone())
            .unwrap_or_default();
        Candidate {
            name: name.into(),
            type_id: symbol.type_id.clone(),
            definition: Definition {
                identity: Identity {
                    project: self.project.clone(),
                    dependency: key.0.clone(),
                    dependency_version,
                    module: module.id.clone(),
                    symbol: symbol.id.clone(),
                },
                uri: module.model.uri().into(),
                version: module.model.version(),
                span: symbol.declaration,
            },
        }
    }
    fn imported(&self, owner: &Key, module: &Module, imported: &Import) -> Binding {
        let dependency = if imported.target.dependency.is_empty() {
            owner.0.clone()
        } else {
            imported.target.dependency.clone()
        };
        let key = (dependency, imported.target.module.clone());
        let mut failure = "UNRESOLVED_IMPORT";
        if let Some(target) = self.modules.get(&key) {
            for symbol in &target.model.data().symbols {
                if symbol.name != imported.symbol
                    || target
                        .model
                        .data()
                        .scopes
                        .iter()
                        .find(|s| s.id == symbol.scope)
                        .unwrap()
                        .parent
                        .is_some()
                {
                    continue;
                }
                if target.exports.contains(&symbol.id) {
                    return Binding {
                        name: imported.name.clone(),
                        candidates: vec![self.candidate(&key, target, symbol, &imported.name)],
                        diagnostics: vec![],
                    };
                }
                failure = "PRIVATE_SYMBOL";
            }
        }
        Binding {
            name: imported.name.clone(),
            candidates: vec![],
            diagnostics: vec![Diagnostic {
                code: failure,
                uri: module.model.uri().into(),
                span: imported.span,
            }],
        }
    }
    pub fn diagnostics(&self) -> Vec<Diagnostic> {
        let mut result = vec![];
        for (key, module) in &self.modules {
            let mut aliases = BTreeSet::new();
            for imported in &module.imports {
                result.extend(self.imported(key, module, imported).diagnostics);
                if !aliases.insert((&imported.scope, &imported.name)) {
                    result.push(Diagnostic {
                        code: "AMBIGUOUS_IMPORT",
                        uri: module.model.uri().into(),
                        span: imported.span,
                    });
                }
            }
        }
        result.sort_by(|a, b| {
            (&a.uri, a.span.start, a.span.end, a.code).cmp(&(
                &b.uri,
                b.span.start,
                b.span.end,
                b.code,
            ))
        });
        result
    }
    pub fn visible(
        &self,
        module_id: &str,
        project_version: i64,
        document_version: i64,
        cursor: usize,
    ) -> Result<Vec<Binding>> {
        let module = self.query_module(module_id, project_version, document_version, cursor)?;
        let model = &module.model;
        let length = model.source().chars().count();
        let mut scope = model
            .data()
            .scopes
            .iter()
            .find(|s| s.parent.is_none())
            .unwrap();
        while let Some(child) = model.data().scopes.iter().find(|s| {
            s.parent.as_deref() == Some(&scope.id)
                && s.span.start <= cursor
                && (cursor < s.span.end || cursor == length && s.span.end == length)
        }) {
            scope = child;
        }
        let mut visible = BTreeMap::new();
        let mut current: Option<&Scope> = Some(scope);
        while let Some(scope) = current {
            let mut local: BTreeMap<String, Binding> = BTreeMap::new();
            for imported in &module.imports {
                if imported.scope != scope.id || imported.visible_from > cursor {
                    continue;
                }
                let mut binding =
                    self.imported(&(String::new(), module_id.into()), module, imported);
                if let Some(mut previous) = local.remove(&imported.name) {
                    previous.candidates.extend(binding.candidates);
                    previous.diagnostics.extend(binding.diagnostics);
                    previous.diagnostics.push(Diagnostic {
                        code: "AMBIGUOUS_IMPORT",
                        uri: model.uri().into(),
                        span: imported.span,
                    });
                    binding = previous;
                }
                local.insert(imported.name.clone(), binding);
            }
            for symbol in &model.data().symbols {
                if symbol.scope == scope.id && symbol.visible_from <= cursor {
                    local.insert(
                        symbol.name.clone(),
                        Binding {
                            name: symbol.name.clone(),
                            candidates: vec![self.candidate(
                                &(String::new(), module_id.into()),
                                module,
                                symbol,
                                &symbol.name,
                            )],
                            diagnostics: vec![],
                        },
                    );
                }
            }
            for (name, binding) in local {
                visible.entry(name).or_insert(binding);
            }
            current = scope
                .parent
                .as_ref()
                .and_then(|id| model.data().scopes.iter().find(|s| &s.id == id));
        }
        Ok(visible.into_values().collect())
    }
    pub fn resolve(
        &self,
        module: &str,
        project_version: i64,
        document_version: i64,
        cursor: usize,
        name: &str,
    ) -> Result<Binding> {
        Ok(self
            .visible(module, project_version, document_version, cursor)?
            .into_iter()
            .find(|b| b.name == name)
            .unwrap_or_else(|| Binding {
                name: name.into(),
                candidates: vec![],
                diagnostics: vec![],
            }))
    }
    pub fn is_assignable(&self, actual: &str, expected: &str) -> Result<Compatibility> {
        if (actual != UNKNOWN && !self.types.contains_key(actual))
            || (expected != UNKNOWN && !self.types.contains_key(expected))
        {
            return Err(error("UNDEFINED_TYPE", "", zero()));
        }
        if actual == UNKNOWN || expected == UNKNOWN {
            return Ok(Compatibility::Unknown);
        }
        let mut seen = BTreeSet::new();
        let mut queue = VecDeque::from([actual]);
        while let Some(id) = queue.pop_front() {
            if id == expected {
                return Ok(Compatibility::Yes);
            }
            if seen.insert(id) {
                queue.extend(self.types[id].supertypes.iter().map(String::as_str));
            }
        }
        Ok(Compatibility::No)
    }
    pub fn complete(
        &self,
        module_id: &str,
        project_version: i64,
        document_version: i64,
        cursor: usize,
        prefix: &str,
        expected: &str,
    ) -> Result<Vec<Completion>> {
        let module = self.query_module(module_id, project_version, document_version, cursor)?;
        self.is_assignable(UNKNOWN, expected)?;
        let count = prefix.chars().count();
        if count > cursor
            || module
                .model
                .source()
                .chars()
                .skip(cursor - count)
                .take(count)
                .collect::<String>()
                != prefix
        {
            return Err(error(
                "PREFIX_MISMATCH",
                module.model.uri(),
                Span {
                    start: cursor,
                    end: cursor,
                },
            ));
        }
        let mut result = vec![];
        for binding in self.visible(module_id, project_version, document_version, cursor)? {
            if binding.status() != "RESOLVED" || !binding.name.starts_with(prefix) {
                continue;
            }
            let candidate = binding.candidates.into_iter().next().unwrap();
            let compatibility = self.is_assignable(&candidate.type_id, expected)?;
            if compatibility == Compatibility::No {
                continue;
            }
            result.push(Completion {
                reason: format!(
                    "{} -> {}: {}",
                    candidate.type_id,
                    expected,
                    compatibility.name()
                ),
                edit: Edit {
                    uri: module.model.uri().into(),
                    version: document_version,
                    span: Span {
                        start: cursor - count,
                        end: cursor,
                    },
                    text: candidate.name.clone(),
                },
                candidate,
                compatibility,
            });
        }
        result.sort_by(|a, b| {
            (a.compatibility, &a.candidate.name).cmp(&(b.compatibility, &b.candidate.name))
        });
        Ok(result)
    }
}
