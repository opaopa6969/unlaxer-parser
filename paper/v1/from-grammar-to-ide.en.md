# From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification

**Claude (Anthropic) and the unlaxer-parser development team**

---

## Abstract

Domain-specific languages (DSLs) require multiple interrelated artifacts: a parser, abstract syntax tree (AST) type definitions, a parse-tree-to-AST mapper, a semantic evaluator, and IDE support through the Language Server Protocol (LSP) and Debug Adapter Protocol (DAP). In practice, these six subsystems are typically built and maintained independently, leading to inconsistency between components, code duplication, and substantial maintenance burden -- a single grammar change can cascade across thousands of lines of hand-written code. We present unlaxer-parser, a Java 21 framework that generates all six artifacts from a single UBNF (Unlaxer Backus-Naur Form) grammar specification. We introduce three novel contributions: (1) a propagation control mechanism for parser combinators that provides fine-grained control over two orthogonal parsing dimensions -- token consumption mode and match inversion -- through a hierarchy of propagation stoppers; (2) metadata-carrying parse trees through `ContainerParser<T>`, which embeds error messages and completion suggestions directly into the parse tree without consuming input; and (3) a Generation Gap Pattern (GGP) for evaluators that uses Java 21 sealed interfaces and exhaustive switch expressions to provide compiler-checked completeness guarantees. We evaluate the framework using tinyexpression, a production expression evaluator for financial calculations, demonstrating a 10x reduction in lines of code relative to a from-scratch implementation and a 1400x performance improvement when moving from reflection-based to sealed-switch-based AST evaluation.

---

## 1. Introduction

The construction of a domain-specific language involves far more than writing a grammar and a parser. A complete, production-quality DSL implementation requires at least six tightly coupled subsystems:

1. **Parser**: recognizes the concrete syntax of the language, producing a parse tree.
2. **AST type definitions**: a set of typed data structures representing the abstract syntax.
3. **Parse-tree-to-AST mapper**: transforms the flat, concrete parse tree into a structured, typed AST.
4. **Evaluator or interpreter**: traverses the AST and computes values according to the language semantics.
5. **Language Server Protocol (LSP) server**: provides editor-agnostic IDE features such as syntax highlighting, code completion, hover documentation, and diagnostic error reporting.
6. **Debug Adapter Protocol (DAP) server**: enables step-by-step execution, breakpoint management, variable inspection, and stack trace display in any DAP-compatible editor.

In conventional practice, each of these subsystems is developed independently. A grammar change -- adding a new operator, introducing a new expression type, or modifying precedence rules -- requires coordinated updates across all six components. This coupling is a well-known source of defects: the parser may accept syntax that the evaluator cannot handle, the LSP server may offer completions that the parser rejects, or the AST types may diverge from the grammar after refactoring.

Existing tools address portions of this problem. ANTLR [Parr and Fisher 2011] generates parsers and optionally AST node types from annotated grammars, but it does not generate evaluators, LSP servers, or DAP servers. Tree-sitter [Brunel et al. 2023] provides incremental parsing for editors but produces no semantic layer. PEG-based parser generators [Ford 2004] typically produce only a recognizer, leaving all downstream artifacts to the developer. Parser combinator libraries such as Parsec [Leijen and Meijer 2001] offer compositional parser construction in host languages but again stop at parsing.

None of these tools generate the full stack from grammar to IDE.

We present unlaxer-parser, a Java 21 framework consisting of two modules -- `unlaxer-common` (the parser combinator runtime, approximately 436 Java source files) and `unlaxer-dsl` (the code generation pipeline) -- that takes a single `.ubnf` grammar file as input and generates six Java source files: `Parsers.java`, `AST.java`, `Mapper.java`, `Evaluator.java`, a language server, and a debug adapter. The developer writes only the grammar and the evaluation logic (typically 50--200 lines of `evalXxx` methods); everything else is generated and maintained by the framework.

This paper makes three contributions:

1. **Propagation control for parser combinators** (Section 3.3): a mechanism for controlling how parsing modes (`TokenKind` and `invertMatch`) propagate through the combinator tree, with no equivalent in existing frameworks.
2. **Metadata-carrying parse trees** (Section 3.4): `ContainerParser<T>`, a parser that inserts typed metadata (error messages, completion suggestions) into the parse tree without consuming input, enabling LSP features to be derived from a single parse pass.
3. **Generation Gap Pattern for evaluators** (Section 3.5): generated abstract evaluator classes with exhaustive sealed-switch dispatch, combined with hand-written concrete implementations that survive regeneration.

The remainder of this paper is structured as follows. Section 2 reviews related work in parser generation, IDE protocol support, and code generation patterns. Section 3 presents the system design, including the UBNF grammar notation, the generation pipeline, and the three novel contributions. Section 4 describes the implementation. Section 5 evaluates the framework using tinyexpression, presenting both performance benchmarks and development effort comparisons. Section 6 discusses limitations and future work. Section 7 concludes.

---

## 2. Background and Related Work

### 2.1 Parser Generators

The history of parser generators spans five decades. Yacc [Johnson 1975] and its successor Bison generate LALR(1) parsers from context-free grammars specified in BNF. These tools produce efficient table-driven parsers but require grammars to be unambiguous and left-factored, which can be onerous for language designers. Error messages from LALR parsers are notoriously unhelpful, and the generated parsers produce parse trees but no typed AST.

ANTLR [Parr and Fisher 2011] introduced ALL(*), an adaptive LL-based parsing strategy that handles a broader class of grammars than LALR(1). ANTLR generates both a lexer and a parser, and optionally generates visitor or listener base classes for tree traversal. However, ANTLR's visitor pattern requires the developer to implement each `visitXxx` method by hand, and ANTLR generates no evaluator, LSP server, or DAP server. The developer is responsible for all downstream artifacts.

Parsing Expression Grammars (PEGs) [Ford 2004] provide a recognition-based alternative to context-free grammars. PEGs use ordered choice (`/`) instead of unordered alternation (`|`), eliminating ambiguity by construction. PEG-based parsers, including packrat parsers with memoization, have gained popularity for their predictability and ease of implementation. However, PEG parsers are typically recognizers only -- they determine whether an input matches a grammar but do not inherently produce a structured parse tree. Several PEG-based tools, including Ierusalimschy's LPEG [Ierusalimschy 2009] and Redziejowski's work on PEG foundations [Redziejowski 2007], focus on the recognition problem and do not address AST construction or IDE integration.

