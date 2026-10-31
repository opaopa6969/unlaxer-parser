# Java parser の再帰資源制限

Java runtime は `ParseContext.startParse` で通知された同時実行中の parser frame を
1024 に制限する。組込み parser と通常の生成 parser はこの通知を使う。
上限を超える次の通知は `ParseLimitException` で解析セッションを中断する。
`code()` は `ULX-PARSE-DEPTH-LIMIT`、`limit()` は上限、`offset()` は通知された
TokenKind のカーソルにおける原文のコードポイント位置を返す。

これは通常の構文不一致や `Parsed.FAILED` と別の資源制限である。
生成 mapper の `parse` / `diagnose` でも例外を呼出元へ伝える。
DETAILED と DETAILED_ON_FAILURE の両方に適用し、診断を遅延しても上限は無効化されない。
上限に達したセッションでは memo 利用を無効にし、再解析を試みても同じ例外を返す。
別入力への再試行には新しい `ParseContext` を作る。否定assertion・optional・choiceでも
この資源制限を通常の構文不一致として吸収して成功へ変えることはない。

```java
try (ParseContext context = new ParseContext(StringSource.createRootSource(source))) {
    Parsed parsed = parser.parse(context);
    // 通常の構文不一致は parsed.isFailed() で判定する。
} catch (ParseLimitException failure) {
    // close の復元後に、failure.code() / offset() / limit() を呼出元へ通知する。
}
```

資源制限の例外では通常の parser return を通らないため、`close()` が未完の
transaction を内側から外側へ片付け、明示的に登録された `TransactionalState` と
choice/interleave の選択状態を復元する。通常の成功・rollback 通知や `onEnd` は合成しない。
`onClose` は復元後に実行する。任意の外部副作用や未登録 object の復元は含まれない。
別の未知例外や呼出元の誤った transaction nest に対する既存の close 検査は変更しない。

| 検証対象 | 条件・限界 |
|---|---|
| JVM thread stack | `-Xss1m` 以上を検証対象とする。cold な `-Xint` process でも、通常と8重group wrapperの生成再帰文法を検証する |
| shallow / deep 入力 | 各diagnostics/memo modeで64段を受理し、10,000段をStackOverflowErrorではなく型付き資源制限で中断し、closeの二次nest例外を出さない |
| 既存 frontend | 64 / 96段のUBNF group文法を保持する。frame数は文法構造に依存し、入力の括弧数と同じではない |
| 独自 parser / callback | `startParse` を通知しない独自再帰、任意callback自身の大きなstack消費、JVM設定の異なるstack、AST mapper等の別再帰処理はこの上限の保証外 |
| Java / Rust | Javaは型付き例外によるセッション中断。Rust #506 はrule/expressionの資源上限をnativeな失敗診断として返す。どちらも過深解析を制限するが、同じ数値・同じ文法ネスト深さ・同じ診断文字列は保証しない |

回帰テスト: `ParseDepthLimitTest`、`JavaParseDepthLimitTest`、
`RustUbnfFrontendConformanceTest`、`RustPortabilityConformanceTest`。

## UBNF の入れ子の上限（#740 / #741 / #754）

UBNF 文法そのものの入れ子は、frontend の構文解析と、生成時の解析で制限される。
Rust 生成の lowering の上限は両 host で同じだが、frontend の上限は意図して異なる。

| 段階 | Java host | Rust host（native） |
|---|---|---|
| frontend：規則本体の入れ子 | 専用の計数はない。UBNF parser の frame が上記の 1024 を超えると `ParseLimitException`（`ULX-PARSE-DEPTH-LIMIT`）。届く段数は入れ子の形で変わる（下表） | `MAX_NESTING` = 128。規則本体を 1 段目として本体の入れ子を数え、128 段まで（括弧なら 127 段まで）受理し、129 段目の本体（括弧 128 段目）を開くと `UBNF nesting limit exceeded`（`DiagnosticKind::NestingLimit`）。形によらない |
| frontend：宣言的 token の括弧 | 128 段で構文失敗 `UBNF パース失敗`（内部の `LexicalSyntax` が 128 段で止める。文言は例外に含まれない） | 128 段で `lexical nesting exceeds 128` |
| Rust 生成の lowering | lowered IR の構造解析の深さ 256。超えると `Rust subset: structural analysis depth exceeds 256` | 同じ深さ 256 で `structural analysis depth exceeds 256` |
| Java 生成の固定点 | AST の式の深さ 256（単一選択肢の choice は追加の深さに数えない）。超えると `E-RECURSIVE-SHAPE: expression depth exceeds 256` | Java target はない。Rust preferred projection の解析で同じ計数と上限を使う |

