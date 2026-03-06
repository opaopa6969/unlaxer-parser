# UBNF 完全ガイド

> 最終更新: 2026-03-07

---

## このドキュメントについて

このドキュメントは UBNF（Unlaxer BNF）文法言語の **完全ガイド** です。
形式仕様と、実際のユースケースに基づいたやさしい解説の両方を含みます。

「パーサー」「消費」「先読み」といった言葉がよくわからなくても大丈夫です。
読み進めながら概念ごとに説明します。

---

## そもそも UBNF って何？

プログラムやデータを読み込むとき、「このファイルはこういう構造になっています」という
**ルール** をあらかじめ定義する必要があります。

たとえば電卓プログラムが `3 + 5 * 2` という文字列を受け取ったとき、
「`+` より `*` が先に計算される」とか「数字は連続したら掛け算ではない」といった
ルールを知らないと正しく解釈できません。

このルールのことを **文法（grammar）** といいます。

**UBNF** はその文法を書くための言語です。
`.ubnf` ファイルに文法を書くと、Java のパーサーコードが自動生成されます。

---

## 例に使う言語：MiniLang

このガイドでは例として **MiniLang** という小さな言語を定義します。
以下のようなコードを読み込む言語です：

```
// MiniLang のサンプルプログラム
let x = 42
let greeting = "hello"
let isReady = true

if (x > 10) {
  print(greeting)
  print(x + 8)
}
```

MiniLang には次の要素があります：
- 変数宣言（`let`）
- 数値・文字列・真偽値リテラル
- 算術・比較演算
- `if` 文
- `print` 文
- `//` 行コメント

この言語の文法を UBNF で少しずつ書いていきます。

---

## 基本構造

### grammar ブロック

すべての UBNF 文法は `grammar` ブロックで囲みます：

```ubnf
grammar MiniLang {
  // ここに文法を書く
}
```

`MiniLang` は文法の名前で、生成される Java クラス名のプレフィックスになります。
（`MiniLangParsers.java`, `MiniLangAST.java` などが生成されます）

---

## グローバル設定

### `@package`

生成コードの Java パッケージを指定します：

```ubnf
grammar MiniLang {
  @package: org.example.minilang
}
```

### `@whitespace`

空白（スペース、タブ、改行）の扱いを指定します。
`javaStyle` を指定すると、`//` コメントも含めて自動でスキップされます：

```ubnf
grammar MiniLang {
  @package: org.example.minilang
  @whitespace: javaStyle   // スペース・タブ・改行・//コメントを自動スキップ
}
```

> **「自動スキップ」って何？**
> パーサーは文字を1つずつ読んでいきます。
> `@whitespace: javaStyle` を設定すると、
> ルールを照合するたびに先頭の空白とコメントを読み飛ばします。
> `let x = 42` も `let   x=42` も同じように解釈されます。

---

## トークン宣言（`token`）

ルールの中で使う「基本的な文字のかたまり」を **トークン** として宣言します。
トークンは文字単位の細かい照合を担当します。

```ubnf
token NUMBER = NumberParser         // 数字の並び（例: 42, 3.14）
token IDENTIFIER = IdentifierParser // 識別子（例: x, greeting, isReady）
token STRING = SingleQuotedParser   // シングルクォート文字列（例: 'hello'）
```

左辺がトークン名（大文字推奨）、右辺が unlaxer-common のパーサークラス名です。

### 特殊なトークンキーワード

クラス名の代わりにキーワードで直接ビルトインの挙動を指定できます：

#### `UNTIL('terminator')` — 終端文字まで読む

