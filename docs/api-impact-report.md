# 文法進化と生成 AST / 評価 API の影響レポート

追跡: #159、親 #111。対象は生成 AST と評価 API の source-level schema である。
解析意味論、全 public API、binary/ABI 互換性、外部 provider の互換性を保証するものではない。
`ok: true` は schema 比較成功であり、生成物や利用者コードがコンパイルできる保証でもない。
型の参照解決・外部依存・型パラメータの整合性は、通常の build で検証する。

## CLI と境界

`impact --target java|rust --before old.ubnf --after new.ubnf [--format json]`。
Java host は Java / Rust target、native Rust host は既存生成器と同じ Rust target を扱う。
native host に Java target の生成能力があるとは主張せず、指定時は引数エラーにする。
レポートは stdout の JSON 一個。成果物や手書きコードを保存・上書きしない。
内部では現在の生成器でコードをメモリ上に生成し、実際の型 / 宣言位置を確認する。
import 展開、外部 parser class の探索・初期化、host compiler の起動やコード実行は行わない。
Java schema の構文読取りには JDK の compiler tree API を使う（parse のみ、analyze / compile はしない）。
Java の生成位置は Java 17 profile（`EvaluatorGenerator(17)`）の仮想生成物を指す。
Java 21 の switch 版 evaluator は同じ protected API を持つが、本文・行番号は異なる。
Java host の実行には JDK が必要で、compiler module を除いた runtime image は対象外である。

終了コードは 0（変更の有無によらず比較成功）、2（引数）、3（schema 作成不能）、4（I/O）。
成功時は stderr 空。schema 作成不能は JSON の side / code / message に記録し、差分を判定しない。
message は host 固有であり同値契約に含めない。

## JSON schemaVersion 1

Report:

```text
schemaVersion: 1, scope: "ast-semantics", target: "java" | "rust",
ok: boolean, hasChanges: boolean,
before: Snapshot | null, after: Snapshot | null,
changes: Change[], diagnostics: Diagnostic[]
```

Snapshot は `grammar`, `astType`, `evaluatorType`, `nodes`, `methods`。
node / method は name 順。field / parameter は実際の宣言順を保持する。

```text
Node: name, kind, fields, parents, variants, origins, generated
Field: name, type, cardinality, generated
Method: name, returnType, parameters, required, origins, generated
Parameter: name, type
Origin: rule, span
Location: path, span, line, column
Span: start, end
Diagnostic: side (before|after), code (I-SCHEMA), message
Change: kind, subject, before, after,
        beforeOrigins, afterOrigins, beforeGenerated, afterGenerated
```

Span は Unicode code-point 半開区間。origin は contributing rule 全体（annotation を含む）、
generated は保存していない生成ファイル内の宣言位置であり、実ファイルの存在は要求しない。
line / column は1始まり。path は生成先からの相対パス。原文の LF / CRLF は保持して数える。
共有 mapping の origins は全 contributing rule を rule 名順に列挙する。

kind は Java の record / interface / enum、Rust の variant。
cardinality は外側 container の one / optional / many（入れ子の詳細は type に保持）。
Rust の implicit span は field に含めず、共通 AST フィールドのみ比較する。
Java の AST root 自体は astType で、record / sum / nested 型は nodes で表す。
Java の protected 評価メソッド（abstract / concrete / helper）、Rust Semantics の required methods を扱う。
DebugStrategy の nested API、AST の便利メソッド、parser / mapper / LSP / DAP API は対象外。

## 差分の規則

Change.before / after は比較値の文字列（追加・削除側は null）。origins / generated は両側の位置。
rule 名一覧の変更以外では、位置のずれやコメントだけの変更を API 差分にしない。
parents / variants / origin rule 名は整列して比較する。

- AST_TYPE_CHANGED / EVALUATOR_TYPE_CHANGED: subject は ast / evaluator、値は型名。
- NODE_ADDED / NODE_REMOVED / NODE_KIND_CHANGED: subject は node 名、値は kind。
- NODE_PARENTS_CHANGED / NODE_VARIANTS_CHANGED / NODE_RULES_CHANGED: subject は node 名、値は comma 連結名。
- FIELD_ADDED / FIELD_REMOVED / FIELD_TYPE_CHANGED / FIELD_CARDINALITY_CHANGED: subject は Node.field、値は type または cardinality。
- FIELD_ORDER_CHANGED: 同じ field 集合の順序だけが変わった場合。subject は node 名、値は comma 連結 field 名。生成位置は並び全体を持つ node 宣言。
- METHOD_ADDED / METHOD_REMOVED / METHOD_SIGNATURE_CHANGED: subject は method 名、値は `(name:type,...)->returnType`。
- METHOD_REQUIRED_CHANGED: subject は method 名、値は true / false。

