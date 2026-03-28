# DGE Session: P4TypedAstEvaluator での MethodInvocation と External 呼び出しの実装

## テーマ
P4TypedAstEvaluator が現在 UnsupportedOperationException を投げている MethodInvocationExpr / External*InvocationExpr を実装し、fallback を不要にする。

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 前提条件
- P4TypedAstEvaluator は sealed-interface switch dispatch による型安全な AST 評価器
- 現在 MethodInvocationExpr, External*InvocationExpr で UnsupportedOperationException → fallback to reflection-based GeneratedP4ValueAstEvaluator
- MethodInvocationExpr の `name` フィールドはメソッド名のみ（例: "identity"）
- External*InvocationExpr の `name` フィールドは最初の identifier のみ
- 引数やクラス参照は source formula テキストから取得する必要がある
- GeneratedP4ValueAstEvaluator に既に method resolution / argument binding / external invocation のロジックが存在する
- TinyExpressionP4Mapper.sourceSpanOf() でノードのソース位置を取得可能

## 先輩 (ナレーション)
P4TypedAstEvaluator は sealed-interface の switch dispatch で型安全に AST を評価する新世代のエバリュエータだ。四則演算、変数参照、if/else、match/case、Math 関数、文字列メソッドまでは実装済みで、AstEvaluatorCalculator の最優先パスとして動作している。しかし `call identity(1)` のようなメソッド呼び出しや `external returning as number org.example.Fee#calculate($age)` のような外部 Java メソッド呼び出しに遭遇すると UnsupportedOperationException を投げ、reflection-based の GeneratedP4ValueAstEvaluator にフォールバックする。このフォールバックは動作するが、型安全性の利点を失い、デバッグが困難になり、パフォーマンスも劣化する。ゴールは fallback を完全に排除することだ。

---

## Scene 1: MethodInvocation の仕組み（メソッド定義の解決）

今泉: 「そもそも、MethodInvocationExpr のノードには何が入ってるんですか？ name だけ？ 引数は？ メソッドボディは？」

ヤン: 「P4 AST の MethodInvocationExpr は `record MethodInvocationExpr(String name)` だ。name にはメソッド名だけが入っている。例えば `call identity(1)` なら name は `"identity"`。引数もボディも AST ノードには含まれない。」

今泉: 「じゃあ引数とメソッドボディはどこにあるんですか？」

ヤン: 「ソースフォーミュラの中だ。メソッド定義は `float identity($amount as number){ $amount }` のようにソーステキストに書かれている。引数は `call identity(1)` の括弧の中にある。GeneratedP4ValueAstEvaluator は sourceSpanOf() でノードのソース位置を取得し、そこから引数を parse している。」

  → **Gap G1: P4TypedAstEvaluator は現在 sourceFormula を保持していない。メソッド定義の解決にはソーステキストへのアクセスが必須。**

千石: 「GeneratedP4ValueAstEvaluator.findMethodSource() がこの解決ロジックを持っています。ソーステキストからメソッド名で検索し、型宣言・パラメータ・ボディを抽出します。」

リヴァイ: 「つまり P4TypedAstEvaluator に sourceFormula を渡せば、既存ロジックを再利用できるってことだな。新しいロジックを発明する必要はない。」

  → **Gap G2: GeneratedP4ValueAstEvaluator の method resolution ロジックは private static メソッド群。P4TypedAstEvaluator から呼べない。可視性変更またはヘルパー抽出が必要。**

---

## Scene 2: 引数のバインディングとスコープ

今泉: 「引数のバインディングってどうやるんですか？ `call identity($x)` で $x を評価して、メソッドボディ内の $amount にバインドする、って話ですよね？」

ヤン: 「その通り。GeneratedP4ValueAstEvaluator は以下の手順を踏んでいる:
1. sourceSpanOf() で呼び出しノードのソーステキストを取得
2. 引数式を parse（カンマ区切り分割）
3. メソッド定義のパラメータ仕様を parse（`$amount as number` → name="amount", type=number）
4. 各引数式を評価して値を得る
5. パラメータ名 → 値 のマップを作る
6. ScopedCalculationContext で元の context をラップ
7. メソッドボディを新しい context で評価」

