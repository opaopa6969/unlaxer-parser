# Mixed text/node conformance fixtures

`Mixed.ubnf` と `corpus.json` は、mapped leaf が2種類の `two` mode と、
`OtherRule` の choice 枝だけを除いた1種類の `one` mode を共有する。
`modes` のない成功例は両 mode で受理する。成功例の `value` は Text/Leaf/OtherLeaf を
区別する独立の評価 oracle、`texts` は文字列値ごとの `[開始, 終了, 値]` oracle。
offset は UTF-16 や byte ではなく Unicode code point。

Java の text value は String/Object で個別 source span を持たないため、AST 全 field と
node span は両実装で比較し、Rust の text span はこの独立 oracle と比較する。
`probe.rs.txt` は CST・ParseContext・入力文字列を破棄してから Semantics を呼び出す。
capture の生 source span と single-quote 除去後の値を混同しない。

bare choice の繰返しは text branch の後で delimiter を自動消費しない。
そのため `a::a a` は trailing input で不受理とし、mapped rule が消費する trivia や
外側 sequence の trivia と区別する。これを Rust 独自の whitespace 処理で補正しない。

`Shared.ubnf` と `shared-probe.java.txt` は shared generic mapping の宣言順問題を
独立に再現する。baseline `0b32f14` では First 先の生成 Java はコンパイル・実行できるが、
Second 先は record の Leaf field と mapper の Object 引数が不一致で javac が失敗する。
この probe はその失敗を表示する診断用であり、成功した conformance 件数には数えない。

修正後は `shared-corpus.json` と `shared-probe.rs.txt` を
`sharedMixedMappingsAreExecutableInBothDeclarationOrders` へ通し、11入力×両宣言順を
通常の成功条件として検証する。Java 単体の生成 javac/runtime は常時、Rust 比較は
ほかの conformance 同様 `-DrustConformance=true` で実行する。

`Delimited.ubnf` / `delimited-corpus.json` は `Outer ::= '(' Factor ')'` を必須・optional・
反復の各 capture に使う。Text の値は括弧を含む捕捉全体（末尾 comment も保持）、
Node の値は内側の mapped leaf。15入力×両 mode の Java oracle は実生成・実行で確認する。
`ValueBoundary` 導入前の Rust は `(a)` を `a` に縮めるため、この比較は失敗する。
別の型付き境界でそれを修正しても、既存 bare choice corpus の text span は変更しない。
