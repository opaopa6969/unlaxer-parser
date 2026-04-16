# DD-001: AtomicElement 再構造 — (b) PrimaryAtomicElement パターン採用

**Date**: 2026-04-16  
**Session**: dge/sessions/2026-04-16-issue14-ubnf-v2.md  
**Status**: Active  

## Decision

`ubnf.ubnf` の `AtomicElement` を `PrimaryAtomicElement + optional suffix` 構造に再設計する。
後置量化子（`+`, `?`, `*`, `{n,m}`, `%sep`）を `AnnotatedElement` から除去し、
`AtomicElement` の variants として組み込む。

**（Round 8 + auto-merge で改訂）**

```ubnf
// PrimaryAtomicElement 廃止 → QuantifiedRef に統合
// 先頭トークンで即コミット → backtracking 爆発を回避
AtomicElement ::=
    GroupElement          // ( → 即コミット
  | OptionalElement       // [ → 即コミット
  | RepeatElement         // { → 即コミット
  | ErrorElement          // 'ERROR' → 即コミット
  | TerminalElement       // ' → 即コミット
  | QuantifiedRef ;       // IDENTIFIER → 後置チェック（1回のみ）

// @mapping なし透過: UBNFMapper が suffix を見て型を決定
// UBNFAST は変更なし（QuantifiedRef は AST ノードにならない）
QuantifiedRef ::=
    [ IDENTIFIER @namespace '.' ] IDENTIFIER @name
    [ '+' | '?' | '*'
    | '{' Digits @min [ ',' [ Digits @max ] ] '}'
    | '%' AtomicElement @separator
    ] ;

AnnotatedElement ::=
    [ TypeofElement @typeofConstraint ]
    AtomicElement @element
    [ '@' IDENTIFIER @captureName ] ;
```

## Rationale

- 現行: `AnnotatedElement` が `PostfixQuantifier` を内包するが `@mapping params` に含まれないため、生成 Mapper が量化子昇格ロジックを持てない（self-describing に反する）
- 選択肢 (a): `@mapping` に quantifier 追加 → `UBNFAST.AnnotatedElement` シグネチャ変更が波及
- 選択肢 (b): `AtomicElement` に fold → UBNFAST は変更最小（既に正しい型を持っていた）
- 選択肢 (c): `MapperGenerator` に特殊ケース追加 → 技術的負債
- **破壊的変更 OK（ユーザー少）** の前提で (b) を採用

## How to apply

- `ubnf.ubnf`: `QuantifiedRef` 新設（`PrimaryAtomicElement` 廃止）、`AnnotatedElement` から `PostfixQuantifier`/`SeparatedBy` 除去
- `UBNFParsers.java`: `AtomicElementParser` を先頭トークン即コミット + `QuantifiedRefParser` に書き直し（atomic コミット）
- `UBNFMapper.java`: `toAtomicElement` で `QuantifiedRef` → suffix に応じて `OneOrMoreElement` 等を返す、`wrapElementInSequenceBody` 削除
- **Backtracking 回避**: 先頭トークンで即コミット。`QuantifiedRef` のみ IDENTIFIER を1回パースして suffix を判定
