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

global `none` の変更は global `@comment` を削除・無効化するものではない。両者の合成は
下記の [global `@comment`](#global-comment712) を参照。

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

任意 trivia parser の登録、scope metadata、evaluator、
full tinyexpression-rs、rustcodeblock、LSP/DAP は別の未完了項目として追跡する。

実 tinyexpression `2a2db7c` の P4 文法を native generator へ渡すと、この変更で
`Formula` の `@interleave` を通過し、次の未対応 `@scopeTree(mode=lexical)` を
exit 3 で明示拒否する。P4 全体の生成成功や full-spec 完了を意味しない。

## 名前付き lexical 空白定義（#393、親 #368）

UBNF v2 の宣言的 token を、同じファイルで `@whitespace: GAP` または rule の
`@whitespace(GAP)` から参照できる。`GAP` の lexical expression は空文字で成功しては
ならない。undefined、nullable、循環 lexical reference は生成前に拒否する。
名前付き定義の名前は大文字小文字を区別する。`javaStyle` / `none` は従来の固定互換
別名として予約し、これらの大小文字を無視する契約は変えない。

```ubnf
grammar Example {
  @ubnf: v2
  @whitespace: GAP
  token GAP ::= (' ' | '#');
  @root @mapping(Root, params=[value])
  Root ::= 'a' @value 'b';
}
```

既存の相対 module import の export（全 declarative token）も利用できる。
`@import layout from 'layout.ubnf'` と `@whitespace: layout.GAP`、または rule の
`@whitespace(layout.GAP)` を組み合わせる。import 先の lexical references は定義元で
閉じるため、呼出し側の同名 token で上書きされない。読み飛ばしの適用位置、rule の
優先順位、CST / AST の既存位置契約、token 内部が原子的なことは上記と同じ。

名前付きglobal空白は通常の文字入力と `@tokenStream: enabled` の全4modeで使える。
tokenStream の通常 profile は rule-local whitespace/interleave を拒否する。
[文脈付き goal](contextual-lexing.md) の profile は global 名前付き定義と rule-local
`javaStyle` / `none`・interleave を組み合わせられるが、rule-local 名前付き定義は拒否する。
token一覧に単一global定義を使い、
名前付き定義の一致は汎用 `trivia` として原文spanを保持する。
package / lock / HTTPS / 出典表示は [package仕様](ubnf-packages.md) を参照する。

共通 trivia corpus は名前付き global/rule、相対 import の global/rule、token 内部の
原子性を追加し、Java / Rust の受理・拒否、consumed/matched cursor、AST の全 field /
node span、native frontend の生成物を比較する。既存 lexical module invalid corpus は
未定義、空文字、名前の大文字小文字違いも両生成経路と portability report で検証する。

## global `@comment`（#712）

`@comment: { line: '//' }` は Java 生成器・Java-host Rust 生成器・native Rust 生成器で同じ
意味になる。単一 grammar の既存 API（`getRootParser` / `parse_context` / mapper 等）は変わらない。

| grammar 既定 | rule の既定 trivia |
|---|---|
| `@whitespace: javaStyle` と併記 | javaStyle のまま（行コメントは既に含まれ、追加効果なし） |
| `@whitespace` 省略 / `none` と併記 | 行コメントだけ: `//` から CR / LF / EOF の**直前**まで |
| 名前付き `@whitespace: GAP` と併記 | `E-COMMENT-WHITESPACE` で拒否 |
| `@tokenStream: enabled` | 従来どおり `E-TOKEN-STREAM-TRIVIA` で拒否 |

行コメントだけの既定では、行末記号・空白・block comment は trivia ではない。改行を構文 token
として書く行指向の文法で使う。コメントの内部は原子的で、引用符・`/*`・補助文字を区別しない。
Java の `CPPComment` と同じく、終端の CR / LF / CRLF は消費せずに先読み一致するので、matched
cursor と失敗診断の位置は終端の後ろへ進む。Rust も同じ cursor・診断位置を返す。

rule の優先順位は上記の 1〜3 のままで、`@comment` は 3 の grammar 既定にだけ効く。明示した
rule `@whitespace(...)`（`none` / `javaStyle` / 名前付き）と `@interleave` はそれぞれの設定を
使い、行コメントを追加しない。親の連接と子の連接はそれぞれの rule の設定に従う。
lexical context の rule でも同じ既定が使える。

受理する形は `@comment` 1 個・block 値・entry は `line: '//'` 1 個だけ。重複、文字列値、
`line` 以外の key、`line` の重複、`//` 以外の値（`'#'`、空文字、`'\/\/'` など）は
`E-COMMENT-SETTING` で生成前に拒否する。Java 検証器・Java `ParserGenerator`・Java-host
Rust 生成器・native 生成器が同じ code を返し、Java / native の portability check は
setting 全体の code point span を同じ値で報告する。

ただし全体の `@whitespace: javaStyle` と併記した場合は、これらの形も拒否しない。javaStyle は
`//` を既に読み飛ばすので、3.3.0 の Java 生成器はこれらを javaStyle 単独と同じに生成していた
（ubnfc の oracle corpus などが実際に使っている）。3.3.0 との互換を最小の変更で保つため、
生成物は javaStyle 単独と同じにしたまま、該当する setting ごとに `W-COMMENT-IGNORED`
（subject `comment`、setting 全体の code point span）を出す。Java は `GrammarValidator`
（severity WARNING。CLI は `Validation warnings:` に表示）と `GlobalComment.warnings`、
Rust は `unlaxer_generator::global_comment::warnings` が同じ code と span を返す。
portability check では portable のままで、diagnostic にも出さない（native CLI には warning を
出す経路がまだ無い）。

### Java の変更と移行

変更前の Java は (1) `line` key があれば値に関わらず `//` を読み飛ばし、`line` が無い block や
文字列値は黙って無視していた。(2) delimiter class を grammar 全体で 1 つ共有していたため、
別の rule に `@whitespace` / `@interleave` が 1 つでもあると、継承 rule の comment 既定まで
空白・block comment を読み飛ばしていた。(3) 名前付き global whitespace と併記すると
`@comment` は黙って無視されていた。

#712 では (2) を優先順位どおりに直し、継承 rule は常に行コメントだけを読み飛ばす
（生成 class `<Grammar>LineCommentLazyChain`）。(1) と (3) は書いた値と違う意味で黙って
生成していたため、位置付きで拒否する。`@whitespace: javaStyle` と `line: '//'` を併記する
既存の文法（tinyexpression P4 等）の Java 生成物は変わらない。空白も読み飛ばしたかった
場合は `@whitespace: javaStyle` を明示し、名前付き空白でコメントも扱いたい場合は
その token の定義へコメントを含める。javaStyle と併記した (1) の形は、拒否せずに
`W-COMMENT-IGNORED` を出す（上記）。

3.3.0 の生成物を手書きコードから参照している場合は、次の変化に注意する。

- javaStyle も規則ごとの `@whitespace` / `@interleave` も無い、`@comment` だけの文法では、
  `<Grammar>LazyChain` と `<Grammar>SpaceDelimitor` が生成されなくなり、
  `<Grammar>LineCommentLazyChain` と `<Grammar>LineCommentDelimitor` に置き換わる。これらの
  class を参照する手書きコードは compile できなくなる。CST に現れる parser class も変わる。
- `@comment` に規則ごとの `@whitespace` / `@interleave` を併記した文法では、継承規則が
  空白・block comment を読み飛ばさなくなるので、受理が変わることがある。たとえば
  `@whitespace R0 ::= 'a' R1 'd'; R1 ::= 'b' 'c';` に `a b c d` を与えると、3.3.0 は受理し、
  #712 では位置 2 の syntax error になる。受理が変わらない入力でも、子規則の node span が
  変わることがある（例: `R1[2,6]` → `R1[1,6]`）。AST の source span も同じく変わる。

### 検証

`GlobalCommentConformanceTest` は [共通 corpus](../spec-corpus/global-comment/README.md)
（8 文法・54 入力）を Java と Java-host / native の Rust 生成物それぞれで compile・実行し、
受理、prefix の consumed / matched cursor（memo 有無）、診断種別と位置、AST の全 field と
node span を独立期待値と照合する。明示 Classic 入口（Java の原文・AST/source map、Rust の
token mode の cursor と CST 原文）も比較する。両 Rust 生成物は byte 一致も確認するが、実行を
省略しない。
無効設定 11 件は Java 検証器・両生成器・両 portability check で code / subject / span を比較する。
javaStyle と併記した 8 件（`ignored.json`）は、`W-COMMENT-IGNORED` の code / subject / span を
Java と native で比較し、Java・Java-host Rust・native の生成物が javaStyle 単独と一致すること、
portability check が portable のままであることを確認する。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=GlobalCommentConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

CI は `target/rust-global-comment.tsv` と `target/rust-global-comment-invalid.tsv` を必須 artifact にする。
