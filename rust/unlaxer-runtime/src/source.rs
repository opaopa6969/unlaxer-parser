//! Snapshot-bound source maps and embedded-language provider dispatch.
use crate::Span;
use std::collections::{HashMap, HashSet};

pub type Result<T> = std::result::Result<T, &'static str>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub uri: String,
    pub version: u64,
    pub text: String,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Position {
    pub line: usize,
    pub character: usize,
}
impl Snapshot {
    pub fn new(uri: impl Into<String>, version: u64, text: impl Into<String>) -> Result<Self> {
        let result = Self {
            uri: uri.into(),
            version,
            text: text.into(),
        };
        if result.uri.is_empty() {
            return Err("invalid snapshot");
        }
        Ok(result)
    }
    pub fn len(&self) -> usize {
        self.text.chars().count()
    }
    pub fn is_empty(&self) -> bool {
        self.text.is_empty()
    }
    pub fn check(&self, span: Span) -> Result<()> {
        if span.start > span.end || span.end > self.len() {
            return Err("outside snapshot");
        }
        Ok(())
    }
    pub fn utf8(&self, point: usize) -> Result<usize> {
        self.check(Span {
            start: point,
            end: point,
        })?;
        Ok(self.text.chars().take(point).map(char::len_utf8).sum())
    }
    pub fn utf16(&self, point: usize) -> Result<usize> {
        self.check(Span {
            start: point,
            end: point,
        })?;
        Ok(self.text.chars().take(point).map(char::len_utf16).sum())
    }
    pub fn from_utf8(&self, offset: usize) -> Result<usize> {
        (0..=self.len())
            .find(|&point| self.utf8(point) == Ok(offset))
            .ok_or("not a UTF-8 boundary")
    }
    pub fn from_utf16(&self, offset: usize) -> Result<usize> {
        (0..=self.len())
            .find(|&point| self.utf16(point) == Ok(offset))
            .ok_or("not a UTF-16 boundary")
    }
    pub fn slice(&self, span: Span) -> Result<&str> {
        self.check(span)?;
        Ok(&self.text[self.utf8(span.start)?..self.utf8(span.end)?])
    }
    pub fn lsp(&self, point: usize) -> Result<Position> {
        self.check(Span {
            start: point,
            end: point,
        })?;
        let characters: Vec<char> = self.text.chars().collect();
        let (mut line, mut column, mut index) = (0, 0, 0);
        while index < point {
            let character = characters[index];
            if character == '\r' {
                if characters.get(index + 1) == Some(&'\n') {
                    if index + 1 == point {
                        return Err("inside CRLF");
                    }
                    index += 1;
                }
                line += 1;
                column = 0;
            } else if character == '\n' {
                line += 1;
                column = 0;
            } else {
                column += character.len_utf16();
            }
            index += 1;
        }
        Ok(Position {
            line,
            character: column,
        })
    }
    pub fn from_lsp(&self, position: Position) -> Result<usize> {
        (0..=self.len())
            .find(|&point| self.lsp(point) == Ok(position))
            .ok_or("not an LSP boundary")
    }
}
fn contains(outer: Span, inner: Span) -> bool {
    outer.start <= inner.start && inner.start <= inner.end && inner.end <= outer.end
}
fn length(span: Span) -> usize {
    span.end - span.start
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Copy,
    Transformed,
    Generated,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Location {
    pub snapshot: Snapshot,
    pub span: Span,
}
impl Location {
    pub fn new(snapshot: Snapshot, span: Span) -> Result<Self> {
        snapshot.check(span)?;
        Ok(Self { snapshot, span })
    }
}
#[derive(Debug, Clone)]
pub struct Segment {
    pub output: Span,
    pub kind: Kind,
    pub origin: Option<Location>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mapping {
    pub location: Location,
    pub exact: bool,
}
#[derive(Debug, Clone)]
pub struct SourceMap {
    output: Snapshot,
    segments: Vec<Segment>,
    parents: Vec<SourceMap>,
}
impl SourceMap {
    pub fn new(output: Snapshot, segments: Vec<Segment>) -> Result<Self> {
        let mut cursor = 0;
        for segment in &segments {
            output.check(segment.output)?;
            let empty_anchor =
                output.is_empty() && segments.len() == 1 && segment.kind == Kind::Copy;
            if segment.output.start != cursor || (length(segment.output) == 0 && !empty_anchor) {
                return Err("segments must partition output");
            }
            if segment.kind != Kind::Generated && segment.origin.is_none() {
                return Err("missing origin");
            }
            if let Some(origin) = &segment.origin {
                origin.snapshot.check(origin.span)?;
            }
            if segment.kind == Kind::Copy {
                let origin = segment.origin.as_ref().ok_or("missing origin")?;
                if output.slice(segment.output)? != origin.snapshot.slice(origin.span)? {
                    return Err("COPY must preserve text");
                }
            }
            cursor = segment.output.end;
        }
        if cursor != output.len() {
            return Err("incomplete map");
        }
        Ok(Self {
            output,
            segments,
            parents: vec![],
        })
    }
    pub fn output(&self) -> &Snapshot {
        &self.output
    }
    pub fn through(mut self, parent: SourceMap) -> Result<Self> {
        let reachable = self
            .diagnostics(Span {
                start: 0,
                end: self.output.len(),
            })?
            .iter()
            .any(|mapping| mapping.location.snapshot == parent.output);
        if !reachable {
            return Err("unrelated map");
        }
        self.parents.push(parent);
        Ok(self)
    }
    pub fn diagnostics(&self, span: Span) -> Result<Vec<Mapping>> {
        let mut mapped = self.direct(span, false)?;
        for parent in &self.parents {
            let mut next = vec![];
            for mapping in mapped {
                if mapping.location.snapshot == parent.output {
                    for origin in parent.diagnostics(mapping.location.span)? {
                        next.push(Mapping {
                            location: origin.location,
                            exact: mapping.exact && origin.exact,
                        });
                    }
                } else {
                    next.push(mapping);
                }
            }
            mapped = next;
        }
        Ok(mapped)
    }
    pub fn edit(&self, span: Span) -> Result<Location> {
        let mapped = self.direct(span, true)?;
        if mapped.len() != 1 || !mapped[0].exact {
            return Err("edit has no unique inverse");
        }
        let mut location = mapped[0].location.clone();
        for parent in &self.parents {
            if location.snapshot == parent.output {
                location = parent.edit(location.span)?;
            }
        }
        let mut aliases = 0;
        for candidate in self.diagnostics(Span {
            start: 0,
            end: self.output.len(),
        })? {
            if candidate.location.snapshot != location.snapshot {
                continue;
            }
            let candidate_span = candidate.location.span;
            let overlaps = if length(location.span) == 0 {
                contains(candidate_span, location.span)
            } else {
                candidate_span.start < location.span.end && location.span.start < candidate_span.end
            };
            if overlaps {
                aliases += 1;
            }
            if aliases > 1 {
                return Err("duplicated composed origin");
            }
        }
        Ok(location)
    }
    fn exact_links(&self) -> Vec<(Span, Location)> {
        let mut links: Vec<(Span, Location)> = self
            .segments
            .iter()
            .filter(|segment| segment.kind == Kind::Copy)
            .map(|segment| {
                (
                    segment.output,
                    segment.origin.as_ref().expect("validated copy").clone(),
                )
            })
            .collect();
        for parent in &self.parents {
            let mut next = vec![];
            let parent_links = parent.exact_links();
            for (output, origin) in links {
                if origin.snapshot != parent.output {
                    next.push((output, origin));
                    continue;
                }
                for (parent_output, parent_origin) in &parent_links {
                    let start = origin.span.start.max(parent_output.start);
                    let end = origin.span.end.min(parent_output.end);
                    if start > end || (start == end && length(origin.span) != 0) {
                        continue;
                    }
                    let mapped = parent_origin.span.start + start - parent_output.start;
                    next.push((
                        Span {
                            start: output.start + start - origin.span.start,
                            end: output.start + end - origin.span.start,
                        },
                        Location {
                            snapshot: parent_origin.snapshot.clone(),
                            span: Span {
                                start: mapped,
                                end: mapped + end - start,
                            },
                        },
                    ));
                }
            }
            links = next;
        }
        links
    }
    /// Unique original-to-virtual cursor; inexact and ambiguous boundaries return None.
    pub fn cursor(&self, original: &Location) -> Result<Option<usize>> {
        original.snapshot.check(original.span)?;
        if length(original.span) != 0 {
            return Err("cursor must be a point");
        }
        let links = self.exact_links();
        let candidates: HashSet<usize> = links
            .iter()
            .filter(|(_, origin)| {
                origin.snapshot == original.snapshot && contains(origin.span, original.span)
            })
            .map(|(output, origin)| output.start + original.span.start - origin.span.start)
            .collect();
        if candidates.len() != 1 {
            return Ok(None);
        }
        let point = *candidates.iter().next().expect("one candidate");
        for (output, origin) in links {
            if contains(
                output,
                Span {
                    start: point,
                    end: point,
                },
            ) {
                let mapped = origin.span.start + point - output.start;
                if origin.snapshot != original.snapshot || mapped != original.span.start {
                    return Ok(None);
                }
            }
        }
        Ok(Some(point))
    }
    fn direct(&self, span: Span, editing: bool) -> Result<Vec<Mapping>> {
        self.output.check(span)?;
        let mut result = vec![];
        for (index, segment) in self.segments.iter().enumerate() {
            let intersects = if length(span) == 0 {
                contains(segment.output, span)
            } else {
                segment.output.start < span.end && span.start < segment.output.end
            };
            if !intersects {
                continue;
            }
            let Some(origin) = &segment.origin else {
                if editing {
                    return Err("generated source");
                }
                continue;
            };
            let exact = segment.kind == Kind::Copy;
            let mapped = if exact {
                Span {
                    start: span.start.max(segment.output.start) - segment.output.start
                        + origin.span.start,
                    end: span.end.min(segment.output.end) - segment.output.start
                        + origin.span.start,
                }
            } else {
                origin.span
            };
            if editing && exact {
                for (other_index, other) in self.segments.iter().enumerate() {
                    let Some(other_origin) = &other.origin else {
                        continue;
                    };
                    if index == other_index || origin.snapshot != other_origin.snapshot {
                        continue;
                    }
                    let duplicates = if length(mapped) == 0 {
                        contains(other_origin.span, mapped)
                    } else {
                        other_origin.span.start < mapped.end && mapped.start < other_origin.span.end
                    };
                    if duplicates {
                        return Err("duplicated origin");
                    }
                }
            }
            result.push(Mapping {
                location: Location {
                    snapshot: origin.snapshot.clone(),
                    span: mapped,
                },
                exact,
            });
        }
        Ok(result)
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Complete,
    Partial,
    Failed,
    Unavailable,
    Unsupported,
    Timeout,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Operation {
    Parse,
    Validate,
    Completion,
    Hover,
    Definition,
    Rename,
    Format,
    CodeAction,
}
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Language {
    pub id: String,
    pub package_id: String,
    pub version: String,
    pub grammar: String,
    pub entry: String,
}
#[derive(Debug, Clone)]
pub struct Region {
    pub id: String,
    pub parent: Option<String>,
    pub language: Language,
    pub full: Span,
    pub body: Span,
    pub source_map: SourceMap,
    pub parse_state: State,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Edit {
    pub span: Span,
    pub replacement: String,
}
pub struct Response {
    pub snapshot: Snapshot,
    pub state: State,
    pub diagnostics: Vec<Span>,
    pub edits: Vec<Edit>,
}
pub trait Provider {
    fn capabilities(&self) -> HashSet<Operation>;
    fn invoke(&self, region: &Region, operation: Operation) -> Result<Response>;
}
#[derive(Debug)]
pub struct Dispatch {
    pub state: State,
    pub diagnostics: Vec<Mapping>,
    pub edits: Vec<Edit>,
}
pub struct LanguageRegions {
    host: Snapshot,
    regions: HashMap<String, Region>,
    open_ends: HashSet<String>,
}
impl LanguageRegions {
    pub fn regions(&self) -> Vec<&Region> {
        let mut regions: Vec<_> = self.regions.values().collect();
        regions.sort_by(|a, b| a.id.cmp(&b.id));
        regions
    }

    /// Compatibility path for callers without explicit enclosure metadata.
    pub fn new(host: Snapshot, input: Vec<Region>) -> Result<Self> {
        let open_ends = input
            .iter()
            .filter(|r| r.parse_state == State::Partial && r.full.end == r.body.end)
            .map(|r| r.id.clone())
            .collect();
        Self::with_open_ends(host, input, open_ends)
    }
    pub fn with_open_ends(
        host: Snapshot,
        input: Vec<Region>,
        open_ends: HashSet<String>,
    ) -> Result<Self> {
        let mut regions = HashMap::new();
        for region in input {
            host.check(region.full)?;
            if region.id.is_empty() || !contains(region.full, region.body) {
                return Err("invalid region");
            }
            for value in [
                &region.language.id,
                &region.language.package_id,
                &region.language.version,
                &region.language.grammar,
                &region.language.entry,
            ] {
                if value.is_empty() {
                    return Err("empty language identity");
                }
            }
            for mapped in region.source_map.diagnostics(Span {
                start: 0,
                end: region.source_map.output.len(),
            })? {
                if mapped.location.snapshot != host || !contains(region.body, mapped.location.span)
                {
                    return Err("origin outside region body");
                }
            }
            if regions.insert(region.id.clone(), region).is_some() {
                return Err("duplicate region");
            }
        }
        for region in regions.values() {
            let mut ancestors = HashSet::new();
            let mut current = region;
            while let Some(parent_id) = &current.parent {
                if !ancestors.insert(&current.id) {
                    return Err("cyclic region");
                }
                let parent = regions.get(parent_id).ok_or("invalid region parent")?;
                if !contains(parent.body, current.full) {
                    return Err("invalid region parent");
                }
                current = parent;
            }
            for other in regions.values() {
                if region.id == other.id || region.parent != other.parent {
                    continue;
                }
                if region.full.start < other.full.end && other.full.start < region.full.end {
                    return Err("overlapping sibling regions");
                }
            }
        }
        for id in &open_ends {
            let region = regions.get(id).ok_or("invalid open region boundary")?;
            if region.full.end != region.body.end {
                return Err("invalid open region boundary");
            }
        }
        Ok(Self {
            host,
            regions,
            open_ends,
        })
    }
    pub fn host(&self) -> &Snapshot {
        &self.host
    }
    pub fn at(&self, point: usize) -> Result<Option<&Region>> {
        self.host.check(Span {
            start: point,
            end: point,
        })?;
        let mut selected = None;
        let mut selected_depth = 0;
        let mut ambiguous = false;
        for region in self.regions.values() {
            let owns = region.body.start <= point && point < region.body.end
                || self.open_ends.contains(&region.id)
                    && point == self.host.len()
                    && point == region.body.end;
            if !owns {
                continue;
            }
            let depth = self.depth(region);
            if selected.is_none() || depth > selected_depth {
                selected = Some(region);
                selected_depth = depth;
                ambiguous = false;
            } else if depth == selected_depth {
                ambiguous = true;
            }
        }
        if ambiguous {
            return Err("ambiguous cursor ownership");
        }
        Ok(selected)
    }
    fn depth<'a>(&'a self, mut region: &'a Region) -> usize {
        let mut depth = 0;
        while let Some(parent) = &region.parent {
            depth += 1;
            region = &self.regions[parent];
        }
        depth
    }
    pub fn dispatch(
        &self,
        region_id: &str,
        operation: Operation,
        providers: &HashMap<Language, Box<dyn Provider>>,
        current: &Snapshot,
    ) -> Result<Dispatch> {
        if &self.host != current {
            return Err("stale host snapshot");
        }
        let region = self.regions.get(region_id).ok_or("unknown region")?;
        let Some(provider) = providers.get(&region.language) else {
            return Ok(Dispatch {
                state: State::Unavailable,
                diagnostics: vec![],
                edits: vec![],
            });
        };
        if !provider.capabilities().contains(&operation) {
            return Ok(Dispatch {
                state: State::Unsupported,
                diagnostics: vec![],
                edits: vec![],
            });
        }
        let response = provider.invoke(region, operation)?;
        if response.snapshot != region.source_map.output {
            return Err("stale provider snapshot");
        }
        if !matches!(response.state, State::Complete | State::Partial) && !response.edits.is_empty()
        {
            return Err("failed response cannot contain edits");
        }
        let mut diagnostics = vec![];
        for diagnostic in response.diagnostics {
            diagnostics.extend(region.source_map.diagnostics(diagnostic)?);
        }
        let mut edits = vec![];
        for edit in response.edits {
            let origin = region.source_map.edit(edit.span)?;
            if origin.snapshot != self.host || !contains(region.body, origin.span) {
                return Err("edit outside body");
            }
            edits.push(Edit {
                span: origin.span,
                replacement: edit.replacement,
            });
        }
        validate_edits(&edits)?;
        Ok(Dispatch {
            state: response.state,
            diagnostics,
            edits,
        })
    }
    pub fn apply(&self, current: &Snapshot, next_version: u64, edits: &[Edit]) -> Result<Snapshot> {
        if &self.host != current || next_version <= self.host.version {
            return Err("stale or non-increasing version");
        }
        validate_edits(edits)?;
        let mut ordered: Vec<&Edit> = edits.iter().collect();
        ordered.sort_by_key(|edit| std::cmp::Reverse(edit.span.start));
        let mut text = self.host.text.clone();
        for edit in ordered {
            self.host.check(edit.span)?;
            text.replace_range(
                self.host.utf8(edit.span.start)?..self.host.utf8(edit.span.end)?,
                &edit.replacement,
            );
        }
        Snapshot::new(self.host.uri.clone(), next_version, text)
    }
}
pub fn validate_edits(edits: &[Edit]) -> Result<()> {
    let mut ordered: Vec<&Edit> = edits.iter().collect();
    ordered.sort_by_key(|edit| edit.span.start);
    for edit in &ordered {
        if edit.span.start > edit.span.end {
            return Err("invalid span");
        }
    }
    for pair in ordered.windows(2) {
        let (previous, next) = (pair[0].span, pair[1].span);
        if next.start < previous.end
            || next.start == previous.start
            || (next.start == previous.end && (length(next) == 0 || length(previous) == 0))
        {
            return Err("conflicting edits");
        }
    }
    Ok(())
}
