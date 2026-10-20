# 事前 matched cursor と診断 frontier

[#655](https://github.com/opaopa6969/unlaxer-parser/issues/655) は親 #545 の診断境界を検証する。
Java21 / Rust1.85 を基準とし、受理だけでなく、呼び出す入口と transaction 境界を揃える。

| 意図 | Java | Rust | 失敗位置と cursor |
|---|---|---|---|
| Classic の既存 parser を直接呼ぶ | `parser.parse(context, kind, invert)` | `context.parse_with_token_mode(&expr, kind, invert)` | Classic の matched 到達を含む failure frame。失敗した consuming leaf は matched を consumed へ戻す |
| 旧 explicit token subset を外側 transaction で呼ぶ | `beginPreservingMatched` → parse → commit/rollback | `context.parse_token(&TokenExpr, kind, invert)` | 失敗時は外側 transaction が両 cursor を戻す。raw leaf と直接比較しない |
| Java の PEG assertion を Classic 診断で使う | `new PegLookahead(child[, positive])` | `child.ahead()` / `not_ahead()` を明示 Classic 入口で呼ぶ | assertion の結果・両 cursor 復元と Classic failure frontier を揃えられる。外側 kind/invert は assertion polarity を変更しない |
| native PEG の source failure を使う | 上の Classic 診断と同一とは扱わない | `context.parse(&child.ahead())` / `not_ahead()` | 既存 native PEG の実 source failure 位置。Classic の matched 到達位置で置き換えない |

例えば `ab` に先行 `MatchOnly('a')` を適用すると consumed=CP0、matched=CP1 になる。
続く assertion 内の `Z` は入力 CP0 で失敗する。Java Classic 診断と Rust 明示 Classic
診断は CP1、Rust native PEG 診断は CP0 を返す。`ab Z` を検査すれば、本当の failure
は CP2 であり、両入口とも CP2 を保持する。どちらの assertion も両 cursor を CP0/CP1
へ復元し、child の token を公開しない。

Rust は Classic と native を同じ context で組み合わせられる。成功時の Classic 到達
watermark は、構文失敗がまだ無いときだけ `failure().offset` の fallback になる。
native の構文失敗もこの fallback を解除する。従来は Classic 成功の CP1 が後続
native PEG の CP0 / CP2 をともに CP1 と誤報した。失敗を記録した後に Classic 成功を
重ねても、その成功の到達位置で失敗位置を書き換えない。

成功する positive / negative assertion は内部の speculative failure とその有無を
ともに元へ戻す。例えば `ab Optional(Z)` の成功 assertion では CP2 の内部失敗を
外側へ漏らさない。以前に確定した構文診断は保持する。Deferred の低水準 context は
引き続き offset=0・expected 空であり、getter 自体は再試行しない。到達情報のみで
expected が空の値を IDE の構文エラーとして公開しない。

`parse_token` は独立した旧 subset API である。明示 outer transaction での失敗後に
matched が CP1 のままでも、raw consuming leaf の matched=CP0 への reset と矛盾しない。
前者は rollback 後、後者は外側 rollback 前の観測である。reset policy を true/false
にしても同じ比較境界を使用する。

## 検証

- `conformance/prior-matched-diagnostics/cases.tsv`: 92 個の手書き期待値。
  `p😀\r\nab!`、先行 consumed=CP1 / matched=CP4、4 TokenKind、invert、
  raw Classic / legacy transactional subset、positive/negative assertion、
  source の遠方 failure=CP6、successful hint discard と Classic parent を含む。
- `reverse.tsv`: native/PEG → Classic の4 trace。先行の実 failure と成功 assertion の
  speculative hint を区別し、Classic の後続失敗と混同しない。
- `PriorMatchedDiagnosticsTest` と Rust `prior_matched_diagnostics` は memo2種 ×
  diagnostics3種 × reset2種で実行する。raw/transactional の失敗を同じ context で2回
  繰り返し、manual rollback、両 cursor、CST、登録 state、owned capture、portable
  metadata と retained snapshot、最後の consuming retry を検証する。
- Rust は同じ入力で legacy TokenExpr / 一般 Classic の両方による事前準備を使い、
  native PEG の独立 source 期待値も確認する。追加の CP0/CP2 probe は古い watermark
  に隠れた実 failure を直接固定する。memo 方針の検証を全行の実 memo hit とは数えない。
- 追加の36設定では、同じ rule を反復して実 memo hit を検証する。失敗再利用は
  OFF=0 / ON=1 hit、成功再利用は OFF=0 / ON=2 hit。診断3種と reset2種で、
  native の CP0/CP2、続く Classic の CP1/CP2、成功先読みの hint 破棄と
  失敗する否定先読みの hint 保持を確認する。

旧 `peg-assertion/cases.tsv` の Java/Rust 別診断期待値は変更しない。その native PEG
との比較を、Java互換 Classic 入口の不足と読み替えない。認識や既存 API の既定値は
変更せず、同じ Classic 挙動を明示して選ぶ実用経路を検証した。

構文診断以外の failure 契約は維持する。Java の通常 child exception は元例外を再送して
登録状態を復元し、observer failure は公開境界を復元後に context を無効化する。
両版の resource failure は sticky、Rust の panic 後は context を破棄する。
本件だけで親 #545 全体を close しない。

## grammar 呼び出しの frontier（#706）

[#706](https://github.com/opaopa6969/unlaxer-parser/issues/706) では、UBNF の正の
`LOOKAHEAD('x')`（Java `MatchOnly(WordParser)`、Rust `Expr::JavaLookahead`）が matched
cursor を進めた後に続く要素が失敗すると、失敗位置と rule stack が両版で食い違っていた。

| 文法と入力 | Java | 修正前の Rust | 修正後の Rust |
|---|---|---|---|
| `R0 ::= 'a' AHEAD 'é'` に `ax` / `axé`（`diagnose`） | syntax CP2 | syntax CP1 | syntax CP2 |
| `R0 ::= 'y' (AHEAD R1) ; R1 ::= 'x' 'a'` に `yx`（rule stack） | `R0@0>R1@2` | `R0@0>R1@1` | `R0@0>R1@2` |
| `R0 ::= AHEAD (R1 \| 'x' 'a') ; R1 ::= 'a'` に `x`（rule stack） | `R0@0>R1@1` | `R0@0` | `R0@0>R1@1` |

Java は失敗の到達（`maxReachedOffset`）、parse frame の開始（`ParseFrame.startOffset`）、
frame の到達を、どれも max(consumed, matched) で測る。Rust は consumed だけで測っていた。

**揃え方**: Rust を Java に合わせた。Rust では、grammar を呼び出している間
（全入力の `parse_detailed*` 系と `ParseContext::parse_grammar` / `parse_shared_grammar`。
token stream の lexing session の中を除く）、
失敗の記録位置と rule frame の開始位置を max(consumed, matched) にする。
Classic token mode（`CAPTURE` などを含む grammar）の中では、rule frame の開始だけを補正し、
失敗の記録位置は補正しない。token mode の各失敗箇所は、すでに Java Classic の位置
（owner の開始や max(consumed, matched)）を明示して記録しているからである。両方を補正すると
`R0 ::= CAPTURE(w0, EOF)` の `a` で context の失敗位置（と rule stack の offset）が Rust だけ CP1 になり、逆に frame の開始を補正しないと
`R0 ::= AHEAD CAPTURE(w0, R1) ; R1 ::= 'a'` の `x` で Rust だけ `R0@0>R1@0` になる（どちらも
PBT で確かめた）。

**token stream の中は対象外**: `@tokenStream: enabled` の lexing session（Rust の
`parse_with_lexing`）の中では、失敗も rule frame も従来の consumed の位置で測る。そこでは Java の
match-only terminal が lexeme を見る。たとえば `Root ::= EMPTY END`（`@whitespace: javaStyle`）に
`a` を与えると、Java の `EMPTY`（`MatchOnly(任意の 1 文字)`）は error lexeme の `a` に一致せず、
matched は CP0 のまま `EOF` が CP0 で失敗する（`spec-corpus/token-stream/corpus.json` の
`empty-assertions` / `not-eof`）。Rust の `JavaEmpty` は lexing session の中でも matched を 1 文字
進めるので、補正すると Rust だけ CP1 になる。session の中から `parse_grammar` で入れ子に呼んだ
grammar も、呼び出しが session を外しても同じく補正しない（Rust `prior_matched_diagnostics` の
`a_grammar_nested_in_a_token_stream_session_keeps_the_consumed_frontier`）。UBNF は token stream の
文法で `LOOKAHEAD` / `NEGATIVE_LOOKAHEAD` / `UNTIL` などの非 declarative token を
`E-TOKEN-STREAM-TOKEN` で拒否する（`TokenStreamGrammar`）。token stream の中で matched を進めうる
UBNF の要素は `EMPTY` だけなので、この例外で #706 の対象から外れる UBNF 文法は `EMPTY` を使うものに限られる。

**shared grammar call**: `shared_calls::Registry::call` は、Java の `SharedGrammarCalls` と同じく、
子 grammar を呼び出し元の consumed の位置から、両 cursor をそこに揃えて始める。呼び出し元の正の
`LOOKAHEAD` が進めた matched は、子の失敗位置にも rule frame の開始にもならない（`'a' LOOKAHEAD('x')`
の後に子 `Root ::= 'b' 'c'` を呼ぶと、`ax` / `axb` とも両版で CP1、`Root@1`）。呼び出し元の matched は
呼び出しの後に元へ戻る。

**変えないもの**: 受理・拒否、両 cursor、CST、AST、token は変えない。式単位の公開入口
（`ParseContext::parse`、`parse_with_token_mode`、`parse_token`）は、grammar の中から
呼ばれた場合も含め、その呼び出しの間は従来の frontier を使う。上の表の native PEG の
source failure（`context.parse(&child.ahead())` の CP0）と `peg-assertion/cases.tsv` の
`rustOffset` はそのままである。matched cursor が consumed を越えるのは Java 互換の要素
（正の `LOOKAHEAD`、`EMPTY`、`UNTIL`）か Classic token mode だけなので、それらを使わない
grammar の診断は変わらない。

**Java を Rust に合わせなかった理由**: Java の max(consumed, matched) は Classic の
failure frame 全体の前提で、この文書の表の 1 行目、92 行の手書き期待値、Java の LSP が
これに依存している。Java 側を変えると Classic の既存契約を広く動かすことになる。Rust 側は
grammar 呼び出しの境界で方針を切り替えるだけで済む。

**費用**: 分岐が 1 つ増えるだけで、割当は増えない。失敗の記録（Detailed のときだけ）と
rule frame の開始で比較を 1 回、公開入口で `bool` を 1 つ退避・復元する。
`DetailedOnFailure` の 1 回目の pass は、失敗の記録より前に戻るので何もしない。

**検証**: Rust `prior_matched_diagnostics` の
`grammar_invocations_measure_failures_at_the_matched_reach` が、全入力と `parse_grammar` の
両入口、CRLF と補助文字を含む CP4 の例、式入口が従来の frontier を保つことを、memo 2 種 ×
診断 3 種で確かめる。rule stack は [共有 fixture](../conformance/rule-stack/cases.tsv) の
`lookahead-later-start` / `lookahead-extra-frame` を Java `RuleStackTest` と Rust
`tests/rule_stack.rs` が両方とも検証する。`PropertyBasedParityTest` は既知差の除外なしで、
`regressions.json` の #706 の 5 例を Java / Rust の 12 設定で比べる。
