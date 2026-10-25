# LOOKAHEAD の後に失敗する group・選択（#793）

`corpus.json` の16文法16入力は、Java の parse frame の規則から独立に求めた期待値を固定する。
`LookaheadGroupFrameConformanceTest` は既存の `AtomicTriviaConformanceTest` harness を使い、
Java と Java-host / native Rust を別々に生成・compile・実行する。診断種別・code point 位置、
prefix の consumed / matched cursor、成功時の AST と span を memo OFF/ON ×
Detailed / DetailedOnFailure / Auto の6設定で比較する。明示 Classic 入口の prefix と成功時の
原文・AST も比較する。候補文字列の Java/Rust 間の一致は共通契約に含めない。

## 期待値の根拠

Java は parser を呼ぶとき `ParseFrame` を max(consumed, matched) から始め、失敗すると
その frame の到達を失敗候補にする。`'a' LOOKAHEAD('xyz')` の直後は consumed CP1、
matched CP4 なので、後続 group の開始は CP4。`'x'` の消費で両 cursor が CP2 に下がり
`'q'` が失敗しても、group の失敗候補は CP4 になる。選択の枝と入れ子 group も同じ規則。
根の失敗は transaction を rollback するので prefix は `[false, 0, 0]`。

- `LookaheadGroupFails` / `LookaheadChoiceFails` / `LookaheadNestedGroupFails`:
  issue の3例。`axyz` は syntax CP4。修正前の Rust は CP2。
- `LookaheadUnicodeGroupFails`: `a😀\r\nyz` の先読み到達は CP6。UTF-8 byte 数や
  UTF-16 unit 数で数えず、同じ frame 規則を適用する。
- `LookaheadGroupSucceeds` / `LookaheadChoiceSucceeds`: 成功した式の開始 CP4 は
  候補にならない。後続 `q` の失敗は、それぞれ消費後の CP3 / CP2。
- `LookaheadOptionalGroup` / `LookaheadRepeatGroup`: 任意・反復の子が失敗し、
  続く `r` が CP4 に候補を残す既存の挙動を保持する。
- `LookaheadNegativeTokenFails`: `x` の消費後 CP2 から `NEGATIVE_LOOKAHEAD('yz')` が
  失敗する。group の開始を候補に加えて syntax CP4 になる。
- `LookaheadGroupReads*`: group の中で `x` を消費した後は matched CP2 から
  `LOOKAHEAD` / `NEGATIVE_LOOKAHEAD` / `UNTIL` / `EMPTY` が読む。先読み2例は全体が
  成功して consumed / matched CP4。`UNTIL('y')` と `EMPTY` は prefix が `[true, 2, 3]`、
  全入力では trailing_input CP2。frame の開始 CP4 を live cursor に戻す実装を検出する。
- `LookaheadGroupFartherFailure`: 中の消費が先読み到達を越えた後は syntax CP5。
  frame の開始 CP4 が、より遠い失敗を上書きしない。
- `LookaheadGroupFailureMemo` / `LookaheadGroupSuccessMemo`: 子 group の候補を
  持つ R1 を同じ位置で再試行する。失敗 memo / 成功 memo の実 hit を全診断方針で
  要求し、再生後も syntax CP4 と rollback 後の両 cursor を保持する。

Rust の修正は失敗した `Sequence` / `Choice` / `PredictiveChoice` の既存 checkpoint を
復元した後に開始を記録する。成功経路の保存領域・割当を追加せず、matched cursor 自体は
変えない。Classic token mode、token-stream session、直接の式入口の frontier は既存規則に従う。

修正を無効にした runtime の専用コピーでは、必須3例を両 Rust 生成経路の6設定で実行すると
36結果すべてが syntax CP2 に戻り、corpus の CP4 と不一致になる（両 cursor は0のまま）。
Rust の3つの直接回帰テストも失敗し、既存の rule-frame 回帰は成功する。
