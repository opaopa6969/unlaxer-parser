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
