# UBNF から LSP/DAP まで -- 会話で学ぶ unlaxer-dsl コード生成パイプライン

> **登場人物**
> - **先輩（S）**: unlaxer-dsl の設計をよく知るシニア開発者
> - **後輩（K）**: パーサージェネレータは初めて触る開発者

> **前提知識**
> - `implementation-guide-dialogue.md` の第1話〜第9話を読んでいる
> - Java 21 の sealed interface / record / switch pattern を知っている

---

## Part 1 -- UBNF 文法を書く

**K:** 先輩、`tinyexpression-p4-draft.ubnf` を見たんですが、普通の BNF と何が違うんですか？

**S:** UBNF は BNF に **アノテーション** と **トークン宣言** を足したもの。3つの違いを覚えればいい。

```
1. token宣言    — 字句解析器（lexer）を Java クラスで指定する
2. @mapping     — ルールから AST record を自動生成する指示
3. @root 他     — パイプライン全体の振る舞いを制御するメタ情報
```

**K:** 具体的に見せてもらえますか？

**S:** TinyExpression の四則演算部分を抜粋する。

```ubnf
grammar TinyExpressionP4 {

  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser

  @root
  Formula ::= Expression EOF ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;

  AddOp ::= '+' | '-' ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=20)
  NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;

  MulOp ::= '*' | '/' ;

  @mapping(VariableRefExpr, params=[name])
  VariableRef ::= '$' IDENTIFIER @name ;
}
```

**K:** `@mapping(BinaryExpr, params=[left, op, right])` で AST の record が決まるわけですね。

**S:** そう。`@left`, `@op`, `@right` は **キャプチャ名**。パースツリーのどの子トークンが record のどのフィールドに対応するかを宣言している。これを書くだけで、3つのファイルが自動生成される:

| 生成ファイル | 中身 |
|---|---|
| `TinyExpressionP4AST.java` | `sealed interface` + `record BinaryExpr(...)` |
| `TinyExpressionP4Mapper.java` | Token -> AST 変換コード |
| `TinyExpressionP4Evaluator.java` | abstract 評価器（switch dispatch） |

**K:** 文法を書くだけで evaluator まで出てくるんですか。

**S:** evaluator は abstract メソッドだけ。中身は人間が書く。これが **Generation Gap Pattern**。`implementation-guide-dialogue.md` の第6話で話したやつ。

---

## Part 2 -- コード生成の仕組み: Generator たち

**K:** `mvn generate-sources` を実行すると裏で何が動くんですか？

**S:** `CodegenRunner` が UBNF をパースして `GrammarDecl`（UBNF の AST）を作り、それを各 **Generator** に渡す。

```
UBNF文法ファイル
    |
    v  UBNFBootstrapParser
GrammarDecl (UBNF AST)
    |
    +---> ParserGenerator     --> TinyExpressionP4Parsers.java
    +---> ASTGenerator        --> TinyExpressionP4AST.java
    +---> MapperGenerator     --> TinyExpressionP4Mapper.java
    +---> EvaluatorGenerator  --> TinyExpressionP4Evaluator.java
    +---> LSPGenerator        --> TinyExpressionP4LanguageServer.java
    +---> DAPGenerator        --> TinyExpressionP4DebugAdapter.java
```

**K:** Generator ごとに 1 つの Java ファイルを生成する？

**S:** 基本的にそう。`CodeGenerator` インターフェースを実装して `generate(GrammarDecl)` を返す。

**K:** その Generator の中身は…… `StringBuilder` で Java コードを組み立てている？

**S:** 現状の `MapperGenerator` はそう。そしてこれが **読みにくい**。見てみて:

```java
// MapperGenerator.java -- 現状（raw StringBuilder）
StringBuilder sb = new StringBuilder();
sb.append("package ").append(packageName).append(";\n\n");
sb.append("import java.util.ArrayList;\n");
sb.append("import java.util.List;\n");
sb.append("import java.util.Optional;\n\n");

sb.append("public class ").append(mapperClass).append(" {\n\n");
sb.append("    private ").append(mapperClass).append("() {}\n\n");

sb.append("    public static ").append(rootClassName).append(" parse(String source) {\n");
sb.append("        return parse(source, null);\n");
sb.append("    }\n\n");

sb.append("    public static ").append(rootClassName)
  .append(" parse(String source, String preferredAstSimpleName) {\n");
sb.append("        NODE_SOURCE_SPANS.clear();\n");
sb.append("        Parser rootParser = ").append(parsersClass).append(".getRootParser();\n");
sb.append("        ParseContext context = new ParseContext(createRootSourceCompat(source));\n");
sb.append("        Parsed parsed;\n");
sb.append("        try {\n");
sb.append("            parsed = rootParser.parse(context);\n");
sb.append("        } finally {\n");
sb.append("            context.close();\n");
sb.append("        }\n");
sb.append("        if (!parsed.isSucceeded()) {\n");
sb.append("            throw new IllegalArgumentException(\"Parse failed: \" + source);\n");
sb.append("        }\n");
// ... さらに50行以上続く
```

