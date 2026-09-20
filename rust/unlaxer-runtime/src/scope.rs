//! Owned lexical symbols and semantic diagnostics. A store belongs to one ParseContext.
//! Unlike speculative syntax failure hints, these values roll back with the parse.

use std::collections::BTreeMap;

/// Both modes currently use the same parse-time stack; the distinction is retained metadata.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScopeMode {
    Lexical,
    Dynamic,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Declaration {
    pub symbol_capture: &'static str,
    /// Preserved annotation metadata, not evaluated as a capture or description at runtime.
    pub description: Option<&'static str>,
}

/// Effects on a rule's own named captures; referenced rules' captures are excluded.
/// A scope encloses the body, then closes before declarations and references are
/// recorded. Every matching occurrence is processed in capture order. Symbol text
/// uses Java String.trim (scalars <= U+0020); quotes and escapes remain unchanged.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RuleEffects {
    pub scope_mode: Option<ScopeMode>,
    pub declares: Option<Declaration>,
    /// Record references and warn for unresolved names; does not reject the parse.
    pub backref: Option<&'static str>,
}

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
#[derive(Debug, Clone)]
enum ScopeUndo {
    PopScope,
    PushScope(BTreeMap<String, SymbolInfo>),
    Declare {
        depth: usize,
        name: String,
        previous: Option<SymbolInfo>,
        declarations_len: usize,
    },
    TruncateReferences(usize),
    TruncateDiagnostics(usize),
    RestoreDiagnostics(Vec<SymbolDiagnostic>),
}

#[derive(Debug, Clone, Default)]
pub struct ScopeStore {
    stack: Vec<BTreeMap<String, SymbolInfo>>,
    global: BTreeMap<String, SymbolInfo>,
    declarations: Vec<SymbolInfo>,
    references: Vec<ReferenceInfo>,
    diagnostics: Vec<SymbolDiagnostic>,
    journal: Vec<ScopeUndo>,
    checkpoint_depth: usize,
    journal_entries_created: u64,
}

impl PartialEq for ScopeStore {
    fn eq(&self, other: &Self) -> bool {
        self.stack == other.stack
            && self.global == other.global
            && self.declarations == other.declarations
            && self.references == other.references
            && self.diagnostics == other.diagnostics
    }
}

impl Eq for ScopeStore {}

impl ScopeStore {
    pub(crate) fn is_empty(&self) -> bool {
        self.stack.is_empty()
            && self.global.is_empty()
            && self.declarations.is_empty()
            && self.references.is_empty()
            && self.diagnostics.is_empty()
    }

    pub fn enter(&mut self) {
        self.record(ScopeUndo::PopScope);
        self.stack.push(BTreeMap::new());
    }

    /// Leaving at global depth is a no-op, matching Java ScopeStore.
    pub fn leave(&mut self) {
        if let Some(scope) = self.stack.pop() {
            self.record(ScopeUndo::PushScope(scope));
        }
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
        let depth = self.stack.len();
        let previous = self
            .stack
            .last_mut()
            .unwrap_or(&mut self.global)
            .insert(name.to_owned(), info.clone());
        self.record(ScopeUndo::Declare {
            depth,
            name: name.to_owned(),
            previous,
            declarations_len: self.declarations.len(),
        });
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
            self.record(ScopeUndo::TruncateReferences(self.references.len()));
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
        self.record(ScopeUndo::TruncateDiagnostics(self.diagnostics.len()));
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
        if !self.diagnostics.is_empty() {
            let diagnostics = std::mem::take(&mut self.diagnostics);
            self.record(ScopeUndo::RestoreDiagnostics(diagnostics));
        }
    }

    pub(crate) fn checkpoint(&mut self) -> usize {
        let mark = self.journal.len();
        self.checkpoint_depth += 1;
        mark
    }

    pub(crate) fn commit_checkpoint(&mut self) {
        debug_assert!(self.checkpoint_depth > 0);
        self.checkpoint_depth -= 1;
        if self.checkpoint_depth == 0 {
            self.journal.clear();
        }
    }

    pub(crate) fn rollback_checkpoint(&mut self, mark: usize) {
        debug_assert!(self.checkpoint_depth > 0);
        debug_assert!(mark <= self.journal.len());
        while self.journal.len() > mark {
            let undo = self.journal.pop().expect("journal length checked");
            self.apply_undo(undo);
        }
        self.checkpoint_depth -= 1;
        if self.checkpoint_depth == 0 {
            self.journal.clear();
        }
    }

    pub(crate) fn journal_entries_created(&self) -> u64 {
        self.journal_entries_created
    }

    pub(crate) fn retain_journal_entry_count(&mut self, count: u64) {
        self.journal_entries_created = self.journal_entries_created.max(count);
    }

    fn record(&mut self, undo: ScopeUndo) {
        if self.checkpoint_depth > 0 {
            self.journal.push(undo);
            self.journal_entries_created += 1;
        }
    }

    fn apply_undo(&mut self, undo: ScopeUndo) {
        match undo {
            ScopeUndo::PopScope => {
                self.stack.pop();
            }
            ScopeUndo::PushScope(scope) => self.stack.push(scope),
            ScopeUndo::Declare {
                depth,
                name,
                previous,
                declarations_len,
            } => {
                let scope = if depth == 0 {
                    &mut self.global
                } else {
                    &mut self.stack[depth - 1]
                };
                if let Some(previous) = previous {
                    scope.insert(name, previous);
                } else {
                    scope.remove(&name);
                }
                self.declarations.truncate(declarations_len);
            }
            ScopeUndo::TruncateReferences(len) => self.references.truncate(len),
            ScopeUndo::TruncateDiagnostics(len) => self.diagnostics.truncate(len),
            ScopeUndo::RestoreDiagnostics(diagnostics) => self.diagnostics = diagnostics,
        }
    }
}
