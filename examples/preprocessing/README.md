# include・条件分岐・前方参照を段階的に解析する例

同じ UBNF と意味規則から生成した Java / Rust parser を使い、複数文書の展開結果を解析する。
元文書の Unicode 位置を保持し、展開先の型エラーと補完位置を元のファイルへ戻す。

この例は `#include` や C/C++ の preprocessor 構文を追加するものではない。
入力の組合せは明示した phase adapter で定義する。実言語の directive を解析する adapter は、
その文法の AST/capture から同じ snapshot と source map を作る。
任意の macro、compiler、外部コード、network、ファイルの自動読込みは行わない。

## 1. 文法と意味を定義する

[TypedModel の UBNF](../../spec-corpus/semantic-rules/model.ubnf)は型、field、値、関数、
呼出し、block を定義する。[意味規則 JSON](../../spec-corpus/semantic-rules/rules.json)が
同じ capture から型と参照を構築する。設定方法は[宣言的意味規則](../../docs/declarative-semantics.md)を参照。

例えば include 文書に次の型を置く。

```text
builtin Int{}
record A{next:B;}
record B{prev:A;}
```

`A` から後方の `B` を参照し、`B` から `A` へ戻る。すべての型宣言を収集してから
field の型を解決するため、この相互参照は有効になる。interface の継承循環は別の条件であり、
合法なデータの相互参照と混同しない。

main 文書には次を書く。

```text
let value:A;
call use(?);
fn use(A):Int;
```

後ろにある `use` 宣言から引数の期待型 `A` を得て、`value` を補完する。
この例の signature は lexical scope 全体から候補を集め、値宣言の可視開始は宣言末尾にする。
関数の本体評価や無制限の型推論・固定点反復を実装した例ではない。

## 2. phase と条件を登録する

[Java adapter](PreprocessingExample.java)と[Rust adapter](pipeline.rs)は同じ4段階を登録する。

| phase | 入力・依存 | 出力 |
|---|---|---|
| `include` | `included` snapshot | 主 include の原文と origin |
| `optional` | `extra == "true"` のときだけ `optional` snapshot | 追加 include、または `INACTIVE` |
| `expand` | include 2種と main、出力世代 `generation` | 展開文書と source-map segment |
| `semantic` | expand と main 内の `cursor` | 元文書位置の診断・期待型・補完 |

宣言順は `semantic` が先で、依存を辿って評価する。`optional` の条件は実行器より前に判定する。

```java
new AnalysisPipeline(definitions, executors,
    Map.of("optional", new AnalysisPipeline.Condition("extra", "true")));
```

```rust
AnalysisPipeline::with_conditions(definitions, executors,
    HashMap::from([("optional".into(), Condition {
        key: "extra".into(), expected: "true".into(),
    })]))?;
```

条件が false または欠けている場合は、追加文書が無くても `INACTIVE` になる。
条件を true にして文書が無ければ `DEFERRED`。古い成功結果で代用しない。
有効な追加文書が不正な構文なら `FAILED` となり、無効な分岐と区別する。
条件は単純な文字列の完全一致であり、任意の実行コードを評価しない。

## 3. source map を作る

expand は生成コメント、include 本文、任意の追加 include、生成改行、main 本文を連結する。
コピーした各部分はそれぞれの URI/version/CP 範囲を持つ `COPY` segment、生成部分は
main の呼出し位置を anchor とする `GENERATED` segment になる。include 元と呼出し元は
別の origin として保存する。この例の呼出し anchor は main 冒頭の6 CP以内（fixture のコメント）を
明示指定した位置で、実 macro 構文から自動抽出したものではない。

段階間の `Artifact.payload` はこの例専用の JSON で text/segment/世代を運び、
`Artifact.origins` は immutable な元 snapshot を運ぶ。UBNF の新たな artifact schema ではない。
次の phase は payload から `SegmentSourceMap` / `SourceMap` を復元し、既存の検証を通す。
実運用で外部から payload を受け取る場合は、その adapter の schema と資源上限を検証する。

例えば include 内の `next:Missing;` の診断は、展開先の offset をそのまま表示せず、
include の元ファイルの該当 CP 範囲へ戻る。補完の挿入は main の範囲へ戻す。
`😀` は1 CP、2 UTF-16 code unitである。生成したコメントの編集は逆変換不能として拒否する。

`generation` は呼出し側の出力 snapshot 世代で、入力や条件を変えるときに更新する。
自動増分ではない。同じ URI/version で異なる出力 text を公開しないよう呼出し側が管理する。
`cursor` は main 内の CP 位置であり、負値・範囲外・桁溢れは展開先の offset 加算前に拒否する。
pipeline の cache key には入力 snapshot の URI/version/text、設定、依存 artifact revision が入る。
カーソルだけの変更なら semantic のみを再評価し、include の変更は expand と semantic に伝播する。

## 4. 共通 fixture で実行する

JDK 21、Maven、Rust 1.85.0 以降の `rustc` / `cargo` を用意し、repository root で実行する。

```sh
RUSTUP_TOOLCHAIN=1.85.0 CARGO_INCREMENTAL=0 \
  mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=AnalysisPipelineTest,PreprocessingConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

テストは実際に両言語へ parser を生成し、Java backend と native Rust generator の出力を照合して
コンパイルする。その parser と同じ意味 JSON を両 adapter で実行する。
[22ケース](cases.json)の独立 expected と Java/Rust の全結果を比較し、
`unlaxer-dsl/target/rust-preprocessing.tsv` に証拠を保存する。
`-DrustConformance=true` を外した実行では共通検証が skip されるため、成功の根拠にしない。

fixture には前方型・相互 field・前方 callable、include 更新、無効分岐、遅延入力、構文エラー、
未閉鎖呼出し、修復、2文書の Unicode 診断位置、打切りと復帰を含む。
phase の循環、実行許可、provider 不在、条件の切替えと cache は
[別の共通10ケース](../../docs/fixtures/pipeline/conditions.tsv)と両言語の runtime test で確認する。

## 外部処理系との境界

本例の4 phase は source のコピーと parser 呼出しだけを行う。
利用者コードを実行する phase は `executesUserCode` / `executes_user_code` を宣言し、
評価時の `allowUserCode` も true にする。片方だけでは呼び出せず、過去の成功 cache も返さない。
実行器を登録しなければ `UNSUPPORTED` であり、文法 package の import と同一扱いしない。

[外部言語 provider](../../docs/external-language-providers.md)は別の明示登録として接続できる。
それぞれの compiler/version/capability、timeout、snapshot を設定へ含め、契約が変わったら
pipeline を作り直すか cache を消す。この phase 実行許可は process sandbox ではない。
