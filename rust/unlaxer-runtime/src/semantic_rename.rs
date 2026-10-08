//! Conservative identity-based rename; requires an explicit complete reference inventory.
use crate::semantic_project::{Definition, ProjectSymbolIndex};
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
pub fn prepare(
    index: &ProjectSymbolIndex,
    target: &Definition,
    replacement: &str,
    identifier_validator: Option<&dyn Fn(&str) -> bool>,
    inventories: &[Inventory],
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
        for imported in &module.imports {
            if imported.target.dependency.is_empty()
                && imported.target.module == identity.module
                && imported.symbol == symbol.name
            {
                return Err("import source-name spans are unsupported");
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
