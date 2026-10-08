# `@catalog` metadata の生成契約

## UBNF の書き方を学ぶ catalog/help との区別

VSIX の「UBNF: はじめの一歩 / Catalog・Help」は **文法を編集する人向け**の入門と構文一覧です。
その内容は `unlaxer-dsl/src/main/resources/ubnf-help/catalog.json` で管理し、
Java / Rust の共通テストで掲載サンプルを検証します。
以下の `@catalog` は **定義した言語の外部シンボル一覧**の metadata で、別の用途です。

## ルールの利用者向け説明 `@doc`

3.3.0-SNAPSHOT 以降、Java-hosted / native Rust の両 Rust emitter は `@doc('説明')` を
`parser.rs` の `pub const RULE_DOCS: &[(&str, &[&str])]` に保持します。
ルール宣言順、同一ルール内の annotation 順を保存し、説明のないルールは含めません。
説明はデータであり、認識・CST・AST・評価を変更しません。未記載の文法には定数を生成せず、
従来の生成物を変えません。これは任意の HTML を実行する機能ではありません。

## 外部シンボルの catalog

`@catalog(context='...')` は parser の受理、AST、mapper、evaluator を変更しない tooling 用 annotation である。
Rust backend は annotation を捨てず、生成する `parser.rs` に次の静的 metadata を宣言する。

```rust
pub struct CatalogSpec {
    pub rule: &'static str,
    pub context: &'static str,
    pub captures: &'static [&'static str],
}

pub const CATALOGS: &[CatalogSpec] = &[
    CatalogSpec {
        rule: "VariableRef",
        context: "variable",
        captures: &["name", "type"],
    },
];
```

`CATALOGS` は文法の rule 宣言順、`captures` は名前順で安定して生成する。catalog を持たない文法には型と定数を出力せず、従来の5ファイルを変更しない。annotated rule 自身に再帰的に見つかる capture が一つもない場合は生成前に拒否する。Rust backend は曖昧な複数 `@catalog` も拒否する。この追加検査は Java の一般 validator ではなく、Java frontend/native frontend の両 Rust lowering 経路に同じ契約として実装している。

## Java と Rust の現在の境界

Java の既存 LSP generator は、文法内に `@catalog` が一つでもあるかを boolean として使い、resolver、completion、hover の拡張用 API を条件生成する。現時点では annotation の `context`、rule、capture 対応を生成物へ渡さず、生成した基底 completion/hover から自動的に catalog を呼び出しもしない。tinyexpression は拡張 LSP からこの API を独自に呼ぶが、entry の context は表示に使うだけで絞り込みには使っていない。

したがって、この変更が保証するのは Rust parser module に tooling metadata を保持するところまでである。Rust の resolver、file loading、completion、hover、diagnostic と `(context, name)` による検索は未実装であり、LSP 対応時に protocol test を伴って追加する。catalog metadata は `ParseContext`、CST、scope store に runtime event を追加しない。

最新 tinyexpression P4 文法の `VariableRef` は `rule=VariableRef`、`context=variable`、`captures=[name,type]` として生成できる。これは tinyexpression-rs の catalog-aware LSP が完成したという意味ではない。
