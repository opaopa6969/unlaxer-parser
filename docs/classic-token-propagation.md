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
needs Classic token collection.

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
by a rewind. `Expr::character(label, fn(char) -> bool)` provides a mode-aware
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

Remaining #545 work is explicit: [capture/replay and name lifetime](https://github.com/opaopa6969/unlaxer-parser/issues/572),
including its remaining generated UBNF integration, and [external Tiny code-fence mode graphs](https://github.com/opaopa6969/unlaxer-parser/issues/573). The earlier PEG diagnostic-frontier gap
also remains tracked in #545; new Classic-entry cases do not prove all legacy
PEG/TokenExpr entry diagnostics equivalent. [General owned capture/replay](owned-capture-replay.md)
now has separate runtime APIs for input, projected and generated values (#590).
The existing Rust AST field capture store retains its original-input boundary:
it rejects a projected or detached field capture instead of reading a different
input slice. Generated UBNF integration remains #591 / #572. Native
code-fence scanners without a mode-aware implementation reject this explicit
mode; normal native parsing remains available. Classic `JavaUntil` and positive /
negative `JavaLookahead` preserve their distinct runtime APIs and have shared
handwritten cases. Full generated capture/replay integration must be verified
separately before claiming that remaining scope.

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
