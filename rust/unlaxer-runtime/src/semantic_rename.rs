//! Conservative identity-based rename; requires an explicit complete reference inventory.
use crate::semantic_project::{Definition, Identity, Import, Module, ProjectSymbolIndex};
use crate::source::{Edit, Result, Snapshot};
use crate::source_edits::{Operation, Plan, SourceEdits};
use crate::Span;
use std::collections::{HashMap, HashSet};
pub struct Inventory {
    pub module: String,
    pub source: SourceEdits,
    pub reference_tokens: Vec<String>,
    pub complete: bool,
}
/// The import index addresses an exact immutable Module.imports entry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportSite {
    pub module: String,
    pub import_index: usize,
    pub source_token: String,
    pub alias_token: String,
}
pub fn prepare(
    index: &ProjectSymbolIndex,
    target: &Definition,
    replacement: &str,
    identifier_validator: Option<&dyn Fn(&str) -> bool>,
    inventories: &[Inventory],
) -> Result<Vec<Plan>> {
    prepare_with_imports(
        index,
        target,
        replacement,
        identifier_validator,
        inventories,
        &[],
    )
}
pub fn prepare_with_imports(
    index: &ProjectSymbolIndex,
    target: &Definition,
    replacement: &str,
    identifier_validator: Option<&dyn Fn(&str) -> bool>,
    inventories: &[Inventory],
    import_sites: &[ImportSite],
) -> Result<Vec<Plan>> {
    if !identifier_validator.is_some_and(|validator| validator(replacement)) {
        return Err("unsupported or invalid identifier");
    }
    let identity = &target.identity;
    if identity.project != index.project()
        || !identity.dependency.is_empty()
        || !identity.dependency_version.is_empty()
    {
        return Err("foreign or read-only symbol");
    }
    let selected = index
        .modules()
        .iter()
        .find(|module| module.id == identity.module)
        .ok_or("missing target module")?;
    let symbol = selected
        .model
        .data()
        .symbols
        .iter()
        .find(|symbol| symbol.id == identity.symbol)
        .ok_or("stale target")?;
    if selected.model.uri() != target.uri
        || selected.model.version() != target.version
        || symbol.declaration != target.span
    {
        return Err("stale target");
    }
    let mut by_module = HashMap::new();
    for inventory in inventories {
        if !inventory.complete || by_module.insert(&inventory.module, inventory).is_some() {
            return Err("incomplete or duplicate reference inventory");
        }
    }
    let sites = validate_sites(index, &by_module, import_sites)?;
    for module in index.modules() {
        let inventory = by_module
            .get(&module.id)
            .ok_or("missing or stale source inventory")?;
        let expected = Snapshot::new(
            module.model.uri(),
            module.model.version() as u64,
            module.model.source(),
        )?;
        if &expected != inventory.source.snapshot() {
            return Err("missing or stale source inventory");
        }
        for (i, imported) in module.imports.iter().enumerate() {
            if targets(imported, selected, symbol) {
                let site = sites
                    .get(&(module.id.as_str(), i))
                    .ok_or("missing import source-name metadata")?;
                if site.alias_token.is_empty() && replacement != symbol.name {
                    check_capture(module, inventory, replacement, None)?;
                }
                if module
                    .imports
                    .iter()
                    .enumerate()
                    .any(|(j, other)| j != i && other.name == imported.name)
                {
                    return Err("ambiguous import rename metadata");
                }
            }
        }
    }
    if by_module.len() != index.modules().len() {
        return Err("unknown inventory module");
    }
    if replacement != symbol.name
        && selected
            .model
            .data()
            .symbols
            .iter()
            .any(|other| other.name == replacement)
    {
        return Err("rename may capture a symbol");
    }
    if replacement != symbol.name
        && selected
            .imports
            .iter()
            .any(|imported| imported.name == replacement)
    {
        return Err("rename may capture an import");
    }
    let mut result = vec![];
    for module in index.modules() {
        let inventory = by_module[&module.id];
        let mut edits = vec![];
        let mut changed = HashSet::new();
        if module.id == identity.module {
            let declaration = symbol.declaration;
            if inventory.source.snapshot().slice(declaration)? != symbol.name {
                return Err("declaration is not a source-name token");
            }
            edits.push(Edit {
                span: declaration,
                replacement: replacement.into(),
            });
            changed.insert((declaration.start, declaration.end));
        }
        let mut preserved_aliases = HashSet::new();
        for (i, imported) in module.imports.iter().enumerate() {
            if targets(imported, selected, symbol) {
                let site = sites[&(module.id.as_str(), i)];
                let span = inventory.source.token(&site.source_token)?.span;
                if changed.insert((span.start, span.end)) {
                    edits.push(Edit {
                        span,
                        replacement: replacement.into(),
                    });
                }
                if !site.alias_token.is_empty() {
                    preserved_aliases.insert(i);
                }
            }
        }
        let mut references = HashSet::new();
        for token_id in &inventory.reference_tokens {
            if !references.insert(token_id) {
                return Err("duplicate reference token");
            }
            let span = inventory.source.token(token_id)?.span;
            let name = inventory.source.snapshot().slice(span)?;
            let binding = index
                .resolve(
                    &module.id,
                    index.version(),
                    module.model.version(),
                    span.start,
                    name,
                )
                .map_err(|_| "project resolution failed")?;
            if replacement != symbol.name && module.id == identity.module && name == replacement {
                return Err("rename may capture an existing reference");
            }
            if binding.status() != "RESOLVED" {
                if name == symbol.name {
                    return Err("unresolved or ambiguous target reference");
                }
                continue;
            }
            if &binding.candidates[0].definition.identity != identity {
                continue;
            }
            if import_binding(module, span.start, name)
                .is_some_and(|i| preserved_aliases.contains(&i))
            {
                continue;
            }
            let collision = index
                .resolve(
                    &module.id,
                    index.version(),
                    module.model.version(),
                    span.start,
                    replacement,
                )
                .map_err(|_| "project resolution failed")?;
            if !collision.diagnostics.is_empty()
                || collision
                    .candidates
                    .iter()
                    .any(|candidate| &candidate.definition.identity != identity)
            {
                return Err("rename may capture a reference");
            }
            if changed.insert((span.start, span.end)) {
                edits.push(Edit {
                    span,
                    replacement: replacement.into(),
                });
            }
        }
        if !edits.is_empty() {
            result.push(inventory.source.plan(
                Operation::Rename,
                Span {
                    start: 0,
                    end: inventory.source.snapshot().len(),
                },
                edits,
            )?);
        }
    }
    Ok(result)
}

