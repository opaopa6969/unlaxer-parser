//! Named pure semantic predicates (#1006): caller-registered postconditions over a rule's
//! original matched text. Grammars name a condition; they never embed or evaluate host code.
//!
//! The registry lives in the parse-local typed user state, so ordinary grammars keep the
//! same [`ParseContext`] layout, checkpoints and hot paths. Its sticky control block is
//! shared by every checkpoint copy: a missing binding or callback error survives the
//! rollback of the alternatives that observed it, while `false` is an ordinary failure.
use crate::{ParseContext, ParseError, Span};
use std::cell::RefCell;
use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::Arc;

/// Maximum number of distinct predicate names a grammar may require or a registry may hold.
pub const MAX_PREDICATES: usize = 64;
/// Maximum ASCII length of one predicate name.
pub const MAX_NAME_LENGTH: usize = 128;

/// Original matched text and its half-open code-point span in the parse input.
/// This is deliberately not a CST object; see #851 for general parsed-tree predicates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Candidate<'a> {
    pub text: &'a str,
    pub span: Span,
}

/// Application failure of a fallible callback. Its details are intentionally not part of
/// the portable diagnostic (`predicate_error`), matching Java's thrown `RuntimeException`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PredicateError;

impl std::fmt::Display for PredicateError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("semantic predicate failed")
    }
}

impl std::error::Error for PredicateError {}

type Callback = Arc<dyn Fn(&Candidate<'_>) -> Result<bool, PredicateError> + Send + Sync>;

/// Immutable registry of pure named conditions. Callbacks must not observe parse order or
/// call counts: diagnostic retries and backtracking may evaluate a candidate again.
/// Captured application values must also stay immutable for the duration of a parse.
#[derive(Clone, Default)]
pub struct Bindings {
    callbacks: BTreeMap<String, Callback>,
}

impl std::fmt::Debug for Bindings {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_set()
            .entries(self.callbacks.keys())
            .finish()
    }
}

impl Bindings {
    pub fn new() -> Self {
        Self::default()
    }

