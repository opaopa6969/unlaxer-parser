//! Bounded reuse of complete immutable snapshots. No partial parsing or offset rebasing.
//! Caller revisions cover grammar/options, profile and all externally observed parser state.
//! Values must have independent `Clone` semantics (or share only immutable data).
use crate::source::Snapshot;
use std::collections::VecDeque;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Revisions {
    pub grammar: String,
    pub profile: String,
    pub state: String,
}
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Stats {
    pub entries: usize,
    pub source_utf16_units: usize,
    pub hits: u64,
    pub misses: u64,
}
struct Entry<T> {
    snapshot: Snapshot,
    revisions: Revisions,
    value: T,
    units: usize,
}
pub struct SnapshotParseCache<T> {
    entries: VecDeque<Entry<T>>,
    max_entries: usize,
    max_source_utf16_units: usize,
    stats: Stats,
}
impl<T: Clone> SnapshotParseCache<T> {
    pub fn new(max_entries: usize, max_source_utf16_units: usize) -> Self {
        Self {
            entries: VecDeque::new(),
            max_entries,
            max_source_utf16_units,
            stats: Stats::default(),
        }
    }
    pub fn get(&mut self, snapshot: &Snapshot, revisions: &Revisions) -> Option<T> {
        let Some(index) = self
            .entries
            .iter()
            .position(|e| e.snapshot == *snapshot && e.revisions == *revisions)
        else {
            self.stats.misses = self.stats.misses.saturating_add(1);
            return None;
        };
        let result = self.entries[index].value.clone();
        let entry = self.entries.remove(index).unwrap();
        self.entries.push_back(entry);
        self.stats.hits = self.stats.hits.saturating_add(1);
        Some(result)
    }
    pub fn put(&mut self, snapshot: &Snapshot, revisions: &Revisions, value: &T) {
        let units = snapshot.text.encode_utf16().count();
        if self.max_entries == 0 || units > self.max_source_utf16_units {
            return;
        }
        // Clone all caller data before eviction; a failed computation never reaches this path.
        let entry = Entry {
            snapshot: snapshot.clone(),
            revisions: revisions.clone(),
            value: value.clone(),
            units,
        };
        if let Some(index) = self
            .entries
            .iter()
            .position(|e| e.snapshot == *snapshot && e.revisions == *revisions)
        {
            self.stats.source_utf16_units -= self.entries.remove(index).unwrap().units;
        }
        while !self.entries.is_empty()
            && (self.entries.len() >= self.max_entries
                || self.stats.source_utf16_units > self.max_source_utf16_units - units)
        {
            self.stats.source_utf16_units -= self.entries.pop_front().unwrap().units;
        }
        self.entries.push_back(entry);
        self.stats.source_utf16_units += units;
    }
    pub fn get_or_try_compute<E>(
        &mut self,
        snapshot: &Snapshot,
        revisions: &Revisions,
        compute: impl FnOnce() -> Result<T, E>,
    ) -> Result<T, E> {
        if let Some(value) = self.get(snapshot, revisions) {
            return Ok(value);
        }
        let value = compute()?;
        self.put(snapshot, revisions, &value);
        Ok(value)
    }
    pub fn invalidate(&mut self, uri: &str) {
        self.entries.retain(|entry| {
            if entry.snapshot.uri != uri {
                return true;
            }
            self.stats.source_utf16_units -= entry.units;
            false
        });
    }
    pub fn invalidate_all(&mut self) {
        self.entries.clear();
        self.stats.source_utf16_units = 0;
    }
    pub fn clear(&mut self) {
        self.invalidate_all();
        self.stats = Stats::default();
    }
    pub fn stats(&self) -> Stats {
        Stats {
            entries: self.entries.len(),
            ..self.stats
        }
    }
}