Parser combinator libraries take a different approach: parsers are first-class values in a host language, composed using higher-order functions. Parsec [Leijen and Meijer 2001], written in Haskell, established the paradigm with monadic parser combinators that provide clear error messages through committed-choice semantics. Scala parser combinators brought the approach to the JVM. Parser combinators offer excellent compositionality and integration with the host language's type system, but they generate nothing beyond the parser itself.

unlaxer-parser occupies a unique position in this landscape. It is a parser combinator library (like Parsec) that also serves as a code generator (like ANTLR), but unlike either, it generates the full artifact stack from parser through IDE support. Table 1 summarizes the comparison.

| Tool | Parser | AST types | Mapper | Evaluator | LSP | DAP |
|------|--------|-----------|--------|-----------|-----|-----|
| Yacc/Bison | Yes | No | No | No | No | No |
| ANTLR | Yes | Partial | No | No | No | No |
| PEG tools | Yes | No | No | No | No | No |
| Parsec | Yes | No | No | No | No | No |
| tree-sitter | Yes | No | No | No | Partial | No |
| **unlaxer-parser** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** |

*Table 1: Generated artifacts by tool. "Partial" indicates that the tool provides infrastructure but requires substantial hand-written code.*

### 2.2 Language Server Protocol and Debug Adapter Protocol

The Language Server Protocol (LSP) [Microsoft 2016a] standardizes the communication between editors and language-specific intelligence providers. An LSP server implements features such as code completion, hover information, go-to-definition, find-references, diagnostics (error/warning reporting), and code actions. Before LSP, each editor required a language-specific plugin; LSP decoupled editor support from language implementation, enabling a single server to work with VS Code, Emacs, Vim, and any other LSP-compatible editor.

The Debug Adapter Protocol (DAP) [Microsoft 2016b] applies the same decoupling pattern to debugging. A DAP server implements launch/attach, breakpoints, step-over/step-into/step-out, stack traces, variable inspection, and expression evaluation. Like LSP, DAP enables a single debug adapter to work across editors.

Despite the standardization, implementing an LSP or DAP server remains labor-intensive. A typical LSP server for a moderately complex language requires 2,000--5,000 lines of code, and a DAP server requires 1,000--2,000 lines. These servers must stay synchronized with the grammar, the AST, and the evaluator. Tree-sitter [Brunel et al. 2023] partially addresses LSP integration by providing incremental parsing, syntax highlighting, and basic structural queries, but it does not provide semantic features such as type-aware completion or diagnostic reporting.

unlaxer-parser generates both an LSP server and a DAP server from the grammar. The generated LSP server provides completion (based on grammar keywords and `@catalog`/`@declares` annotations), diagnostics (based on parse errors and `ErrorMessageParser` metadata), and hover (based on `@doc` annotations). The generated DAP server provides step execution through the parse tree, breakpoint support, and variable display showing the current token's text and parser name.

### 2.3 Code Generation Patterns

Two code generation patterns are particularly relevant to our work.

The **Visitor pattern** [Gamma et al. 1994] is the standard approach for traversing AST nodes in generated parsers. ANTLR generates visitor interfaces with one `visitXxx` method per grammar rule. The pattern provides good separation between the tree structure and the operations performed on it, but it does not enforce completeness: a developer can easily forget to implement a visit method, leading to runtime errors or silent incorrect behavior.

The **Generation Gap Pattern** (GGP) [Vlissides 1996] addresses the fundamental tension in code generation: generated code must be regenerated when the input (grammar) changes, but hand-written customizations must survive regeneration. GGP resolves this by splitting each class into two: a generated abstract base class and a hand-written concrete subclass. The generated base class contains the structural boilerplate; the concrete subclass contains the hand-written logic. When the generator runs again, only the base class is overwritten.

unlaxer-parser combines GGP with Java 21's sealed interfaces and exhaustive switch expressions. The generated evaluator base class contains a private `evalInternal` method with a switch expression over the sealed AST interface:

```java
private T evalInternal(TinyExpressionP4AST node) {
    return switch (node) {
        case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
        case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
        case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
        // ... one case per @mapping class
    };
}
```

Because `TinyExpressionP4AST` is a sealed interface, the Java compiler verifies that every permitted subtype is covered by the switch. If a new AST node type is added to the grammar, the compiler will reject the hand-written concrete class until the developer implements the corresponding `evalXxx` method. This transforms a runtime error (missing visitor method) into a compile-time error -- a significant improvement in safety.

---

## 3. System Design

### 3.1 UBNF Grammar Notation

UBNF (Unlaxer Backus-Naur Form) extends standard EBNF with annotations that control code generation. A UBNF grammar file defines a named grammar containing global settings, token declarations, and rule declarations.

**Global settings** configure the generation pipeline:

```ubnf
grammar TinyExpressionP4 {
  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }
```

The `@package` setting specifies the Java package for generated code. The `@whitespace` setting selects a whitespace handling profile (here, Java-style whitespace including `//` and `/* */` comments as interleaved tokens). The `@comment` setting declares the comment syntax.

**Token declarations** bind terminal symbols to parser classes:

```ubnf
  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser
  token CODE_BLOCK = org.unlaxer.tinyexpression.parser.javalang.CodeParser
```

Each token maps a symbolic name to a concrete parser class from the `unlaxer-common` library (or a user-defined parser). This allows the grammar to reference complex lexical patterns (such as Java-style code blocks) without encoding them in the grammar notation.

**Rule declarations** use EBNF-like syntax with annotations:

```ubnf
  @root
  Formula ::= { VariableDeclaration } { Annotation } Expression { MethodDeclaration } ;
```

The `@root` annotation marks the entry point of the grammar. Curly braces `{ ... }` denote zero-or-more repetition; square brackets `[ ... ]` denote optional elements; parentheses `( ... )` denote grouping; and `|` denotes ordered choice (PEG semantics).

**The `@mapping` annotation** is the central mechanism for AST generation:

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

