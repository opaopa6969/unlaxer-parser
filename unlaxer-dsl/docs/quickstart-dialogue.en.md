[English](./quickstart-dialogue.en.md) | [日本語](./quickstart-dialogue.ja.md) | [Index](./INDEX.ja.md)

---

# Getting Started with unlaxer-parser in 5 Minutes -- A Quick Start Through Dialogue

> **Characters**
> - **Senior**: The creator of unlaxer-parser. Designed a framework that generates an entire language processing system just from writing a grammar
> - **Newcomer**: Knows the basics of Java but has never used a parser generator. Starting from "What is a parser?"

---

## Table of Contents

- [Part 1: What Can It Do?](#part-1-what-can-it-do)
- [Part 2: Setting Up the Environment](#part-2-setting-up-the-environment)
- [Part 3: Writing Your First Grammar](#part-3-writing-your-first-grammar)
- [Part 4: Generate and Run](#part-4-generate-and-run)
- [Part 5: Writing the Evaluator](#part-5-writing-the-evaluator)
- [Part 6: Next Steps](#part-6-next-steps)

---

## Part 1: What Can It Do?

**Newcomer:** Senior, what is a "parser generator"? I've heard the name before, but honestly I don't really understand it.

**Senior:** Good question. Let me ask you the reverse -- if the string `"1+2*3"` is the input, what's the answer?

**Newcomer:** 7, right? Multiplication comes first. `2*3=6`, then `1+6=7`.

**Senior:** Right. So how would you compute that in a program?

**Newcomer:** Hmm... split the string by `+` using `String.split`... but wait, that wouldn't calculate `2*3` first...

**Senior:** Exactly. The naive approach goes like this.

```java
// Naive approach: struggling with split
String input = "1+2*3";
String[] parts = input.split("\\+");
// parts = ["1", "2*3"]
// Then split "2*3" by "\\*" ...
// But what about "1-2+3"? Split by both "-" and "+"?
// What about "(1+2)*3"? Parentheses?
// What about "1+-2" (negative numbers)?
```

**Newcomer:** Yikes, that's a nightmare.

**Senior:** Yes, a nightmare. If you try to do it with `split`, you have to handle operator precedence, parentheses, negative numbers, whitespace... everything yourself. Even after writing 100 lines you can't cover all the patterns.

**Newcomer:** So how do the pros do it?

**Senior:** They write a "parser." A parser is a program that converts a string into structured data (a tree structure). It transforms `"1+2*3"` into a tree like this.

```
    +
   / \
  1   *
     / \
    2   3
```

**Newcomer:** Ah, if you compute from the bottom of the tree upward, `2*3=6` naturally gets calculated first.

**Senior:** Exactly. And there are two approaches to writing this parser.

1. **By hand** -- Write a recursive descent parser yourself. Hundreds of lines. Bug-prone
2. **Parser generator** -- Write grammar rules and parser code is generated automatically

**Newcomer:** The parser generator approach sounds better. So what about unlaxer?

**Senior:** unlaxer goes beyond a "parser generator." Here's the difference.

| Tool | What It Generates |
|--------|-------------|
| Typical parser generators (ANTLR, etc.) | Parser only |
| **unlaxer** | Parser + AST + Mapper + Evaluator + LSP + DAP |

**Newcomer:** LSP... is that the thing for VS Code auto-completion and stuff?

**Senior:** Yes. Just by writing a grammar, you get editor completion, error display, hover information, and go-to-definition -- all of it.

**Newcomer:** Wait, all that? Just from writing a grammar?

**Senior:** Just from writing a grammar. Well, let's actually try it.

---

## Part 2: Setting Up the Environment

**Newcomer:** What do I need?

**Senior:** Java 21 or higher and Maven. That's it.

**Newcomer:** Oh, I have Java 21 installed. Maven too.

**Senior:** Then let's create a new project. First, `pom.xml`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>tinycalc</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <unlaxer.version>2.5.0</unlaxer.version>
    </properties>

    <dependencies>
        <!-- unlaxer core library -->
        <dependency>
            <groupId>org.unlaxer</groupId>
            <artifactId>unlaxer-common</artifactId>
            <version>${unlaxer.version}</version>
        </dependency>
        <!-- unlaxer code generator (compile-time only) -->
        <dependency>
            <groupId>org.unlaxer</groupId>
            <artifactId>unlaxer-dsl</artifactId>
            <version>${unlaxer.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Plugin to generate code from UBNF -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>generate-parser</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>java</goal>
                        </goals>
                        <configuration>
                            <mainClass>org.unlaxer.dsl.UbnfCodeGenerator</mainClass>
                            <arguments>
                                <argument>${project.basedir}/src/main/resources/TinyCalc.ubnf</argument>
                                <argument>${project.build.directory}/generated-sources/ubnf</argument>
                            </arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- Add generated code to compile path -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <version>3.4.0</version>
                <executions>
                    <execution>
                        <id>add-generated-sources</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>add-source</goal>
                        </goals>
                        <configuration>
                            <sources>
                                <source>${project.build.directory}/generated-sources/ubnf</source>
                            </sources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

**Newcomer:** That's pretty long...

**Senior:** It looks long, but it only does three things.

1. **Dependencies**: `unlaxer-common` (parser library) and `unlaxer-dsl` (code generator)
2. **exec-maven-plugin**: Automatically generates code from `.ubnf` files during `mvn compile`
3. **build-helper-maven-plugin**: Adds generated code to the compile targets

**Newcomer:** I see. So where do I put the grammar file?

**Senior:** Under `src/main/resources/`. The directory structure looks like this.

```
tinycalc/
  pom.xml
  src/
    main/
      resources/
        TinyCalc.ubnf          <-- Grammar file (write it here)
      java/
        com/example/tinycalc/
          CalcEvaluator.java    <-- This is the only thing you write yourself
          Main.java
  target/
    generated-sources/
      ubnf/
        com/example/tinycalc/
          TinyCalcParsers.java  <-- Auto-generated
          TinyCalcAST.java      <-- Auto-generated
          TinyCalcMapper.java   <-- Auto-generated
          TinyCalcEvaluator.java <-- Auto-generated
```

**Newcomer:** The only things I write by hand are the grammar file, `CalcEvaluator.java`, and `Main.java`?

**Senior:** That's right. Everything else is generated. Now let's write the grammar.

---

## Part 3: Writing Your First Grammar

**Senior:** Create `src/main/resources/TinyCalc.ubnf`. I'll explain it from top to bottom so it doesn't get confusing all at once.

### The Header

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc
```

**Newcomer:** You declare the language name with `grammar` and specify the target Java package with `@package`, right?

**Senior:** Exactly. The `grammar` name becomes the prefix for class names. So writing `TinyCalc` generates classes like `TinyCalcParsers`, `TinyCalcAST`...

### Token Declarations

```ubnf
  token NUMBER = NumberParser
  token EOF    = EndOfSourceParser
```

**Newcomer:** What is a `token`?

**Senior:** A lexer-level parser. The smallest unit for reading strings. `NumberParser` is a built-in class from unlaxer-common that reads numeric literals like `123` or `3.14`.

**Newcomer:** And `EndOfSourceParser`?

**Senior:** A parser that detects the end of input. It means "we successfully read everything up to this point."

### The Root Rule

```ubnf
  @root
  Formula ::= Expression EOF ;
```

**Newcomer:** `@root` is the starting point for parsing, right?

**Senior:** Yes. The string entered by the user is first parsed by the `Formula` rule. `Formula` declares that an `Expression` is followed by `EOF`. In other words, the rule is "write one expression and that's the end of the input."

### Arithmetic Expression Rules -- This Is the Core

**Senior:** This is the most important part. How to express the precedence of arithmetic operators.

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;
```

**Newcomer:** Wow, lots of annotations. What's `@mapping`?

**Senior:** `@mapping(BinaryExpr, params=[left, op, right])` is an instruction that says "when this rule matches, create an AST node called `BinaryExpr`."

Let me break it down.

| Part | Meaning |
|------|------|
| `@mapping(BinaryExpr, ...)` | Convert the match result into a `BinaryExpr` record |
| `params=[left, op, right]` | Field names for the record |
| `@left` | Put the value at this position in the rule body into the `left` field |
| `@op` | Put the value at this position into the `op` field |
| `@right` | Put the value at this position into the `right` field |

**Newcomer:** Ahh, so while parsing you mark "this is left," "this is op," "this is right," and it automatically assembles them into an AST node.

**Senior:** Exactly. If you were doing this by hand, you'd write code to `new` up a node and set each field -- all of that is automated.

**Newcomer:** And what's `@leftAssoc`?

**Senior:** A left-associative declaration. Without it, the interpretation of `3-2-1` would be wrong.

```
Left associative (correct):     Right associative (wrong):
    -                               -
   / \                             / \
  -   1                           3   -
 / \                                 / \
3   2                               2   1

(3-2)-1 = 0                       3-(2-1) = 2
```

**Newcomer:** Oh right, subtraction has to be calculated from left to right. Just adding `@leftAssoc` handles it correctly?

**Senior:** Yes. unlaxer assembles the repetition part `{ AddOp @op Term @right }` into a left-associative tree.

### Rule Body Syntax

**Senior:** Let me summarize the notation used after `::=` in rule bodies.

```ubnf
  Expression ::= Term @left { AddOp @op Term @right } ;
```

| Notation | Meaning | Example |
|------|------|-----|
| `A B` | Sequence (A followed by B) | `Term AddOp Term` |
| `A \| B` | Choice (A or B) | `'+' \| '-'` |
| `{ A }` | Zero or more repetitions | `{ AddOp Term }` |
| `[ A ]` | Optional (zero or one) | `[ '-' ]` |
| `( A )` | Grouping | `( '+' \| '-' )` |
| `'+'` | Literal string | `'+'` |
| `@name` | Capture (binding to an AST field) | `@left`, `@op` |

**Newcomer:** It looks like EBNF.

**Senior:** Yes, it's based on EBNF. The `@name` capture is what makes UBNF distinctive.

### Multiplication and Terms

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;
```

**Newcomer:** Same pattern as `Expression`. `Term` handles multiplication and division.

**Senior:** Right. Precedence increases in the order `Expression` -> `Term` -> `Factor`. This is a commonly used pattern in grammars -- the technique of **expressing operator precedence through nesting (hierarchy) of syntax rules**.

```
Expression = Term   { (+|-) Term }      <- Precedence: low
Term       = Factor { (*|/) Factor }    <- Precedence: high
Factor     = NUMBER | '(' Expression ')'<- Highest (atom)
```

**Newcomer:** I see. Because `Factor` is the deepest level, multiplication and division are evaluated before addition and subtraction.

**Senior:** Correct.

### Factors and Operators

```ubnf
  Factor ::= NUMBER | '(' Expression ')' ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;
}
```

**Newcomer:** `Factor` is either a numeric literal or an expression wrapped in parentheses. `AddOp` and `MulOp` are operator choices.

**Senior:** Right. That completes the grammar. Let's look at the whole thing one more time.

### The Complete Grammar (Full View)

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc

  token NUMBER = NumberParser
  token EOF    = EndOfSourceParser

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

**Newcomer:** The whole thing is only about 20 lines.

**Senior:** Yes. This alone defines the full functionality of a four-function arithmetic parser.

---

## Part 4: Generate and Run

**Senior:** Let's generate.

```bash
mvn compile
```

**Newcomer:** ...Wait, the compile already finished. That was fast.

**Senior:** The `exec-maven-plugin` automatically generates code during the `generate-sources` phase. Let's look under `target/generated-sources/ubnf/`.

```bash
ls target/generated-sources/ubnf/com/example/tinycalc/
```

```
TinyCalcAST.java
TinyCalcEvaluator.java
TinyCalcMapper.java
TinyCalcParsers.java
```

**Newcomer:** Whoa, four files were generated! What's inside them?

### Generated File 1: TinyCalcAST.java

**Senior:** First, the AST. The node types specified by `@mapping` in the grammar are generated as records.

```java
// TinyCalcAST.java (excerpt, simplified)
public sealed interface TinyCalcAST {

    // Generated from @mapping(BinaryExpr, params=[left, op, right])
    record BinaryExpr(
        TinyCalcAST left,
        String op,
        TinyCalcAST right
    ) implements TinyCalcAST {}

    // Generated from the NUMBER token
    record NumberLiteral(
        String value
    ) implements TinyCalcAST {}
}
```

**Newcomer:** `sealed interface` and `record`! Making full use of Java 21 features.

**Senior:** Yes. Because it's a sealed interface, you can use pattern matching in `switch` expressions. Because they're records, they're immutable, and `equals`/`hashCode`/`toString` are automatic.

### Generated File 2: TinyCalcParsers.java

**Senior:** The parsers. Each grammar rule is converted into a chain of parser combinators.

```java
// TinyCalcParsers.java (conceptual structure)
public class TinyCalcParsers {
    // Expression ::= Term @left { AddOp @op Term @right }
    public Parser expression() {
        return sequence(
            term(),
            zeroOrMore(sequence(addOp(), term()))
        );
    }
    // ... other rules follow the same pattern
}
```

**Newcomer:** The grammar structure is directly reflected as Java code.

### Generated File 3: TinyCalcMapper.java

**Senior:** The mapper is a class that converts the raw parse tree output by the parser into a clean AST. It uses the capture information from `@left`, `@op`, `@right` to map to the correct fields.

**Newcomer:** Is this all automatic too?

**Senior:** All automatic. No need to touch it by hand.

### Generated File 4: TinyCalcEvaluator.java

**Senior:** The evaluator. This is the class you'll extend.

```java
// TinyCalcEvaluator.java (excerpt, simplified)
public abstract class TinyCalcEvaluator<T> {

    public T eval(TinyCalcAST node) {
        return switch (node) {
            case BinaryExpr n -> evalBinaryExpr(n);
            case NumberLiteral n -> evalNumber(n);
        };
    }

    protected abstract T evalBinaryExpr(BinaryExpr node);
    protected abstract T evalNumber(NumberLiteral node);
}
```

**Newcomer:** The `eval` method dispatches by node type with a `switch` and calls each `evalXxx`, right?

**Senior:** Right. And the `evalXxx` methods are `abstract`, so you implement them yourself. But all you need to do is "evaluate the left side, evaluate the right side, compute with the operator."

**Newcomer:** Is it already working?!

**Senior:** Just one more thing. Write the evaluator implementation and it will work.

---

## Part 5: Writing the Evaluator

**Senior:** Create `src/main/java/com/example/tinycalc/CalcEvaluator.java`.

```java
package com.example.tinycalc;

import com.example.tinycalc.TinyCalcAST.BinaryExpr;
import com.example.tinycalc.TinyCalcAST.NumberLiteral;

public class CalcEvaluator extends TinyCalcEvaluator<Double> {

    @Override
    protected Double evalBinaryExpr(BinaryExpr node) {
        Double left = eval(node.left());
        Double right = eval(node.right());
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default -> throw new IllegalArgumentException("Unknown operator: " + node.op());
        };
    }

    @Override
    protected Double evalNumber(NumberLiteral node) {
        return Double.parseDouble(node.value());
    }
}
```

**Newcomer:** That's it?

**Senior:** That's it.

**Newcomer:** Wait... really, that's it?

**Senior:** Really, that's it. Let me summarize the key points.

| Method | What It Does |
|----------|---------------|
| `evalBinaryExpr` | Recursively `eval()` the left side, recursively `eval()` the right side, compute with the operator |
| `evalNumber` | Parse the numeric literal string into a `Double` |

**Newcomer:** `eval(node.left())` recursively evaluates the subtree. Since it's a tree structure, recursion is natural.

**Senior:** Exactly. For `1+2*3`, it works like this.

```
eval(BinaryExpr(left=1, op="+", right=BinaryExpr(left=2, op="*", right=3)))
  -> eval(NumberLiteral("1")) = 1.0
  -> eval(BinaryExpr(left=2, op="*", right=3))
      -> eval(NumberLiteral("2")) = 2.0
      -> eval(NumberLiteral("3")) = 3.0
      -> 2.0 * 3.0 = 6.0
  -> 1.0 + 6.0 = 7.0
```

**Newcomer:** Oh, that works beautifully.

### A Main Class for Testing

**Senior:** Let's test it. Write `Main.java`.

```java
package com.example.tinycalc;

public class Main {
    public static void main(String[] args) {
        // 1. Parse with the parser
        var parsers = new TinyCalcParsers();
        var parseResult = parsers.parse("1 + 2 * 3");

        // 2. Convert parse tree to AST
        var ast = new TinyCalcMapper().map(parseResult);

        // 3. Evaluate the AST
        var result = new CalcEvaluator().eval(ast);

        System.out.println("1 + 2 * 3 = " + result);
    }
}
```

**Senior:** Run it.

```bash
mvn compile exec:java -Dexec.mainClass=com.example.tinycalc.Main
```

```
1 + 2 * 3 = 7.0
```

**Newcomer:** It works!!

**Senior:** Let's try various expressions.

```
"10 - 3 - 2"     -> 5.0    (left-associative: (10-3)-2)
"(1 + 2) * 3"    -> 9.0    (parentheses working)
"100 / 10 / 2"   -> 5.0    (left-associative: (100/10)/2)
"1 + 2 + 3 + 4"  -> 10.0
```

**Newcomer:** All correct. Precedence, parentheses, left-associativity -- all handled by those 20 lines of grammar.

**Senior:** Right. You wrote a grammar file of 20 lines and an evaluator of 20 lines. A total of 40 lines. With `split`, you could write hundreds of lines and still not get it working correctly.

**Newcomer:** Parser generators are amazing...

**Senior:** Actually, this is just the beginning.

---

## Part 6: Next Steps

**Newcomer:** Wait, there's more?

**Senior:** The calculator we just built only handles numbers and basic arithmetic. A real DSL needs variables, conditionals, functions, strings... all sorts of features. With unlaxer, you just add rules to the grammar to handle all of them.

### Adding Variables

**Newcomer:** For example, if I wanted to use variables like `$x + 1`?

**Senior:** Just add a token and a rule to the grammar.

```ubnf
  token IDENTIFIER = IdentifierParser

  @mapping(VariableRef, params=[name])
  VariableReference ::= '$' IDENTIFIER @name ;

  // Add VariableReference to Factor
  Factor ::= NUMBER | VariableReference | '(' Expression ')' ;
```

**Newcomer:** With `@mapping(VariableRef, params=[name])`, a `VariableRef` AST node gets generated, right?

**Senior:** Yes. Then add `evalVariableRef` to the evaluator.

```java
@Override
protected Double evalVariableRef(VariableRef node) {
    return variables.get(node.name());  // Fetch from Map<String, Double>
}
```

**Newcomer:** Add 3 lines to the grammar, add 3 lines to the evaluator, and that's it...

### Adding an if Expression

**Senior:** A conditional expression like `if($x > 0, $x, -$x)` is easy too.

```ubnf
  @mapping(IfExpr, params=[condition, thenExpr, elseExpr])
  IfExpression ::= 'if' '(' BooleanExpression @condition ','
                       Expression @thenExpr ','
                       Expression @elseExpr ')' ;
```

**Newcomer:** Same pattern. Declare the node type with `@mapping` and bind the fields with `@param`.

**Senior:** Right. The pattern for adding new syntax is always the same.

1. Add a rule to the grammar
2. Declare the AST node with `@mapping`
3. Regenerate with `mvn compile`
4. Add `evalXxx` to the evaluator

### Enabling LSP

**Newcomer:** You mentioned LSP earlier. Completion and all that.

**Senior:** Parsers generated by unlaxer can be used directly as backends for an LSP server. Adding the `@completion` annotation to the grammar automatically enables keyword completion.

```ubnf
  @completion(keywords=["if", "sin", "cos", "sqrt", "min", "max"])
  Factor ::= NUMBER | VariableReference | IfExpression
           | SinFunction | CosFunction | SqrtFunction
           | MinFunction | MaxFunction
           | '(' Expression ')' ;
```

**Newcomer:** Just that gives me completions in VS Code?

**Senior:** You still need to wire up the LSP server, but the parser-side support is just this. For details, see the [From UBNF to LSP/DAP](./tutorial-ubnf-to-lsp-dap-dialogue.en.md) tutorial.

### Looking at tinyexpression

**Senior:** What we did today with TinyCalc is an introductory mini-language. If you want to see a full-fledged example, check out [tinyexpression](https://github.com/opaopa6969/tinyexpression).

**Newcomer:** What is tinyexpression?

**Senior:** A full-featured expression language built with unlaxer. It has features like these.

| Feature | Example |
|------|-----|
| Arithmetic | `1 + 2 * 3` |
| Variables | `$price * $tax` |
| Strings | `'Hello ' + $name` |
| Comparison & logic | `$x > 0 && $y < 100` |
| Ternary operator | `$x > 0 ? $x : -$x` |
| Built-in functions | `sin($angle)`, `sqrt($x)`, `min($a, $b)` |
| Method definitions | `number abs(x) { x > 0 ? x : -x }` |
| Imports | `import java.lang.Math#abs as abs;` |
| Java code blocks | Embed Java code inside backticks |

**Newcomer:** Wow. And all of this is generated from a 300-line grammar file?

**Senior:** Yes. About 300 lines of grammar + 200 lines of evaluator. A total of about 500 lines, and you get variables, functions, type checking, LSP, DAP -- everything.

### Tutorial List

**Senior:** If you want to dive deeper, read the following tutorials.

| Tutorial | Content | Link |
|---------------|------|--------|
| Parser Fundamentals | How parser combinators work (unlaxer-common) | [EN](../../unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.en.md) / [JA](../../unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md) |
| From UBNF to LSP/DAP | The full pipeline from grammar to IDE support (unlaxer-dsl) | [EN](./tutorial-ubnf-to-lsp-dap-dialogue.en.md) / [JA](./tutorial-ubnf-to-lsp-dap-dialogue.ja.md) |
| The LLM Era and Unlaxer | Why frameworks are needed in the age of LLMs | [EN](./llm-era-and-unlaxer-dialogue.en.md) / [JA](./llm-era-and-unlaxer-dialogue.ja.md) |
| tinyexpression Implementation Guide | A complete expression language implementation example | [tinyexpression repository](https://github.com/opaopa6969/tinyexpression) |

---

**Newcomer:** Thank you, Senior. It feels like a dream compared to when I was despairing trying to implement arithmetic with `split`.

**Senior:** Right? Write a grammar and you get a language. That's unlaxer.

**Newcomer:** Oh, one last thing. What's the origin of the name "unlaxer"?

**Senior:** "un-" + "laxer." There's an XML schema language called RELAX NG, and the name was inspired by that. The opposite of "relaxer" -- meaning a "not-lax" parser. Strict, yet simple.

**Newcomer:** I see. Well, I'm going to go read the tinyexpression code right away!

**Senior:** Great. If there's anything you don't understand, just ask.

---

## Summary

What we learned in this Quick Start:

| Step | What We Did | Lines |
|----------|-----------|------|
| Write the grammar | `TinyCalc.ubnf` | ~20 lines |
| Code generation | `mvn compile` | 0 lines (automatic) |
| Evaluator implementation | `CalcEvaluator.java` | ~20 lines |
| Test | `Main.java` | ~10 lines |
| **Total** | | **~50 lines** |

In 50 lines, we completed a four-function arithmetic language that correctly handles operator precedence, left-associativity, and parentheses.

---

## Related Links

- [unlaxer-parser GitHub](https://github.com/opaopa6969/unlaxer-parser) -- This repository
- [tinyexpression GitHub](https://github.com/opaopa6969/tinyexpression) -- Complete implementation example
- [unlaxer-common](../../unlaxer-common/) -- Core parser combinator library
- [unlaxer-dsl](../) -- Code generator

---

[English](./quickstart-dialogue.en.md) | [日本語](./quickstart-dialogue.ja.md) | [Index](./INDEX.ja.md)
