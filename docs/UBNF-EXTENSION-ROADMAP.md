# UBNF 拡張ロードマップ

> ステータス: draft
> 最終更新: 2026-03-06
> 関連: [specs/ubnf-syntax.md](../specs/ubnf-syntax.md) · [specs/open-questions.md](../specs/open-questions.md)

## 概要

UBNF の表現力を段階的に ANTLR/EBNF 水準に引き上げるためのロードマップ。
各 Tier は独立してリリース可能な単位として設計されている。

---

## 現在の実装状態（基準ライン）

```
✅ grammar 宣言
✅ token 宣言（ParserClass 形式）
✅ token 宣言（UNTIL 形式）  ← v0.1 で実装済み
✅ rule 宣言
✅ シーケンス（暗黙的）
✅ 選択肢 (|)
✅ グループ (...)
✅ 0回以上 {...}
✅ オプション [...]
✅ リテラル '...'
✅ キャプチャ @name
✅ アノテーション @root, @mapping, @whitespace など
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

### T1-2: `+` 演算子（1回以上）

**ステータス**: 未実装
**優先度**: High — EBNF との表記互換性

**設計案**:

```ubnf
// 現状（迂回策）
rule digits ::= digit { digit } ;

// 提案（+ 演算子）
rule digits ::= digit+ ;
rule words  ::= word+ ;
```

**実装方針**:
- `UBNFParsers`: `AnnotatedElementParser` の後に `+` / `?` / `*` を postfix として認識
- `UBNFAST`: `AtomicElement` を拡張するか、`AnnotatedElement` に量化フィールドを追加
- `ParserGenerator`: `OneOrMore(...)` を生成

**影響範囲**: パーサー・AST・マッパー・コード生成器・スナップショット
**推定工数**: M（中）

---

### T1-3: `?` 演算子（0または1回）

**ステータス**: 未実装
**優先度**: High — EBNF との表記互換性

**設計案**:

```ubnf
// 現状（迂回策）
rule maybeSign ::= [ sign ] ;

// 提案
rule maybeSign ::= sign? ;
rule optInit   ::= [ '=' expr ]? ;  // グループに ? をつける
```

**実装方針**: T1-2 と同時に実装（同一の postfix 機構）

---

### T1-4: エスケープシーケンス完全化

**ステータス**: 部分実装（`\\` のみ動作未確認）
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

### T2-1: `NEGATION` キーワード

**ステータス**: 未実装
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

### T2-2: `LOOKAHEAD` キーワード

**ステータス**: 未実装
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

### T2-3: `NEGATIVE_LOOKAHEAD` キーワード

**ステータス**: 未実装
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

### T3-2: `*` postfix（{...} の別記法）

```ubnf
rule items ::= item* ;   // 既存の { item } と同義
```

### T3-3: セマンティック述語

```ubnf
rule id ::= letter (letter | digit)* ?{ !isReservedWord($$) } ;
```

**実装難度**: Very High（ランタイム Java コード埋め込み）

---

## バックログ優先度サマリー

| ID     | 機能                     | 優先度 | 工数 | ステータス  |
|--------|--------------------------|--------|------|-------------|
| T1-1   | `UNTIL` キーワード        | ✅     | —    | **完了**    |
| T1-2   | `+` 演算子               | High   | M    | 未実装      |
| T1-3   | `?` 演算子               | High   | S    | 未実装      |
| T1-4   | エスケープシーケンス      | High   | S    | 部分実装    |
| T2-1   | `NEGATION` キーワード     | Medium | M    | 未実装      |
| T2-2   | `LOOKAHEAD` キーワード    | Med-Lo | S    | 未実装      |
| T2-3   | `NEGATIVE_LOOKAHEAD`      | Med-Lo | M    | 未実装      |
| T3-1   | `{n,m}` 定量化            | Low    | L    | 未実装      |
| T3-2   | `*` postfix               | Low    | S    | 未実装      |
| T3-3   | セマンティック述語        | Low    | XL   | 未実装      |

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
