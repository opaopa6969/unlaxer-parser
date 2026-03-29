[English](./tutorial-tinycalc-level2.en.md) | [日本語](./tutorial-tinycalc-level2.ja.md) | [Index](./INDEX.ja.md)

---

# TinyCalc Level 2 -- 変数と if 式を追加して VSIX にパッケージする

> **登場人物**
> - **先輩**: unlaxer-parser の作者。TinyCalc Level 1（クイックスタート）で四則演算を教えた
> - **後輩**: Level 1 を完走した。四則演算パーサーは動いた。次は「本物の言語っぽいもの」を作りたい

---

## 目次

- [Part 1: Level 1 の振り返り](#part-1-level-1-の振り返り)
- [Part 2: 変数を追加する](#part-2-変数を追加する)
- [Part 3: 比較演算と Boolean を追加する](#part-3-比較演算と-boolean-を追加する)
- [Part 4: if 式を追加する](#part-4-if-式を追加する)
- [Part 5: テストで確認する](#part-5-テストで確認する)
- [Part 6: VSIX にパッケージする](#part-6-vsix-にパッケージする)
- [Part 7: まとめ](#part-7-まとめ)

---

## Part 1: Level 1 の振り返り

**後輩:** 先輩、Level 1 の電卓が動いて感動しました。文法20行、エバリュエータ20行で四則演算ができるなんて。

**先輩:** よかった。じゃあ今日は Level 2。変数と if 式を追加して、最後に VS Code 拡張（VSIX）にパッケージするところまでやろう。

**後輩:** おお、一気に本格的になりますね。

**先輩:** まず Level 1 の文法を確認しよう。

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc
  @whitespace: javaStyle

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token EOF        = EndOfSourceParser

  @root
  Formula ::= Expression EOF ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  Factor ::= NUMBER | '(' Expression ')' ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;
}
```

**後輩:** `Expression` → `Term` → `Factor` の3層で優先順位を表現するパターンですね。

**先輩:** そのとおり。Level 2 では、この文法に4つの機能を追加する。

| 機能 | 構文例 | 追加するもの |
|------|--------|------------|
| 変数宣言 | `var price set 100;` | `VariableDeclaration` ルール |
| 変数参照 | `$price * 1.1` | `VariableRef` ルール |
| 比較式 | `$price > 50` | `ComparisonExpression` ルール |
| if 式 | `if ($price > 50) { $price * 0.9 } else { $price }` | `IfExpression` ルール |

---

## Part 2: 変数を追加する

### 変数宣言

**先輩:** まず変数宣言。`var price set 100;` という構文にしよう。

```ubnf
@mapping(VarDecl, params=[name, init])
VariableDeclaration ::=
  'var' IDENTIFIER @name
  [ 'set' NumberExpression @init ]
  ';' ;
```

**後輩:** `@mapping(VarDecl, params=[name, init])` で、`name` と `init` の2つのフィールドを持つ AST ノードが生成されるんですね。

**先輩:** そう。`[ 'set' NumberExpression @init ]` は省略可能だから、`var price;`（初期値なし）も `var price set 100;`（初期値あり）も両方マッチする。

**後輩:** `[ ]` が Optional でしたね。

### 変数参照

**先輩:** 次に変数参照。`$price` のように `$` プレフィックスで変数を参照する。

```ubnf
@mapping(VariableRefExpr, params=[name])
VariableRef ::= '$' IDENTIFIER @name ;
```

**後輩:** シンプルですね。`$` の後に識別子が来る。

**先輩:** で、これを `Factor` に追加する。ここが重要なポイント。

```ubnf
Factor ::=
    '(' NumberExpression ')'
  | NUMBER
  | VariableRef ;
```

**後輩:** `Factor` の選択肢に `VariableRef` を追加するだけ！

**先輩:** そう。PEG の Ordered Choice だから、`NUMBER` をリテラルとして先に試して、マッチしなかったら `VariableRef` を試す。リテラル `123` は `$` で始まらないから `VariableRef` にマッチしないし、`$price` は数値じゃないから `NUMBER` にマッチしない。安全に分離できる。

### ルートルールの変更

**先輩:** ルートルールも変更する。変数宣言を0個以上受け付けてから式を評価する。

```ubnf
@root
@mapping(TinyCalcProgram, params=[declarations, expression])
TinyCalc ::=
  { VariableDeclaration } @declarations
  NumberExpression @expression
  EOF ;
```

**後輩:** `{ VariableDeclaration }` で0個以上の変数宣言を受け付けて、最後に `NumberExpression` を1つ評価する。

**先輩:** そのとおり。`Expression` を `NumberExpression` にリネームしたのは、Level 2 では Boolean 式も追加するから、区別するため。

---

## Part 3: 比較演算と Boolean を追加する

**先輩:** if 式を作るには、条件部分が必要。つまり Boolean 式。`$price > 50` みたいな比較式を追加しよう。

```ubnf
@mapping(ComparisonExpr, params=[left, op, right])
ComparisonExpression ::=
  NumberExpression @left
  CompareOp @op
  NumberExpression @right ;

CompareOp ::= '==' | '!=' | '<=' | '>=' | '<' | '>' ;
```

**後輩:** `NumberExpression` 同士を比較演算子でつなぐ。結果は Boolean になるんですね。

**先輩:** そう。`CompareOp` の選択順序に注意。`<=` を `<` より先に書くこと。PEG だから `<` が先にマッチすると `=` が残ってしまう。

**後輩:** あ、そうか。`<=` は2文字だから、1文字の `<` より先に試さないとダメですね。

**先輩:** Boolean の Factor も用意しよう。

```ubnf
BooleanFactor ::=
    ComparisonExpression
  | 'true'
  | 'false'
  | '(' BooleanExpression ')' ;

@mapping(BooleanAndExpr, params=[left, op, right])
@leftAssoc
BooleanExpression ::=
  BooleanFactor @left { '&&' @op BooleanFactor @right } ;
```

**後輩:** Boolean も同じパターン！ `BooleanFactor` がアトムで、`BooleanExpression` が `&&` で結合する。

**先輩:** `||` も追加したければ、`NumberExpression` と `Term` の関係と同じように、もう1層作ればいい。今回は `&&` だけにしておこう。

---

## Part 4: if 式を追加する

**先輩:** いよいよ if 式。

```ubnf
@mapping(IfExpr, params=[condition, thenExpr, elseExpr])
IfExpression ::=
  'if' '(' BooleanExpression @condition ')'
  '{' NumberExpression @thenExpr '}'
  'else'
  '{' NumberExpression @elseExpr '}' ;
```

**後輩:** パターンは同じですね。`@mapping` でノード型を宣言して、`@` でフィールドに紐づける。

**先輩:** if 式は NumberFactor に追加する。

```ubnf
Factor ::=
    IfExpression
  | '(' NumberExpression ')'
  | NUMBER
  | VariableRef ;
```

**後輩:** `IfExpression` を一番上に置くんですね。なぜですか？

**先輩:** PEG の Ordered Choice で、`if` キーワードで始まるものは `IfExpression` だけだから、最初に試しても他の選択肢とぶつからない。そしてキーワードで始まるものを先に置くのが良いプラクティス。

### 完成した Level 2 文法

**先輩:** 全体を見てみよう。

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc
  @whitespace: javaStyle
  @comment: { line: '//' }

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token EOF        = EndOfSourceParser

  // === Root ===

  @root
  @mapping(TinyCalcProgram, params=[declarations, expression])
  TinyCalc ::=
    { VariableDeclaration } @declarations
    NumberExpression @expression
    EOF ;

  // === Variable declaration ===

  @mapping(VarDecl, params=[name, init])
  VariableDeclaration ::=
    'var' IDENTIFIER @name
    [ 'set' NumberExpression @init ]
    ';' ;

  // === Variable reference ===

  @mapping(VariableRefExpr, params=[name])
  VariableRef ::= '$' IDENTIFIER @name ;

  // === Numeric expressions ===

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;

  NumberFactor ::=
      IfExpression
    | '(' NumberExpression ')'
    | NUMBER
    | VariableRef ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;

  // === Boolean expressions ===

  @mapping(ComparisonExpr, params=[left, op, right])
  ComparisonExpression ::=
    NumberExpression @left CompareOp @op NumberExpression @right ;

  CompareOp ::= '==' | '!=' | '<=' | '>=' | '<' | '>' ;

  BooleanFactor ::=
      ComparisonExpression
    | 'true'
    | 'false'
    | '(' BooleanExpression ')' ;

  @mapping(BooleanAndExpr, params=[left, op, right])
  @leftAssoc
  BooleanExpression ::=
    BooleanFactor @left { '&&' @op BooleanFactor @right } ;

  // === if expression ===

  @mapping(IfExpr, params=[condition, thenExpr, elseExpr])
  IfExpression ::=
    'if' '(' BooleanExpression @condition ')'
    '{' NumberExpression @thenExpr '}'
    'else'
    '{' NumberExpression @elseExpr '}' ;
}
```

**後輩:** Level 1 の20行から、約60行に増えましたけど、変数・比較・Boolean・if 式、全部入ってますね。

**先輩:** 60行で、変数付き条件分岐言語が完成。悪くないでしょ。

---

## Part 5: テストで確認する

**先輩:** `mvn compile` して、エバリュエータを実装しよう。

```bash
mvn compile
```

**後輩:** 新しく追加した AST ノードのぶん、`evalXxx` メソッドが増えてるはずですね。

**先輩:** そう。GGP（Generation Gap Pattern）の恩恵で、生成された抽象クラスに新しい abstract メソッドが追加されて、手書きの具象クラスでコンパイルエラーになる。コンパイラが「これ実装してね」って教えてくれる。

### エバリュエータの実装

```java
package com.example.tinycalc;

import com.example.tinycalc.TinyCalcAST.*;
import java.util.HashMap;
import java.util.Map;

public class CalcEvaluator extends TinyCalcEvaluator<Object> {

    private final Map<String, Double> variables = new HashMap<>();

    @Override
    protected Object evalTinyCalcProgram(TinyCalcProgram node) {
        for (var decl : node.declarations()) {
            evalVarDecl(decl);
        }
        return eval(node.expression());
    }

    @Override
    protected Object evalVarDecl(VarDecl node) {
        Double value = node.init() != null
            ? ((Number) eval(node.init())).doubleValue()
            : 0.0;
        variables.put(node.name(), value);
        return value;
    }

    @Override
    protected Object evalVariableRefExpr(VariableRefExpr node) {
        String name = node.name();
        if (name.startsWith("$")) name = name.substring(1);
        return variables.getOrDefault(name, 0.0);
    }

    @Override
    protected Object evalBinaryExpr(BinaryExpr node) {
        Double left = ((Number) eval(node.left())).doubleValue();
        Double right = ((Number) eval(node.right())).doubleValue();
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default -> throw new IllegalArgumentException(
                "Unknown operator: " + node.op());
        };
    }

    @Override
    protected Object evalNumberLiteral(NumberLiteral node) {
        return Double.parseDouble(node.value());
    }

    @Override
    protected Object evalComparisonExpr(ComparisonExpr node) {
        Double left = ((Number) eval(node.left())).doubleValue();
        Double right = ((Number) eval(node.right())).doubleValue();
        return switch (node.op()) {
            case "==" -> left.equals(right);
            case "!=" -> !left.equals(right);
            case "<"  -> left < right;
            case ">"  -> left > right;
            case "<=" -> left <= right;
            case ">=" -> left >= right;
            default -> throw new IllegalArgumentException(
                "Unknown comparator: " + node.op());
        };
    }

    @Override
    protected Object evalBooleanAndExpr(BooleanAndExpr node) {
        Boolean left = (Boolean) eval(node.left());
        if (!left) return false;  // 短絡評価
        Boolean right = (Boolean) eval(node.right());
        return left && right;
    }

    @Override
    protected Object evalIfExpr(IfExpr node) {
        Boolean condition = (Boolean) eval(node.condition());
        return condition
            ? eval(node.thenExpr())
            : eval(node.elseExpr());
    }
}
```

**後輩:** Level 1 の20行から60行に増えましたけど、追加した4機能ぶんの evalXxx が増えただけで、パターンは全部同じですね。

**先輩:** そう。「左を eval、右を eval、演算する」が基本パターン。if 式も「条件を eval、true なら thenExpr を eval、false なら elseExpr を eval」。

### テスト

```java
public class Main {
    public static void main(String[] args) {
        test("1 + 2 * 3", 7.0);
        test("var price set 100; $price * 1.1", 110.0);
        test("var x set 10; var y set 20; $x + $y", 30.0);
        test("var price set 100; if ($price > 50) { $price * 0.9 } else { $price }", 90.0);
        test("var price set 30; if ($price > 50) { $price * 0.9 } else { $price }", 30.0);
    }

    private static void test(String input, double expected) {
        var ast = TinyCalcMapper.parse(input);
        var result = ((Number) new CalcEvaluator().eval(ast)).doubleValue();
        String status = Math.abs(result - expected) < 0.001 ? "OK" : "FAIL";
        System.out.printf("[%s] %s = %s (expected %s)%n", status, input, result, expected);
    }
}
```

```
[OK] 1 + 2 * 3 = 7.0 (expected 7.0)
[OK] var price set 100; $price * 1.1 = 110.0 (expected 110.0)
[OK] var x set 10; var y set 20; $x + $y = 30.0 (expected 30.0)
[OK] var price set 100; if ($price > 50) { $price * 0.9 } else { $price } = 90.0 (expected 90.0)
[OK] var price set 30; if ($price > 50) { $price * 0.9 } else { $price } = 30.0 (expected 30.0)
```

**後輩:** 全部通った！ 変数、if 式、比較演算、全部動いてますね。

**先輩:** 文法60行 + エバリュエータ60行 = 120行。変数付き条件分岐言語が完成。

---

## Part 6: VSIX にパッケージする

**後輩:** 先輩、この言語を VS Code で使えるようにしたいです。

**先輩:** よし、VSIX（VS Code Extension）にパッケージしよう。やることは4つ。

### ステップ 1: プロジェクト構造を作る

```
tinycalc-vscode/
  package.json              <-- 拡張マニフェスト
  language-configuration.json
  syntaxes/
    tinycalc.tmLanguage.json  <-- TextMate 文法（シンタックスハイライト）
  grammar/
    TinyCalc.ubnf             <-- 参照用の文法コピー
  src/
    extension.ts            <-- LSP/DAP クライアント配線
  server-dist/
    tinycalc-lsp-server.jar <-- LSP サーバーの Fat JAR
  tsconfig.json
```

**後輩:** ファイル多いですね……

**先輩:** 見た目は多いけど、実質書くのは `extension.ts` くらい。残りはほぼテンプレート。

### ステップ 2: package.json を書く

```json
{
  "name": "tinycalc-lsp",
  "displayName": "TinyCalc DSL (LSP)",
  "description": "TinyCalc DSL の VS Code 拡張。LSP サーバーで補完とエラー表示。",
  "version": "0.1.0",
  "publisher": "your-name",
  "engines": { "vscode": "^1.85.0" },
  "categories": ["Programming Languages"],
  "activationEvents": ["onLanguage:tinycalc"],
  "main": "./out/extension.js",
  "contributes": {
    "languages": [{
      "id": "tinycalc",
      "aliases": ["TinyCalc"],
      "extensions": [".tcalc"],
      "configuration": "./language-configuration.json"
    }],
    "grammars": [{
      "language": "tinycalc",
      "scopeName": "source.tinycalc",
      "path": "./syntaxes/tinycalc.tmLanguage.json"
    }],
    "debuggers": [{
      "type": "tinycalc",
      "label": "TinyCalc Debug",
      "languages": ["tinycalc"],
      "configurationAttributes": {
        "launch": {
          "required": ["program"],
          "properties": {
            "program": { "type": "string", "default": "${file}" },
            "stopOnEntry": { "type": "boolean", "default": false }
          }
        }
      }
    }],
    "configuration": {
      "title": "TinyCalc LSP",
      "properties": {
        "tinycalcLsp.server.javaPath": {
          "type": "string",
          "default": "java",
          "description": "Java 実行パス"
        },
        "tinycalcLsp.server.jarPath": {
          "type": "string",
          "default": "",
          "description": "LSP サーバー JAR パス（空の場合はバンドル版を使用）"
        }
      }
    }
  },
  "scripts": {
    "vscode:prepublish": "npm run compile",
    "compile": "tsc -p ./"
  },
  "devDependencies": {
    "@types/node": "^20.11.30",
    "@types/vscode": "^1.85.0",
    "typescript": "^5.4.5",
    "@vscode/vsce": "^2.26.0"
  },
  "dependencies": {
    "vscode-languageclient": "^9.0.1"
  }
}
```

**後輩:** `contributes` の中に言語登録、TextMate 文法、デバッガ、設定……全部入ってるんですね。

**先輩:** そう。VS Code はこの `package.json` を読んで、「.tcalc ファイルを開いたら tinycalc 言語モードを使う」「tinycalc 言語が有効になったら extension.ts の activate() を呼ぶ」と判断する。

### ステップ 3: extension.ts を書く

```typescript
import * as path from "path";
import * as vscode from "vscode";
import {
  LanguageClient, LanguageClientOptions, ServerOptions
} from "vscode-languageclient/node";

let client: LanguageClient | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const config = vscode.workspace.getConfiguration("tinycalcLsp");
  const javaPath = config.get<string>("server.javaPath", "java");
  const configuredJar = config.get<string>("server.jarPath", "");

  const jarPath = configuredJar.trim().length > 0
    ? configuredJar
    : context.asAbsolutePath(path.join("server-dist", "tinycalc-lsp-server.jar"));

  // LSP サーバー起動
  const serverOptions: ServerOptions = {
    command: javaPath,
    args: ["--enable-preview", "-jar", jarPath]
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "tinycalc" }],
    outputChannel: vscode.window.createOutputChannel("TinyCalc LSP")
  };

  client = new LanguageClient(
    "tinycalcLanguageServer", "TinyCalc Language Server",
    serverOptions, clientOptions
  );
  client.start();

  context.subscriptions.push({ dispose: () => { void client?.stop(); } });

  // DAP アダプタ
  const dapFactory: vscode.DebugAdapterDescriptorFactory = {
    createDebugAdapterDescriptor(): vscode.ProviderResult<vscode.DebugAdapterDescriptor> {
      return new vscode.DebugAdapterExecutable(javaPath, [
        "--enable-preview", "-cp", jarPath,
        "com.example.tinycalc.generated.TinyCalcDapLauncher"
      ]);
    }
  };
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory("tinycalc", dapFactory)
  );
}

export async function deactivate(): Promise<void> {
  if (client) await client.stop();
}
```

**後輩:** やってることは3つだけですね。

1. 設定から JAR パスを解決
2. LSP クライアントを起動
3. DAP アダプタを登録

**先輩:** そう。LSP クライアントの仕事は「Java プロセスを起動して、stdin/stdout で JSON-RPC を通信する」だけ。実際の補完やエラー表示のロジックは Java 側の LSP サーバー（unlaxer が生成したもの）にある。

### ステップ 4: ビルドとインストール

**先輩:** まず Java 側の LSP サーバー JAR をビルドする。

```bash
# 1. LSP サーバーの Fat JAR をビルド
cd tinycalc-server
mvn package -DskipTests
mkdir -p ../tinycalc-vscode/server-dist
cp target/tinycalc-lsp-server.jar ../tinycalc-vscode/server-dist/

# 2. VS Code 拡張をビルド
cd ../tinycalc-vscode
npm install
npm run compile

# 3. VSIX にパッケージ
npx @vscode/vsce package
# 出力: tinycalc-lsp-0.1.0.vsix

# 4. VS Code にインストール
code --install-extension tinycalc-lsp-0.1.0.vsix
```

**後輩:** これで `.tcalc` ファイルを開くと……

**先輩:** シンタックスハイライト、キーワード補完、リアルタイムエラー表示、全部動く。

**後輩:** 文法60行から、IDE サポート付きの言語が手に入るんですか……

**先輩:** さらに `stopOnEntry: true` で launch.json を設定すれば、F10 でステップ実行もできる。

```json
{
  "type": "tinycalc",
  "request": "launch",
  "name": "Debug TinyCalc",
  "program": "${file}",
  "stopOnEntry": true
}
```

**後輩:** DAP まで！すごい……

---

## Part 7: まとめ

**後輩:** Level 2 で学んだことを整理させてください。

| ステップ | やったこと | 追加行数 |
|----------|-----------|---------|
| 変数宣言 | `VarDecl` ルール + `@mapping` | +5行 |
| 変数参照 | `VariableRef` ルール + Factor に追加 | +3行 |
| 比較演算 | `ComparisonExpression` + `CompareOp` | +6行 |
| Boolean 式 | `BooleanExpression` + `BooleanFactor` | +8行 |
| if 式 | `IfExpression` ルール + Factor に追加 | +6行 |
| エバリュエータ | 6つの evalXxx 実装 | +40行 |
| VSIX | extension.ts + package.json | テンプレート |
| **合計** | | **文法~60行 + eval~60行** |

**先輩:** Level 1 からの追加パターンをまとめると、こうなる。

### 新しい構文を追加するパターン（4ステップ）

1. **文法にルールを追加** -- `@mapping(NodeName, params=[...])` で AST ノードを宣言
2. **既存の Factor/Choice に新ルールを追加** -- PEG の順序に注意
3. **`mvn compile` で再生成** -- 新しい abstract メソッドがエバリュエータに出現
4. **evalXxx を実装** -- コンパイラがどのメソッドが足りないか教えてくれる

**後輩:** 毎回同じ4ステップなんですね。

**先輩:** そう。100個機能を追加しても、この4ステップの繰り返し。sealed interface のおかげで、実装漏れはコンパイルエラーで検出される。

**後輩:** Level 3 はありますか？

**先輩:** tinyexpression を見てほしい。文字列、関数定義、match 式、外部メソッド呼び出し……Level 2 の延長で全部作れる。パターンは同じ。

| レベル | 内容 | 文法行数 |
|--------|------|---------|
| Level 1 | 四則演算 | ~20行 |
| Level 2 | + 変数 + if 式 + VSIX | ~60行 |
| Level 3 (tinyexpression) | + 関数 + match + 文字列 + import | ~300行 |

**後輩:** 20行 → 60行 → 300行。段階的に積み上がっていくんですね。

**先輩:** そのとおり。文法を書けば言語が手に入る。それが unlaxer。

---

## 関連リンク

- [Level 1: クイックスタート](./quickstart-dialogue.ja.md) -- 四則演算パーサーを5分で作る
- [UBNF から LSP/DAP まで](./tutorial-ubnf-to-lsp-dap-dialogue.ja.md) -- 生成パイプライン詳解
- [LLM リファレンス](./llm-reference.md) -- LLM 向けワンファイルリファレンス
- [tinyexpression](https://github.com/opaopa6969/tinyexpression) -- 完全な式言語の実装例

---

[English](./tutorial-tinycalc-level2.en.md) | [日本語](./tutorial-tinycalc-level2.ja.md) | [Index](./INDEX.ja.md)