frontend の上限を `R0 ::= 'x' (…'a'…) @x` の形の括弧の段数で測った値（2026-10 の master）:

| 入れ子の形 | Java 受理の上限 | Rust 受理の上限 |
|---|---|---|
| 括弧 `( )`、`[ ]`、`{ }`、`( )+`、`( )*`、`( )?` | 168 | 127 |
| 選択肢を開く括弧 `('b' \| …)` | 126 | 127 |
| 宣言的 token `token T ::= (…'a'…)` | 127 | 127 |

#996: native はこの AST 上限の先も明示 `Vec` で構文だけを反復認識し、後続の構文不正を優先する。
正常な構文では全体認識後に最初の mapper 値／移行診断、それがなければ既存 `NestingLimit` を返す。
認識器の時間・補助空間は入力長に対して線形で、debug・2 MiB の10,000段も検証する。
Java の frame 上限による型付き例外は模倣せず、通常構文不正と区別して共通 oracle へ固定する。
構文ごとの実測境界・CLI の終了値の残差は [REGEX移行文書](regex-retirement.md#ast-上限後の反復認識996) を参照。

frontend の上限をそろえない理由:

- Java の上限は parser frame の数なので、入れ子の形ごとに受理できる段数が変わる。
  Rust の 1 本の計数をどの値にしても、すべての形で一致させることはできない。
- Java 側に 128 段の計数を足すと、いま受理している括弧 128〜168 段の文法を拒否するようになる。
- Rust 側を 168 段に上げても、選択肢の形では Java が 127 段から拒否するので一致しない。
  `MAX_NESTING` は AST の再帰的な破棄の深さと、`E-PARAM-LIMIT` の展開の入れ子（`MAX_EXPANDED_NESTING`）の
  上限も兼ねており、上げると stack の確認範囲も広がる。

このため frontend の差は契約とする。片方の frontend だけが受理する文法を書かない場合は、
規則本体の入れ子を 127 段まで（選択肢の形では 126 段まで）に収める。

Rust 生成の lowering では、再帰する規則の値の形を求める固定点も、構造解析の深さの上限 256 で
数える（#740）。固定点は最初の再帰参照に出会ったときに全規則についてまとめて解くもので、呼び出し元の
解析の一部ではないため、呼び出し元の深さを引き継がず 0 から数える。Java host も native と同じく
lowered IR の上で同じ数え方で固定点を解くので、両 host は同じ文法を受理し、同じ深さで同じ診断を返す。
たとえば再帰する helper を括弧で包んだ `H ::= 'n' R0 | 'y' (…H…)+ | 'a'` は、両 host とも 122 段まで
同じ生成物を出し、123 段で `structural analysis depth exceeds 256` で拒否する。native は括弧 128 段目
（本体 129 段目）を開く時点で frontend の `UBNF nesting limit exceeded` を先に返すため、その段数からは、
両 host とも拒否するが診断の文言は frontend の差に従う。解析の再帰は、固定点の前の 256 段と固定点の
256 段を合わせた深さまでになる。この最も深い場合も、Java は `-Xss1m -Xint`、Rust は debug build の
既定 2 MiB の test thread で通ることを確かめている。

Java の Java 生成（`ParserGenerator` / `ASTGenerator` / `MapperGenerator`）は、AST 上の固定点
（`RecursiveSemanticShapes`、深さ 256）を使う。単一選択肢の `ChoiceBody` は AST の表現上の包みなので、
その alternative を同じ深さで評価する（#754）。複数選択肢の choice、sequence の要素、element の子は
従来どおり深さを 1 増やす。括弧 1 段を choice / sequence / element の 3 段と数えていたのが 2 段になり、
上の再帰 helper は 84〜122 段すべてで Java 生成も成功する。Java 生成単体では 126 段まで受理し、
127 段で従来の `E-RECURSIVE-SHAPE: expression depth exceeds 256` を返す。Rust 生成の lowered IR は
前述の 122 / 123 段の境界を保つ。両 target を使う文法は低い方の上限に収める。

深さの数値を引き上げたり、固定点の値の計算を変えたりはしていない。単一選択肢の choice も訪問ごとの
作業量に数えるので、100 万回の上限・短絡評価・非収束の扱いは変わらない。選択肢を実際に増やす
入れ子には依然として深さの上限が適用される。深さを増やさない呼び出しは choice → sequence の 1 回だけで、
次の element では必ず増やすため、VM stack の使用も有界である。生成 3 経路について、cold な
`-Xss1m -Xint` JVM で 126 段の成功と 127 段の診断、および 82 規則の参照列の先で
126 段の固定点解析を始める組合せを検証する。

Rust の `preferred_plan.rs` も同じ深さを使う。#737 の旧境界の直後でも preferred projection を保持するようになり、
`parser.rs` / `mapper.rs` に投影情報が追加されるが、Java が既に生成できた文法の Java 生成物は変えない。
84〜130 段の 3 形（再帰 helper、separator 側の再帰、数値 capture 付き）の掃引で両 Rust host の
5 ファイルを byte 比較し、受理範囲では preferred projection があることも確認する。生成 Java parser を
コンパイルして成功・失敗入力を解析し、両 host の成否・CST・AST・位置を 12 設定で比較する。
生成は成功したまま固定点の上限で projection だけが外れる新しい境界（mapped 値を含む再帰の横に置いた
helper の `( )+` 85 / 86 段、`( )?`・`( )*` 63 / 64 段）でも、両 host の 5 ファイルが一致し、Java 生成が
成功する段で projection があり、拒否する段で無いことを確かめる。`( )`・`[ ]`・`{ }` はこの形では
native frontend が受理する最後の 127 段まで projection を保つ。

Rust native generator は **debug build・2 MiB の呼出元 thread stack** で検証する（#805）。
CLI の main stack を `ulimit -s 2048` にした場合も同じで、追加の worker thread や
`RUST_MIN_STACK` の指定は不要。mapped 再帰の横に置いた `H ::= [[…'a' M…]]` では
`[ ]`・`{ }`・`( )`・`( )+` の 127 段まで生成できる。`( )?`・`( )*` は 84 段まで生成し、
85〜127 段では両 Rust host とも既存の `structural analysis depth exceeds 256` を返す。
いずれも native の 128 段は frontend の `UBNF nesting limit exceeded` になり、出力を作らない。
preferred projection の既存の境界（上記の 63 / 64、85 / 86 段）も維持する。

元の stack overflow は IR のソース出力関数 `unlaxer_codegen::expression` が debug build で
約 10 KiB の書式化用 frame を各 IR 段に保持していたためで、通常出力と preferred 出力に共通する。
子の出力を先に完成させ、書式化を非再帰・非 inline の関数に分離して、再帰 frame を小さくした。
さらに、拒否対象の深い `( )?`・`( )*` は lowering の enum 構築 frame を group ごとにも保持していたので、
IR の wrapper を増やさない group は小さな frame から直接その本体へ進む。
frontend・構造解析・preferred 解析の上限や出力テンプレートは変えない。

上の修正の後は、127 段の括弧で最も stack を使うのは frontend の構文解析（debug で約 1.8 MiB）だった。
さらに `CAPTURE(…)`・`CAPTURE_SCOPE(…)`・`COMMIT(…)`・`LABEL(…)`・`CATCH(…)` の入れ子は 1 段あたりの
frame がより大きく、104 段から、引数付き規則の呼出し `P(P(…))` は 126 段で frontend が stack overflow
していた。frontend の `element` / `atomic` も、再帰するのは子の解析だけにし、接頭辞・後置演算子・capture・
葉の解析と builtin の各演算をそれぞれ別の非 inline 関数に分けた（1 段あたり約 14 KiB / 20 KiB から
約 7 KiB / 8 KiB）。受理する文法・診断・AST は変えない。mapped 再帰の横の helper で、これらの builtin は
127 段（`CATCH` は 84 段まで生成し 85 段で構造解析の上限）、`P(…)` は 126 段（127 段は既存の `E-PARAM-LIMIT`）まで
2 MiB で生成でき、128 段は frontend の `UBNF nesting limit exceeded` になる。
選択肢・`%`・`{n,m}`・規則参照の長い列（3000 規則）・宣言的 token の入れ子（既存の `lexical expansion exceeds 128`
まで）・`--lsp --dap` 付きの生成も、同じ 2 MiB の debug CLI で panic / abort しないことを確かめた。
この保証は生成器の文法入力に対するもので、任意の巨大な手製 IR や 2 MiB 未満の stack は対象外。
`RustNativeGeneratorTest.nativeGenerationFitsTwoMegabyteStack` は同じ入力を Java host でも生成し、
成功する 58 文法の全 5 ファイル（290 ファイル）を byte 比較する。構造上限の 14 文法と
frontend 上限の 6 文法も、panic / abort ではなく診断・終了 code 3・出力なしになることを固定する。
Rust の `nesting_limits.rs` でも明示した 2 MiB thread で同じ境界を検証し、builtin と引数付き規則の
境界は `builtin_and_parameterized_calls_fit_two_megabyte_stack` で固定する。

Rust 生成の整数 field の判定は、両 host とも `numeric_fields.rs` と同じ「mapping を共有する、
`@skip` でない全規則の対象 capture がすべて数値 token」の規則を直接使う（#758）。`@typeof` の制約に使う
capture も同じで、mapping の field はその判定結果に従い、mapping にない capture は自分の規則の capture だけで判定する。lowered IR が既に
値の種類と個数を求めているため、数値への絞り込みに Java AST 全体の型推論は必要ない。以前は field ごとに
この型推論を呼び、無関係な規則も固定点の各反復で再走査したため、80 規則の参照連鎖と 560 規則の literal 列が
隣にあるだけで `analysis work exceeds 1000000` に達し、Java host だけが生成に失敗した。

native にも lowered IR と preferred projection の解析作業量上限 1,000,000 はある。差の原因は native の
上限欠如ではなく、Java host の数値判定だけが行う余分な解析だった。このため上限の引き上げや、上限に達してからの
fallback ではなく、不要な固定点呼び出しを省く方法を選んだ。固定点の memo 化・反復方法の変更は preferred AST の
生成可否にも影響するので、この修正には含めない。数値判定は AST 固定点の深さ・作業量・非収束に依存しなくなるが、
lowered IR の解析失敗は両 host とも従来どおり返し、preferred projection の解析失敗は従来どおり投影を無効にする。
#758 の修正は、Java target の AST 型推論とその制限（前述の #754）、公開 API、生成 parser/runtime の処理を変えない。

回帰テスト: `RustBackendTest`、`NumericFieldsTest`、`RustNativeGeneratorTest`
（`recursiveNestingLimitsMatchJavaHost`、84〜130 段の掃引、固定点の各構文の境界、cold JVM の生成、`numericAnalysisWorkMatchesNative`、
0 / 500 / 550 / 560 / 575 規則の filler と数値 capture の生成物 5 ファイルの byte 一致、
580 / 600 / 700 規則で同じ作業量診断。`@typeof` 付きの `typeofBesideAnalysisWork` も同じ掃引）、
`PropertyBasedParityTest.deeplyGroupedRecursiveHelpersAgreeAcrossTargets`、`RustUbnfFrontendConformanceTest`
（`frontendNestingLimitsAreTheDocumentedContract`、126〜200 段の掃引）、Rust の
`unlaxer-generator/tests/nesting_limits.rs` と `unlaxer-ubnf/tests/frontend.rs`。
