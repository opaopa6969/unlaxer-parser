# Typed nested capture と `@typeof`

`@nestedCaptures` 所有 rule の `@typeof` は、Java と両 Rust 生成 host で既存の [浅い意味型比較](typeof-constraints.md) に接続する。これは従来両版で拒否していた合成を追加するもので、旧 Java のみにあった機能の移植ではない。annotation のない flat capture API は変わらない。

```ubnf
@nestedCaptures @mapping(Pair,params=[left,right])
Pair ::= [ {'a'} ] @left '=' @typeof(left) ['b'] @right;
```

上の field は `Optional<List<String>>` と `Optional<String>` だが、比較する外側の型はいずれも Optional なので一致する。`List<Optional<Node>>` と `List<Enum>` も List として一致する。container の内容、要素数、内側の形は再帰比較しない。空 Optional/List も比較する。

scalar の nested mixed 値は `NestedValue` wrapper の型で比較せず、中の text / i32 / concrete mapped node / 宣言 enum の意味型を使う。たとえば mixed 内の i32 と通常の nested Integer は一致し、同じ wrapper に入った text と i32 は不一致となる。値・順序・重複・原文 CP は変換しない。

公開 constructor field を宣言順に bind した後、比較専用の private capture を bind し、制約をソース順で検査する。forward reference を許す。private capture は AST constructor や evaluator 引数に追加しない。private のみが mixed 値を使う場合にも内部 mapper 用の `NestedValue` 型を生成する。公開 field の shared schema と private local の所有 rule schema は区別する。

nested binder は保存された capture boundary と元の CST を使い、入力を再 parse して capture を復元しない。separated の separator は opaque なため、separator が enum/node を生成しても比較対象の item に含めない。既存の mapper の graph・深さ・訪問量と capture snapshot の資源上限を継続する。不正な nested IR の leaf / container / enum / field metadata も生成前に拒否する。

型不一致は mapping failure であり、構文 choice の再試行を起こさない。位置は own capture の CP 範囲。空の外側 Optional/List は boundary 自体が存在していても所有 rule の CP 範囲へ fallback する。source map と preferred candidate snapshot は後続 parse/map 後も保持される。

`spec-corpus/nested-typeof` の手書き期待値で Java 生成 consumer と Java/native 両 host の Rust consumer を別々に compile/run し、Rust 5 file の byte 一致も照合する。memoization 2 種 × diagnostics 3 種で値・CP・manual eval・失敗を比較する。実行:

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=NestedTypeofConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

alias/helper を越えた nested shape、container 自体を choice alternative にする形、nested auto-eval、associative、token-stream の合成は親 #583 の別条件であり、本変更の完了範囲に含まない。各 capture は既存どおり一つの完全な外側 container を所有する。container 内側に別 capture を追加する形や重複 site の解放は行わない。

既存の constrained mapped sum を nested field から参照する経路は維持する。一方、sum 所有 rule 自体を nested にし、`@mapping(Family) @commonField(text) Value ::= @typeof(value) (A | B) @value;` と group 全体を capture する新形は、既存 family schema の対象外である。両 host は AST 生成前に `E-COMMONFIELD-MISMATCH` として拒否する。構文のみを生成する Java `ParserGenerator` の直接 API は AST family schema の検証を担当しない。この grouped sum 投影拡張は親 #583 の残条件とする。
