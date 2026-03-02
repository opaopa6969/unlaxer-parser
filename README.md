# unlaxer-dsl
[English](README.md) | [日本語](README.ja.md)
[Specification](SPEC.md)
[Parser IR Draft](docs/PARSER-IR-DRAFT.md)
[Railroad Diagrams](docs/RAILROAD-DIAGRAMS.md)

A tool that automatically generates Java parsers, ASTs, mappers, and evaluators from grammar definitions written in UBNF (Unlaxer BNF) notation.

---

## Table of Contents

- [Features](#features)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Quick Start](#quick-start)
- [How to Write UBNF Grammars](#how-to-write-ubnf-grammars)
  - [Overall Structure](#overall-structure)
  - [Global Settings](#global-settings)
  - [Token Declarations](#token-declarations)
  - [Rule Declarations](#rule-declarations)
  - [Element Syntax](#element-syntax)
  - [Annotations](#annotations)
- [How to Use the Code Generators](#how-to-use-the-code-generators)
- [Generated Artifacts in Detail (TinyCalc Example)](#generated-artifacts-in-detail-tinycalc-example)
  - [ASTGenerator](#astgenerator)
  - [ParserGenerator](#parsergenerator)
  - [MapperGenerator](#mappergenerator)
  - [EvaluatorGenerator](#evaluatorgenerator)
- [CodegenMain - CLI Tool](#codegenmain---cli-tool)
- [Building the VS Code Extension (VSIX)](#building-the-vs-code-extension-vsix)
- [Tutorial 1: TinyCalc](#tutorial-1-tinycalc)
- [Tutorial 2: Building a VS Code Extension for UBNF](#tutorial-2-building-a-vs-code-extension-for-ubnf)
- [Project Structure](#project-structure)
- [Self-Hosting](#self-hosting)
- [Roadmap](#roadmap)

---

## Features

- **UBNF notation**: Describe grammars concisely with extended BNF syntax (groups `()`, optional `[]`, repetition `{}`, capture `@name`)
- **Four kinds of code generation**: Automatically generate four Java classes from one grammar definition
  - `XxxParsers.java`: parser classes using unlaxer-common parser combinators
  - `XxxAST.java`: type-safe AST using sealed interfaces + records
  - `XxxMapper.java`: parse-tree -> AST mapping skeleton
  - `XxxEvaluator.java`: abstract evaluator that traverses AST
- **Java 21 support**: Full use of sealed interfaces, records, and switch expressions
- **Self-hosting design**: UBNF grammar itself is written in UBNF, aiming to eventually process itself

---

## Prerequisites

| Software | Version |
|---|---|
| Java | 21+ (with `--enable-preview`) |
| Maven | 3.8+ |

---

## Build

```bash
git clone https://github.com/yourorg/unlaxer-dsl.git
cd unlaxer-dsl
mvn package
```

Run tests:

```bash
mvn test
```

Refresh golden snapshots:

```bash
./scripts/refresh-golden-snapshots.sh
```

Check golden snapshots are up to date:

```bash
./scripts/check-golden-snapshots.sh
```

Refresh JSON report examples in `SPEC.md`:

```bash
./scripts/spec/refresh-json-examples.sh
```

Check `SPEC.md` JSON examples are current (for CI):

```bash
./scripts/spec/check-json-examples.sh
```

Check shell scripts (shebang + syntax):

```bash
./scripts/check-scripts.sh
```

Check CLI option docs are synchronized across docs:

```bash
./scripts/spec/check-doc-sync.sh
```

Run all local checks (scripts + golden sync + tests + spec freshness):

```bash
./scripts/check-all.sh
```

Validate a parser IR JSON document:

```bash
mvn -q -DskipTests compile
java --enable-preview -cp target/classes org.unlaxer.dsl.ParserIrSchemaMain --ir path/to/parser-ir.json
```

---

## Quick Start

1. Prepare a UBNF file (e.g. `tinycalc.ubnf`)
2. Parse the grammar with `UBNFMapper.parse()`
3. Generate Java sources with each generator
4. Add generated sources to your project

```java
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.*;

// 1. Read .ubnf file content as a string
String ubnfSource = Files.readString(Path.of("tinycalc.ubnf"));

// 2. Parse grammar
GrammarDecl grammar = UBNFMapper.parse(ubnfSource).grammars().get(0);

// 3. Generate each output
CodeGenerator.GeneratedSource ast       = new ASTGenerator()      .generate(grammar);
CodeGenerator.GeneratedSource parsers   = new ParserGenerator()   .generate(grammar);
CodeGenerator.GeneratedSource mapper    = new MapperGenerator()   .generate(grammar);
CodeGenerator.GeneratedSource evaluator = new EvaluatorGenerator().generate(grammar);

// 4. Extract and save source
System.out.println(parsers.packageName()); // org.unlaxer.tinycalc.generated
System.out.println(parsers.className());   // TinyCalcParsers
System.out.println(parsers.source());      // public class TinyCalcParsers { ... }
```

---

## How to Write UBNF Grammars

### Overall Structure

```ubnf
grammar GrammarName {
    // Global settings
    @package: com.example.generated
    @whitespace: javaStyle

    // Token declarations
    token TOKEN_NAME = ParserClassName

    // Rule declarations
    @root
    RootRule ::= ... ;

    OtherRule ::= ... ;
}
```

- You can write multiple `grammar` blocks in one file
- Comments use `//` line comment syntax

---

### Global Settings

Write global settings in the form `@key: value`.

| Key | Value Example | Description |
|---|---|---|
| `@package` | `org.example.generated` | Package name of generated Java files |
| `@whitespace` | `javaStyle` | Whitespace handling style. With `javaStyle`, spaces between rule elements are skipped automatically |
| `@comment` | `{ line: "//" }` | Comment format. With `line: "//"`, line comments are skipped like whitespace |

```ubnf
grammar MyLang {
    @package: com.example.mylang
    @whitespace: javaStyle
    @comment: { line: "//" }
    ...
}
```

---

### Token Declarations

Declare external unlaxer-common parser classes as tokens.

```ubnf
token TOKEN_NAME = ParserClassName
```

- `TOKEN_NAME`: name referenced in grammar (uppercase snake case by convention)
- `ParserClassName`: unlaxer-common parser class name to use (without package)

**Example:**

```ubnf
token NUMBER     = NumberParser
token IDENTIFIER = IdentifierParser
token STRING     = StringParser
```

If you write `NUMBER` in a rule, generated code converts it to `Parser.get(NumberParser.class)`.

---

### Rule Declarations

```ubnf
[annotations...]
RuleName ::= body ;
```

- PascalCase is recommended for rule names
- The body can combine choice `|`, sequence, group `()`, optional `[]`, and repetition `{}`
- Trailing `;` is required

**Choice:**

```ubnf
Factor ::= '(' Expression ')' | NUMBER | IDENTIFIER ;
```

**Sequence:**

```ubnf
VariableDeclaration ::= 'var' IDENTIFIER '=' Expression ';' ;
```

**Group `()` - grouping choices:**

```ubnf
VariableDeclaration ::= ( 'var' | 'variable' ) IDENTIFIER ';' ;
```

**Optional `[]` - zero or one time:**

```ubnf
VariableDeclaration ::= 'var' IDENTIFIER [ '=' Expression ] ';' ;
```

**Repetition `{}` - zero or more times:**

```ubnf
Program ::= { VariableDeclaration } Expression ;
```

---

### Element Syntax

| Syntax | Meaning | Generated Code |
|---|---|---|
| `'literal'` | literal string | `new WordParser("literal")` |
| `RuleName` | rule reference | `Parser.get(RuleNameParser.class)` |
| `TOKEN` | token reference (declared with `token`) | `Parser.get(TokenParserClass.class)` |
| `( A \| B )` | group (choice) | helper class `extends LazyChoice` |
| `[ A ]` | optional (0 or 1) | `new Optional(...)` |
| `{ A }` | repetition (0 or more) | `new ZeroOrMore(...)` |

**Capture name `@name`:**

Adding `@name` to the end of an element maps it to an AST record field.

```ubnf
Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;
```

In this example:
- `Term @left` -> `left` field
- `'+' @op` -> `op` field
- `Term @right` -> `right` field

List capture names corresponding to `@mapping` annotation `params`.

---

### Annotations

Place annotations immediately before a rule declaration.

| Annotation | Description |
|---|---|
| `@root` | Declares this rule as the parse entry point (root). Its class is returned by `getRootParser()` |
| `@mapping(ClassName)` | Specifies AST class name for mapping parse tree |
| `@mapping(ClassName, params=[a, b, c])` | Specifies AST class name and field names. Field types are inferred from capture names |
| `@leftAssoc` | Declares a left-associative operator (validated as a contract for left/op/right captures/params; must be paired with `@precedence`) |
| `@rightAssoc` | Declares a right-associative operator. Canonical form `Base { Op Self }` is required (otherwise validation fails), and is emitted as right-recursive parser generation |
| `@precedence(level=10)` | Declares precedence metadata for operator rules. Current validator requires pairing with either `@leftAssoc` or `@rightAssoc` and disallows duplicate declarations on one rule |
| `@whitespace` | Controls whitespace for this rule; it overrides global whitespace settings (optional) |
| `@interleave(profile=...)` | Declares interleave policy metadata (for parser IR / downstream tooling contracts) |
| `@backref(name=...)` | Declares backreference intent metadata (reserved for semantic constraints / diagnostics) |
| `@scopeTree(mode=...)` | Declares scope-tree usage metadata (for symbol pipeline and tooling integration) |

---

## How to Use the Code Generators

All generators implement the `CodeGenerator` interface.

```java
public interface CodeGenerator {
    GeneratedSource generate(GrammarDecl grammar);

    record GeneratedSource(
        String packageName,  // package name for generated code
        String className,    // generated class name
        String source        // full Java source code
    ) {}
}
```

**Example:**

```java
GrammarDecl grammar = UBNFMapper.parse(ubnfSource).grammars().get(0);

// Generate AST
var ast = new ASTGenerator().generate(grammar);
// ast.packageName() -> "org.unlaxer.tinycalc.generated"
// ast.className()   -> "TinyCalcAST"
// ast.source()      -> "package org.unlaxer...public sealed interface TinyCalcAST..."

// Generate parser
var parsers = new ParserGenerator().generate(grammar);

// Generate mapper
var mapper = new MapperGenerator().generate(grammar);

// Generate evaluator
var evaluator = new EvaluatorGenerator().generate(grammar);
```

---

## Generated Artifacts in Detail (TinyCalc Example)

The following grammar (`examples/tinycalc.ubnf`) is used to explain outputs from each generator.

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

Generates `TinyCalcAST.java`. It collects rules with `@mapping` and outputs them as sealed interface + records.

**Generated `TinyCalcAST.java`:**

```java
package org.unlaxer.tinycalc.generated;

import java.util.List;
import java.util.Optional;

public sealed interface TinyCalcAST permits
    TinyCalcAST.TinyCalcProgram,
    TinyCalcAST.VarDecl,
    TinyCalcAST.BinaryExpr {

    // { VariableDeclaration } @declarations -> List<TinyCalcAST.VarDecl>
    // Expression @expression -> TinyCalcAST.BinaryExpr (Expression has @mapping(BinaryExpr))
    record TinyCalcProgram(
        List<TinyCalcAST.VarDecl> declarations,
        TinyCalcAST.BinaryExpr expression
    ) implements TinyCalcAST {}

    // ( 'var' | 'variable' ) @keyword -> Object because it is a grouped element
    // IDENTIFIER @name -> String because it is a token reference
    // [ 'set' Expression @init ] @init -> Optional<TinyCalcAST.BinaryExpr>
    record VarDecl(
        Object keyword,
        String name,
        Optional<TinyCalcAST.BinaryExpr> init
    ) implements TinyCalcAST {}

    // Both Expression and Term have @mapping(BinaryExpr), but only one record is generated
    record BinaryExpr(
        TinyCalcAST.BinaryExpr left,
        String op,
        TinyCalcAST.BinaryExpr right
    ) implements TinyCalcAST {}
}
```

**Field type inference rules:**

| Grammar Element | Inferred Type |
|---|---|
| `{ RuleName } @field` (reference to @mapping rule inside repetition) | `List<TinyCalcAST.ClassName>` |
| `[ RuleName ] @field` (reference to @mapping rule inside optional) | `Optional<TinyCalcAST.ClassName>` |
| `RuleName @field` (reference to @mapping rule) | `TinyCalcAST.ClassName` |
| `TOKEN @field` (token reference) | `String` |
| `'literal' @field` (terminal symbol) | `String` |
| `( A \| B ) @field` (group element) | `Object` |

---

### ParserGenerator

Generates `TinyCalcParsers.java`. It emits parser class groups that correspond to the grammar using unlaxer-common parser combinators.

**Structure of generated `TinyCalcParsers.java`:**

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

    // --- Whitespace Delimiter ---
    // Generated from @whitespace: javaStyle setting
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
    // Base class for sequence parsers. Automatically skips whitespace between parsers.
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

    // --- Helper classes (expanded composite elements) ---

    // Generated from ( 'var' | 'variable' ) in VariableDeclaration
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

    // Generated from [ 'set' Expression @init ] in VariableDeclaration
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

    // Generated from { ( '+' @op | '-' @op ) Term @right } in Expression
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

    // --- Rule classes ---

    // @root rule
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

    // Factor is a 3-way choice body, so it extends LazyChoice
    // Candidate 1 '(' Expression ')' contains multiple elements, so it is wrapped in anonymous TinyCalcLazyChain
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

    // --- Factory ---
    public static Parser getRootParser() {
        return Parser.get(TinyCalcParser.class);
    }
}
```

**Element conversion rules:**

| Grammar Element | Generated Code |
|---|---|
| `'var'` | `new WordParser("var")` |
| `NUMBER` (token declared) | `Parser.get(NumberParser.class)` |
| `Expression` (rule reference) | `Parser.get(ExpressionParser.class)` |
| `{ VariableDeclaration }` (repetition of a single RuleRef) | `new ZeroOrMore(VariableDeclarationParser.class)` |
| `{ ( '+' \| '-' ) Term }` (repetition of composite body) | helper class + `new ZeroOrMore(ExpressionRepeat0Parser.class)` |
| `[ 'set' Expression ]` (optional composite body) | helper class + `new Optional(VariableDeclarationOpt0Parser.class)` |
| `( 'var' \| 'variable' )` (group) | helper class `extends LazyChoice` + `Parser.get(VariableDeclarationGroup0Parser.class)` |

**Helper class naming rules:**

| Pattern | Class Name |
|---|---|
| `{ compositeBody }` (repetition) | `{RuleName}Repeat{N}Parser` |
| `[ compositeBody ]` (optional) | `{RuleName}Opt{N}Parser` |
| `( body )` (group) | `{RuleName}Group{N}Parser` |

`N` is a zero-based sequence number inside each rule.

---

### MapperGenerator

Generates `TinyCalcMapper.java`. It outputs `to{ClassName}(Token)` method skeletons for rules annotated with `@mapping`.

**Structure of generated `TinyCalcMapper.java`:**

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
     * Parses TinyCalc source text and converts it into AST.
     * NOTE: complete implementation after TinyCalcParsers is generated and placed.
     */
    public static TinyCalcAST.TinyCalcProgram parse(String source) {
        // TODO: implement after TinyCalcParsers is generated
        // StringSource stringSource = StringSource.createRootSource(source);
        // try (ParseContext context = new ParseContext(stringSource)) {
        //     Parser rootParser = TinyCalcParsers.getRootParser();
        //     Parsed parsed = rootParser.parse(context);
        //     if (!parsed.isSucceeded()) {
        //         throw new IllegalArgumentException("Parse failed: " + source);
        //     }
        //     return toTinyCalcProgram(parsed.getRootToken());
        // }
        throw new UnsupportedOperationException("TinyCalcParsers: not implemented");
    }

    // --- Mapping methods (skeleton) ---

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

    // BinaryExpr appears in both Expression and Term @mapping, but generated once
    static TinyCalcAST.BinaryExpr toBinaryExpr(Token token) {
        // TODO: extract left, op, right
        return new TinyCalcAST.BinaryExpr(null, null, null);
    }

    // --- Utilities ---

    /** Find descendant Tokens for the specified parser class in depth-first order */
    static List<Token> findDescendants(Token token, Class<? extends Parser> parserClass) { ... }

    /** Remove surrounding single quotes from quoted string */
    static String stripQuotes(String quoted) { ... }
}
```

**Post-generation work (manual implementation):**

`to{ClassName}` methods in `TinyCalcMapper` are generated as TODO skeletons. Implement actual field extraction logic using `findDescendants()`.

```java
// Implementation example
static TinyCalcAST.BinaryExpr toBinaryExpr(Token token) {
    // Get left-hand side Factor or recursive BinaryExpr
    List<Token> leftTokens = findDescendants(token, TermParser.class);
    // Extract op ('+'/'-')
    // Extract right
    ...
}
```

---

### EvaluatorGenerator

Generates `TinyCalcEvaluator.java`. It is an abstract class with type parameter `<T>`, where you implement evaluation logic by overriding methods for each AST node type.

**Structure of generated `TinyCalcEvaluator.java`:**

```java
package org.unlaxer.tinycalc.generated;

public abstract class TinyCalcEvaluator<T> {

    private DebugStrategy debugStrategy = DebugStrategy.NOOP;

    public void setDebugStrategy(DebugStrategy strategy) {
        this.debugStrategy = strategy;
    }

    /** Public entry point. Calls evalInternal with debug hooks. */
    public T eval(TinyCalcAST node) {
        debugStrategy.onEnter(node);
        T result = evalInternal(node);
        debugStrategy.onExit(node, result);
        return result;
    }

    /** Dispatch with sealed switch */
    private T evalInternal(TinyCalcAST node) {
        return switch (node) {
            case TinyCalcAST.TinyCalcProgram n -> evalTinyCalcProgram(n);
            case TinyCalcAST.VarDecl n        -> evalVarDecl(n);
            case TinyCalcAST.BinaryExpr n     -> evalBinaryExpr(n);
        };
    }

    // Abstract methods corresponding to each @mapping class
    protected abstract T evalTinyCalcProgram(TinyCalcAST.TinyCalcProgram node);
    protected abstract T evalVarDecl(TinyCalcAST.VarDecl node);
    protected abstract T evalBinaryExpr(TinyCalcAST.BinaryExpr node);    // shared by Expression and Term

    // --- Debug strategies ---

    public interface DebugStrategy {
        void onEnter(TinyCalcAST node);
        void onExit(TinyCalcAST node, Object result);

        DebugStrategy NOOP = new DebugStrategy() {
            public void onEnter(TinyCalcAST node) {}
            public void onExit(TinyCalcAST node, Object result) {}
        };
    }

    /** Debug implementation that counts eval() call steps */
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

**Evaluator implementation example (arithmetic evaluation):**

```java
public class TinyCalcCalculator extends TinyCalcEvaluator<Double> {

    @Override
    protected Double evalTinyCalcProgram(TinyCalcAST.TinyCalcProgram node) {
        // Process variable declarations, then evaluate final expression
        node.declarations().forEach(d -> eval(d));
        return eval(node.expression());
    }

    @Override
    protected Double evalVarDecl(TinyCalcAST.VarDecl node) {
        // Register variable in environment (implementation omitted)
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

## CodegenMain - CLI Tool

`CodegenMain` is a CLI tool that reads a `.ubnf` file, runs specified generators in batch, and writes generated Java sources to files.

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --output  src/main/java \
  --generators Parser,LSP,Launcher,DAP,DAPLauncher
```

Validation-only mode (no source files are written):

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --validate-only
```

Parser IR validation mode:

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --validate-parser-ir path/to/parser-ir.json
```

Parser IR export mode from UBNF:

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --export-parser-ir build/parser-ir.json
```

Machine-readable report:

```bash
java -cp unlaxer-dsl.jar org.unlaxer.dsl.CodegenMain \
  --grammar path/to/my.ubnf \
  --validate-only \
  --report-format json \
  --report-file build/validation-report.json
```

JSON reports always include stable top-level fields:
`reportVersion`, `schemaVersion`, `schemaUrl`, `toolVersion`, `argsHash`, `generatedAt` (UTC ISO-8601), and `mode` (`validate` or `generate`).
`toolVersion` uses artifact `Implementation-Version` when available, otherwise `dev`.
`argsHash` is SHA-256 over normalized semantic CLI settings (not raw argv), so destination-only flags such as
`--report-file` and `--output-manifest` do not change it.
The public v1 JSON schema is documented at `docs/schema/report-v1.json`.
NDJSON event schema is documented at `docs/schema/report-v1.ndjson.json`.
Manifest schemas are documented at `docs/schema/manifest-v1.json` and `docs/schema/manifest-v1.ndjson.json`.
Parser IR draft schema is documented at `docs/schema/parser-ir-v1.draft.json`.
Validation failure entries in `issues[]` include:
`grammar`, `rule`, `code`, `severity`, `category`, `message`, and `hint`.
Validation failure reports also include `severityCounts` and `categoryCounts` summaries.

| Option | Description | Default |
|---|---|---|
| `--grammar <file>` | Path to `.ubnf` file | (required) |
| `--output <dir>` | Output root directory (written with package structure) | required unless `--validate-only` |
| `--generators <list>` | Comma-separated generator names | `Parser,LSP,Launcher` |
| `--validate-parser-ir <file>` | Validate Parser IR JSON only (skip grammar parsing/generation) | (none) |
| `--export-parser-ir <file>` | Export Parser IR JSON from UBNF grammar (skip code generation) | (none) |
| `--dry-run` | Preview generated file paths without writing files | `false` |
| `--clean-output` | Delete planned target files before generation | `false` |
| `--overwrite never\|if-different\|always` | Overwrite policy for existing files | `always` |
| `--fail-on none\|warning\|skipped\|conflict\|cleaned\|warnings-count>=N` | Additional failure policy trigger | `conflict` |
| `--strict` | Treat warnings as validation failures | `false` |
| `--help`, `-h` | Print usage and exit | `false` |
| `--version`, `-v` | Print tool version and exit | `false` |
| `--validate-only` | Run grammar validation only (skip code generation) | `false` |
| `--report-format text\|json\|ndjson` | Output/report format | `text` |
| `--report-file <path>` | Write report payload to a file | (none) |
| `--output-manifest <path>` | Write generation/validation action manifest JSON | (none) |
| `--manifest-format json\|ndjson` | Manifest output format (`--output-manifest`) | `json` |
| `--report-version 1` | JSON report schema version | `1` |
| `--report-schema-check` | Validate JSON payload shape before emitting it | `false` |
| `--warnings-as-json` | Emit warning diagnostics as JSON to stderr (text mode) | `false` |

Available generator names: `AST`, `Parser`, `Mapper`, `Evaluator`, `LSP`, `Launcher`, `DAP`, `DAPLauncher`
`--generators` values are trimmed by comma, empty entries are rejected (for example, `"AST, LSP"` is valid).
When `--report-schema-check` fails, error messages are prefixed with `E-REPORT-SCHEMA-*`.
`--warnings-as-json` emits warning payloads using the same JSON shape as validation failure reports.
JSON payloads expose `warningsCount` so clients can detect warnings without scanning `issues[]`.
Generation JSON payloads also expose `writtenCount`, `skippedCount`, `conflictCount`, and `dryRunCount`, with `failReasonCode` on policy failure.
`ndjson` emits one JSON object per line (file events + summary event) for streaming-friendly integrations.
`ndjson` also emits `cli-error` events for CLI failures (usage/argument errors and runtime CLI failures such as unknown generator names).
`cli-error` fields are stable: `code`, `message`, nullable `detail`, and `availableGenerators` (empty unless relevant).
`code` follows the stable pattern `E-[A-Z0-9-]+`.
Common `code` values include `E-CLI-USAGE`, `E-CLI-UNKNOWN-GENERATOR`, `E-CLI-UNSAFE-CLEAN-OUTPUT`, `E-PARSER-IR-EXPORT`, `E-IO`, and `E-RUNTIME`.
In `ndjson` mode, `stdout` is JSON-lines only (no human progress text).
In `ndjson` generation mode, conflict/fail-on human messages are suppressed from `stderr`.
In `ndjson` validation failure paths, `stderr` is also JSON-lines only.
In `ndjson` generation failure paths, events are emitted to `stdout` and `stderr` remains empty.
When `--report-file` is used with `ndjson`, the file stores the raw JSON payload (not the NDJSON event wrapper).
For warning-only validate runs (`--fail-on none`), the file stores the final `validate` success payload.
`--fail-on warnings-count>=N` fails when warning count reaches/exceeds `N`.
`--report-schema-check` also validates manifest payloads when `--output-manifest` is used.

Exit codes:

| Code | Meaning |
|---|---|
| `0` | Success |
| `2` | CLI usage/argument error |
| `3` | Grammar validation failure |
| `4` | Generation/runtime failure |
| `5` | Strict validation failure (warnings treated as errors) |

---

## Building the VS Code Extension (VSIX)

The `tinycalc-vscode/` directory contains a sample VS Code extension for the TinyCalc language.
Running `mvn verify` performs the full pipeline in one command: **grammar definition -> code generation -> fat jar -> VSIX**.

### Prerequisite

```bash
# Install unlaxer-dsl into local Maven repository (first time only)
cd unlaxer-dsl
mvn install -DskipTests
```

### Build

```bash
cd tinycalc-vscode
mvn verify
# -> target/tinycalc-lsp-0.1.0.vsix
```

### What Each Maven Phase Does

| Phase | Process | Output |
|---|---|---|
| `generate-sources` | `CodegenMain` reads `grammar/tinycalc.ubnf` and generates Java sources (Parser, LSP, Launcher, DAP, DAPLauncher) | `target/generated-sources/tinycalc/` |
| `compile` | Compiles the 5 generated classes | `target/classes/` |
| `package` | Creates fat jar with `maven-shade-plugin` (includes both LSP and DAP classes) and copies to `server-dist/` | `target/tinycalc-lsp-server.jar` |
| `verify` | `npm install` -> `npx vsce package` (also compiles TypeScript internally) | `target/tinycalc-lsp-0.1.0.vsix` |

### Install

```bash
code --install-extension tinycalc-vscode/target/tinycalc-lsp-0.1.0.vsix
```

When you open a `.tcalc` file, the LSP server starts automatically.

To debug with DAP, press `F5` (or create `launch.json`).
With a `.tcalc` file open in the editor, press `F5` and choose `TinyCalc Debug`;
the file is parsed and results appear in the Debug Console.

**Normal run (`stopOnEntry: false`):**

```json
// Example .vscode/launch.json
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

**Step run (`stopOnEntry: true`):**

With `stopOnEntry: true`, token-by-token stepping is enabled.
Parsing stops at the first token, and `F10` (next) advances one token at a time.
The Variables panel shows the current token text and parser class name.

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

| Action | Description |
|---|---|
| `F5` (Continue) | Run until next breakpoint (or finish if none) |
| `F10` (Next) | Move to the next token |
| Variables panel | Shows current token text and parser class name |
| Editor highlight | Automatically indicates current token line/column |
| Breakpoint | Click gutter (left of line number) to set. Becomes active immediately with `verified: true` |

**LSP and DAP are packaged in the same fat jar.** The extension launches LSP via `-jar`, and DAP via `-cp ... TinyCalcDapLauncher`.

---

## Tutorial 1: TinyCalc

TinyCalc is a small calculator DSL that supports variable declarations and basic arithmetic. Here is the flow from grammar definition to evaluation.

### Step 1: Define the grammar

See `examples/tinycalc.ubnf` (the TinyCalc grammar shown above).

### Step 2: Generate code

```java
String src = Files.readString(Path.of("examples/tinycalc.ubnf"));
GrammarDecl grammar = UBNFMapper.parse(src).grammars().get(0);

// Generate 4 kinds of code and save to src/main/java/org/unlaxer/tinycalc/generated/
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

### Step 3: Implement `TinyCalcMapper.parse()`

Complete the TODO part in the generated `parse()` method in `TinyCalcMapper.java`.

```java
public static TinyCalcAST.TinyCalcProgram parse(String source) {
    StringSource stringSource = StringSource.createRootSource(source);
    try (ParseContext context = new ParseContext(stringSource)) {
        Parser rootParser = TinyCalcParsers.getRootParser();
        Parsed parsed = rootParser.parse(context);
        if (!parsed.isSucceeded()) {
            throw new IllegalArgumentException("Parse failed: " + source);
        }
        return toTinyCalcProgram(parsed.getRootToken());
    }
}
```

### Step 4: Implement evaluator and run evaluation

```java
TinyCalcAST.TinyCalcProgram ast = TinyCalcMapper.parse("1 + 2 * 3");
TinyCalcCalculator calc = new TinyCalcCalculator();
Double result = calc.eval(ast);
System.out.println(result); // 7.0
```

### Step 5: External Parser Adapter with ScopeTree Metadata

For non-UBNF parsers, emit parser-IR scope events from rule-level metadata:

```java
// generated metadata API (from ParserGenerator)
Map<String, TinyCalcParsers.ScopeMode> modeByRule = TinyCalcParsers.getScopeTreeModeByRule();

List<Object> scopeEvents = ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRulesAnyMode(
    "TinyCalc",
    modeByRule,
    nodes
);
```

Reference executable sample:
- `src/test/java/org/unlaxer/dsl/ParserIrAdapterContractTest.java` (`ScopeTreeSampleAdapter`)

---

## Tutorial 2: Building a VS Code Extension for UBNF

In this tutorial, you will use `grammar/ubnf.ubnf` (the grammar definition of UBNF itself)
as input and build a VS Code extension (VSIX) for `.ubnf` files from scratch.

Unlike TinyCalc, this has a special constraint:
**the grammar definition itself depends on handwritten implementation details**.
This section explains both the build flow and the workaround.

---

### Background and Technical Constraint

`grammar/ubnf.ubnf` contains the following token declaration:

```ubnf
token STRING = SingleQuotedParser
```

`SingleQuotedParser` is an **inner class** in `UBNFParsers.java` from `unlaxer-common`.
If you generate parsers with normal `--generators Parser`, runtime `ClassNotFoundException` occurs.

**Workaround**: generate only `--generators LSP,Launcher` (do not generate Parser),
and manually place a shim class in `src/main/java/` that delegates `getRootParser()`
to handwritten `org.unlaxer.dsl.bootstrap.UBNFParsers`.

```
Auto-generated: UBNFLanguageServer.java  (LSP server)
                UBNFLspLauncher.java     (main class)
Manual shim:    UBNFParsers.java         (delegates to bootstrap getRootParser())
```

---

### Step 1: Create directories

```bash
mkdir -p unlaxer-dsl/ubnf-vscode/src/main/java/org/unlaxer/dsl/bootstrap/generated
mkdir -p unlaxer-dsl/ubnf-vscode/syntaxes
```

---

### Step 2: Create `UBNFParsers.java` (shim)

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

`UBNFLanguageServer.java` generated by `LSPGenerator`
calls `org.unlaxer.dsl.bootstrap.generated.UBNFParsers.getRootParser()`.
This shim bridges that call to the handwritten bootstrap implementation.

---

### Step 3: Create `pom.xml`

Compared with TinyCalc `pom.xml`, there are four main differences:

| Setting | tinycalc-vscode | ubnf-vscode |
|---|---|---|
| `--grammar` | `grammar/tinycalc.ubnf` | `../grammar/ubnf.ubnf` |
| `--generators` | `Parser,LSP,Launcher` | `LSP,Launcher` |
| shade `mainClass` | `TinyCalcLspLauncher` | `org.unlaxer.dsl.bootstrap.generated.UBNFLspLauncher` |
| fat jar name | `tinycalc-lsp-server` | `ubnf-lsp-server` |

`build-helper-maven-plugin` is still required
to add generated LSP/Launcher code from `target/generated-sources/ubnf/`
to the compile source set.

Per-phase processing in Maven:

| Phase | Process | Output |
|---|---|---|
| `generate-sources` | `CodegenMain` reads `grammar/ubnf.ubnf` and generates LSP/Launcher | `target/generated-sources/ubnf/` |
| `compile` | Compiles shim + generated code (`UBNFLanguageServer`, `UBNFLspLauncher`) | `target/classes/` |
| `package` | Creates fat jar with `maven-shade-plugin` and copies to `server-dist/` | `target/ubnf-lsp-server.jar` |
| `verify` | `npm install` -> `npx vsce package` (also compiles TypeScript) | `target/ubnf-lsp-0.1.0.vsix` |

---

### Step 4: Create VS Code extension config files

**`package.json`**: language ID `ubnf`, extension `.ubnf`, settings key prefix `ubnfLsp.server.*`

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

**`src/extension.ts`**: only change `tinycalcLsp` -> `ubnfLsp` and jar path to `server-dist/ubnf-lsp-server.jar`.

---

### Step 5: Create syntax highlighting definition

Define the following patterns in `syntaxes/ubnf.tmLanguage.json`.

| Pattern | Scope |
|---|---|
| `//.*$` | `comment.line.double-slash.ubnf` |
| `\bgrammar\b`, `\btoken\b` | `keyword.control.ubnf` |
| `::=`, `\|`, `;` | `keyword.operator.ubnf` |
| `@root`, `@mapping`, `@whitespace`, `@interleave`, `@backref`, `@scopeTree`, `@leftAssoc`, `@rightAssoc`, `@precedence` | `storage.modifier.ubnf` |
| `'[^']*'` | `string.quoted.single.ubnf` |
| `[A-Z][A-Z_0-9]*` | `entity.name.type.ubnf` |

---

### Step 6: Build

```bash
# Install unlaxer-dsl core to local repository first (first time only)
cd unlaxer-dsl
mvn install -DskipTests

# Build ubnf-vscode
cd ubnf-vscode
mvn verify
```

On success, `target/ubnf-lsp-0.1.0.vsix` is generated.

```
 DONE  Packaged: target/ubnf-lsp-0.1.0.vsix (7 files, 2.19 MB)
```

Check VSIX contents:

```bash
unzip -l target/ubnf-lsp-0.1.0.vsix
# extension/server-dist/ubnf-lsp-server.jar  <- fat jar (~2.4 MB)
# extension/out/extension.js                 <- compiled TypeScript
# extension/syntaxes/ubnf.tmLanguage.json
# extension/language-configuration.json
# extension/package.json
```

---

### Step 7: Install into VS Code

```bash
code --install-extension target/ubnf-lsp-0.1.0.vsix
```

After reloading VS Code (`Ctrl+Shift+P` -> `Developer: Reload Window`),
opening a `.ubnf` file enables the following features:

| Feature | Details |
|---|---|
| Syntax highlighting | Colors comments, keywords, operators, annotations, strings, and type names |
| Parse diagnostics | Shows syntax errors with red squiggles |
| Hover | Shows parse status at cursor (`Valid UBNF` / `Parse error at offset N`) |
| Completion | Suggests keywords such as `grammar`, `token`, etc. |

---

### Comparison with TinyCalc

| Item | tinycalc-vscode | ubnf-vscode |
|---|---|---|
| Grammar file | `grammar/tinycalc.ubnf` | `../grammar/ubnf.ubnf` |
| `--generators` | `Parser,LSP,Launcher,DAP,DAPLauncher` | `LSP,Launcher` (Parser via shim, no DAP) |
| shim | Not required | `generated/UBNFParsers.java` required |
| DAP support | Yes (`TinyCalcDebugAdapter` + `TinyCalcDapLauncher`) | No |
| Language ID | `tinycalc` | `ubnf` |
| Extension | `.tcalc` | `.ubnf` |
| fat jar | `tinycalc-lsp-server.jar` (LSP + DAP) | `ubnf-lsp-server.jar` (LSP only) |

---

## Project Structure

```
unlaxer-dsl/
├── grammar/
│   └── ubnf.ubnf              Self-hosting grammar: UBNF described in UBNF
├── examples/
│   └── tinycalc.ubnf          TinyCalc sample grammar
├── src/
│   ├── main/java/org/unlaxer/dsl/
│   │   ├── CodegenMain.java       CLI tool (bulk generates Java sources from ubnf)
│   │   ├── bootstrap/
│   │   │   ├── UBNFAST.java       UBNF AST (sealed interface + record)
│   │   │   ├── UBNFParsers.java   Bootstrap parser (handwritten)
│   │   │   └── UBNFMapper.java    Parse tree -> AST mapper
│   │   └── codegen/
│   │       ├── CodeGenerator.java         Common interface
│   │       ├── ASTGenerator.java          XxxAST.java generator
│   │       ├── ParserGenerator.java       XxxParsers.java generator
│   │       ├── MapperGenerator.java       XxxMapper.java generator
│   │       ├── EvaluatorGenerator.java    XxxEvaluator.java generator
│   │       ├── LSPGenerator.java          XxxLanguageServer.java generator
│   │       ├── LSPLauncherGenerator.java  XxxLspLauncher.java generator
│   │       ├── DAPGenerator.java          XxxDebugAdapter.java generator
│   │       └── DAPLauncherGenerator.java  XxxDapLauncher.java generator
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
│           ├── CompileVerificationTest.java    Compile verification for generated Java sources
│           ├── SelfHostingTest.java            Structure and compile verification for generated UBNFParsers
│           └── SelfHostingRoundTripTest.java   Round-trip parse of ubnf.ubnf using generated parser
├── tinycalc-vscode/           VS Code extension sample (TinyCalc, LSP + DAP)
│   ├── pom.xml                Maven build config (codegen -> compile -> jar -> VSIX)
│   ├── grammar/
│   │   └── tinycalc.ubnf      Source grammar for extension (input to CodegenMain)
│   ├── src/
│   │   └── extension.ts       VS Code client (LSP + DAP factory registration)
│   ├── syntaxes/
│   │   └── tinycalc.tmLanguage.json  TextMate grammar (syntax highlighting)
│   ├── language-configuration.json
│   ├── package.json
│   └── target/                <- build outputs (gitignored)
│       ├── generated-sources/ <- generated Java sources (Parser, LSP, Launcher, DAP, DAPLauncher)
│       ├── tinycalc-lsp-server.jar  <- fat jar (includes both LSP and DAP)
│       └── tinycalc-lsp-0.1.0.vsix <- VS Code extension package
├── ubnf-vscode/               VS Code extension (editor for UBNF grammar itself)
│   ├── pom.xml                Maven build config (generates only LSP, Launcher)
│   ├── src/
│   │   ├── extension.ts       VS Code client (TypeScript)
│   │   └── main/java/org/unlaxer/dsl/bootstrap/generated/
│   │       └── UBNFParsers.java   Handwritten shim (delegates to bootstrap.UBNFParsers)
│   ├── syntaxes/
│   │   └── ubnf.tmLanguage.json  TextMate grammar (syntax highlighting)
│   ├── language-configuration.json
│   ├── package.json
│   └── target/                <- build outputs (gitignored)
│       ├── generated-sources/ <- generated Java sources (LSP, Launcher only)
│       ├── ubnf-lsp-server.jar    <- fat jar
│       └── ubnf-lsp-0.1.0.vsix   <- VS Code extension package
 └── pom.xml
```

---

## Self-Hosting

`unlaxer-dsl` has achieved **self-hosting**.
When `grammar/ubnf.ubnf` (the UBNF grammar itself written in UBNF) is processed by `ParserGenerator`,
it generates `org.unlaxer.dsl.bootstrap.generated.UBNFParsers`.
`SelfHostingRoundTripTest` verifies that this generated parser can fully parse `ubnf.ubnf` itself.

### Round-trip Verification Flow

```
grammar/ubnf.ubnf
    │
    ▼  Handwritten bootstrap (UBNFParsers + UBNFAST + UBNFMapper)
GrammarDecl (AST)
    │
    ▼  ParserGenerator.generate()
generated/UBNFParsers.java (source string)
    │
    ▼  javax.tools.JavaCompiler (--enable-preview --release 21)
generated/UBNFParsers.class (in-memory compile -> tmpDir)
    │
    ▼  URLClassLoader + reflective getRootParser() call
Parser (root parser of generated parser)
    │
    ▼  parser.parse(ParseContext(grammar/ubnf.ubnf))
Parsed (success + full input consumed) <- verified in SelfHostingRoundTripTest
```

### Bug Found and Fixed

During self-hosting implementation, a bug was found and fixed in `UBNFMapper.toTokenDecl()`.

**Bug**: the trailing `IdentifierParser` in `token CLASS_NAME = IdentifierParser`
uses handwritten `UBNFParsers.IdentifierParser extends UBNFLazyChain`,
so trailing SPACE (including CPPComment) was included in token source.
As a result, `source.toString().trim()` returned `"IdentifierParser\n\n// comment"`,
and `ParserGenerator` generated invalid code:
`Parser.get(IdentifierParser\n\n// comment.class)`.

**Fix**: added `firstWord()` helper to `toTokenDecl()`,
so everything after the first whitespace is removed and only a pure class name is extracted.

```java
// Before
String parserClass = identifiers.get(1).source.toString().trim();

// After
String parserClass = firstWord(identifiers.get(1).source.toString());
// firstWord(): returns only the part before first whitespace
```

### Current Scope of Self-Hosting

| Component | Auto-generated | Description |
|---|---|---|
| `UBNFParsers` (parser) | Yes | Generated by `ParserGenerator`, verified with round-trip test |
| `UBNFAST` (AST) | No | `ASTGenerator` does not yet generate nested sealed interfaces |
| `UBNFMapper` (mapper) | No | `MapperGenerator` outputs stubs only (handwritten implementation still required) |

Full auto-generation for the two components other than `UBNFParsers` remains future work.

---

## Roadmap

| Phase | Description | Status |
|---|---|---|
| Phase 0 | UBNF grammar definition (`grammar/ubnf.ubnf`) | Done |
| Phase 1 | Bootstrap parser (`UBNFParsers.java`) | Done |
| Phase 2 | AST definitions + bootstrap mapper (`UBNFAST.java`, `UBNFMapper.java`) | Done |
| Phase 3 | ASTGenerator / EvaluatorGenerator / MapperGenerator implementation | Done |
| Phase 4 | ParserGenerator implementation | Done |
| Phase 5 | TinyCalc integration test (`TinyCalcIntegrationTest`) | Done |
| Phase 6 | Self-hosting test (`SelfHostingTest`) | Done |
| Phase 7 | Compile verification test (`CompileVerificationTest`) | Done |
| Phase 8 | LSP server code generation (`LSPGenerator`, `LSPLauncherGenerator`, `CodegenMain`) | Done |
| Phase 9 | One-command VSIX build (`tinycalc-vscode/pom.xml`) | Done |
| Phase 9.5 | VS Code extension for UBNF itself (`ubnf-vscode/`, with shim pattern) | Done |
| Phase 10 | DAP code generation (`DAPGenerator`, `DAPLauncherGenerator`) | Done |
| Phase 11 | Self-hosting (`parse ubnf.ubnf itself using UBNFParsers generated from grammar/ubnf.ubnf`) | Done |
| Phase 12 | DAP token-level stepping (`F10` next, Variables panel, stackTrace line highlight) | Done |
| Phase 13 | DAP breakpoint support (line matching, continue to next breakpoint) | Done |

---

## License

MIT License - Copyright (c) 2026 opaopa6969