/// Caller-managed chunk memo, matching Java's legacy IncrementalParseCache.
/// Reuse is valid only for independently parsed chunks with unchanged grammar/state and
/// caller-corrected source coordinates. This does not splice/rebase CSTs across edits.
#[derive(Clone)]
pub struct CachedChunk<T> {
    pub text: String,
    pub token: Option<T>,
    pub start_line: usize,
}
pub struct IncrementalParseCache<T> {
    cache: SnapshotParseCache<CachedChunk<T>>,
}
impl<T: Clone> Default for IncrementalParseCache<T> {
    fn default() -> Self {
        Self::new(1024, 1_048_576)
    }
}
impl<T: Clone> IncrementalParseCache<T> {
    pub fn new(max_entries: usize, max_source_utf16_units: usize) -> Self {
        Self {
            cache: SnapshotParseCache::new(max_entries, max_source_utf16_units),
        }
    }
    fn key(text: &str) -> Snapshot {
        Snapshot {
            uri: "chunk".into(),
            version: 0,
            text: text.into(),
        }
    }
    fn revisions() -> Revisions {
        Revisions {
            grammar: String::new(),
            profile: String::new(),
            state: String::new(),
        }
    }
    pub fn get_cached(&mut self, text: &str) -> Option<T> {
        self.cache
            .get(&Self::key(text), &Self::revisions())
            .and_then(|chunk| chunk.token)
    }
    pub fn put(&mut self, text: &str, token: Option<T>, start_line: usize) {
        self.cache.put(
            &Self::key(text),
            &Self::revisions(),
            &CachedChunk {
                text: text.into(),
                token,
                start_line,
            },
        );
    }
    pub fn is_cached(&self, text: &str) -> bool {
        self.cache
            .entries
            .iter()
            .any(|entry| entry.snapshot.text == text)
    }
    pub fn stats(&self) -> Stats {
        self.cache.stats()
    }
    pub fn clear(&mut self) {
        self.cache.clear();
    }
    pub fn size(&self) -> usize {
        self.cache.stats().entries
    }
    pub fn split_into_chunks(source: &str, delimiters: &[&str]) -> Vec<String> {
        if source.is_empty() {
            return vec![];
        }
        let mut chunks = vec![];
        let mut current = String::new();
        let mut quote = None;
        let mut chars = source.chars();
        while let Some(ch) = chars.next() {
            current.push(ch);
            if let Some(end) = quote {
                if ch == '\\' {
                    if let Some(escaped) = chars.next() {
                        current.push(escaped);
                    }
                } else if ch == end {
                    quote = None;
                }
            } else if ch == '\'' || ch == '"' {
                quote = Some(ch);
            } else if ch.len_utf16() == 1
                && delimiters
                    .iter()
                    .any(|d| d.chars().count() == 1 && d.starts_with(ch))
            {
                chunks.push(std::mem::take(&mut current));
            }
        }
        if !current.is_empty() {
            chunks.push(current);
        }
        chunks
    }
}

/// Whole-snapshot memoization is conservative for custom callbacks, even when they
/// permit diagnostic retries. Revision-aware hosts may explicitly opt in separately.
pub fn grammar_is_snapshot_cacheable(rules: &[crate::Rule]) -> bool {
    use crate::Expr;
    let mut pending: Vec<&Expr> = rules.iter().map(|rule| &rule.expression).collect();
    while let Some(expression) = pending.pop() {
        match expression {
            Expr::Custom(_) | Expr::CustomWith { .. } => return false,
            Expr::Sequence(children)
            | Expr::Choice(children)
            | Expr::LongestChoice(children)
            | Expr::UniqueLongestChoice(children) => pending.extend(children),
            Expr::PredictiveChoice { alternatives, .. } => pending.extend(alternatives),
            Expr::Capture(_, child)
            | Expr::TextValue(child)
            | Expr::ValueBoundary(child)
            | Expr::Optional(child)
            | Expr::JavaOptional(child)
            | Expr::Repeat { child, .. }
            | Expr::JavaRepeat { child, .. }
            | Expr::Lookahead { child, .. }
            | Expr::RuleEffects { child, .. }
            | Expr::CaptureEquality { child, .. }
            | Expr::NamePredicate { child, .. }
            | Expr::NameResolutionScope { child, .. }
            | Expr::Recovery { child, .. }
            | Expr::TriviaScope { child, .. }
            | Expr::LexicalTriviaScope { child, .. }
            | Expr::LexicalContextScope { child, .. } => pending.push(child),
            Expr::Lexical(_, _)
            | Expr::Literal(_)
            | Expr::CaseInsensitive(_)
            | Expr::Number
            | Expr::Identifier
            | Expr::Grapheme
            | Expr::CodeStart
            | Expr::CodeEnd
            | Expr::LongCodeBlock
            | Expr::Quoted(_)
            | Expr::Rule(_)
            | Expr::Any
            | Expr::Eof
            | Expr::Empty
            | Expr::CharRange(_, _)
            | Expr::Except(_)
            | Expr::Until(_)
            | Expr::JavaLookahead { .. }
            | Expr::JavaEmpty
            | Expr::JavaUntil(_)
            | Expr::Error(_)
            | Expr::Backreference(_) => {}
        }
    }
    true
}
