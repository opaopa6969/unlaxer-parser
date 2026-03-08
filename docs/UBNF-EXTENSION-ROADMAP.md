# UBNF 拡張ロードマップ

> ステータス: draft
> 最終更新: 2026-03-08 (T1-4, T2-1〜T2-3, T3-1, T3-2, T4-1〜T4-9, @scopeTree/@declares/@backref 完了)
> 関連: [specs/ubnf-syntax.md](../specs/ubnf-syntax.md) · [specs/open-questions.md](../specs/open-questions.md)

## 概要

UBNF の表現力を段階的に ANTLR/EBNF 水準に引き上げるためのロードマップ。
各 Tier は独立してリリース可能な単位として設計されている。

---

## 現在の実装状態（基準ライン）

```
✅ grammar 宣言
✅ token 宣言（ParserClass 形式）
✅ token 宣言（UNTIL 形式）
✅ token 宣言（NEGATION 形式）
✅ token 宣言（LOOKAHEAD 形式）
✅ token 宣言（NEGATIVE_LOOKAHEAD 形式）
✅ rule 宣言
✅ シーケンス（暗黙的）
✅ 選択肢 (|)
✅ グループ (...)
✅ 0回以上 {...} / element*
✅ 1回以上 element+
✅ 0または1回 element? / [...]
✅ リテラル '...'（エスケープ \n \t \r \\ \' 対応）
✅ キャプチャ @name
✅ アノテーション @root, @mapping, @whitespace など
✅ アノテーション @scopeTree / @declares / @backref（セマンティックスコープ）
✅ コメント (//)
```

---

## Tier 1 — 必須拡張（次リリース対象）

### T1-1: `UNTIL` キーワード ✅ 実装済み

**ステータス**: `unlaxer-dsl` commit `99acff6` にて実装済み

```ubnf
token CODE_BODY = UNTIL('```')
```

- `UBNFAST.TokenDecl` を sealed interface (`Simple` / `Until`) に変更
- `UntilExpressionParser` / `TokenValueParser` を追加
- `ParserGenerator` が `WildCardStringTerninatorParser("terminator")` を生成

**関連 API**: `org.unlaxer.parser.elementary.WildCardStringTerninatorParser`

---

### T1-2: `+` 演算子（1回以上）✅ 実装済み

**ステータス**: `unlaxer-dsl` v0.2.0 にて実装済み
**優先度**: High — EBNF との表記互換性

```ubnf
// 現状の等価表記
rule digits ::= digit { digit } ;

// 新しい postfix 構文
rule digits ::= digit+ ;
rule words  ::= word+ ;
```

**実装内容**:
- `UBNFAST.OneOrMoreElement(RuleBody body)` を `AtomicElement` sealed hierarchy に追加
- `UBNFParsers.PlusParser`, `PostfixQuantifierParser` を追加
- `AnnotatedElementParser` に `Optional(PostfixQuantifierParser)` を追加
- `UBNFMapper.toAnnotatedElement` で `+` を検出し `OneOrMoreElement` でラップ
- `ParserGenerator` が `new OneOrMore(XxxParser.class)` を生成
- `ASTGenerator`, `MapperGenerator`, `RailroadMain`, `UBNFToRailroad`, `UBNFToBNFConverter` も対応

---

### T1-3: `?` 演算子（0または1回）✅ 実装済み

**ステータス**: `unlaxer-dsl` v0.2.0 にて実装済み（T1-2 と同一 PR）
**優先度**: High — EBNF との表記互換性

```ubnf
// 現状の等価表記
rule maybeSign ::= [ sign ] ;

// 新しい postfix 構文
rule maybeSign ::= sign? ;
```

**実装内容**: T1-2 と同一の postfix 機構。`?` は `UBNFMapper` で既存の `OptionalElement` にラップ。

---

### T1-4: エスケープシーケンス完全化 ✅ 実装済み

**ステータス**: 実装済み
**優先度**: High — リテラル内で `\n` `\t` `\'` が使えない

**設計案**:

```ubnf
token NEWLINE  = '\n'
token TAB      = '\t'
token BACKSLASH = '\\'
token SINGLE_Q = '\''
```

**実装方針**:
- `SingleQuotedParser` の内部で escape 処理を有効化
- `UBNFMapper.stripQuotes()` でエスケープ変換を追加

**影響範囲**: ボトム（パーサーレベル）

---

## Tier 2 — 強く推奨（次々リリース）

### T2-1: `NEGATION` キーワード ✅ 実装済み

**ステータス**: 実装済み
**優先度**: Medium