**K:** これは…… インデントがどこで間違っているか見つけるのが大変ですね。`}\n` の閉じ忘れとか気づきにくそう。

**S:** そう。しかも `"` のエスケープが連鎖して読めなくなる。これが Generator を複数人で保守する上での最大のボトルネックだった。

---

## Part 3 -- JavaCodeBuilder の登場

**K:** 何か対策はあるんですか？

**S:** `JavaCodeBuilder` を作った。`unlaxer-dsl` の `org.unlaxer.dsl.codegen` パッケージにある。

```
unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/JavaCodeBuilder.java
```

考え方はシンプルで、「**Java のソースコード構造を Java の API で表現する**」。

```java
// JavaCodeBuilder -- 型安全で構造が見える
JavaCodeBuilder java = new JavaCodeBuilder("com.example");
java.imports("java.util.List", "java.util.Optional");

java.publicClass("MyMapper", cls -> {
    cls.method("public static", "MyAST", "parse", m -> {
        m.param("String", "source");
        m.body(b -> {
            b.returnStmt("parse(source, null)");
        });
    });
});

String javaSource = java.build();
```

**K:** おお、ラムダのネストが Java のブレース構造とそのまま対応していますね！

**S:** それが狙い。ポイントは4つ:

| 特徴 | 説明 |
|---|---|
| **自動インデント** | `indent++` / `indent--` を手動管理しなくていい |
| **構造スコープ** | `ClassScope`, `MethodScope`, `BodyScope` で今どこにいるか型レベルで分かる |
| **セミコロン自動付与** | `b.stmt("foo()")` → `foo();` になる。付け忘れがない |
| **制御構造ヘルパー** | `ifBlock`, `forEachLoop`, `tryCatch`, `switchExpr` が揃っている |

**K:** スコープが型で分かれているというのは？

**S:** `ClassScope` の中では `field()` や `method()` が呼べるが `returnStmt()` は呼べない。逆に `BodyScope` の中では `returnStmt()` が呼べるが `field()` は呼べない。**コード生成コードの時点で構造ミスがコンパイルエラーになる**。

```java
java.publicClass("Example", cls -> {
    // ClassScope: field, method, constructor, record, comment, blankLine
    cls.field("private final", "String", "name");

    cls.method("public", "String", "getName", m -> {
        // MethodScope: param, body
        m.body(b -> {
            // BodyScope: varDecl, assign, stmt, returnStmt, ifBlock, forEachLoop, ...
            b.returnStmt("this.name");
        });
    });

    // cls.returnStmt("x");  // <-- コンパイルエラー！ ClassScope にそんなメソッドはない
});
```

**K:** これはいいですね。`StringBuilder` だと何でも `append` できてしまうから、メソッドの中にクラス宣言を書いてしまうようなミスが防げない。

---

## Part 4 -- MapperGenerator を JavaCodeBuilder で書き直すとどうなるか

**K:** 先輩、さっきの `MapperGenerator` の冒頭部分を `JavaCodeBuilder` で書き直すとどうなりますか？

**S:** Before / After を並べるとこうなる。まずエントリポイントの `parse()` メソッド:

### Before (raw StringBuilder)

