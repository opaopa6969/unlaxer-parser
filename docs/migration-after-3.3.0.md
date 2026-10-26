# 3.3.0 以降の開発版への移行

これは [Unreleased](../CHANGELOG.md#unreleased) の移行案内です（#769）。公開済みの
`v3.3.0` と Maven Central の 3.3.0 の仕様を変更するものではありません。
各項目の PR 番号は `opaopa6969/unlaxer-parser` の PR を指し、CHANGELOG で `PR` の明記がない括弧内の番号（例: `(#717)`、`(#713)`）は issue 番号です。

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
| global `@comment`（PR #748 / issue #712） | `@comment: { line: '//' }` 以外の形、`@comment` の重複、名前付きの全体 `@whitespace` との併記は `E-COMMENT-SETTING` / `E-COMMENT-WHITESPACE` で位置付きで拒否されます。3.3.0 の Java が黙って生成していた文法は書き直します。全体の `@whitespace: javaStyle` と併記した場合は拒否されず、生成物も変わらず `W-COMMENT-IGNORED` が出ます。規則ごとの `@whitespace` / `@interleave` と併記した文法では継承 rule が空白・block comment を読み飛ばさなくなり、受理と node / AST の source span が変わることがあります。javaStyle も規則ごとの trivia も無い文法では生成 class `<Grammar>LazyChain` / `<Grammar>SpaceDelimitor` が `<Grammar>LineCommentLazyChain` / `<Grammar>LineCommentDelimitor` に変わるので、手書きの参照を更新します。[契約](rule-trivia.md#global-comment712)、[Java の変更と移行](rule-trivia.md#java-の変更と移行)。 |
| `@autoCommit` と global `@comment`（issue #745） | 注釈付きの規則は javaStyle 併記・comment 単独・none 併記でも COMMIT を挿入します。全入力の受理と成功時の CST / AST は維持し、失敗位置・診断が変わります。comment の MatchOnly 行末は消費位置に含めず、その直前で COMMIT が始まる場合は CR / LF の位置で確定エラーになります。規則 trivia などの対象外には `comment-setting` / `comment-whitespace` / `comment-rule-trivia` の reason を返します。解析可能になった文法の未知注釈は規則を `opaque` にするため、従来の `grammar` reason と未知注釈の警告文面も変わります。[条件](auto-commit.md)。 |
| Simple annotation（PR #778 / issue #715） | `@custom` などの生成に作用しない Simple annotation は、Java に加えて Java-host Rust・native Rust でも受理され、native の `unlaxer check` も `P-ANNOTATION` で blocked にせず passed とします。各注釈に警告 `W-ANNOTATION-IGNORED` が出ます。native frontend は Java に合わせ、`@ leftAssoc`（`@` の後に空白）を `@leftAssoc` として適用しなくなり、`@mapping` など引数の必要な名前を `(` なしで書いた形を構文エラーでなく Simple annotation とします。これらに頼っていた文法は正しい綴りに直します。`@autoCommit` を使う文法では注釈のある rule が解析から外れ、挿入位置が変わることがあります。[契約](simple-annotations.md)。 |
| 量指定子・`@precedence` の overflow（PR #801 / issue #783、PR #810 / issue #802） | `{n}` / `{n,m}` / `{n,}` の境界と `@precedence(level=...)` が `2147483647` を超える文法は、構文層では受理され、検証・生成で `E-BOUNDED-OVERFLOW` / 新設の `E-PRECEDENCE-OVERFLOW` と元ソースの CP 範囲で拒否されます。従来の Java の `NumberFormatException`（未処理例外）や native Rust frontend の構文エラーを捕まえていた処理は、診断 code の判定へ置き換えます。Rust の逆順境界の生成診断は `invalid repetition bounds` から Java と同じ `E-BOUNDED-INVALID: rule NAME has BoundedRepeatElement with min=N, max=M` になり、逆順と overflow が併存するときは Java の走査順で最初の診断を返します。Parser IR export（`--export-parser-ir`、終了コード 3）と BNF 変換も `E-PRECEDENCE-OVERFLOW` で拒否します。[検証仕様](../unlaxer-dsl/specs/validation.md)。 |
| UBNF の数字は ASCII のみ（PR #817 / issue #811） | `@precedence(level=N)`、量指定子 `{n}` / `{n,m}` / `{n,}`、`ADAPTER(..., version=N)` の数字は仕様の `DIGIT = CHAR_RANGE('0','9')` どおり ASCII の `0`–`9` だけを受理します。3.3.0 の Java が受理していた全角 `１` などの Unicode 数字は、Java・native Rust とも構文エラー（`unlaxer check` では `P-SYNTAX`）になるため、ASCII 数字へ書き換えます。公開クラス `UBNFParsers.DigitParser` も ASCII の数字だけに一致します。生成 parser が入力テキストの数字を読む runtime の挙動は変わりません。 |
| `E-RAISING-REPETITION` と空 literal（PR #775 / issue #749） | 通常の文法の `{ COMMIT('') }`・`{ COMMIT('', 'm') }`・捕まえない `{ LABEL(L, '') }` は、Java の validator / 生成器、native generator、両 portability check で位置付きで拒否されます。`''` は通常の文法では常に失敗するためです。`@tokenStream` / `@lexicalContext` の文法は従来どおり拒否しません。[契約](empty-literal.md)。 |
| 複数 grammar（PR #772 / issue #714） | 同じファイルに複数 grammar がある場合、両 Rust host は `grammar_<ASCII小文字名>/` と root `mod.rs` を生成します。単一 grammar の出力は従来どおりです。Java 向け生成は、同一 package で小文字化した生成クラス名が衝突すると拒否し、report / manifest に検証エラーを出します。後続の generator が例外を投げた場合は、単一 grammar でも生成ファイルを一つも書きません。複数 grammar の生成失敗には grammar 名と元ソースの CP 範囲が付きます。[契約](multiple-grammars.md)。 |
| 再帰 helper の深さと作業量（PR #804 / issue #754、PR #803 / issue #758） | Java の Java 生成は単一選択肢の choice を追加の深さに数えなくなり、深い括弧に包まれた再帰 helper（再現文法の84〜122段）を `E-RECURSIVE-SHAPE` で拒否しなくなります。Rust の preferred projection も同じ数え方になり、従来の境界の直後の深さでは投影が保持されるため、Rust の `parser.rs` / `mapper.rs` が従来の投影なしの出力から変わります。Java host の Rust 生成は、数値 capture と無関係な規則を含む文法を作業量上限で拒否しなくなります。拒否を前提にした期待値や保存した生成物を更新します。[上限の契約](java-parser-limits.md)。 |
| Rust runtime / IR の variant（PR #800 / issue #768、PR #799 / issue #788） | Rust runtime の公開 `Expr` に `JavaLiteral`（PR #800）と `MetadataCapture`（PR #799）、Rust IR の `Expression` と Java host の `GrammarIR` に `LexicalLiteral`（PR #800）が追加されます。網羅的な `match` / `switch` に加えます。どちらも生成器の出力が変わるため、Rust の parser を再生成します。PR #799 の preferred AST の修正は、runtime の更新だけでは旧生成物に適用されません。 |

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
| 生成の警告と CLI（PR #778 / issue #715、PR #796 / issue #777） | native の `unlaxer generate` と Java-host の `generate --target rust` は、生成物を変えない警告を stderr に `warning: CP <start>..<end>: <message>` の形で出します。新しい `--strict` / `--fail-on warning` を付けると、警告があれば何も書かずに終了コード 5 で失敗します。Java の flag CLI は `W-ANNOTATION-IGNORED`・`W-AUTO-COMMIT-SKIPPED`・`W-COMMENT-IGNORED` を同じ1行の message で報告し、`--strict` で失敗します。text 表示と JSON の `issues[]` の `W-COMMENT-IGNORED` は、`message` が subject（`comment`）から上の1行に、`rule` が空から `comment` に、hint が `Remove this @comment setting (docs/rule-trivia.md).` に変わります。stderr や検証 report を解析するスクリプトを更新します。`GrammarValidator.validate` の戻り値は変わりません。[生成の警告](simple-annotations.md#生成の警告を-cli-まで届ける)、[global comment](rule-trivia.md#global-comment712)。 |
| `check` の位置・件数・構造診断の優先順位（PR #824 / issue #776、issue #825、#833 / #834 / #835） | Java-host / native Rust の `check --target rust` は、import 解決後も root ソース由来の診断に元の CP 範囲を返し、別 grammar・別位置の同じ code/subject を別件として残します。従来 null・1件になっていた診断の期待値を更新します。import 由来・出典不明は null のままで、同じ code/subject の null 診断の重複除去と root CP 順は維持します。複数の構造エラーが同居するときは Java-host に揃え、commonField schema のエラーを eval・enum・embedding 等より先に、その規則の CP 範囲で返します（bounded/precedence/nested と前段診断は先行）。単一エラーと schemaVersion 1・フィールドは変えません。[契約](portability-check.md)。 |
| Rust lowering / generator の spec corpus 診断（issue #833 / #834 / #835） | 参照・mapping・whitespace・結合性/precedence・profile のエラーは Java validator と同じ `E-*:` を先頭に付け、root 欠如より先に返します。Java-host の `Rust subset: ` は説明部分に残ります。直接呼び出す Java-host lowering / generator も、結合性のみ・precedence のみの不正な宣言を拒否するようになります。メッセージを完全一致で扱う利用者は期待値を更新してください。check JSON の code・CP 範囲・終了コード・schema は変わりません。 |
| Rust lowering / generator の残る宣言診断（issue #843） | 重複 `@interleave` / `@scopeTree`、precedence が1つある結合性の mapping 欠如・rightAssoc 非canonical、`@longestChoice` の形状、import なし修飾参照にも Java と同じ `E-*:` を付けます。修飾参照は `E-RULE-UNDEFINED: Rust subset: qualified rule reference` / `E-RULE-UNDEFINED: unsupported qualified rule reference` となり、従来の説明を残します。root なしでもこれらの診断を先に返すため、完全一致の期待値を更新してください。結合性は root 投影の失敗を含む従来の説明を保ち、precedence 欠如・重複の既存優先順位も維持します。単一エラーの CP 範囲・終了コード、check JSON の code・span・schemaVersion 1、成功時生成物は変わりません。 |
| UBNF LSP の生成警告（PR #806 / issue #798） | `W-COMMENT-IGNORED` は文書先頭ではなく設定のソース範囲に表示され、`W-ANNOTATION-IGNORED` も表示されるようになります。どちらも CLI と同じ code・message で、文書ごとに一度だけ出ます。editor の診断の件数・範囲・message を固定したテストや拡張を見直します。 |
| Rust の診断位置と期待候補（PR #791 / issue #753、PR #807 / issue #793、PR #792 / issue #752、PR #808 / issue #794、PR #813 / issue #809、PR #819 / issue #814、PR #821 / issue #820、PR #837 / issue #829、PR #773 / issue #750） | 生成した Rust parser の診断を Java に揃えます。正の `LOOKAHEAD` の後に失敗した rule・group・選択・回数指定の反復は、失敗した frame の開始を失敗位置にします（例: `axyz` で CP2 → CP4、`a//c\nxy` で CP5 → CP7）。native Number の内部失敗は Java と同じ位置・候補になり（`Number.not_ahead()` に `2a` で CP0 → CP1）、期待候補が `number` から `SignParser`・`DigitParser` などの名前に変わります。`SAFE_FAILURES` の `(!Num 'x') \| Num` に `-` / `+` を与えると、期待候補に `negative lookahead` が加わります。通常の経路で失敗した Chain の後も、Java と同じく owner の matched cursor の reset を false のまま残すので、同じ context の後続の `LOOKAHEAD` / `NEGATIVE_LOOKAHEAD` / `UNTIL` / `EMPTY` の結果と診断位置が変わることがあります（例: `ab` に `LOOKAHEAD('a')`、`'a' 'z'` の失敗、`NonOrdered(LOOKAHEAD('b'))` の順で同じ context を使うと、最後の先読みが失敗・matched CP1 から成功・matched CP2 に変わります）。名前解決 scope / predicate は成功後も親の reset を維持し、Classic token mode の rule 参照・透過 wrapper は失敗した Chain の false を保持するよう揃えます（#814）。通常呼出の frame 開始も reset=true なら matched を consumed へ戻すため、先読み後の同一 context での受理が変わります（#829: `ab` に `LOOKAHEAD('a')` の後、`LOOKAHEAD('b') | Number` を呼ぶと成功 CP0/2 から失敗 CP0/1 に変わります。Chain が先に reset=false にする場合は維持します。先読みの後に rule を介さず直接呼んだ `LOOKAHEAD` / `NEGATIVE_LOOKAHEAD` / longest 系 choice の最遠診断 CP は、先読みが進めた位置から frame の開始位置に下がることがあります。Java との残差は #838）。空の期待候補は LSP の message と `ParseError` / `ParseDiagnostic` の `Display` で `''` と表示されます。この空候補の表示変更では canonical JSON と `data.expectedTokens` の値 `""`、受理と cursor は変わりません。位置・候補・表示を固定した期待値を更新します。[rule frame](prior-matched-diagnostics.md#失敗した-rule-frame-の開始753)、[Number](../conformance/number-token/README.md)、[Chain 失敗後の reset](../conformance/chain-reset-after-failure/README.md)、[空の候補の表示](empty-literal.md#人が読む表示750)。 |
| Rust の `@f` と capture（PR #759 / issue #742、PR #785 / issue #761、PR #787 / issue #762、PR #799 / issue #788） | Rust の受理と capture 値を Java に揃えます。Classic で直接の正の LOOKAHEAD を `@f` にした値は外側の CAPTURE に先読みの原文を渡しません（`CAPTURE(w, AHEAD @f) SAME_AS(w)` に `x` で `w` は空文字列）。生成・投影した原文が Choice／rule 経由や全入力解析で `@f` に届く場合は、Rust でも syntax 失敗になります（生成は CP0、投影は CP1）。MatchOnly と reset=true の反復を包む `@f` は拒否されず成功します。preferred AST 用 parser は通常 parser と同じ成否・cursor・原文・AST を返します。Rust だけで成功・失敗していた入力の期待値を見直します。[owned capture](owned-capture-replay.md)。 |
| Rust の lexical literal の Classic 反転（PR #800 / issue #768） | `@tokenStream` / `@lexicalContext` の文法で、非空 literal の Classic の反転は入力を消費せず、不一致・短い入力・EOF で幅0の成功になります。同じ区別で、Classic の非反転照合も lexical goal を通り、optional / repeat 内の literal の失敗で matched cursor を戻さず、token trace の label が `LexicalTokenParser` になります。Java の挙動は変わりません。Rust の parser を再生成し、cursor・CST・失敗位置の期待値を確認します。[契約](empty-literal.md#非空-lexical-literal-の-classic-反転768)。 |
| Java の成功 memo の再生（PR #760 / issue #744） | `SAFE_FAILURES` で Choice の成功 memo が hit しても、miss と同じ `Parsed`（token・status・message）を返し、CAPTURE の値と解析位置が memo OFF と同じになります。返す token と message を成功 memo の予算に計上するため、予算が飽和する入力では成功 entry と hit がわずかに減ります（PR の計測で hit 9925 → 9864）。hit 数や entry 統計を固定したテストを見直します。[契約](pure-success-memo.md)。 |

`@nestedCaptures` は規則単位の opt-in です。旧 flat field を一律に入れ子型へ変える変更ではありません。
利用を始める場合は [対応範囲表](typed-nested-captures.md#nested-の対応範囲と拡張先)に従って再生成し、
循環 helper、container を選択肢に持つ choice、nested 所有規則自身の `@eval` / 結合演算子、
token stream 等の未対応形を対応済みと扱わないでください（PR #698、#699）。
