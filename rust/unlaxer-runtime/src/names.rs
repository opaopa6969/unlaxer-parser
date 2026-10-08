//! Explicit read-only name snapshots; runtime has no external provider, registry or I/O.
use crate::{ParseContext, ParseError, Span};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Type,
    Value,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Requirement {
    pub id: String,
    pub version: String,
}
impl Requirement {
    pub fn new(id: impl Into<String>, version: impl Into<String>) -> Result<Self, String> {
        let value = Self {
            id: id.into(),
            version: version.into(),
        };
        validate_id(&value.id)?;
        validate_version(&value.version)?;
        Ok(value)
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    id: String,
    version: String,
    names: BTreeMap<String, Kind>,
}
impl Snapshot {
    pub fn new(
        id: impl Into<String>,
        version: impl Into<String>,
        names: BTreeMap<String, Kind>,
    ) -> Result<Self, String> {
        let requirement = Requirement::new(id, version)?;
        if names.len() > 4096 {
            return Err("name snapshot exceeds 4096 names".into());
        }
        for name in names.keys() {
            validate_name(name)?;
        }
        Ok(Self {
            id: requirement.id,
            version: requirement.version,
            names,
        })
    }
    pub fn id(&self) -> &str {
        &self.id
    }
    pub fn version(&self) -> &str {
        &self.version
    }
    pub fn names(&self) -> &BTreeMap<String, Kind> {
        &self.names
    }
    pub fn lookup(&self, name: &str) -> Option<Kind> {
        self.names.get(name).copied()
    }
}
pub fn validate_id(id: &str) -> Result<(), String> {
    let bytes = id.as_bytes();
    if bytes.is_empty()
        || bytes.len() > 128
        || !bytes[0].is_ascii_alphabetic()
        || bytes
            .iter()
            .any(|c| !(c.is_ascii_alphanumeric() || b"_./-".contains(c)))
    {
        return Err("invalid name snapshot id".into());
    }
    Ok(())
}
pub fn validate_version(version: &str) -> Result<(), String> {
    if version.is_empty()
        || version.len() > 128
        || version.bytes().any(|c| !(33..=126).contains(&c))
    {
        return Err("invalid name snapshot version".into());
    }
    Ok(())
}
pub fn validate_name(name: &str) -> Result<(), String> {
    if name.is_empty()
        || name.chars().count() > 256
        || name
            .chars()
            .any(|c| c <= '\u{20}' || ('\u{7f}'..='\u{9f}').contains(&c))
    {
        return Err("invalid snapshot name".into());
    }
    Ok(())
}
fn prefix(id: &str) -> String {
    format!("ubnf.name.{id}.")
}
pub fn bindings_of(snapshots: &[Snapshot]) -> Result<BTreeMap<String, Vec<String>>, String> {
    if snapshots.len() > 64 {
        return Err("name snapshot count exceeds 64".into());
    }
    let mut bindings = BTreeMap::new();
    let mut ids = BTreeSet::new();
    let mut count = 0;
    for snapshot in snapshots {
        if !ids.insert(snapshot.id()) {
            return Err("duplicate name snapshot id".into());
        }
        count += snapshot.names.len();
        if count > 4096 {
            return Err("name snapshots exceed 4096 names".into());
        }
        let prefix = prefix(snapshot.id());
        bindings.insert(format!("{prefix}version"), vec![snapshot.version.clone()]);
        for (label, kind) in [("type", Kind::Type), ("value", Kind::Value)] {
            bindings.insert(
                format!("{prefix}{label}"),
                snapshot
                    .names
                    .iter()
                    .filter(|(_, current)| **current == kind)
                    .map(|(name, _)| name.clone())
                    .collect(),
            );
        }
    }
    Ok(bindings)
}
pub(crate) fn from_context(
    context: &ParseContext<'_>,
    id: &str,
) -> Result<Option<Snapshot>, String> {
    validate_id(id)?;
    let prefix = prefix(id);
    let version = context.binding_values(&format!("{prefix}version"));
    let types = context.binding_values(&format!("{prefix}type"));
    let values = context.binding_values(&format!("{prefix}value"));
    if version.is_empty() && types.is_empty() && values.is_empty() {
        return Ok(None);
    }
    if version.len() != 1 || types.len() + values.len() > 4096 {
        return Err("invalid name snapshot bindings".into());
    }
    let mut names = BTreeMap::new();
    for (list, kind) in [(types, Kind::Type), (values, Kind::Value)] {
        for name in list {
            if names.insert(name.clone(), kind).is_some() {
                return Err("duplicate snapshot name".into());
            }
        }
    }
    Ok(Some(Snapshot::new(id, &version[0], names)?))
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Failure {
    pub kind: &'static str,
    pub span: Span,
    pub expected: String,
}
impl Failure {
    pub fn error(&self) -> ParseError {
        ParseError {
            offset: self.span.start,
            expected: vec![self.expected.clone()],
        }
    }
}
#[derive(Default)]
pub(crate) struct State {
    pub frames: Vec<Option<Failure>>,
    pub snapshots: BTreeMap<String, Snapshot>,
    pub last: Option<Failure>,
}
impl State {
    pub fn begin(&mut self) {
        if self.frames.is_empty() {
            self.last = None;
            self.snapshots.clear();
        }
        self.frames.push(None);
    }
    pub fn end(&mut self) {
        let failure = self.frames.pop().expect("unbalanced name resolution scope");
        if self.frames.is_empty() {
            self.last = failure;
        }
    }
    pub fn reject(&mut self, kind: &'static str, span: Span, expected: String) {
        let failure = Failure {
            kind,
            span,
            expected,
        };
        for frame in &mut self.frames {
            if frame.is_none() {
                *frame = Some(failure.clone());
            }
        }
        if self.frames.is_empty() {
            self.last = Some(failure);
        }
    }
    pub fn failure(&self) -> Option<&Failure> {
        self.frames
            .last()
            .map(|frame| frame.as_ref())
            .unwrap_or(self.last.as_ref())
    }
}

impl<'a> crate::ParseContext<'a> {
    /// Validates and takes ownership of explicit snapshots before parsing begins.
    pub fn with_name_snapshots(
        input: &'a str,
        snapshots: &[Snapshot],
        options: crate::ParseOptions,
    ) -> Result<Self, String> {
        Ok(Self::with_bindings(input, bindings_of(snapshots)?, options))
    }

    #[inline(never)]
    pub(crate) fn name_scope(
        &mut self,
        child: &crate::Expr,
        requirements: &[Requirement],
        depth: usize,
    ) -> Option<crate::Fragment> {
        self.names_state.begin();
        let at = self.code_point(self.position);
        let span = Span { start: at, end: at };
        if requirements.len() > 64 {
            self.names_state.reject(
                "name_snapshot_limit",
                span,
                "at most 64 name snapshots".into(),
            );
        } else {
            for requirement in requirements {
                if validate_id(&requirement.id).is_err()
                    || validate_version(&requirement.version).is_err()
                {
                    self.names_state.reject(
                        "name_snapshot_invalid",
                        span,
                        "valid immutable name snapshot".into(),
                    );
                    break;
                }
                if !self.names_state.snapshots.contains_key(&requirement.id) {
                    match from_context(self, &requirement.id) {
                        Ok(Some(snapshot)) => {
                            if self.names_state.snapshots.len() >= 64
                                || self
                                    .names_state
                                    .snapshots
                                    .values()
                                    .map(|snapshot| snapshot.names.len())
                                    .sum::<usize>()
                                    + snapshot.names.len()
                                    > 4096
                            {
                                self.names_state.reject(
                                    "name_snapshot_limit",
                                    span,
                                    "at most 64 snapshots and 4096 names".into(),
                                );
                                break;
                            }
                            self.names_state
                                .snapshots
                                .insert(requirement.id.clone(), snapshot);
                        }
                        Ok(None) => self.names_state.reject(
                            "name_snapshot_missing",
                            span,
                            format!("name snapshot {}@{}", requirement.id, requirement.version),
                        ),
                        Err(_) => self.names_state.reject(
                            "name_snapshot_invalid",
                            span,
                            format!("valid immutable name snapshot {}", requirement.id),
                        ),
                    }
                }
                if self
                    .names_state
                    .snapshots
                    .get(&requirement.id)
                    .is_some_and(|snapshot| snapshot.version != requirement.version)
                {
                    self.names_state.reject(
                        "name_snapshot_version",
                        span,
                        format!("name snapshot {}@{}", requirement.id, requirement.version),
                    );
                }
            }
        }
        // FIRST and failure memo must not skip the required predicate observation.
        let previous_first = self.first_sets.take();
        let result = if self.names_state.failure().is_none() {
            self.expression(child, depth)
        } else {
            None
        };
        let result = if self.names_state.failure().is_some() {
            None
        } else {
            result
        };
        self.first_sets = previous_first;
        self.names_state.end();
        result
    }

    #[inline(never)]
    pub(crate) fn name_predicate(
        &mut self,
        child: &crate::Expr,
        id: &str,
        version: &str,
        capture_name: &str,
        kind: &str,
        depth: usize,
    ) -> Option<crate::Fragment> {
        let at = self.code_point(self.position);
        if self.names_state.frames.is_empty() {
            self.names_state.reject(
                "name_scope_missing",
                Span { start: at, end: at },
                "explicit name resolution entry scope".into(),
            );
            return None;
        }
        if !["type", "value", "resolved"].contains(&kind) {
            self.names_state.reject(
                "name_predicate_invalid",
                Span { start: at, end: at },
                "type, value or resolved predicate".into(),
            );
            return None;
        }
        let fragment = self.expression(child, depth)?;
        let sites = fragment
            .captures
            .iter()
            .filter(|capture| capture.name == capture_name)
            .collect::<Vec<_>>();
        if sites.len() != 1 {
            self.names_state.reject(
                "name_capture",
                Span { start: at, end: at },
                "single nonempty name capture".into(),
            );
            return None;
        }
        let capture = sites[0];
        let raw = self.text(capture.span).expect("capture belongs to source");
        let name = raw.trim_matches(|ch| ch <= '\u{20}');
        let start = capture.span.start + raw.chars().take_while(|ch| *ch <= '\u{20}').count();
        let span = Span {
            start,
            end: start + name.chars().count(),
        };
        if name.is_empty() {
            self.names_state
                .reject("name_capture", span, "single nonempty name capture".into());
            return None;
        }
        let snapshot = self.names_state.snapshots.get(id);
        if snapshot.is_none_or(|snapshot| snapshot.version != version) {
            self.names_state.reject(
                "name_snapshot_missing",
                span,
                format!("name snapshot {id}@{version}"),
            );
            return None;
        }
        match snapshot.and_then(|snapshot| snapshot.lookup(name)) {
            None => {
                self.names_state.reject(
                    "unresolved_name",
                    span,
                    format!("resolved name in snapshot {id}@{version}"),
                );
                None
            }
            Some(class)
                if kind == "resolved"
                    || matches!((kind, class), ("type", Kind::Type) | ("value", Kind::Value)) =>
            {
                Some(fragment)
            }
            Some(_) => {
                self.fail(&format!("{kind} name in snapshot {id}@{version}"));
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Rule};
    fn snapshot(name: &str) -> Snapshot {
        Snapshot::new("names", "v1", [(name.to_owned(), Kind::Type)].into()).unwrap()
    }
    fn gate(text: &'static str, kind: &'static str) -> Expr {
        Expr::NamePredicate {
            child: Box::new(Expr::Literal(text).capture("name")),
            snapshot: "names",
            version: "v1",
            capture: "name",
            kind,
        }
    }
    fn scope(child: Expr) -> Expr {
        Expr::NameResolutionScope {
            child: Box::new(child),
            requirements: vec![Requirement::new("names", "v1").unwrap()],
        }
    }
    #[test]
    fn identity_name_and_aggregate_bounds() {
        for id in ["", "1bad", "with space"] {
            assert!(Snapshot::new(id, "v1", BTreeMap::new()).is_err());
        }
        for version in ["", "v 1", "😀"] {
            assert!(Snapshot::new("names", version, BTreeMap::new()).is_err());
        }
        for name in ["", "a b", "a\u{7f}"] {
            assert!(Snapshot::new("names", "v1", [(name.into(), Kind::Type)].into()).is_err());
        }
        assert!(validate_id(&"a".repeat(129)).is_err());
        assert!(validate_version(&"a".repeat(129)).is_err());
        assert!(validate_name(&"a".repeat(257)).is_err());
        let one = snapshot("𠮷");
        assert_eq!(one.lookup("𠮷"), Some(Kind::Type));
        assert!(bindings_of(&[one.clone(), one.clone()]).is_err());
        assert!(bindings_of(&vec![one.clone(); 65]).is_err());
        let mut names = (0..4096)
            .map(|i| (format!("name{i}"), Kind::Type))
            .collect::<BTreeMap<_, _>>();
        let max = Snapshot::new("max", "v1", names.clone()).unwrap();
        assert_eq!(max.names().len(), 4096);
        assert!(bindings_of(&[max, one]).is_err());
        names.insert("excess".into(), Kind::Type);
        assert!(Snapshot::new("max", "v1", names).is_err());
    }
    #[test]
    fn fatal_child_survives_fallback_but_next_entry_starts_clean() {
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let child = scope(gate("U", "type"));
            let parent = scope(Expr::choice([child, Expr::Literal("U")]));
            let mut context = ParseContext::with_bindings(
                "U",
                bindings_of(&[snapshot("T")]).unwrap(),
                ParseOptions::with_memoization(memo),
            );
            assert!(context.parse(&parent).is_err());
            assert_eq!(context.name_failure().unwrap().kind, "unresolved_name");
            assert_eq!((context.position(), context.matched_position()), (0, 0));
            assert!(context.captured("name").is_none());
            assert!(context.nodes.is_empty());
            let fresh = Expr::NameResolutionScope {
                child: Box::new(Expr::Literal("U")),
                requirements: vec![],
            };
            assert!(context.parse(&fresh).is_ok());
            assert!(context.name_failure().is_none());
        }
    }
    #[test]
    fn full_input_typed_snapshot_api_and_diagnostic_modes() {
        let grammar = share_grammar(vec![Rule {
            name: "root",
            expression: scope(Expr::choice([gate("T", "type"), Expr::Literal("T")])),
        }]);
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                let tree = crate::parse_detailed_shared_with_name_snapshots(
                    &grammar,
                    0,
                    false,
                    "T",
                    options,
                    &[snapshot("T")],
                )
                .unwrap();
                assert_eq!(tree.text(tree.nodes[tree.root].span), "T");
                let error = crate::parse_detailed_shared_with_name_snapshots(
                    &grammar,
                    0,
                    false,
                    "T",
                    options,
                    &[snapshot("U")],
                )
                .unwrap_err();
                assert_eq!((error.kind, error.offset), ("unresolved_name", 0));
            }
        }
    }
    #[test]
    fn undeclared_requirement_empty_capture_and_direct_gate_refuse() {
        let mut context = ParseContext::new("T");
        assert!(context.parse(&gate("T", "type")).is_err());
        assert_eq!(context.name_failure().unwrap().kind, "name_scope_missing");
        let mut context = ParseContext::with_bindings(
            "T",
            bindings_of(&[snapshot("T")]).unwrap(),
            ParseOptions::default(),
        );
        assert!(context
            .parse(&Expr::NameResolutionScope {
                child: Box::new(gate("T", "type")),
                requirements: vec![]
            })
            .is_err());
        assert_eq!(
            context.name_failure().unwrap().kind,
            "name_snapshot_missing"
        );
        let mut context = ParseContext::with_bindings(
            " ",
            bindings_of(&[snapshot("T")]).unwrap(),
            ParseOptions::default(),
        );
        assert!(context.parse(&scope(gate(" ", "type"))).is_err());
        assert_eq!(context.name_failure().unwrap().kind, "name_capture");
    }
    #[test]
    fn fatal_names_are_not_syntax_recovery_events() {
        let recovering =
            gate("U", "type").recover(crate::RecoveryMode::Sync, [";"], "syntax error");
        let mut context =
            ParseContext::with_name_snapshots("U;", &[snapshot("T")], ParseOptions::default())
                .unwrap();
        assert!(context.parse(&scope(recovering)).is_err());
        assert_eq!(context.name_failure().unwrap().kind, "unresolved_name");
        assert_eq!(context.position(), 0);
        assert!(context.nodes.is_empty());
        assert!(context.recoveries.is_empty());
    }
}
