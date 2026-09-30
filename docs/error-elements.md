# UBNF `ERROR(...)` — 失敗時の期待候補

Issue: [#329](https://github.com/opaopa6969/unlaxer-parser/issues/329)。
ClassicのJava生成parserと、Java/native Rust両hostから生成するRust parserの契約。

## 構文と役割

```ubnf
grammar Expected {
  @root @mapping(Result)
  Root ::= 'ok' | ERROR('expected ok') ;
}
```

`ERROR('message')`は、入力を消費せず必ず失敗する要素である。失敗診断のexpected候補に
指定したメッセージを加える。上の文法では通常のordered choiceが先に`'ok'`を試し、
その枝が失敗した場合にERRORへ進む。ERRORは例外を投げて選択全体を中断するcutではなく、
後続の選択肢を試すことを妨げない。

- 成功するerrorノード、回復処理、入力読み飛ばしを作る機能ではない。
- `@recovery`はこの変更の対象外であり、Rust targetでは引き続き未対応として拒否する。
- メッセージは通常のUBNF文字列として読む。コード・format式として評価しない。
- `ERROR`という名前自体を予約しない。`ERROR ::= 'x'; Root ::= ERROR;`のような
  通常のrule名・参照は既存の意味を維持する。

## 生成経路

| 経路 | 生成する表現 |
|---|---|
| Java target | `ErrorMessageParser.expected(message)` |
| Java host → Rust target | `GrammarIR.ErrorExpected` → `Expr::Error(message)` |
| native Rust host → Rust target | `Expression::ErrorExpected` → `Expr::Error(message)` |

ERRORを含む文法でも、AST/mapper/evaluatorの既存契約を維持する。ERROR自体は成功しないので
値を生成せず、optionalや失敗したchoiceのcaptureを利用者へ漏らさない。通常の構造検証は
省略しない。たとえばoptionalなERRORを無限反復すれば、反復bodyが空入力で成功できるので
既存の非消費反復検査の対象になる。

## 診断・状態の契約

診断位置は入力のUnicode code pointによる。メッセージの長さを消費位置として扱わない。
比較するのは公開の失敗分類・位置・明示したexpectedメッセージと、構文解析が利用者へ残す状態である。
Javaの詳細なtrial履歴やparser stackをRustと同じ形にする変更ではない。

空文字またはJavaの`String.isBlank()`に相当する空白だけのメッセージは、失敗そのものを
取り消さず、空のexpected候補を追加しない。NBSPのようにJavaがblankと扱わない文字まで
Rustの`char::is_whitespace()`でまとめて除外しない。非blankメッセージはtrimしない。
ただし、Javaの上位診断には明示hintがないparserのclass名を補う既存fallbackがある。
たとえばblank ERRORの失敗では`ErrorMessageParser`等がexpectedに現れ、Rustの空集合とは
一致しない。これは生成mapperが既に区別しているbackend固有のnative hintであり、
Java側を削ったりRustへJava class名を合成したりしない。通常のliteral候補にもJavaの`'a'`と
Rustの`a`という既存の表示差があり、Javaのtrivia parserは空白・コメントの候補も記録する。
Rustのtrivia走査はそれらを同じ形では記録しない。共通corpusではこれらの差も個別の期待値として検証し、
正規化で見えなくしない。診断の完全一致を保証する機能ではない。
余剰入力を検出した場合も、主診断の`end of input`と位置は比較する一方、最遠候補の収集経路は
異なる。たとえば成功prefixに余剰文字が続くケースで、Javaの`farthestExpected`は空、
Rustは`end of input`を記録する。この既存差もcorpusへそのまま残す。
この修正は既存のRust `Expr::Error`にも適用される。従来の空/blank文字列そのものを
expected集合で探していた利用者は、その候補がなくなる点に注意する。
低水準の`ParseContext::error(message)`の契約は変更しない。

入力側のtrivia処理は既存の文法・rule設定に従う。失敗した分岐の消費cursor、照合cursor、
capture、scope、利用者状態は既存のtransaction契約で復元する。診断履歴はそれらの状態とは
区別し、最遠失敗の情報を保つ。ERRORを追加したことでmemoや診断policyを暗黙に切り替えない。

## 検証

共通の独立期待値付き[corpus](../spec-corpus/error-elements/corpus.json)（12文法19入力）を、
Java生成parserと両hostのRust生成物に適用する。両hostの5つのRust生成fileも比較し、
実javac/rustcで生成物をコンパイルして実行する。成功する5入力ではASTの全fieldとspanを比較し、
全入力では診断3policy × memo2modeの受理・cursor・診断・scope状態を検証する。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ErrorElementConformanceTest,RustPortabilityConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml
```

CIは`unlaxer-dsl/target/rust-error-elements.tsv`の出力を必須とし、conformance artifactへ保存する。
release版native generatorでも、JVMへ到達できない環境で
[`expected.ubnf`](../spec-corpus/error-elements/expected.ubnf)の移植可能性検査と全5file生成を行う。
opt-inのない`mvn test`でこの比較がskipされても、Java/Rust同値を確認した根拠にはしない。
`portable: true`は生成targetの構造検査を通ったという意味で、文法が任意入力に成功する保証ではない。
単独のERRORをrootに持つ文法も、`@mapping(Result)`など既存のAST境界条件を満たせば
生成できるが、入力の解析には必ず失敗する。任意のunmapped rootを新たに許可する変更ではない。

## 互換性と残る範囲

Javaの既存ERROR動作を変更するための機能ではない。Rustの旧移植可能性診断
`P-ERROR-ELEMENT`は発行しなくなるが、このcodeを別の意味には転用しない。
汎用consume/invert伝播、error CSTを伴う回復、増分解析、Rust LSP/DAPは別の未完了項目として
[FULL-SPEC](../rust/FULL-SPEC.md)で追跡する。
