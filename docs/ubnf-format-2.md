# UBNF format 2 — 外部 token と context accessor

format 2 の目的は、`token NAME = SomeParser` のような host 実装参照を文法の意味そのものと
混同しないことです。通常の token は UBNF の組込み式で受理範囲を記述します。BNF だけでは表せない
token は `ADAPTER` と `@tokenAdapter` で結び、受理条件・失敗時の cursor 契約・読む context を
UBNF ソース内に残します。

```ubnf
grammar Example {
  @ubnf: v2
  @tokenAdapter: {
    id: 'example.identifier'
    version: '1'
    java: 'example.IdentifierParser'
    rust: 'crate::identifier'
    accepts: 'ASCII letter or _ followed by ASCII letters, digits, or _'
    failure: 'no-consume'
    consumes: 'always'
    context: 'remaining,position'
  }
  token IDENTIFIER = ADAPTER('example.identifier', version=1)
}
```

`accepts` は実装クラス名ではなく、入力に対する認識契約です。`failure` は adapter 自身が失敗した
場合の consumed cursor の契約であり、外側の PEG choice / transaction が行う rollback の説明では
ありません。`consumes` は成功時の consumed cursor の契約で、`always`、`maybe`、`never` のいずれかです。
`context` は実装が参照する共通 accessor を明示します。

## 安定 accessor

format 2 の `contextAccessorsV1` は `source`、`remaining`、`position`、`matchedPosition` を公開します。
追加能力 `parseBindingsV1` は、解析開始時に固定した外部データを読む `bindings` を公開します。
[仕様・Java / Rust API・実例](parse-composition.md)を参照してください。
すべて read-only で、offset は Unicode code point です。Java/Rust の実装詳細、capture と scope の
可変 API、listener、diagnostic、memoization、任意 user state は意図的に対象外です。これにより
speculative parse と rollback の観測可能な意味を host ごとに変えません。

将来 accessor を追加するときは、両 runtime に同じ名前・型・offset 単位・EOF 時の挙動を実装し、
Java/Rust 共通入力で適合テストを追加します。既存 accessor の意味を変更する場合は format version を
上げます。読み取り専用の追加能力は、既存 feature の契約を変えず、新しい feature 名で識別します。

## Feature 宣言

format 2 の機能は、必要なら明示的に宣言できます。未宣言でも v2 の標準機能として利用できますが、
宣言すると generator や LSP が要求する互換性を静的に検査できます。

```ubnf
@ubnf: v2
@feature: tokenContractsV1
@feature: contextAccessorsV1
@feature: parseBindingsV1
@feature: tokenProgressContractsV1
```

未知の feature、重複、v1 grammar での feature 宣言はエラーです。feature 名は後方互換のため固定であり、
format version を更新せずに既存 feature の意味を変更しません。
