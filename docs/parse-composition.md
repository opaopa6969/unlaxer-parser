# UBNF の最小拡張とホスト処理の合成

文字種ごとの専用モデルを runtime に持ち込まず、固定の認識は UBNF、外部データは
解析ごとに固定した bindings、認識後の値変換や判断はホスト処理で組み合わせる。
追加する共通機能は **読み取り専用 parse bindings** 一つ。
Java / Rust の `3.3.0-SNAPSHOT` で利用できる。

## 既存の字句式で表す

[character-kinds.ubnf](../examples/parse-composition/character-kinds.ubnf) はそのまま生成できる。

```ubnf
token KANA ::= CHAR_RANGE('ァ', 'ヶ') | CHAR_RANGE('ｦ', 'ﾝ') | 'ﾞ' | 'ﾟ';
token LONG_MARK ::= 'ー' | 'ｰ';
token KATAKANA ::= KANA (KANA | LONG_MARK)*;
token ALPHABET ::= (CHAR_RANGE('A', 'Z') | CHAR_RANGE('a', 'z'))+;
token HYPHEN ::= '-' | '‐' | '－';
```

異なる token に分ければ `カタカナABCカナ` は `カタカナ / ABC / カナ` になる。
`コーポA` の長音は KATAKANA に含み、`８ー３` の長音は NUMBER 間の別 token にする。
長音を常に hyphen とする必要はない。未知文字の受け皿は
`(NEGATIVE_LOOKAHEAD(KNOWN) ANY)+` とする。token 化モードで `ANY+` を使うと、
既知文字まで含む最長 token になってしまう。

例の範囲・hyphen 集合は小さなサンプルであり、Unicode Script / General Category の完全な
定義でも、onigiri-parser の全辞書の複製でもない。必要な集合を token-only module に置き、
既存の `@import chars from 'chars.ubnf'` で共有できる。ネットワーク取得は #368。

選択順も新構文を必要としない。字句式の `|` は先勝ちで、token 化モードは token 間の最長一致。
この違いを固定優先順位で揃えるには、後続候補を先行候補の否定先読みでガードする。
[first-match.ubnf](../examples/parse-composition/first-match.ubnf) は `ab` を `a / b` に分ける。

```ubnf
token FIRST ::= 'a';
token SECOND ::= NEGATIVE_LOOKAHEAD(FIRST) ('ab' | 'b');
```

## 新しい共通 API: immutable parse bindings

bindings は `string → ordered list<string>`。辞書、地域 ID、正規化テーブル、複数の tag などを
ホストが解析開始前に用意する。型付き任意オブジェクト、DB connection、callback は含まない。
`words.default` などの key は例の provider が決めた規約で、UBNF の予約語ではない。

| 契約 | Java | Rust |
|---|---|---|
| context の生成 | `ParseContext.withBindings(source, map, options, effectors...)` | `ParseContext::with_bindings(source, map, options)` |
| key の参照 | `bindingValues(name): List<String>` | `binding_values(name): &[String]` |
| 入力コンテナ | `Map<String, ? extends List<String>>` | `BTreeMap<String, Vec<String>>` |
| 所有権 | map / list を deep copy | 所有権を受け取る |

- key は完全一致。未登録 key と空リストはいずれも空リストを返す。
- 値の順序、重複、空文字を保持する。key の列挙 API は設けない。
- Java の null key / list / 要素は拒否する。Rust の型には null を含められない。
- 作成後の更新 API はない。Java は effectors / `onOpen` より前に snapshot を作る。
- 既存の context 生成 API は空の bindings を使う。既存の解析結果は変わらない。
- source、cursor、capture、scope と独立している。choice / lookahead / transaction は
  bindings を変更しないので、rollback 用コピーを増やさない。
- 同じ parser を別の snapshot で呼べる。更新した DB は次の context に渡す。
  cache は従来どおり context ごと。custom adapter の memo 非対応は維持する。
- 通常の文字列と同じく、移植可能なデータには有効な Unicode scalar を使う。

```java
var bindings = Map.of("region", List.of("north"),
    "words.north", List.of("東京", "𠮷"));
try (var context = ParseContext.withBindings(
        StringSource.createRootSource(input), bindings, ParseOptions.DEFAULT)) {
    var result = DictionaryParsers.getRootParser().parse(context);
    // generated mapper の mapParsedTokenWithSourceMap に成功 token を渡せる。
}
```

```rust
let bindings = BTreeMap::from([
    ("region".into(), vec!["north".into()]),
    ("words.north".into(), vec!["東京".into(), "𠮷".into()]),
]);
let mut context = ParseContext::with_bindings(input, bindings, ParseOptions::default());
let result = generated::parser::parse_context(&mut context);
// 成功時は context.tree(result.root_node()) から generated mapper へ渡す。
```