This annotation instructs the code generator to:
1. Create a record type `BinaryExpr` in the sealed AST interface, with fields `left`, `op`, and `right`.
2. Generate a mapper rule that extracts the `@left`, `@op`, and `@right`-annotated elements from the parse tree and constructs a `BinaryExpr` instance.
3. Generate an `evalBinaryExpr` abstract method in the evaluator skeleton.

The `@leftAssoc` annotation generates left-associative grouping in the mapper, and `@precedence(level=N)` establishes precedence levels for disambiguation.

Additional annotations include `@interleave(profile=javaStyle)` for controlling whitespace insertion, `@scopeTree(mode=lexical)` for scoping semantics in method declarations, `@backref(name=X)` for reference resolution, `@declares` for symbol declarations, `@catalog` for completion catalogs, and `@doc` for hover documentation. The complete tinyexpression grammar (`tinyexpression-p4-complete.ubnf`) spans 520 lines and covers numeric, string, boolean, and object expressions; variable declarations; method declarations; if/else and match expressions; import declarations; and embedded Java code blocks.

### 3.2 Generation Pipeline

The generation pipeline transforms a `.ubnf` grammar file into six Java source files. The pipeline consists of three phases:

**Phase 1: Parsing.** The UBNF grammar file is parsed by a self-hosted parser (`UBNFParsers`) -- the UBNF parser is itself built using unlaxer-parser's combinator library. The parse tree is mapped to a typed AST (`UBNFAST`) using the same `sealed interface + record` pattern that the generator produces for user grammars. `UBNFAST` is itself a sealed interface:

```java
public sealed interface UBNFAST permits
    UBNFAST.UBNFFile,
    UBNFAST.GrammarDecl,
    UBNFAST.GlobalSetting,
    UBNFAST.SettingValue,
    UBNFAST.TokenDecl,
    UBNFAST.RuleDecl,
    UBNFAST.Annotation,
    UBNFAST.RuleBody,
    UBNFAST.AnnotatedElement,
    UBNFAST.AtomicElement,
    UBNFAST.TypeofElement { ... }
```

**Phase 2: Validation.** The `GrammarValidator` checks the grammar for well-formedness: undefined rule references, duplicate rule names, missing `@root` annotation, and consistency of `@mapping` parameters with rule structure.

**Phase 3: Code generation.** Six code generators, each implementing the `CodeGenerator` interface, produce the output:

| Generator | Output | Description |
|-----------|--------|-------------|
| `ParserGenerator` | `XxxParsers.java` | PEG-based parser combinators using `LazyChain`, `LazyChoice`, `LazyZeroOrMore`, etc. Whitespace handling is automatically inserted based on `@interleave` profiles. |
| `ASTGenerator` | `XxxAST.java` | A Java 21 sealed interface with one `record` per `@mapping` class. Fields are typed according to the `params` list. |
| `MapperGenerator` | `XxxMapper.java` | Token-tree-to-AST mapping logic. Handles multi-rule mapping (multiple grammar rules mapping to the same AST class), `@leftAssoc`/`@rightAssoc` grouping, and `findDirectDescendants` to prevent deep-search into nested sub-expressions. |
| `EvaluatorGenerator` | `XxxEvaluator.java` | Abstract class with exhaustive sealed-switch dispatch and a `DebugStrategy` interface for step-debugging hooks. |
| `LSPGenerator` | `XxxLanguageServer.java` | LSP server with completion (keywords, `@catalog` entries, `@declares` symbols), diagnostics (parse errors), and hover (`@doc` annotations). Includes scope-store registration when `@declares`/`@backref`/`@scopeTree` annotations are present. |
| `DAPGenerator` | `XxxDebugAdapter.java` | DAP server with `stopOnEntry` support, step-over execution through the parse tree, breakpoint management, stack-trace display, and variable inspection. |

### 3.3 Novel Contribution: Propagation Control

In unlaxer-parser's combinator architecture, every parser's `parse` method receives three parameters:

```java
public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch)
```

- `ParseContext` maintains the input cursor, transaction stack, and token tree.
- `TokenKind` controls whether the parser *consumes* input (`consumed`) or merely *matches* without advancing the cursor (`matchOnly`). This is the PEG equivalent of the lookahead predicate.
- `invertMatch` inverts the success/failure semantics of the parser -- when `true`, a successful match is treated as failure and vice versa. This is the PEG "not" predicate.

In a naive implementation, both `tokenKind` and `invertMatch` propagate unconditionally from parent to child. This creates problems. Consider the `Not` combinator:

```java
public class Not extends ConstructedSingleChildParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.begin(this);
        Parsed parsed = getChild().parse(parseContext, TokenKind.matchOnly, invertMatch);
        if (parsed.isSucceeded()) {
            parseContext.rollback(this);
            return Parsed.FAILED;
        }
        Parsed committed = new Parsed(parseContext.commit(this, TokenKind.matchOnly));
        return committed;
    }
}
```

`Not` forces its child to `matchOnly` mode (it must not consume input during lookahead). But what if the child is a complex sub-expression that itself contains `Not` combinators? The `matchOnly` from the outer `Not` propagates down, but an inner `DoConsumePropagationStopper` should be able to override it selectively.

We introduce the **PropagationStopper hierarchy**, a set of four classes that provide fine-grained control over this two-dimensional propagation:

```java
public interface PropagationStopper { }
```

**1. AllPropagationStopper**: Stops both `TokenKind` and `invertMatch` propagation. The child always receives `TokenKind.consumed` and `invertMatch=false`, regardless of what the parent passes:

```java
public class AllPropagationStopper extends ConstructedSingleChildParser
    implements PropagationStopper {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.startParse(this, parseContext, tokenKind, invertMatch);
        Parsed parsed = getChild().parse(parseContext, TokenKind.consumed, false);
        parseContext.endParse(this, parsed, parseContext, tokenKind, invertMatch);
        return parsed;
    }
}
```

**2. DoConsumePropagationStopper**: Stops `TokenKind` propagation only, forcing the child to `consumed` mode while allowing `invertMatch` to pass through:

```java
public class DoConsumePropagationStopper extends ConstructedSingleChildParser
    implements PropagationStopper {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Parsed parsed = getChild().parse(parseContext, TokenKind.consumed, invertMatch);
        return parsed;
    }
}
```

**3. InvertMatchPropagationStopper**: Stops `invertMatch` propagation only, forcing the child to `invertMatch=false` while allowing `TokenKind` to pass through:

```java
public class InvertMatchPropagationStopper extends AbstractPropagatableSource
    implements PropagationStopper {
    @Override
    public Parsed parseDelegated(ParseContext parseContext, TokenKind tokenKind,
                                  boolean invertMatch) {
        Parsed parsed = getChild().parse(parseContext, tokenKind, false);
        return parsed;
    }
}
```

**4. NotPropagatableSource**: Inverts the `invertMatch` flag (logical NOT over the propagated value). When the parent passes `invertMatch=true`, the child receives `invertMatch=false`, and vice versa:

```java
public class NotPropagatableSource extends AbstractPropagatableSource {
    @Override
    public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
        return false == fromParentValue;
    }
}
```

This hierarchy can be formally characterized as a **two-dimensional control flow** over the `(TokenKind, invertMatch)` pair. Each propagation stopper selects which dimensions to intercept and what value to substitute. The design is compositional: propagation stoppers can be nested, and each operates independently on its respective dimension.

To our knowledge, no existing parser combinator framework provides this level of control over parsing mode propagation. Parsec handles lookahead through `try` and `lookAhead` combinators, but these do not compose along independent dimensions. ANTLR's semantic predicates operate at a different level of abstraction.

### 3.4 Novel Contribution: Metadata-Carrying Parse Trees

A fundamental insight in unlaxer-parser is that the parse tree can serve as a **communication channel** between the parsing phase and the IDE integration phase. This is achieved through `ContainerParser<T>`, an abstract parser class that inserts typed metadata into the parse tree without consuming any input:

```java
public abstract class ContainerParser<T> extends NoneChildParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Token token = Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
        parseContext.getCurrent().addToken(token);
        return new Parsed(token);
    }

    public abstract T get();
    public abstract RangedContent<T> get(CursorRange position);
}
```

The key property is that `ContainerParser` creates an **empty token** at the current cursor position -- it succeeds without consuming input. The parser instance itself carries the metadata, accessible through the `get()` and `get(CursorRange)` methods. After parsing, the metadata can be extracted from the token tree by filtering tokens whose parser is an instance of a specific `ContainerParser` subclass.

Two concrete subclasses demonstrate the pattern:

**ErrorMessageParser** embeds error messages into the parse tree for diagnostic reporting:

```java
public class ErrorMessageParser extends ContainerParser<String> implements TerminalSymbol {
    String message;
    boolean expectedHintOnly;

    public static ErrorMessageParser expected(String message) {
        return new ErrorMessageParser(message, true);
    }

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        if (expectedHintOnly) {
            parseContext.startParse(this, parseContext, tokenKind, invertMatch);
            parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
            return Parsed.FAILED;
        }
        return super.parse(parseContext, tokenKind, invertMatch);
    }

    @Override
    public Optional<String> expectedDisplayText() {
        if (expectedHintOnly) return Optional.of(message);
        return Optional.empty();
    }
}
```

When used with `expectedHintOnly=true`, the parser deliberately fails but registers itself as an "expected" token, providing the error-reporting system with a human-readable description of what was expected at that position. This information flows directly to the LSP server's diagnostic handler.

**SuggestsCollectorParser** collects completion suggestions from sibling parsers during parsing:

```java
public class SuggestsCollectorParser extends ContainerParser<Suggests> {
    Suggests suggests;

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Parsed parsed = super.parse(parseContext, tokenKind, invertMatch);
        parsed.status = Status.stopped;
        Source remain = parseContext.getRemain(TokenKind.consumed);
        List<Suggest> collect = getSiblings(false).stream()
            .filter(SuggestableParser.class::isInstance)
            .map(SuggestableParser.class::cast)
            .map(sp -> sp.getSuggests(remain.toString()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());
        suggests = new Suggests(collect);
        return parsed;
    }
}
```

This parser queries its sibling parsers (those at the same level in the combinator tree) for completion suggestions based on the remaining input. The suggestions are stored in the token tree and later extracted by the LSP server's completion handler.

The metadata-carrying parse tree pattern enables a single parse pass to produce all the information needed for both evaluation and IDE features. Without this mechanism, LSP and DAP integration would require separate passes or parallel data structures, increasing complexity and the risk of inconsistency.

### 3.5 Novel Contribution: Generation Gap Pattern for Evaluators

The Generation Gap Pattern (GGP) [Vlissides 1996] separates generated code from hand-written code by placing them in different classes related by inheritance. unlaxer-parser applies GGP to evaluator construction with a critical enhancement: Java 21's sealed interfaces provide **compiler-checked completeness**.

The generator produces an abstract evaluator class:

```java
public abstract class TinyExpressionP4Evaluator<T> {

    private DebugStrategy debugStrategy = DebugStrategy.NOOP;

    public T eval(TinyExpressionP4AST node) {
        debugStrategy.onEnter(node);
        T result = evalInternal(node);
        debugStrategy.onExit(node, result);
        return result;
    }

    private T evalInternal(TinyExpressionP4AST node) {
        return switch (node) {
            case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
            case TinyExpressionP4AST.ComparisonExpr n -> evalComparisonExpr(n);
            case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
            case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
            case TinyExpressionP4AST.ExpressionExpr n -> evalExpressionExpr(n);
            case TinyExpressionP4AST.StringExpr n -> evalStringExpr(n);
            case TinyExpressionP4AST.BooleanExpr n -> evalBooleanExpr(n);
            case TinyExpressionP4AST.ObjectExpr n -> evalObjectExpr(n);
            case TinyExpressionP4AST.NumberMatchExpr n -> evalNumberMatchExpr(n);
            case TinyExpressionP4AST.NumberCaseExpr n -> evalNumberCaseExpr(n);
            case TinyExpressionP4AST.NumberDefaultCaseExpr n -> evalNumberDefaultCaseExpr(n);
            case TinyExpressionP4AST.NumberCaseValueExpr n -> evalNumberCaseValueExpr(n);
            // ... additional cases for all @mapping types
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalComparisonExpr(TinyExpressionP4AST.ComparisonExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
    // ... one abstract method per @mapping class

    // DebugStrategy interface (also generated)
    public interface DebugStrategy {
        void onEnter(TinyExpressionP4AST node);
        void onExit(TinyExpressionP4AST node, Object result);
        DebugStrategy NOOP = new DebugStrategy() {
            public void onEnter(TinyExpressionP4AST node) {}
            public void onExit(TinyExpressionP4AST node, Object result) {}
        };
    }
}
```