```ubnf
token CODE_BODY = UNTIL('```')  // バッククォート3つが来るまで何でも読む
```

> **ユースケース**: Markdown のコードブロック、ヒアドキュメント、
> 引用符に囲まれた文字列の内容を読むとき。
> 「この記号が来るまでは全部本文」という場合に使います。

#### `NEGATION('chars')` — 指定文字以外の1文字

```ubnf
token NOT_QUOTE = NEGATION('"')     // ダブルクォート以外の1文字
token NOT_SPACE = NEGATION(' \t\n') // スペース・タブ・改行以外の1文字
```

> **ユースケース**: ダブルクォート文字列の中身を文字単位で読むとき。
> `"hello"` の中の `h`, `e`, `l`, `l`, `o` は「`"` 以外の文字」です。

#### `CHAR_RANGE('min','max')` — 文字の範囲

```ubnf
token LOWER  = CHAR_RANGE('a','z')   // a〜z の1文字
token UPPER  = CHAR_RANGE('A','Z')   // A〜Z の1文字
token DIGIT  = CHAR_RANGE('0','9')   // 0〜9 の1文字
token HEX    = CHAR_RANGE('0','9')   // ※複数の範囲は NEGATION などと組み合わせる
```

> **ユースケース**: 特定の文字クラスを細かく定義したいとき。
> `IdentifierParser` は「英字・数字・アンダースコア」を読みますが、
> 「小文字のみ」「16進数の文字」などをピンポイントで定義できます。

#### `ANY` — 任意の1文字

```ubnf
token ANY_CHAR = ANY  // どんな文字でも1文字マッチ
```

> **ユースケース**: 「残り全部読む」系のパーサーの部品として。
> 通常は `UNTIL` や `NEGATION` のほうが適切ですが、
> 「次の1文字は何でもいい」という場面に使います。

#### `EOF` — ファイルの終わり

```ubnf
token FILE_END = EOF  // 入力の末尾にマッチ（文字は消費しない）
```

> **ユースケース**: ファイル全体を読み終えたことを確認したいとき。
> `Program ::= { Statement } FILE_END ;` のようにルールの末尾に置くと、
> 「ファイルの最後まできちんと処理できた」ことを保証できます。

#### `EMPTY` — 空文字列（常に成功）

```ubnf
token NOTHING = EMPTY  // 何も消費せずに常に成功する
```

> **ユースケース**: 省略可能な部分の「省略した」ケースを明示したいとき。
> `[]` 記法（後述）で大抵は代替できますが、
> 明示的に「ここには何もない」を表現したい場合に使います。

#### `LOOKAHEAD('pattern')` — 次に来ることを確認（消費しない）

```ubnf
token COLON_AHEAD = LOOKAHEAD(':')  // ':' が続くことを確認するが読み進めない
```

> **「消費」って何？**
> パーサーは入力を左から右へ読み進めます。
> 文字を読んで「ここまで読んだ」と進めることを **消費** といいます。
> LOOKAHEAD は確認だけして読み進めない（消費しない）特殊な操作です。
>
> **ユースケース**: `a:b` のコロン付き識別子と、普通の識別子 `a` を区別したいとき。
> `COLON_AHEAD` がマッチすれば「次は `a:b` 形式だ」と判断してルールを切り替えられます。

#### `NEGATIVE_LOOKAHEAD('pattern')` — 次に来ないことを確認（消費しない）

```ubnf
token NOT_COMMENT = NEGATIVE_LOOKAHEAD('//')  // '//' が続かないことを確認する
```

> **ユースケース**: `/` は割り算演算子として読みたいが、
> `//` はコメントなので別扱いにしたいとき。
> `NOT_COMMENT` を先に照合すれば「`//` ではない `/`」だけを通せます。

#### `CI('keyword')` — 大文字小文字を区別しないキーワード

```ubnf
token KW_SELECT = CI('select')  // SELECT, Select, select すべてマッチ
token KW_FROM   = CI('from')
```

> **ユースケース**: SQL や HTML などの大文字小文字を区別しない言語。
> MiniLang は区別しますが、`SELECT` を書いても `select` でも通したい場合に使います。

---

## コメント

