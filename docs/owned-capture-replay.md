# Owned general capture and replay

The general parser APIs share one contract across Java and Rust. Java uses
`NamedCapture(name, child)`, `ReplayParser(name)` and `CaptureScope(child)`;
Rust uses `child.capture_owned(name)`, `Expr::Replay(name)` and
`child.capture_scope()`. The store belongs to one `ParseContext`, never to a
shared parser instance. Names are exact strings (including empty names in the
runtime API). No Unicode normalization or case folding is performed.

A successful capture stores its child's actual result text. It also records
provenance independently of the text: an input slice, an owned projected source,
or generated text with a zero-width input anchor. All coordinates are Unicode
code points relative to the context's input. Projected coordinates describe the
result's logical extent and can exceed the corresponding input slice; their text
must be read from the capture value. Generated text has an anchor, not an input
extent. A Java detached source without an explicit input origin is anchored at
the capture entry cursor. Capturing does not turn input-backed virtual token
kinds into generated provenance.
The capture itself is transparent to the CST and preserves the child's result,
raw tokens, kinds and metadata. It does not recapture an enclosing parser's
unrelated retained siblings.

Bindings last for the context's lifetime and the latest successful definition
wins. A failed enclosing transaction removes its definitions. A capture scope
can read outer bindings; local definitions shadow them, and exiting the scope
restores all previous bindings on success, failure or Java child exception.
This scope affects capture bindings only, not semantic symbol scopes. Nested
scopes can use the same names. `MatchOnly` restores definitions even on success;
pure PEG assertions restore definitions, tokens and both cursors.

Replay compares the stored text with input at the current cursor for its token
kind. These new Rust expressions select Classic mode at ordinary Expr entry
points too, as the existing MatchOnly expressions do; they preserve an explicit
caller-supplied mode. Replay produces input-backed text at this new position,
not a token at the capture's original position. Undefined names fail even under inversion. Empty
captures are defined and replay successfully with zero width; an inverted empty
replay fails. Nonempty replay uses the captured code-point width, including
under inversion, and requires that full width to be available. Classic token
kind propagation, inversion and propagation stoppers otherwise behave as for
word terminals. No automatic trivia is inserted by these runtime APIs.

Java exposes immutable `CapturedValue` values through `ParseContext.captured`.
Rust exposes `captured_source`, `captured_text` and `capture_sources` for
these general bindings. Its existing `Capture` / `capture(name)`,
`Backreference`, `captured(name) -> Option<&'input str>` and `capture_spans`
remain a separate input-backed AST capture API. The general and AST names do not
shadow one another. AST captures continue rejecting projected/generated sources,
since AST comparison and symbol effects use original spans. Use the general
owned API when capturing such sources. Neither getter silently falls back to a
binding from the other store.

Capture bindings are owned transactional state. Failed alternatives, controlled
Rust errors, Java child callback exceptions and resource limits restore them
with the surrounding state and CST. Java observer failures invalidate the
context as described in [parser-observer-failures.md](parser-observer-failures.md).
Rust panic unwinding still requires discarding the context. Arbitrary external
I/O and unregistered host objects are outside rollback guarantees.

This general API is separate from AST field captures and the existing lexical
`CAPTURE`/`SAME_AS` implementation. Lexical bindings remain private to one
lexical match; existing lexical `SCOPE` starts an isolated empty environment.
No general UBNF syntax is added by the runtime unit #590. General rule-expression
syntax and generation from both hosts remain required by #591 / #572.
