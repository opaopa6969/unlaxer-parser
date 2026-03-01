---

[← 10 - デバッグ・リスナーシステム](./10-debug-system.md) | [目次](./index.md)

# 11 - 2.4.0互換レイヤーと診断拡張

## 概要

2.4.0 では、既存プロジェクト（TinyExpression など）を段階移行しやすくするために、
互換APIと診断拡張方針を明文化します。

## 互換API（legacyコード向け）

`org.unlaxer.Token`:

- 互換フィールド: `tokenString`, `tokenRange`（`@Deprecated`）
- 互換メソッド: `getToken()`, `getTokenRange()`, `getRangedString()`
- 互換コンストラクタ:
  - `Token(TokenKind, List<? extends Token>, Parser)`
  - `Token(TokenKind, List<? extends Token>, Parser, int)`
  - `Token(TokenKind, Source, Parser, int)`
- 互換メソッド: `newCreatesOf(List<? extends Token>)`

`org.unlaxer.TypedToken`:

- 互換メソッド: `newCreatesOfTyped(List<? extends Token>)`

`org.unlaxer.StringSource`:

- 互換コンストラクタ: `StringSource(String)`（root source 扱い）

`org.unlaxer.listener.TransactionListener`:

- 新署名 `TokenList` を基本にしつつ、旧署名 `List<Token>` を default メソッドとして残す

`org.unlaxer.RangedString`:

- legacy 参照向けに復活（`StringSource` ベースの薄い互換ラッパ）

## 互換レイヤーの位置付け

- 新規実装は `Source` / `CursorRange` / `TokenList` を直接使う
- 互換APIは既存コードの移行期間専用
- 段階移行後は `@Deprecated` API を縮退対象にする

## 診断拡張: 最深失敗位置 + スタック情報

`Ln1,col1` 問題の改善のため、位置だけではなく「失敗時の文脈」を保持する方針を採用します。

提案データ:

- `farthestOffset`
- `maxReachedStackElements`（最深到達時の parser stack snapshot）
- `expected`（期待トークン/期待ルール）
- `contextWindow`（前後テキスト）

更新ルール:

1. より深い `offset` で更新
2. 同一 `offset` は stack 深度が深い方を優先
3. 同率は上位 N 件を保持（診断候補）

実装メモ（2.4.0 ローカル実装）:

- `TerminalSymbol#expectedDisplayText()` を導入し、terminal parser が期待文言を返せるようにした
- `TerminalSymbol#expectedDisplayTexts()` を追加し、terminal parser が複数候補を返せるようにした
- `SingleCharacterParser` / `WordParser` が既定実装を提供
- `SuggestableParser` が候補全体（例: `if`, `match`, `sin/cos/tan`）を expected として返す
- `SingleStringParser` は単一文字記号を自動推定して expected を返す（例: `<`, `>`, `:`, `?`, `$`）
- `ParseFailureDiagnostics.ExpectedHintCandidate` を追加し、`displayHint` に加えて
  `parserClassName` / `parserQualifiedClassName` / `parserDepth` / `terminal` を取得可能にした
- `Choice` 失敗時は子parserの expected を集約（`'sin','cos','tan'` のような候補）
- 集約探索は再帰ではなく反復BFS + 深さ上限 + visited で `StackOverflowError` を回避

## LSP/DAP への効果

- LSP: 構文エラー位置を実際の失敗点に近づける
- DAP: 現在位置だけでなく、失敗候補の根拠（期待/stack）を表示可能

## 次ステップ

1. `unlaxer-common` に deepest failure API を正式追加
2. TinyExpression/LSP のヒューリスティクス位置推定を段階置換
3. 互換API利用箇所を可視化し、段階的に native API へ移行
4. base combinator に `@FirstClass` 相当の明示メタデータを付与し、静的annotationと動的providerを併用できる設計にする