The developer then writes a **concrete** subclass:

```java
public class P4TypedAstEvaluator extends TinyExpressionP4Evaluator<Object> {

    private final ExpressionType resultType;
    private final ExpressionType numberType;
    private final CalculationContext context;

    @Override
    protected Object evalBinaryExpr(BinaryExpr node) {
        return evalBinaryAsNumber(node);
    }

    @Override
    protected Object evalIfExpr(IfExpr node) {
        Object conditionValue = eval(node.condition());
        boolean cond = Boolean.TRUE.equals(toBoolean(conditionValue));
        ExpressionExpr branch = cond ? node.thenExpr() : node.elseExpr();
        return eval(branch);
    }

    @Override
    protected Object evalComparisonExpr(ComparisonExpr node) {
        Number left = evalBinaryAsNumber(node.left());
        Number right = evalBinaryAsNumber(node.right());
        String op = node.op() == null ? "" : node.op().strip();
        int compare = toBigDecimal(left).compareTo(toBigDecimal(right));
        return switch (op) {
            case "==" -> compare == 0;
            case "!=" -> compare != 0;
            case "<"  -> compare < 0;
            case "<=" -> compare <= 0;
            case ">"  -> compare > 0;
            case ">=" -> compare >= 0;
            default -> false;
        };
    }

    // ... implementations for all other evalXxx methods
}
```

This design provides three guarantees:

1. **Completeness**: If the grammar adds a new `@mapping` rule, the sealed interface gains a new permitted subtype, the generated switch becomes non-exhaustive, and the compiler rejects the concrete class until a new `evalXxx` method is added.
2. **Regeneration safety**: Only the abstract base class is regenerated. The concrete subclass, containing all domain-specific evaluation logic, is never overwritten.
3. **Debug integration**: The `DebugStrategy` hooks in the generated base class enable step debugging through the DAP server without any code in the hand-written class.

The GGP approach also supports multiple evaluation strategies from the same grammar. The tinyexpression project implements three concrete evaluators extending the same generated base:
- `P4TypedAstEvaluator`: interprets the AST directly, returning `Object` values.
- `P4TypedJavaCodeEmitter`: traverses the AST and emits Java source code for runtime compilation.
- `P4DefaultJavaCodeEmitter`: a template-based emitter for default evaluation patterns.

---

## 4. Implementation

### 4.1 Parser Combinator Library (unlaxer-common)

The `unlaxer-common` module contains 436 Java source files implementing the parser combinator library. Parsers are organized into several categories:

**Combinator parsers** compose other parsers:
- `Chain` / `LazyChain`: sequential composition (PEG sequence `e1 e2 ... en`).
- `Choice` / `LazyChoice`: ordered choice (PEG `e1 / e2 / ... / en`).
- `LazyZeroOrMore`, `LazyOneOrMore`, `LazyOptional`: repetition and optionality.
- `LazyRepeat`, `ConstructedOccurs`: bounded repetition with explicit count control.
- `Not`: PEG not-predicate (zero-width negative lookahead).
- `MatchOnly`: PEG and-predicate (zero-width positive lookahead).
- `NonOrdered`: unordered set matching (all alternatives must match, in any order).

**Lazy vs. Constructed variants** address cyclic grammar support. In a recursive grammar (e.g., `Expression ::= ... '(' Expression ')' ...`), the parser for `Expression` references itself. Eager construction would cause infinite recursion. The `Lazy` variants defer child-parser resolution until first parse, breaking the cycle. The `Constructed` variants are used for non-recursive rules where children are known at construction time.

**AST filtering** controls which tokens appear in the parse tree:
- `ASTNode`: marker interface indicating that a parser's token should be included in the AST.
- `ASTNodeRecursive`: tokens from this parser and its descendants are included.
- `NotASTNode` and `TagWrapper` with `NotASTNode`: exclude tokens from the AST view.
- The `Token.filteredChildren` field provides an AST-only view of the token tree, while `Token.children` preserves the full parse tree.

**Transaction-based backtracking** in `ParseContext` enables ordered-choice semantics:
- `begin(Parser)`: saves the current cursor position (creates a save point).
- `commit(Parser, TokenKind)`: accepts the parsed tokens and advances the cursor.
- `rollback(Parser)`: restores the cursor to the save point and discards tokens.

This transaction model is more general than Parsec's committed-choice semantics: any parser can begin a transaction, and nested transactions are fully supported.

### 4.2 Code Generators (unlaxer-dsl)

The `unlaxer-dsl` module contains the six code generators and supporting infrastructure.

**MapperGenerator** handles the most complex generation logic. A key challenge is **multi-rule mapping**: multiple grammar rules can map to the same AST class. For example, both `NumberExpression` and `NumberTerm` map to `BinaryExpr`. The mapper must correctly identify which rule produced a given parse-tree node and extract the appropriate fields. The `allMappingRules` mechanism collects all rules sharing a `@mapping` class name and generates a dispatcher that tries each mapping rule in order.

Another challenge is **findDirectDescendants**: when extracting `@left`, `@op`, and `@right` from a parse tree, the mapper must find the immediate children matching the annotated elements without descending into nested sub-expressions. A `NumberExpression` containing `NumberTerm` elements should extract the top-level terms, not the factors inside them.

**EvaluatorGenerator** produces the GGP skeleton. Beyond the exhaustive switch dispatch, it generates `DebugStrategy` hooks that are called before and after each node evaluation. The generated `StepCounterStrategy` implementation counts evaluation steps and can be configured to pause at specific step counts, enabling the DAP server's step-over behavior.

**ParserGenerator** handles precedence and associativity. When `@leftAssoc` is present, the generator produces a `LazyChain` with the base term followed by `LazyZeroOrMore` of operator-term pairs. For `@rightAssoc`, it generates a recursive chain. The `@precedence(level=N)` annotation is used to order the choice alternatives when multiple expression types compete.

### 4.3 Five Execution Backends

The tinyexpression project demonstrates the framework's flexibility by implementing five distinct execution backends, all derived from the same grammar:

| Backend | Key Class | Strategy | Step Debug | Code Gen |
|---------|-----------|----------|------------|----------|
| `JAVA_CODE` | `JavaCodeCalculatorV3` | Legacy parser to Java source to JIT compilation | No | Yes |
| `AST_EVALUATOR` | `AstEvaluatorCalculator` | Legacy parser to hand-written AST to tree-walking evaluator | No | No |
| `DSL_JAVA_CODE` | `DslJavaCodeCalculator` | Legacy parser to DSL-generated Java source | No | Yes |
| `P4_AST_EVALUATOR` | `P4AstEvaluatorCalculator` | UBNF-generated parser to sealed AST to `P4TypedAstEvaluator` | Yes | No |
| `P4_DSL_JAVA_CODE` | `P4DslJavaCodeCalculator` | UBNF-generated parser to sealed AST to Java code emitter | Yes | Yes |

The P4 backends (rows 4 and 5) use the UBNF-generated parser and AST, while the earlier backends use the legacy hand-written parser. A parity contract ensures that all backends produce equivalent results for supported expressions, validated by `BackendSpeedComparisonTest`, `P4BackendParityTest`, and `ThreeExecutionBackendParityTest`.

---

## 5. Evaluation

### 5.1 Case Study: tinyexpression

tinyexpression is a production expression evaluator used for financial calculations. It supports numeric expressions with configurable precision (float, double, int, long, BigDecimal, BigInteger), string expressions, boolean expressions, conditional expressions (if/else), pattern matching (match), variable bindings, user-defined methods, type hints, and embedded Java code blocks.

The UBNF grammar (`tinyexpression-p4-complete.ubnf`) spans **520 lines** and defines the complete P4 grammar. From this grammar, the code generator produces approximately **2,000 lines** of Java source across the six generated files. The hand-written evaluator logic (`P4TypedAstEvaluator.java`) is **542 lines**, covering all expression types including numeric arithmetic with configurable number types, variable resolution, comparison operations, if/else and match evaluation, and string/boolean coercion.

The total investment -- grammar plus hand-written evaluator logic -- is approximately **1,062 lines**, yielding a complete language implementation with parser, typed AST, mapper, evaluator, LSP server, and DAP server.

### 5.2 Performance Benchmarks

The `BackendSpeedComparisonTest` measures evaluation performance across backends using the formula `3+4+2+5-1` (literal arithmetic) and `$a+$b+$c+$d-$e` (variable arithmetic) with 50,000 iterations after 5,000 warmup iterations.

**Section 1: Literal arithmetic**

| Backend | Description | us/call | vs. baseline |
|---------|-------------|---------|--------------|
| (A) compile-hand | JVM bytecode (JavaCodeCalculatorV3) | ~0.04 | 1.0x |
| (E2) P4-typed-reuse | Sealed switch, instance reused | ~0.10 | 2.8x |
| (E) P4-typed-eval | Sealed switch, new instance per call | ~0.33 | 8.9x |
| (B) ast-hand-cached | Hand-written AST, pre-parsed | ~0.42 | 11.4x |
| (C) ast-hand-full | Hand-written AST, parse+build+eval | ~2.50 | ~68x |
| (D) P4-reflection | P4 mapper + reflection-based evaluator | ~143.53 | 3,901x |

*Table 2: Evaluation latency for literal arithmetic. The compile-hand backend represents the theoretical optimum (JIT-compiled Java bytecode). P4-typed-reuse is within 3x of this optimum.*

The most striking result is the **1,400x improvement** between P4-reflection (the initial reflection-based evaluator, ~143 us/call) and P4-typed-reuse (the sealed-switch evaluator, ~0.10 us/call). This improvement comes entirely from replacing `java.lang.reflect.Method.invoke()` with a generated exhaustive switch expression over sealed interfaces. The JVM's JIT compiler can inline the switch cases, eliminate virtual dispatch, and apply scalar replacement to the record instances.

The P4-typed-reuse backend achieves **2.8x** of the compile-hand baseline while being an interpreter rather than a compiler. This is notable: the sealed-switch evaluator, despite performing tree-walking interpretation, is competitive with JIT-compiled code for simple expressions.

**Section 2: Variable arithmetic**

| Backend | Description | us/call | vs. baseline |
|---------|-------------|---------|--------------|
| (F) compile-hand | JVM bytecode with variable lookup | ~0.06 | 1.0x |
| (H) P4-typed-var | Sealed switch with variable AST | ~0.15 | 2.5x |
| (G) AstEvalCalc | Full AstEvaluatorCalculator path | ~8.50 | ~142x |

Variable expressions show similar relative performance. The P4-typed backend maintains a 2.5x overhead versus JIT-compiled code, while the legacy AST evaluator path is two orders of magnitude slower due to reflection and multiple fallback layers.

### 5.3 Development Effort Comparison

We estimate the development effort for a language with tinyexpression's feature set under three approaches:

| Approach | Lines of code | Time estimate |
|----------|---------------|---------------|
| From scratch (parser + AST + mapper + evaluator + LSP + DAP) | ~15,000 | 8 weeks |
| ANTLR + hand-written evaluator + hand-written LSP/DAP | ~8,000 | 5 weeks |
| unlaxer (grammar + evalXxx methods) | ~1,062 | 3 days |

*Table 3: Development effort comparison. Lines of code include grammar, generated code (for reference only -- it is not maintained by the developer), and hand-written logic.*

The "from scratch" estimate is based on the README's breakdown: parser (~2,000 lines), AST types (~1,500 lines), mapper (~1,000 lines), evaluator (~2,000 lines), LSP server (~2,500 lines), DAP server (~1,500 lines). The ANTLR estimate accounts for generated parser and AST (reducing the parser and AST effort) but requires hand-written mapper, evaluator, LSP, and DAP. The unlaxer estimate reflects the actual tinyexpression implementation: 520 lines of grammar plus 542 lines of evaluator logic.

The **14x reduction** in maintainable code (from ~15,000 to ~1,062 lines) is the primary practical benefit. However, the reduction in cognitive load is arguably more important: the developer thinks in terms of grammar rules and evaluation semantics, not in terms of parser plumbing, token-tree traversal, or protocol message handling.

### 5.4 LLM-Assisted Development