これらは低水準 entry point。全入力の受理が必要なら例のように grammar に `EOF` を付ける。
自動診断 retry を行うホストは、同じ snapshot で新しい context を作る。

## 既存 ADAPTER への接続

[dictionary.ubnf](../examples/parse-composition/dictionary.ubnf) は `@feature: parseBindingsV1` を
宣言し、既存 `@tokenAdapter` の `context` に `bindings` を追加する。
format 2 の既存文法や `contextAccessorsV1` の四つの accessor の意味は変えない。
`parseBindingsV1` は追加能力の識別子。旧 generator は未知の feature / accessor として拒否する。

[Java provider](../examples/parse-composition/java/DictionaryParser.java) と
[Rust provider](../examples/parse-composition/rust/dictionary.rs) は、`region` が指す辞書の
先頭から最初に prefix 一致する非空文字列を採用する。空のエントリは読み飛ばし、失敗時は
消費しない。`['a','ab']` は `ab` の `a` だけを消費するため、後続 EOF は失敗する。
最長一致が必要な provider は、ホスト側で明示的にその契約を選ぶ。

任意 adapter を純粋と推定しない。無制限反復では既存の nullability 検査に従い、例では
`{'[' WORD ']'}` のように必ず進む要素で囲む。`@tokenStream: enabled` は custom adapter を
引き続き拒否する。動的辞書のこの例は通常の直接解析を使う。固定字句の例は4モードで検証する。
生成時に provider や DB を実行することはない。ネットワーク取得、認証、更新時刻の決定はホストの責務。

## onigiri-parser 調査で挙がった振る舞いとの対応

| 振る舞い | 表現と責務 |
|---|---|
| 文字種の変化、hyphen、文脈に応じた長音 | range / literal / repeat / lookahead と token の組合せ |
| 複数文字の分類語、登録順で最初の候補を採る | ordered choice、否定先読みのガード、または上記辞書 adapter |
| 実行時辞書・地域別の分類 | 解析前に取得した bindings を adapter が読む |
| 一つの範囲へ複数の分類を付ける | CST の raw span を key にしたホストの属性テーブル。例は `tags.<raw>` |
| 半角全角・異体字などの正規化 | raw token の認識後に値を投影。例は `normalized.<raw>` を引く |
| 後方検索、prefix / match / suffix の抽出 | 固定パターンは前向き文法へ変換できる。任意検索は既存 source accessor を読むホスト関数で CP span を返す |
| 地域・階数・部屋番号など複数値の意味判断 | AST / CST と bindings を受け取るホストの検証関数。汎用の宣言的意味規則は #381 |
| 複数 parser の結果から採用する | 同じ原文と snapshot で別 context を生成し、結果をホストが順番に選ぶ。失敗した context は再利用しない |

例えば `😀:ｺｰﾎﾟ` の raw span `[2,6)`、raw `ｺｰﾎﾟ`、正規化値 `コーポ`、tags
`['katakana','building']` を併存させる。認識後の投影なら原文の位置を変更する必要がない。
この投影は例のテストでも実行する。Unicode 正規化アルゴリズム自体は含まない。
NFKC / 辞書置換を認識前に行い、その正規化文字列の内部位置を原文へ戻す場合は、別途 source map
が必要になる。この API で任意の正規化入力の位置変換を解決済みとはしない（#380 / #369）。

また、実行中の lexer mode 切替 (#377)、自動的な意味規則生成 (#381)、外部 parser の投票規約は
追加していない。上表のホスト処理まで全てを単独の UBNF ファイルで宣言する機能ではない。
必要な領域まで個別に拡張できるよう、core の追加を snapshot の受け渡しに限定した。

## 検証

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ParseContextBindingsTest,ParseBindingsConformanceTest,TokenStreamConformanceTest,RustUbnfFrontendConformanceTest,RustPortabilityConformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DrustConformance=true
cargo test --locked --manifest-path rust/Cargo.toml
```

`spec-corpus/parse-bindings/runtime.json` は18例の独立した期待値を持つ。
同じ UBNF を Java と native Rust の両 generator で処理し、Rust 生成物の一致も検査する。
受理、consumed / matched、negative lookahead、choice の rollback、原文 capture、AST と span、
長さの変わる値投影、複数属性、snapshot を作った後の元コンテナ変更を照合する。

固定字句は `spec-corpus/token-stream/corpus.json` の文字種11例と優先順位1例を、4モード ×
trivia 公開有無で照合する。adapter 契約の受理・重複・未知 accessor の拒否と診断位置は
`spec-corpus/token-adapters/cases.tsv` に含める。CI は `rust-parse-bindings.tsv` と
`rust-token-stream.tsv` の証跡を必須にする。Rust 比較は `-DrustConformance=true` が必要。