UBNF ファイル内では `//` で行コメントが書けます：

```ubnf
// これはコメントです
grammar MiniLang {
  @whitespace: javaStyle  // javaStyle は // コメントを自動スキップ
}
```

---

## ルール宣言

トークンを組み合わせて **ルール（rule）** を作ります。
ルールは言語の構造（文・式・宣言など）を表します。

```ubnf
RuleName ::= ... body ... ;
```

- 左辺はルール名（大文字始まりの識別子）
- `::=` の右辺がルールの内容（本体）
- **`;` で終わる（必須）**

---

## ルール本体の書き方

### リテラル（`'keyword'`）

シングルクォートで囲んだ文字列はそのまま照合します：

```ubnf
LetKeyword ::= 'let' ;
IfKeyword  ::= 'if' ;
```

**エスケープシーケンス** も使えます：

| 書き方 | 意味 |
|--------|------|
| `'\n'` | 改行 |
| `'\t'` | タブ |
| `'\r'` | 復帰 |
| `'\\'` | バックスラッシュ |
| `'\''` | シングルクォート |

### ルール参照・トークン参照

他のルール名やトークン名を書くだけで参照できます：

```ubnf
// MiniLang の数値リテラル
NumberLiteral ::= NUMBER ;

// MiniLang の変数宣言（'let' の後に識別子、'=' 、式）
VarDecl ::= 'let' IDENTIFIER '=' Expression ;
```

### 連接（Sequence）— 順番に並べる

要素を並べると「この順番で全部来なければならない」という意味になります：

```ubnf
VarDecl ::= 'let' IDENTIFIER '=' Expression ;
//          ^^^^  ^^^^^^^^^^  ^^^  ^^^^^^^^^^
//          1番目  2番目       3番目  4番目
```

`let x = 42` を読むとき：
1. `'let'` → "let" にマッチ
2. `IDENTIFIER` → "x" にマッチ
3. `'='` → "=" にマッチ
4. `Expression` → "42" にマッチ

### 選択（Choice）— どれか1つ

`|` で区切ると「どれか1つにマッチすれば OK」という意味になります：

```ubnf
Literal ::= NUMBER | STRING | 'true' | 'false' ;
```

`42` なら `NUMBER`、`"hello"` なら `STRING`、`true` なら `'true'` にマッチします。

### グループ（`( ... )`）— まとめて扱う

括弧でまとめると、複数の要素を1つの単位として選択や繰り返しに使えます：

```ubnf
// '+' か '-' のどちらかと、続く Term をセットとして繰り返したい
Expression ::= Term { ('+' | '-') Term } ;
```

括弧がないと `{ '+' | '-' Term }` という意味になってしまいます（区切り方が変わる）。

### 省略可能（`[ ... ]`）— あってもなくてもいい

角括弧で囲むと「0回または1回」という意味になります：

```ubnf
// MiniLang の型注釈はオプション（あってもなくてもいい）
VarDecl ::= 'let' IDENTIFIER [ ':' TypeName ] '=' Expression ;
```

`let x = 42` でも `let x: number = 42` でも両方マッチします。

### 繰り返し（`{ ... }`）— 0回以上

波括弧で囲むと「0回以上繰り返す」という意味になります：

```ubnf
// プログラムは文の並び（0個以上）
Program ::= { Statement } ;
```

空のプログラム（文が0個）も、100行のプログラムも両方マッチします。

### `+` — 1回以上

`element+` は「1回以上繰り返す」という意味です：

```ubnf
// 識別子の後に型パラメータが1つ以上続く（例: Map<String, Integer>）
TypeParams ::= '<' TypeName { ',' TypeName }+ '>' ;

// 数字が1桁以上続く（実は NumberParser のほうが便利だが例として）
Digits ::= DIGIT+ ;
```

> **`{ ... }` と `element+` の違い**:
> - `{ X }` → X が **0個以上**（なくても OK）
> - `X+` → X が **1個以上**（最低1個は必要）

