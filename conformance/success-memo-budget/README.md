# Finite success snapshot oracle

製品出力から作らず、次の文法と独立期待を手書きで固定しています。

```text
T = '👨‍👩‍👧‍👦\r\n'                 # CP9 / UTF-16 13 / UTF-8 27
S ::= (T '!') | T
input = T '?'
far-input = T + 'x' * 3991 + T + '?'
```

先頭候補がCP9（farは4009）で失敗した後、後続候補が成功します。
far-prefixにもfamilyがあるため、far frontierはCP4009 / UTF-16 4017 / UTF-8 4045です。
通常/消費kindはconsumed=9、非消費kindはconsumed=0、matchedは常に9。
各試行の外側rollback後は両cursor=0、committed CST空、user state=7、semantic warning
`kept` (CP0,length1,WARNING)を維持します。Rustではimmutable field captureとfresh/stale IDも確認します。
JavaはParsed.originalTokensと返却CSTを観測し、返却tree/child/metadata mapを毎回変更してからrollbackし、
次のhitへ漏れないことを要求します。命名capture/typed ASTは既存生成memoの両host oracleも再実行します。

10case × normal+Classic4kind × reset2policy × diagnostics3mode × memo OFF/ON = **600構成/言語**。
31,260成功試行/言語、ONで1,980回の実success hitを要求します。
単なるON指定やunsafeによるmissを実hitとは呼びません。

same-state、64epoch満杯、64branch rollbackと元entryのhit、zero/small-entry/total budgets、
eviction再保存、underrun再保存は `cases.tsv` の件数/hit/不採用数と照合します。
syntax expectedは `!`（Java表示 `'!'` / Rust表示 `!`）です。
既存のdeferred diagnosticsは位置0/空expectedを公開する契約を維持します。
保持byte数は言語固有chargeであり、件数から同一RSSを推定しません。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=SuccessMemoBudgetTest,SuccessMemoBudgetConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

Java21/Rust1.85。`unlaxer-dsl/target/success-memo-budget-java.tsv` と
`unlaxer-dsl/target/success-memo-budget-rust.log` が共通観測reportです。
言語固有storage試験は単体/合計/件数の正確な境界、置換と古payload解放、外部Source/不正tree、
深い正常treeの非再帰copy、巨大text/expected payload、distant indexとoverflowを補います。

`retention-measurements.tsv` は100,000個の同位置epochについて成功解析と外側rollbackを行った
cold-process測定（各3試行、Java -Xms64m -Xmx512m / Rust rustc -O）です。
Java保持は100,000→14,820件、Rustは既存byte予算で59,918件→追加charge込み31,300件でした。
修正前Javaのcharge=-1は観測APIが無いことを示します。Rustは以前から成功byte予算を持ちます。
RSSは16MiBを超えます。共有host上の短い測定をRSS保証、一般の速度向上、production workloadや
既存no-retry overheadの撤回とは扱いません。
