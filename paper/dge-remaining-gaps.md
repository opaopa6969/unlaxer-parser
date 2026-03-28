# DGE Session: 残りの機能ギャップ — String連結, inTimeRange, String slice

## テーマ
P4-typed パスで compile-hand と同等のカバレッジを達成するために、残りの機能ギャップを洗い出し、優先順位を決定する。

## キャラクター
- ☕ ヤン・ウェンリー — 「最もシンプルな解は？」「コスト対効果は？」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 前提条件
- P4-typed (sealed switch) パスが primary evaluator として機能中
- UnsupportedOperationException → fallback chain で compile-hand が救済
- 月10億トランザクション本番環境
- 残りギャップ: String連結(+), inTimeRange, inDayTimeRange, String slice

---

## Scene 1: 今泉が問う — 実際に使われているのはどれか

今泉: 「そもそも、この4つの機能って本番で実際にどれくらい使われてるんですか？ fallback が発生してるってことは使われてるんでしょうけど、頻度は？」

ヤン: 「いい質問だね。compile-hand がキャッチしてる時点で、fallback が発生してもユーザーは気づいていない。パフォーマンスの差は出るけど、正確性は保たれてる。」

今泉: 「じゃあ急いで全部やる必要はないですよね。どれが一番 fallback を減らせるんですか？」

リヴァイ: 「String 連結だ。`$firstName + ' ' + $lastName` みたいな式は、名前を扱うルールなら腐るほどある。inTimeRange は特定のドメイン（営業時間判定）だけだ。String slice は見たことない。」

  → **Gap 発見: fallback 発生頻度の定量データがない。ログで計測すべきだが、定性的には String 連結が最多。**

---

## Scene 2: 優先順位 — コスト対効果

ヤン: 「じゃあコスト対効果で考えよう。」

```
| 機能                | 本番利用頻度 | 実装コスト | 優先度 |
|---------------------|-------------|-----------|--------|
| String連結 (+)       | 高          | 中        | P0     |
| inTimeRange          | 中          | 低        | P1     |
| inDayTimeRange       | 中          | 低        | P1     |
| String slice [0:3]   | 低〜なし     | 高        | P2     |
```

リヴァイ: 「String 連結は文法変更が必要だ。StringExpression を StringConcatExpr + StringTerm に分離する。左結合で '+' で連結。inTimeRange/inDayTimeRange は BooleanFactor に追加するだけで済む。」

今泉: 「String slice って Python の `[0:3]` でしょ？ 角括弧のパーサーが必要だし、`:` はテルナリーでも使ってる。パーサー変更のリスクが高くないですか？」

ヤン: 「高い。String slice は P2 で後回し。今回は String 連結 + inTimeRange/inDayTimeRange の3つだけやろう。」

  → **Decision: String slice は SKIP。String連結 + inTimeRange + inDayTimeRange を今回実装。**

---

## Scene 3: MVP — 何をスキップできるか

リヴァイ: 「MVP を定義しろ。」

ヤン: 「String 連結は `+` 演算子の左結合パース。`String.valueOf(left) + String.valueOf(right)` で十分。型チェックは不要 — 全部 String.valueOf で包む。」

今泉: 「Number + Number は既に NumberExpression の '+' で取られるんですよね？ String の '+' と衝突しませんか？」

リヴァイ: 「しない。StringExpression は NumberExpression と別の文法ルール。StringExpression の中で '+' を処理する。StringTerm 同士の '+' だけが String 連結になる。NumberExpression の '+' は NumberTerm 同士だから衝突しない。」

今泉: 「inTimeRange は？」

ヤン: 「既に CalculationContext.inDayTimeRange() と EmbeddedFunction.inTimeRange() がある。P4 の文法に `InTimeRangeFunction` と `InDayTimeRangeFunction` を追加して、BooleanFactor に入れる。evaluator は既存の Java メソッドを呼ぶだけ。」

リヴァイ: 「DayOfWeek は固定キーワードのリテラルだ。'MONDAY'|'TUESDAY'|...|'SUNDAY' を enum 的に解析する。」

---

## Gap List

| # | Gap | 決定 |
|---|-----|------|
| G1 | String連結が P4 文法にない | StringExpression を StringConcatExpr + StringTerm に再構成 |
| G2 | inTimeRange が P4 文法にない | InTimeRangeFunction を追加、BooleanFactor に追加 |
| G3 | inDayTimeRange が P4 文法にない | InDayTimeRangeFunction を追加、BooleanFactor に追加 |
| G4 | DayOfWeek リテラルが P4 文法にない | DayOfWeek ルールを追加 |
| G5 | String slice が P4 文法にない | **SKIP (P2)** — 角括弧パーサーのリスクが高い |
| G6 | fallback 頻度の定量計測がない | 将来課題 — ログ計測を追加 |

## Decision Record

| DR# | 決定 | 理由 |
|-----|------|------|
| DR1 | String連結は `@leftAssoc` で `StringConcatExpr` として実装 | NumberExpression の BinaryExpr と同じパターン |
| DR2 | inTimeRange/inDayTimeRange は BooleanFactor に追加 | boolean を返す関数なので BooleanFactor の一部 |
| DR3 | String slice は今回 SKIP | パーサー変更リスク高、本番利用頻度低 |
| DR4 | evalXxx は 4 クラスに追加 | P4TypedAstEvaluator, P4TypedJavaCodeEmitter, P4DefaultJavaCodeEmitter, P4TemplateJavaCodeEmitter |

## Action Items

- [ ] UBNF 文法に StringConcatExpr, StringTerm, InTimeRangeFunction, InDayTimeRangeFunction, DayOfWeek を追加
- [ ] unlaxer-parser で `mvn install` して P4 コード再生成
- [ ] tinyexpression の 4 つの GGP クラスに evalXxx メソッドを追加
- [ ] テスト実行して全テスト green 確認
- [ ] backend-coverage-matrix.md を更新