    /// Registers an infallible condition. A panic is reported as `predicate_error` only when
    /// the binary unwinds; with `panic = "abort"` the process aborts, so prefer
    /// [`Self::with_fallible`] for application failures.
    pub fn with(
        self,
        name: &str,
        predicate: impl Fn(&Candidate<'_>) -> bool + Send + Sync + 'static,
    ) -> Result<Self, String> {
        self.with_fallible(name, move |candidate| Ok(predicate(candidate)))
    }

    /// Registers a condition whose `Err` becomes the sticky `predicate_error` diagnostic,
    /// never an ordinary `false` that a later alternative could hide.
    pub fn with_fallible(
        mut self,
        name: &str,
        predicate: impl Fn(&Candidate<'_>) -> Result<bool, PredicateError> + Send + Sync + 'static,
    ) -> Result<Self, String> {
        validate_name(name)?;
        if !self.callbacks.contains_key(name) && self.callbacks.len() >= MAX_PREDICATES {
            return Err(format!("at most {MAX_PREDICATES} semantic predicates"));
        }
        self.callbacks.insert(name.to_owned(), Arc::new(predicate));
        Ok(self)
    }

    pub fn contains(&self, name: &str) -> bool {
        self.callbacks.contains_key(name)
    }

    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.callbacks.keys().map(String::as_str)
    }

    pub fn len(&self) -> usize {
        self.callbacks.len()
    }

    pub fn is_empty(&self) -> bool {
        self.callbacks.is_empty()
    }

    /// Installs this registry before parsing. Refused while a predicate entry scope is active;
    /// installing replaces the previous registry and clears its last failure.
    pub fn install(&self, context: &mut ParseContext<'_>) -> Result<(), String> {
        if context
            .state::<Session>(STATE_KEY)
            .is_some_and(|session| !session.0.control.borrow().frames.is_empty())
        {
            return Err("cannot replace active semantic predicates".into());
        }
        context.set_state(
            STATE_KEY,
            Session(Rc::new(SessionInner {
                bindings: self.clone(),
                control: RefCell::new(Control::default()),
            })),
        );
        Ok(())
    }
}

/// ASCII `[A-Za-z_][A-Za-z0-9_.-]*`, at most 128 bytes.
pub fn validate_name(name: &str) -> Result<(), String> {
    let bytes = name.as_bytes();
    if bytes.is_empty()
        || bytes.len() > MAX_NAME_LENGTH
        || !(bytes[0].is_ascii_alphabetic() || bytes[0] == b'_')
        || bytes[1..]
            .iter()
            .any(|c| !(c.is_ascii_alphanumeric() || b"_.-".contains(c)))
    {
        return Err("invalid semantic predicate name".into());
    }
    Ok(())
}

/// Full-input entry policy, specialized at compile time. The ordinary entry carries
/// only `()`: no registry pointer, setup branch, state lookup or allocation is added
/// to successful parsing without predicates. Diagnostic inspection remains on failure.
pub(crate) trait Entry: Copy {
    fn prepare(self, context: &mut ParseContext<'_>);
    fn failure(self, context: &ParseContext<'_>, root: usize) -> Option<Failure>;
}

impl Entry for () {
    #[inline(always)]
    fn prepare(self, _context: &mut ParseContext<'_>) {}

    fn failure(self, context: &ParseContext<'_>, root: usize) -> Option<Failure> {
        context
            .semantic_predicate_failure()
            .or_else(|| unregistered(&context.rules, root))
    }
}

impl Entry for &Bindings {
    #[inline(always)]
    fn prepare(self, context: &mut ParseContext<'_>) {
        self.install(context)
            .expect("a new context has no active predicate scope");
    }

    fn failure(self, context: &ParseContext<'_>, _root: usize) -> Option<Failure> {
        context.semantic_predicate_failure()
    }
}

/// Sticky entry failure: `predicate_missing`, `predicate_error`, `predicate_source`,
/// `predicate_limit` or `predicate_scope_missing`.
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

const STATE_KEY: &str = "\u{0}unlaxer.semantic_predicates";

#[derive(Default)]
struct Control {
    frames: Vec<Option<Failure>>,
    last: Option<Failure>,
}

struct SessionInner {
    bindings: Bindings,
    control: RefCell<Control>,
}

/// Checkpoints copy this handle, not the control block, so rollback cannot erase it.
#[derive(Clone)]
struct Session(Rc<SessionInner>);

impl Session {
    fn reject(&self, kind: &'static str, span: Span, expected: String) {
        let failure = Failure {
            kind,
            span,
            expected,
        };
        let mut control = self.0.control.borrow_mut();
        if control.frames.is_empty() {
            control.last = Some(failure);
            return;
        }
        for frame in &mut control.frames {
            if frame.is_none() {
                *frame = Some(failure.clone());
            }
        }
    }

    fn failed(&self) -> bool {
        self.0
            .control
            .borrow()
            .frames
            .last()
            .is_some_and(Option::is_some)
    }
}

/// Requirements of a grammar entry that has no registry at all, for plain full-input APIs.
pub(crate) fn unregistered(rules: &[crate::Rule], root: usize) -> Option<Failure> {
    match &rules.get(root)?.expression {
        crate::Expr::SemanticPredicateScope { requirements, .. } => {
            requirements.first().map(|name| Failure {
                kind: "predicate_missing",
                span: Span { start: 0, end: 0 },
                expected: format!("registered predicate {name}"),
            })
        }
        _ => None,
    }
}

impl<'a> ParseContext<'a> {
    /// Creates a low-level context whose predicate entry scopes use `bindings`.
    pub fn with_semantic_predicates(
        input: &'a str,
        bindings: &Bindings,
        options: crate::ParseOptions,
    ) -> Self {
        let mut context = Self::with_options(input, options);
        bindings
            .install(&mut context)
            .expect("a new context has no active predicate scope");
        context
    }

    /// The sticky predicate failure of the active entry, or of the last completed one.
    /// Contexts without an installed registry report none: their entry scope fails instead.
    pub fn semantic_predicate_failure(&self) -> Option<Failure> {
        let control = self.state::<Session>(STATE_KEY)?.0.control.borrow();
        match control.frames.last() {
            Some(frame) => frame.clone(),
            None => control.last.clone(),
        }
    }

    // One arm in the recursive dispatch: destructuring there enlarges every debug frame.
    #[inline(never)]
    pub(crate) fn expression_semantic_predicate(
        &mut self,
        expression: &crate::Expr,
        depth: usize,
    ) -> Option<crate::Fragment> {
        match expression {
            crate::Expr::SemanticPredicateScope {
                child,
                requirements,
            } => self.semantic_predicate_scope(child, requirements, depth),
            crate::Expr::SemanticPredicate { child, name } => {
                self.semantic_predicate(child, name, depth)
            }
            _ => unreachable!("semantic predicate dispatch"),
        }
    }

    #[inline(never)]
    fn semantic_predicate_scope(
        &mut self,
        child: &crate::Expr,
        requirements: &[&'static str],
        depth: usize,
    ) -> Option<crate::Fragment> {
        let Some(session) = self.state::<Session>(STATE_KEY).cloned() else {
            if let Some(name) = requirements.first() {
                if self.options.diagnostics == crate::Diagnostics::Detailed {
                    self.fail(&format!("registered predicate {name}"));
                }
                return None;
            }
            return self.expression(child, depth);
        };
        {
            let mut control = session.0.control.borrow_mut();
            if control.frames.is_empty() {
                control.last = None;
            }
            control.frames.push(None);
        }
        let at = self.code_point(self.position);
        let span = Span { start: at, end: at };
        if requirements.len() > MAX_PREDICATES {
            session.reject(
                "predicate_limit",
                span,
                format!("at most {MAX_PREDICATES} semantic predicates"),
            );
        } else if let Some(name) = requirements
            .iter()
            .find(|name| !session.0.bindings.contains(name))
        {
            session.reject(
                "predicate_missing",
                span,
                format!("registered predicate {name}"),
            );
        }
        // FIRST exclusion must not skip a callback whose error is a sticky entry failure.
        let previous_first = self.first_sets.take();
        let result = if session.failed() {
            None
        } else {
            self.expression(child, depth)
        };
        let result = result.filter(|_| !session.failed());
        self.first_sets = previous_first;
        let mut control = session.0.control.borrow_mut();
        let failure = control
            .frames
            .pop()
            .expect("unbalanced semantic predicate scope");
        if control.frames.is_empty() {
            control.last = failure;
        }
        result
    }

    #[inline(never)]
    fn semantic_predicate(
        &mut self,
        child: &crate::Expr,
        name: &'static str,
        depth: usize,
    ) -> Option<crate::Fragment> {
        let session = match self.state::<Session>(STATE_KEY) {
            Some(session) if !session.0.control.borrow().frames.is_empty() => session.clone(),
            session => {
                let at = self.code_point(self.position);
                let expected = "semantic predicate entry scope";
                match session {
                    Some(session) => session.reject(
                        "predicate_scope_missing",
                        Span { start: at, end: at },
                        expected.into(),
                    ),
                    None => self.fail(expected),
                }
                return None;
            }
        };
        let start = self.mode_cursor();
        // Like capture_owned, read the Parsed root a Choice/rule returned, not its collection.
        // Only this subtree records input-backed returned sources; memo keys keep them apart.
        let tracked = std::mem::replace(&mut self.track_owned_source, true);
        let fragment = self.expression(child, depth);
        self.track_owned_source = tracked;
        let fragment = fragment?;
        // As Java, a success that returned no token (a PEG assertion, or a Choice that selected
        // one) is the cursor span of the input. Otherwise a virtual collector that kept nothing
        // is generated "", as its CST token and capture_owned already are.
        let source = fragment
            .parsed_source()
            .filter(|_| !self.classic_returned_no_token(&fragment.nodes));
        if source.is_some_and(|source| {
            source.generated
                || source.text.is_some()
                || source.kind.is_virtual() && source.span.start == source.span.end
        }) {
            // Projected or generated text has no original input span to report. As Java's
            // emptyAt, generated or empty text anchors at the child's start, never a host anchor.
            let at = source
                .filter(|source| !source.generated && source.span.start != source.span.end)
                .map_or_else(|| self.code_point(start), |source| source.span.start);
            session.reject(
                "predicate_source",
                Span { start: at, end: at },
                format!("input-backed candidate for predicate {name}"),
            );
            return None;
        }
        let span = match source {
            Some(source) => source.span,
            None => Span {
                start: self.code_point(start),
                end: self.code_point(self.mode_cursor()),
            },
        };
        let text = self.text(span).expect("candidate belongs to source");
        let callback = session.0.bindings.callbacks.get(name).cloned();
        let Some(callback) = callback else {
            session.reject(
                "predicate_missing",
                span,
                format!("registered predicate {name}"),
            );
            return None;
        };
        let candidate = Candidate { text, span };
        // Only the callback's own unwind is contained; parser panics keep the existing
        // discard-the-context contract. `panic = "abort"` cannot be caught at all.
        let verdict =
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| callback(&candidate)));
        match verdict {
            Ok(Ok(true)) => Some(fragment),
            Ok(Ok(false)) => {
                if self.options.diagnostics == crate::Diagnostics::Detailed {
                    self.fail(&format!("predicate {name}"));
                }
                None
            }
            Ok(Err(PredicateError)) | Err(_) => {
                session.reject(
                    "predicate_error",
                    span,
                    format!("predicate {name} completed normally"),
                );
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::token::{TokenKind, TokenSource};
    use crate::{share_grammar, Diagnostics, Expr, Memoization, ParseMatch, ParseOptions, Rule};
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn scope(child: Expr) -> Expr {
        Expr::SemanticPredicateScope {
            child: Box::new(child),
            requirements: vec!["valid"],
        }
    }
    fn gate(child: Expr) -> Expr {
        Expr::SemanticPredicate {
            child: Box::new(child),
            name: "valid",
        }
    }
    fn always(result: bool) -> Bindings {
        Bindings::new().with("valid", move |_| result).unwrap()
    }
    fn all_options() -> Vec<ParseOptions> {
        let mut options = vec![];
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                options.push(ParseOptions::with_memoization(memo).with_diagnostics(diagnostics));
            }
        }
        options
    }

    #[test]
    fn false_rolls_back_and_allows_choice_fallback() {
        for options in all_options() {
            let mut context =
                ParseContext::with_semantic_predicates("123", &always(false), options);
            let choice = scope(Expr::choice([
                gate(Expr::Literal("123")),
                Expr::Literal("123"),
            ]));
            assert!(context.parse(&choice).is_ok());
            assert_eq!(context.position(), 3);
            assert!(context.semantic_predicate_failure().is_none());
        }
    }

    #[test]
    fn candidate_uses_original_unicode_text_and_code_point_span() {
        let seen = Arc::new(std::sync::Mutex::new(vec![]));
        let record = Arc::clone(&seen);
        let bindings = Bindings::new()
            .with("valid", move |candidate| {
                record
                    .lock()
                    .unwrap()
                    .push((candidate.text.to_owned(), candidate.span));
                true
            })
            .unwrap();
        let mut context =
            ParseContext::with_semantic_predicates("a😀\r\n", &bindings, ParseOptions::default());
        let parser = Expr::sequence([Expr::Literal("a"), scope(gate(Expr::Literal("😀\r\n")))]);
        assert!(context.parse(&parser).is_ok());
        assert_eq!(context.position(), 4);
        assert_eq!(
            *seen.lock().unwrap(),
            vec![("😀\r\n".to_owned(), Span { start: 1, end: 4 })]
        );
    }

    #[test]
    fn missing_binding_cannot_be_hidden_by_successful_alternative() {
        let choice = scope(Expr::choice([Expr::Literal("x"), gate(Expr::Literal("x"))]));
        let mut context =
            ParseContext::with_semantic_predicates("x", &Bindings::new(), ParseOptions::default());
        assert!(context.parse(&choice).is_err());
        let failure = context.semantic_predicate_failure().unwrap();
        assert_eq!(
            (failure.kind, failure.span, failure.expected.as_str()),
            (
                "predicate_missing",
                Span { start: 0, end: 0 },
                "registered predicate valid"
            )
        );
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        always(true).install(&mut context).unwrap();
        assert!(context.parse(&choice).is_ok());
        assert!(context.semantic_predicate_failure().is_none());
    }

    #[test]
    fn callback_error_and_unwind_are_distinct_and_context_can_be_reused() {
        let failing = [
            Bindings::new()
                .with_fallible("valid", |_| Err(PredicateError))
                .unwrap(),
            Bindings::new()
                .with("valid", |_| panic!("private detail"))
                .unwrap(),
        ];
        for bindings in failing {
            let parser = scope(Expr::choice([gate(Expr::Literal("x")), Expr::Literal("x")]));
            let mut context =
                ParseContext::with_semantic_predicates("x", &bindings, ParseOptions::default());
            assert!(context.parse(&parser).is_err());
            let failure = context.semantic_predicate_failure().unwrap();
            assert_eq!(failure.kind, "predicate_error");
            assert_eq!(failure.span, Span { start: 0, end: 1 });
            assert!(!failure.expected.contains("private detail"));
            assert_eq!((context.position(), context.matched_position()), (0, 0));
            assert!(context.nodes.is_empty());
            always(true).install(&mut context).unwrap();
            // Java install clears the previous failure the same way.
            assert_eq!(context.semantic_predicate_failure(), None);
            assert!(context.parse(&parser).is_ok());
            assert_eq!(context.semantic_predicate_failure(), None);
            assert_eq!(context.position(), 1);
        }
    }

    #[test]
    fn registry_is_immutable_and_names_are_bounded() {
        for invalid in ["", "1bad", "a b", "é", &"x".repeat(129)] {
            assert!(validate_name(invalid).is_err(), "{invalid:?}");
            assert!(Bindings::new().with(invalid, |_| true).is_err());
        }
        for valid in ["_", "a.b-c_9", &"x".repeat(128)] {
            assert!(validate_name(valid).is_ok(), "{valid:?}");
        }
        let mut bindings = Bindings::new();
        for i in 0..MAX_PREDICATES {
            bindings = bindings.with(&format!("p{i}"), |_| true).unwrap();
        }
        assert!(bindings.clone().with("p0", |_| false).is_ok());
        assert!(bindings.clone().with("excess", |_| true).is_err());
        // The context keeps its own registry: later caller changes cannot alter a parse.
        let original = always(true);
        let mut context =
            ParseContext::with_semantic_predicates("x", &original, ParseOptions::default());
        drop(original);
        assert!(context.parse(&scope(gate(Expr::Literal("x")))).is_ok());
    }

    #[test]
    fn match_only_and_virtual_kinds_still_expose_the_matched_text() {
        for kind in [
            TokenKind::MatchOnly,
            TokenKind::Consumed,
            TokenKind::VirtualMatchOnly,
            TokenKind::VirtualConsumed,
        ] {
            for invert in [false, true] {
                let text = if invert { "b" } else { "a" };
                let bindings = Bindings::new()
                    .with("valid", move |candidate| {
                        assert_eq!(candidate.text, text);
                        assert_eq!(candidate.span, Span { start: 0, end: 1 });
                        true
                    })
                    .unwrap();
                let mut context = ParseContext::with_semantic_predicates(
                    text,
                    &bindings,
                    ParseOptions::default(),
                );
                let result =
                    context.parse_with_token_mode(&scope(gate(Expr::Literal("a"))), kind, invert);
                assert!(result.is_ok(), "{kind:?}/{invert}");
                assert!(context.semantic_predicate_failure().is_none());
            }
        }
    }

    fn marked(context: &mut ParseContext<'_>) -> crate::ParseResult {
        let parsed = context.parse(&Expr::Literal("x"))?;
        context.set_state("candidate", 1usize);
        context.scopes_mut().declare("discarded", 0);
        Ok(parsed)
    }

    #[test]
    fn rejection_restores_registered_child_state() {
        for options in all_options() {
            let parser = scope(Expr::choice([
                gate(Expr::Custom(marked).capture("name")),
                Expr::Literal("x"),
            ]));
            let mut context = ParseContext::with_semantic_predicates("x", &always(false), options);
            assert!(context.parse(&parser).is_ok());
            assert!(context.state::<usize>("candidate").is_none());
            assert!(!context.scopes().is_declared("discarded"));
            assert!(context.captured("name").is_none());
            assert_eq!(context.position(), 1);
        }
    }

    #[test]
    fn false_inside_optional_leaves_no_cursor_node_or_state() {
        for options in all_options() {
            // No enclosing choice: only the predicate's own failure restores the candidate.
            let parser = scope(Expr::sequence([
                gate(Expr::Custom(marked).capture("name")).optional(),
                Expr::Literal("x"),
            ]));
            let mut context = ParseContext::with_semantic_predicates("x", &always(false), options);
            let matched = context.parse(&parser).unwrap();
            assert_eq!(context.position(), 1);
            assert!(context.state::<usize>("candidate").is_none());
            assert!(!context.scopes().is_declared("discarded"));
            assert!(matched
                .captures
                .iter()
                .all(|capture| capture.name != "name"));
        }
    }

    fn generated(context: &mut ParseContext<'_>) -> crate::ParseResult {
        let id = context
            .add_token_node(
                0,
                TokenKind::VirtualConsumed,
                TokenSource::Generated {
                    anchor: 0,
                    text: "g".into(),
                },
            )
            .ok_or_else(|| context.error("token"))?;
        Ok(ParseMatch {
            span: Span { start: 0, end: 0 },
            nodes: vec![id.index()],
            captures: vec![],
        })
    }

    #[test]
    fn generated_candidate_text_is_an_explicit_entry_failure() {
        let mut context =
            ParseContext::with_semantic_predicates("", &always(true), ParseOptions::default());
        let parser = scope(Expr::choice([gate(Expr::Custom(generated)), Expr::Empty]));
        assert!(context.parse(&parser).is_err());
        let failure = context.semantic_predicate_failure().unwrap();
        assert_eq!(failure.kind, "predicate_source");
        assert_eq!(failure.span, Span { start: 0, end: 0 });
    }

    #[test]
    fn direct_gate_without_scope_refuses() {
        let mut context =
            ParseContext::with_semantic_predicates("x", &always(true), ParseOptions::default());
        assert!(context.parse(&gate(Expr::Literal("x"))).is_err());
        assert_eq!(
            context.semantic_predicate_failure().unwrap().kind,
            "predicate_scope_missing"
        );
        // Without any registry the scope itself fails before running its body.
        let mut plain = ParseContext::new("x");
        assert!(plain.parse(&scope(gate(Expr::Literal("x")))).is_err());
        assert!(plain.semantic_predicate_failure().is_none());
        assert_eq!(plain.position(), 0);
    }

    fn grammar() -> crate::SharedGrammar {
        share_grammar(vec![
            Rule {
                name: "start",
                expression: scope(Expr::choice([Expr::Rule(1), Expr::Literal("12")])),
            },
            Rule {
                name: "checked",
                expression: gate(Expr::Literal("12")),
            },
        ])
    }

    #[test]
    fn full_input_api_reports_sticky_failures_and_retries_with_same_registry() {
        let grammar = grammar();
        for options in all_options() {
            let calls = Arc::new(AtomicUsize::new(0));
            let counter = Arc::clone(&calls);
            let accepting = Bindings::new()
                .with("valid", move |candidate| {
                    counter.fetch_add(1, Ordering::Relaxed);
                    candidate.text == "12"
                })
                .unwrap();
            let tree = crate::parse_detailed_shared_with_semantic_predicates(
                &grammar, 0, false, "12", options, &accepting,
            )
            .unwrap();
            assert_eq!(tree.text(tree.nodes[tree.root].span), "12");
            assert!(calls.load(Ordering::Relaxed) >= 1);
            // false falls back to the second alternative; trailing input stays syntax.
            let error = crate::parse_detailed_shared_with_semantic_predicates(
                &grammar,
                0,
                false,
                "123",
                options,
                &always(false),
            )
            .unwrap_err();
            assert_eq!((error.kind, error.offset), ("trailing_input", 2));
            for (bindings, kind, offset) in [
                (Bindings::new(), "predicate_missing", 0),
                (
                    Bindings::new()
                        .with_fallible("valid", |_| Err(PredicateError))
                        .unwrap(),
                    "predicate_error",
                    0,
                ),
            ] {
                let error = crate::parse_detailed_shared_with_semantic_predicates(
                    &grammar, 0, false, "12", options, &bindings,
                )
                .unwrap_err();
                assert_eq!((error.kind, error.offset), (kind, offset));
            }
            // Plain entries cannot silently accept: every requirement is missing.
            let error =
                crate::parse_detailed_shared_with_options(&grammar, 0, false, "12", options)
                    .unwrap_err();
            assert_eq!(
                (error.kind, error.offset, error.expected.as_slice()),
                (
                    "predicate_missing",
                    0,
                    &["registered predicate valid".to_owned()][..]
                )
            );
        }
    }

    fn deep(context: &mut ParseContext<'_>) -> crate::ParseResult {
        context.parse(&Expr::Custom(deep))
    }

    #[test]
    fn returned_source_tracking_is_scoped_to_the_predicate_child() {
        let choice = || Expr::choice([Expr::literal("c").match_only(), Expr::Literal("z")]);
        for options in all_options() {
            // Ordinary true/false, a sticky predicate_source and a resource failure all restore it.
            for (child, verdict) in [
                (choice(), true),
                (choice(), false),
                (Expr::Custom(generated), true),
                (Expr::Custom(deep), true),
            ] {
                let mut context =
                    ParseContext::with_semantic_predicates("c", &always(verdict), options);
                let _ = context.parse(&scope(gate(child)));
                assert!(!context.track_owned_source, "{options:?}");
            }
            // A reused context still reads the returned "c" and keeps ordinary parses untracked.
            let seen = Arc::new(std::sync::Mutex::new(vec![]));
            let record = Arc::clone(&seen);
            let bindings = Bindings::new()
                .with("valid", move |candidate| {
                    record.lock().unwrap().push(candidate.text.to_owned());
                    candidate.text == "c"
                })
                .unwrap();
            let mut context = ParseContext::with_semantic_predicates("c", &bindings, options);
            for _ in 0..2 {
                assert!(context.parse(&scope(gate(choice()))).is_ok());
                assert!(!context.track_owned_source);
                assert!(context.parse(&choice()).is_ok());
            }
            assert_eq!(*seen.lock().unwrap(), ["c", "c"]);
        }
    }

    #[test]
    fn rule_memo_keeps_tracked_and_untracked_results_apart() {
        // R is evaluated twice at the same cursor, outside and inside the predicate in both
        // orders and twice inside, for the UBNF LOOKAHEAD and the Classic MatchOnly.
        for ahead in [
            Expr::JavaLookahead {
                pattern: "c",
                positive: true,
            },
            Expr::literal("c").match_only(),
        ] {
            let rule = Rule {
                name: "r",
                expression: Expr::choice([ahead.clone(), Expr::Literal("z")]),
            };
            let pairs = [
                (Expr::Rule(1), gate(Expr::Rule(1))),
                (gate(Expr::Rule(1)), Expr::Rule(1)),
                (gate(Expr::Rule(1)), gate(Expr::Rule(1))),
            ];
            for (pair, (first, second)) in pairs.into_iter().enumerate() {
                let start = Rule {
                    name: "start",
                    expression: scope(Expr::choice([
                        Expr::sequence([first, Expr::Literal("z")]),
                        Expr::sequence([second, Expr::Literal("c")]),
                    ])),
                };
                for wants_c in [true, false] {
                    let mut outcomes = vec![];
                    for options in all_options() {
                        let seen = Arc::new(std::sync::Mutex::new(vec![]));
                        let record = Arc::clone(&seen);
                        let bindings = Bindings::new()
                            .with("valid", move |candidate| {
                                record.lock().unwrap().push(candidate.text.to_owned());
                                (candidate.text == "c") == wants_c
                            })
                            .unwrap();
                        let mut context =
                            ParseContext::with_semantic_predicates("c", &bindings, options);
                        let result =
                            context.parse_grammar(vec![start.clone(), rule.clone()], 0, false);
                        let seen = seen.lock().unwrap().clone();
                        assert!(!seen.is_empty() && seen.iter().all(|text| text == "c"));
                        assert!(!context.track_owned_source);
                        // Twice inside, the second evaluation replays the tracked entry.
                        if pair == 2 && options.memoization == Memoization::SafeFailures {
                            assert!(context.memoized_success_hits() > 0 || !wants_c);
                        }
                        outcomes.push(format!("{result:?} {}", context.position()));
                    }
                    // A memo hit must not change the result or the cursor of a diagnostic mode.
                    let (off, safe) = outcomes.split_at(outcomes.len() / 2);
                    assert_eq!(off, safe);
                }
            }
        }
    }

    #[test]
    fn retry_reuses_the_original_registry_snapshot() {
        // A rejecting first pass under DetailedOnFailure retries with the same callbacks.
        let calls = Arc::new(AtomicUsize::new(0));
        let counter = Arc::clone(&calls);
        let bindings = Bindings::new()
            .with("valid", move |_| {
                counter.fetch_add(1, Ordering::Relaxed);
                false
            })
            .unwrap();
        let grammar = share_grammar(vec![Rule {
            name: "start",
            expression: scope(gate(Expr::Literal("12"))),
        }]);
        let error = crate::parse_detailed_shared_with_semantic_predicates(
            &grammar,
            0,
            false,
            "12",
            ParseOptions::default().with_diagnostics(Diagnostics::DetailedOnFailure),
            &bindings,
        )
        .unwrap_err();
        assert_eq!(error.kind, "syntax");
        assert!(error.expected.iter().any(|hint| hint == "predicate valid"));
        assert_eq!(calls.load(Ordering::Relaxed), 2);
    }
}
