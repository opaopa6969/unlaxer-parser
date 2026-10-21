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
The runtime unit #590 is connected to general rule expressions by #591:
`CAPTURE`, `SAME_AS` and `CAPTURE_SCOPE` generate from both hosts. See the
[syntax and generated-consumer contract](general-owned-capture-syntax.md).
Acceptance of the broader #572 / #545 work is recorded in those issues; this document
states the runtime contract only.

## match-only の結果を capture する場合

`token AHEAD = LOOKAHEAD('x')` に対する `CAPTURE(name, AHEAD)` は、Java の
`NamedCapture` と同じく子の結果の原文を保存する。Java の
`ParseContext.parseCaptureBoundary` は `result.getRootToken().source` を値として取り、
結果 token がなければ現在位置の空の source を使う。正の先読みは原文 token を返すので、
consumed cursor の移動幅が 0 でも束縛値は空とは限らない。

Rust の非 Classic 経路では `OwnedCapture` の入力 span を capture 開始位置から
`max(consumed, matched)` までとして、その範囲の原文を保存する（#729）。
Classic 経路では従来どおり子の source を使い、投影・生成した値もそのまま保持する。
全入力入口のモード選択は変更しない。通常の `ParseContext::parse` / `parse_grammar`
には既存の `requires_classic_mode` による自動選択があり、これは CAPTURE / SAME_AS 以外の
Classic ラッパーにも適用される。子の CST・両 cursor・AST field capture は変更しない。

たとえば `LOOKAHEAD('x')` を consumed/matched とも CP1 で照合すると、capture 後は
consumed CP1 / matched CP2、束縛値は `x`（入力 span CP1..2）になる。
続く consumed の `SAME_AS(name)` は Java の `ReplayParser` と同じく CP1 から `x` を
照合・消費し、両 cursor を CP2 にする。空の束縛も定義済みの値であり、再照合は幅0で成功する。
Classic の match-only の再照合は matched cursor から照合し、その cursor だけを進める。
補助文字を含む値の幅と公開する span/cursor は CP 単位である。
純 PEG assertion は token も cursor の前進も公開しないため、その空の結果とは区別する。
