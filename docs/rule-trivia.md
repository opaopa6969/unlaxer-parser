# Java / Rust のルール単位 trivia 契約

追跡: #172（親 #111）。`@whitespace` と `@interleave` は annotation を捨てて受理する
のではなく、Java parser と両 Rust 生成経路で delimiter の挿入規則へ反映する。

## 設定と優先順位

対象の grammar 設定は `@whitespace: javaStyle` / `@whitespace: none`。
未指定は自動 trivia なし。rule では `@whitespace`（style 省略）、
`@whitespace(javaStyle)`、`@whitespace(none)` を使える。
`@interleave(profile=javaStyle)` と `@interleave(profile=commentsAndSpaces)` は、
この範囲では同じ空白・行コメント・block comment の自動挿入を有効にする。

rule の有効設定は次の順で決める。

1. 明示した rule `@whitespace` があればそれを採用する（annotation の記述順とは無関係）。
2. なければ rule `@interleave` により有効化する。
3. どちらもなければ grammar の既定設定を使う。

呼出し元 rule の設定を子 rule が動的に継承するわけではない。親の連接が child の
前後へ挿入する trivia と、child 内部の設定は別である。たとえば親が有効、child が
`none` なら child の外側は読み飛ばせても、child の連接内の空白は許さない。

適用位置は連接・生成 delimiter wrapper の境界であり、全 atom / choice に無条件で
skip を加えるものではない。引用符や code fence の内部は原子的な字句のまま。
group、optional、repeat、separated list、alias の境界も共通 corpus で比較する。

whitespace style は trim 後に大文字小文字を区別しない。interleave profile は trim 後の
`javaStyle` / `commentsAndSpaces` を正確に指定する。不正値と重複設定・重複 annotation
は Java 検証器と両 Rust lowerer で拒否する。Java の `ParserGenerator` を直接呼ぶ場合は
従来どおり先に検証器を通す。`@whitespace(none)` と `@interleave(...)` の併記は重複とは
せず、前者が勝つ。

## Java の global none と移行

変更前の Java 検証器は global `none` を拒否した一方、検証器を通さずに
`ParserGenerator` を直接呼ぶと、設定の存在だけで delimiter を有効にしていた。
Rust backend は既に global `none` を無効として扱っていた。

#172 では Java でも global `none` を正式に許可し、生成器が値に従って無効化する。
従来の直接呼出しで `@whitespace: none` を書きながら自動 skip に依存していた利用者は、
`@whitespace: javaStyle` へ明示的に変更する。重複 rule/global whitespace の
最後の指定が勝つことに依存していた場合も、意図する指定一つへ整理する。

Java の独立した global `@comment` 設定は別機能で、この Rust 移植の対応対象ではない。
global `none` の変更はそのコメント機能を削除・無効化するものではない。

## 実装と検証

Rust の trivia scope は子の評価前後で設定を保存・復元し、失敗した分岐や別 grammar の
呼出し後に policy を漏らさない。既存の ParseContext transaction と共有する。
rule の型・capture・nullable・左再帰・結合性解析は trivia scope の挿入前に行い、
scope が AST の node/field を増やすことはない。

`RuleTriviaConformanceTest` は生成した Java parser/AST/mapper と生成 Rust を実行し、
受理・全入力判定・consumed/matched cursor・AST 全 field/node span を独立期待値で
照合する。Java frontend と native Rust frontend の全5生成ファイルも byte 比較する。
共通 corpus は15文法・33入力で、各文法を Java 検証器にも通す。
native 生成は PATH が空でも実行し、Java を起動しない。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RuleTriviaConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

CI は `target/rust-rule-trivia.tsv` を必須 artifact にする。Rust 無効の通常ローカル
test で skip されたことを、この適合性検証の成功には数えない。

任意 trivia parser の登録、global comment 設定、scope metadata、evaluator、
full tinyexpression-rs、rustcodeblock、LSP/DAP は別の未完了項目として追跡する。

実 tinyexpression `2a2db7c` の P4 文法を native generator へ渡すと、この変更で
`Formula` の `@interleave` を通過し、次の未対応 `@scopeTree(mode=lexical)` を
exit 3 で明示拒否する。P4 全体の生成成功や full-spec 完了を意味しない。
