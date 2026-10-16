# Java parser の再帰資源制限

Java runtime は `ParseContext.startParse` で通知された同時実行中の parser frame を
1024 に制限する。組込み parser と通常の生成 parser はこの通知を使う。
上限を超える次の通知は `ParseLimitException` で解析セッションを中断する。
`code()` は `ULX-PARSE-DEPTH-LIMIT`、`limit()` は上限、`offset()` は通知された
TokenKind のカーソルにおける原文のコードポイント位置を返す。

これは通常の構文不一致や `Parsed.FAILED` と別の資源制限である。
生成 mapper の `parse` / `diagnose` でも例外を呼出元へ伝える。
DETAILED と DETAILED_ON_FAILURE の両方に適用し、診断を遅延しても上限は無効化されない。
上限に達したセッションでは memo 利用を無効にし、再解析を試みても同じ例外を返す。
別入力への再試行には新しい `ParseContext` を作る。否定assertion・optional・choiceでも
この資源制限を通常の構文不一致として吸収して成功へ変えることはない。

```java
try (ParseContext context = new ParseContext(StringSource.createRootSource(source))) {
    Parsed parsed = parser.parse(context);
    // 通常の構文不一致は parsed.isFailed() で判定する。
} catch (ParseLimitException failure) {
    // close の復元後に、failure.code() / offset() / limit() を呼出元へ通知する。
}
```

資源制限の例外では通常の parser return を通らないため、`close()` が未完の
transaction を内側から外側へ片付け、明示的に登録された `TransactionalState` と
choice/interleave の選択状態を復元する。通常の成功・rollback 通知や `onEnd` は合成しない。
`onClose` は復元後に実行する。任意の外部副作用や未登録 object の復元は含まれない。
別の未知例外や呼出元の誤った transaction nest に対する既存の close 検査は変更しない。

| 検証対象 | 条件・限界 |
|---|---|
| JVM thread stack | `-Xss1m` 以上を検証対象とする。cold な `-Xint` process でも、通常と8重group wrapperの生成再帰文法を検証する |
| shallow / deep 入力 | 各diagnostics/memo modeで64段を受理し、10,000段をStackOverflowErrorではなく型付き資源制限で中断し、closeの二次nest例外を出さない |
| 既存 frontend | 64 / 96段のUBNF group文法を保持する。frame数は文法構造に依存し、入力の括弧数と同じではない |
| 独自 parser / callback | `startParse` を通知しない独自再帰、任意callback自身の大きなstack消費、JVM設定の異なるstack、AST mapper等の別再帰処理はこの上限の保証外 |
| Java / Rust | Javaは型付き例外によるセッション中断。Rust #506 はrule/expressionの資源上限をnativeな失敗診断として返す。どちらも過深解析を制限するが、同じ数値・同じ文法ネスト深さ・同じ診断文字列は保証しない |

回帰テスト: `ParseDepthLimitTest`、`JavaParseDepthLimitTest`、
`RustUbnfFrontendConformanceTest`、`RustPortabilityConformanceTest`。
