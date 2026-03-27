[English](./README.md) | [日本語](./README.ja.md)

---

```
                _
  _   _ _ __   | | __ ___  _____ _ __
 | | | | '_ \  | |/ _` \ \/ / _ \ '__|
 | |_| | | | | | | (_| |>  <  __/ |
  \__,_|_| |_| |_|\__,_/_/\_\___|_|
                              - parser
```

# unlaxer-parser

**Write a grammar, get a language -- Parser + AST + Evaluator + LSP + DAP, all generated**

[![Maven Central](https://img.shields.io/maven-central/v/org.unlaxer/unlaxer-common)](https://central.sonatype.com/artifact/org.unlaxer/unlaxer-common)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)]()

---

## The Problem

Building a DSL typically means writing and maintaining **6+ subsystems** by hand:

| Subsystem | Lines (approx.) |
|-----------|----------------|
| Lexer / Parser | 2,000+ |
| AST node types | 1,500+ |
| Parse-tree-to-AST mapper | 1,000+ |
| Evaluator / interpreter | 2,000+ |
| LSP server (completion, diagnostics, hover) | 2,500+ |
| DAP server (breakpoints, stepping, variables) | 1,500+ |
| **Total** | **10,000+** |

These subsystems are tightly coupled. A single grammar change cascades across all of them.

## The Solution

Write a **UBNF grammar** (~300 lines). Run the generator. Get everything.

```
  .ubnf grammar file
        |
        v
  +-----------------+
  | unlaxer-dsl     |
  |  code generator |
  +-----------------+
        |
        +---> Parsers.java      (parser combinators)
        +---> AST.java           (sealed interfaces + records)
        +---> Mapper.java        (parse tree -> AST)
        +---> Evaluator.java     (visitor skeleton)
        +---> LSP server         (completion, diagnostics, hover)
        +---> DAP server         (breakpoints, step, variables)
```

You write **only** the evaluation logic -- typically 50-200 lines of `evalXxx` methods.

---

## Quick Example

Here is a fragment from the [tinyexpression](https://github.com/opaopa6969/tinyexpression) UBNF grammar:

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;

@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;

AddOp ::= '+' | '-' ;
MulOp ::= '*' | '/' ;
```

From this, unlaxer generates:

- A **parser** that handles operator precedence and left-associativity correctly
- A **`BinaryExpr` AST record** with typed `left`, `op`, `right` fields
- A **mapper** that transforms the flat parse tree into a nested AST
- An **`evalBinaryExpr`** hook in the evaluator skeleton

---

## What Gets Generated

| You Write | unlaxer Generates |
|-----------|-------------------|
| Grammar rules (`::=`) | Parser combinators (`Parsers.java`) |
| `@mapping` annotations | AST sealed interfaces + records (`AST.java`) |
| `@left`, `@right`, `@op` captures | Parse-tree-to-AST mapper (`Mapper.java`) |
| `@leftAssoc` / `@rightAssoc` | Correct associativity handling |
| `@root` | Entry point parser |
| (your grammar) | Evaluator skeleton with `evalXxx` hooks (`Evaluator.java`) |
| (your grammar) | LSP server (completion, diagnostics, hover) |
| (your grammar) | DAP server (breakpoints, stepping, variables) |

---

## 5-Minute Quick Start

### 1. Add Maven dependencies

```xml
<dependencies>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-common</artifactId>
        <version>2.5.0</version>
    </dependency>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-dsl</artifactId>
        <version>2.5.0</version>
    </dependency>
</dependencies>
```

### 2. Write a grammar

Create `src/main/resources/TinyCalc.ubnf`:

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

### 3. Add the code generator plugin

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals><goal>java</goal></goals>
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
```

### 4. Generate code

```bash
mvn compile
```

This generates four files under `target/generated-sources/ubnf/com/example/tinycalc/`:

```
TinyCalcParsers.java    -- parser combinators
TinyCalcAST.java        -- sealed interface + records (BinaryExpr, etc.)
TinyCalcMapper.java     -- parse tree -> AST transformation
TinyCalcEvaluator.java  -- abstract evaluator with evalXxx hooks
```

### 5. Write the evaluator

```java
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
            default -> throw new IllegalArgumentException("Unknown op: " + node.op());
        };
    }

    @Override
    protected Double evalNumber(NumberLiteral node) {
        return Double.parseDouble(node.value());
    }
}
```

### 6. Run it

```java
var parser = new TinyCalcParsers();
var tree = parser.parse("1 + 2 * 3");
var ast = new TinyCalcMapper().map(tree);
var result = new CalcEvaluator().eval(ast);
System.out.println(result);  // 7.0
```

---

## Architecture

```
                    +------------------+
                    |   .ubnf grammar  |
                    +--------+---------+
                             |
                      code generation
                             |
            +----------------+----------------+
            |                |                |
     +------v------+  +-----v------+  +------v------+
     |   Parsers   |  |    AST     |  |   Mapper    |
     | (combinator |  | (sealed    |  | (parse tree |
     |   chain)    |  |  records)  |  |  -> AST)    |
     +------+------+  +-----+------+  +------+------+
            |                |                |
            v                v                v
     +------+------+  +-----+------+  +------v------+
     |  Parse Tree |->|  AST Tree  |->|  Evaluator  |
     +-------------+  +------------+  +------+------+
                                             |
                                    +--------+--------+
                                    |                 |
                              +-----v-----+    +-----v-----+
                              | LSP Server|    | DAP Server|
                              +-----------+    +-----------+
