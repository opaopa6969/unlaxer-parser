# Finite failure memo retention oracle

`cases.tsv` は製品出力から生成せず、文法 `F ::= '😀\r\n' '!'` と入力 `😀\r\n?` の
独立期待値を手書きで定義しています。far case は4000 CPの `x` prefixを追加します。
失敗後の両 cursor は0、committed CST は空、元の user state=7 と semantic warning
`kept` (CP0,length1,WARNING) は保存されます。詳細診断の末尾は CP3/UTF-16 4/UTF-8 6、
far case は各+4000です。期待token `!` は既存のJava表示 `'!'` / Rust表示 `!` をそれぞれ明示照合します。
既存 deferred diagnostic policy は公開位置0・空のexpectedを返します。

10行を normal と Classic4kind、reset2policy、diagnostics3mode、memo OFF/ON の積で比較します。
600構成/言語、31,260回のfailure試行/言語、ONで1,980回の実hitを要求します。
OFF は保存数/hit/予算不採用数とも0。ON の件数/hit/不採用数は TSV の独立期待を要求し、
単なる unsafe gate による miss を hit 検証とは呼びません。

* `same`: 同一 state の64回中、初回だけ保存し63回の実 hit。
* `epoch`: 毎回新しい state を使い、件数4の後は既存entryを保持してsafe miss。
* `rollback`: 元stateを保存し64個の枝をrollback、最後に元entryの実 hit。単調allocatorは戻しません。
* `evict`: 遠方へ移動して古い位置をevict、減った計数で再保存しhit。
* `underrun`: 遠方の解析をrollbackし、窓を拡張して元位置を再保存しhit。
* zero/small byte limit: 保存停止でも診断・状態・cursorは同じ。

`configuration.tsv` の25行は、UTF-8 hex（`-` は未設定、空欄は空文字）と、
同じ値を3設定へ渡した場合の件数/合計bytes/単体bytesの独立期待値です。
欠省/不正値の合計既定は32 MiB、件数65,536と単体1 MiBは維持します。
空白・符号・非ASCII数字・overflow・各ceiling境界・明示0を含みます。
Java/Rustとも各行を新しいprocessでconstructor/OnceLockまで読み、
既定memo OFF/AUTOと別のsuccess予算が変わらないことも確認します。
共有processのSystem property/環境変数は変更しません。

実行:

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=FailureMemoBudgetTest,FailureMemoConfigurationTest,FailureMemoBudgetConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

各テストは開始時に以前の証跡を削除します。Rust は全 budget テストと単独 oracle が成功した後だけ、一時 TSV を report として公開します。コンパイル・テストの stdout/stderr は失敗時も log に残します。gate は Java/Rust ごとの固定した mode・reset・memo・diagnostics の全600構成を要求します。

Java21/Rust1.85。Java report は `unlaxer-dsl/target/failure-memo-budget-java.tsv`、
Rust report は `unlaxer-dsl/target/failure-memo-budget-rust.tsv`（Rust の oracle が
`UNLAXER_TEST_FAILURE_BUDGET_EVIDENCE` のファイルへ書く。`-rust.log` は libtest の stdout で、診断用）。
CI は両方を `scripts/check-memo-budget-evidence.py` で検査します（#789）。
保持 bytes 自体は各言語の [charge 契約](../../docs/failure-memo-budget.md) に従い、
同一件数を同一byte数と見なさず、それぞれ上限内か検証します。

`retention-measurements.tsv` は、同位置の safe literal failure を100,000回、新epochで解析した
cold-process 3試行ずつの測定です。baseline `bd7faf68` と本修正、DetailedOnFailure、memo ON、
Java `-Xms64m -Xmx512m` / Rust `rustc -O`、`/usr/bin/time` の max RSSを使用しました。
同時に他のビルドも動く共有hostの測定なので、一般の速度向上やRSS削減の保証にはしません。
Javaは100,000→31,536件、Rustは100,000→65,536件で保持が止まりました。
Rust baseline charge=0は未計数を意味し、payload保持が0である根拠ではありません。
このcold試行では両言語とも修正後RSSの中央値が増えており、当時のcharge16MiBをRSS上限とは呼びません。
現在の32MiB既定の選択は[#670のproduction対照](../../benchmarks/tiny-classic/failure-budget-calibration.tsv)を参照してください。
