# DD-004: Plan S 達成 — Simple トークン wrapper inner class を ParserTokenEmitter で生成

**Date**: 2026-04-16  
**Session**: dge/sessions/2026-04-16-issue14-ubnf-v2.md  
**Status**: Active  

## Decision

`ParserTokenEmitter` に `Simple` トークン用の wrapper inner class 生成を追加する。

```java
// token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser → 生成:
public static class IdentifierParser extends org.unlaxer.parser.clang.IdentifierParser {
    private static final long serialVersionUID = 1L;
}
```

`ParserRuleEmitter` は `tokenParserMap` の値（完全修飾名）の代わりに wrapper クラス名を使う。

## Rationale

- 現状: `ParserRuleEmitter` が `org.unlaxer.parser.clang.IdentifierParser.class` を直接参照
- 手書き `UBNFMapper` は `findDescendants(token, UBNFParsers.IdentifierParser.class)` を使用
- 生成版 `UBNFParsers` では `IdentifierParser` inner class が存在しないため、`findDescendants` が空を返す
- **これが Plan S（UBNFParsers のみ生成版で置換）のブロッカー**
- wrapper を生成することで、手書き `UBNFMapper` の `findDescendants` 呼び出しが正しく動作する

## How to apply

- `ParserTokenEmitter`: `generateSimpleTokenWrappers(GenContext ctx)` メソッドを追加
- `ParserGenerator`: `generateSimpleTokenWrappers` の結果を出力に含める
- `ParserRuleEmitter.resolveParserClass`: `tokenParserMap` に一致する場合、wrapper クラス名（`toParserClassName(name) + ".class"`）を返すよう変更
- Plan S 達成確認テストを追加
