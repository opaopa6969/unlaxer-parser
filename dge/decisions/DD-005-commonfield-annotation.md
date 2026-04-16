# DD-005: sealed interface 共通メソッドは @commonField 明示アノテーションで宣言

**Date**: 2026-04-16  
**Session**: dge/sessions/2026-04-16-issue14-ubnf-v2.md  
**Status**: Active  

## Decision

sealed interface の全 permit record が共通で持つフィールドを abstract method として宣言するために、
自動推論ではなく `@commonField` 明示アノテーションを採用する。

```ubnf
@commonField(name)
@mapping(TokenDecl)
TokenDecl ::= Simple | Until | Negation | ... ;
```

生成 Java:
```java
sealed interface TokenDecl extends XxxAST permits TokenDecl.Simple, TokenDecl.Until, ... {
    String name();  // @commonField(name) から生成
}
```

複数フィールドの場合: `@commonField(name, parserClass)` のように列挙する。
型は各 inner record の対応フィールドから `inferType` で推論する。

## Rationale

- 自動推論（全 permits の共通フィールドを計算）は全 permits 確定前に計算できない → 二パス必要 → `ASTGenerator` 複雑化
- 自動推論は名前の一致を「判断基準」にするため、偶然一致した場合にサイレントで abstract method が追加される
- 明示アノテーションなら意図が明確、未定義フィールドはコンパイルエラーで早期発見

## How to apply

- `ubnf.ubnf`: `@commonField` を Annotation の一つとして追加
- `UBNFAST`: `CommonFieldAnnotation(List<String> fieldNames)` レコード追加
- `UBNFParsers.java`: `CommonFieldAnnotationParser` 追加
- `ASTGenerator`: 中間 sealed interface 生成時に `@commonField` を検出し abstract method を追加