千石: 「ScopedCalculationContext は delegate パターンで、ローカル変数を優先しつつ外側の変数も参照できます。これがスコープの push/pop に相当します。」

リヴァイ: 「ScopedCalculationContext も GeneratedP4ValueAstEvaluator の private inner class だ。これも使えるようにする必要がある。」

  → **Gap G3: ScopedCalculationContext が GeneratedP4ValueAstEvaluator の private inner class。P4TypedAstEvaluator から利用するには抽出が必要。**

ヤン: 「最もシンプルなアプローチは、GeneratedP4ValueAstEvaluator の static メソッド群を package-private にして P4TypedAstEvaluator から直接呼ぶことだな。同じパッケージにいるんだから。」

今泉: 「でもメソッドボディの評価は P4TypedAstEvaluator 自身で行いたいですよね？ reflection-based evaluator に委譲したら意味がない。」

ヤン: 「メソッドボディは式テキスト（例: `$amount`）だから、P4Mapper.parse() で新たに AST に変換して P4TypedAstEvaluator.eval() で評価すればいい。ただし ScopedCalculationContext で引数をバインドした状態で。」

  → **Gap G4: メソッドボディの式テキストを P4Mapper.parse() → P4TypedAstEvaluator.eval() で評価するパスが未実装。parse 失敗時のフォールバック戦略も未定。**

---

## Scene 3: External 呼び出し（Java リフレクション）

今泉: 「External 呼び出しは全く別の話ですよね？ Java のメソッドを reflection で呼ぶんですよね。」

ヤン: 「構造は似ている。`external returning as number org.example.Fee#calculate($age, $taxRate)` の場合:
1. クラス名 (`org.example.Fee`) とメソッド名 (`calculate`) を parse
2. Class.forName() でクラスをロード
3. 引数を評価（$age, $taxRate → 値）
4. リフレクションでメソッドを呼ぶ
5. 戻り値を期待する型に変換」

千石: 「注意点があります。External メソッドの第一引数は常に CalculationContext です。実際のユーザー引数は第二引数以降にマッピングされます。」

リヴァイ: 「External*InvocationExpr の name フィールドにはクラス名の最初の部分しか入ってない可能性がある。フルの式テキストが必要だ。」

  → **Gap G5: External invocation の完全なクラス#メソッド参照と引数は AST ノードに含まれない。sourceSpanOf() でソーステキストから取得する必要がある。**

今泉: 「GeneratedP4ValueAstEvaluator は external をどう処理してるんですか？」

ヤン: 「実は GeneratedP4ValueAstEvaluator には external 専用のコードはない。external 式は AstEmbeddedExpressionRuntime.tryEvaluate() 経由で JavaCodeCalculatorV3 に丸投げされている。compile-hand パスが external を処理する。」

  → **Gap G6: P4-reflection パスでも external は直接処理されていない。compile-hand にフォールバックしている。P4TypedAstEvaluator で external を native 実装するなら、reflection 呼び出しロジックを新規実装するか、同様に compile-hand に委譲するかの選択が必要。**

千石: 「CalculatorImplTest のテストケースでは external 呼び出しが多数テストされています。TestSideEffector クラスに booleanToFloatMethod, salary, beforeSupecifiedDate, getAge, getYear メソッドがあります。」

---

## Scene 4: Side effect の取り扱い

今泉: 「Side effect って何ですか？ `call with side effect` みたいな構文ですか？」

ヤン: 「外部メソッドを呼んで、その副作用（DB書き込みなど）を利用するケース。`external returning as number : TestSideEffector#setBlackList($result)` のように書く。メソッド内で CalculationContext を変更したり、外部システムに書き込んだりする。」

リヴァイ: 「AST 評価の中で副作用を起こすのは汚い。だが動かす必要がある。既存のテストが通る必要がある。」

  → **Gap G7: Side effect のある external 呼び出しは CalculationContext を第一引数に受ける。P4TypedAstEvaluator が context を渡す機構が必要。既に context フィールドを保持しているので、これ自体は問題ない。**

---

## Scene 5: P4TypedAstEvaluator への追加設計

ヤン: 「設計をまとめよう。P4TypedAstEvaluator に追加するものは:

1. **コンストラクタ引数の追加**: `sourceFormula` (String) と `classLoader` (ClassLoader) を追加
2. **evalMethodInvocationExpr の実装**:
   - sourceFormula から method definition を検索（findMethodSource ロジック）
   - sourceSpanOf() で invocation ソーステキストから引数を取得
   - 引数を評価してパラメータにバインド
   - ScopedCalculationContext でメソッドボディを評価
3. **evalExternal*InvocationExpr の実装**:
   - sourceSpanOf() でフル式テキストを取得
   - クラス名#メソッド名を parse
   - 引数を評価
   - reflection でメソッド呼び出し
   - 戻り値を型変換

ただし AstEvaluatorCalculator 側で P4TypedAstEvaluator を生成する箇所も修正が必要だ。sourceFormula と classLoader を渡す必要がある。」

今泉: 「GeneratedP4ValueAstEvaluator の既存コードをどこまで再利用するんですか？ コピペは最悪ですよね。」

ヤン: 「2つのアプローチがある:
- (A) GeneratedP4ValueAstEvaluator の static メソッドを package-private にして直接呼ぶ
- (B) 共通ロジックをヘルパークラスに抽出する

(A) の方がシンプルだ。同じパッケージ内だから可視性変更だけで済む。」

リヴァイ: 「(A) でいい。動くものを早く見せろ。リファクタリングは後でいい。」

  → **Decision D1: GeneratedP4ValueAstEvaluator の method resolution / argument parsing / ScopedCalculationContext を package-private にして P4TypedAstEvaluator から再利用する。**

千石: 「ただし、メソッドボディの評価は P4TypedAstEvaluator 自身で行うべきです。GeneratedP4ValueAstEvaluator に委譲すると型安全性の利点が失われます。」

ヤン: 「メソッドボディの評価手順:
1. findMethodSource() でボディ式テキストを取得
2. TinyExpressionP4Mapper.parse() で P4 AST に変換
3. 新しい P4TypedAstEvaluator（ScopedContext 付き）で eval()
4. parse 失敗時は AstEmbeddedExpressionRuntime に委譲（最終手段）」

  → **Decision D2: メソッドボディは TinyExpressionP4Mapper.parse() → P4TypedAstEvaluator.eval() で評価。parse 失敗時は embedded runtime にフォールバック。**

---

## Scene 6: テスト戦略

千石: 「テストは3段階です:

1. **P4TypedAstEvaluatorTest**: ユニットテスト。直接 AST ノードを作って evalMethodInvocationExpr / evalExternal*InvocationExpr を呼ぶ。ただし sourceFormula が必要なので、新しいコンストラクタ経由で設定する。

2. **P4AstEvaluatorCalculatorTest**: 統合テスト。CalculatorImplTest を継承。formulas に `call identity(1)` や `external returning as number ...` を含むテストケースが既に存在する。P4TypedAstEvaluator が処理できれば fallback せずに通る。

3. **_astEvaluatorRuntime マーカーの確認**: 既存テストで `_astEvaluatorRuntime` が `"p4-typed"` になることを確認。fallback が発生していないことの証明。」

リヴァイ: 「P4AstEvaluatorCalculatorTest で全テストが green なら、それが最も信頼できる証拠だ。ユニットテストは補助的。」

  → **Decision D3: 実装の正しさは P4AstEvaluatorCalculatorTest (CalculatorImplTest 継承) で検証。MethodInvocation / External を含む既存テストケースが p4-typed パスで通ることを確認。**

---

## Scene 7: MVP 判断

ヤン: 「MVP のスコープを決めよう。」

今泉: 「全部一度にやるんですか？ MethodInvocation と External は別々にできませんか？」

ヤン: 「MethodInvocation が先だ。理由:
1. 既存の P4AstEvaluatorCalculatorTest に method invocation テストがある
2. external は class loading と reflection があるのでスコープが大きい
3. MethodInvocation が動けば external も同じパターンで実装できる」

リヴァイ: 「1回で全部やれ。中途半端に分けると結合テストの意味がない。」

千石: 「同意です。AstEvaluatorCalculator のフォールバックチェーンがあるので、実装が不完全でも既存テストは壊れません。UnsupportedOperationException を catch して reflection-based にフォールバックするコードが既にあります。」

  → **Decision D4: MethodInvocation と External を同時に実装する。フォールバックチェーンがあるため、部分的な実装でも既存テストは壊れない。**