fn targets(imported: &Import, selected: &Module, symbol: &crate::semantic::Symbol) -> bool {
    imported.target.dependency.is_empty()
        && imported.target.module == selected.id
        && imported.symbol == symbol.name
        && selected.exports.contains(&symbol.id)
}
fn validate_sites<'a>(
    index: &ProjectSymbolIndex,
    inventories: &HashMap<&String, &Inventory>,
    sites: &'a [ImportSite],
) -> Result<HashMap<(&'a str, usize), &'a ImportSite>> {
    let mut result = HashMap::new();
    let mut tokens = HashSet::new();
    for site in sites {
        let module = index
            .modules()
            .iter()
            .find(|m| m.id == site.module)
            .ok_or("unknown import module")?;
        let inventory = inventories
            .get(&site.module)
            .ok_or("invalid import metadata")?;
        let imported = module
            .imports
            .get(site.import_index)
            .ok_or("invalid import metadata")?;
        if result
            .insert((site.module.as_str(), site.import_index), site)
            .is_some()
        {
            return Err("duplicate import metadata");
        }
        let source = inventory.source.token(&site.source_token)?.span;
        let contains = |s: Span| imported.span.start <= s.start && s.end <= imported.span.end;
        if !contains(source)
            || inventory.source.snapshot().slice(source)? != imported.symbol
            || !tokens.insert((&site.module, &site.source_token))
        {
            return Err("invalid import source token");
        }
        if site.alias_token.is_empty() {
            if imported.name != imported.symbol {
                return Err("missing explicit alias token");
            }
        } else {
            let alias = inventory.source.token(&site.alias_token)?.span;
            if !contains(alias)
                || inventory.source.snapshot().slice(alias)? != imported.name
                || !tokens.insert((&site.module, &site.alias_token))
            {
                return Err("invalid import alias token");
            }
        }
        if inventory.reference_tokens.contains(&site.source_token)
            || (!site.alias_token.is_empty()
                && inventory.reference_tokens.contains(&site.alias_token))
        {
            return Err("import token is also a reference");
        }
    }
    Ok(result)
}
fn check_capture(
    module: &Module,
    inventory: &Inventory,
    replacement: &str,
    excluded: Option<usize>,
) -> Result<()> {
    if module
        .model
        .data()
        .symbols
        .iter()
        .any(|s| s.name == replacement)
    {
        return Err("rename may capture a symbol");
    }
    if module
        .imports
        .iter()
        .enumerate()
        .any(|(i, s)| Some(i) != excluded && s.name == replacement)
    {
        return Err("rename may capture an import");
    }
    for token in &inventory.reference_tokens {
        if inventory
            .source
            .snapshot()
            .slice(inventory.source.token(token)?.span)?
            == replacement
        {
            return Err("rename may capture an existing reference");
        }
    }
    Ok(())
}
/// Rename an explicit local alias, preserving its imported name and target declaration.
pub fn prepare_alias(
    index: &ProjectSymbolIndex,
    target: &ImportSite,
    replacement: &str,
    identifier_validator: Option<&dyn Fn(&str) -> bool>,
    inventories: &[Inventory],
    import_sites: &[ImportSite],
) -> Result<Plan> {
    if !identifier_validator.is_some_and(|v| v(replacement)) {
        return Err("unsupported or invalid identifier");
    }
    let mut by_module = HashMap::new();
    for inventory in inventories {
        if !inventory.complete || by_module.insert(&inventory.module, inventory).is_some() {
            return Err("incomplete or duplicate reference inventory");
        }
    }
    if by_module.len() != index.modules().len() {
        return Err("unknown inventory module");
    }
    for module in index.modules() {
        let inventory = by_module
            .get(&module.id)
            .ok_or("missing or stale source inventory")?;
        let expected = Snapshot::new(
            module.model.uri(),
            module.model.version() as u64,
            module.model.source(),
        )?;
        if inventory.source.snapshot() != &expected {
            return Err("missing or stale source inventory");
        }
    }
    let sites = validate_sites(index, &by_module, import_sites)?;
    if sites
        .get(&(target.module.as_str(), target.import_index))
        .copied()
        != Some(target)
        || target.alias_token.is_empty()
    {
        return Err("explicit alias metadata required");
    }
    let module = index
        .modules()
        .iter()
        .find(|m| m.id == target.module)
        .ok_or("unknown import module")?;
    let inventory = by_module[&module.id];
    let imported = &module.imports[target.import_index];
    if module
        .imports
        .iter()
        .enumerate()
        .any(|(i, s)| i != target.import_index && s.name == imported.name)
    {
        return Err("ambiguous alias identity");
    }
    if replacement != imported.name {
        check_capture(module, inventory, replacement, Some(target.import_index))?;
    }
    let (targets, dependency_version) = if imported.target.dependency.is_empty() {
        (index.modules(), "")
    } else {
        let dep = index
            .dependencies()
            .iter()
            .find(|d| d.id == imported.target.dependency)
            .ok_or("unresolved alias target")?;
        (dep.modules.as_slice(), dep.version.as_str())
    };
    let owner = targets
        .iter()
        .find(|m| m.id == imported.target.module)
        .ok_or("unresolved alias target")?;
    let candidates = owner
        .model
        .data()
        .symbols
        .iter()
        .filter(|s| s.name == imported.symbol && owner.exports.contains(&s.id))
        .collect::<Vec<_>>();
    if candidates.len() != 1 {
        return Err("unresolved alias target");
    }
    let identity = Identity {
        project: index.project().into(),
        dependency: imported.target.dependency.clone(),
        dependency_version: dependency_version.into(),
        module: owner.id.clone(),
        symbol: candidates[0].id.clone(),
    };
    let mut edits = vec![Edit {
        span: inventory.source.token(&target.alias_token)?.span,
        replacement: replacement.into(),
    }];
    let mut seen = HashSet::new();
    for token in &inventory.reference_tokens {
        if !seen.insert(token) {
            return Err("duplicate reference token");
        }
        let span = inventory.source.token(token)?.span;
        let name = inventory.source.snapshot().slice(span)?;
        if name != imported.name {
            continue;
        }
        let resolved = index
            .resolve(
                &module.id,
                index.version(),
                module.model.version(),
                span.start,
                name,
            )
            .map_err(|_| "project resolution failed")?;
        if resolved.status() != "RESOLVED" {
            return Err("unresolved or ambiguous alias reference");
        }
        if resolved.candidates[0].definition.identity == identity
            && import_binding(module, span.start, name) == Some(target.import_index)
        {
            edits.push(Edit {
                span,
                replacement: replacement.into(),
            });
        }
    }
    inventory.source.plan(
        Operation::Rename,
        Span {
            start: 0,
            end: inventory.source.snapshot().len(),
        },
        edits,
    )
}

fn import_binding(module: &Module, cursor: usize, name: &str) -> Option<usize> {
    let data = module.model.data();
    let length = module.model.source().chars().count();
    let mut scope = data
        .scopes
        .iter()
        .find(|s| s.parent.is_none())
        .expect("root");
    while let Some(child) = data.scopes.iter().find(|s| {
        s.parent.as_deref() == Some(&scope.id)
            && s.span.start <= cursor
            && (cursor < s.span.end || cursor == length && s.span.end == length)
    }) {
        scope = child;
    }
    loop {
        if data
            .symbols
            .iter()
            .any(|s| s.scope == scope.id && s.name == name && s.visible_from <= cursor)
        {
            return None;
        }
        if let Some(i) = module
            .imports
            .iter()
            .position(|s| s.scope == scope.id && s.name == name && s.visible_from <= cursor)
        {
            return Some(i);
        }
        scope = data
            .scopes
            .iter()
            .find(|s| Some(&s.id) == scope.parent.as_ref())?;
    }
}