```java
// MapperGenerator.java -- 現状
sb.append("    public static ").append(rootClassName).append(" parse(String source) {\n");
sb.append("        return parse(source, null);\n");
sb.append("    }\n\n");

sb.append("    public static ").append(rootClassName)
  .append(" parse(String source, String preferredAstSimpleName) {\n");
sb.append("        NODE_SOURCE_SPANS.clear();\n");
sb.append("        Parser rootParser = ").append(parsersClass).append(".getRootParser();\n");
sb.append("        ParseContext context = new ParseContext(createRootSourceCompat(source));\n");
sb.append("        Parsed parsed;\n");
sb.append("        try {\n");
sb.append("            parsed = rootParser.parse(context);\n");
sb.append("        } finally {\n");
sb.append("            context.close();\n");
sb.append("        }\n");
sb.append("        if (!parsed.isSucceeded()) {\n");
sb.append("            throw new IllegalArgumentException(\"Parse failed: \" + source);\n");
sb.append("        }\n");
sb.append("        int consumed = consumedLengthCompat(parsed.getConsumed());\n");
sb.append("        if (consumed != source.length()) {\n");
sb.append("            throw new IllegalArgumentException(\"Parse failed at offset \" + consumed + \": \" + source);\n");
sb.append("        }\n");
```

### After (JavaCodeBuilder)

```java
// MapperGenerator.java -- JavaCodeBuilder 版
JavaCodeBuilder java = new JavaCodeBuilder(packageName);
java.imports("java.util.ArrayList", "java.util.List", "java.util.Optional",
             "org.unlaxer.Parsed", "org.unlaxer.StringSource",
             "org.unlaxer.Token", "org.unlaxer.context.ParseContext",
             "org.unlaxer.parser.Parser");

java.javadoc(grammarName + " parse tree (Token) -> " + astClass + " mapper.");

java.publicClass(mapperClass, cls -> {

    cls.field("private static final", "java.util.IdentityHashMap<Object, int[]>",
              "NODE_SOURCE_SPANS", "new java.util.IdentityHashMap<>()");
    cls.blankLine();

    // --- parse(String) ---
    cls.method("public static", rootClassName, "parse", m -> {
        m.param("String", "source");
        m.body(b -> {
            b.returnStmt("parse(source, null)");
        });
    });

    // --- parse(String, String) ---
    cls.method("public static", rootClassName, "parse", m -> {
        m.param("String", "source");
        m.param("String", "preferredAstSimpleName");
        m.body(b -> {
            b.stmt("NODE_SOURCE_SPANS.clear()");
            b.varDecl("Parser", "rootParser", parsersClass + ".getRootParser()");
            b.varDecl("ParseContext", "context",
                       "new ParseContext(createRootSourceCompat(source))");
            b.rawLine("Parsed parsed;");

            b.tryCatch(
                tryBody -> {
                    tryBody.assign("parsed", "rootParser.parse(context)");
                },
                "Throwable", "t",
                catchBody -> {
                    catchBody.stmt("context.close()");
                    catchBody.rawLine("throw t;");
                }
            );

            b.ifBlock("!parsed.isSucceeded()", ib -> {
                ib.throwNew("IllegalArgumentException",
                    JavaCodeBuilder.quoted("Parse failed: ") + " + source");
            });

            b.varDecl("int", "consumed", "consumedLengthCompat(parsed.getConsumed())");
            b.ifBlock("consumed != source.length()", ib -> {
                ib.throwNew("IllegalArgumentException",
                    JavaCodeBuilder.quoted("Parse failed at offset ")
                    + " + consumed + " + JavaCodeBuilder.quoted(": ") + " + source");
            });

            // ... Token -> AST 変換へ続く
        });
    });

    // --- toBinaryExpr(Token) ---
    cls.method("static", astClass + ".BinaryExpr", "toBinaryExpr", m -> {
        m.param("Token", "token");
        m.body(b -> {
            b.varDecl("Token", "working", "token");
            b.ifBlock("working.parser.getClass() != "
                       + parsersClass + ".NumberExpressionParser.class", ib -> {
                ib.assign("working",
                    "findFirstDescendant(working, "
                    + parsersClass + ".NumberExpressionParser.class)");
            });
            b.ifBlock("working == null", ib -> {
                ib.varDecl("String", "literal", "stripQuotes(firstTokenText(token))");
                ib.returnStmt("new " + astClass
                    + ".BinaryExpr(null, List.of(literal), List.of())");
            });
            b.comment("... structured code continues ...");
            b.returnStmt("null");
        });
    });
});

String javaSource = java.build();
```

**K:** 全然読みやすさが違いますね！ ラムダのネストが Java ソースのブレース構造と一致しているから、「今どのスコープのコードを生成しているか」が一目瞭然です。

**S:** そう。そして大事なのは:

1. **インデントずれが起きない** -- `indent++` / `indent--` は `JavaCodeBuilder` が管理する
2. **`"\n"` の付け忘れが起きない** -- `line()` が自動で改行を入れる
3. **`"` のエスケープ地獄が軽減される** -- `JavaCodeBuilder.quoted()` ヘルパーがある
4. **構造の閉じ忘れが起きない** -- ラムダのスコープが自然に `}` を生成する

**K:** `JavaCodeBuilder.quoted()` はどういう動作ですか？

**S:** 文字列をダブルクォートで囲んで、中身をエスケープする:

```java
JavaCodeBuilder.quoted("Parse failed: ")
// => "\"Parse failed: \""
// 生成される Java コード上では: "Parse failed: "
```

`StringBuilder` で書くと `"\\\"Parse failed: \\\"" + source` みたいなエスケープの入れ子になる。人間が読めるレベルを超える。

---

## Part 5 -- BodyScope の制御構造ヘルパー

**K:** `ifBlock` や `tryCatch` 以外にはどんなヘルパーがありますか？

**S:** `BodyScope` に用意されている制御構造を一覧にする:

```java
// if 文
b.ifBlock("condition", ib -> { ... });

// if-else 文
b.ifElseBlock("condition",
    thenBody -> { ... },
    elseBody -> { ... }
);

// for ループ
b.forLoop("int i = 0", "i < list.size()", "i++", lb -> { ... });

// 拡張 for ループ
b.forEachLoop("Token", "child", "token.filteredChildren", lb -> { ... });

// try-catch
b.tryCatch(
    tryBody  -> { ... },
    "Exception", "e",
    catchBody -> { ... }
);

// switch 式 (Java 21)
b.switchExpr("node", sw -> {
    sw.caseArrow("BinaryExpr n", "evalBinary(n)");
    sw.caseArrow("VariableRefExpr n", "evalVariable(n)");
    sw.caseBlock("IfExpr n", cb -> {
        cb.comment("complex case needs a block");
        cb.returnStmt("evalIf(n)");
    });
    sw.defaultArrow("throw new UnsupportedOperationException()");
});
```

**K:** `switchExpr` があるんですね。P4-typed の `TinyExpressionP4Evaluator` の dispatch は sealed switch ですよね。これで生成できる？

**S:** その通り。`EvaluatorGenerator` がまさにこの `switchExpr` を使って `evalInternal()` の switch 文を生成する設計になっている。

```java
// EvaluatorGenerator が生成する evalInternal() のイメージ
cls.method("private", "T", "evalInternal", m -> {
    m.param(astClass, "node");
    m.body(b -> {
        b.switchExpr("node", sw -> {
            for (String astNodeName : mappingClassNames) {
                String varName = "n";
                sw.caseArrow(astClass + "." + astNodeName + " " + varName,
                             "eval" + astNodeName + "(" + varName + ")");
            }
        });
    });
});
```

**K:** `mappingClassNames` をループで回すだけで全ケースが生成される。UBNF に `@mapping` を1つ追加すれば自動的に switch の分岐も増える。

**S:** そう。**文法の変更が自動的にコンパイルエラーとして伝播する**のが、この仕組みの最大の価値。

---

## Part 6 -- AST 生成: ASTGenerator と sealed interface

**K:** `TinyExpressionP4AST.java` の生成はどうなっていますか？

**S:** `ASTGenerator` が `GrammarDecl` の `@mapping` アノテーションを収集して、sealed interface と record を生成する。

```java
// ASTGenerator の出力イメージ（JavaCodeBuilder で書く場合）
JavaCodeBuilder java = new JavaCodeBuilder(packageName);

List<String> permits = mappingClassNames.stream()
    .map(name -> astClass + "." + name)
    .toList();

java.publicSealedInterface(astClass, permits, cls -> {

    for (MappingInfo mapping : mappings) {
        cls.record(mapping.className(), mapping.components());
        // components = [["String", "left"], ["List<String>", "op"], ...]
    }

    cls.blankLine();
    cls.comment("Source span support");
    cls.abstractMethod("default", "int[]", "sourceSpan");
});

String javaSource = java.build();
```

生成結果:

```java
public sealed interface TinyExpressionP4AST permits
    TinyExpressionP4AST.BinaryExpr,
    TinyExpressionP4AST.VariableRefExpr,
    TinyExpressionP4AST.IfExpr {

    record BinaryExpr(String left, List<String> op, List<TinyExpressionP4AST> right)
        implements TinyExpressionP4AST {}

    record VariableRefExpr(String name)
        implements TinyExpressionP4AST {}

    record IfExpr(TinyExpressionP4AST condition,
                  TinyExpressionP4AST thenExpr,
                  TinyExpressionP4AST elseExpr)
        implements TinyExpressionP4AST {}

    // Source span support
    default int[] sourceSpan();
}
```

**K:** `publicSealedInterface` というメソッドがあるんですね。`permits` リストを渡すと自動で複数行に展開してくれる？

**S:** そう。permits が長くなると1行に収まらないので、`JavaCodeBuilder` が改行とインデントを自動で入れる。`StringBuilder` で手動管理すると `"permits\n" + "    BinaryExpr,\n"` みたいなのを延々と書くことになる。

---

## Part 7 -- Mapper 生成の全体像

**K:** `MapperGenerator` は Generator の中で一番複雑ですよね？

**S:** そう。Mapper は「パースツリー (Token) の構造を AST record に変換する」ロジックを生成する。UBNF の各ルールの構造（sequence, choice, repeat, optional）を解析して、対応するトークン走査コードを出力する。

```
UBNF ルール構造                     生成される Mapper メソッド
--------------------                -------------------------
NumberExpression ::=                toBinaryExpr(Token token)
  NumberTerm @left                    left  = 子トークン[0] を再帰変換
  { AddOp @op                        op    = 繰り返し子トークンからテキスト抽出
    NumberTerm @right }               right = 繰り返し子トークンを再帰変換

VariableRef ::=                    toVariableRefExpr(Token token)
  '$' IDENTIFIER @name                name  = IDENTIFIER のテキスト
```

**K:** `@left`, `@op`, `@right` のキャプチャ名がそのまま record のフィールド名になって、Mapper がトークンツリーからフィールド値を取り出すコードを生成する。

**S:** そう。ここが `MapperGenerator` の核心で、`SequenceBody` の子要素を走査して `@name` が付いたものを見つけ、その位置（childIndex）とAST型を計算する。

JavaCodeBuilder を使うと、こういう構造を表現しやすい:

```java
// MapperGenerator 内部 -- toBinaryExpr のような toXxx メソッドの生成
cls.method("static", astClass + "." + mapping.className(), "to" + mapping.className(), m -> {
    m.param("Token", "token");
    m.body(b -> {
        b.varDecl("Token", "working", "unwrapIfNeeded(token)");

        for (CaptureInfo capture : mapping.captures()) {
            if (capture.isRepeated()) {
                // { AddOp @op NumberTerm @right } のような繰り返しキャプチャ
                b.varDecl("List<" + capture.javaType() + ">", capture.name(),
                           "new ArrayList<>()");
                b.forEachLoop("Token", "child", "working.filteredChildren", lb -> {
                    lb.ifBlock("child.parser instanceof " + capture.parserClass(), ib -> {
                        ib.stmt(capture.name() + ".add(" + capture.convertExpr("child") + ")");
                    });
                });
            } else {
                // NumberTerm @left のような単一キャプチャ
                b.varDecl(capture.javaType(), capture.name(),
                           capture.convertExpr("childAt(working, " + capture.index() + ")"));
            }
        }

        b.blankLine();
        b.returnStmt("new " + astClass + "." + mapping.className() + "("
            + mapping.captures().stream().map(CaptureInfo::name).collect(joining(", "))
            + ")");
    });
});
```

**K:** `CaptureInfo` がキャプチャの情報を持っていて、繰り返しか単一かで生成パターンが変わるわけですね。

**S:** そう。`StringBuilder` だとこの if 分岐のたびにインデントの深さを計算し直す必要があるが、`JavaCodeBuilder` はラムダのネストで自然に正しいインデントになる。

---

## Part 8 -- LSP サーバー生成

**K:** LSP の生成はどうなっていますか？

**S:** `LSPGenerator` が `TinyExpressionP4LanguageServer.java` を生成する。これは `LanguageServer` インターフェースを implements した基底クラスで、人間が `TinyExpressionP4LanguageServerExt` でサブクラス化して拡張する。

```
生成コード (触らない)                   手書きコード
TinyExpressionP4LanguageServer   <--   TinyExpressionP4LanguageServerExt
  initialize()                           追加のセマンティックトークン定義
  textDocumentDidOpen()                  追加の diagnostics ロジック
  textDocumentDidChange()                TinyExpression 固有の補完候補
  completion()
  semanticTokensFull()
```

