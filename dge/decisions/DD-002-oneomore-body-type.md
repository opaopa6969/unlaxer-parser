# DD-002: OneOrMoreElement / BoundedRepeatElement の body 型を AtomicElement に変更

**Date**: 2026-04-16  
**Session**: dge/sessions/2026-04-16-issue14-ubnf-v2.md  
**Status**: Active  

## Decision

`UBNFAST.OneOrMoreElement` と `UBNFAST.BoundedRepeatElement` の `body` フィールドを
`RuleBody` から `AtomicElement` に変更する。

```java
// Before
record OneOrMoreElement(RuleBody body) implements AtomicElement {}
record BoundedRepeatElement(RuleBody body, int min, int max) implements AtomicElement {}

// After
record OneOrMoreElement(AtomicElement body) implements AtomicElement {}
record BoundedRepeatElement(AtomicElement body, int min, int max) implements AtomicElement {}
```

## Rationale

- DD-001 の新設計で `OneOrMoreElement ::= PrimaryAtomicElement @body '+' ;`
- `PrimaryAtomicElement @body` の型は `AtomicElement`（サブタイプ）
- `RuleBody` のままでは生成 Mapper が `AtomicElement → RuleBody` の変換（`wrapElementInSequenceBody`）を必要とする
- `(A B C)+` は `OneOrMoreElement(GroupElement(body))` で表現可能 — `RuleBody` 不要
- `wrapElementInSequenceBody` が消え、生成コードがシンプルになる

## How to apply

- `UBNFAST.java` の `OneOrMoreElement` / `BoundedRepeatElement` を変更
- `UBNFMapper.toAnnotatedElement` の `wrapElementInSequenceBody` 呼び出しを削除
- `UBNFMapper.toAtomicElement` で `OneOrMoreElement(body)` の `body` が `AtomicElement` に
- コンシューマー（`ASTGenerator`, `MapperGenerator` 等）の switch 文を更新
