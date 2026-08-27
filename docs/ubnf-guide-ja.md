[English](./ubnf-guide.md) | [日本語](./ubnf-guide-ja.md)

---

# UBNF 言語ガイド

**バージョン**: 3.0.14

UBNF（Unlaxer BNF）は unlaxer-parser の文法定義言語です。認識セマンティクスだけでなく、コード生成の意図も表現するために設計された、型付きでアノテーション駆動の EBNF 拡張です。

---

## 目次

- [文法ファイル構造](#文法ファイル構造)
- [トークン宣言](#トークン宣言)
- [ルール宣言](#ルール宣言)
- [構文リファレンス](#構文リファレンス)
- [アノテーション](#アノテーション)
- [演算子の結合性](#演算子の結合性)
- [空白とコメントの処理](#空白とコメントの処理)
- [文法インポート](#文法インポート)
- [機能マトリックス](#機能マトリックス)
- [完全な例: TinyCalc](#完全な例-tinycalc)

---

## 文法ファイル構造

`.ubnf` ファイルは1つ以上の `grammar` ブロックを含みます：

```ubnf
grammar GrammarName {
  // グローバル設定
  @package: com.example.pkg
  @whitespace: javaStyle
  @comment: { line: '//' }

  // トークン宣言
  token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
  token NUMBER     = org.unlaxer.parser.elementary.NumberParser

  // ルール宣言
  @root
  Start ::= ... ;

  Rule ::= ... ;
}
```

文法ブロックは別の文法からルールを `@import` できます：

```ubnf
@import base from 'path/to/base.ubnf'
```

---

## トークン宣言

トークンは文字シーケンスを認識するリーフパーサーです。`token` キーワードで宣言され、`Parser` を実装する Java クラスを参照する必要があります。

### シンプルトークン（クラス参照）

```ubnf
token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
token NUMBER     = org.unlaxer.parser.elementary.NumberParser
```

短縮形（非修飾クラス名）も使用できますが、`W-TOKEN-UNRESOLVED` 警告が発生します。警告を抑制するには完全修飾名を使用してください。短縮名が同梱パーサーパッケージ（`org.unlaxer.parser.elementary`, `posix`, `clang`, `combinator`, `ascii`）のクラスに一致する場合、警告の hint に完全修飾名の候補が列挙されます（例: `Did you mean 'org.unlaxer.parser.elementary.NumberParser'?`）。

### UNTIL トークン

ターミネーター文字列まで（ただし含まない）の任意の文字シーケンスにマッチします：

```ubnf
token CODE_BODY = UNTIL('```')
token LINE      = UNTIL('\n')
```

### NEGATION トークン

参照されたパーサーのマッチセットに*含まれない*任意の文字にマッチします：

```ubnf
token NON_QUOTE = NEGATION(SingleQuotedParser)
```

### LOOKAHEAD トークン

次の入力が参照されたパーサーにマッチする場合に成功しますが、何も消費しません：

```ubnf
token BEFORE_SEMICOLON = LOOKAHEAD(SemicolonParser)
```

### NEGATIVE_LOOKAHEAD トークン

次の入力が参照されたパーサーにマッチ*しない*場合に成功し、何も消費しません：

```ubnf
token NOT_KEYWORD = NEGATIVE_LOOKAHEAD(KeywordParser)
```

---

## ルール宣言

ルールは再帰的な文法を定義します。各ルールは `;` で終わります。

```ubnf
RuleName ::= body ;
```

---

## 構文リファレンス

### シーケンス（暗黙的）

隣接して記述された要素はシーケンスを形成します：

```ubnf
Assignment ::= IDENTIFIER '=' Expression ;
```

### 選択肢 `|`

```ubnf
Literal ::= NUMBER | STRING | 'true' | 'false' ;
```

### グループ `(...)`

```ubnf
Factor ::= '(' Expression ')' | NUMBER ;
```

### ゼロ以上 `{...}` または `*`

```ubnf
Statements ::= { Statement } ;     // UBNF 波括弧構文
Words       ::= Word* ;            // 後置構文
```

### 1回以上 `+`

```ubnf
Digits ::= DIGIT+ ;
```

### ゼロまたは1回 `[...]` または `?`

```ubnf
OptSign ::= [SignParser] ;         // 括弧構文
OptSign ::= SignParser? ;          // 後置構文
```

### 文字列リテラル `'...'`

```ubnf
Assign ::= IDENTIFIER '=' Expression ;
Plus   ::= '+' ;
```

リテラル内のエスケープシーケンス: `\n`、`\t`、`\r`、`\\`、`\'`。

### キャプチャ `@name`

`@mapping` が取り出すためのパースツリー要素をマークします：

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
BinaryExpr ::= Expression @left Operator @op Expression @right ;
```

### コメント

`//` を使ったラインコメント：

```ubnf
// これはコメントです
Rule ::= A B ; // インラインコメント
```

---

## アノテーション

アノテーションはルール宣言の直前に記述され、コード生成の動作を制御します。

### `@root`

文法のエントリポイントをマークします。ちょうど1つのルールに `@root` が必要です。

```ubnf
@root
Program ::= { Statement } EOF ;
```

### `@mapping(TypeName, params=[field1, field2, ...])`

Java レコード `TypeName(FieldType field1, FieldType field2, ...)` と、エバリュエータスケルトン内の対応する `evalXxx` メソッドを生成します。`params` リストはルールボディのキャプチャ名（`@name`）と一致する必要があります。

```ubnf
@mapping(FunctionCall, params=[name, args])
FunctionCall ::= IDENTIFIER @name '(' ArgList @args ')' ;
```

フィールド型の推論：
- `NumberParser` キャプチャ → `int`
- ルール参照キャプチャ → そのルールのマッピングされたレコード型
- 型なしのトークンキャプチャ → `String`（マッチしたテキスト）

### `@leftAssoc` / `@rightAssoc`

二項式ルールの演算子の結合性を制御します。このアノテーションがない場合、繰り返し演算子シーケンスはデフォルトで右結合になります。

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
Addition ::= Term @left { AddOp @op Term @right } ;

@mapping(Power, params=[base, exp])
@rightAssoc
Exponent ::= Factor @base '^' Factor @exp ;
```

### `@whitespace: style`

ルール要素間に暗黙の空白スキップを挿入します。サポートされているスタイル：

| スタイル | 動作 |
|---------|------|
| `javaStyle` | スペース、タブ、改行をスキップ |
| `none` | 暗黙の空白スキップなし |

```ubnf
@whitespace: javaStyle
```

### `@comment: { line: '//' }`

暗黙のコメントスキップを挿入します。ブロック形式は `line` キーを受け付けます：

```ubnf
@comment: { line: '//' }
```

### `@enum`

ルールの選択肢から Java `enum` を生成します。すべての選択肢は文字列リテラルである必要があります。

```ubnf
@enum
BoolLiteral ::= 'true' | 'false' ;
```

これにより以下が生成されます：

```java
public enum BoolLiteral { TRUE, FALSE }
```

### `@commonField`

sealed interface のすべての `@mapping` バリアントに共通して現れるフィールドを、共通のインターフェースメソッドに引き上げます：

```ubnf
@commonField(name)
@mapping(FunctionCall, params=[name, args])
FunctionCall ::= IDENTIFIER @name '(' ArgList @args ')' ;

@commonField(name)
@mapping(VariableRef, params=[name])
VariableRef ::= IDENTIFIER @name ;
```

生成された sealed interface は `String name();` をデフォルトインターフェースメソッドとして持ちます。

### `@scopeTree(mode=lexical|dynamic)`

ルールをスコープ境界としてマークします。コードジェネレーターは ParserIR 出力に `enterScope` および `leaveScope` イベントを出力します。

```ubnf
@scopeTree(mode=lexical)
Block ::= '{' { Statement } '}' ;
```

### `@declares(symbol=capture)`

キャプチャを現在のスコープ内のシンボル定義としてマークします（`@scopeTree` と組み合わせて使用）。`symbol=` 引数で、宣言される名前を保持するキャプチャを指定します。任意で `description=` 文字列を続けられます：

```ubnf
@declares(symbol=name)
VarDecl ::= 'var' IDENTIFIER @name '=' Expression ;
```

### `@backref(name=capture)`

キャプチャを、スコープ内で以前に定義されていなければならないシンボル使用としてマークします。`name=` 引数で、参照される名前を保持するキャプチャを指定します：

```ubnf
@backref(name=name)
VarRef ::= IDENTIFIER @name ;
```

### `@eval(key: 'value', ...)`

ルールに key/value の評価メタデータを付与します。エバリュエータジェネレータが消費します。各エントリは `識別子: '文字列'` の形です：

```ubnf
@eval(kind: 'binary')
Expression ::= Term @left { AddOp @op Term @right } ;
```

### `@precedence(level=N)`

ルールに整数の優先順位レベルを割り当てます（演算子優先順位の処理で結合性アノテーションと併用）。`N` は符号なし整数です：

```ubnf
@precedence(level=2)
@leftAssoc
Term ::= Factor @left { MulOp @op Factor @right } ;
```

### `@interleave(profile=name)`

ルールを、指定プロファイルによるインターリーブ解析の対象としてマークします（空白／コメントのインターリーブ方針など）。`profile=` 引数は識別子です：

```ubnf
@interleave(profile=default)
Document ::= { Element } ;
```

### `@doc('...')`

ルールにドキュメント文字列を付与します。ジェネレータはこれを生成物（ホバーテキストなど）へ引き継ぎます。文字列リテラルを1つ取ります：

```ubnf
@doc('トップレベルの文。')
Statement ::= Assignment | Expression ;
```

### `@recovery(sync='...' | mode)`

ルールをエラー回復の対象としてマークします。`sync='...'` で同期トークン文字列を与えるか、回復モードの識別子を裸で与えます。パーサージェネレータがそのルールに回復ラッパーを生成します：

```ubnf
@recovery(sync=';')
Statement ::= Assignment ';' ;
```

### `@catalog(context='...')`

ルールにカタログコンテキスト文字列を付与します。LSP ジェネレータがコンテキスト対応補完の駆動に消費します。文字列リテラルを1つ取ります：

```ubnf
@catalog(context='functions')
FunctionName ::= IDENTIFIER @name ;
```

### `@skip`

ルールに AST レコードを**生成させず**、マッパーがそれをスキップするようにマークします（そのルールはパースには参加しますが AST からは除外されます）。引数はありません：

```ubnf
@skip
Separator ::= ',' ;
```

### `@typeof(capture)`（要素レベル）

ルール本体の**中**に記述する要素レベルのアノテーションです（ルールに付けるものではありません）。注釈された要素の型を、指定キャプチャと相関させます：

```ubnf
Assignment ::= IDENTIFIER @name @typeof(value) '=' Expression @value ;
```

---

## 演算子の結合性

左結合演算子（ほとんどの算術演算）には、以下のパターンで `@leftAssoc` を使用します：

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
Expression ::= Term @left { Operator @op Term @right } ;
```

`{ Operator @op Term @right }` の繰り返しと `@leftAssoc` の組み合わせにより、ジェネレーターは右再帰構造ではなく左折りたたみループを構築します。

右結合演算子（べき乗、代入）には：

```ubnf
@mapping(Power, params=[base, exp])
@rightAssoc
Power ::= Factor @base '^' Power @exp ;
```

---

## 空白とコメントの処理

`@whitespace: javaStyle` がグローバルに設定されている場合、すべてのシーケンス要素間で空白がスキップされます。

```ubnf
grammar MyGrammar {
  @whitespace: javaStyle

  // このルールは javaStyle の空白スキップを受ける
  Statement ::= KEYWORD Expression ';' ;
}
```

---

## 文法インポート

`@import` を使って別の文法ファイルからルールをインポートします：

```ubnf
grammar ExtendedCalc {
  @import base from 'common/expressions.ubnf'

  @root
  Program ::= base.Expression EOF ;
}
```

インポートされたルールはエイリアス名前空間で使用できます。

---

## 機能マトリックス

| 機能 | 構文 | ステータス |
|-----|------|----------|
| シーケンス | 暗黙の隣接 | 安定 |
| 選択肢 | `\|` | 安定 |
| グループ | `(...)` | 安定 |
| ゼロ以上 | `{...}` / `*` | 安定 |
| 1回以上 | `+` | 安定 (v2.8+) |
| ゼロまたは1回 | `[...]` / `?` | 安定 |
| 文字列リテラル | `'...'` | 安定 |
| キャプチャ | `@name` | 安定 |
| `@root` | アノテーション | 安定 |
| `@mapping` | アノテーション | 安定 |
| `@leftAssoc` / `@rightAssoc` | アノテーション | 安定 |
| `@whitespace` | グローバル設定 | 安定 |
| `@comment` | グローバル設定 | 安定 |
| `@enum` | アノテーション | 安定 (v3.0+) |
| `@commonField` | アノテーション | 安定 (v3.0+) |
| `@scopeTree` | アノテーション | 安定 (v2.8+) |
| `@declares(symbol=...)` | アノテーション | 安定 (v2.8+) |
| `@backref(name=...)` | アノテーション | 安定 (v2.8+) |
| `@eval` | アノテーション | 認識（ジェネレータ対応あり） |
| `@precedence(level=...)` | アノテーション | 認識（ジェネレータ対応あり） |
| `@interleave(profile=...)` | アノテーション | 認識（ジェネレータ対応あり） |
| `@doc('...')` | アノテーション | 認識（ジェネレータ対応あり） |
| `@recovery(...)` | アノテーション | 認識（ジェネレータ対応あり） |
| `@catalog(context=...)` | アノテーション | 認識（ジェネレータ対応あり） |
| `@skip` | アノテーション | 認識（ジェネレータ対応あり） |
| `@typeof(...)` | 要素レベルアノテーション | 認識（ジェネレータ対応あり） |
| `UNTIL(...)` トークン | トークン形式 | 安定 (v2.8+) |
| `NEGATION(...)` トークン | トークン形式 | 安定 (v2.8+) |
| `LOOKAHEAD(...)` トークン | トークン形式 | 安定 (v2.8+) |
| `NEGATIVE_LOOKAHEAD(...)` トークン | トークン形式 | 安定 (v2.8+) |
| `@import` | 文法ディレクティブ | 安定 (v3.0+) |

---

## 完全な例: TinyCalc

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc
  @whitespace: javaStyle
  @comment: { line: '//' }

  token NUMBER     = org.unlaxer.parser.elementary.NumberParser
  token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
  token EOF        = org.unlaxer.parser.elementary.EndOfSourceParser

  @root
  @mapping(Program, params=[expr])
  Program ::= Expression @expr EOF ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  Factor ::=
      NUMBER
    | IDENTIFIER
    | '(' Expression ')'
    | '-' Factor ;

  @enum
  AddOp ::= '+' | '-' ;

  @enum
  MulOp ::= '*' | '/' ;
}
```
