# DD-003: ZeroOrMorePostfix / OptionalPostfix は不要 — 既存型に統合

**Date**: 2026-04-16  
**Session**: dge/sessions/2026-04-16-issue14-ubnf-v2.md  
**Status**: Active  

## Decision

`element *` と `element ?` の後置形式に新しい UBNFAST 型を作らない。
既存の `RepeatElement` / `OptionalElement` にそれぞれ統合する。

- `element *` → `RepeatElement(body: AtomicElement)`（`{ body }` と同型）
- `element ?` → `OptionalElement(body: RuleBody)`（`[ body ]` と同型）

## Rationale

- `{ body }` (brace form) と `element *` は意味が同じ（0回以上の繰り返し）
- `[ body ]` (bracket form) と `element ?` は意味が同じ（省略可能）
- 手書き `UBNFMapper.toAnnotatedElement` も PostfixQuantifier `?` → `OptionalElement`、`*` → `RepeatElement` にマッピングしている
- 新型を追加すると switch 文の全コンシューマーに変更が波及し、コスト大

## How to apply

- `ubnf.ubnf` に `ZeroOrMorePostfix`/`OptionalPostfix` ルールは追加しない
- `element *` はパーサーが `RepeatElement(body)` を返す
- `element ?` はパーサーが `OptionalElement(body)` を返す
- UBNFAST 変更なし