**K:** セマンティックトークンはどうやって型安全に？

**S:** AST の sealed interface を使う。生成コードでは AST ノード種別ごとに LSP のトークンタイプを割り当てる:

```java
// LanguageServerExt -- AST node type -> LSP semantic token type
private SemanticTokenType tokenTypeOf(TinyExpressionP4AST node) {
    return switch (node) {
        case TinyExpressionP4AST.BinaryExpr n      -> SemanticTokenType.OPERATOR;
        case TinyExpressionP4AST.VariableRefExpr n  -> SemanticTokenType.VARIABLE;
        case TinyExpressionP4AST.IfExpr n           -> SemanticTokenType.KEYWORD;
        case TinyExpressionP4AST.NumberMatchExpr n  -> SemanticTokenType.KEYWORD;
        // 新しい AST ノードが追加されると、ここを書かないとコンパイルエラー
    };
}
```

**K:** sealed switch だから、UBNF に新しい `@mapping` を追加して AST が変わったら、この switch がコンパイルエラーになるんですね。

**S:** Regex ベースの LSP だと「新しい構文を追加したけど LSP のハイライトが追従していない」というバグが静かに発生する。sealed switch なら**漏れがコンパイル時に検出される**。

---

## Part 9 -- DAP (Debug Adapter Protocol) 生成

**K:** DAP は何ができるんですか？

**S:** 式のステップ実行とブレークポイント。VSCode のデバッガパネルで:

1. **Variables** -- 式中の `$a`, `$b` の現在値を表示
2. **Call Stack** -- AST ノードのパスを表示（`Formula > BinaryExpr > left`）
3. **Step In / Step Over** -- AST の評価を1ノードずつ進める

**K:** AST のノードパスが Call Stack に出るんですね。

**S:** `DAPGenerator` が生成する `TinyExpressionP4DebugAdapter` は、Evaluator の `eval()` をフックして各ノードの評価前後でイベントを発火する。人間は `TinyExpressionP4DebugAdapterExt` で表示のカスタマイズだけ行う。

```
生成コード (触らない)                   手書きコード
TinyExpressionP4DebugAdapter    <--   TinyExpressionP4DebugAdapterExt
  launch()                             parity probe (全バックエンド結果比較)
  setBreakpoints()                     変数のフォーマット
  evaluate()                           ウォッチ式の評価
  stackTrace()
  variables()
```

---

## Part 10 -- パイプライン全体のまとめ

**K:** ここまでの流れを整理させてください。

**S:** こうなる。

```
Step 1: UBNF 文法を書く
    tinyexpression-p4.ubnf
    @mapping, @root, @leftAssoc, @precedence ...
        |
Step 2: mvn generate-sources
    CodegenRunner が Generator を順に実行
        |
        +-- ParserGenerator      --> Parsers.java     (トークナイザ)
        +-- ASTGenerator         --> AST.java         (sealed records)
        +-- MapperGenerator      --> Mapper.java      (Token -> AST)
        +-- EvaluatorGenerator   --> Evaluator.java   (abstract switch)
        +-- LSPGenerator         --> LanguageServer.java
        +-- DAPGenerator         --> DebugAdapter.java
        |
Step 3: 手書き実装 (Generation Gap Pattern)
        |
        +-- NumberEvaluator extends Evaluator<Float>
        +-- LanguageServerExt extends LanguageServer
        +-- DebugAdapterExt extends DebugAdapter
        |
Step 4: ビルド & パッケージング
    mvn package --> tinyexpression-p4-lsp-server.jar (fat JAR)
    vsce package --> .vsix (VSCode 拡張)
        |
Step 5: 開発者体験
    VSCode でシンタックスハイライト + 型チェック + ステップデバッグ
```

**K:** Generator のコードを `JavaCodeBuilder` で書くことで:

1. 生成される Java コードの構造がそのまま Generator コードに反映される
2. スコープの型安全性で構造ミスがコンパイル時に見つかる
3. インデント、セミコロン、ブレースの管理が自動になる
4. `quoted()` でエスケープ地獄が軽減される

**S:** そう。**Generator を書くためのツールが JavaCodeBuilder。Generator が出力するコードが Evaluator や LSP/DAP。Evaluator が処理するデータが sealed AST。その AST を定義するのが UBNF。** 全部つながっている。

