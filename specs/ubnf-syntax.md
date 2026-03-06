# UBNF 文法の形式仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは UBNF（Unlaxer BNF）文法の構文仕様を形式的に定義する。grammar ブロック、グローバル設定、トークン宣言、ルール宣言、要素構文、予約語を含む。

このドキュメントが **扱わない** 範囲:
- アノテーションのセマンティクス（→ [annotations.md](annotations.md)）
- トークン解決のランタイム動作（→ [token-resolution.md](token-resolution.md)）

## 関連ドキュメント

- [overview.md](overview.md) — プロジェクト概要
- [annotations.md](annotations.md) — アノテーション仕様
- [token-resolution.md](token-resolution.md) — トークン解決仕様
- [grammar/ubnf.ubnf](../grammar/ubnf.ubnf) — セルフホスティング文法定義
- [docs/UBNF-EXTENSION-ROADMAP.md](../docs/UBNF-EXTENSION-ROADMAP.md) — 拡張ロードマップ（UNTIL, NEGATION, +, ? など）

## 用語定義

- **UBNF**: Unlaxer BNF。unlaxer-dsl が定義する文法記述言語
- **grammar ブロック**: 1つの文法全体を囲む最上位構造
- **キャプチャ**: `@name` 構文で要素に付ける名前。マッピングで使用される

---

## ファイル構造

UBNF ファイルは1つ以上の grammar ブロックから構成される（MUST）。

```
UBNFFile ::= { GrammarDecl }
```

1つの `.ubnf` ファイル内のすべての grammar ブロックが処理される（最初のブロックだけではない）。

---

## grammar ブロック

```
GrammarDecl ::= 'grammar' IDENTIFIER '{' { GlobalSetting } { TokenDecl } { RuleDecl } '}'
```

- `IDENTIFIER` は文法名。生成されるクラス名のプレフィックスとなる
- ブロック内の宣言順序: グローバル設定 → トークン宣言 → ルール宣言

---

## グローバル設定

```
GlobalSetting ::= '@' IDENTIFIER ':' SettingValue
SettingValue  ::= DottedIdentifier | '{' { KeyValuePair } '}'
KeyValuePair  ::= IDENTIFIER ':' STRING
```

### 既知のグローバル設定

| キー | 値の形式 | 説明 |
|------|---------|------|
| `@package` | ドット区切り識別子 | 生成コードの Java パッケージ名 |
| `@whitespace` | 識別子（例: `javaStyle`） | デフォルトの空白処理モード |
| `@comment` | ブロック値 `{ line: '...' }` | コメント構文定義 |

---

## トークン宣言

```
TokenDecl ::= 'token' IDENTIFIER '=' CLASS_NAME
```

- `IDENTIFIER`: トークン名。ルール本体で参照される
- `CLASS_NAME`: unlaxer-common のパーサークラス名
- 生成コードでは `Parser.get(ParserClass.class)` に変換される

### 例

```
token IDENTIFIER   = IdentifierParser
token STRING       = SingleQuotedParser
token UNSIGNED_INTEGER = NumberParser
```

---

## ルール宣言

```
RuleDecl ::= { Annotation } IDENTIFIER '::=' RuleBody ';'
```

- ルール名は `IDENTIFIER`
- ルール本体は `::=` の後に記述
- ルールはセミコロン `;` で終端する（MUST）。これによりルール境界の曖昧さが排除される

---

## ルール本体

### 選択（Choice）

```
RuleBody   ::= ChoiceBody
ChoiceBody ::= SequenceBody { '|' SequenceBody }
```

`|` で区切られた複数の選択肢。unlaxer-common の `Choice` コンビネータに対応する。

### 連接（Sequence）

```
SequenceBody ::= { AnnotatedElement }
```

要素の連続。unlaxer-common の `Chain` コンビネータに対応する。

### 要素（Element）

```
AnnotatedElement ::= AtomicElement [ '@' IDENTIFIER ]
```

各要素にはオプションでキャプチャ名（`@name`）を付けられる。キャプチャ名は `@mapping` アノテーションの `params` と対応する。

### 基本要素（Atomic Element）

```
AtomicElement ::= GroupElement | OptionalElement | RepeatElement | TerminalElement | RuleRefElement
```

| 要素 | 構文 | 説明 | unlaxer-common 対応 |
|------|------|------|-------------------|
| グループ | `( body )` | サブ式のグループ化 | — |
| 省略可能 | `[ body ]` | 0回または1回 | `Optional` |
| 繰り返し | `{ body }` | 0回以上 | `ZeroOrMore` |
| ターミナル | `'keyword'` | リテラル文字列 | `WordParser` |
| ルール参照 | `IDENTIFIER` | 他のルールまたはトークンの参照 | パーサークラス参照 |

### グループ

```
GroupElement ::= '(' RuleBody ')'
```

括弧内に選択や連接を含められる。

### 省略可能

```
OptionalElement ::= '[' RuleBody ']'
```

角括弧で囲まれた要素は省略可能。

### 繰り返し

```
RepeatElement ::= '{' RuleBody '}'
```

波括弧で囲まれた要素は0回以上繰り返される。

### リテラル文字列

```
TerminalElement ::= STRING
```

シングルクォートで囲まれた文字列リテラル。

### ルール参照

```
RuleRefElement ::= IDENTIFIER
```

他のルール名またはトークン名への参照。

---

## ドット区切り識別子

```
DottedIdentifier ::= IDENTIFIER { '.' IDENTIFIER }
```

パッケージ名等で使用される。例: `org.unlaxer.dsl.bootstrap.generated`

---

## キャプチャ構文

```
element @captureName
```

要素の直後に `@` + 識別子でキャプチャ名を付ける。同じキャプチャ名が複数回出現する場合、その値はリスト（`List`）として収集される。

### 例

```
{ ',' IDENTIFIER @paramNames }
```

上記では `IDENTIFIER` が繰り返されるたびに `paramNames` リストに追加される。

---

## コメント

UBNF ファイル内では `//` で始まる行コメントが使用できる（`@comment: { line: '//' }` 設定による）。

---

## 予約語

以下のキーワードは UBNF 文法で予約されている:

- `grammar`
- `token`
- `params`

---

## 現在の制限事項

- 繰り返しの回数指定（`{1,3}` 等）は未サポート
- 否定（Not）に相当する構文は UBNF レベルでは提供されていない
- `OneOrMore`（1回以上）に直接対応する構文糖衣は未提供（`{ body }` は ZeroOrMore のみ）

## 変更履歴

- 2026-03-01: 初版作成
