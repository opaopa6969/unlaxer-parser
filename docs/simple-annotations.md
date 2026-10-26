# Simple annotation と生成の警告 — Java / Rust

Issue: [#715](https://github.com/opaopa6969/unlaxer-parser/issues/715)

## 何を決めるか

`@name` の形で、専用の構文を持つ annotation（`@root`・`@mapping(...)` など）のどれにも
当たらないものを **Simple annotation** と呼ぶ（UBNF の catch-all `'@' IDENTIFIER`）。
3.3.0 の Java は、Simple annotation を構文木と parser IR に残し、生成では無いものとして扱っていた。
native Rust の検査・lowering は `P-ANNOTATION` で拒否していた。

この版では、3.3.0 の Java の受理を狭めずに、3 経路（Java・Java-host Rust・native Rust）を
次のように揃える。

- Simple annotation は 3 経路とも受理する。生成器はその名前を使わない。parser IR の export
  （`GrammarToParserIrExporter`）と BNF 変換は、従来どおり名前を metadata として残す。
- **生成物が注釈を除いた文法と byte 一致するのは、`@autoCommit` が解析しない文法に限る。**
  `@autoCommit` の解析は、生成に効かない Simple annotation を持つ rule も、従来どおり opaque として
  解析対象から外す（保守的な既存挙動は変えない）。そのため `@autoCommit` が解析する文法では、注釈を
  消すと COMMIT の挿入位置が変わり得る。例: `@root @mapping(Root) @autoCommit @custom R ::= 'a' X | 'b' Y;`
  は `@custom` を消すと `COMMIT(X)`・`COMMIT(Y)` が入る。注釈を付けた rule を参照する別の rule の挿入も
  変わり得る。このときの生成物は、3 経路とも同じ（`@autoCommit` の書き換えは両 host で同じ）。
- 生成に効果を持つ Simple annotation は `@nestedCaptures` と `@autoCommit` の 2 つだけ。
  それ以外のすべての Simple annotation を、注釈ごとに警告 `W-ANNOTATION-IGNORED` として報告する。
  誤記した機能 annotation が黙って無視されないようにするため。
- `--strict` / `--fail-on warning` の CLI では警告を失敗にする（下記）。

## 境界：どの書き方が Simple annotation になるか

Java の専用 annotation は `@leftAssoc`・`@mapping(` のように `@` と名前が続いた 1 語で、catch-all は
`'@' IDENTIFIER`（間に通常の trivia を許す）である。そのため次の書き方は、名前が既知でも
Simple annotation になる。native の frontend も同じに読む（`rust/unlaxer-ubnf/tests/fixtures/positive/annotation-plain-forms.ubnf`
を `RustUbnfFrontendConformanceTest` が Java と AST 比較する）。

| 書き方 | 例 | 読み方（両 frontend） |
|---|---|---|
| `@` の後に空白・改行・行コメント | `@ leftAssoc`、`@ // c` 改行 `skip` | Simple（`leftAssoc` / `skip`） |
| 引数が必要な既知名を `(` なしで書く | `@mapping`、`@doc`、`@precedence` | Simple（`mapping` / `doc` / `precedence`） |
| 引数付きの未知名 | `@custom(x)`、`@custom()` | 構文エラー |
| `@` の後に空白がある既知名に引数 | `@ mapping(X)` | 構文エラー |
| `@namePredicate`（`(` なし） | | 構文エラー（Java の catch-all はこの名前だけを除く） |
| 名前と `(` の間の空白 | `@mapping (X)`、`@whitespace (javaStyle)` | 専用 annotation のまま |

以前の native frontend は `@ leftAssoc` を `@leftAssoc` として適用し、`@mapping` を構文エラーに
していた。Java（公開済み 3.3.0 の利用経路）に合わせて Simple annotation に変えた。`@ leftAssoc`
を書いていた native の文法は、結合性が付かなくなり、`W-ANNOTATION-IGNORED` が出る。

## 警告 `W-ANNOTATION-IGNORED`

| 項目 | 値 |
|---|---|
| code | `W-ANNOTATION-IGNORED`（severity `warning`） |
| subject | 書かれた名前 |
| span | 注釈の `@` から名前の終わりまで（Unicode code point の半開区間） |
| rule | 注釈を付けた rule |
| 順序 | rule の順、同じ rule では出現順。同じ名前の重複は位置ごとに 1 件 |

message は次の 3 種類で、両 host で同じ文字列を返す。`<effect>` は通常 `has no effect`。
`@autoCommit` が解析する文法（`@autoCommit` の rule があり、文法が解析の対象内:
`AutoCommit.analyses` / `auto_commit::analyses`）では
`is not used by the generator, but it excludes rule <rule> from @autoCommit analysis` になる
（`Warning.excludesAutoCommit` / `excludes_auto_commit`）。code は同じ `W-ANNOTATION-IGNORED`。

- 名前が専用 annotation の keyword（`KEYWORDS`）: `W-ANNOTATION-IGNORED: <rule>: @<name> is a plain annotation here and <effect>; write @<name>(...) with no space after '@'`（引数の要らない keyword は `(...)` なし）
- 近い既知名がある: `W-ANNOTATION-IGNORED: <rule>: unknown annotation @<name> <effect>; did you mean @<known>?`
- それ以外: `W-ANNOTATION-IGNORED: <rule>: unknown annotation @<name> <effect>`

候補（suggestion）は、既知名（22 個の keyword、続いて `nestedCaptures`・`autoCommit`）のうち、
ASCII 小文字化した後の optimal string alignment 距離（挿入・削除・置換・隣接 2 文字の入れ替え）が
最小で、`max(1, 名前の code point 数 / 3)` 以下のもの。同距離は先に並ぶ名前を選ぶ。
例: `@LeftAssoc` → `leftAssoc`、`@presedence` → `precedence`、`@nestedCpatures` → `nestedCaptures`、
`@rooted` → `root`、`@foo` → なし（`doc` まで距離 2、上限 1）。

API:

- Java: `SimpleAnnotations.warnings(GrammarDecl)` → `Warning(rule, subject, node, suggestion, excludesAutoCommit)`
  （`node` の位置は `UBNFSourceSnapshot.spanOf`）、`SimpleAnnotations.suggestion(String)`。
- Rust: `unlaxer_generator::simple_annotations::warnings(&GrammarDecl)` →
  `Warning { rule, subject, span, suggestion, excludes_auto_commit }`、`simple_annotations::suggestion(&str)`。

どちらも `@autoCommit` の書き換え前の木（`AutoCommit.parseBeforeAutoCommit` /
`auto_commit::parse_before_auto_commit`）を渡す。書き換え後の木では `@autoCommit` との関係が分からない。
`@autoCommit` 自体は報告しない。`GrammarValidator` は書き換え後の木を見るので、この警告を返さない
（Java の flag CLI は下の生成の警告から報告する）。

## 生成の警告を CLI まで届ける

生成器が注釈・設定を使わないことを知らせる警告を 1 か所で集め、ライブラリと CLI が同じ code・位置・文字列を出す。

- Java: `GenerationWarnings.collect(String source)` → `Warning(code, grammar, rule, span, message)`
- Rust: `unlaxer_generator::warnings::collect(&str)` → `Warning { code, grammar, rule, span, message }`

対象は `W-ANNOTATION-IGNORED`、`W-AUTO-COMMIT-SKIPPED`（[autoCommit](auto-commit.md)）、
`W-COMMENT-IGNORED`（[global comment](rule-trivia.md#global-comment712)、#777）。
書き換え前の木から読み、grammar の順、その中は span の（開始、終了、code）順に並べる。
構文エラーの source は空を返す（生成がその誤りを報告する）。
`W-COMMENT-IGNORED` の `rule` は設定の subject と同じ `comment`、span は setting 全体、message は
`W-COMMENT-IGNORED: comment: @comment has no effect with @whitespace: javaStyle`。
`GrammarValidator` は #748 からこの警告を返し続ける（API と UBNF LSP の診断）が、flag CLI は収集 API の
行だけを一度報告し、validator の同じ code の行は捨てる。

| CLI | 既定 | `--strict` / `--fail-on warning` |
|---|---|---|
| native `unlaxer generate` | 生成し、stderr に警告を 1 行ずつ出す | 警告があれば何も書かずに終了コード `5` |
| Java-host `CodegenMain generate --target rust` | 同上（native と同じ stderr） | 同上 |
| Java の flag CLI（`CodegenMain --grammar ...`） | `Validation warnings:` に ` - grammar <G>: <同じ 1 行> [code: ...] [hint: ...]` を表示 | 既存の strict 失敗（終了コード `5` = `EXIT_STRICT_VALIDATION_ERROR`） |

stderr の 1 行は `warning: CP <start>..<end>: <message>`。strict で失敗するときは最後に
`strict: <件数> warning(s) fail generation` を足す。文法・構造のエラーは警告より先に報告する（終了コード `3`）。
`--fail-on` が受け付けるのは Rust 生成 CLI では `warning` だけ。Java の flag CLI は、これらの警告を
検証 issue（WARNING、category `GENERAL`）として報告し、その `message` は上の 1 行（`warning: CP <start>..<end>: ...`）
そのものにする。JSON レポートの `issues[].message` も同じ。

`unlaxer check`（移植可能性検査）は警告を含めない。Simple annotation は portable のまま
（`P-ANNOTATION` はもう出さない。[移植可能性チェック](portability-check.md)）。

## 共通 fixture と検証

- `spec-corpus/simple-annotations/corpus.json`（16 文法）: 未知名、遠い名前、誤記（大文字小文字・置換・
  入れ替え・接尾辞）、`@` の後の空白、`@` と名前の間の行コメント、`(` なしの `@mapping` / `@doc`、重複、
  `@leftAssoc` / `@precedence` / `@mapping` / `@root` との合成、全 rule、`@autoCommit`、`@autoCommit` と同じ rule への併記。各行に警告の
  rule・code・subject・span・suggestion・message・excludesAutoCommit と、生成物が一致する baseline を持つ。
  `@autoCommit` との併記では、baseline は注釈と `@autoCommit` の両方を除いた文法で、注釈だけを除いた
  `contrast` とは生成物が異なることも確かめる。span と message は
  実装を使わずに文法の文字列と上の契約から計算し、suggestion は手で決めた。
- `spec-corpus/simple-annotations/rejected.json`（4 文法）: 引数付き・空引数・`@ mapping(X)`・`@namePredicate`。
- `spec-corpus/generation-warnings/corpus.json`（8 例）: 警告なし、`W-ANNOTATION-IGNORED`、
  `W-AUTO-COMMIT-SKIPPED`、両者の位置順の混在、`@autoCommit` と同じ rule への併記、`@autoCommit` の rule
  から参照される rule への注釈、`W-COMMENT-IGNORED` 単独・3種類の混在。
  各警告に CLI の 1 行を持つ。
- `spec-corpus/generation-warnings/ordering.json`: 複数 grammar の収集順序を両ライブラリで検証する。
  Rust 生成 CLI の複数 grammar 対応は #714 の範囲で、今回も既存の拒否を保つ。
- `spec-corpus/global-comment/ignored.json`（8 例）の `generationWarnings` も同じテストで使い、
  対応済みの形は警告なし、無効・重複した設定は各1件であることを確かめる。

`SimpleAnnotationConformanceTest`（Java）は、警告に加えて、Parser・AST・Mapper・Evaluator・LSP・DAP の
Java 生成物、Java-host Rust の生成物と CLI 出力、移植可能性検査が baseline と同じであること、parser IR に
名前が残ること、Java CLI の既定・`--strict`・`--fail-on warning`（同じ 1 行を含むこと）を確かめる。
`rust/unlaxer-generator/tests/simple_annotations.rs` は native の警告・生成物・検査を同じ corpus で確かめる。
`GenerationWarningsConformanceTest`（Java）と `rust/unlaxer-generator/tests/generation_warnings.rs` は、
両ライブラリと 3 つの CLI の stderr・終了コード・書き込まないことを同じ corpus で確かめる。

## 制限

- `@autoCommit` の解析は、他の Simple annotation を持つ rule を従来どおり opaque として扱う
  （その rule 自身の `@autoCommit` は `W-AUTO-COMMIT-SKIPPED`、reason=opaque）。警告文はこれを示すが、
  実際に挿入が変わったかどうかまでは判定しない。
- Simple annotation を意図的な metadata として使う場合も警告は出る。警告を抑える指定は設けていない。
- LSP の診断には、CLI と同じ code・message・CP 範囲で警告を出す（#798）。`unlaxer check` の JSON には警告を出していない。
