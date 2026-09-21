# Java の構文診断ポリシー

`ParseOptions` は memoization と構文診断を独立して選択する。既定は
`Memoization.OFF` と `ParseOptions.Diagnostics.AUTO`。`ParseOptions.DEFAULT`、
constructor、`defaults()`、`withMemoization(...)` はすべて `AUTO` を選ぶ。
明示した `DETAILED` / `DETAILED_ON_FAILURE` は入口や parser の性質によって変更されない。

## AUTO の解決

| 入口 | parser 木 | 解決先 |
|---|---|---|
| 再解析経路を持つ生成 Mapper の `parse` / `parseWithOptions` / `diagnose` | 全ノードが library parser または marker 実装 | `DETAILED_ON_FAILURE` |
| 同 entry point | 未宣言の手書き parser を含む | `DETAILED` |
| `ParseContext.withOptions` / `new ParseContext` | いずれも | `DETAILED` |

低水準 API は再解析しないため、`AUTO` をコンストラクタ内で `DETAILED` に解決する。
listener の `onOpen` より前に解決され、`getOptions()` は解決後の方針を返す。
既定オプションで失敗後に `getParseFailureDiagnostics()` を読む既存コードは詳細診断を維持する。

生成 Mapper は `DiagnosticsSafety.isDeferredDiagnosticsSafe(root)` で
`HasChildrenParser.getChildren()` をたどり、判定結果を文法ごとの `static final` に保存する。
循環と共有ノードは identity で一度だけ訪問する。入力ごとの走査はない。
生成 parser の既知の実装には `DiagnosticsAgnostic` を付与する。custom token の wrapper は
基底クラスが library parser の場合だけ自動付与し、custom の場合は基底クラスの宣言を継承する。
無条件の `true` 埋め込みを避けたのは、custom token の子孫も同じ規則で検査するため。
`org.unlaxer.parser` とそのサブパッケージの既存 library parser は安全として扱う。

```java
import org.unlaxer.context.DiagnosticsAgnostic;
import org.unlaxer.parser.elementary.WordParser;

public class KeywordParser extends WordParser implements DiagnosticsAgnostic {
    public KeywordParser() { super("keyword"); }
}
```

marker は「解析中に `getParseFailureDiagnostics()` を読まず、同じ入力を新しい context で
再実行できる」という宣言。callback や外部作用も再実行できる必要があり、subclass にも
契約が継承される。marker は自分自身の振る舞いの宣言であり、子 parser の安全性や
memoization の安全性を宣言しない。準備後の parser 木・振る舞いは固定する。
手書きの再解析 entry point でも判定を一度保存し、`options.resolveDiagnostics(safe)` を使える。
再解析しない入口では必ず `resolveDiagnostics(false)` とする。

## 明示的な方針と再解析

```java
ParseOptions options = ParseOptions.withMemoization(Memoization.SAFE_FAILURES)
    .withDiagnostics(ParseOptions.Diagnostics.DETAILED_ON_FAILURE);
var ast = ExampleMapper.parseWithOptions(source, options);
var diagnostic = ExampleMapper.diagnose(source, options);
```

`DETAILED_ON_FAILURE` は成功が多い入力向けの方針。安全な生成 entry point の `AUTO` もこれを選ぶ。初回は到達位置、失敗候補、
expected hints、失敗時 stack、memo の診断フレームと診断 replay を記録・合成しない。
memo の対象・キー・hit、Token 木、capture、scope、意味診断、listener の呼び出しは維持する。
memo hit 時の transaction 状態復元は構文診断から独立して維持する。

生成 Mapper の `parseWithOptions`、`parse(source, preferredAstSimpleName, options)`、
`diagnose(source, options)` は解決後の方針が `DETAILED_ON_FAILURE` のとき、
root parse の失敗または入力の未消費があれば、初回 context を閉じ、
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

`ParseContext` を直接使う低水準 API は自動再解析しない。明示 deferred で詳細診断が必要なら呼び出し元で
新しい context を作って `options.withDiagnostics(DETAILED)` で再解析する。
明示的な trial recording は `getTrialHistory()` に残るが、このモードの
`getParseFailureDiagnostics()` は trial を含めて空の診断を返す。
カウンタは個々の context の値であり、別 context の再解析分を合算しない。

明示 `DETAILED_ON_FAILURE` は、解析中の診断を読んで受理判定を変える custom parser には非互換。
再実行できない callback・外部作用を持つ parser にも使わず `DETAILED` を選ぶ。
再解析は入力と options を引き継ぐが、custom parser 内部の可変状態や外部状態を複製・復元しない。
失敗が多い編集途中の入力（LSP 等）では初回と詳細解析の両方の費用がかかるため、
`ParseOptions.defaults().withDiagnostics(ParseOptions.Diagnostics.DETAILED)` を明示することを推奨する。
成功時の speculative な native 診断が必要な用途でも `DETAILED` を選ぶ。

Java 側の #261 の実装範囲は以下。Rust の AUTO は別作業であり、この変更だけで
Java / Rust 共通機能の完了とはしない。

