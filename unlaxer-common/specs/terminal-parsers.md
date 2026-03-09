# 端末パーサー仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは入力テキストの文字を直接消費する端末パーサー（Terminal Symbol）の仕様を定義する。POSIX 文字クラスパーサー、ASCII 句読点パーサー、および複合端末パーサー（Word, Number, Quoted 等）を含む。

このドキュメントが **扱わない** 範囲:
- コンビネータ（→ [combinators.md](combinators.md)）
- AST フィルタリング（→ [ast-filtering.md](ast-filtering.md)）

## 関連ドキュメント

- [core-types.md](core-types.md) — Token, TokenKind の仕様
- [combinators.md](combinators.md) — コンビネータ仕様

## 用語定義

- **TerminalSymbol**: 入力テキストの文字を直接消費するパーサーを示すマーカーインタフェース
- **MappedSingleCharacterParser**: 受理文字の集合を文字列で定義する `SingleCharacterParser` の実装

---

## SingleCharacterParser

**クラス**: `org.unlaxer.parser.elementary.SingleCharacterParser`
**実装**: `TerminalSymbol`

### 動作仕様

1. 入力ソースから1コードポイントを先読み（peek）する（MUST）
2. `isMatch(char)` メソッドで受理判定を行う
3. `invertMatch` フラグが `true` の場合、判定を反転する
4. 受理された場合、そのコードポイントを含む `Token` を返す
5. 拒否された場合、空トークンを返す

### 契約

- `isMatch(char)` は **1文字**（BMP 文字）に対する判定を行う
- peek で取得するサイズは `CodePointLength(1)` 固定（MUST）

---

## POSIX 文字クラスパーサー

パッケージ: `org.unlaxer.parser.posix`

すべて `MappedSingleCharacterParser` を継承し、受理文字の集合を文字列で定義する。

| パーサー | 受理文字 | POSIX 相当 |
|---------|---------|-----------|
| `AlphabetParser` | `A-Za-z` | `[:alpha:]` |
| `DigitParser` | `0-9` | `[:digit:]` |
| `AlphabetNumericParser` | `A-Za-z0-9` | `[:alnum:]` |
| `AlphabetUnderScoreParser` | `A-Za-z_` | — |
| `AlphabetNumericUnderScoreParser` | `A-Za-z0-9_` | — |
| `UpperParser` | `A-Z` | `[:upper:]` |
| `LowerParser` | `a-z` | `[:lower:]` |
| `SpaceParser` | 空白文字 | `[:space:]` |
| `BlankParser` | ブランク文字 | `[:blank:]` |
| `PunctuationParser` | 句読点文字 | `[:punct:]` |
| `GraphParser` | 可視文字 | `[:graph:]` |
| `PrintParser` | 印字可能文字 | `[:print:]` |
| `ControlParser` | 制御文字 | `[:cntrl:]` |
| `XDigitParser` | `0-9A-Fa-f` | `[:xdigit:]` |
| `AsciiParser` | ASCII 文字 | `[:ascii:]` |

### 区切り文字パーサー（POSIX パッケージ）

| パーサー | 受理文字 |
|---------|---------|
| `CommaParser` | `,` |
| `ColonParser` | `:` |
| `SemiColonParser` | `;` |
| `DotParser` | `.` |
| `HashParser` | `#` |

### Unicode 動作

- POSIX 文字クラスパーサーは **ASCII 範囲のみ** を対象とする
- 非 ASCII 文字（日本語等）はすべての POSIX パーサーで拒否される
- `isMatch(char)` は `char` 型を受け取るため、BMP（Basic Multilingual Plane）文字のみを処理する

---

## ASCII 句読点パーサー

パッケージ: `org.unlaxer.parser.ascii`

ASCII 句読点・記号文字それぞれに対応する個別パーサーが提供される。すべて `MappedSingleCharacterParser` を継承し、単一文字を受理する。

---

## WordParser

**クラス**: `org.unlaxer.parser.elementary.WordParser`
**実装**: `TerminalSymbol`

### 動作仕様

- 指定された文字列（`word`）と入力ソースの先頭を比較する
- `ignoreCase` フラグが `true` の場合、大文字小文字を区別しない
- マッチした場合、文字列全体を含むトークンを返す

### コンストラクタ

| コンストラクタ | 説明 |
|--------------|------|
| `WordParser(String word)` | 大文字小文字を区別してマッチ |
| `WordParser(String word, boolean ignoreCase)` | `ignoreCase` でマッチモードを指定 |
| `WordParser(Source word)` | Source オブジェクトからマッチ文字列を取得 |

---

## NumberParser

**クラス**: `org.unlaxer.parser.elementary.NumberParser`
**継承**: `LazyChain`

### 受理する数値形式

NumberParser は以下の形式の数値リテラルを受理する:

```
[sign] digits ["." digits] [exponent]
```

具体的には:
- `12` — 整数
- `12.3` — 小数
- `12.` — 小数点で終わる数値
- `.3` — 小数点で始まる数値
- `+12`, `-3.14` — 符号付き
- `1e10`, `1.5e-3` — 指数表記

### 構成

```
Optional(SignParser) → Choice(digits.digits | digits. | digits | .digits) → Optional(ExponentParser)
```

---

## QuotedParser

**クラス**: `org.unlaxer.parser.elementary.QuotedParser`
**継承**: `LazyChain`

### 動作仕様

- 指定された引用符パーサー（`quoteParser`）で囲まれたテキストをパースする
- 構造: `leftQuote → contents → rightQuote`
- `Parts` enum で各部分にアクセスできる: `leftQuote`, `contents`, `rightQuote`

### 関連パーサー

| パーサー | 説明 |
|---------|------|
| `DoubleQuotedParser` | ダブルクォートで囲まれた文字列 |
| `SingleQuotedParser` | シングルクォートで囲まれた文字列 |
| `EscapeInQuotedParser` | 引用符内のエスケープシーケンス処理 |

---

## その他の端末パーサー

パッケージ: `org.unlaxer.parser.elementary`

| パーサー | 説明 |
|---------|------|
| `SingleStringParser` | 単一の文字列リテラルにマッチ |
| `IgnoreCaseWordParser` | 大文字小文字を区別しない単語マッチ |
| `SignParser` | `+` または `-` にマッチ |
| `ExponentParser` | 指数部（`e` または `E` + 省略可能な符号 + 数字列）にマッチ |
| `EmptyParser` | 常に成功し、入力を消費しない |
| `EndOfSourceParser` | 入力の末尾でのみ成功 |
| `StartOfSourceParser` | 入力の先頭でのみ成功 |
| `EndOfLineParser` | 行末でのみ成功 |
| `StartOfLineParser` | 行頭でのみ成功 |
| `EmptyLineParser` | 空行にマッチ |
| `LineTerminatorParser` | 改行文字にマッチ |
| `SpaceDelimitor` | 空白区切り |
| `WildCardCharacterParser` | 任意の1文字にマッチ |
| `WildCardStringParser` | 任意の文字列にマッチ（ターミネータまで） |
| `WildCardLineParser` | 行末までの任意の文字列にマッチ |

---

## 現在の制限事項

- POSIX 文字クラスパーサーは ASCII 範囲に限定されており、Unicode カテゴリベースの判定は行わない
- `SingleCharacterParser.isMatch(char)` は BMP 文字のみ対応。サロゲートペアを構成する文字は個別の `char` として処理される

## 変更履歴

- 2026-03-01: 初版作成