```

---

## Real-World Example

**[tinyexpression](https://github.com/opaopa6969/tinyexpression)** is a complete formula language built with unlaxer-parser.

- ~300-line UBNF grammar
- Variables, functions (`sin`, `cos`, `sqrt`, `min`, `max`), ternary operator, if/else, method declarations
- Full LSP support (completion, diagnostics, hover, go-to-definition)
- Full DAP support (breakpoints, stepping, variable inspection)
- Used in production

---

## Documentation

| Tutorial | Description | Language |
|----------|-------------|----------|
| Parser Fundamentals | Core parser combinator concepts (unlaxer-common) | [JA](./unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md) |
| UBNF to LSP/DAP | Full pipeline: grammar to IDE support (unlaxer-dsl) | [EN](./unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.en.md) / [JA](./unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.ja.md) |
| LLM Era and Unlaxer | Why frameworks still matter in the age of LLMs | [JA](./unlaxer-dsl/docs/llm-era-and-unlaxer-dialogue.ja.md) |
| Quick Start (5 minutes) | Dialogue-format getting started guide | [JA](./unlaxer-dsl/docs/quickstart-dialogue.ja.md) |
| Implementation Guide | Building tinyexpression end-to-end | [tinyexpression repo](https://github.com/opaopa6969/tinyexpression) |

---

## Why unlaxer?

| | ANTLR | tree-sitter | PEG.js | **unlaxer** |
|---|---|---|---|---|
| Language | Java, C#, Python, ... | C + bindings | JavaScript | **Java** |
| Parser type | ALL(*) | GLR | PEG | **PEG + combinators** |
| AST generation | Manual | Manual | Manual | **Automatic** (from `@mapping`) |
| Evaluator skeleton | No | No | No | **Yes** |
| LSP generation | No | Partial (queries) | No | **Yes** |
| DAP generation | No | No | No | **Yes** |
| Grammar annotations | No | No | No | **Yes** (`@mapping`, `@leftAssoc`, `@eval`, ...) |
| Operator associativity | In grammar | In grammar | Manual | **`@leftAssoc` / `@rightAssoc`** |
| Zero dependencies | No | No | No | **Yes** (unlaxer-common) |

unlaxer is designed for Java teams who want to go from grammar to working IDE support with minimal boilerplate.

---

## Project Structure

```
unlaxer-parser/
  +-- unlaxer-common/     Core parser combinator library (zero dependencies)
  +-- unlaxer-dsl/         Code generator: UBNF -> Parsers + AST + Mapper + Evaluator + LSP + DAP
```

- **[unlaxer-common](./unlaxer-common/)** -- Parser combinators inspired by RELAX NG. Infinite lookahead, backtracking, comprehensive logging. Pure Java, zero dependencies.
- **[unlaxer-dsl](./unlaxer-dsl/)** -- Reads `.ubnf` grammar files and generates all the Java code you need.

---

## License

MIT License. See [LICENSE](./LICENSE) for details.

## Contributing

Contributions are welcome. Please open an issue or pull request on [GitHub](https://github.com/opaopa6969/unlaxer-parser).

## Author

[opaopa6969](https://github.com/opaopa6969)
