# Rust failure memo の保持窓（#290）

基準: `e0e5d2a`。変更前の保持方針は同一バイナリの
`UNLAXER_MEMO_EVICT_BELOW_FRONTIER=false` で再現する。
これは親commitとの速度比較ではなく、保持方針だけを切り替える対照実験である。
[raw TSV](2026-09-30-rust-memo-retention.tsv) に全112測定を保存した。

## 仮説と実装

失敗memoは解析中に使い終わっても入力終了まで生存していた。Javaのケース35と同様に、
consumed cursor の最大到達位置から1,024 code pointより前のバケットを解放する。
Rustは既存の256位置ごとのHashMapを丸ごとdropする。`clear()`ではcapacityが残るので不十分。
境界はbucket単位に切り下げるため、指定窓に最大255位置分が加わる。

これは確定済みの構文境界ではなくキャッシュ保持方針である。古い位置への再訪は許す。
再訪時は窓を `2 * max(現在の窓, 観測したlook-back)` に拡張し、保存拒否境界も後退させる。
捨てた結果は再解析で作り直す。Javaでは窓だけ広げて保存拒否境界を戻しておらず、
再解析してもmemoが保存されない不具合があった。今回Java側も修正した。

cursor、capture、user state、scope、CSTや診断をこの解放処理で変更しない。
安全と判定済みの失敗ruleだけが対象で、stateful memoの対象拡張は含まない。
grammar session切替時は保持窓も含むmemo table全体を隔離・復元する。

## 測定

AMD Ryzen 9 7950X / Linux WSL2 x86_64、rustc 1.98.1、release build。
各条件は別process。warmup 1回、記録7回。FIRST候補除外はOFF。
文法は `root = (failure | failure | token)* EOF; failure = token "!";`。
同じ失敗を各位置で二度試し、memo hitを発生させる人工負荷で、TinyExpression P4ではない。

```sh
cargo test --release --quiet --locked --manifest-path rust/Cargo.toml \
  -p unlaxer-alloc-audit --test memo_retention
```

CountingAllocatorで、contextと入力の構築後からparse中までの「追加で生存した要求heap byte」の
最大値を測る。入力・offset表の事前確保、allocator内部metadata、stack、RSSは含まない。
`realloc`は論理的なサイズ差を数えるため、移動時の一時的な二重確保も含まない。
経過時間にはallocator計測のatomic操作を含む。共有マシン上の逐次測定であり、速度の優劣を
確定するbenchmarkではない。以下はDetailedの7回中央値。遅延診断の全値もTSVに残す。

| 入力 | 保持し続ける: peak byte | 窓あり: peak byte | 削減 | 時間 ms（保持 → 窓） |
|---|---:|---:|---:|---:|
| ASCII 200 CP | 25,162 | 25,162 | 0% | 0.0326 → 0.0324 |
| ASCII 4,000 CP | 525,114 | 183,818 | 65.0% | 0.8187 → 0.8481 |
| ASCII 32,000 CP | 4,182,922 | 187,402 | 95.5% | 7.2781 → 5.6407 |
| 絵文字 32,000 CP（128,000 byte） | 4,182,922 | 187,402 | 95.5% | 7.8341 → 5.5156 |

DetailedOnFailureではASCII 200 CPが0.0283 → 0.0501 ms、4,000 CPが0.7238 → 0.9660 msと
遅くなった測定もある。高速化を採用根拠とせず、今回の効果は長入力の保持メモリ削減とする。
窓が小さいと再解析は増え得る。非適用の比較スイッチを残す。

## 検証と限界

- 共通 `unlaxer-common/src/test/resources/memo-retention.tsv` をJava/Rust双方で読む。
  ASCII/補助面文字、200/1,024/4,000 CP、成功と末尾不正入力について、受理・consumed位置・
  farthest位置をfixtureに照合。各runtimeで窓OFF/ONのmatched位置・expected・hit数も比較。
  RustはCSTも比較し、Detailed / DetailedOnFailure両方で試す。
- この局所backtrack corpusではhit数は全件同じ。入力が窓以下ならevictは0件。
- 4,000 CPを先頭まで巻き戻す別テストでは、Java/Rustとも窓拡大後に古い位置へ再び保存できることを確認。
  Rustは繰り返す拡大、bucket境界、遅れて完了したruleの挿入、整数上限も検証する。
- 有限corpusのhit一致は、任意文法で一切hitを失わない証明ではない。実際、窓を越すbacktrackは
  既に捨てたentryを復元できず一時的にmissになる。最悪時間計算量の保証も追加しない。
- bucket用の空のVec slotは入力長/256に比例して残る。入力・CST等も別に保持するので、
  parser全体が定数メモリになったという主張ではない。

## 設定

Rustはprocess内の最初のcontext生成で環境変数を一度だけ読む。

| 用途 | Java | Rust |
|---|---|---|
| 窓（既定1,024 CP） | `-Dunlaxer.memo.window=1024` | `UNLAXER_MEMO_WINDOW=1024` |
| 解放を無効化 | `-Dunlaxer.memo.evictBelowFrontier=false` | `UNLAXER_MEMO_EVICT_BELOW_FRONTIER=false` |

Rustの窓は正のusize。無効値・0は既定値を使用。解放スイッチは正確に`false`のときだけOFF。
memoization自体は従来どおりopt-inのまま。小入力でも観測hookの費用は残る。
