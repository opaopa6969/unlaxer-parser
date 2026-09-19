# tinyexpression の文字列 token 契約

追跡: #168（親 #111 / #158）。tinyexpression の外部 Java class binding のうち、
次の exact FQN を Java frontend と native Rust frontend の双方から生成する。

```ubnf
token STRING = org.unlaxer.tinyexpression.parser.StringLiteralParser
```

## 字句と mapper

固定した tinyexpression 実装は `DoubleQuotedParser`、次に `SingleQuotedParser` を
試す ordered choice である。Rust では同じ順序の既存 quoted token へ lower する。
任意 Java parser の実行・自動翻訳ではない。短名 `StringLiteralParser` や別 package の
同名 class は許可しない。runtime への JVM 依存も追加しない。

引用符と escape を含む元の字句を capture し、位置は Unicode codepoint の半開区間。
backslash と次の文字を一組として扱うが、escape のデコードはしない。生の改行、NUL、
非 BMP 文字を扱い、閉じ引用符の欠損や末尾 backslash は解析失敗となる。

生成 mapper の既存契約では外側の単引用符だけを除去する。二重引用符と escape は
保持する。これは tinyexpression evaluator による文字列値の解釈とは別の層であり、
一般的な JSON/Java の文字列デコードへ置き換えてはいけない。

## 実クラスを使う比較

`TinyStringTokenConformanceTest` は、固定した tinyexpression checkout をビルドした
`target/classes` を明示的に受け取り、実 `StringLiteralParser` と生成 Java parser/AST/mapper
を使う。同名の代替 test class で比較しない。
CI の oracle は tinyexpression commit `2a2db7c4ce38234c2ec8c4ddbf7d51eed08fc4ba`。
ロードされたクラスの code source が指定したディレクトリであることも検査する。

```sh
# tinyexpression を先に、検証対象の unlaxer に対してビルドする。
mvn -B -pl unlaxer-dsl test -Dtest=TinyStringTokenConformanceTest \
  -DrustConformance=true -Dtinyexpression.classes=/absolute/path/to/tinyexpression/target/classes
```

この統合テストは外部 checkout がない通常のローカル test では skip するが、CI の
downstream job では固定 commit からビルドしたクラスを必須入力にする。
`target/rust-tiny-string.tsv` の生成を確認して artifact に保存するため、統合テストを
実行せずに通常 test が通っただけでは、この契約を検証したことにしない。

共通 corpus は7文法・39入力で prefix 受理と consumed/matched cursor、全入力受理を
比較し、受理27入力の AST 全フィールドと node span を独立期待値と照合する。
Java frontend と native Rust frontend の生成物も全5ファイル（計35ファイル）比較し、
生成 Rust をコンパイル・実行する。AST は CST の破棄後にも参照して所有権を検証する。

## 残る範囲

これは既存 binding の明示的な移行対応であり、target-neutral token ID/schema、登録 API、
未登録・重複 adapter の診断を含む #158 全体の完了ではない。
`CodeStartParser` / `CodeEndParser` の字句認識は [#170 の別契約](tiny-code-fence.md)で扱う。
UBNF annotation、tinyexpression の評価機能、
`rustcodeblock`、LSP/DAP は引き続き対応表で追跡する。
