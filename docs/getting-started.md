[English](./getting-started.md) | [日本語](./getting-started-ja.md)

---

# Getting Started with unlaxer-parser

**Version**: 3.0.10

This guide walks you through building a complete calculator language with unlaxer-parser from scratch — Maven setup, grammar, code generation, and a working evaluator.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Step 1: Create a Maven Project](#step-1-create-a-maven-project)
- [Step 2: Add Dependencies](#step-2-add-dependencies)
- [Step 3: Configure the Code Generator](#step-3-configure-the-code-generator)
- [Step 4: Write Your First Grammar](#step-4-write-your-first-grammar)
- [Step 5: Generate Code](#step-5-generate-code)
- [Step 6: Explore the Generated Code](#step-6-explore-the-generated-code)
- [Step 7: Write the Evaluator](#step-7-write-the-evaluator)
- [Step 8: Write a Test](#step-8-write-a-test)
- [Step 9: Add Variables](#step-9-add-variables)
- [Next Steps](#next-steps)

---

## Prerequisites

- Java 21 or later
- Maven 3.8 or later
- Basic Java knowledge

---

## Step 1: Create a Maven Project

```bash
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=tinycalc \
  -DarchetypeArtifactId=maven-archetype-quickstart \
  -DarchetypeVersion=1.4 \
  -DinteractiveMode=false
cd tinycalc
```

---

## Step 2: Add Dependencies

Edit `pom.xml` to require Java 21 and add unlaxer:

```xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>

<dependencies>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-common</artifactId>
        <version>3.0.10</version>
    </dependency>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-dsl</artifactId>
        <version>3.0.10</version>
    </dependency>

    <!-- for tests -->
    <dependency>
        <groupId>junit</groupId>
        <artifactId>junit</artifactId>
        <version>4.13.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## Step 3: Configure the Code Generator

Add the `exec-maven-plugin` to run the unlaxer code generator during `generate-sources`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>3.1.0</version>
            <executions>
                <execution>
                    <id>generate-ubnf</id>
                    <phase>generate-sources</phase>
                    <goals><goal>java</goal></goals>
                    <configuration>
                        <mainClass>org.unlaxer.dsl.CodegenMain</mainClass>
                        <arguments>
                            <argument>--grammar</argument>
                            <argument>${project.basedir}/src/main/resources/tinycalc.ubnf</argument>
                            <argument>--output</argument>
                            <argument>${project.build.directory}/generated-sources/ubnf</argument>
                            <argument>--generators</argument>
                            <argument>AST,Parser,Mapper,Evaluator</argument>
                        </arguments>
                    </configuration>
                </execution>
            </executions>
        </plugin>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>build-helper-maven-plugin</artifactId>
            <version>3.4.0</version>
            <executions>
                <execution>
                    <id>add-source</id>
                    <phase>generate-sources</phase>
                    <goals><goal>add-source</goal></goals>
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
```

---

## Step 4: Write Your First Grammar

Create `src/main/resources/tinycalc.ubnf`:

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc

  // Use Java-style whitespace skipping between tokens
  @whitespace: javaStyle

  // Token declarations: reference Java parser classes
  token NUMBER     = org.unlaxer.parser.elementary.NumberParser
  token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
  token EOF        = org.unlaxer.parser.elementary.EndOfSourceParser

  // The grammar entry point
  @root
  @mapping(Program, params=[expression])
  Program ::= Expression @expression EOF ;

  // Left-associative addition and subtraction
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  // Left-associative multiplication and division
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  // Atoms: numbers, parentheses
  Factor ::= NUMBER | '(' Expression ')' ;

  // Operator tokens
  @enum
  AddOp ::= '+' | '-' ;

  @enum
  MulOp ::= '*' | '/' ;
}
```

### What this grammar says

- **`@root`**: `Program` is where parsing begins
- **`@mapping(Program, params=[expression])`**: Generate a record `Program(TinyCalcNode expression)` in the AST
- **`@leftAssoc`**: `1+2+3` parses as `(1+2)+3`, not `1+(2+3)`
- **`@enum`**: Generate enums `AddOp { PLUS, MINUS }` and `MulOp { STAR, SLASH }`
- **`@whitespace: javaStyle`**: Spaces and newlines are silently skipped between tokens

---

## Step 5: Generate Code

```bash
mvn generate-sources
```

If successful, you will find four generated files under `target/generated-sources/ubnf/com/example/tinycalc/`:

```
TinyCalcParsers.java    -- one parser class per grammar rule
TinyCalcAST.java        -- sealed interface + records for the AST
TinyCalcMapper.java     -- converts the raw parse tree to typed AST nodes
TinyCalcEvaluator.java  -- abstract visitor with one method per AST node type
```

---

## Step 6: Explore the Generated Code

### TinyCalcAST.java (excerpt)

```java
public sealed interface TinyCalcNode permits
    TinyCalcAST.Program, TinyCalcAST.BinaryExpr, ... {}

public record Program(TinyCalcNode expression) implements TinyCalcNode {}

public record BinaryExpr(TinyCalcNode left, String op, TinyCalcNode right)
    implements TinyCalcNode {}
```

### TinyCalcEvaluator.java (excerpt)

```java
public abstract class TinyCalcEvaluator<T> {
    public T eval(TinyCalcNode node) {
        return switch (node) {
            case TinyCalcAST.Program n   -> evalProgram(n);
            case TinyCalcAST.BinaryExpr n -> evalBinaryExpr(n);
            // ... one case per AST node type
        };
    }

    protected abstract T evalProgram(TinyCalcAST.Program node);
    protected abstract T evalBinaryExpr(TinyCalcAST.BinaryExpr node);
    // ...
}
```

---

## Step 7: Write the Evaluator

Create `src/main/java/com/example/tinycalc/CalcEvaluator.java`:

```java
package com.example.tinycalc;

public class CalcEvaluator extends TinyCalcEvaluator<Double> {

    @Override
    protected Double evalProgram(TinyCalcAST.Program node) {
        return eval(node.expression());
    }

    @Override
    protected Double evalBinaryExpr(TinyCalcAST.BinaryExpr node) {
        double left  = eval(node.left());
        double right = eval(node.right());
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default  -> throw new IllegalArgumentException("Unknown op: " + node.op());
        };
    }

    // NUMBER tokens arrive as a NumberLiteral node; the value is its text
    @Override
    protected Double evalNumberLiteral(TinyCalcAST.NumberLiteral node) {
        return Double.parseDouble(node.value());
    }
}
```

---

## Step 8: Write a Test

Create `src/test/java/com/example/tinycalc/CalcTest.java`:

```java
package com.example.tinycalc;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalcTest {

    private double calc(String input) {
        var parsers = new TinyCalcParsers();
        var parseTree = parsers.parse(input);
        var ast = new TinyCalcMapper().map(parseTree);
        return new CalcEvaluator().eval(ast);
    }

    @Test
    public void testAddition() {
        assertEquals(3.0, calc("1 + 2"), 0.001);
    }

    @Test
    public void testPrecedence() {
        // multiplication before addition
        assertEquals(7.0, calc("1 + 2 * 3"), 0.001);
    }

    @Test
    public void testParentheses() {
        assertEquals(9.0, calc("(1 + 2) * 3"), 0.001);
    }

    @Test
    public void testLeftAssociativity() {
        // 10 - 3 - 2 = (10 - 3) - 2 = 5
        assertEquals(5.0, calc("10 - 3 - 2"), 0.001);
    }
}
```

Run with:

```bash
mvn test
```

---

## Step 9: Add Variables

To extend the grammar with variable support, add these rules:

```ubnf
  // Variable declaration
  @mapping(VarDecl, params=[name, value])
  VarDecl ::= 'var' IDENTIFIER @name '=' Expression @value ';' ;

  // Variable reference (extend Factor)
  Factor ::= NUMBER | IDENTIFIER | '(' Expression ')' ;
```

Then extend the evaluator:

```java
private final Map<String, Double> variables = new HashMap<>();

@Override
protected Double evalVarDecl(TinyCalcAST.VarDecl node) {
    double value = eval(node.value());
    variables.put(node.name(), value);
    return value;
}

@Override
protected Double evalIdentifier(TinyCalcAST.IdentifierNode node) {
    return variables.getOrDefault(node.name(), 0.0);
}
```

---

## Next Steps

| What | Where |
|------|-------|
| Full UBNF syntax reference | [docs/ubnf-guide.md](./ubnf-guide.md) |
| Architecture deep-dive | [docs/architecture.md](./architecture.md) |
| LSP and DAP generation | [unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.en.md](../unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.en.md) |
| Real-world example | [tinyexpression](https://github.com/opaopa6969/tinyexpression) |
| Parser combinator concepts | [unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.en.md](../unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.en.md) |
