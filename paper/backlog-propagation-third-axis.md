# Backlog: PropagationStopper 第3軸 — syntaxContext

## Status: DEFERRED (MVP では ArgumentExpression で代替)

## 概要

PropagationStopper に第3軸「syntaxContext」を追加し、括弧付き構文が別の括弧付き構文の中に入る場合のコンテキスト制御を行う。

## 現行2軸

| 軸 | 値 | 制御対象 |
|---|---|---|
| TokenKind | consumed / matchOnly | トークン消費モード |
| invertMatch | true / false | マッチ反転 |

## 提案3軸目

| 軸 | 値 | 制御対象 |
|---|---|---|
| syntaxContext | normal / argument / bracket / matchValue / ... | 構文コンテキスト |

## ユースケース

1. **関数引数内 ternary**: `sin($a > 0 ? $a : -$a)` — argument コンテキストなら外側括弧不要
2. **match case 内 ternary**: `match{ cond -> $x > 0 ? 'a' : 'b', ... }` — matchValue コンテキスト
3. **配列添字内 ternary**: `$arr[$cond ? 0 : 1]` — bracket コンテキスト
4. **将来**: lambda, dict literal, named arguments

## MVP の代替策

ArgumentExpression を文法レベルで定義:
```ubnf
ArgumentExpression ::= BoolExpr '?' Expression ':' Expression | Expression ;
SinFunction ::= 'sin' '(' ArgumentExpression ')' ;
```

→ 3軸は不要。文法だけで解決。

## 移行トリガー

文法レベルの回避策（ArgumentExpression, MatchValueExpression, BracketExpression...）が3つ以上溜まったら第3軸にリファクタ。

## 論文への貢献

- Parsec の Reader monad との対応が3軸目で完全になる
- 操作的意味論の状態空間が 4→8 に拡張
- 合成表も 8×8 に拡張
- SLE v5+ で発表可能

## DGE 資料

詳細: `paper/dge-propagation-third-axis.md` (22 Gaps)

## 関連

- `paper/dge-ternary-parens-in-context.md` — 括弧問題の DGE
- PropagationStopper 4兄弟: AllStop, DoConsume, StopInvert, NotProp
