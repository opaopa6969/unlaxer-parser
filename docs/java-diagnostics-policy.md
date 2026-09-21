# Java の構文診断ポリシー

`ParseOptions` は memoization と構文診断を独立して選択する。既定は
`Memoization.OFF` と `ParseOptions.Diagnostics.DETAILED` で、既存の constructor、
`defaults()`、`withMemoization(...)` は詳細診断を維持する。

```java
ParseOptions options = ParseOptions.withMemoization(Memoization.SAFE_FAILURES)
    .withDiagnostics(ParseOptions.Diagnostics.DETAILED_ON_FAILURE);
var ast = ExampleMapper.parseWithOptions(source, options);
var diagnostic = ExampleMapper.diagnose(source, options);
```

`DETAILED_ON_FAILURE` は成功が多い入力向けの opt-in。初回は到達位置、失敗候補、
expected hints、失敗時 stack、memo の診断フレームと診断 replay を記録・合成しない。
memo の対象・キー・hit、Token 木、capture、scope、意味診断、listener の呼び出しは維持する。
memo hit 時の transaction 状態復元は構文診断から独立して維持する。

生成 Mapper の `parseWithOptions`、`parse(source, preferredAstSimpleName, options)`、
`diagnose(source, options)` は root parse の失敗または入力の未消費があれば、初回 context を閉じ、
同じ入力と memoization 設定の新しい `ParseContext` で `DETAILED` の解析を一度行う。
`diagnose` の `ParseDiagnostic` と `parse` の例外メッセージには再解析した診断を使う。
成功時は再解析せず、AST mapping の失敗も再解析の対象にしない。
生成済み Mapper は再生成が必要。例外メッセージは従来の接頭辞に `ParseDiagnostic` の
内容を追加するため、メッセージ全文の文字列一致に依存する利用者は更新が必要。

```java
try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(source), options)) {
    Parsed parsed = rootParser.parse(context); // 一度だけ実行
    ParseFailureDiagnostics diagnostics = context.getParseFailureDiagnostics();
    // DETAILED_ON_FAILURE では offset 0、空 expected/stack、failure candidate なし。
}
```

`ParseContext` を直接使う低水準 API は自動再解析しない。詳細診断が必要なら呼び出し元で
新しい context を作って `options.withDiagnostics(DETAILED)` で再解析する。
明示的な trial recording は `getTrialHistory()` に残るが、このモードの
`getParseFailureDiagnostics()` は trial を含めて空の診断を返す。
カウンタは個々の context の値であり、別 context の再解析分を合算しない。

解析中の診断を読んで受理判定を変える custom parser には非互換。
再実行できない callback・外部作用を持つ parser にも使わず `DETAILED` を選ぶ。
再解析は入力と options を引き継ぐが、custom parser 内部の可変状態や外部状態を複製・復元しない。
失敗が多い編集途中の入力では初回と詳細解析の両方の費用がかかる。

| 契約 | Java (#259) | Rust (#257) |
|---|---|---|
| 既定 | `Diagnostics.DETAILED` | `Diagnostics::Detailed` |
| opt-in | `Diagnostics.DETAILED_ON_FAILURE` | `Diagnostics::DetailedOnFailure` |
| 失敗時の再解析 | 生成 Mapper の全入力 entry point | runtime / 生成 parser の全入力 entry point |
| 低水準 API | 一度のみ、offset 0・空 expected/stack | 一度のみ、offset 0・空 expected |
| 再解析 context | 新規、同じ入力と memo 方針 | 新規、同じ入力・文法と memo 方針 |

構文診断の native hints と stack 表現は各 backend 固有。未消費入力の主診断は共通で
`trailing_input`、Unicode code point の offset、expected は `end of input`。
Java のテストは成功・途中切断・末尾追加・括弧不一致で方針間の同等性を検証する。
Rust runtime と生成文字列は本変更では変更しない。

## issue #259 の検証・簡易計測（2026-09-21）

- `mvn -B -pl unlaxer-common,unlaxer-dsl -am test`: common 679 件成功、dsl 990 件中 967 件成功・23 件スキップ、失敗・エラー 0。
  スキップは既存の opt-in Rust 統合テスト。`RustNativeEmitterTest` は 1 件実行・成功。
- `SnapshotFixtureWriter` で golden を再生成。変更は Mapper の 2 snapshot のみ。
- wt-259 の parent/common/dsl を専用の isolated Maven repo に install し、TinyExpression の
  `mvn -B -P p4-smoke test -Dgpg.skip=true -Dtinyexpression.skipRailroad=true -Dmaven.repo.local=...` は 85 件成功。
- `ParseContext.java` はバイト単位で編集し、既存同一行 1,333 行の行末変更が 0 件であることを確認。

計測は JDK `21.0.9+7-LTS-338`、`-Xms1g -Xmx2g`、`System.nanoTime` の経過時間。
同じ isolated repo と TinyExpression の `target/classes:target/test-classes:依存 jar` を使用。
`P4ParserBenchmark.parseFreshToken` と同じ root parser、`Memoization.SAFE_FAILURES`、
context の生成・parse・committed root 選択・close の経路を通す。
全消費判定のため消費 code point 数も確認する。
初期 warm-up は小入力で両方針を 30 回ずつ、その後各条件 15 回ずつ
（x64 のみ 5 回ずつ）。各条件 11 回の中央値で、計測順は各ラウンドで交代させた。
合計欄は初回失敗後に新しい context で `DETAILED` を実行した二回分の解析時間。
AST mapping、診断の materialization・文字列化、検証用の木の列挙は計測外。
単一 JVM の簡易計測であり、JMH の複数 fork による性能保証ではない。

| 入力 | 結果 | DETAILED 中央値 ms | 初回のみ ms | 初回 / DETAILED | 再解析込み ms | 合計 / DETAILED |
|---|---|---:|---:|---:|---:|---:|
| complex.tiny | 成功 | 33.529 | 26.842 | 0.8006 | 26.842 | 0.8006 |
| complex-x64.tiny | 成功 | 2351.639 | 1870.130 | 0.7952 | 1870.130 | 0.7952 |
| complex.tiny の前半 | 失敗 | 13.724 | 11.229 | 0.8182 | 25.224 | 1.8379 |
| complex.tiny + `@` | 失敗 | 33.230 | 26.961 | 0.8113 | 61.074 | 1.8379 |

4 入力とも、選択された Token と committed Token の木を深さ・parser クラス・TokenKind・
source 開始/終了位置で比較して一致。成否、全消費判定、カーソル、memo hit 数も一致。
hit 数は順に 2,453 / 149,684 / 1,279 / 2,453。
初回の deferred 診断はすべて offset 0・空 expected/stack。
失敗入力は新しい context で再解析し、通常の `DETAILED` と診断全項目
（offset、行/列、expected、hint 属性、stack 各要素、trial、最深 rule）が一致。
この TinyExpression 文法は末尾 `@` も root parse の失敗として返す。
root が成功して末尾だけ未消費の経路は、生成 Java runtime テストの `(😀)@` で別途検証。

追加テストは `DetailedOnFailureTest` の 7 件と `JavaDetailedOnFailureRuntimeTest` の 3 件。
低水準 API の Token 木・memo・listener・transaction replay・trial・互換 effector、
生成 entry point の再解析回数・context/memo table の分離・Unicode offset・診断・例外文言、
capture と scope の保持/rollback を固定している。