### `?` — 0回か1回（`[ ... ]` の別記法）

`element?` は `[ element ]` と同じ意味です：

```ubnf
// 末尾のカンマは省略可能
ArgList ::= '(' Expr { ',' Expr } ','? ')' ;
```

### `*` — 0回以上（`{ ... }` の別記法）

`element*` は `{ element }` と同じ意味です：

```ubnf
// 引数ゼロ以上のリスト
ArgList ::= '(' Expr* ')' ;  // ※セパレータなし版の例
```

### `element{n}` — ちょうど n 回

```ubnf
// 16進数の色コード（例: #FF0080）
HexColor ::= '#' HEX{6} ;   // HEX トークンがちょうど6回
```

### `element{n,m}` — n〜m 回

```ubnf
// パスワードは8〜32文字
Password ::= VISIBLE_CHAR{8,32} ;
```

### `element{n,}` — n 回以上

```ubnf
// 1個以上の引数（カンマ区切り）
ArgList ::= Expr{1,} ;
```

---

## キャプチャ（`@name`）

要素の直後に `@name` を書くと、その要素に名前を付けて後から参照できます。
`@mapping` アノテーションと組み合わせて使います：

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
BinaryExpr ::= Expr @left ('+' | '-' | '*' | '/') @op Expr @right ;
```

`3 + 5` をパースすると：
- `left` = `3`
- `op` = `+`
- `right` = `5`

として `BinaryExpr` オブジェクトが生成されます。

**繰り返しの中でキャプチャすると、リストになります：**

```ubnf
@mapping(Program, params=[statements])
Program ::= { Statement @statements } ;
```

`statements` は `List<StatementAST>` になります。

---

## アノテーション

ルール宣言の前に `@` で始まる指示（アノテーション）を付けられます。

### `@root` — エントリポイント

文法全体のトップレベルルールを指定します。
ファイルを読み込んだときに最初に呼ばれるルールです：

```ubnf
@root
Program ::= { Statement } ;
```

### `@mapping(ClassName, params=[...])` — AST クラスへのマッピング

パースした内容を Java のクラスに変換するときの設定です：

```ubnf
@mapping(VarDeclAST, params=[name, value])
VarDecl ::= 'let' IDENTIFIER @name '=' Expression @value ;
```

`@mapping` を付けると、`VarDeclAST(String name, ExpressionAST value)` のような
Java のデータクラスが自動生成されます。

### `@leftAssoc` / `@rightAssoc` — 演算子の結合性

同じ優先度の演算子が並んだとき、左から結合するか右から結合するかを指定します：

```ubnf
// 'a - b - c' は '(a - b) - c'（左結合）
@leftAssoc
Expression ::= Term @left { ('+' @op | '-' @op) Term @right } ;

// 'a = b = c' は 'a = (b = c)'（右結合。代入演算子など）
@rightAssoc
Assignment ::= IDENTIFIER @left '=' Assignment @right ;
```

### `@precedence(level=N)` — 演算子優先度

数値が大きいほど優先度が高い（先に計算される）：

```ubnf
@precedence(level=10)
@leftAssoc
AddExpr ::= Term { ('+' | '-') Term } ;