**設計案**:

```ubnf
token NOT_QUOTE      = NEGATION('"')
token NOT_WHITESPACE = NEGATION(' \t\n\r')
```

**セマンティクス**: 指定文字セットに含まれない単一文字にマッチ

**実装方針**:
- `TokenDecl.Negation` record を追加（`Until` と同様の sealed hierarchy 拡張）
- `UBNFParsers.NegationExpressionParser` を追加
- `ParserGenerator` が `NegationParser("chars")` を生成
- **前提**: `unlaxer-common` に `NegationParser` クラスが必要（または `unlaxer-dsl` 内で提供）

**依存**: `unlaxer-common` への新パーサー追加、または wrapper 生成コード

---

### T2-2: `LOOKAHEAD` キーワード ✅ 実装済み

**ステータス**: 実装済み
**優先度**: Medium-Low

**設計案**:

```ubnf
token FOLLOWED_BY_COLON = LOOKAHEAD(':')
```

**セマンティクス**: パターンが現在位置に存在することを確認するが、消費しない（MatchOnly 相当）

**実装方針**:
- `TokenDecl.Lookahead` record を追加
- `ParserGenerator` が `new MatchOnly(new WordParser("pattern"))` を生成
- **関連 API**: `org.unlaxer.parser.combinator.MatchOnly`

---

### T2-3: `NEGATIVE_LOOKAHEAD` キーワード ✅ 実装済み

**ステータス**: 実装済み
**優先度**: Medium-Low

**設計案**:

```ubnf
token NOT_DOUBLE_SLASH = NEGATIVE_LOOKAHEAD('//')
```

**セマンティクス**: パターンが現在位置に存在 **しない** ことを確認（消費しない）

**実装方針**:
- `unlaxer-common` に `NegativeLookaheadParser` が必要（または unlaxer-dsl で生成コード出力）

---

## Tier 3 — オプション拡張（将来）

### T3-1: `{n,m}` 定量化

```ubnf
rule words ::= word{1,3} ;   // 1〜3回
rule hex   ::= HEX{2}    ;   // ちょうど2回
```

### T3-2: `*` postfix（{...} の別記法）✅ 実装済み

**ステータス**: 実装済み

```ubnf
rule items ::= item* ;   // 既存の { item } と同義
```

### T3-3: セマンティック述語（保留）

```ubnf
rule id ::= letter (letter | digit)* ?{ !isReservedWord($$) } ;
```

**実装難度**: Very High（ランタイム Java コード埋め込み）
**方針**: 要望があるまで保留

---

## バックログ優先度サマリー

| ID     | 機能                     | 優先度 | 工数 | ステータス  |
|--------|--------------------------|--------|------|-------------|
| T1-1   | `UNTIL` キーワード        | ✅     | —    | **完了**    |
| T1-2   | `+` 演算子               | ✅     | —    | **完了**    |
| T1-3   | `?` 演算子               | ✅     | —    | **完了**    |
| T1-4   | エスケープシーケンス      | ✅     | —    | **完了**    |
| T2-1   | `NEGATION` キーワード     | ✅     | —    | **完了**    |
| T2-2   | `LOOKAHEAD` キーワード    | ✅     | —    | **完了**    |
| T2-3   | `NEGATIVE_LOOKAHEAD`      | ✅     | —    | **完了**    |
| T3-1   | `{n,m}` 定量化            | ✅     | —    | **完了**    |
| T3-2   | `*` postfix               | ✅     | —    | **完了**    |
| T3-3   | セマンティック述語        | Low    | XL   | **保留**    |
| T4-1   | `CHAR_RANGE('a','z')`     | ✅     | —    | **完了**    |
| T4-2   | `ANY`                     | ✅     | —    | **完了**    |
| T4-3   | `REGEX('pattern')`        | ✅     | —    | **完了**    |
| T4-4   | `CI('keyword')`           | ✅     | —    | **完了**    |
| T4-5   | セパレータ糖衣 `%`         | ✅     | —    | **完了**    |
| T4-6   | `EOF` / `EMPTY`           | ✅     | —    | **完了**    |
| T4-7   | `ERROR('msg')`            | ✅     | —    | **完了**    |
| T4-8   | `@doc('text')`            | ✅     | —    | **完了**    |
| T4-9   | `@skip`                   | ✅     | —    | **完了**    |

工数目安: S=数時間 / M=1-2日 / L=数日 / XL=1週間以上

---

## 設計方針

### `TokenDecl` sealed interface パターン

