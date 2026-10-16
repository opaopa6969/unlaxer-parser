# Typed nested mixed values

`@nestedCaptures` の直接 choice を scalar 葉として扱う場合、text / i32 / mapped AST / enum を混在できる（#653、親 #583）。Optional と List の境界、空 List、欠損 Optional、宣言順、重複する値を保つ。例えば次の field は Java で `List<ExampleAST.NestedValue>`、Rust で `Vec<NestedValue>` になる。

```ubnf
grammar Example {
  @package: example @whitespace: none
  token N = NumberParser
  @root @nestedCaptures @mapping(Root,params=[values])
  Root ::= '[' { (Mode | Leaf | N | 'text') } @values ']';
  @enum Mode ::= 'sync' | 'auto';
  @mapping(Leaf,params=[text]) Leaf ::= '<' '😀' @text '>';
}
```

同種の text / i32 choice は従来の scalar 型を維持する。複数の alternative が mapped node または enum を含む場合は、同じ nominal 型の choice でも次の sum を使う。

| 意味 | Java `ExampleAST.NestedValue` | Rust `NestedValue` |
|---|---|---|
| 原文 text | `Text(String value)` | `Text { text: String, span: Span }` |
| i32 | `Integer(int value)` | `Integer { value: i32, span: Span }` |
| mapped node | `Node(ExampleAST value)` | `Node(Box<Ast>)` |
| enum `Mode` | `EnumMode(ExampleAST.Mode value)` | `EnumMode { value: Mode, span: Span }` |

Java は sealed interface と record、Rust は enum を生成する。grammar の各 enum に専用 variant がある。数値は既存の signed i32 契約であり、overflow と非整数表記は mapping 時に失敗する。text を正規化・trim しない。型の区別は CST の capture marker と元 rule に保持し、mapper は入力を再解析せずに読み出す。

この sum は nested 専用である。旧 flat mapping の Java `Object` / enum wrapper と Rust `AstValue` は変更しない。手書き Rust の既存 `AstValue` exhaustive match に variant を追加しない。生成型が必要な grammar では mapped name `NestedValue` / `NestedValue.*` と enum name `NestedValue` を生成前に拒否する。文法名・unmapped helper 名 `NestedValue` は別 namespace なので使用できる。

Java の保持可能な `SourceMappedAst` は各 wrapper の identity に元の CP span を保存する。Rust の scalar variant は `Span` を所有し、Node は元 AST の span を使う。補助文字・CRLF・EOF の zero length occurrence も CP 基準を維持する。新しい parse / map や元 CST の破棄後も、保持した値と span を使える。

manual evaluator の引数、shared mapping、commonField getter は実際の再帰型を使う。同じ mixed 葉・同じ container 階層は共有可能で、異なる container 階層は共有 mapping として拒否する。API schema / impact は field の再帰型、sum の variant と payload 型、生成位置・source rule origin を報告する。preferred snapshot の default candidate も通常 mapper と同じ値を返す。

DAP の AST step traversal は Optional / List 内の mapped node を宣言順に巡回する。これは mixed Node variant と、既存の uniform `List<Option<Node>>` の両方に適用する。text / i32 / enum は AST step にしない。

資源・不正入力の扱いは既存 nested snapshot と公開 mapper の上限を引き継ぐ。capture snapshot は最大 2 MiB の保持 text、65,536 要素、深さ 256 の制限を持つ（総作業メモリの上限という意味ではない）。公開 CST の cycle 等は mapper が明示失敗し、失敗後の正常入力の parse / map は使用可能。構文失敗は通常の rollback を保持する。

## 今回の範囲と残差

今回対応するのは、直接 choice の各 alternative が scalar で、その外側に Optional / List がある形。container を choice alternative に置く形、mapped / mixed 葉を含む多要素 group は早期拒否を維持する。unmapped alias / recursive helper を介した shape、typeof、auto-eval、associative projection、token-stream の組合せは親 #583 の残条件であり、この変更の完了根拠に含めない。separated list は #646 の境界を利用し、mixed item の Optional/List、nullable item、EOF zero leaf を共通 consumer で確認する。separator に mapped AST があっても、その値と DAP step は除外する。

`spec-corpus/nested-mixed-values/` の独立期待値を `NestedMixedValueConformanceTest` が検証する。Java 生成コードと、Java host / native host がそれぞれ出した Rust コードを実 compile / run し、6 種の解析 mode、値・型・CP・manual eval・DAP・preferred・保持 snapshot・失敗後 reuse を比較する。Rust 5 基本ファイルと DAP ファイルの byte 一致に加え、両出力を個別に実行する。予約名・不正 shape の source CP 診断、API schema / impact の両 host 一致、旧 flat / uniform nested の回帰も確認する。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=NestedMixedValueConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```
