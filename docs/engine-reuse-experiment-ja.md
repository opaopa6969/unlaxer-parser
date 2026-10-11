# 固定文法での既存生成エンジン再利用評価（#435）

2026-10-12。結論は **production 採用には証拠不足**。下記の限定された AST profile
では既存 ubnfc の Java / Rust 生成物を再利用できた。Classic に別の生成エンジンを
追加する理由は得られず、既存 engine、既定値、`ParseContext`、性能作業の凍結は変更しない。
これは [設計提案5](parser-execution-design-qa-ja.md#提案5-要求する機能に合う既存エンジンを選ぶ264)
の隔離実験であり、任意 UBNF の自動 engine 選択や共通 production API の実装ではない。

## 固定した比較対象と独立期待値

| 項目 | 値 |
|---|---|
| Classic Java / Rust | `875144631d932d7fe31e66a3b68045ecaf77928e` |
| ubnfc Java / Rust | `95fd2a54e0769949d88f5be11d86eeae66a1ce25` |
| 文法 | [Reuse.ubnf](fixtures/engine-reuse/Reuse.ubnf)、4経路とも同じ bytes |
| 文法 SHA-256 | `93461623ea7d69af4c587caa045588a1364e6a7bd3cd7a79d44f88f7e341af6c` |
| profile | 全入力、typed AST 全 field、元 text、CP / UTF-16 span、owned snapshot |
| 診断 | 主 offset と必須 terminal は独立期待値。最遠 offset と native expected 集合は生のまま保持 |
| toolchain | JDK 21.0.9、Rust 1.85.0、Python 3 |
| adapter | `engine-reuse-435-v1`。各ファイルの SHA-256 は [manifest](reports/engine-reuse-435/manifest.json) |

[18入力](fixtures/engine-reuse/cases.json)は正常8件・拒否10件。非BMP、CRLF、空値、
同じ値で異なる位置のノード、複数の子、ASCII空白・非改行空白・全角空白、途中入力、
末尾の余分な文字を含む。[oracle.py](../examples/engine-reuse/oracle.py)はこの小言語の
規則から独立に AST / text / 座標 / 受理拒否 / 必須 terminal を計算する。
いずれかの engine 出力を期待値として採取してはいない。mapper の text capture は
`String.strip()` 相当であり、scope capture の `String.trim()` と混同しない。

[raw correctness](reports/engine-reuse-435/correctness.tsv) は **18 × 4 = 72件が一致**。
AST の `Packet.first` / `second` / `others` と各 `Atom.value` をすべて比較する。
`Canonical.java` は record と identity source map、Rust adapter は生成 enum と node span を
明示的に射影する。各 node の CP 範囲から元 text と UTF-16 範囲を作り、独立 oracle と
照合する。空文字は `s`、文字列は `s` + UTF-8 hex なので、空リストと空文字のリストも区別する。
成功後に別の入力を解析しても、保持した AST / source map の結果が変わらないことを確認する。

拒否行の列は `case / route / REJECT / 主CP位置 / 最遠CP位置 / 主expectedのhex /
最遠expectedのhex`。成功行には全 AST が入る。主 expected は独立 oracle の必須 terminal を
含むことを検査するが、native expected 集合全体の同値性は主張しない。たとえば末尾に余分な
文字がある入力では主位置13に一致し、Classic Rust の最遠位置は13、他3経路は直前の
投機的失敗位置11を保持する。これを AST / 受理の不一致や、同じ診断内容と読み替えない。

private ubnfc の source、内部文書、生成物は公開リポジトリへコピーしない。
公開するのは新規に書いた文法・oracle・adapter・driver と、この文法に対する結果だけ。
生成物・build log は ignored `target/engine-reuse/` に置く。

## capability、純粋性、memo の分離

[34 capability fixture](fixtures/engine-reuse/capabilities.tsv) と
[19 proof fixture](fixtures/engine-reuse/proofs.tsv)を Java / Rust で実行し、共通の期待値へ
照合する。全 AST の同値性を認定した固定 profile のみ `UBNFC_MEMO` 候補とし、
完全CST、user state、scope/replay、trace、incremental、custom、provider、callback の要求を
黙って落とさない。ubnfc を明示した場合も同じ不足条件で拒否する。

この表は **固定875 revisionでの実験 profile** の認定範囲である。後続 revision の DAP 等の
機能を過去の証拠へ混ぜない。`RUST_RICH_PROFILE_UNPROVEN` は、Rust API が一切存在しない
という意味ではなく、要求全体の移行同値性がこの実験では認定されていないという意味である。
Classic Rust に切り替えただけでその要求を「対応済み」にしない。

proof は小さな式モデル `lit / one / empty / seq / alt / star / look / cycle / variable /
custom / state` に対する保守的検査であり、UBNF frontend ではない。`reuse-whole-grammar`
は固定文法を手動で対応付けたもの。文法変更時にその認定を引き継がない。production に
適用するには、生成IRからの完全な抽出、revision と認定の結合、全 combinator の性質の
扱いが別途必要になる。

| proof から得る性質 | 結果 |
|---|---|
| 非nullable反復、既知の1CP terminal、互いに素な FIRST | 限定モデルで memo 無しの適格性を認める |
| nullable cycle / nullable repeat | 純粋でも拒否 |
| 重なる FIRST / nullable choice | 純粋でも拒否 |
| lookahead の重複探索 / 可変字句 | 純粋でも拒否 |
| 未知 custom / 未知の式 | 純粋性も不明として拒否 |
| state 依存 | stateful として拒否 |
| 深さ32を超える分析 / 4096 bytesを超える式 | 証明不能として拒否 |

**適格判定に通っても、この実験では memo を無効化しない。** 既存の memo を保持した経路を使う。
純粋性が不明なら失敗時の再実行を許さず、最初から Classic を1回呼ぶ。
未知の foreign custom parser を翻訳したことにも扱わない。capability 表は移行方針の検証であり、
全 rich 機能の実行試験を新たに完了したという証拠ではない。

## Rust の隔離 driver と失敗診断

[driver.rs](../examples/engine-reuse/driver.rs) は固定 engine probe を別 process として呼ぶ。
元入力 bytes、文法 SHA、snapshot ID を束ね、各応答の binding と前後の元入力を検査する。
固定した生成コマンドと source pin が probe の出所を決める。自己申告の header が
任意の実行ファイルの真正性を保証する仕組みではない。

- 成功 AST は oracle の全 field / source / CP / UTF-16 と照合する。
- generated 経路が拒否した場合だけ、同じ入力・文法・snapshot で新しい Classic process を呼ぶ。
  新しい context / memo を使い、診断を取得する。
- generated 拒否 / Classic 受理、位置・AST・元 text の相違を明示エラーにする。
- 起動失敗・process異常終了・入力/出力上限・期限超過を構文拒否へ変換しない。
- 全 engine 呼び出しで1つの deadline を共有する。入力4096 bytes、出力65536 bytes、
  engine 呼び出し最大2回、通常の fixture は5秒。Java probe は `-Xmx256m`。
- provider / callback の再実行安全性が不明な場合は generated 経路を呼ばず、Classic の1回だけ。

[driver raw結果](reports/engine-reuse-435/driver.tsv)は **59件**。
実 engine の18入力 × Java / Rust = 36件と、23件の故障注入を実行した。
故障注入は AST field・CP・UTF-16・元 text の相違、grammar / snapshot / source の取り違え、
解析中の source 変更、前後の期限、共通期限、入力/出力/oracleの上限、不正な数値引数、process失敗、callback/providerの
呼び出し回数を検証する。故障した probe は実 engine のバグを装うための独立 fixture である。

callback/providerのfixtureはengine境界での再呼び出し禁止を検証する。実際の外部provider登録や
stateful grammarのadapterは追加しておらず、その内部の作用を含む安全性は未認定である。

呼び出し回数は **probe process の起動回数**。認識・mapping・保持結果検査など、probe 内の
parser 実行回数と同一ではない。直接起動して制御できる既知の probe が対象で、任意の
外部 provider や子process群を安全に強制停止する汎用 sandbox は実装していない。

## 測った境界と採用判断

| 境界 | 正確性・実行の証拠 | 速度 / heap / allocation |
|---|---|---|
| 生成 | 固定1文法から4経路を実際に生成 | 未計測 |
| 準備 | compile、固定 profile と proof の照合 | 未計測 |
| 認識 | 18入力の受理拒否・主CP位置 | 未計測 |
| AST adapter | 全 field / text / CP / UTF-16 / retained snapshot | 未計測 |
| 失敗診断 | 同 snapshot、新context、期限共有、mismatch検出 | 未計測 |
| heap / allocation | Java heap上限は設定。Rustのheapとallocation数の実測は無し | **未計測** |

固定 corpus の3run速度計測は行っていない。数値として設定した期限や上限は性能測定値ではなく、
速度改善・低allocation・低heapの根拠にはしない。Classic の性能改善も開始しない。

| 実装面 | 今回の到達点 | 残る条件 |
|---|---|---|
| Classic Java / Rust | 固定文法の生成・AST/source/拒否参照 | 任意文法全体の同値性 |
| ubnfc Java / Rust | 同じ文法の生成物と明示adapterを再利用 | 他profile、stateful/外部callbackの証明 |
| Rust 隔離driver | oracle・binding・期限・再試行制御を検証 | production API、汎用IR分析、資源測定 |
| Java | 同じoracleとcapability/proofの参照を実行 | **lean facade / 共通API は未実装** |
| 公開CI | 現在の Classic 両言語、共通fixture、Rust driver | private ubnfc の2経路は公開CIでは実行しない |

したがって判断は「固定 fixture の再利用は成立」「production 採用には証拠不足」。
既存 ubnfc を使えばこの検証に足り、新 engine を実装する必要は無かった。
一般的な facade の採用を提案するときは、上記の残条件と両言語の受入条件を持つ別issueで扱う。

## 再現

公開側だけを検証する（private access不要、CIもこのコマンド）:

```sh
python3 examples/engine-reuse/run.py
```

固定4経路の検証（private ubnfc のclean checkoutへのアクセスが必要）:

```sh
python3 examples/engine-reuse/run.py --ubnfc /path/to/ubnfc
```

後者は ubnfc の HEAD と clean 状態、Classic の対象 source が固定revisionから変更されていないことを
確認してから build / generate / compile する。保存された manifest と raw TSV は
[reports/engine-reuse-435](reports/engine-reuse-435/) にある。公開CIでは、そのCI revisionの
Classicを検証して artifact に記録する。privateを含む固定4経路を毎回検証するものではない。
`--skip-build` は同じ生成物で adapter の修正を検証するときの開発補助で、初回再現には使わない。