@precedence(level=20)
@leftAssoc
MulExpr ::= Factor { ('*' | '/') Factor } ;
```

### `@interleave(profile=...)` — 空白処理プロファイルの上書き

特定のルールだけ空白の扱いを変えたいときに使います：

```ubnf
@interleave(profile=javaStyle)
DocBlock ::= '/**' CONTENT '*/' ;
```

### `@backref(name=...)` — 後方参照

ルール内で以前にキャプチャした値を再度参照するときに使います：

```ubnf
// 開始タグと終了タグが一致することを確認
@backref(name=tagName)
Element ::= '<' IDENTIFIER @tagName '>' Content '</' IDENTIFIER '>' ;
```

### `@scopeTree(mode=...)` — スコープの管理

変数スコープの追跡方法を指定します：

```ubnf
@scopeTree(mode=lexical)
Block ::= '{' { Statement } '}' ;
```

### `@doc('説明文')` — ドキュメントコメント

ルールの説明を付けます。生成される Java コードに Javadoc コメントとして出力されます：

```ubnf
@doc('変数宣言。let キーワードで始まる')
VarDecl ::= 'let' IDENTIFIER '=' Expression ;
```

### `@typeof(captureName)` — 型制約

キャプチャした要素の型を別のキャプチャから推論させるときに使います：

```ubnf
BinaryExpr ::= @typeof(left) Expr @left Op Expr @right ;
```

---

## ERROR 要素 — エラーメッセージのヒント

`ERROR('message')` を選択肢の最後に置くと、
パースが失敗したときに表示されるエラーメッセージを指定できます：

```ubnf
Statement ::=
    VarDecl
  | IfStatement
  | PrintStatement
  | ERROR('statement expected: let, if, or print') ;
```

> **仕組み**: unlaxer のパーサーは失敗したとき、
> 「どこまで進んでどこで詰まったか」を自動で記録します（`ParseFailureDiagnostics`）。
> `ERROR('msg')` を置くと、その位置でのヒントメッセージが診断情報に追加されます。
> `WordParser("if")` などのリテラルは自動的に `"'if' expected"` というヒントを生成するので、
> 大抵の場面では `ERROR` なしでも有用なエラーメッセージが得られます。

---

## MiniLang の完全な文法

ここまでの機能をすべて使った MiniLang の完全な文法です：

```ubnf
// MiniLang — UBNF の機能を網羅した例題言語
grammar MiniLang {

  @package: org.example.minilang.generated
  @whitespace: javaStyle   // スペース・タブ・改行・// コメントを自動スキップ

  // ---------------------------------------------------------
  // トークン定義（基本的な文字のかたまり）
  // ---------------------------------------------------------

  token NUMBER     = NumberParser        // 数値（整数・小数）
  token IDENTIFIER = IdentifierParser    // 識別子（変数名・関数名）
  token STRING     = SingleQuotedParser  // シングルクォート文字列
  token LOWER      = CHAR_RANGE('a','z') // 小文字1文字
  token UPPER      = CHAR_RANGE('A','Z') // 大文字1文字
  token EOF_MARKER = EOF                 // ファイル終端

  // ---------------------------------------------------------
  // プログラム全体
  // ---------------------------------------------------------

  @root
  @doc('MiniLang プログラム。文の並び。')
  @mapping(Program, params=[statements])
  Program ::= { Statement @statements } EOF_MARKER ;

  // ---------------------------------------------------------
  // 文（Statement）
  // ---------------------------------------------------------

  @doc('文。変数宣言・if・print のいずれか。')
  Statement ::=
      VarDecl
    | IfStatement
    | PrintStatement
    | ERROR('expected: let, if, or print') ;

  @doc('変数宣言。let x = 式 の形式。型注釈はオプション。')
  @mapping(VarDecl, params=[name, type, value])
  VarDecl ::= 'let' IDENTIFIER @name [ ':' IDENTIFIER @type ] '=' Expression @value ;

  @doc('if 文。else 節はオプション。')
  @mapping(IfStatement, params=[condition, thenBody, elseBody])
  IfStatement ::=
      'if' '(' Expression @condition ')' Block @thenBody
      [ 'else' Block @elseBody ] ;

  @doc('print 文。カッコ内の式を出力する。')
  @mapping(PrintStatement, params=[value])
  PrintStatement ::= 'print' '(' Expression @value ')' ;

  // ---------------------------------------------------------
  // ブロック（{ 文* }）
  // ---------------------------------------------------------

  @doc('ブロック。波括弧で囲まれた文の並び。')
  @mapping(Block, params=[statements])
  Block ::= '{' { Statement @statements } '}' ;

  // ---------------------------------------------------------
  // 式（Expression）— 演算子優先度を左結合で表現
  // ---------------------------------------------------------

  @doc('比較式。> < == != を扱う。')
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= AddExpr @left [ ('>' @op | '<' @op | '==' @op | '!=' @op) AddExpr @right ] ;

  @doc('加減算式。')
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  AddExpr ::= MulExpr @left { ('+' @op | '-' @op) MulExpr @right } ;

  @doc('乗除算式。加減算より優先度が高い。')
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  MulExpr ::= UnaryExpr @left { ('*' @op | '/' @op) UnaryExpr @right } ;

  @doc('単項式。負の数やカッコ式など。')
  UnaryExpr ::= '-' Primary | Primary ;

  @doc('基本要素。リテラル、識別子、カッコ式のいずれか。')
  Primary ::=
      NUMBER
    | STRING
    | 'true'
    | 'false'
    | IDENTIFIER
    | '(' Expression ')' ;
}
```

このプログラム `let x = 3 + 5 * 2` を読み込むと：

```
Program
  statements:
    [0] VarDecl
          name: "x"
          type: (なし)
          value: BinaryExpr
                   left: "3"
                   op: "+"
                   right: BinaryExpr
                            left: "5"
                            op: "*"
                            right: "2"
