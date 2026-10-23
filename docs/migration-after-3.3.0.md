# 3.3.0 以降の開発版への移行

これは [Unreleased](../CHANGELOG.md#unreleased) の移行案内です（#769）。公開済みの
`v3.3.0` と Maven Central の 3.3.0 の仕様を変更するものではありません。
各項目の PR 番号は `opaopa6969/unlaxer-parser` の PR を指し、CHANGELOG の Fixed で `PR` の明記がない括弧内の番号（例: `(#717)`、`(#713)`）は issue 番号です。

## 再生成の手順

1. 利用する開発版 revision を固定し、その revision の生成器と runtime を揃えます。
   Java 21 / Rust 1.85.0 が開発版の検証基準です（PR #692）。公開版の依存物と混在させず、
   ソースからの Maven build は専用の local repository を指定します。repository のルートで、
   [品質検証文書の隔離 build](production-quality.md#開発版の再現手順java21)と
   [Rust 生成器の build](../rust/README.md#rustだけで生成する)を実行する例です。

   ```sh
   parity_m2=$(mktemp -d)
   mvn -B -Dmaven.repo.local="$parity_m2" -pl unlaxer-common,unlaxer-dsl -am install -DskipTests -Dgpg.skip=true
   cargo build --release --locked --manifest-path rust/Cargo.toml -p unlaxer-generator
   ```

   以降の Maven 例も同じ shell の `parity_m2` を使います。転記元のコマンドに
   `-Dmaven.repo.local="$parity_m2"` を付け、install と利用側の依存解決を同じ場所に揃えています。

2. 下表の該当する文法・手書き API 利用箇所を直し、従来の生成設定で parser・AST・mapper・
   evaluator を一緒に再生成します。利用している LSP / DAP / Playground も同じ生成器で更新します。
   Java は [README の Maven 設定](../README-ja.md#3-コードジェネレータプラグインを追加)
   （`AST,Parser,Mapper,Evaluator` と生成先 source root の登録）を適用した利用側 project の
   ルートで、同 README の [生成コマンド](../README-ja.md#4-コードを生成)を実行します。
   この例は生成とコンパイルを一緒に行います。直接 CLI を使う設定は
   [CodegenMain の説明](../unlaxer-dsl/README.ja.md#codegenmain--cli-ツール)を参照してください。

   ```sh
   mvn -Dmaven.repo.local="$parity_m2" compile
   ```

   Rust は repository のルートで [再生成の例](../rust/README.md#rustだけで生成する)を使います。
   同文書の説明どおり、生成物の一致確認用の `--check` を外した生成コマンドです。

   ```sh
   rust/target/release/unlaxer generate --target rust \
     --grammar unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf \
     --output rust/examples/evolution/src/generated
   ```

   これは同梱 Evolution 文法の例です。利用側では文法・出力先を自分のものへ置き換えます。

3. 手書きの evaluator / `Semantics`、enum・IR の網羅的な `switch` / `match`、
   AST field と root wrapper を使うコードを新しい型に合わせ、生成物と一緒にコンパイルします。
   Java は手順2の `mvn ... compile` を再実行します。Rust の同梱 consumer の build は
   [ネイティブ実行ファイルの例](../rust/README.md#ネイティブ実行ファイル)を使います。

   ```sh
   cargo build --release --locked --manifest-path rust/Cargo.toml -p unlaxer-evolution-example
   ```

   文法変更による公開型の差は [API impact の再現例](api-impact-report.md#再現手順と-artifact)
   でも確認できます。次は同梱の before / after 文法を比較するコマンドで、generator の旧・新 revision
   自体を比較するものではありません。利用側の文法差を調べるときは2つのパスを置き換えます。

   ```sh
   rust/target/release/unlaxer impact --target rust \
     --before spec-corpus/api-impact/node-add/before.ubnf \
     --after spec-corpus/api-impact/node-add/after.ubnf --format json
   ```

4. 利用側の同じ入力で、受理・拒否、AST 値、原文と CP 位置、失敗段階を確認します。
   Java と Rust の双方を提供する場合はそれぞれ実行します。診断位置の修正と
   AST 型の変更は別々に期待値へ反映します。
   repository のルートから両言語の生成 consumer を検証する例は
   [API impact の共通テスト](api-impact-report.md#再現手順と-artifact)にあります。
   `-DrustConformance=true` を付け、対象テストが skip されていないことも確認します。

   ```sh
   mvn -B -Dmaven.repo.local="$parity_m2" -pl unlaxer-common,unlaxer-dsl -am test \
     -Dtest=JavaApiSchemaTest,ApiImpactOverloadTest,ApiImpactCommandTest,ApiImpactConformanceTest,EvaluationConformanceTest \
     -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
   ```

   同梱 Rust consumer の実行例は [README](../rust/README.md#ネイティブ実行ファイル)からの転記です。
   返る JSON の評価値は `9` です。このサンプルの成功に加え、利用側自身の文法と入力でも検証します。

   ```sh
   printf '%s\n' 'if(0,neg(2*3),4+5)' | rust/target/release/unlaxer-evolution-example
   ```

## 文法と生成 API

| 対象・根拠 | 利用側の変更と詳しい契約 |
| --- | --- |
| REGEX 廃止（PR #617 / issue #612） | `REGEX(...)` と旧 `RegexTokenParser` binding を宣言的な `token NAME ::= expression;` へ置き換えて再生成します。Java 実行 API も削除されています。[移行例](regex-retirement.md)の選択順・反復の意味を確認し、正規表現と同じ後戻りを仮定しないでください。 |
| enum の型・名前・root（PR #557） | scalar / optional / list の field を enum 型へ、enum-only root の利用を生成 wrapper へ合わせます。非 ASCII 定数への手書き参照を更新します。例えば `日本` は `U65E5__U672C_` になります。[移行](enum-values.md#migration-and-staged-scope)。 |
| 混在 enum（PR #563） | Java の混在 field は `EnumValue.value()` で enum を取り出します。Rust の `AstValue` に追加される enum variant を網羅的な `match` に加えます。純粋な単一 enum field と混同しないでください。[混在値](enum-values.md#mixed-values)。 |
| Rust 数値 capture（PR #575） | 直接の `NumberParser` / `DigitParser` field を `String` から `i32` へ変更し、文字列からの再変換を外します。optional / list と `Semantics` 引数も更新し、IR の `Kind::Integer` を扱います。小数・指数・範囲外は認識成功後に mapping が失敗します。[型と移行](numeric-captures.md#compatibility-and-verification)。 |
| 共通 field（PR #570） | Java の `@commonField` accessor は実際の member の型・cardinality に合わせます。従来の一律 `String` を仮定しないでください。Rust は borrowed family view を使います。[型の契約](common-fields.md)。 |
| 自動評価（PR #577） | Java の自動 literal / passthrough を使う evaluator に `convertValue(Object)` を実装します。Rust は使う kind に必要な `convert_value` / `is_truthy` 等の hook を実装します。[値と移行](evaluation-strategies.md#values-and-migration)。 |
| `@typeof` と直接 IR（PR #584） | Rust の既存 `Rule` literal の構築に `type_constraints: vec![]` を追加します。制約を使う文法では syntax 成功と mapping 時の型不一致を分けて処理します。[IR の移行](typeof-constraints.md#generated-ir-and-verification)。 |
| AST family（PR #597） | Rust の旧空親 variant / `eval_family` を参照するコードを具体的な子 variant / evaluator へ移します。[透明投影と互換性](family-projection.md)。 |
| 結合演算子の合成 leaf（PR #605） | 該当する Rust operand の格納型を `Option<Box<Ast>>`、手書き evaluator 引数を `Option<&Ast>` に合わせます。IR の `Kind::NullableNode`（Java host は `Kind.NULLABLE_NODE`）を網羅的な分岐へ加えます。`None` は合成 leaf 用です。[投影と移行](associative-projection.md)。 |
| `CI` の比較（PR #534） | 生成 `CI` は Unicode 17.0.0 固定の simple upper→lower 比較になります。再生成後は JDK 固有の大小文字対応を期待値に使わず、[生成 CI の契約](../spec-corpus/case-insensitive/README-generated.md)で受理ペアを確認します。手書き `IgnoreCaseWordParser` は従来どおりです。 |
| 左再帰（PR #600） | Java も未変換の非消費 cycle を生成前に `E-LEFT-RECURSION` で拒否します。例えば `Root ::= Root '+' Term \| Term` は `Root ::= Term { '+' Term }` に書き換えます。[生成境界](left-recursion.md)。 |
| Java token binding（PR #645） | 生成 subclass の継承元に final class を指定していた場合、利用可能な public・nonfinal・引数なし constructor の Parser へ置き換えます。Rust callback の可否とは別の Java 検証です。[binding](java-token-bindings.md)。 |
| 関数形式の予約（PR #641、#710） | 既存の規則参照＋group を `CAPTURE(` / `SAME_AS(` / `CAPTURE_SCOPE(` / `COMMIT(` / `LABEL(` / `CATCH(` と隣接して書いていた場合、名前と `(` の間に空白を入れます。新機能を使う場合は隣接した呼出形式を使います。[owned capture](general-owned-capture-syntax.md)、[cut / label](cut-and-labeled-failures.md)。 |
| API impact の overload（PR #579） | Java の同名 method 群の `subject` は `name(type,...)` になります。レポートを読むコードは method 名だけで一意とせず引数型列も扱います。非 overload の表記は従来どおりです。[差分の規則](api-impact-report.md#差分の規則)。 |
| 学習用 interpreter（PR #581、#584） | `unlaxer-learning` は未対応の `@enum` / `@typeof` を compile 時に拒否します。これらを使う文法は対応済みの Java/Rust 生成 CLI・生成 Playground へ移します。[enum の利用境界](enum-values.md#browser-lesson-boundary)、[型制約](typeof-constraints.md)。 |

## runtime・診断・ツール

| 対象・根拠 | 利用側の変更と詳しい契約 |
| --- | --- |
| 反復（PR #611、#622、#623） | 通常の `{0}` に子の実行・副作用を期待しないでください。従来の否定検査が必要な手書きコードは Java `Zero` / `LazyZero`、Rust `zero_match()` の契約を確認します。Classic の nullable repeat / separated list は進捗しない成功を一度数えて止まります。[ゼロ回](zero-repetition.md)、[反復](nullable-repetition.md)、[区切り付き](nullable-separation.md)。 |
| memo key（PR #625） | Java `PositionKey` の reflection / record pattern に追加の reset policy を反映します。通常は `positionKeyOf(context, kind, invert)` で取得します。旧5引数 constructor は reset=true を表します。[移行](memo-reset-policy.md)。 |
| memo の有限予算（PR #656、#661、#675） | 件数・payload 予算に達すると保存せず通常解析へ戻ります。必要な明示設定を初期化前に与え、hit 数や entry 統計を固定したテストを見直します。失敗合計の既定は32 MiB、成功合計は16 MiB、memo 自体は既定 OFF です。[失敗の設定](failure-memo-budget.md)、[成功の設定](success-memo-budget.md)。 |
| 公開 CST mapper（PR #650、#682） | mapper を再生成します。手書き CST の不正参照・循環・span を修正し、`E-MAPPING-CST` と資源上限の `E-MAPPING-LIMIT` を処理します。[構造・上限・失敗後の契約](public-mapper-cst.md)。 |
| Java observer / callback の例外（PR #589、#636、#651） | observer 失敗後は context を close して新しい context で再試行します。通常 callback の例外は状態復元後に同じ context で再試行可能、資源失敗は fatal です。Rust の controlled `Err` と panic の扱いも区別します。[observer](parser-observer-failures.md)、[通常例外](ordinary-parser-exceptions.md)、[資源失敗](resource-abort-publication.md)。 |
| Classic 原文・診断と capture（PR #607、#610、#658、#732、#735、#736、#751） | Rust の旧 trivia 除去後の原文・span、先読み後や不完全な指数の失敗位置、owned capture の期待値を更新します。[atomic trivia](classic-atomic-trivia.md)、[Sequence 原文](classic-sequence-trivia.md)、[診断位置](prior-matched-diagnostics.md)、[rule stack](rule-stack-diagnostics.md)、[capture 原文](owned-capture-replay.md)を入口ごとに確認します。 |
| LSP lifecycle（PR #553） | `initialize` 成功前に通常 request を送らず、再設定のための2度目の `initialize` を `unlaxer/configure` へ置き換えます。省略した設定は既定値へ戻るため、必要な capability 設定も再送します。[移行例](classic-lsp-lifecycle.md#migration-from-repeated-initialize)。 |
| VS Code 拡張（PR #689） | TinyCalc と新規 scaffold の拡張を使う editor を VS Code 1.97.2 以上へ更新します。Java / Rust backend の設定・ビルド手順は [起動案内](rust-vscode-launch.md)にあります。 |

`@nestedCaptures` は規則単位の opt-in です。旧 flat field を一律に入れ子型へ変える変更ではありません。
利用を始める場合は [対応範囲表](typed-nested-captures.md#nested-の対応範囲と拡張先)に従って再生成し、
循環 helper、container を選択肢に持つ choice、nested 所有規則自身の `@eval` / 結合演算子、
token stream 等の未対応形を対応済みと扱わないでください（PR #698、#699）。
