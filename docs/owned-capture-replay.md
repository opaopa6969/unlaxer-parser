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

Java の `ParseContext.parseCaptureBoundary` は子が返した
`result.getRootToken().source` を保存する。結果 token がなければ現在位置の空の source を使う。
`Transaction.commit` の収集では `tokenKind.passFilter` により親と同じ kind の token だけを
原文へ連結する。そのため consumed の Chain 内にある LOOKAHEAD や、EMPTY・UNTIL の
先読み部分は値に入らない。`max(consumed, matched)` は保存範囲の契約ではない。

`CAPTURE(w, AHEAD)`（`token AHEAD = LOOKAHEAD('x')`）では、子の root 自体が
match-only token なので、consumed cursor が動かなくても値は `x` になる。
一方 `CAPTURE(w, 'a' AHEAD)` の値は consumed の `a` だけである。
Choice は選択した子の Parsed を返すため、先読み分岐ならその原文を保持する。
Choice を経由した結果と、親へ commit する consumed CST の原文は区別する。
単一要素の rule や明示 group が Chain を作る場合は、その consumed の原文を使う。
token 別名や call-by-value の脱糖も、最終的に返る子の root token に従う。

Rust の非 Classic 経路は正の LOOKAHEAD の結果 span を Fragment に保持し、
owned capture と選択した Choice を通して伝える（#729）。AST field capture（`@f`）は
Java の consumed collector と同じく結果 span を捨て、外側の owned capture へ伝えない。
Classic 経路でも `@f` は `parsed_source` を破棄し、commit 済み原文だけを返す。
この境界は rule の成功 memo 再生でも変わらない。
消費側の Chain・反復は
この結果 span を集めず、従来の consumed 範囲を使う。rule の結果に必要な span は
CST の span とは別に保持し、成功 memo の再生でも復元する。
Classic 経路でも、owned capture には子が返す Parsed の原文を使い、投影・生成した値も
保持する。Choice と rule 参照では、この原文を commit 済み token の原文と別に渡す。
AST field capture（`@f` / `capture`）と CaptureBoundary は従来の commit 済み原文を使う。
たとえば先読み分岐を選ぶ Choice／その rule 参照／単一 LOOKAHEAD rule を `@f` にすると、
consumed の field span は `[0,0]`、値は空文字列のままである。
`CAPTURE(w, R1 @f) SAME_AS(w)`（`R1 ::= AHEAD | 'q'`）では、入力 `x` の `w` は空文字列、
context の consumed cursor は CP0、全入力解析は `trailing_input` CP0 になる。
Java の `@f` は LazyChain の collector なので、Classic 経路の Rust も `@f` の子を現在の
TokenKind で集め直す（#742）。直接の先読み token を `@f` にした
`CAPTURE(w, AHEAD @f) SAME_AS(w)` も、入力 `x` で `w` は空文字列、context の両 cursor は CP0。
公開 `ParseContext::parse` の `ParseMatch.span` も commit 済み原文を使うため、
`Choice[LOOKAHEAD('x'), 'q'].stop_consume()` を入力 `x` で解析すると `[0,0]` を返す。
同じ Choice の owned capture は値 `x` を保存する。
全入力入口のモード選択、CST node 数、両 cursor は変更しない。

Optional／JavaOptional／Recovery の consumed collector は、直接 LOOKAHEAD を子に取っても
空原文を返す。非 Classic 経路でも子の結果 source を破棄する必要がある。
CaptureBoundary（入力専用境界を含む）も同じで、先読みの Parsed 原文を AST の span にしない。
Java の直接 API と Rust の全入力入口を共通の入力 `x`・空原文・`[0,0]` で検証する。
UBNF の `[AHEAD]` は内部に Chain があるため、その例だけでは Optional 自身の破棄処理を
検証できない。直接 API の回帰テストに加え、Choice を子に持つ `@recovery` を共通 corpus に置く。
通常の `ParseContext::parse` / `parse_grammar` には既存の `requires_classic_mode` による
自動選択があり、CAPTURE / SAME_AS 以外の Classic ラッパーにも適用される。

非 Classic の collector（Sequence／NonOrdered／JavaRepeat／Optional／JavaOptional／Recovery、
`@f`、TextValue／ValueBoundary／CaptureBoundary、本体が Choice 系でない規則）は、Java の
consumed collector（規則は生成規則の LazyChain）と同じ規則で
返却 source を作る（#1023 A/B）。低水準の Classic `MatchOnly` など種別の違う Classic の子は
寄与せず、入力は消費した区間、host の投影（現在の種別の Projected）は出現順に連結する。
consumed 種別の Classic の子が消費区間と違う収集（virtual の子を落とした後の cursor の `""`）を
返したときは、その収集を集める。
`capture_owned(Optional(MatchOnly('c')))` + `Replay` は入力 `pc` で `""` を保存して拒否し、
`Chain[投影 "Z"]` は投影 `"Z"`（論理開始）を保存する。値境界の node はその収集値の span と
consumed 種別になる。Classic の collector も、空の投影だけを集めたときは入力の幅0でなく
投影の `""` を返す（Java の sub-source と同じ）。本体が `MatchOnly('c')` の規則と、それを
参照するだけの規則も `""`（consumed cursor）を保存し、本体が Choice の規則（Java LazyChoice）は
選んだ子の `"c"` を保存する。投影や Classic の子を持たない collector は従来どおり source を
持たず、割当ても増えない。Java 実測の共通表
`conformance/collector-source/full.tsv` で、Java と Rust の 4 入口（full-input、
`.stop_consume()` の root、`ParseContext::parse`、明示 Classic）を照合する。
native の custom が返す入力由来の別種別の token（virtual / match-only）も、その種別の
Classic の子と同じく寄与せず（`virtual-input.tsv`）、任意 terminator 付き反復は body と
commit した terminator を収集する（`terminated.tsv`）。どちらも同じ 4 入口で照合する。
何も消費しなかった子の後で収集が始まるときは、Java と同じく、空でも token を返す子
（collector・規則・値境界・EmptyParser）があればその位置から収集し、PEG assertion だけなら
cursor の `""` になる（`capture_owned(Chain[Optional('z'), vci])` は `p` 位置の `""`）。
native の規則は本体の収集を返し、node を持たない入力も含む（`empty-token.tsv`）。

続く consumed の `SAME_AS(w)` は保存した値を consumed cursor から照合・消費する。
空の束縛も定義済みの値であり、再照合は幅0で成功する。
Classic の match-only の再照合は matched cursor だけを進める。
補助文字を含む値の幅と公開する span/cursor は CP 単位である。
純 PEG assertion は token も cursor の前進も公開しないため、その空の結果とは区別する。