node の追加・削除では配下の field 差分は重複列挙しない。method の追加・削除は別に列挙する。
changes は (kind, subject) 順。差分は変更候補を示すもので、一律に breaking と分類しない。
Java / Rust の API 表現の違いは各 target の schema に残し、型を偽って一致させない。
AST / evaluator の root 型名変更は名前の比較のみで、Change の生成位置は null、origins は空。
node / field / method の変更は対応する宣言と contributing rule の位置を持つ。

## 検証

共通 language-evolution corpus、実 javac / rustc の positive / negative compile、
元文法と生成宣言の位置、空 PATH の native 実行、無書込み、同一入力の決定性を検証する。
fixture ごとの型 / obligation の変化と compile の結果は CI artifact に保存する。
計測値はテスト環境の記録であり、一般的な性能保証ではない。

### 再現手順と artifact

repo ルートで実行する。通常の `mvn test` だけでは Rust conformance は skip される。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=JavaApiSchemaTest,ApiImpactCommandTest,ApiImpactConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-generator
cargo build --release --locked --manifest-path rust/Cargo.toml -p unlaxer-generator
rust/target/release/unlaxer impact --target rust \
  --before spec-corpus/api-impact/node-add/before.ubnf \
  --after spec-corpus/api-impact/node-add/after.ubnf --format json
```

Java host は `org.unlaxer.dsl.CodegenMain impact` に同じ引数を渡す。
Java API: `ApiImpact.compare("java" /* または "rust" */, beforeSource, afterSource).toJson()`。
Rust API: `unlaxer_generator::impact::compare(before_source, after_source).to_json()`。

[共通 corpus](../spec-corpus/api-impact/cases.json) は13組の before / after と独立した期待値を持つ。
コメント、node 追加・削除、数値→文字列、文字列→node、optional、list、field 追加・削除・順序、
shared mapping 統合、演算子のみ変更、grammar 名変更を検証する。

- `unlaxer-dsl/target/api-impact-conformance.tsv`: 13組 × LF/CRLF × 2 target = 52行。
  Rust target は Java/native host の JSON 全体（位置を含む）を比較する。Java target は実生成ソースと照合する。
- `unlaxer-dsl/target/api-impact-compile.tsv`: 13組 × 2 target × 2利用者 = 52行。
  変更前 schema から作った評価器・AST 利用者を凍結し、旧・新 API に対して実 javac/rustc でコンパイルする。
  新 schema に合わせた利用者もコンパイルし、失敗が単に不正な生成コードによるものではないことを確認する。
  成否・compiler 診断コード・生成バイト数・コンパイル所要時間を記録する。
- CI は上記両 TSV の存在を要求し、`rust-conformance` artifact に添付する。
  `unlaxer-generator-linux-x86_64` には空 PATH / 不正 JAVA_HOME で impact を smoke-test した release binary を添付する。

生成バイト数の対象は Java が AST / evaluator の2ファイル、Rust が既存の5 module であり、
言語間のサイズ比較には使わない。時間にはテスト準備・compiler 起動も含み、fork/warmup付き性能実験ではない。
手書き変更行数や人間の作業時間を測った実験でもない。これは API 変化と compiler の検出能力の再現用 artifact である。

### 言語間の差を隠さない例

| 文法変更 | Java の旧利用者 | Rust の旧利用者 |
|---|---|---|
| node 追加 | evaluator の abstract method 未実装 | Semantics の required method 未実装（E0046） |
| 数値 capture →文字列 | `int` を期待する利用者が型エラー | 元から字句 `String` なのでこの例では API 差分なし |
| scalar → optional / list | evaluator の node 引数型は同じだが field 利用者は型エラー | Semantics 引数型と field 利用者の両方が型エラー |
| field 順序入替え | この corpus の位置引数 constructor は型エラー | 名前付き field の構築は成功。両引数が `&str` の impl も成功 |
| 演算子文字列だけ変更 | API 差分なし・コンパイル成功 | API 差分なし・コンパイル成功 |

最後の2例のように、差分検出やコンパイル成功だけでは評価の意味的互換性を保証できない。
shared mapping 統合では Java の `Object` と Rust の `AstValue` への型変化をそれぞれ記録する。