```

のような AST が生成されます。

---

## よくあるパターン集

### パターン 1: カンマ区切りのリスト

```ubnf
// 引数リスト: 0個以上の引数
ArgList ::= '(' [ Expr { ',' Expr } ] ')' ;

// 引数リスト: 1個以上の引数
ArgList1 ::= '(' Expr { ',' Expr } ')' ;

// 末尾のカンマも許す場合
ArgListTrailing ::= '(' Expr { ',' Expr } ','? ')' ;
```

### パターン 2: キーワードを識別子と区別する

```ubnf
// 'let' はキーワードなので IDENTIFIER とは別に定義
// WordParser("let") は 'let' に完全一致し、識別子を横取りしない
VarDecl ::= 'let' IDENTIFIER '=' Expression ;
```

### パターン 3: 文字列リテラルの定義

```ubnf
token DQUOTE     = NEGATION('"')  // ダブルクォート以外の文字
token BACKSLASH  = NEGATION('\\') // バックスラッシュ以外の文字

DoubleQuotedString ::= '"' { DQUOTE } '"' ;
```

### パターン 4: 行コメント

```ubnf
token LINE_END = UNTIL('\n')  // 改行まで読む

LineComment ::= '//' LINE_END ;
```

### パターン 5: 16進数カラーコード

```ubnf
token HEX_DIGIT = NEGATION('ghijklmnopqrstuvwxyzGHIJKLMNOPQRSTUVWXYZ !@#$...')
// ※ より正確には CHAR_RANGE を組み合わせるか専用パーサーを使う

HexColor ::= '#' HEX_DIGIT{6} ;    // #FF0080
ShortHex ::= '#' HEX_DIGIT{3} ;    // #F08
```

### パターン 6: 大文字小文字を区別しない SQL 風キーワード

```ubnf
token KW_SELECT = CI('select')
token KW_FROM   = CI('from')
token KW_WHERE  = CI('where')

SelectStmt ::= KW_SELECT ArgList KW_FROM IDENTIFIER [ KW_WHERE Expression ] ;
```

### パターン 7: 末尾に EOF を置く

```ubnf
token EOF_MARKER = EOF

@root
File ::= { Statement } EOF_MARKER ;
// ファイル全体を読み終えたことを確認。途中で止まったらエラーになる。
```

### パターン 8: ERROR でわかりやすいメッセージ

```ubnf
TypeAnnotation ::=
    'int' | 'string' | 'bool' | 'float'
  | ERROR('unknown type: use int, string, bool, or float') ;
