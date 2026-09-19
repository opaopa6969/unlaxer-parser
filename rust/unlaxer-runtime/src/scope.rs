//! Owned lexical symbols and semantic diagnostics. A store belongs to one ParseContext.
//! Unlike speculative syntax failure hints, these values roll back with the parse.

use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SymbolInfo {
    pub name: String,
    /// Caller-provided Unicode scalar offset in the original source.
    pub source_offset: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReferenceInfo {
    pub name: String,
    pub offset: usize,
    pub length: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Severity {
    Error,
    Warning,
    Info,
    Hint,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SymbolDiagnostic {
    pub message: String,
    pub offset: usize,
    pub length: usize,
    pub severity: Severity,
}

/// Lexical scope stack plus a global scope and flat, insertion-ordered event lists.
/// Same-scope redeclaration replaces lookup, but retains both declaration events.
/// Leaving a scope hides its names without discarding its events. Empty names are ignored.
/// Positions/lengths are supplied by the caller, not inferred from UTF-8 string lengths.
/// Cloning creates an independent owned snapshot. Use ParseContext::scopes_mut to
/// participate in automatic parser rollback; a standalone store has no transactions.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ScopeStore {
    stack: Vec<BTreeMap<String, SymbolInfo>>,
    global: BTreeMap<String, SymbolInfo>,
    declarations: Vec<SymbolInfo>,
    references: Vec<ReferenceInfo>,
    diagnostics: Vec<SymbolDiagnostic>,
}

impl ScopeStore {
    pub fn enter(&mut self) {
        self.stack.push(BTreeMap::new());
    }

    /// Leaving at global depth is a no-op, matching Java ScopeStore.
    pub fn leave(&mut self) {
        self.stack.pop();
    }

    pub fn current_scope_depth(&self) -> usize {
        self.stack.len()
    }

    pub fn declare(&mut self, name: &str, source_offset: usize) {
        if name.is_empty() {
            return;
        }
        let info = SymbolInfo {
            name: name.to_owned(),
            source_offset,
        };
        self.stack
            .last_mut()
            .unwrap_or(&mut self.global)
            .insert(name.to_owned(), info.clone());
        self.declarations.push(info);
    }

    pub fn resolve(&self, name: &str) -> Option<&SymbolInfo> {
        if name.is_empty() {
            return None;
        }
        self.stack
            .iter()
            .rev()
            .find_map(|scope| scope.get(name))
            .or_else(|| self.global.get(name))
    }

    pub fn is_declared(&self, name: &str) -> bool {
        self.resolve(name).is_some()
    }

    /// Current-scope lookup entries sorted by name, not the full declaration history.
    /// Java's corresponding list has unspecified order; compare as a set across languages.
    pub fn declared_in_current_scope(&self) -> Vec<SymbolInfo> {
        self.stack
            .last()
            .unwrap_or(&self.global)
            .values()
            .cloned()
            .collect()
    }

    pub fn all_declarations(&self) -> &[SymbolInfo] {
        &self.declarations
    }

    /// Records an occurrence only; checking whether it resolves is a separate operation.
    pub fn add_reference(&mut self, name: &str, offset: usize, length: usize) {
        if !name.is_empty() {
            self.references.push(ReferenceInfo {
                name: name.to_owned(),
                offset,
                length,
            });
        }
    }

    pub fn all_references(&self) -> &[ReferenceInfo] {
        &self.references
    }

    pub fn add_diagnostic(
        &mut self,
        message: &str,
        offset: usize,
        length: usize,
        severity: Severity,
    ) {
        self.diagnostics.push(SymbolDiagnostic {
            message: message.to_owned(),
            offset,
            length,
            severity,
        });
    }

    pub fn diagnostics(&self) -> &[SymbolDiagnostic] {
        &self.diagnostics
    }

    pub fn clear_diagnostics(&mut self) {
        self.diagnostics.clear();
    }
}
