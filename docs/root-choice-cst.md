# root choice の CST 契約（#767）

共通 CST は **commit 済みの grammar root を保持する木**とする。Java 3.3.0 を参照実装とし、
Java / Rust の runtime・生成 parser・公開 API は変更しない。Rust の native / Java-host の生成経路、
通常 / Classic の入口は既にこの木を保持している。#767 の差は Java の観測対象を訂正して解消する。

## Java の root が消えて見えた理由

1. `ParserRuleEmitter.generateRuleClass` は複数選択肢を持つ rule を `LazyChoice` として生成する。
   `ParserGenerator.getRootParser()` の生成コードはその rule の parser を返す。root を消す専用の処理はない。
2. `ChoiceInterface.parse` は成功した子の `Parsed` を `PackratMemoTable.commitSuccess` に渡す。
   `commitSuccess` は choice 自身を commit した後、渡された子の `Parsed` をそのまま返す。
3. `Transaction.commit` は `CollectingParser` の token を作り、親 transaction の `tokens` に追加する。
   したがって `context.getCurrent().getTokens()` の root は choice rule、`parsed.getRootToken()` は選択枝である。
   親 rule の中の choice も、commit 済み木の中に rule token を残す。
4. 生成 mapper は context を閉じる前に commit 済み root を取得する。公開 `mapParsedToken` の Javadoc も
   choice の root を欠いた `Parsed` ではなく commit 済み parser root を要求する。この契約と実装は
   **`v3.3.0` tag にも存在する**。`SafeSuccessMemoizationTest#choiceHitReturnsTheSelectedChildParsedLikeAMiss`
   は、memo miss / hit の両方で返却 `Parsed` と commit 済み木を別々に確認している。

根拠となる現行ソース:
[ChoiceInterface](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceInterface.java)、
[PackratMemoTable](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java)、
[Transaction](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java)、
[MapperGenerator](../unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/MapperGenerator.java)。
公開版の確認は `git show v3.3.0:<path>` でできる。

以上から、選択枝を返す Java の挙動は維持すべき仕様と判断した。Java の CST 自体から root が失われる不具合ではない。
Rust の CST から root を削ると、Java の公開 mapping 契約と異なり、root capture・AST の型・source map を壊し得る。
既存の動作を保ち、同じ対象を比較する訂正が最小である。

## 利用側の API

Java の CST は parse 成功後、context を閉じる前に取得する。通常の生成 grammar では commit 済み root は1個である。

```java
Parsed parsed = parser.parse(context);
if (parsed.isSucceeded()) {
    Token root = context.getCurrent().getTokens().get(0);
    // root は grammar rule を保持する。公開 mapper へ渡すのもこの token。
}
```

Rust の full-input 入口は `Tree.root`、prefix / Classic 入口は
`context.tree(matched.root_node().unwrap())` が対応する commit 済み木を返す。
helper / terminal の表現は各言語で異なるため、共通 oracle は rule 名・親子関係・code point span を比較する。
root 以外の choice と choice でない root も保持する。空入力の成功では root span は `[0,0)`。

**残る API の区別:** Java の生の `Parsed.getRootToken()` は選択枝を指し、Rust `ParseMatch.nodes` は
commit 済み rule を指す。この戻り値をそのまま同一視しない。今回その API を変更・統一することはしない。
Java の返却 `Parsed` を CST と呼んでいた従来の記述は、この区別が抜けていた。

## 検証

`EmptyLiteralConformanceTest` は `spec-corpus/empty-literal` の AST・source map・診断の期待値を保ち、
CST の観測を commit 済み root に直す。CaptureRollback / Labeled / AutoCommit の `javaCst` 3件を削除し、
`javaCst` / `rustCst` をテストが拒否する。3件は通常 / Classic consumed / Classic matchOnly の CST も固定する。

追加の [共通 corpus](../spec-corpus/root-choice-cst/corpus.json) は12文法20入力。
直接 literal、sequence helper、rule 参照、入れ子 choice、sequence 内の choice、単一参照の root、
非 choice root、predictive / longest / unique-longest、javaStyle trivia、非 BMP、幅0成功を含む。
`cstOnly` はこの対照 corpus の観測項目を `cst` と `cstModes` に限定する。
`cstModes` は通常 prefix / Classic consumed / Classic matchOnly（非反転）の3入口。
memo OFF / SAFE_FAILURES を比較し、Java は choice の返却 token と commit 済み root が別物であることも直接確認する。
Java-host / native の生成物は byte 一致を確認したうえで、それぞれ独立に compile・実行する。
AST と source map は元の22文法92入力、LSP の診断 range は同クラスの生成 server 試験と Cross-language fixtures で確認する。
Classic 反転の cursor も元の corpus で継続検証する。

mutation 確認では観測箇所を `parsed.getRootToken()` に戻し、共通 CST 期待値との比較が失敗することを確認する。
Rust root を削る変更も共通 oracle に反するので、root を取り除いて同じ試験を成功させることはできない。
EOF・失敗条件・AST・source map・LSP・性能の実装変更はない。