```

---

## 形式的な文法定義

以下は UBNF 自身の文法を UBNF で表した形式仕様です。

```
UBNFFile     ::= { '//' LINE_COMMENT } GrammarDecl+
GrammarDecl  ::= 'grammar' IDENTIFIER '{' { GlobalSetting } { TokenDecl } { RuleDecl } '}'
GlobalSetting::= '@' IDENTIFIER ':' SettingValue
SettingValue ::= DottedIdentifier | '{' { IDENTIFIER ':' STRING } '}'

TokenDecl    ::= 'token' IDENTIFIER '=' TokenValue
TokenValue   ::= CLASS_NAME
               | UNTIL('terminator')
               | NEGATION('chars')
               | LOOKAHEAD('pattern')
               | NEGATIVE_LOOKAHEAD('pattern')
               | CHAR_RANGE('min','max')
               | CI('word')
               | ANY
               | EOF
               | EMPTY

RuleDecl     ::= { Annotation } IDENTIFIER '::=' RuleBody ';'
Annotation   ::= '@root'
               | '@mapping' '(' IDENTIFIER [',' 'params' '=' '[' IDENTIFIER {',' IDENTIFIER} ']'] ')'
               | '@whitespace' ['(' IDENTIFIER ')']
               | '@leftAssoc' | '@rightAssoc'
               | '@precedence' '(' 'level' '=' INTEGER ')'
               | '@interleave' '(' 'profile' '=' IDENTIFIER ')'
               | '@backref' '(' 'name' '=' IDENTIFIER ')'
               | '@scopeTree' '(' 'mode' '=' IDENTIFIER ')'
               | '@doc' '(' STRING ')'
               | '@' IDENTIFIER

RuleBody     ::= ChoiceBody
ChoiceBody   ::= SequenceBody { '|' SequenceBody }
SequenceBody ::= AnnotatedElement+

AnnotatedElement ::= ['@typeof' '(' IDENTIFIER ')'] AtomicElement [Quantifier] ['@' IDENTIFIER]
Quantifier   ::= '+' | '?' | '*' | '{' INTEGER '}' | '{' INTEGER ',' [INTEGER] '}'

AtomicElement ::= GroupElement
                | OptionalElement
                | RepeatElement
                | TerminalElement
                | RuleRefElement
                | ErrorElement

GroupElement    ::= '(' RuleBody ')'
OptionalElement ::= '[' RuleBody ']'
RepeatElement   ::= '{' RuleBody '}'
TerminalElement ::= "'" chars "'"
RuleRefElement  ::= IDENTIFIER
ErrorElement    ::= 'ERROR' '(' STRING ')'
```

---

## 予約語

以下は UBNF で特別な意味を持つ単語です：

- `grammar` — grammar ブロックの開始
- `token` — トークン宣言
- `UNTIL`, `NEGATION`, `LOOKAHEAD`, `NEGATIVE_LOOKAHEAD` — トークンキーワード
- `CHAR_RANGE`, `CI`, `ANY`, `EOF`, `EMPTY` — トークンキーワード
- `ERROR` — エラーヒント要素
- `params` — `@mapping` アノテーション内

---

## 関連ドキュメント

- [annotations.md](annotations.md) — アノテーション詳細仕様
- [token-resolution.md](token-resolution.md) — トークン解決ランタイム動作
- [generators.md](../docs/generators.md) — コード生成の詳細
- [docs/UBNF-EXTENSION-ROADMAP.md](../docs/UBNF-EXTENSION-ROADMAP.md) — 拡張ロードマップ

---

## 変更履歴

| 日付 | 内容 |
|------|------|
| 2026-03-01 | 初版作成 |
| 2026-03-07 | 全面改訂。T1-4〜T4-8 の新機能を追加。初心者向けガイドと MiniLang サンプルを統合 |