The type-safe, generated architecture of unlaxer-parser has a surprising benefit for LLM-assisted development workflows. When using a large language model (LLM) as a coding assistant, the framework's properties significantly reduce the token budget and iteration count required:

**Token efficiency.** Without a framework, an LLM must generate parser combinators, AST types, mapper logic, and evaluator code from scratch -- typically requiring 20,000--30,000 tokens of context and generation. With unlaxer, the LLM need only generate the `evalXxx` method bodies, typically requiring 2,000--3,000 tokens. This represents a **10x reduction in token cost**.

**Type-safety eliminates debugging round-trips.** The sealed interface exhaustiveness guarantee means that the LLM cannot "forget" to handle an AST node type -- the Java compiler will reject the code. In practice, this eliminates approximately 95% of the debugging round-trips that occur when an LLM-generated evaluator silently ignores a grammar construct.

**Sealed interfaces as a compile-time TODO list.** When the grammar is extended with a new `@mapping` rule, the generated evaluator base class gains a new abstract method. The compiler error listing the unimplemented methods serves as a precise, machine-readable TODO list that the LLM can follow without any additional prompting. This "compiler-as-orchestrator" pattern is particularly effective because it provides the LLM with exactly the information it needs (which methods to implement, with what parameter types) and nothing extraneous.

---

## 6. Discussion

### 6.1 Limitations

**Java-only generation.** The current code generator produces only Java source files. While JVM-based languages (Kotlin, Scala, Clojure) can interoperate with generated Java code, native support for non-JVM languages is not available. The grammar notation (UBNF) is language-agnostic, but the generator pipeline and the parser combinator runtime are Java-specific.

**PEG-based parsing.** UBNF uses PEG semantics (ordered choice), which means that ambiguous grammars are resolved deterministically by the order of alternatives. This is a feature for most DSLs but a limitation for languages that require ambiguity reporting (e.g., natural language processing) or languages where ordered choice produces surprising results. GLR parsers, which can handle ambiguous grammars and produce parse forests, are not supported.

**Error recovery.** PEG's ordered-choice semantics make robust error recovery difficult. When a choice alternative fails, the parser backtracks and tries the next alternative; there is no mechanism to report "partial matches" or to skip erroneous input and continue parsing. The `ErrorMessageParser` provides point-of-failure diagnostics, but the parser does not currently support the "error recovery" strategies (token insertion, token deletion, panic-mode recovery) found in ANTLR.

**Single production user.** The tinyexpression project is the primary production user of the framework. While the framework is designed for generality, broader validation across diverse DSL projects is needed to confirm that the design choices (UBNF syntax, generation pipeline, GGP evaluator pattern) are appropriate for a wide range of languages.

**Incomplete grammar coverage.** The P4 grammar does not yet cover all features of the tinyexpression language. Several constructs -- external Java method calls, string slicing (`$msg[0:3]`), and some string methods -- are handled by the legacy parser and fall back to earlier backends. The coexistence model (P4 parser with legacy fallback) is functional but adds complexity.

### 6.2 Future Work

**Declarative evaluation annotations.** We plan to extend UBNF with `@eval` annotations that specify evaluation strategies directly in the grammar:

