# Finite memo と scaling oracle (#665)

Java21/Rust1.85。製品・既定memo OFF・success/failure予算を変えず、有限cacheの飽和を
無限cacheと同じhit率だと仮定していたscalingテストを修正する単位です。性能改善ではありません。

## 原因と固定した対照

`feab1679`（#652直前）と`bde53e3b`（直後）の同じ元Scale文法・入力・JDK21で比較しました。
`measurements.tsv` は各サイズにつき同入力1回warmup後、新ParseContextで1回測った生データです。
プロセス間/共有host/JIT条件の違いを含み、精密な時間比較やproduction benchmarkではありません。

| scale / bytes | 元rollback | 予算導入後rollback | 導入後rollback/byte | success budget miss | 保持charge |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 / 400 | 64 | 64 | .1600 | 0 | 7,700,504 |
| 4 / 1,600 | 256 | 328 | .2050 | 867 | 13,766,096 |
| 16 / 6,400 | 1,024 | 1,376 | .2150 | 4,352 | 16,776,700 |
| 64 / 25,600 | 4,096 | 5,392 | .2106 | 16,390 | 16,776,976 |
| 256 / 102,400 | 16,384 | 21,136 | .2064 | 63,465 | 16,776,976 |

元テストは .160→.215 の1.344倍を既存1.3条件で拒否しました。JDK17/21のカウンタは同一です。
保持16MiBの飽和による再評価が係数を変え、x64/x256でも増加し続ける挙動ではありません。
同じ現行製品を別processで比較すると因果を分離できます。

- total64MiB: rollback64/256/1024/4096に戻り、保持は約20MiB。既存の単体予算/windowによる不採用1は残ります。
- 保存停止0: success hit/保持0、rollback192/768/3072/12288は同入力OFFと一致。
- total1MiB: rollback168/720/2920/11480。保持は1MiB内で、OFF以下。
- eviction無効: rollback64/432/2736/11952。保持16MiB内、eviction0、同じくOFF以下。
- このfixtureのfailure hit/保持は0です。実fraudのfailure budget飽和調査 #663 とは分離します。

割当増加は残ります。x256では旧1,251,485,376→新1,888,525,392 bytes（約+51%）でした。
cache plan/コピー等の代価を隠さず保持し、#663とも共有しています。RSS上限、速度向上、
一般文法の線形時間、親#184/#197の性能条件達成という主張ではありません。

## 変更後の検出条件

- 同入力OFFを独立に測り、従来の全counter/割当の1byteあたり1.3条件を維持します。
- ONのtransaction/rollback数を各サイズのOFF以下として直接制限します。OFFの線形な処理量が上界です。
- ONのtransaction・hit・entry・割当には既存1.3条件も維持し、rollback係数が無限cacheと同じという仮定だけを除きます。
- x64を追加し、既定success予算の飽和・有限件数/charge・evictionを実際に通します。
- 二次のworkと割当をそれぞれ注入するとoracleが拒否するnegative controlも実行します。

これは単なる1.3→1.35の緩和ではありません。新たな独立controlと線形上界を設けています。
Javaのtransaction数とRustのcheckpoint構造には同一カウンタ契約を要求しません。

## 共通fixtureと両host

`scale.ubnf` と手書き`cases.tsv`を共有し、`'let abc = 1+2*3-(4+5)/6; '`を16/64/256/1024回連結します。
全入力はASCIIなのでCP/UTF16/UTF8長は同じ400/1600/6400/25600です。
JavaはOFF/ONで全文消費・matched・CST offset0/原文を検証します。

Rust typed generatorはunmapped repeated rootを単一ASTにできないため、共通文法に
`@mapping(ProgramNode)`を明記しました。認識式は変更せず、元文法とJavaの全transaction/hit/
entry/charge/miss/eviction/underrunがx1/4/16/64で完全同値であることを確認しました。
元unmapped文法の前後測定は`measurements.tsv`に保持し、このAST root契約差を隠しません。

`MemoScalingConformanceTest`はJava/native両hostのRust生成fileを全byte比較し、各host生成物を
rustcで実compileします。OFF/ON・4入力・total16MiB/0/64MiB/64KiBで計64観測を行い、
受理/全文CP/committed CSTの全spanと原文・有限件数/charge・保存停止を検証します。Rustの64KiB条件では非空の保持と実際の予算不採用も要求し、
全unsafeによる自明な0保持を有限予算検証とは呼びません。
外側の非memo observerだけが生成grammar session中のcacheを読み、生成規則は変更しません。
このfixtureのRust実success hitは0で、Javaとのhit parityやRustのhit検証とは呼びません。
実hit・Unicode・状態/rollbackは既存[success budget oracle](../success-memo-budget/README.md)を再検証します。
reportは`unlaxer-dsl/target/memo-scaling-java.tsv`（8観測）と
`unlaxer-dsl/target/memo-scaling-rust.tsv`（64観測）です。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ParserScalingSmokeTest,MemoScalingConformanceTest,SuccessMemoBudgetConformanceTest,FailureMemoBudgetConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

## 前後測定の再現

測る各treeで先に`ParserScalingSmokeTest`を実行してclassesとSurefire classpathを用意します。
旧テストの修正後製品でのREDも有効な測定準備です。以下はsource/POMを変更せず、指定先へ証跡だけ出します。

```sh
python3 conformance/memo-scaling/run_probe.py --tree /path/to/compiled-tree --output /tmp/scaling-default
python3 conformance/memo-scaling/run_probe.py --tree /path/to/compiled-tree --output /tmp/scaling-roomy --budget-bytes 67108864
python3 conformance/memo-scaling/run_probe.py --tree /path/to/compiled-tree --output /tmp/scaling-disabled --budget-bytes 0
python3 conformance/memo-scaling/run_probe.py --tree /path/to/compiled-tree --output /tmp/scaling-small --budget-bytes 1048576
python3 conformance/memo-scaling/run_probe.py --tree /path/to/compiled-tree --output /tmp/scaling-no-eviction --eviction false
```

JAVA_HOMEでJDKを選びます。revision・文法/入力SHA256・toolchain・設定・dirty状態をmetadataへ、
生counter/割当/時間をTSVへ保存します。warmup/generation/compileの包含境界も記録します。
この小fixtureのprobeをTiny実fraudのbenchmark #657/#663の代替にはしません。
