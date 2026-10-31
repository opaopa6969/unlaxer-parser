# Classic token propagation

The [#571](https://github.com/opaopa6969/unlaxer-parser/issues/571) runtime API slice
of [#545](https://github.com/opaopa6969/unlaxer-parser/issues/545) adds inherited Classic token arguments to Rust `Expr` and handwritten parsers.
It does not replace [pure PEG assertions](peg-assertions.md), add UBNF syntax,
or complete capture/replay integration and every external parser adapter.

```rust
use unlaxer_runtime::{Expr, ParseContext};
use unlaxer_runtime::token::TokenKind;

let parser = Expr::sequence([
    Expr::literal("a"),
    Expr::literal("b").match_only(),
]);
let mut context = ParseContext::new("ab");
let matched = context.parse_with_token_mode(&parser, TokenKind::Consumed, false).unwrap();
assert_eq!(context.position(), 1);
assert_eq!(context.matched_position(), 2);
let token = context.token_node_id(matched.nodes[0]).unwrap();
assert_eq!(context.token_text(token), Some("a"));
```

The arguments correspond to Java `parser.parse(context, tokenKind, invertMatch)`.
The previous mode is restored when the parse returns, including syntax and
resource failures. The explicit Classic entry retains a failed direct consuming
leaf's matched-cursor reset, as Java does; enclosing collecting transactions
still roll it back. Existing `context.parse` keeps its stronger transactional
failure contract. `Parser::parse_classic` has a transactional default for existing
custom parsers; an explicit mode-aware custom implementation can override it. A custom Rust parser can inspect `context.token_mode()` and
call ordinary `context.parse(&child)` to inherit the arguments. An `Expr` graph
containing one of the new wrappers also activates normal Classic arguments at
its entry; earlier ordinary terminals in that same graph remain observable.
Opaque custom callbacks must use the explicit entry when their outer boundary
needs Classic token collection. The full-input entries never switch the root to
Classic (a `.stop_consume()` root does, as Java's `DoConsumePropagationStopper`),
but their native collectors (including a rule whose body is not a Choice, a Java
`LazyChain`) return the same source as Java's consumed collectors
([#1023](https://github.com/opaopa6969/unlaxer-parser/issues/1023)): a Classic
child of another kind contributes nothing and a host projection is collected,
so for the shapes those shared tables check, owned captures, predicates and `@f`
see one value on every entry (`conformance/collector-source/full.tsv`). An input-backed token of another kind
that a native custom publishes (`add_token_node` with a virtual or match-only
kind over `TokenSource::Input`) is collected by that kind as a Classic child of
the kind is: native collectors exclude it and a rule boundary leaves it out of
the CST, as Java's commit filter does; a native Choice that selects such a child
still returns it, while its own collection is "" at the consumed cursor
(`conformance/collector-source/virtual-input.tsv`). A native
`repeat_java_until` is a consumed collector of its bodies and its committed
terminator, as Java's `Occurs` (`conformance/collector-source/terminated.tsv`).
A Java collector returns a token of the consumed kind even when it is empty, so
a collection that a later child starts begins at it; so does a lexical leaf that
matched nothing (`Lexing.LiteralParser("")`, a `LexicalTokenParser`; Rust
`JavaEmptyLiteral`, `Expr::Lexical`). A PEG assertion returns none, and
NamedCapture / CaptureScope / Commit / Label / InvertMatchPropagationStopper /
SemanticPredicateParser / SemanticPredicateScope / NameResolutionScope (none of
them a CollectingParser) return what their child returned. A native Sequence or
NonOrdered tells them apart by the earlier children's expressions, only when the
collection starts after children that consumed nothing. A host returns what it
returned: the consumed tokens over the input among the CST roots the earlier
children returned (an empty Java `Token(consumed, peek(cursor, 0))`, or input
the host did not consume) are collected in order at their own positions, after
an earlier collector's token at the cursor, while a host that returns no token
leaves "" at the consumed cursor. A host whose one root token of the consumed
kind is over other input than it consumed (a Java `Token(consumed, peek(0, 0))`,
or 'c' it peeked without consuming) returns that token's source, as Java's
`Parsed(token)` does: owned capture, a predicate, `@f` and
`InputCaptureBoundary` read it, and a collector collects it as a Classic child's
collection, in order (`Chain[hc, c]` is the projection "cc"). A host that returns
the input it consumed keeps the ordinary path. A
native rule commits what a one-child consumed collector collects from its body,
including plain input without CST nodes
(`conformance/collector-source/empty-token.tsv`, `returned-token.tsv`,
`lexical-token.tsv`, `host-token.tsv`, `host-token-lexical.tsv` and their
`-boundary.tsv` tables).
These tables bound what is verified. Differences that remain open in #1023
include a host token of another kind at a position other than the cursor
(`hvx`, `hmx`, `hvc`), the node span of a value boundary around a projection, the Classic-entry rows whose Java
counterpart is still unconfirmed, and generated anchors/text (D); they are not
treated as language-specific exceptions.

| Kind | Cursor used to recognize a leaf | Cursor movement after nonempty recognition |
|---|---|---|
| `Consumed` | consumed | consumed += source length; matched = consumed |
| `MatchOnly` | matched | matched advances |
| `VirtualConsumed` | matched | consumed += source length; matched = consumed |
| `VirtualMatchOnly` | matched | matched advances |

All positions and source lengths are Unicode code points. Virtual kind is
independent of source provenance. Collection keeps children with the **exact**
owner kind, including the distinction between real and virtual kinds.
`reset_matched_with_consumed` / `set_reset_matched_with_consumed` expose Java's
transaction-start cursor policy. A sequence sets it false for subsequent
siblings; local choice/occurrence/wrapper transactions restore their owner policy.

| Rust wrapper | Java counterpart | Child arguments |
|---|---|---|
| `.match_only()` | `MatchOnly` | real `MatchOnly`, inherited inversion |
| `.invert_match()` | `NotPropagatableSource` | inherited kind, toggled inversion |
| `.stop_invert()` | `InvertMatchPropagationStopper` | inherited kind, inversion false |
| `.stop_consume()` | `DoConsumePropagationStopper` | exact `Consumed`, inherited inversion |

Classic inversion reaches terminals; it is not PEG negative lookahead. A short
or empty word still fails under inversion. Number, ASCII identifier and quoted
parsers propagate through their leaf graph. Quote delimiters explicitly use
`Consumed` and inversion false, as Java's `ParserWrapper` does. Consequently a
match-only quoted parser can consume delimiters; it is not a scanner followed
by a rewind. Built-in Number/Identifier/Quoted leaf graphs isolate outer whitespace and
declarative trivia; see the [atomic lexical boundary contract](classic-atomic-trivia.md).
`Expr::character(label, fn(char) -> bool)` provides a mode-aware
terminal for custom predicates. Its FIRST/memo/deferred-diagnostic analysis is
conservative because the callback can read external state.

`MatchOnly` retains its new CST and cursor result and syntax diagnostics, while
restoring captures, registered user state, scopes, semantic diagnostics,
recovery events, and portable metadata. An inner consuming stopper or an explicit
raw callback can still advance consumed. A portable node handle registered
inside the child becomes stale; a retained CST node can be registered again
with a new generation after the boundary. Structural token kind/source/parser
labels and raw return-token observations are separate from portable extras.
`TokenInfo::original_tokens()` exposes the raw tokens before collection; a
Choice retains the selected child's raw result, as Java `Parsed` does.

`TokenSource::Projected { span, text }` represents concatenated token-source
children whose text is not a contiguous input slice. For example a virtual
consuming chain can publish `bb` at offset 1 although the original input there
is `bc`. Use `token_text(node_id)` for token text; `text(span)` continues to mean
an original-input slice. `Projected` is distinct from detached `Generated`
source, and can use any token kind. Empty real collections retain a child's
empty-source coordinate when present; otherwise they anchor at the current
owner-kind cursor. Empty virtual collections are detached at coordinate zero.

FIRST exclusion and failure memo reuse are disabled while inherited Classic
arguments are active. Existing default parsing keeps its previous memo/FIRST
behavior and creates no terminal CST metadata. Detailed Classic diagnostics
retain failures explored by choices and optional/repeated children. If there
has been no failure, they expose the reached cursor; deferred diagnostics remain
empty until an outer retry. Bounded token trace follows retained terminal
observations without rerunning parsers; speculative/different-kind leaves are
removed at collection. Exceeding a trace limit remains an explicit trace error.
Recovery trace remains explicitly unsupported, as in the existing runtime.

Java now unwinds transaction, parse, memo and deferred frames when a **child
callback before the owner's commit** throws, then propagates the original
exception. The context can be retried after an ordinary child exception.
Resource-limit failures remain fatal for that context, with owned state restored
before close. [Observer failures](parser-observer-failures.md) retract the outermost
publication and invalidate that Java context; retry uses a new context (#564).
Rust `ParseError` and resource failures restore owned state; the existing Rust
contract still requires discarding a context after a panicking callback. Neither
runtime rolls back arbitrary external I/O.

The generated UBNF connection uses [general owned capture/replay syntax](general-owned-capture-syntax.md)
(#591), including [nested fields](typed-nested-captures.md). The
[prior-matched diagnostic contract](prior-matched-diagnostics.md) compares matching
Classic and legacy TokenExpr boundaries and separately preserves native PEG's
source-failure semantics. The legacy TokenExpr entry corresponds to an explicit
outer Java transaction, not a raw leaf call.

[General owned capture/replay](owned-capture-replay.md) retains input, projected
and generated values. AST field captures keep their original-input boundary and
reject projected or detached field values. Tiny code-fence adapters have
[mode-aware implementations](tiny-classic-modes.md); other unsupported native
scanner shapes still reject this explicit mode. Classic `JavaUntil` and positive /
negative `JavaLookahead` retain their distinct APIs and shared handwritten cases.

Verification uses independent hand-written shared fixtures:

- `conformance/token-propagation/cases.tsv`: 166 inputs, four kinds, inversion,
  both cursors, raw versus committed token views, source projection, Unicode,
  CRLF, builtins, empty/EOF, reset policy, retry and failed alternatives.
- `conformance/token-propagation/lexical.tsv`: 12 declarative lexical cases,
  including zero-width inverse assertions in all four kinds.
- `conformance/token-propagation/state.tsv`: 11 state traces, registered state,
  portable references, scopes, recovery, exceptions, retry and resource failure.

Java21 and Rust1.85 run those under two memo policies and all three diagnostic
policies. Java also checks actual success-memo hits/tree copying and exceptional
child frames. Rust additionally checks committed trace and the existing standard
2 MiB recursion/resource tests. Callback exception rows use Java unchecked
exceptions and Rust `ParseError`; Rust panics are not asserted to be recoverable.

Tiny CodeStart/CodeEnd/LongCodeBlock also support explicit Classic modes; see the [pinned real-parser contract and corpus](tiny-classic-modes.md).