| 契約 | Java (#261) | Rust (#257 時点、#261 は別作業) |
|---|---|---|
| 既定 | `Diagnostics.AUTO`（入口で解決） | `Diagnostics::Detailed` |
| 明示指定 | `DETAILED` / `DETAILED_ON_FAILURE` | `Diagnostics::DetailedOnFailure` |
| 失敗時の再解析 | 生成 Mapper の全入力 entry point | runtime / 生成 parser の全入力 entry point |
| 低水準 API | AUTO は詳細、一度のみ。明示 deferred は offset 0・空 expected/stack | 一度のみ、offset 0・空 expected |
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

## issue #261 の検証・TinyExpression の入口（2026-09-21）

`P4PreferredAstMapper.parseDetailed` は `parseMappedCandidates` → `parseRootToken` →
`parseWithRoot` → `new ParseContext` を通る。生成 Mapper の `parse` は通らないため、
既定の `AUTO` は `DETAILED` に解決され、今回の変更ではこの facade は高速化しない。
既存の memoization 有効化は維持される。

さらに検証対象の TinyExpression 文法の `STRING` は手書きの
`org.unlaxer.tinyexpression.parser.StringLiteralParser` を参照している。
このクラスは `DiagnosticsAgnostic` 未宣言なので、生成 wrapper の `StringParser` も
未宣言のまま。`TinyExpressionP4Mapper` の準備時判定は `false` となり、生成 entry point の
既定 `AUTO` も `DETAILED` に解決される。これは未宣言 custom parser に対する保守的な規則の結果。
生成 entry point の自動高速化には、その手書き parser の再実行契約を確認して marker を
宣言する必要がある。TinyExpression の変更は今回の範囲外。

- `mvn -B -pl unlaxer-common,unlaxer-dsl -am test`: common 683 件成功、dsl 993 件中
  970 件成功・23 件スキップ。失敗・エラー 0。`RustNativeEmitterTest` も 1 件成功。
- 専用 isolated Maven repo に parent/common/dsl を install して p4-smoke を実施し、85 件成功。
- golden は Parser 2 件・Mapper 2 件を更新。marker 宣言、準備時判定、AUTO 解決のみの差分。
- `ParseContext.java` は既存同一内容 1,360 行の行末変更 0 件。差分は追加 3 行・削除 2 行。
- 追加テストは `AutoDiagnosticsTest` の 4 件と `JavaDetailedOnFailureRuntimeTest` の 3 件。
  既定・明示方針・値の同等性、低水準診断、custom/marker 境界、循環/共有ノード、
  生成 entry point の AST・Token 木・診断・再解析回数を検証する。

今回の A/B は指定の `TinyExpressionP4Mapper.parse(source, null, ParseOptions.defaults())` と、
同じ options に `.withDiagnostics(DETAILED)` を付けた呼び出しを比較する。
`Memoization.OFF` を維持し、AST mapping と失敗時の例外生成を計測に含める。
入力の読み込み、検証用の Token 木・診断の列挙と構造比較は計測外。
JDK は `21.0.9+7-LTS-338`、G1 GC、`-Xms1g -Xmx2g`。

`complex.tiny` は単一 JVM で各20回 warm-up 後、各30回を交互に測定する。
他3入力は各4 JVM に分け、それぞれ `complex.tiny` で同じ生成文法を各20回 warm-up 後、
対象入力を各8回、合計32回ずつ測定する。各 JVM を別の物理 core（論理 CPU 0/2/4/6）に
固定し、初回の方針と各ラウンドの実行順を交代させる。
中央値は全サンプルから算出する。並行計測の負荷も含む簡易計測であり、絶対時間や数%の差を
性能保証としない。現在の文法では両方とも `DETAILED` であり、自動高速化の測定にはならない。

4入力すべてで AST（失敗時は例外メッセージ）、公開 `diagnose` の戻り値、Token 木と
native 診断が一致した。成功は `complex.tiny` / `complex-x64.tiny`、失敗は
`complex-half.tiny` / `complex-tail.tiny`。Token は元の木・committed root について、
深さ、parser クラス、TokenKind、source の開始/終了位置を比較した。
比較した Token 記録数は小入力2,636、x64入力161,784、失敗入力は各1。
native 診断は offset、行/列、expected と hint 属性、stack、trial、最深 rule を比較した。

| 入力 | 各方針の測定回数 | AUTO 中央値 ms | DETAILED 中央値 ms | AUTO / DETAILED |
|---|---:|---:|---:|---:|
| complex.tiny | 30 | 1,196.710 | 1,204.474 | 0.9936 |
| complex-x64.tiny | 32 | 87,662.883 | 87,329.210 | 1.0038 |
| complex-half.tiny | 32 | 70,125.343 | 70,235.332 | 0.9984 |
| complex-tail.tiny | 32 | 1,441.394 | 1,480.324 | 0.9737 |

両方の解決先が同じなので、この差を AUTO による高速化とは解釈しない。
特に途中切断入力は memoization 無効時の backtracking の費用が大きく、
診断方針だけを比較した今回の計測ではその既存費用を維持している。
