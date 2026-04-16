# DD-006: @enum アノテーションで Choice+Terminal ルールを Java enum に変換

**Date**: 2026-04-16  
**Session**: dge/sessions/2026-04-16-issue14-ubnf-v2.md  
**Status**: Active  

## Decision

`@enum` アノテーション付きの Choice+Terminal ルールを Java `enum` として生成する。

```ubnf
@enum
RecoveryMode ::= 'sync' | 'auto' | 'skip' ;
```

生成 Java:
```java
public enum RecoveryMode {
    SYNC, AUTO, SKIP;

    public static RecoveryMode fromText(String text) {
        return switch (text.toLowerCase()) {
            case "sync" -> SYNC;
            case "auto" -> AUTO;
            case "skip" -> SKIP;
            default -> throw new IllegalArgumentException("Unknown RecoveryMode: " + text);
        };
    }
}
```

`MapperGenerator` は `RecoveryMode` 型フィールドに対して `RecoveryMode.fromText(text)` を生成する。

## Rationale

- UBNF に enum 構文がなく、`RecoveryMode.mode` が `String` 型に推論される
- 手書き `UBNFAST.RecoveryAnnotation(RecoveryMode mode, ...)` は `enum` 型を使用
- `@enum` アノテーション方式は 3層（パーサー・ASTGenerator・MapperGenerator）に影響するが独立して追加可能
- リテラル値 → 定数名の変換規則: `text.toUpperCase().replace('-', '_')` を適用

## How to apply

- `ubnf.ubnf`: `@enum` を Annotation の一つとして追加
- `UBNFAST`: `EnumAnnotation()` レコード追加（`sealed interface Annotation permits ...` に追加）
- `UBNFParsers.java`: `EnumAnnotationParser` 追加
- `ASTGenerator`: `@enum` 検出時に `record` ではなく `enum` クラスを生成するパスを追加
- `MapperGenerator`: `enum` 型フィールドに対して `EnumType.fromText(text)` を生成