ヤン: 「ただし external の reflection 呼び出しは GeneratedP4ValueAstEvaluator の既存コードを見ると AstEmbeddedExpressionRuntime 経由で compile-hand に丸投げしている。P4TypedAstEvaluator でもとりあえず同じ委譲パターンでいい。native reflection 実装は次のフェーズでもいい。」

今泉: 「それだと external は結局 compile-hand に委譲するんですよね？ P4-typed パスで完結しないですよね？」

ヤン: 「嫌な言い方をすれば、P4TypedAstEvaluator の中で compile-hand を呼ぶことで UnsupportedOperationException を回避する。フォールバックチェーンの階層が1段減るだけだが、P4TypedAstEvaluator が「処理できない」と言わなくなる点が重要だ。」

リヴァイ: 「結果的に同じ値が返るなら、実装の内部構造はどうでもいい。テストが green なら出荷しろ。」

  → **Decision D5: External invocation は MVP では source formula テキストから直接 reflection 呼び出しを行う。GeneratedP4ValueAstEvaluator と同じパターン（sourceSpanOf + parse + reflection）で実装。将来的に native reflection 実装に切り替え可能。**

---

## Gap List

| ID | Gap | 解決方法 |
|----|-----|---------|
| G1 | P4TypedAstEvaluator が sourceFormula を保持していない | コンストラクタに sourceFormula パラメータを追加 |
| G2 | GeneratedP4ValueAstEvaluator の method resolution ロジックが private | package-private に変更して再利用 |
| G3 | ScopedCalculationContext が private inner class | package-private クラスとして抽出またはアクセサ提供 |
| G4 | メソッドボディの P4Mapper.parse → eval パスが未実装 | evalMethodInvocationExpr 内で TinyExpressionP4Mapper.parse() を呼び eval() |
| G5 | External invocation の式テキストが AST ノードに含まれない | sourceSpanOf() でソーステキストから取得 |
| G6 | External の reflection 呼び出しロジックが P4-typed パスにない | sourceFormula テキストから parse してリフレクション呼び出しを実装 |
| G7 | Side effect external が CalculationContext を第一引数に必要 | context フィールドを既に保持。リフレクション呼び出し時に渡す |

## Decision Record

| ID | Decision | 根拠 |
|----|----------|------|
| D1 | GeneratedP4ValueAstEvaluator の static メソッドを package-private にして再利用 | 同一パッケージ、コード重複回避、シンプルさ優先 |
| D2 | メソッドボディは P4Mapper.parse → P4TypedAstEvaluator.eval で評価 | 型安全性を維持、fallback は最終手段 |
| D3 | 検証は P4AstEvaluatorCalculatorTest (CalculatorImplTest 継承) で行う | 既存テストケースが最も包括的 |
| D4 | MethodInvocation と External を同時に実装 | フォールバックが安全網、結合テストの意味 |
| D5 | External は source formula から直接 reflection 呼び出し | GeneratedP4ValueAstEvaluator と同じパターン、動くものを早く |

## Action Items

| # | 内容 | 対象ファイル |
|---|------|-------------|
| 1 | P4TypedAstEvaluator に sourceFormula, classLoader フィールド追加、コンストラクタ拡張 | P4TypedAstEvaluator.java |
| 2 | GeneratedP4ValueAstEvaluator の findMethodSource, parseMethodParameterSpecs, ScopedCalculationContext 等を package-private に変更 | GeneratedP4ValueAstEvaluator.java |
| 3 | evalMethodInvocationExpr を実装: method resolution → argument binding → scoped eval | P4TypedAstEvaluator.java |
| 4 | evalExternal*InvocationExpr を実装: sourceSpanOf → parse class#method → reflection | P4TypedAstEvaluator.java |
| 5 | AstEvaluatorCalculator の P4TypedAstEvaluator 生成箇所を修正: sourceFormula, classLoader を渡す | AstEvaluatorCalculator.java |
| 6 | テスト実行: P4TypedAstEvaluatorTest, P4AstEvaluatorCalculatorTest | テスト |
| 7 | backend-coverage-matrix.md を更新 | docs/backend-coverage-matrix.md |