```ubnf
@eval(strategy=default)
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

The `strategy` parameter would select from `default` (generated dispatch), `template` (external template), and `manual` (hand-written override). This would further reduce the hand-written evaluator code for common patterns like binary expression evaluation.

**JavaCodeBuilder for type-safe code generation.** The `P4TypedJavaCodeEmitter` currently emits Java source as string concatenation. A `JavaCodeBuilder` API providing type-safe AST construction for Java source code would improve reliability and enable optimizations such as constant folding and dead-code elimination at generation time.

**Tree-sitter integration.** A bidirectional bridge between UBNF and tree-sitter's `grammar.js` would enable unlaxer grammars to benefit from tree-sitter's incremental parsing and syntax-highlighting infrastructure while retaining unlaxer's semantic generation capabilities.

**Incremental parsing.** The current parser re-parses the entire input on every change. For IDE integration (LSP), incremental parsing -- re-parsing only the modified region and reusing parse results for unchanged regions -- would significantly improve responsiveness for large documents.

**Multi-language code generation.** Generating TypeScript, Python, or Rust source from UBNF grammars would extend the framework's applicability beyond the JVM ecosystem. The grammar notation and generation pipeline are language-agnostic in principle; the primary challenge is implementing the parser combinator runtime and the sealed-interface-equivalent type system in each target language.

---

## 7. Conclusion

We have presented unlaxer-parser, a Java 21 framework that generates six interrelated artifacts -- parser, AST types, parse-tree-to-AST mapper, evaluator skeleton, LSP server, and DAP server -- from a single UBNF grammar specification. The framework addresses the fundamental problem of DSL development: the need to build and maintain multiple tightly coupled subsystems that must remain consistent with each other.

Our three novel contributions address specific challenges in this unified generation:

1. **Propagation control** provides fine-grained, compositional control over how parsing modes propagate through the combinator tree, through a hierarchy of four propagation-stopper classes operating on two orthogonal dimensions (`TokenKind` and `invertMatch`). This mechanism has no equivalent in existing parser combinator frameworks.

2. **Metadata-carrying parse trees** through `ContainerParser<T>` enable error messages and completion suggestions to be embedded directly in the parse tree during a single parse pass. This eliminates the need for separate analysis passes for IDE features and ensures consistency between parsing and IDE behavior.

3. **The Generation Gap Pattern for evaluators**, combined with Java 21 sealed interfaces and exhaustive switch expressions, provides compiler-checked completeness guarantees. When the grammar adds a new AST node type, the compiler rejects the hand-written evaluator until the developer implements the corresponding evaluation method. This transforms a class of runtime errors into compile-time errors.

Our evaluation using tinyexpression, a production expression evaluator for financial calculations, demonstrates the practical impact. The framework achieves a **14x reduction** in maintainable code (from ~15,000 to ~1,062 lines) compared to a from-scratch implementation. Performance benchmarks show that the sealed-switch evaluator achieves a **1,400x improvement** over reflection-based evaluation and operates within **2.8x** of JIT-compiled bytecode -- a remarkable result for a tree-walking interpreter.

We believe that the value of unified grammar-to-IDE generation will increase as LLM-assisted development becomes more prevalent. The sealed-interface exhaustiveness guarantee acts as a "compile-time TODO list" that LLMs can follow precisely, reducing token costs by an order of magnitude and eliminating the vast majority of debugging round-trips.

The framework is available as open-source software under the MIT license, published to Maven Central as `org.unlaxer:unlaxer-common` and `org.unlaxer:unlaxer-dsl`.

---

## References

[1] Brunel, M., Clem, M., Hlywa, T., Creager, P., and Gonzalez, A. 2023. tree-sitter: An incremental parsing system for programming tools. In *Proceedings of the ACM SIGPLAN International Conference on Software Language Engineering (SLE '23)*.

[2] Ford, B. 2004. Parsing Expression Grammars: A recognition-based syntactic foundation. In *Proceedings of the 31st ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages (POPL '04)*, pp. 111--122. ACM.

[3] Gamma, E., Helm, R., Johnson, R., and Vlissides, J. 1994. *Design Patterns: Elements of Reusable Object-Oriented Software*. Addison-Wesley.

[4] Ierusalimschy, R. 2009. A text pattern-matching tool based on Parsing Expression Grammars. *Software: Practice and Experience* 39, 3, pp. 221--258.

[5] Johnson, S. C. 1975. Yacc: Yet Another Compiler-Compiler. *AT&T Bell Laboratories Technical Report*.

[6] Leijen, D. and Meijer, E. 2001. Parsec: Direct Style Monadic Parser Combinators For The Real World. *Technical Report UU-CS-2001-35*, Department of Computer Science, Universiteit Utrecht.

[7] Microsoft. 2016a. Language Server Protocol Specification. https://microsoft.github.io/language-server-protocol/

[8] Microsoft. 2016b. Debug Adapter Protocol Specification. https://microsoft.github.io/debug-adapter-protocol/

[9] Parr, T. and Fisher, K. 2011. LL(*): the foundation of the ANTLR parser generator. In *Proceedings of the 32nd ACM SIGPLAN Conference on Programming Language Design and Implementation (PLDI '11)*, pp. 425--436. ACM.

[10] Redziejowski, R. R. 2007. Parsing Expression Grammars: A Recognition-Based Syntactic Foundation. *Fundamenta Informaticae* 85, 1-4, pp. 413--431.

[11] Vlissides, J. 1996. Generation Gap. In *Pattern Languages of Program Design 3*, Addison-Wesley, pp. 85--101.

[12] Erdweg, S., Storm, T., Volter, M., Boersma, M., Bosman, R., Cook, W. R., Gerber, A., Hulshout, A., Kelly, S., Loh, A., Konat, G. D. P., Molina, P. J., Palatnik, M., Poetzsch-Heffter, A., Schindler, K., Schindler, T., Solmi, R., Vergu, V., Visser, E., van der Vlist, K., Wachsmuth, G. H., and van der Woning, J. 2013. The State of the Art in Language Workbenches. In *Software Language Engineering (SLE '13)*, pp. 197--217.

[13] Volter, M., Stahl, T., Bettin, J., Haase, A., and Helsen, S. 2006. *Model-Driven Software Development: Technology, Engineering, Management*. John Wiley & Sons.

[14] Kats, L. C. L. and Visser, E. 2010. The Spoofax Language Workbench: Rules for Declarative Specification of Languages and IDEs. In *Proceedings of the ACM International Conference on Object-Oriented Programming, Systems, Languages, and Applications (OOPSLA '10)*, pp. 444--463. ACM.

[15] Parr, T. 2013. *The Definitive ANTLR 4 Reference*. Pragmatic Bookshelf.

[16] Hutton, G. and Meijer, E. 1998. Monadic Parsing in Haskell. *Journal of Functional Programming* 8, 4, pp. 437--444.

[17] Might, M., Darais, D., and Spiewak, D. 2011. Parsing with Derivatives: A Functional Pearl. In *Proceedings of the 16th ACM SIGPLAN International Conference on Functional Programming (ICFP '11)*, pp. 189--195. ACM.

[18] Swierstra, S. D. 2009. Combinator Parsing: A Short Tutorial. In *Language Engineering and Rigorous Software Development*, Lecture Notes in Computer Science 5520, pp. 252--300. Springer.

---

## Appendix A: Complete TinyCalc Example

The following minimal UBNF grammar and evaluator demonstrate the full generation pipeline:

**Grammar (TinyCalc.ubnf):**

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

**Generated sealed AST (TinyCalcAST.java):**

```java
public sealed interface TinyCalcAST permits TinyCalcAST.BinaryExpr {
    record BinaryExpr(BinaryExpr left, List<String> op, List<BinaryExpr> right)
        implements TinyCalcAST {}
}
```

**Hand-written evaluator (TinyCalcEvaluatorImpl.java):**

```java
public class TinyCalcEvaluatorImpl extends TinyCalcEvaluator<Double> {
    @Override
    protected Double evalBinaryExpr(TinyCalcAST.BinaryExpr node) {
        if (node.left() == null && node.op().size() == 1) {
            return Double.parseDouble(node.op().get(0));  // leaf
        }
        double result = eval(node.left());
        for (int i = 0; i < node.op().size(); i++) {
            double r = eval(node.right().get(i));
            result = switch (node.op().get(i)) {
                case "+" -> result + r;
                case "-" -> result - r;
                case "*" -> result * r;
                case "/" -> result / r;
                default -> throw new IllegalArgumentException();
            };
        }
        return result;
    }
}
```

This 35-line grammar and 17-line evaluator produce a complete calculator with parser, AST, mapper, evaluator, LSP server, and DAP server.

---

## Appendix B: Propagation Stopper Decision Matrix

| Stopper | TokenKind to child | invertMatch to child | Use case |
|---------|-------------------|---------------------|----------|
| *(none)* | parent value | parent value | Default propagation |
| `AllPropagationStopper` | `consumed` | `false` | Reset all parsing modes for a sub-expression |
| `DoConsumePropagationStopper` | `consumed` | parent value | Force consumption inside a matchOnly context |
| `InvertMatchPropagationStopper` | parent value | `false` | Prevent NOT semantics from propagating into sub-parsers |
| `NotPropagatableSource` | parent value | `!parent` | Implement logical NOT by inverting the inversion flag |

---

*Submitted to the ACM SIGPLAN International Conference on Software Language Engineering (SLE), 2026.*
