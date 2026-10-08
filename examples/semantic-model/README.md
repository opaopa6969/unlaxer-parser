# UBNF から型モデルと引数補完を使う

[model.ubnf](../../spec-corpus/semantic-model/model.ubnf) は、名前付き型・field・変数・関数 signature・呼出しを定義する小さな例である。Java や TinyExpression の完全な構文ではない。`?` を明示的な編集用の穴として扱う。未閉鎖の任意構文から partial AST を復元する機能は別 issue #373。

[example.model](example.model) の一部:

```text
interface IAccessor {}
interface IChild : IAccessor {}
record DbAccessor : IChild { path: String; }
record Context { accessor: IAccessor; }
record Request { context: Context; next: Request; }
let ctx: Context;
let db: DbAccessor;
let cached: IAccessor;
fn process(Context, IAccessor): Result;
call process(ctx, ?);
```

`String` / `Result` を含む全宣言は example.model にある。生成した AST を [Java adapter](TypedModelExample.java) / [Rust adapter](adapter.rs) で読んで共通の SemanticModel へ登録する。parser の host class ではなく、利用者がソースに書いた型宣言が判定に使われる。field は型の組合せを表す。値の構築・関数実行はこの例では行わない。

第2引数の候補は `cached: IAccessor` と `db: DbAccessor`。`let db: String;` に変更すれば `cached` だけになる。関数の第1引数を `String` に変えると、`ctx: Context` と一致する signature がなくなるため、この呼出しの候補は空になる。`Object` / `String` の変数は第2引数に適合しない。

repo root で実行する（Java 21、Maven、Rust 1.85 以上、Cargo が必要）:

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=SemanticModelConformanceTest,SemanticModelUbnfTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

テストは両言語の parser / AST / mapper を実際に生成・コンパイルし、native Rust frontend の5生成ファイルも比較する。adapter は生成ソースと合わせてコンパイルする例なので、生成されていない `TypedModelAST` 等を repository へ手書きで追加しない。

`unlaxer-dsl/target/rust-semantic-ubnf.tsv` に3つの入力の候補・型・元ソース span が出る。`rust-semantic-model.tsv` には scope・unknown・エラーを含む共有 corpus の結果が出る。

Java は `completeArgument`、Rust は `complete_argument` に call ID・引数 index・code point cursor・文書 version・prefix を渡す。言語固有のアクセス制御、複雑な overload / generics、LSP edit の組立ては adapter 側の追加機能である。詳細は[仕様と対応表](../../docs/semantic-model.md)を参照。