**K:** UBNF の1行を変えると、Generator が新しいコードを生成して、sealed switch がコンパイルエラーで「ここも直せ」と教えてくれる。手作業で見落とす場所がない。

**S:** それがこのパイプラインの設計意図。文法の進化に対して **静的安全性** を最大化する仕組み。

---

## 付録A -- JavaCodeBuilder API リファレンス

### トップレベル

| メソッド | 説明 |
|---------|------|
| `new JavaCodeBuilder(packageName)` | ビルダー生成 |
| `packageDecl()` | `package xxx;` を出力 |
| `imports(String...)` | import 文を出力 |
| `javadoc(String...)` | Javadoc コメントを出力 |
| `publicClass(name, Consumer<ClassScope>)` | `public class Name { ... }` |
| `publicSealedInterface(name, permits, Consumer<ClassScope>)` | sealed interface |
| `build()` | 完成した Java ソースを `String` で取得 |

### ClassScope

| メソッド | 説明 |
|---------|------|
| `field(modifiers, type, name)` | フィールド宣言 |
| `field(modifiers, type, name, initializer)` | フィールド宣言 (初期化付き) |
| `method(modifiers, returnType, name, Consumer<MethodScope>)` | メソッド定義 |
| `constructor(modifiers, className, Consumer<MethodScope>)` | コンストラクタ |
| `abstractMethod(modifiers, returnType, name, params...)` | abstract メソッド |
| `record(name, List<String[]> components)` | record 宣言 |
| `blankLine()` / `comment(text)` / `rawLine(text)` | ユーティリティ |

### MethodScope

| メソッド | 説明 |
|---------|------|
| `param(type, name)` | パラメータ追加 |
| `body(Consumer<BodyScope>)` | メソッドボディ |

### BodyScope

| メソッド | 説明 |
|---------|------|
| `varDecl(type, name, initializer)` | ローカル変数宣言 |
| `assign(target, value)` | 代入文 |
| `stmt(statement)` | 任意の文 (セミコロン自動付与) |
| `returnStmt(expression)` | return 文 |
| `throwNew(exceptionType, message)` | throw new 文 |
| `ifBlock(condition, Consumer<BodyScope>)` | if ブロック |
| `ifElseBlock(condition, then, else)` | if-else ブロック |
| `forLoop(init, condition, increment, body)` | for ループ |
| `forEachLoop(type, varName, iterable, body)` | 拡張 for ループ |
| `tryCatch(tryBody, exType, exName, catchBody)` | try-catch |
| `switchExpr(subject, Consumer<SwitchScope>)` | switch 式 |
| `blankLine()` / `comment(text)` / `rawLine(text)` | ユーティリティ |

### SwitchScope

| メソッド | 説明 |
|---------|------|
| `caseArrow(pattern, expression)` | `case X -> expr;` |
| `caseBlock(pattern, Consumer<BodyScope>)` | `case X -> { ... }` |
| `defaultArrow(expression)` | `default -> expr;` |

### 静的ヘルパー

| メソッド | 説明 |
|---------|------|
| `quoted(s)` | `"s"` (エスケープ付き) |
| `ternary(cond, then, otherwise)` | 三項演算子 |
| `cast(type, expr)` | キャスト式 |
| `instanceOf(expr, type, varName)` | instanceof パターン |
| `methodCall(target, method, args...)` | メソッド呼び出し式 |

---

## 付録B -- 実ファイルの場所

| ファイル | パス |
|---------|------|
| JavaCodeBuilder | `unlaxer-parser/unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/JavaCodeBuilder.java` |
| JavaCodeBuilderExample (Before/After) | `unlaxer-parser/unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/JavaCodeBuilderExample.java` |
| MapperGenerator (現行) | `unlaxer-parser/unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/MapperGenerator.java` |
| ASTGenerator | `unlaxer-parser/unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/ASTGenerator.java` |
| EvaluatorGenerator | `unlaxer-parser/unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/EvaluatorGenerator.java` |
| UBNF 文法 (ドラフト) | `tinyexpression/docs/ubnf/tinyexpression-p4-draft.ubnf` |
| P4 Pipeline Guide | `tinyexpression/docs/TINYEXPRESSION-P4-PIPELINE-GUIDE.md` |
| 実装ガイド (5バックエンド) | `tinyexpression/docs/implementation-guide-dialogue.md` |
