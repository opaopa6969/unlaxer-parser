# unlaxer-dsl
[English](README.md) | [日本語](README.ja.md)
[仕様メモ](SPEC.md)
[Parser IR 設計ドラフト](docs/PARSER-IR-DRAFT.md)

UBNF（Unlaxer BNF）記法で書いた文法定義から、Java のパーサー・AST・マッパー・エバリュエーター・LSP サーバー・DAP デバッグアダプターを自動生成し、VS Code 拡張（VSIX）までビルドできるツールです。

---

## 目次

- [特徴](#特徴)
- [前提条件](#前提条件)
- [ビルド](#ビルド)
- [クイックスタート](#クイックスタート)
- [UBNF 文法の書き方](#ubnf-文法の書き方)
  - [全体構造](#全体構造)
  - [グローバル設定](#グローバル設定)
  - [トークン宣言](#トークン宣言)
  - [ルール宣言](#ルール宣言)
  - [要素記法](#要素記法)
  - [アノテーション](#アノテーション)
- [コードジェネレーターの使い方](#コードジェネレーターの使い方)
- [生成物の詳細（TinyCalc 例）](#生成物の詳細tinycalc-例)
  - [ASTGenerator](#astgenerator)
  - [ParserGenerator](#parsergenerator)
  - [MapperGenerator](#mappergenerator)
  - [EvaluatorGenerator](#evaluatorgenerator)
  - [LSPGenerator](#lspgenerator)
  - [LSPLauncherGenerator](#lsplaunchergenerator)
  - [DAPGenerator](#dapgenerator)
  - [DAPLauncherGenerator](#daplaunchergenerator)
- [CodegenMain — CLI ツール](#codegenmain--cli-ツール)
- [VS Code 拡張（VSIX）のビルド](#vs-code-拡張vsixのビルド)
- [チュートリアル 1: TinyCalc](#チュートリアル-1-tinycalc)
- [チュートリアル 2: UBNF の VS Code 拡張を作る](#チュートリアル-2-ubnf-の-vs-code-拡張を作る)
- [プロジェクト構造](#プロジェクト構造)
- [自己ホスティング](#自己ホスティング)
- [ロードマップ](#ロードマップ)

---

## 特徴

- **UBNF 記法** — BNF 拡張記法（グループ `()`、Optional `[]`、繰り返し `{}`、キャプチャ `@name`）でシンプルに文法を記述できる
- **8 種類のコード生成** — 1 つの文法定義から最大 8 つの Java クラスを自動生成
  - `XxxParsers.java` — unlaxer-common のパーサーコンビネータを使ったパーサー
  - `XxxAST.java` — sealed interface + record による型安全な AST
  - `XxxMapper.java` — パースツリー → AST 変換のスケルトン
  - `XxxEvaluator.java` — AST を走査する抽象エバリュエーター
  - `XxxLanguageServer.java` — lsp4j 製 LSP サーバー（補完・ホバー・シンタックスハイライト）
  - `XxxLspLauncher.java` — stdio 経由で起動する LSP サーバーの main クラス
  - `XxxDebugAdapter.java` — DAP サーバー（launch・parseエラー報告・stopOnEntry・トークン単位ステップ実行・ブレークポイント）
  - `XxxDapLauncher.java` — stdio 経由で起動する DAP サーバーの main クラス
- **CLI ツール `CodegenMain`** — `.ubnf` ファイルを指定してコマンド 1 行でソースを生成
- **VSIX ワンコマンドビルド** — `tinycalc-vscode/` または `ubnf-vscode/` で `mvn verify` を実行するだけで VS Code 拡張（`.vsix`）が `target/` に生成される。LSP + DAP は同一 fat jar に収録
- **Java 21 対応** — sealed interface・record・switch 式をフル活用
- **自己ホスティング達成** — `grammar/ubnf.ubnf` を `ParserGenerator` で処理して生成した `UBNFParsers` が、`ubnf.ubnf` 自身を完全にパースできることを `SelfHostingRoundTripTest` で検証済み

---

## 前提条件

| ソフトウェア | バージョン | 用途 |
|---|---|---|
| Java | 21 以上（`--enable-preview` 有効） | ライブラリ本体・コード生成 |
| Maven | 3.8 以上 | ビルド管理 |
| Node.js + npm | 18 以上 | VSIX ビルド時のみ必要 |

---

## ビルド

```bash
git clone https://github.com/yourorg/unlaxer-dsl.git
cd unlaxer-dsl
mvn package
```

テスト実行：

```bash
mvn test
```

golden snapshot の再生成：

```bash
./scripts/refresh-golden-snapshots.sh
```

golden snapshot が最新かチェック：

```bash
./scripts/check-golden-snapshots.sh
```

`SPEC.md` の JSON レポート例を再生成：

```bash
./scripts/spec/refresh-json-examples.sh
```

`SPEC.md` の JSON レポート例が最新か確認（CI向け）：

```bash
./scripts/spec/check-json-examples.sh
```

シェルスクリプトのチェック（shebang + 構文）：

```bash
./scripts/check-scripts.sh
```

CLI オプション記述のドキュメント同期チェック：

```bash
./scripts/spec/check-doc-sync.sh
```

ローカルの一括チェック（scripts + golden同期 + tests + spec freshness）：

```bash
./scripts/check-all.sh
```

Parser IR JSON の妥当性チェック：

```bash
mvn -q -DskipTests compile
java --enable-preview -cp target/classes org.unlaxer.dsl.ParserIrSchemaMain --ir path/to/parser-ir.json
```

---

## クイックスタート

1. UBNF ファイルを用意する（例: `tinycalc.ubnf`）
2. `UBNFMapper.parse()` で文法をパース
3. 各ジェネレーターで Java ソースを生成
4. 生成されたソースをプロジェクトに追加

```java
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.*;

// 1. .ubnf ファイルの内容を文字列として読み込む
String ubnfSource = Files.readString(Path.of("tinycalc.ubnf"));

// 2. 文法をパース
GrammarDecl grammar = UBNFMapper.parse(ubnfSource).grammars().get(0);

// 3. 各コードを生成
CodeGenerator.GeneratedSource ast        = new ASTGenerator()         .generate(grammar);
CodeGenerator.GeneratedSource parsers    = new ParserGenerator()      .generate(grammar);
CodeGenerator.GeneratedSource mapper     = new MapperGenerator()      .generate(grammar);
CodeGenerator.GeneratedSource evaluator  = new EvaluatorGenerator()   .generate(grammar);
CodeGenerator.GeneratedSource lspServer  = new LSPGenerator()         .generate(grammar);
CodeGenerator.GeneratedSource lspLaunch  = new LSPLauncherGenerator() .generate(grammar);
CodeGenerator.GeneratedSource dapAdapter = new DAPGenerator()         .generate(grammar);
CodeGenerator.GeneratedSource dapLaunch  = new DAPLauncherGenerator() .generate(grammar);

// 4. ソースを取り出して保存
System.out.println(parsers.packageName());    // org.unlaxer.tinycalc.generated
System.out.println(parsers.className());      // TinyCalcParsers
System.out.println(dapAdapter.className());   // TinyCalcDebugAdapter
System.out.println(dapLaunch.className());    // TinyCalcDapLauncher
```

---

## UBNF 文法の書き方

### 全体構造

```ubnf
grammar GrammarName {
    // グローバル設定
    @package: com.example.generated
    @whitespace: javaStyle

    // トークン宣言
    token TOKEN_NAME = ParserClassName

    // ルール宣言
    @root
    RootRule ::= ... ;

    OtherRule ::= ... ;
}
```

- 1 ファイルに複数の `grammar` ブロックを書ける
- コメントは `//` 行コメント

---

### グローバル設定

`@キー: 値` の形式でグローバル設定を記述する。

| キー | 値の例 | 説明 |
|---|---|---|
| `@package` | `org.example.generated` | 生成される Java ファイルのパッケージ名 |
| `@whitespace` | `javaStyle` | 空白処理スタイル。`javaStyle` の場合、ルール間にスペースが自動的に読み飛ばされる |
| `@comment` | `{ line: "//" }` | コメント形式。`line: "//"` の場合、行コメントを空白と同様に読み飛ばす |

```ubnf
grammar MyLang {
    @package: com.example.mylang
    @whitespace: javaStyle
    @comment: { line: "//" }
    ...
}
```

---

### トークン宣言

外部の unlaxer-common パーサークラスをトークンとして宣言する。

```ubnf
token TOKEN_NAME = ParserClassName
```

- `TOKEN_NAME` — 文法内で参照する名前（慣習として大文字スネークケース）
- `ParserClassName` — 使用する unlaxer-common のパーサークラス名（パッケージなし）

**例：**

```ubnf
token NUMBER     = NumberParser
token IDENTIFIER = IdentifierParser
token STRING     = StringParser
```

ルール内で `NUMBER` と書くと、生成コードでは `Parser.get(NumberParser.class)` に変換される。

---

### ルール宣言

```ubnf
[アノテーション...]
RuleName ::= 本体 ;
```

- ルール名は PascalCase を推奨
- 本体は選択 `|`、順列、グループ `()`、Optional `[]`、繰り返し `{}` を組み合わせて記述
- 末尾に `;` が必要

**選択（Choice）：**

```ubnf
Factor ::= '(' Expression ')' | NUMBER | IDENTIFIER ;
```

**順列（Sequence）：**

```ubnf
VariableDeclaration ::= 'var' IDENTIFIER '=' Expression ';' ;
```

**グループ `()` — 選択のグループ化：**

```ubnf
VariableDeclaration ::= ( 'var' | 'variable' ) IDENTIFIER ';' ;
```

**Optional `[]` — 0 または 1 回：**

```ubnf
VariableDeclaration ::= 'var' IDENTIFIER [ '=' Expression ] ';' ;
```

**繰り返し `{}` — 0 回以上：**

```ubnf
Program ::= { VariableDeclaration } Expression ;
```

---

### 要素記法

| 記法 | 意味 | 生成コード |
|---|---|---|
| `'literal'` | リテラル文字列 | `new WordParser("literal")` |
| `RuleName` | ルール参照 | `Parser.get(RuleNameParser.class)` |
| `TOKEN` | トークン参照（`token` 宣言あり） | `Parser.get(TokenParserClass.class)` |
| `( A \| B )` | グループ（選択） | ヘルパークラス `extends LazyChoice` |
| `[ A ]` | Optional（0 または 1 回） | `new Optional(...)` |
| `{ A }` | 繰り返し（0 回以上） | `new ZeroOrMore(...)` |

**キャプチャ名 `@name`：**

要素の末尾に `@名前` を付けると、AST レコードのフィールドとして対応付けられる。

```ubnf
Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;
```

この例では：
- `Term @left` → `left` フィールド
- `'+' @op` → `op` フィールド
- `Term @right` → `right` フィールド

`@mapping` アノテーションの `params` に対応するキャプチャ名を並べる。

---

### アノテーション

ルール宣言の直前に付ける。

| アノテーション | 説明 |
|---|---|
| `@root` | このルールがパースのエントリーポイント（ルート）であることを宣言。`getRootParser()` が返すクラスになる |
| `@mapping(ClassName)` | パースツリーをマップする AST クラス名を指定 |
| `@mapping(ClassName, params=[a, b, c])` | AST クラス名とフィールド名を指定。フィールド型はキャプチャ名から自動推論される |
| `@leftAssoc` | 左結合演算子であることを宣言（left/op/right の capture・params 契約で検証。`@precedence` との併用必須） |
| `@rightAssoc` | 右結合演算子であることを宣言。標準形 `Base { Op Self }` が必須（非標準形は validation エラー）で、右再帰パーサーとして生成される |
| `@precedence(level=10)` | 演算子ルールの優先順位メタデータ。現状 validator は `@leftAssoc` または `@rightAssoc` との併用を要求し、同一ルールでの重複指定を禁止 |
| `@whitespace` | このルールの空白処理を個別に制御（global 設定より優先、オプション） |
| `@interleave(profile=...)` | interleave 方針メタデータを宣言（parser IR / 後段ツール連携向け） |
| `@backref(name=...)` | 後方参照の意図メタデータを宣言（意味制約・診断向け予約） |
| `@scopeTree(mode=...)` | scope tree 利用メタデータを宣言（シンボル処理・ツール連携向け） |

---

## コードジェネレーターの使い方

すべてのジェネレーターは `CodeGenerator` インターフェースを実装している。

```java
public interface CodeGenerator {
    GeneratedSource generate(GrammarDecl grammar);

    record GeneratedSource(
        String packageName,  // 生成コードのパッケージ名
        String className,    // 生成クラス名
        String source        // Java ソースコード全文
    ) {}
}
```

**使用例：**

```java
GrammarDecl grammar = UBNFMapper.parse(ubnfSource).grammars().get(0);

// AST 生成
var ast = new ASTGenerator().generate(grammar);
// ast.packageName() → "org.unlaxer.tinycalc.generated"
// ast.className()   → "TinyCalcAST"
// ast.source()      → "package org.unlaxer...public sealed interface TinyCalcAST..."

// パーサー生成
var parsers = new ParserGenerator().generate(grammar);

// マッパー生成
var mapper = new MapperGenerator().generate(grammar);

// エバリュエーター生成
var evaluator = new EvaluatorGenerator().generate(grammar);

// LSP サーバー生成
var lspServer   = new LSPGenerator().generate(grammar);
// LSP ランチャー生成
var lspLauncher = new LSPLauncherGenerator().generate(grammar);

// DAP デバッグアダプター生成
var dapAdapter  = new DAPGenerator().generate(grammar);
// DAP ランチャー生成
var dapLauncher = new DAPLauncherGenerator().generate(grammar);
```

---

## 生成物の詳細（TinyCalc 例）

以下の文法（`examples/tinycalc.ubnf`）を使って各ジェネレーターの出力を説明する。

```ubnf
grammar TinyCalc {
    @package: org.unlaxer.tinycalc.generated
    @whitespace: javaStyle

    token NUMBER     = NumberParser
    token IDENTIFIER = IdentifierParser

    @root
    @mapping(TinyCalcProgram, params=[declarations, expression])
    TinyCalc ::=
        { VariableDeclaration } @declarations
        Expression              @expression ;

    @mapping(VarDecl, params=[keyword, name, init])
    VariableDeclaration ::=
        ( 'var' | 'variable' ) @keyword
        IDENTIFIER @name
        [ 'set' Expression @init ]
        ';' ;

    @mapping(BinaryExpr, params=[left, op, right])
    @leftAssoc
    Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;

    @mapping(BinaryExpr, params=[left, op, right])
    @leftAssoc
    Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;

    Factor ::=
          '(' Expression ')'
        | NUMBER
        | IDENTIFIER ;
}
```

---

### ASTGenerator

`TinyCalcAST.java` を生成する。`@mapping` アノテーション付きルールを収集し、sealed interface + record として出力する。

**生成される `TinyCalcAST.java`：**

```java
package org.unlaxer.tinycalc.generated;

import java.util.List;
import java.util.Optional;

public sealed interface TinyCalcAST permits
    TinyCalcAST.TinyCalcProgram,
    TinyCalcAST.VarDecl,
    TinyCalcAST.BinaryExpr {

    // { VariableDeclaration } @declarations → List<TinyCalcAST.VarDecl>
    // Expression @expression → TinyCalcAST.BinaryExpr（Expression は @mapping(BinaryExpr) なので）
    record TinyCalcProgram(
        List<TinyCalcAST.VarDecl> declarations,
        TinyCalcAST.BinaryExpr expression
    ) implements TinyCalcAST {}

    // ( 'var' | 'variable' ) @keyword → グループ要素のため Object
    // IDENTIFIER @name → トークン参照のため String
    // [ 'set' Expression @init ] @init → Optional<TinyCalcAST.BinaryExpr>
    record VarDecl(
        Object keyword,
        String name,
        Optional<TinyCalcAST.BinaryExpr> init
    ) implements TinyCalcAST {}

    // Expression と Term の両方に @mapping(BinaryExpr) があるが record は1つだけ生成
    record BinaryExpr(
        TinyCalcAST.BinaryExpr left,
        String op,
        TinyCalcAST.BinaryExpr right
    ) implements TinyCalcAST {}
}
```

**フィールド型の推論ルール：**

| 文法要素 | 推論された型 |
|---|---|
| `{ RuleName } @field`（繰り返し内の @mapping 付きルール参照） | `List<TinyCalcAST.ClassName>` |
| `[ RuleName ] @field`（Optional 内の @mapping 付きルール参照） | `Optional<TinyCalcAST.ClassName>` |
| `RuleName @field`（@mapping 付きルール参照） | `TinyCalcAST.ClassName` |
| `TOKEN @field`（トークン参照） | `String` |
| `'literal' @field`（終端記号） | `String` |
| `( A \| B ) @field`（グループ要素） | `Object` |

---

### ParserGenerator

`TinyCalcParsers.java` を生成する。unlaxer-common のパーサーコンビネータを使って、文法に対応するパーサークラス群を出力する。

**生成される `TinyCalcParsers.java` の構造：**

```java
package org.unlaxer.tinycalc.generated;

import java.util.Optional;
import java.util.function.Supplier;
import org.unlaxer.RecursiveMode;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.SpaceParser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;
import org.unlaxer.util.cache.SupplierBoundCache;

public class TinyCalcParsers {

    // --- Whitespace Delimitor ---
    // @whitespace: javaStyle の設定から生成
    public static class TinyCalcSpaceDelimitor extends LazyZeroOrMore {
        private static final long serialVersionUID = 1L;
        @Override
        public Supplier<Parser> getLazyParser() {
            return new SupplierBoundCache<>(() -> Parser.get(SpaceParser.class));
        }
        @Override
        public java.util.Optional<Parser> getLazyTerminatorParser() {
            return java.util.Optional.empty();
        }
    }

    // --- Base Chain ---
    // 各シーケンスパーサーの基底クラス。パーサー間に自動的に空白をスキップする
    public static abstract class TinyCalcLazyChain extends LazyChain {
        private static final long serialVersionUID = 1L;
        private static final TinyCalcSpaceDelimitor SPACE = createSpace();
        ...
        @Override
        public void prepareChildren(Parsers c) {
            if (!c.isEmpty()) return;
            c.add(SPACE);
            for (Parser p : getLazyParsers()) { c.add(p); c.add(SPACE); }
        }
        public abstract Parsers getLazyParsers();
    }

    // --- ヘルパークラス（複合要素の展開）---

    // VariableDeclaration の ( 'var' | 'variable' ) から生成
    public static class VariableDeclarationGroup0Parser extends LazyChoice {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("var"),
                new WordParser("variable")
            );
        }
        @Override
        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return java.util.Optional.empty();
        }
    }

    // VariableDeclaration の [ 'set' Expression @init ] から生成
    public static class VariableDeclarationOpt0Parser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("set"),
                Parser.get(ExpressionParser.class)
            );
        }
    }

    // Expression の { ( '+' @op | '-' @op ) Term @right } から生成
    public static class ExpressionRepeat0Parser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(ExpressionGroup0Parser.class),
                Parser.get(TermParser.class)
            );
        }
    }

    // --- ルールクラス ---

    // @root ルール
    public static class TinyCalcParser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new ZeroOrMore(VariableDeclarationParser.class),
                Parser.get(ExpressionParser.class)
            );
        }
    }

    public static class VariableDeclarationParser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(VariableDeclarationGroup0Parser.class),
                Parser.get(IdentifierParser.class),
                new Optional(VariableDeclarationOpt0Parser.class),
                new WordParser(";")
            );
        }
    }

    public static class ExpressionParser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(TermParser.class),
                new ZeroOrMore(ExpressionRepeat0Parser.class)
            );
        }
    }

    // Factor は3択の ChoiceBody なので LazyChoice を継承
    // 第1候補 '(' Expression ')' は複数要素なので匿名の TinyCalcLazyChain に
    public static class FactorParser extends LazyChoice {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new TinyCalcLazyChain() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Parsers getLazyParsers() {
                        return new Parsers(
                            new WordParser("("),
                            Parser.get(ExpressionParser.class),
                            new WordParser(")")
                        );
                    }
                },
                Parser.get(NumberParser.class),
                Parser.get(IdentifierParser.class)
            );
        }
        @Override
        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return java.util.Optional.empty();
        }
    }

    // --- ファクトリ ---
    public static Parser getRootParser() {
        return Parser.get(TinyCalcParser.class);
    }
}
```

**要素変換ルール：**

| 文法要素 | 生成コード |
|---|---|
| `'var'` | `new WordParser("var")` |
| `NUMBER`（token 宣言あり） | `Parser.get(NumberParser.class)` |
| `Expression`（ルール参照） | `Parser.get(ExpressionParser.class)` |
| `{ VariableDeclaration }`（単一 RuleRef の繰り返し） | `new ZeroOrMore(VariableDeclarationParser.class)` |
| `{ ( '+' \| '-' ) Term }`（複合 body の繰り返し） | ヘルパークラス + `new ZeroOrMore(ExpressionRepeat0Parser.class)` |
| `[ 'set' Expression ]`（複合 body の Optional） | ヘルパークラス + `new Optional(VariableDeclarationOpt0Parser.class)` |
| `( 'var' \| 'variable' )`（グループ） | ヘルパークラス `extends LazyChoice` + `Parser.get(VariableDeclarationGroup0Parser.class)` |

**ヘルパークラスの命名規則：**

| パターン | クラス名 |
|---|---|
| `{ 複合body }`（繰り返し） | `{RuleName}Repeat{N}Parser` |
| `[ 複合body ]`（Optional） | `{RuleName}Opt{N}Parser` |
| `( body )`（グループ） | `{RuleName}Group{N}Parser` |

N はルール内での 0 始まり連番。

---

### MapperGenerator

`TinyCalcMapper.java` を生成する。`@mapping` 付きルールに対応する `to{ClassName}(Token)` メソッドのスケルトンを出力する。

**生成される `TinyCalcMapper.java` の構造：**

```java
package org.unlaxer.tinycalc.generated;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class TinyCalcMapper {
    private TinyCalcMapper() {}

    /**
     * TinyCalc ソース文字列をパースして AST に変換する。
     * NOTE: TinyCalcParsers が生成・配置されてから実装を完成させる。
     */
    public static TinyCalcAST.TinyCalcProgram parse(String source) {
        // TODO: TinyCalcParsers が生成されたら実装する
        // StringSource stringSource = StringSource.createRootSource(source);
        // try (ParseContext context = new ParseContext(stringSource)) {
        //     Parser rootParser = TinyCalcParsers.getRootParser();
        //     Parsed parsed = rootParser.parse(context);
        //     if (!parsed.isSucceeded()) {
        //         throw new IllegalArgumentException("パース失敗: " + source);
        //     }
        //     return toTinyCalcProgram(parsed.getRootToken());
        // }
        throw new UnsupportedOperationException("TinyCalcParsers: 未実装");
    }

    // --- 変換メソッド（スケルトン） ---

    static TinyCalcAST.TinyCalcProgram toTinyCalcProgram(Token token) {
        // TODO: extract declarations
        // TODO: extract expression
        return new TinyCalcAST.TinyCalcProgram(
            null, // declarations
            null  // expression
        );
    }

    static TinyCalcAST.VarDecl toVarDecl(Token token) {
        // TODO: extract keyword, name, init
        return new TinyCalcAST.VarDecl(null, null, null);
    }

    // BinaryExpr は Expression と Term の両方に @mapping があるが、1つだけ生成
    static TinyCalcAST.BinaryExpr toBinaryExpr(Token token) {
        // TODO: extract left, op, right
        return new TinyCalcAST.BinaryExpr(null, null, null);
    }

    // --- ユーティリティ ---

    /** 指定パーサークラスの子孫 Token を深さ優先で探す */
    static List<Token> findDescendants(Token token, Class<? extends Parser> parserClass) { ... }

    /** シングルクォートで囲まれた文字列から引用符を除去する */
    static String stripQuotes(String quoted) { ... }
}
```

**生成後の作業（手動実装箇所）：**

`TinyCalcMapper` の `to{ClassName}` メソッドは TODO コメント付きのスケルトンとして生成される。`findDescendants()` を使って実際のフィールド抽出ロジックを実装する。

```java
// 実装例
static TinyCalcAST.BinaryExpr toBinaryExpr(Token token) {
    // 左辺 Factor または再帰的 BinaryExpr を取得
    List<Token> leftTokens = findDescendants(token, TermParser.class);
    // op（'+'/'-') を取得
    // right を取得
    ...
}
```

---

### EvaluatorGenerator

`TinyCalcEvaluator.java` を生成する。型パラメーター `<T>` を持つ抽象クラスで、AST ノードの型に応じたメソッドをオーバーライドして評価ロジックを実装する。

**生成される `TinyCalcEvaluator.java` の構造：**

```java
package org.unlaxer.tinycalc.generated;

public abstract class TinyCalcEvaluator<T> {

    private DebugStrategy debugStrategy = DebugStrategy.NOOP;

    public void setDebugStrategy(DebugStrategy strategy) {
        this.debugStrategy = strategy;
    }

    /** パブリックエントリーポイント。デバッグフックを挟んで evalInternal を呼ぶ */
    public T eval(TinyCalcAST node) {
        debugStrategy.onEnter(node);
        T result = evalInternal(node);
        debugStrategy.onExit(node, result);
        return result;
    }

    /** sealed switch によるディスパッチ */
    private T evalInternal(TinyCalcAST node) {
        return switch (node) {
            case TinyCalcAST.TinyCalcProgram n -> evalTinyCalcProgram(n);
            case TinyCalcAST.VarDecl n        -> evalVarDecl(n);
            case TinyCalcAST.BinaryExpr n     -> evalBinaryExpr(n);
        };
    }

    // 各 @mapping クラスに対応する抽象メソッド
    protected abstract T evalTinyCalcProgram(TinyCalcAST.TinyCalcProgram node);
    protected abstract T evalVarDecl(TinyCalcAST.VarDecl node);
    protected abstract T evalBinaryExpr(TinyCalcAST.BinaryExpr node);    // Expression と Term で共有

    // --- デバッグ用ストラテジー ---

    public interface DebugStrategy {
        void onEnter(TinyCalcAST node);
        void onExit(TinyCalcAST node, Object result);

        DebugStrategy NOOP = new DebugStrategy() {
            public void onEnter(TinyCalcAST node) {}
            public void onExit(TinyCalcAST node, Object result) {}
        };
    }

    /** eval() の呼び出し回数をカウントするデバッグ実装 */
    public static class StepCounterStrategy implements DebugStrategy {
        private int step = 0;
        private final java.util.function.BiConsumer<Integer, TinyCalcAST> onStep;

        public StepCounterStrategy(java.util.function.BiConsumer<Integer, TinyCalcAST> onStep) {
            this.onStep = onStep;
        }

        @Override
        public void onEnter(TinyCalcAST node) { onStep.accept(++step, node); }

        @Override
        public void onExit(TinyCalcAST node, Object result) {}
    }
}
```

**エバリュエーターの実装例（四則演算の評価）：**

```java
public class TinyCalcCalculator extends TinyCalcEvaluator<Double> {

    @Override
    protected Double evalTinyCalcProgram(TinyCalcAST.TinyCalcProgram node) {
        // 変数宣言を処理してから最終式を評価
        node.declarations().forEach(d -> eval(d));
        return eval(node.expression());
    }

    @Override
    protected Double evalVarDecl(TinyCalcAST.VarDecl node) {
        // 変数を環境に登録（実装省略）
        return 0.0;
    }

    @Override
    protected Double evalBinaryExpr(TinyCalcAST.BinaryExpr node) {
        Double left  = eval(node.left());
        Double right = eval(node.right());
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default  -> throw new IllegalArgumentException("Unknown op: " + node.op());
        };
    }
}
```

---

### LSPGenerator

`{Name}LanguageServer.java` を生成する。lsp4j を使った LSP サーバーで、以下の機能をスケルトンとして含む。

| 機能 | 実装内容 |
|---|---|
| `initialize` | TextDocumentSync.Full + Completion + Hover + SemanticTokens |
| `completion` | grammar の `TerminalElement` から自動抽出したキーワード一覧を返す |
| `hover` | パース成功時は `"Valid {Name}"`、失敗時は `"Parse error at offset N"` |
| `semanticTokensFull` | 有効範囲（type=0 緑）+ 無効範囲（type=1 赤）の 2 トークン |
| `didOpen / didChange` | `{Name}Parsers.getRootParser()` でパースし診断（Diagnostic）を publish |

**生成される `TinyCalcLanguageServer.java` の主要部分：**

```java
public class TinyCalcLanguageServer implements LanguageServer, LanguageClientAware {

    private static final List<String> KEYWORDS =
        List.of("var", "variable", "set", "(", ")", ";", "+", "-", "*", "/");

    public ParseResult parseDocument(String uri, String content) {
        Parser parser = TinyCalcParsers.getRootParser();
        ParseContext context = new ParseContext(StringSource.createRootSource(content));
        Parsed result = parser.parse(context);
        // ...publishDiagnostics
    }

    static class TinyCalcLanguageServerTextDocumentService implements TextDocumentService {
        // didOpen, didChange, completion, hover, semanticTokensFull ...
    }
    static class TinyCalcLanguageServerWorkspaceService implements WorkspaceService { ... }
}
```

---

### LSPLauncherGenerator

`{Name}LspLauncher.java` を生成する。stdio 経由で LSP サーバーを起動する `main` クラス。

```java
public class TinyCalcLspLauncher {
    public static void main(String[] args) throws IOException {
        TinyCalcLanguageServer server = new TinyCalcLanguageServer();
        Launcher<LanguageClient> launcher =
            LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening();
    }
}
```

### DAPGenerator

`{Name}DebugAdapter.java` を生成する。DAP (Debug Adapter Protocol) over stdio で動作し、
VS Code から `launch` リクエストを受け取ってファイルをパース・**トークン単位ステップ実行**する。

| リクエスト / イベント | 動作 |
|---|---|
| `initialize` | `supportsConfigurationDoneRequest: true` を返す |
| `launch` | `program` パスと `stopOnEntry` フラグを記録し、`initialized` イベントを発火 |
| `configurationDone` | `parseAndCollectSteps()` でパース＆ステップ点収集。`stopOnEntry: false` なら即終了、`true` なら `stopped(entry)` |
| `setBreakpoints` | 要求された行番号を `breakpointLines` に保存し、全件 `verified: true` で返す |
| `next` (F10) | `stepIndex++`。次のトークンがあれば `stopped(step)`、なければ `terminated` |
| `continue` (F5) | `findBreakpointIndex()` で次のブレークポイントを探し、あれば `stopped(breakpoint)`、なければ `terminated` |
| `threads` | スレッド "main"（id=1）を 1 件返す |
| `stackTrace` | 現在トークンの `offsetFromRoot()` から行/列を計算し、ソースファイルを強調表示 |
| `scopes` | ステップ実行中は `"Current Token"` スコープ（`variablesReference=1`）を返す |
| `variables` | 現在トークンのテキストとパーサークラス名を変数として返す |
| `disconnect` | `System.exit(0)` |

**`parseAndCollectSteps()` の処理:**

1. `launch` args の `program` ファイルを読み込む
2. `{Name}Parsers.getRootParser()` でパース
3. 成功 → `result.getConsumed().filteredChildren` をステップ点リストとして収集（空の場合はルートトークンをフォールバック）
4. 失敗 → `"Parse error at offset N"` を stderr として出力し `terminated`

**ステップ実行の仕組み（トークンツリー歩き）:**

```
launch → configurationDone
    ↓ parseAndCollectSteps()
    ↓ [filteredChildren = [expr1, expr2, expr3, ...]]
    ↓ stopOnEntry=true → stopped("entry")  ← stepIndex=0, expr1 がエディタで強調
F10 → next()                               ← stepIndex=1, expr2 が強調
F10 → next()                               ← stepIndex=2, expr3 が強調
F10 → next()                               ← stepIndex >= size → terminated
```

**ブレークポイントの仕組み:**

```
setBreakpoints([line=3])  ← breakpointLines = {3}
launch → configurationDone
    ↓ parseAndCollectSteps()
    ↓ findBreakpointIndex(-1) → expr at line 3 found → stepIndex=2
    ↓ stopped("breakpoint")                    ← line 3 がエディタで強調
F5 → continue_()
    ↓ findBreakpointIndex(2) → no more → terminated
```

`stackTrace()` では `token.source.offsetFromRoot().value()` でソース先頭からの文字オフセットを取得し、
改行カウントで 1-based の行/列に変換する。`getLineForToken()` ヘルパーはブレークポイント行照合にも共用する。

```java
// 生成される TinyCalcDebugAdapter.java（抜粋）
public class TinyCalcDebugAdapter implements IDebugProtocolServer {

    private List<Token> stepPoints = new ArrayList<>();
    private int stepIndex = 0;
    private String sourceContent = "";

    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        stepIndex++;
        if (stepIndex >= stepPoints.size()) {
            sendOutput("stdout", "Completed: " + pendingProgram + "\n");
            sendTerminated();
        } else {
            StoppedEventArguments stopped = new StoppedEventArguments();
            stopped.setReason("step");
            stopped.setThreadId(1);
            client.stopped(stopped);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        Token current = stepPoints.get(stepIndex);
        int charOffset = current.source.offsetFromRoot().value();
        int line = 0, col = 0;
        for (int i = 0; i < charOffset && i < sourceContent.length(); i++) {
            if (sourceContent.charAt(i) == '\n') { line++; col = 0; }
            else { col++; }
        }
        StackFrame frame = new StackFrame();
        frame.setLine(line + 1);   // DAP は 1-based
        frame.setColumn(col + 1);
        ...
    }
}
```

---

### DAPLauncherGenerator

`{Name}DapLauncher.java` を生成する。`DSPLauncher.createServerLauncher` で stdio 接続を確立する main クラス。

```java
public class TinyCalcDapLauncher {
    public static void main(String[] args) throws IOException {
        TinyCalcDebugAdapter adapter = new TinyCalcDebugAdapter();
        Launcher<IDebugProtocolClient> launcher =
            DSPLauncher.createServerLauncher(adapter, System.in, System.out);
        adapter.connect(launcher.getRemoteProxy());
        launcher.startListening();
    }
}
```

**起動方法（VS Code 拡張から）：**

LSP と同じ fat jar に両方のクラスが含まれるため、`-jar` ではなく `-cp` で起動して main クラスを指定する。

```
java --enable-preview -cp tinycalc-lsp-server.jar \
    org.unlaxer.tinycalc.generated.TinyCalcDapLauncher
```

---

## CodegenMain — CLI ツール

`CodegenMain` は `.ubnf` ファイルを読み込んで指定したジェネレーターを一括実行し、Java ソースをファイルに書き出す CLI ツール。

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --output  src/main/java \
  --generators Parser,LSP,Launcher,DAP,DAPLauncher
```

検証のみ（ソース出力なし）:

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --validate-only
```

Parser IR 検証モード:

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --validate-parser-ir path/to/parser-ir.json
```

UBNF から Parser IR を出力するモード:

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --export-parser-ir build/parser-ir.json
```

機械可読レポート出力:

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --validate-only \
  --report-format json \
  --report-file build/validation-report.json
```

JSON レポートは安定したトップレベル項目として
`reportVersion`, `schemaVersion`, `schemaUrl`, `toolVersion`, `argsHash`, `generatedAt`（UTC ISO-8601）, `mode`（`validate` / `generate`）を常に含む。
`toolVersion` は取得可能なら artifact の `Implementation-Version`、未設定時は `dev` を使う。
`argsHash` は raw argv ではなく意味的に正規化した CLI 設定の SHA-256 で、
`--report-file` や `--output-manifest` のような出力先フラグだけでは変化しない。
公開される v1 JSON schema は `docs/schema/report-v1.json` を参照。
NDJSON イベント schema は `docs/schema/report-v1.ndjson.json` を参照。
manifest schema は `docs/schema/manifest-v1.json` と `docs/schema/manifest-v1.ndjson.json` を参照。
Parser IR の draft schema は `docs/schema/parser-ir-v1.draft.json` を参照。
バリデーション失敗時の `issues[]` 要素は
`grammar`, `rule`, `code`, `severity`, `category`, `message`, `hint` を含む。
失敗レポートには `severityCounts` と `categoryCounts` の集計も含む。

| オプション | 説明 | デフォルト |
|---|---|---|
| `--grammar <file>` | `.ubnf` ファイルのパス | （必須） |
| `--output <dir>` | 出力ルートディレクトリ（package 構造で書き出す） | `--validate-only` 以外では必須 |
| `--generators <list>` | カンマ区切りの生成器名 | `Parser,LSP,Launcher` |
| `--validate-parser-ir <file>` | Parser IR JSON の検証のみ実行（文法解析/生成をスキップ） | （なし） |
| `--export-parser-ir <file>` | UBNF 文法から Parser IR JSON を出力（コード生成をスキップ） | （なし） |
| `--dry-run` | 生成ファイルを書き込まずに出力先だけ確認する | `false` |
| `--clean-output` | 生成予定ファイルを事前に削除してから生成する | `false` |
| `--overwrite never\|if-different\|always` | 既存ファイル上書きポリシー | `always` |
| `--fail-on none\|warning\|skipped\|conflict\|cleaned\|warnings-count>=N` | 追加の失敗判定ポリシー | `conflict` |
| `--strict` | warning をバリデーション失敗として扱う | `false` |
| `--help`, `-h` | 使用方法を表示して終了 | `false` |
| `--version`, `-v` | ツールバージョンを表示して終了 | `false` |
| `--validate-only` | 文法検証のみ実行（コード生成をスキップ） | `false` |
| `--report-format text\|json\|ndjson` | 出力/レポート形式 | `text` |
| `--report-file <path>` | レポート内容をファイル出力 | （なし） |
| `--output-manifest <path>` | 生成/検証アクションmanifest JSONを出力 | （なし） |
| `--manifest-format json\|ndjson` | manifest 出力形式（`--output-manifest`） | `json` |
| `--report-version 1` | JSON レポートスキーマのバージョン | `1` |
| `--report-schema-check` | JSON ペイロードを出力前にスキーマ検証する | `false` |
| `--warnings-as-json` | warning 診断を stderr に JSON で出力する（text モード） | `false` |

使用可能な生成器名: `AST`, `Parser`, `Mapper`, `Evaluator`, `LSP`, `Launcher`, `DAP`, `DAPLauncher`
`--generators` はカンマ区切り値をトリムし、空要素はエラーとして拒否する（例: `"AST, LSP"` は有効）。
`--report-schema-check` で失敗した場合のメッセージは `E-REPORT-SCHEMA-*` で始まる。
`--warnings-as-json` は warning をバリデーション失敗JSONと同じ形で出力する。
JSON ペイロードには `warningsCount` が含まれ、`issues[]` を走査せず warning 件数を確認できる。
生成JSONには `writtenCount`, `skippedCount`, `conflictCount`, `dryRunCount` も含まれ、ポリシー失敗時は `failReasonCode` も含まれる。
`ndjson` は 1 行 1 JSON（file イベント + summary イベント）でストリーミング連携しやすい。
`ndjson` は CLI 失敗（引数/使用方法エラーと実行時エラー。例: 未知の generator）で `cli-error` イベントも出力する。
`cli-error` のフィールドは `code`, `message`, nullable な `detail`, `availableGenerators`（通常は空配列）で安定化している。
`code` は `E-[A-Z0-9-]+` パターンに従う。
代表的な `code` は `E-CLI-USAGE`, `E-CLI-UNKNOWN-GENERATOR`, `E-CLI-UNSAFE-CLEAN-OUTPUT`, `E-PARSER-IR-EXPORT`, `E-IO`, `E-RUNTIME`。
`ndjson` モードでは `stdout` は JSON 行のみ（人間向け進捗テキストは出力しない）。
`ndjson` の生成モードでは conflict/fail-on の人間向けメッセージを `stderr` に出さない。
`ndjson` の検証失敗経路でも `stderr` は JSON 行のみ。
`ndjson` の生成失敗経路ではイベントは `stdout` に出力し、`stderr` は空になる。
`ndjson` で `--report-file` を使う場合、ファイルには NDJSON イベント包みではなく生の JSON payload を保存する。
warning のみで成功する検証実行（`--fail-on none`）では、ファイルには最終的な `validate` 成功 payload を保存する。
`--fail-on warnings-count>=N` で warning 件数が `N` 以上なら失敗させられる。
`--report-schema-check` は `--output-manifest` 指定時に manifest ペイロードも検証する。

終了コード:

| コード | 意味 |
|---|---|
| `0` | 成功 |
| `2` | CLI 引数/使用方法エラー |
| `3` | 文法バリデーション失敗 |
| `4` | 生成処理/実行時エラー |
| `5` | strict モードで warning により失敗 |

---

## VS Code 拡張（VSIX）のビルド

`tinycalc-vscode/` ディレクトリが TinyCalc 言語の VS Code 拡張サンプルを含む。
`mvn verify` 1 コマンドで **文法定義 → コード生成 → fat jar → VSIX** までを自動実行する。

### 前提

```bash
# unlaxer-dsl を Maven ローカルリポジトリにインストール（初回のみ）
cd unlaxer-dsl
mvn install -DskipTests
```

### ビルド

```bash
cd tinycalc-vscode
mvn verify
# → target/tinycalc-lsp-0.1.0.vsix
```

### Maven フェーズ別の処理内容

| フェーズ | 処理 | 出力先 |
|---|---|---|
| `generate-sources` | `CodegenMain` が `grammar/tinycalc.ubnf` を読み込み Java ソースを生成（Parser, LSP, Launcher, DAP, DAPLauncher） | `target/generated-sources/tinycalc/` |
| `compile` | 生成された 5 クラスをコンパイル | `target/classes/` |
| `package` | `maven-shade-plugin` で fat jar を作成（LSP・DAP の両クラスを含む）し `server-dist/` にコピー | `target/tinycalc-lsp-server.jar` |
| `verify` | `npm install` → `npx vsce package`（内部で TypeScript コンパイルも実行） | `target/tinycalc-lsp-0.1.0.vsix` |

### インストール

```bash
code --install-extension tinycalc-vscode/target/tinycalc-lsp-0.1.0.vsix
```

`.tcalc` ファイルを開くと LSP サーバーが自動起動する。

DAP のデバッグは `F5` で起動（または `launch.json` を作成）。
`.tcalc` ファイルをエディタで開いた状態で `F5` → `TinyCalc Debug` を選択すると、
そのファイルがパースされ Debug Console に結果が表示される。

**通常実行（`stopOnEntry: false`）：**

```json
// .vscode/launch.json の例
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "tinycalc",
      "request": "launch",
      "name": "Debug TinyCalc File",
      "program": "${file}"
    }
  ]
}
```

**ステップ実行（`stopOnEntry: true`）：**

`stopOnEntry: true` を設定するとトークン単位のステップ実行が可能になる。
パース後に先頭トークンで一時停止し、`F10`（next）でトークンを 1 つずつ進める。
Variables パネルに現在トークンのテキストとパーサークラス名が表示される。

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "tinycalc",
      "request": "launch",
      "name": "Step TinyCalc File",
      "program": "${file}",
      "stopOnEntry": true
    }
  ]
}
```

| 操作 | 説明 |
|---|---|
| `F5` (Continue) | 次のブレークポイントまで実行（なければ終了） |
| `F10` (Next) | 次のトークンへ 1 つ進む |
| Variables パネル | 現在トークンのテキストとパーサークラス名 |
| エディタのハイライト | 現在トークンの行/列を自動で示す |
| ブレークポイント | ガター（行番号左）をクリックして設定。`verified: true` で即時有効になる |

**LSP と DAP は同じ fat jar に含まれる。** 拡張は LSP を `-jar` で、DAP を `-cp … TinyCalcDapLauncher` で起動する。

---

## チュートリアル 1: TinyCalc

TinyCalc は変数宣言と四則演算をサポートする小さな計算機 DSL である。以下は文法定義から評価までの流れ。

### ステップ 1: 文法を定義する

`examples/tinycalc.ubnf` を参照（前述の TinyCalc 文法）。

### ステップ 2: コードを生成する

```java
String src = Files.readString(Path.of("examples/tinycalc.ubnf"));
GrammarDecl grammar = UBNFMapper.parse(src).grammars().get(0);

// 4 種類のコードを生成して src/main/java/org/unlaxer/tinycalc/generated/ に保存
for (CodeGenerator gen : List.of(
        new ASTGenerator(),
        new ParserGenerator(),
        new MapperGenerator(),
        new EvaluatorGenerator())) {
    var result = gen.generate(grammar);
    var path = Path.of("src/main/java",
        result.packageName().replace('.', '/'),
        result.className() + ".java");
    Files.createDirectories(path.getParent());
    Files.writeString(path, result.source());
}
```

### ステップ 3: TinyCalcMapper の parse() を実装する

生成された `TinyCalcMapper.java` の `parse()` メソッドの TODO 部分を完成させる。

```java
public static TinyCalcAST.TinyCalcProgram parse(String source) {
    StringSource stringSource = StringSource.createRootSource(source);
    try (ParseContext context = new ParseContext(stringSource)) {
        Parser rootParser = TinyCalcParsers.getRootParser();
        Parsed parsed = rootParser.parse(context);
        if (!parsed.isSucceeded()) {
            throw new IllegalArgumentException("パース失敗: " + source);
        }
        return toTinyCalcProgram(parsed.getRootToken());
    }
}
```

### ステップ 4: エバリュエーターを実装して評価する

```java
TinyCalcAST.TinyCalcProgram ast = TinyCalcMapper.parse("1 + 2 * 3");
TinyCalcCalculator calc = new TinyCalcCalculator();
Double result = calc.eval(ast);
System.out.println(result); // 7.0
```

### ステップ 5: 外部 Parser Adapter で ScopeTree メタデータを使う

UBNF 以外のパーサーでは、ルール単位メタデータから parser-IR の `scopeEvents` を生成できる。

```java
// ParserGenerator が生成するメタデータ API
Map<String, TinyCalcParsers.ScopeMode> modeByRule = TinyCalcParsers.getScopeTreeModeByRule();

List<Object> scopeEvents = ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRulesAnyMode(
    "TinyCalc",
    modeByRule,
    nodes
);
```

実行可能な最小サンプル:
- `src/test/java/org/unlaxer/dsl/ParserIrAdapterContractTest.java` (`ScopeTreeSampleAdapter`)

---

## チュートリアル 2: UBNF の VS Code 拡張を作る

このチュートリアルでは `grammar/ubnf.ubnf`（UBNF 自身の文法定義）を入力として、
`.ubnf` ファイル対応の VS Code 拡張（VSIX）をゼロからビルドする手順を解説する。

TinyCalc と異なり **「文法定義自体に手書き実装への依存がある」** という特殊な制約があるため、
その回避策も含めて説明する。

---

### 背景と技術的制約

`grammar/ubnf.ubnf` には次のトークン宣言がある。

```ubnf
token STRING = SingleQuotedParser
```

`SingleQuotedParser` は `unlaxer-common` の `UBNFParsers.java` の **inner class** であり、
通常の `--generators Parser` でパーサーを自動生成すると実行時に `ClassNotFoundException` が発生する。

**解決策**: `--generators LSP,Launcher` のみ生成（Parser は生成しない）し、
`getRootParser()` を手書きの `org.unlaxer.dsl.bootstrap.UBNFParsers` に委譲する
shim クラスを `src/main/java/` に手動で置く。

```
自動生成:  UBNFLanguageServer.java  (LSP サーバー)
           UBNFLspLauncher.java     (main クラス)
手動 shim: UBNFParsers.java         (bootstrap の getRootParser() に委譲)
```

---

### ステップ 1: ディレクトリを作る

```bash
mkdir -p unlaxer-dsl/ubnf-vscode/src/main/java/org/unlaxer/dsl/bootstrap/generated
mkdir -p unlaxer-dsl/ubnf-vscode/syntaxes
```

---

### ステップ 2: UBNFParsers.java（shim）を作る

`ubnf-vscode/src/main/java/org/unlaxer/dsl/bootstrap/generated/UBNFParsers.java`

```java
package org.unlaxer.dsl.bootstrap.generated;

import org.unlaxer.parser.Parser;

public class UBNFParsers {
    public static Parser getRootParser() {
        return org.unlaxer.dsl.bootstrap.UBNFParsers.getRootParser();
    }
}
```

`LSPGenerator` が生成する `UBNFLanguageServer.java` は
`org.unlaxer.dsl.bootstrap.generated.UBNFParsers.getRootParser()` を呼び出す。
この shim がその呼び出しを手書き bootstrap 実装に橋渡しする。

---

### ステップ 3: pom.xml を作る

TinyCalc の `pom.xml` との主な差分は次の 4 点。

| 設定 | tinycalc-vscode | ubnf-vscode |
|---|---|---|
| `--grammar` | `grammar/tinycalc.ubnf` | `../grammar/ubnf.ubnf` |
| `--generators` | `Parser,LSP,Launcher` | `LSP,Launcher` |
| shade `mainClass` | `TinyCalcLspLauncher` | `org.unlaxer.dsl.bootstrap.generated.UBNFLspLauncher` |
| fat jar 名 | `tinycalc-lsp-server` | `ubnf-lsp-server` |

`build-helper-maven-plugin` は生成された LSP/Launcher コードを
`target/generated-sources/ubnf/` からコンパイル対象に追加するために引き続き必要。

Maven フェーズ別の処理内容：

| フェーズ | 処理 | 出力先 |
|---|---|---|
| `generate-sources` | `CodegenMain` が `grammar/ubnf.ubnf` を読み込み LSP・Launcher を生成 | `target/generated-sources/ubnf/` |
| `compile` | shim + 生成コード（`UBNFLanguageServer`, `UBNFLspLauncher`）をコンパイル | `target/classes/` |
| `package` | `maven-shade-plugin` で fat jar を作成し `server-dist/` にコピー | `target/ubnf-lsp-server.jar` |
| `verify` | `npm install` → `npx vsce package`（TypeScript コンパイルも実行） | `target/ubnf-lsp-0.1.0.vsix` |

---

### ステップ 4: VS Code 拡張の設定ファイルを作る

**`package.json`** — 言語 ID `ubnf`、拡張子 `.ubnf`、設定キー prefix `ubnfLsp.server.*`

```json
{
  "name": "ubnf-lsp",
  "displayName": "UBNF (LSP)",
  "activationEvents": ["onLanguage:ubnf"],
  "contributes": {
    "languages": [{ "id": "ubnf", "extensions": [".ubnf"] }],
    "grammars": [{
      "language": "ubnf",
      "scopeName": "source.ubnf",
      "path": "./syntaxes/ubnf.tmLanguage.json"
    }]
  }
}
```

**`src/extension.ts`** — `tinycalcLsp` → `ubnfLsp`、jar パスを `server-dist/ubnf-lsp-server.jar` に変更するだけ。

---

### ステップ 5: シンタックスハイライト定義を作る

`syntaxes/ubnf.tmLanguage.json` に以下のパターンを定義する。

| パターン | スコープ |
|---|---|
| `//.*$` | `comment.line.double-slash.ubnf` |
| `\bgrammar\b`, `\btoken\b` | `keyword.control.ubnf` |
| `::=`, `\|`, `;` | `keyword.operator.ubnf` |
| `@root`, `@mapping`, `@whitespace`, `@interleave`, `@backref`, `@scopeTree`, `@leftAssoc`, `@rightAssoc`, `@precedence` | `storage.modifier.ubnf` |
| `'[^']*'` | `string.quoted.single.ubnf` |
| `[A-Z][A-Z_0-9]*` | `entity.name.type.ubnf` |

---

### ステップ 6: ビルドする

```bash
# 事前に unlaxer-dsl 本体をローカルリポジトリにインストール（初回のみ）
cd unlaxer-dsl
mvn install -DskipTests

# ubnf-vscode をビルド
cd ubnf-vscode
mvn verify
```

成功すると `target/ubnf-lsp-0.1.0.vsix` が生成される。

```
 DONE  Packaged: target/ubnf-lsp-0.1.0.vsix (7 files, 2.19 MB)
```

VSIX の内容確認：

```bash
unzip -l target/ubnf-lsp-0.1.0.vsix
# extension/server-dist/ubnf-lsp-server.jar  ← fat jar（約 2.4 MB）
# extension/out/extension.js                 ← コンパイル済み TypeScript
# extension/syntaxes/ubnf.tmLanguage.json
# extension/language-configuration.json
# extension/package.json
```

---

### ステップ 7: VS Code にインストールする

```bash
code --install-extension target/ubnf-lsp-0.1.0.vsix
```

VS Code を再読み込み（`Ctrl+Shift+P` → `Developer: Reload Window`）後、
`.ubnf` ファイルを開くと次の機能が有効になる。

| 機能 | 内容 |
|---|---|
| シンタックスハイライト | コメント・キーワード・演算子・アノテーション・文字列・型名を色分け |
| パース診断 | 構文エラーを赤波線で表示 |
| ホバー | カーソル位置のパース状態を表示（`Valid UBNF` / `Parse error at offset N`） |
| 補完 | `grammar`, `token` 等のキーワード候補を表示 |

---

### TinyCalc との比較まとめ

| 項目 | tinycalc-vscode | ubnf-vscode |
|---|---|---|
| 文法ファイル | `grammar/tinycalc.ubnf` | `../grammar/ubnf.ubnf` |
| `--generators` | `Parser,LSP,Launcher,DAP,DAPLauncher` | `LSP,Launcher`（Parser は shim、DAP は未対応） |
| shim | 不要 | `generated/UBNFParsers.java` が必要 |
| DAP サポート | あり（`TinyCalcDebugAdapter` + `TinyCalcDapLauncher`） | なし |
| 言語 ID | `tinycalc` | `ubnf` |
| 拡張子 | `.tcalc` | `.ubnf` |
| fat jar | `tinycalc-lsp-server.jar`（LSP + DAP） | `ubnf-lsp-server.jar`（LSP のみ） |

---

## プロジェクト構造

```
unlaxer-dsl/
├── grammar/
│   └── ubnf.ubnf              UBNF 自身を UBNF で記述した自己ホスティング文法
├── examples/
│   └── tinycalc.ubnf          TinyCalc サンプル文法
├── src/
│   ├── main/java/org/unlaxer/dsl/
│   │   ├── CodegenMain.java       CLI ツール（ubnf → Java ソース一括生成）
│   │   ├── bootstrap/
│   │   │   ├── UBNFAST.java       UBNF の AST（sealed interface + record）
│   │   │   ├── UBNFParsers.java   Bootstrap パーサー（手書き）
│   │   │   └── UBNFMapper.java    パースツリー → AST マッパー
│   │   └── codegen/
│   │       ├── CodeGenerator.java         共通インターフェース
│   │       ├── ASTGenerator.java          XxxAST.java 生成器
│   │       ├── ParserGenerator.java       XxxParsers.java 生成器
│   │       ├── MapperGenerator.java       XxxMapper.java 生成器
│   │       ├── EvaluatorGenerator.java    XxxEvaluator.java 生成器
│   │       ├── LSPGenerator.java          XxxLanguageServer.java 生成器
│   │       ├── LSPLauncherGenerator.java  XxxLspLauncher.java 生成器
│   │       ├── DAPGenerator.java          XxxDebugAdapter.java 生成器
│   │       └── DAPLauncherGenerator.java  XxxDapLauncher.java 生成器
│   └── test/java/org/unlaxer/dsl/
│       ├── UBNFParsersTest.java
│       ├── UBNFMapperTest.java
│       └── codegen/
│           ├── ASTGeneratorTest.java
│           ├── ParserGeneratorTest.java
│           ├── MapperGeneratorTest.java
│           ├── EvaluatorGeneratorTest.java
│           ├── LSPGeneratorTest.java
│           ├── LSPLauncherGeneratorTest.java
│           ├── LSPCompileVerificationTest.java
│           ├── DAPGeneratorTest.java
│           ├── DAPCompileVerificationTest.java
│           ├── CompileVerificationTest.java    生成 Java ソースのコンパイル検証
│           ├── SelfHostingTest.java            生成 UBNFParsers の構造・コンパイル検証
│           └── SelfHostingRoundTripTest.java   生成パーサーで ubnf.ubnf を実際にパース（ラウンドトリップ）
├── tinycalc-vscode/           VS Code 拡張サンプル（TinyCalc、LSP + DAP）
│   ├── pom.xml                Maven ビルド設定（codegen → compile → jar → VSIX）
│   ├── grammar/
│   │   └── tinycalc.ubnf      拡張のソース文法（CodegenMain への入力）
│   ├── src/
│   │   └── extension.ts       VS Code クライアント（LSP + DAP ファクトリ登録）
│   ├── syntaxes/
│   │   └── tinycalc.tmLanguage.json  TextMate 文法（シンタックスハイライト）
│   ├── language-configuration.json
│   ├── package.json
│   └── target/                ← ビルド成果物（gitignore）
│       ├── generated-sources/ ← 生成 Java ソース（Parser, LSP, Launcher, DAP, DAPLauncher）
│       ├── tinycalc-lsp-server.jar  ← fat jar（LSP + DAP 両方含む）
│       └── tinycalc-lsp-0.1.0.vsix ← VS Code 拡張パッケージ
├── ubnf-vscode/               VS Code 拡張（UBNF 自身の文法エディタ）
│   ├── pom.xml                Maven ビルド設定（LSP,Launcher のみ生成）
│   ├── src/
│   │   ├── extension.ts       VS Code クライアント（TypeScript）
│   │   └── main/java/org/unlaxer/dsl/bootstrap/generated/
│   │       └── UBNFParsers.java   手書き shim（bootstrap.UBNFParsers に委譲）
│   ├── syntaxes/
│   │   └── ubnf.tmLanguage.json  TextMate 文法（シンタックスハイライト）
│   ├── language-configuration.json
│   ├── package.json
│   └── target/                ← ビルド成果物（gitignore）
│       ├── generated-sources/ ← 生成 Java ソース（LSP, Launcher のみ）
│       ├── ubnf-lsp-server.jar    ← fat jar
│       └── ubnf-lsp-0.1.0.vsix   ← VS Code 拡張パッケージ
└── pom.xml
```

---

## 自己ホスティング

`unlaxer-dsl` は **自己ホスティング**を達成している。
`grammar/ubnf.ubnf`（UBNF 文法自身を UBNF で記述したファイル）を `ParserGenerator` で処理すると
`org.unlaxer.dsl.bootstrap.generated.UBNFParsers` が生成される。
この生成パーサーは `ubnf.ubnf` 自身を完全にパースできることが `SelfHostingRoundTripTest` で検証済みである。

### ラウンドトリップ検証フロー

```
grammar/ubnf.ubnf
    │
    ▼  手書き bootstrap (UBNFParsers + UBNFAST + UBNFMapper)
GrammarDecl (AST)
    │
    ▼  ParserGenerator.generate()
generated/UBNFParsers.java (ソース文字列)
    │
    ▼  javax.tools.JavaCompiler (--enable-preview --release 21)
generated/UBNFParsers.class (インメモリコンパイル → tmpDir)
    │
    ▼  URLClassLoader + getRootParser() 反射呼び出し
Parser (生成パーサーのルートパーサー)
    │
    ▼  parser.parse(ParseContext(grammar/ubnf.ubnf))
Parsed (成功 + 全入力消費) ← SelfHostingRoundTripTest で検証
```

### 発見したバグと修正

自己ホスティング実装中に `UBNFMapper.toTokenDecl()` のバグを発見・修正した。

**バグ**: `token CLASS_NAME = IdentifierParser` の末尾の `IdentifierParser` は
手書き `UBNFParsers.IdentifierParser extends UBNFLazyChain` なので、
trailing SPACE（CPPComment を含む）がトークン source に含まれてしまう。
その結果 `source.toString().trim()` が `"IdentifierParser\n\n// コメント"` を返し、
`ParserGenerator` が `Parser.get(IdentifierParser\n\n// コメント.class)` という不正なコードを生成していた。

**修正**: `toTokenDecl()` に `firstWord()` ヘルパーを追加し、
最初の空白文字以降を除去して純粋なクラス名だけを取り出すように修正した。

```java
// 修正前
String parserClass = identifiers.get(1).source.toString().trim();

// 修正後
String parserClass = firstWord(identifiers.get(1).source.toString());
// firstWord(): 最初の空白文字以前の部分だけを返す
```

### 現状の自己ホスティングの範囲

| コンポーネント | 自動生成 | 説明 |
|---|---|---|
| `UBNFParsers` (parser) | ✅ | `ParserGenerator` で生成・ラウンドトリップ検証済み |
| `UBNFAST` (AST) | ❌ | `ASTGenerator` は nested sealed interface を未生成 |
| `UBNFMapper` (mapper) | ❌ | `MapperGenerator` はスタブのみ（手書き実装が必要） |

`UBNFParsers` 以外の2コンポーネントの完全自動生成は将来の課題。

---

## ロードマップ

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 0 | UBNF 文法定義（`grammar/ubnf.ubnf`） | 完了 |
| Phase 1 | Bootstrap パーサー（`UBNFParsers.java`） | 完了 |
| Phase 2 | AST 定義・Bootstrap マッパー（`UBNFAST.java`, `UBNFMapper.java`） | 完了 |
| Phase 3 | ASTGenerator / EvaluatorGenerator / MapperGenerator 実装 | 完了 |
| Phase 4 | ParserGenerator 実装 | 完了 |
| Phase 5 | TinyCalc 統合テスト（`TinyCalcIntegrationTest`） | 完了 |
| Phase 6 | 自己ホスティングテスト（`SelfHostingTest`） | 完了 |
| Phase 7 | コンパイル検証テスト（`CompileVerificationTest`） | 完了 |
| Phase 8 | LSP サーバー自動生成（`LSPGenerator`, `LSPLauncherGenerator`, `CodegenMain`） | 完了 |
| Phase 9 | VSIX ワンコマンドビルド（`tinycalc-vscode/pom.xml`） | 完了 |
| Phase 9.5 | UBNF 自身の VS Code 拡張（`ubnf-vscode/`、shim パターンで実現） | 完了 |
| Phase 10 | DAP サポート自動生成（`DAPGenerator`, `DAPLauncherGenerator`） | 完了 |
| Phase 11 | 自己ホスティング（`grammar/ubnf.ubnf` から生成した `UBNFParsers` で `ubnf.ubnf` 自身をパース） | 完了 |
| Phase 12 | DAP トークン単位ステップ実行（F10 next・Variables パネル・stackTrace 行ハイライト） | 完了 |
| Phase 13 | DAP ブレークポイント対応（行番号照合・continue で次の BP まで実行） | 完了 |

---

## ライセンス

MIT License — Copyright (c) 2026 opaopa6969