`UNTIL` 実装で確立したパターンを `NEGATION` / `LOOKAHEAD` にも適用する：

```java
sealed interface TokenDecl extends UBNFAST
    permits TokenDecl.Simple, TokenDecl.Until,
            TokenDecl.Negation, TokenDecl.Lookahead, TokenDecl.NegativeLookahead {

  String name();
  default String parserClass() { return null; }

  record Simple(String name, String parserClass) implements TokenDecl {}
  record Until(String name, String terminator) implements TokenDecl {}
  record Negation(String name, String excludedChars) implements TokenDecl {}
  record Lookahead(String name, String pattern) implements TokenDecl {}
  record NegativeLookahead(String name, String pattern) implements TokenDecl {}
}
```

### `TokenValueParser` Choice の拡張

```java
public static class TokenValueParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(UntilExpressionParser.class),
            Parser.get(NegationExpressionParser.class),      // T2-1 追加
            Parser.get(LookaheadExpressionParser.class),     // T2-2 追加
            Parser.get(NegativeLookaheadExpressionParser.class), // T2-3 追加
            Parser.get(QualifiedClassNameParser.class)
        );
    }
}
```

### postfix 演算子の AST 設計（T1-2 / T1-3）

`AnnotatedElement` に量化フィールドを追加するか、
`AtomicElement` を wrap する `QuantifiedElement` を新設するかは設計上のトレードオフ。
`AnnotatedElement` 拡張が最小変更。

```java
// 案A: AnnotatedElement にフィールド追加
record AnnotatedElement(
    AtomicElement element,
    Optional<String> captureName,
    Optional<TypeofElement> typeofConstraint,
    Quantifier quantifier   // ← 追加 (ONCE / ZERO_OR_MORE / ONE_OR_MORE / OPTIONAL)
) implements UBNFAST {}

enum Quantifier { ONCE, ZERO_OR_MORE, ONE_OR_MORE, OPTIONAL }

// 案B: QuantifiedElement wrapper
sealed interface AtomicElement permits ..., QuantifiedElement {}
record QuantifiedElement(AtomicElement element, Quantifier q) implements AtomicElement {}
```

---

## 参考リンク

- [specs/ubnf-syntax.md](../specs/ubnf-syntax.md) — 現在の構文仕様
- [specs/open-questions.md](../specs/open-questions.md) — 設計疑問
- `org.unlaxer.parser.elementary.WildCardStringTerninatorParser` — UNTIL の実装基盤
- `org.unlaxer.parser.combinator.MatchOnly` — LOOKAHEAD の実装候補

---

## Tier 5 — 残バックログ（2026-03-08 Session 2 現在）

### 優先度: Low / 保留

| # | 内容 | 規模 | 備考 |
|---|------|------|------|
| T3-3 | セマンティック述語 `?{ !isReservedWord($$) }` | XL | 設計未確定。保留 |
| OQ-002 | セルフホスティング完全化（AST/Mapper 生成対応） | XL | 大規模。優先度低 |

### LSP 拡張（tinyexpression 側） — **新規セクション**

詳細仕様: [`docs/lsp-extensions.md`](./lsp-extensions.md)

#### Phase 1（高優先度 ⭐⭐⭐）

| ID | 内容 | 規模 | 難度 | 備考 |
|----|------|------|------|------|
| LSE-1 | documentSymbol（アウトライン） | M | 低 | ScopeStore.getAllDeclarations API 準備済み |
| LSE-2 | rename（一括リファクタリング） | M | 中 | ScopeStore API 準備済み |

#### Phase 2（中優先度 ⭐⭐）

| ID | 内容 | 規模 | 難度 | 備考 |
|----|------|------|------|------|
| LSE-3 | documentHighlight（同一識別子ハイライト） | S | 低 | ScopeStore.getAllReferences API 準備済み |
| LSE-4 | signatureHelp（パラメータヒント） | M | 中 | AST traverse 必須 |

#### Phase 3（低優先度 ⭐）

| ID | 内容 | 規模 | 難度 | 備考 |
|----|------|------|------|------|
| LSE-5 | codeLens（DAP連携評価表示） | L | 高 | DAP evaluator API 活用 |

### 完了済み（v0.2.2 実装）

| # | 内容 | ステータス |
|---|------|-----------|
| — | go-to-definition: `ScopeStore.resolve()` → LSP Definition | ✅ 実装済み |
| — | find-references: スコープ内全参照を収集 → LSP References | ✅ 実装済み |
| — | ImportDeclaration の `#method` optional 形式 | ✅ 実装済み（Session 2） |

