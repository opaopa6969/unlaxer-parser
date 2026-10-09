//! Explicit same-context prefix calls. Foreign rule IDs never enter the parent's CST arena.
use crate::embedded::CstGrammar;
use crate::source::Language;
use crate::{token, ParseContext, ParseError, ParseMatch, Span, StateMap, Tree};
use std::collections::HashMap;
use std::rc::Rc;

const BUDGET: &str = "unlaxer.shared-call.budget";
const MAX_RETAINED_CP: usize = 4_194_304;
const DEPTH: &str = "unlaxer.shared-call.depth";

#[derive(Debug, Clone)]
pub struct Call {
    pub language: Language,
    pub span: Span,
    pub tree: Box<Tree>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailureKind {
    Unavailable,
    Unsupported,
    Syntax,
    Recovery,
    Limit,
}
#[derive(Debug, Clone)]
pub struct Failure {
    pub kind: FailureKind,
    pub error: ParseError,
}

/// Entries are explicit immutable registrations, not a package resolver or an I/O hook.
pub struct Registry {
    entries: HashMap<Language, CstGrammar>,
}
impl Registry {
    pub fn new(entries: HashMap<Language, CstGrammar>) -> Result<Self, String> {
        if entries.len() > 64 {
            return Err("shared call registry exceeds 64 entries".into());
        }
        for (language, grammar) in &entries {
            for value in [
                &language.id,
                &language.package_id,
                &language.version,
                &language.grammar,
                &language.entry,
            ] {
                if value.is_empty() || value.chars().count() > 256 {
                    return Err("invalid shared call identity".into());
                }
            }
            if language.grammar != grammar.name || !grammar.entries.contains_key(&language.entry) {
                return Err("shared call grammar/entry mismatch".into());
            }
        }
        Ok(Self { entries })
    }
    pub fn call(
        &self,
        context: &mut ParseContext<'_>,
        language: &Language,
    ) -> Result<ParseMatch, Failure> {
        let start = context.position();
        let fail = |kind, offset, hint: &str| Failure {
            kind,
            error: ParseError {
                offset,
                expected: vec![hint.into()],
            },
        };
        let grammar = self.entries.get(language).ok_or_else(|| {
            fail(
                FailureKind::Unavailable,
                start,
                "registered shared language entry",
            )
        })?;
        let root = *grammar.entries.get(&language.entry).ok_or_else(|| {
            fail(
                FailureKind::Unsupported,
                start,
                "public shared grammar entry",
            )
        })?;
        if context.options.diagnostics != crate::Diagnostics::Detailed {
            return Err(fail(
                FailureKind::Unsupported,
                start,
                "detailed diagnostics for shared entry",
            ));
        }
        let retained = context.state::<usize>(BUDGET).copied().unwrap_or(0);
        let source_length = context.byte_offsets.len() - 1;
        let depth = context.state::<usize>(DEPTH).copied().unwrap_or(0);
        if depth >= 32
            || source_length > 1_048_576
            || retained.saturating_add(source_length) > MAX_RETAINED_CP
        {
            return Err(fail(
                FailureKind::Limit,
                start,
                "shared call input/depth limit",
            ));
        }
        let cursor = (context.position, context.matched_position);
        let nodes = std::mem::take(&mut context.nodes);
        let captures = std::mem::take(&mut context.captures);
        let state = std::mem::replace(&mut context.state, Rc::new(StateMap::default()));
        let scopes = std::mem::take(&mut context.scopes);
        let recoveries = std::mem::take(&mut context.recoveries);
        let tokens = std::mem::replace(&mut context.tokens, Rc::new(token::Store::new()));
        let names = std::mem::take(&mut context.names_state);
        context.set_state(DEPTH, depth + 1);
        context.set_state(BUDGET, retained + source_length);
        // Local diagnostics must not report a farther failure from an earlier caller alternative.
        let old_farthest = context.farthest;
        let old_frames = context.diagnostic_frames.clone();
        let old_expected = std::mem::take(&mut context.expected);
        context.farthest = context.position;
        let parsed = context.parse_shared_grammar(&grammar.grammar, root, grammar.whitespace);
        let end = context.position();
        let retained = *context.state::<usize>(BUDGET).unwrap();
        let tree = parsed
            .as_ref()
            .ok()
            .and_then(|value| value.root_node())
            .and_then(|node| context.tree(node));
        let local_error = context.failure();
        let succeeded = parsed.is_ok()
            && end > start
            && tree
                .as_ref()
                .is_some_and(|tree| tree.recoveries().is_empty());
        if succeeded {
            context.farthest = old_farthest;
            context.expected = old_expected;
            context.diagnostic_frames = old_frames;
        } else if old_farthest > context.farthest {
            context.farthest = old_farthest;
            context.expected = old_expected;
        } else if old_farthest == context.farthest {
            for expected in old_expected {
                if !context.expected.contains(&expected) {
                    context.expected.push(expected);
                }
            }
        }
        context.nodes = nodes;
        context.captures = captures;
        context.state = state;
        context.scopes = scopes;
        context.recoveries = recoveries;
        context.tokens = tokens;
        context.names_state = names;
        context.position = cursor.0;
        context.matched_position = cursor.1;
        if parsed.is_err() {
            return Err(Failure {
                kind: FailureKind::Syntax,
                error: local_error,
            });
        }
        let tree =
            tree.ok_or_else(|| fail(FailureKind::Unsupported, start, "one shared entry CST root"))?;
        if !tree.recoveries().is_empty() {
            return Err(fail(
                FailureKind::Recovery,
                tree.recoveries()[0].span.start,
                "strict shared entry without recovery",
            ));
        }
        if end <= start {
            return Err(fail(FailureKind::Limit, start, "nonempty shared entry"));
        }
        let span = Span { start, end };
        let node = context
            .add_token_node(
                crate::VALUE_BOUNDARY_RULE,
                token::TokenKind::Consumed,
                token::TokenSource::Input(span),
            )
            .ok_or_else(|| fail(FailureKind::Limit, start, "shared call token capacity"))?;
        context.set_state(BUDGET, retained);
        Rc::make_mut(&mut context.tokens).calls.insert(
            node.index(),
            Call {
                language: language.clone(),
                span,
                tree: Box::new(tree),
            },
        );
        assert!(context.advance(end - start));
        Ok(ParseMatch {
            span,
            nodes: vec![node.index()],
            captures: vec![],
        })
    }
    /// Existing UBNF ADAPTER functions can directly return this ordinary parser result.
    pub fn parse(&self, context: &mut ParseContext<'_>, language: &Language) -> crate::ParseResult {
        self.call(context, language)
            .map_err(|failure| failure.error)
    }
}
impl Tree {
    /// Metadata is owned by this retained tree and follows the selected opaque CST node.
    pub fn shared_call(&self, node: usize) -> Option<&Call> {
        self.tokens.calls.get(&node)
    }
}
