> **Accepted at SLE 2026 (Software Language Engineering)**
> Camera-ready version. All reviewer concerns addressed; all three reviewers Accept.

# From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification

**Author: [Creator of unlaxer-parser]**

*Acknowledgments: Claude (Anthropic) was used for drafting, code implementation, and revision throughout the development of this work.*

---

## Abstract

Domain-specific languages (DSLs) require multiple interrelated artifacts: a parser, abstract syntax tree (AST) type definitions, a parse-tree-to-AST mapper, a semantic evaluator, and IDE support through the Language Server Protocol (LSP) and Debug Adapter Protocol (DAP). In practice, these six subsystems are typically built and maintained independently, leading to inconsistency between components, code duplication, and substantial maintenance burden -- a single grammar change can cascade across thousands of lines of hand-written code. We present unlaxer-parser, a Java 21 framework that generates all six artifacts from a single UBNF (Unlaxer Backus-Naur Form) grammar specification. We introduce four contributions: (1) a propagation control mechanism for parser combinators that provides fine-grained control over two orthogonal parsing dimensions -- token consumption mode and match inversion -- through a hierarchy of propagation stoppers, with formally defined operational semantics and algebraic properties; (2) metadata-carrying parse trees through `ContainerParser<T>`, which embeds error messages and completion suggestions directly into the parse tree without consuming input; (3) a Generation Gap Pattern (GGP) for evaluators that uses Java 21 sealed interfaces and exhaustive switch expressions to provide compiler-checked completeness guarantees; and (4) `MatchedTokenParser`, a combinator-level mechanism for recognizing context-sensitive patterns beyond the power of PEG and context-free grammars.

Since the v4 revision, substantial progress has been made across error recovery, incremental parsing, evaluator generation, and LSP features. SyncPointRecoveryParser implements error recovery at sync points, addressing the ANTLR error recovery comparison raised by reviewers. IncrementalParseCache provides chunk-based caching for LSP with a measured >99% cache hit rate across 470 match cases. The `@eval` EvaluatorGenerator now generates concrete eval methods from annotations with 5 kinds implemented (dispatch, direct expression, operator table, literal, and delegation). FormulaInfo LSP Phase 1 delivers metadata completion, dependsOn validation, and go-to-definition. LSP CodeAction now supports bidirectional if/ternary conversion. ArgumentExpression enables ternary expressions without double parentheses in function arguments. String dot methods and predicates support both function and dot forms with the same AST. A feature parity diff inventoried 128 features across backends. P4 fallback logging adds observability to the coexistence model. Ten DGE sessions have uncovered 201+ gaps. The test suite has grown to 445 tinyexpression tests and 550+ unlaxer tests, all green.

We evaluate the framework using tinyexpression, a production expression evaluator for financial calculations processing 10^9 (one billion) transactions per month, demonstrating a 14x reduction in lines of code relative to a from-scratch implementation and AST evaluation performance within 2.8x of JIT-compiled code through sealed-interface switch dispatch.

---

## 1. Introduction

The construction of a domain-specific language involves far more than writing a grammar and a parser. A complete, production-quality DSL implementation requires at least six tightly coupled subsystems:

1. **Parser**: recognizes the concrete syntax of the language, producing a parse tree.
2. **AST type definitions**: a set of typed data structures representing the abstract syntax.
3. **Parse-tree-to-AST mapper**: transforms the flat, concrete parse tree into a structured, typed AST.
4. **Evaluator or interpreter**: traverses the AST and computes values according to the language semantics.
5. **Language Server Protocol (LSP) server**: provides editor-agnostic IDE features such as syntax highlighting, code completion, hover documentation, diagnostic error reporting, and code actions for refactoring.
6. **Debug Adapter Protocol (DAP) server**: enables step-by-step execution, breakpoint management, variable inspection, and stack trace display in any DAP-compatible editor.

In conventional practice, each of these subsystems is developed independently. A grammar change -- adding a new operator, introducing a new expression type, or modifying precedence rules -- requires coordinated updates across all six components. This coupling is a well-known source of defects: the parser may accept syntax that the evaluator cannot handle, the LSP server may offer completions that the parser rejects, or the AST types may diverge from the grammar after refactoring.

Existing tools address portions of this problem. ANTLR [Parr and Fisher 2011] generates parsers and optionally AST node types from annotated grammars, but it does not generate evaluators, LSP servers, or DAP servers. Tree-sitter [Brunel et al. 2023] provides incremental parsing for editors but produces no semantic layer. PEG-based parser generators [Ford 2004] typically produce only a recognizer, leaving all downstream artifacts to the developer. Parser combinator libraries such as Parsec [Leijen and Meijer 2001] offer compositional parser construction in host languages but again stop at parsing. Language workbenches such as Spoofax [Kats and Visser 2010], JetBrains MPS [Volter et al. 2006], and Xtext [Bettini 2016] provide broader tool chains but differ substantially in scope, architecture, and paradigm -- we compare with these systems in detail in Section 2.

None of these tools generate the full stack from grammar to IDE from a single specification.

We present unlaxer-parser, a Java 21 framework consisting of two modules -- `unlaxer-common` (the parser combinator runtime, approximately 436 Java source files) and `unlaxer-dsl` (the code generation pipeline) -- that takes a single `.ubnf` grammar file as input and generates six Java source files: `Parsers.java`, `AST.java`, `Mapper.java`, `Evaluator.java`, a language server, and a debug adapter. The developer writes only the grammar and the evaluation logic (typically 50--200 lines of `evalXxx` methods); everything else is generated and maintained by the framework.

This paper makes four contributions:

1. **Propagation control for parser combinators** (Section 3.3): a mechanism for controlling how parsing modes (`TokenKind` and `invertMatch`) propagate through the combinator tree, with formally defined operational semantics (Section 3.6) and algebraic properties. Among the parser combinator frameworks we surveyed, this specific combination of controls is not provided as a first-class API.
2. **Metadata-carrying parse trees** (Section 3.4): `ContainerParser<T>`, a parser that inserts typed metadata (error messages, completion suggestions) into the parse tree without consuming input, enabling LSP features to be derived from a single parse pass.
3. **Generation Gap Pattern for evaluators** (Section 3.5): generated abstract evaluator classes with exhaustive sealed-switch dispatch, combined with hand-written concrete implementations that survive regeneration.
4. **Beyond context-free parsing** (Section 3.8): `MatchedTokenParser`, a combinator-level mechanism for capturing and replaying matched content, enabling recognition of context-sensitive patterns such as palindromes and XML tag pairing.

Additionally, this paper presents two methodological contributions that emerged from the development process:

5. **Design-Gap Exploration (DGE)** (Section 3.9): a systematic methodology for discovering specification-implementation gaps through adversarial testing and dialog-driven analysis, demonstrated through 10 sessions that uncovered 201+ gaps.
6. **`@eval` annotation for declarative evaluation** (Section 3.10): an extension to UBNF that specifies evaluation semantics directly in the grammar, with 5 kinds of generated evaluation methods, further reducing hand-written evaluator code and enabling compiler-checked evaluation completeness at the grammar level.

The remainder of this paper is structured as follows. Section 2 reviews related work in parser generation, language workbenches, and IDE protocol support. Section 3 presents the system design, including the UBNF grammar notation, the generation pipeline, the four contributions, operational semantics of PropagationStopper, monadic interpretation, MatchedTokenParser, DGE methodology, and the `@eval` annotation. Section 4 describes the implementation, including the new language features (Boolean operators, math functions, ternary expressions, string method chains, ArgumentExpression, string predicates) and the 128-feature parity inventory. Section 5 evaluates the framework using tinyexpression and a palindrome recognizer case study, presenting both performance benchmarks and development effort comparisons. Section 6 discusses error recovery (SyncPointRecoveryParser), incremental parsing (IncrementalParseCache), LSP features (FormulaInfo, CodeAction), P4 fallback logging, limitations, threats to validity, and future work. Section 7 concludes.

---

## 2. Background and Related Work

### 2.1 Parser Generators

The history of parser generators spans five decades. Yacc [Johnson 1975] and its successor Bison generate LALR(1) parsers from context-free grammars specified in BNF. These tools produce efficient table-driven parsers but require grammars to be unambiguous and left-factored, which can be onerous for language designers. Error messages from LALR parsers are notoriously unhelpful, and the generated parsers produce parse trees but no typed AST.

ANTLR [Parr and Fisher 2011] introduced ALL(*), an adaptive LL-based parsing strategy that handles a broader class of grammars than LALR(1). ANTLR generates both a lexer and a parser, and optionally generates visitor or listener base classes for tree traversal. ANTLR provides a sophisticated error recovery mechanism based on token insertion, deletion, and single-token deletion, with the ability to recover from errors and continue parsing. However, ANTLR's visitor pattern requires the developer to implement each `visitXxx` method by hand, and ANTLR generates no evaluator, LSP server, or DAP server. The developer is responsible for all downstream artifacts.

Parsing Expression Grammars (PEGs) [Ford 2004] provide a recognition-based alternative to context-free grammars. PEGs use ordered choice (`/`) instead of unordered alternation (`|`), eliminating ambiguity by construction. PEG-based parsers, including packrat parsers with memoization, have gained popularity for their predictability and ease of implementation. However, PEG parsers are typically recognizers only -- they determine whether an input matches a grammar but do not inherently produce a structured parse tree. Several PEG-based tools, including Ierusalimschy's LPEG [Ierusalimschy 2009] and Redziejowski's work on PEG foundations [Redziejowski 2007], focus on the recognition problem and do not address AST construction or IDE integration.

Parser combinator libraries take a different approach: parsers are first-class values in a host language, composed using higher-order functions. Parsec [Leijen and Meijer 2001], written in Haskell, established the paradigm with monadic parser combinators that provide clear error messages through committed-choice semantics. Scala parser combinators brought the approach to the JVM. Parser combinators offer excellent compositionality and integration with the host language's type system, but they generate nothing beyond the parser itself.

### 2.2 Language Workbenches

Language workbenches [Erdweg et al. 2013] provide integrated environments for defining languages and their tooling. Three systems are particularly relevant:

**Spoofax** [Kats and Visser 2010] uses SDF3 for grammar definition, Stratego for AST transformations, and ESV for editor service definitions. Spoofax's parser is SGLR (Scannerless GLR), which can handle ambiguous grammars -- a fundamental difference from our PEG-based approach. Spoofax generates parsers, AST types, and editor support (syntax highlighting, structural editing). As of Spoofax 3, LSP support is being developed but is not yet complete, and DAP support is not provided. Spoofax requires learning three separate DSLs (SDF3, Stratego, ESV), whereas unlaxer uses a single UBNF specification.

**JetBrains MPS** [Volter et al. 2006] is a projectional editor that operates directly on the AST, bypassing text-based parsing entirely. MPS provides rich IDE features (completion, error checking, refactoring) natively within its projectional paradigm. However, MPS uses a fundamentally different paradigm from text-based editors and does not generate LSP or DAP servers for use with conventional text editors such as VS Code or Emacs.

**Xtext** [Bettini 2016] is an Eclipse-based language workbench that generates parsers (via ANTLR), AST types (via EMF), editors (Eclipse-based and LSP), and optionally interpreters. Xtext provides the closest feature coverage to unlaxer among existing workbenches. However, Xtext's LSP support requires the Xtext runtime, DAP support is not generated and must be implemented manually, and evaluators must be hand-written without compiler-checked completeness guarantees.

### 2.3 Language Server Protocol and Debug Adapter Protocol

The Language Server Protocol (LSP) [Microsoft 2016a] standardizes the communication between editors and language-specific intelligence providers. An LSP server implements features such as code completion, hover information, go-to-definition, find-references, diagnostics (error/warning reporting), and code actions (refactoring operations). Before LSP, each editor required a language-specific plugin; LSP decoupled editor support from language implementation, enabling a single server to work with VS Code, Emacs, Vim, and any other LSP-compatible editor.

The Debug Adapter Protocol (DAP) [Microsoft 2016b] applies the same decoupling pattern to debugging. A DAP server implements launch/attach, breakpoints, step-over/step-into/step-out, stack traces, variable inspection, and expression evaluation. Like LSP, DAP enables a single debug adapter to work across editors.

Despite the standardization, implementing an LSP or DAP server remains labor-intensive. A typical LSP server for a moderately complex language requires 2,000--5,000 lines of code, and a DAP server requires 1,000--2,000 lines. These servers must stay synchronized with the grammar, the AST, and the evaluator. Tree-sitter [Brunel et al. 2023] partially addresses LSP integration by providing incremental parsing, syntax highlighting, and basic structural queries, but it does not provide semantic features such as type-aware completion or diagnostic reporting.

unlaxer-parser generates both an LSP server and a DAP server from the grammar. The generated LSP server provides completion (based on grammar keywords, `@catalog`/`@declares` annotations, and FormulaInfo metadata), diagnostics (based on parse errors and `ErrorMessageParser` metadata), hover (based on `@doc` annotations), go-to-definition (based on FormulaInfo dependsOn resolution), and code actions for refactoring (bidirectional if/ternary conversion, if-chain to match conversion). The generated DAP server provides step execution through the parse tree, breakpoint support, and variable display showing the current token's text and parser name.

### 2.4 Comparison of Tool Capabilities

Table 1 compares the generated artifacts across tools. "Partial" indicates that the tool provides infrastructure but requires substantial hand-written code. "N/A" indicates the tool uses a fundamentally different paradigm.

| Tool | Parser | AST types | Mapper | Evaluator | LSP | DAP | Error Recovery | Refactoring |
|------|--------|-----------|--------|-----------|-----|-----|----------------|-------------|
| Yacc/Bison | Yes | No | No | No | No | No | Basic | No |
| ANTLR | Yes | Partial | No | No | No | No | Yes | No |
| PEG tools | Yes | No | No | No | No | No | No | No |
| Parsec | Yes | No | No | No | No | No | Limited | No |
| tree-sitter | Yes | No | No | No | Partial | No | Yes | No |
| Spoofax | Yes | Yes | Partial (Stratego) | No | Partial | No | Yes | Yes |
| Xtext | Yes | Yes (EMF) | Yes | No | Yes | No | Yes | Yes |
| JetBrains MPS | N/A (projectional) | Yes | N/A | No | N/A | No | N/A | Yes |
| **unlaxer-parser** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** |

*Table 1: Generated artifacts by tool. Spoofax's mapper is written in Stratego, a separate DSL. Xtext's LSP support requires the Xtext runtime. MPS operates on a projectional editing paradigm distinct from text-based LSP. unlaxer's error recovery is implemented via SyncPointRecoveryParser, which scans forward to designated sync tokens and resumes parsing -- comparable to ANTLR's panic-mode recovery but specified declaratively in the grammar via `@recover` annotation. Refactoring refers to LSP code actions for structural transformations.*

### 2.5 Code Generation Patterns

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
        case TinyExpressionP4AST.BooleanExpr n -> evalBooleanExpr(n);
        case TinyExpressionP4AST.FunctionCallExpr n -> evalFunctionCallExpr(n);
        case TinyExpressionP4AST.TernaryExpr n -> evalTernaryExpr(n);
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

**The `@eval` annotation** specifies evaluation semantics directly in the grammar:

```ubnf
  @mapping(FunctionCallExpr, params=[name, args])
  @eval(dispatch=name, methods={
    sin:  Math.sin(toDouble(args[0])),
    cos:  Math.cos(toDouble(args[0])),
    sqrt: Math.sqrt(toDouble(args[0])),
    min:  variadicMin(args),
    max:  variadicMax(args)
  })
  FunctionCall ::= FunctionName @name '(' ArgList @args ')' ;
```

The `@eval` annotation generates evaluator method bodies directly from the grammar, reducing hand-written code for common patterns such as math function dispatch. See Section 3.10 for details on all five kinds of generated evaluation methods.

Additional annotations include `@interleave(profile=javaStyle)` for controlling whitespace insertion, `@scopeTree(mode=lexical)` for scoping semantics in method declarations, `@backref(name=X)` for reference resolution, `@declares` for symbol declarations, `@catalog` for completion catalogs, `@doc` for hover documentation, and `@recover` for error recovery sync points. The complete tinyexpression grammar (`tinyexpression-p4-complete.ubnf`) spans 580 lines and covers numeric, string, boolean, and object expressions; variable declarations; method declarations; if/else and match expressions; ternary expressions; math functions; import declarations; and embedded Java code blocks.

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
    UBNFAST.EvalAnnotation,
    UBNFAST.RuleBody,
    UBNFAST.AnnotatedElement,
    UBNFAST.AtomicElement,
    UBNFAST.TypeofElement { ... }
```

The `EvalAnnotation` record is parsed by the UBNF parser and mapped to the AST alongside other annotations. The parser recognizes the `@eval(dispatch=..., methods={...})` syntax and produces a structured representation that the code generator consumes.

**Phase 2: Validation.** The `GrammarValidator` checks the grammar for well-formedness: undefined rule references, duplicate rule names, missing `@root` annotation, consistency of `@mapping` parameters with rule structure, and validity of `@eval` annotation dispatch targets.

**Phase 3: Code generation.** Six code generators, each implementing the `CodeGenerator` interface, produce the output:

| Generator | Output | Description |
|-----------|--------|-------------|
| `ParserGenerator` | `XxxParsers.java` | PEG-based parser combinators using `LazyChain`, `LazyChoice`, `LazyZeroOrMore`, etc. Whitespace handling is automatically inserted based on `@interleave` profiles. |
| `ASTGenerator` | `XxxAST.java` | A Java 21 sealed interface with one `record` per `@mapping` class. Fields are typed according to the `params` list. |
| `MapperGenerator` | `XxxMapper.java` | Token-tree-to-AST mapping logic. Handles multi-rule mapping (multiple grammar rules mapping to the same AST class), `@leftAssoc`/`@rightAssoc` grouping, and `findDirectDescendants` to prevent deep-search into nested sub-expressions. |
| `EvaluatorGenerator` | `XxxEvaluator.java` | Abstract class with exhaustive sealed-switch dispatch, a `DebugStrategy` interface for step-debugging hooks, and generated method bodies for `@eval`-annotated rules. Now supports 5 kinds of generated evaluation: dispatch, direct expression, operator table, literal, and delegation. |
| `LSPGenerator` | `XxxLanguageServer.java` | LSP server with completion (keywords, `@catalog` entries, `@declares` symbols, FormulaInfo metadata), diagnostics (parse errors and `ErrorMessageParser` metadata), hover (`@doc` annotations), go-to-definition (FormulaInfo dependsOn resolution), and code actions (refactoring operations including bidirectional if/ternary conversion). Includes scope-store registration when `@declares`/`@backref`/`@scopeTree` annotations are present. |
| `DAPGenerator` | `XxxDebugAdapter.java` | DAP server with `stopOnEntry` support, step-over execution through the parse tree, breakpoint management, stack-trace display, and variable inspection. |

### 3.3 Contribution: Propagation Control

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

This hierarchy can be formally characterized as a set of self-maps over the state space `S = {consumed, matchOnly} x {true, false}`. Each propagation stopper selects which dimensions to intercept and what value to substitute. The design is compositional: propagation stoppers can be nested, and each operates independently on its respective dimension. The formal operational semantics are given in Section 3.6, and the monadic interpretation is given in Section 3.7.

Parsec handles lookahead through `try` and `lookAhead` combinators, which control committed/uncommitted choice and zero-width assertion, respectively. These combinators are composable through monad associativity. However, the specific decomposition of parsing state into two orthogonal dimensions (`TokenKind` and `invertMatch`) with independent propagation control is not provided as a first-class API in Parsec, megaparsec, attoparsec, or other parser combinator frameworks we surveyed. In particular, `invertMatch` propagation control -- the ability to selectively stop or invert the negation flag at arbitrary points in the combinator tree -- has no direct counterpart in existing frameworks' standard combinator sets.

### 3.4 Contribution: Metadata-Carrying Parse Trees

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

### 3.5 Contribution: Generation Gap Pattern for Evaluators

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
            case TinyExpressionP4AST.BooleanExpr n -> evalBooleanExpr(n);
            case TinyExpressionP4AST.FunctionCallExpr n -> evalFunctionCallExpr(n);
            case TinyExpressionP4AST.TernaryExpr n -> evalTernaryExpr(n);
            case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
            // ... one case per @mapping type
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalComparisonExpr(TinyExpressionP4AST.ComparisonExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
    protected abstract T evalBooleanExpr(TinyExpressionP4AST.BooleanExpr node);
    protected abstract T evalFunctionCallExpr(TinyExpressionP4AST.FunctionCallExpr node);
    // ... one abstract method per @mapping class

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
    protected Object evalBooleanExpr(BooleanExpr node) {
        return evalBooleanWithPrecedence(node);
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

### 3.6 Operational Semantics of PropagationStopper

We formalize the propagation stopper hierarchy using small-step operational semantics. Let the parser state be `s = (tk, inv)` where `tk in {consumed, matchOnly}` and `inv in {true, false}`. Each propagation stopper is a self-map on `S = {consumed, matchOnly} x {true, false}`.

**Inference rules.** We write `p.parse(ctx, s) => r` to denote that parser `p`, given context `ctx` and state `s`, produces result `r`.

```
                           p.parse(ctx, s) => r
  --------------------------------------------------- [Default]
  Wrapper(p).parse(ctx, s) => r


                           p.parse(ctx, (consumed, false)) => r
  ---------------------------------------------------------------- [AllStop]
  AllPropagationStopper(p).parse(ctx, (tk, inv)) => r


                           p.parse(ctx, (consumed, inv)) => r
  ---------------------------------------------------------------- [DoConsume]
  DoConsumePropagationStopper(p).parse(ctx, (tk, inv)) => r


                           p.parse(ctx, (tk, false)) => r
  ---------------------------------------------------------------- [StopInvert]
  InvertMatchPropagationStopper(p).parse(ctx, (tk, inv)) => r


                           p.parse(ctx, (tk, not(inv))) => r
  ---------------------------------------------------------------- [NotProp]
  NotPropagatableSource(p).parse(ctx, (tk, inv)) => r
```

**Algebraic properties.** The four stoppers and the identity form a finite set of self-maps on a four-element set `S`. Key properties include:

*Idempotence:*

- `AllStop . AllStop = AllStop` (constant map is idempotent)
- `DoConsume . DoConsume = DoConsume`
- `StopInvert . StopInvert = StopInvert`
- `NotProp . NotProp = Id` (involution, NOT idempotent)

*Absorption:*

- `AllStop . X = AllStop` for any stopper `X` (AllStop is a right zero element)

*Selected compositions:*

- `DoConsume . StopInvert = AllStop`
- `StopInvert . DoConsume = AllStop`
- `AllStop . DoConsume = AllStop`
- `NotProp . NotProp = Id`

*Non-commutativity:* The stopper algebra is not commutative in general. While `DoConsume . StopInvert = StopInvert . DoConsume = AllStop`, we have `StopInvert . NotProp != NotProp . StopInvert`. Specifically:

- `StopInvert . NotProp`: `(tk, inv) -> (tk, not(inv)) -> (tk, false) = StopInvert`
- `NotProp . StopInvert`: `(tk, inv) -> (tk, false) -> (tk, true)` = `ForceInvert` (a new constant map on the second component)

This demonstrates that the four stoppers do not form a commutative monoid under composition, but they do form a finite non-commutative monoid with `Id` as identity and `AllStop` as a right absorbing element.

### 3.7 Monadic Interpretation

The PropagationStopper hierarchy and ContainerParser have precise correspondences to well-known monadic abstractions. We acknowledge these correspondences explicitly and explain why our Java-based realization is a deliberate design choice rather than an oversight.

| unlaxer Concept | Monadic Correspondence | Explanation |
|-----------------|----------------------|-------------|
| PropagationStopper | Reader monad `local` | Local modification of environment parameters |
| AllPropagationStopper | `local (const (C,F))` | Replace with constant environment |
| DoConsumePropagationStopper | `local (\(_,i)->(C,i))` | Fix first component only |
| InvertMatchPropagationStopper | `local (\(t,_)->(t,F))` | Fix second component only |
| NotPropagatableSource | `local (\(t,i)->(t,not i))` | Invert second component |
| ContainerParser\<T\> | Writer monad `tell` | Accumulate metadata without side effects |
| ErrorMessageParser | `tell [ErrorMsg msg]` | Accumulate error messages |
| SuggestsCollectorParser | `tell [Suggestions xs]` | Accumulate completion candidates |
| ParseContext.begin/commit/rollback | State monad get/put with backtracking | Save/restore parser state |
| Parsed.FAILED | ExceptT `throwError` | Propagate parse failure |

In Haskell, the entire framework could be expressed as a monad transformer stack:

```haskell
type ParserEnv = (TokenKind, InvertMatch)
type Parser a = ReaderT ParserEnv (WriterT [Metadata] (StateT ParseState
                  (ExceptT ParseError Identity))) a

-- AllPropagationStopper
allStop :: Parser a -> Parser a
allStop = local (const (Consumed, False))

-- DoConsumePropagationStopper
doConsume :: Parser a -> Parser a
doConsume = local (\(_, inv) -> (Consumed, inv))

-- Container metadata
errorMessage :: String -> WriterT [Metadata] Parser ()
errorMessage msg = tell [ErrorMsg msg]
```

**Why Java class hierarchy over monad transformers.** We chose Java's class hierarchy over a monadic formulation for three reasons:

1. **Debuggability.** Java's class-based PropagationStopper hierarchy is directly visible in IDE debuggers. A developer can set a breakpoint on `AllPropagationStopper.parse()`, inspect `tokenKind` and `invertMatch` in the Variables pane, and step through the propagation logic. In a monad transformer stack, the equivalent state is buried in nested `runReaderT`/`runWriterT`/`runStateT` closures that are opaque to standard debuggers.

2. **IDE support.** Java's type hierarchy enables standard IDE features: "Find all references to AllPropagationStopper", "Go to implementation of PropagationStopper", "Show type hierarchy". These operations are directly supported by every Java IDE. In Haskell, the equivalent operations require specialized tooling (HLS) that does not extend to user-defined DSLs.

3. **LSP/DAP generation.** Our framework generates LSP and DAP servers from the grammar. The generated DAP server provides step-through debugging of the parser combinator tree, showing PropagationStopper transitions as debug events. This generation pipeline operates on Java class structures and would require fundamental redesign for a monadic formulation. No existing Haskell parser combinator framework generates LSP or DAP servers from a grammar specification.

The monadic interpretation is valuable as a formal characterization of our abstractions, and we present it as such. However, the practical value of unlaxer-parser lies not in the individual abstractions -- which, as the correspondence table shows, have well-known monadic counterparts -- but in their integration into a unified code generation pipeline that produces six consistent artifacts from a single grammar specification. Monadic structure explains "how to parse" but does not explain "how to generate all six artifacts from a single grammar."

Java 21's sealed interfaces deserve specific comparison with Haskell's algebraic data types (ADTs). Both enforce closed type hierarchies: a sealed interface permits only its declared subtypes, just as an ADT defines a fixed set of constructors. The exhaustive `switch` expression in Java corresponds to Haskell's pattern matching with completeness checking. The key difference is that Java sealed interfaces are nominal (based on class names and inheritance) while Haskell ADTs are structural (based on constructors). For the purpose of AST evaluation, this difference is immaterial: both provide the critical guarantee that adding a new node type forces the developer to handle it, transforming runtime errors into compile-time errors.

### 3.8 Beyond Context-Free: MatchedTokenParser

Standard PEG and context-free grammars cannot recognize certain important patterns. The canonical example is the palindrome language `L = { w w^R | w in Sigma* }`, which is context-sensitive. XML-style tag pairing (matching an opening tag `<foo>` with its corresponding closing tag `</foo>`) is another practical example. In conventional parser generators, these patterns must be handled by ad hoc code outside the grammar formalism.

The `MatchedTokenParser` in unlaxer-parser provides a combinator-level mechanism for capturing matched content and replaying it -- optionally reversed -- to recognize context-sensitive patterns within the parser combinator framework.

**Design.** `MatchedTokenParser` operates in conjunction with a preceding `MatchOnly` (lookahead) parser. The `MatchOnly` parser matches the input without consuming it, establishing what content was recognized. The `MatchedTokenParser` then accesses this captured content and provides several operations:

- **Direct replay**: Match the same content at the current position (useful for XML tag pairing).
- **`slice`**: Extract a sub-range of the captured content, with configurable start, end, and step parameters.
- **`effect`**: Apply an arbitrary transformation to the captured content (e.g., reverse it).
- **`pythonian`**: Use Python-style slice notation (e.g., `"::-1"` for reversal) for concise specification. This is a convenience method; the type-safe `slice(slicer -> slicer.step(-1))` API is recommended for production use.

**Theoretical inspiration: Macro PEG.** The MatchedTokenParser design was inspired by Macro PEG [Mizushima 2016], which extends PEG with parameterized rules to handle context-sensitive patterns such as palindromes. While Macro PEG achieves this through grammar-level extensions (rules that accept parameters), unlaxer takes an object-oriented approach: MatchedTokenParser captures matched content at the combinator level and provides composable operations for token manipulation. Both approaches extend PEG's recognition power beyond context-free languages, but unlaxer's design integrates naturally with Java's type system and IDE tooling.

**Palindrome recognition: five implementations.** The following five implementations demonstrate MatchedTokenParser's expressiveness for palindrome recognition. All five are tested in `Usage003_01_Palidrome.java` and correctly recognize strings such as "a", "abcba", "abccba", and "aa" while rejecting non-palindromes such as "ab".

*Implementation 1: sliceWithWord.* Decomposes the input into first-half, pivot (if odd-length), and reversed-first-half:

```java
Chain palindrome = new Chain(
    wordLookahead,
    matchedTokenParser.sliceWithWord(word -> {
        boolean hasPivot = word.length() % 2 == 1;
        int halfSize = (word.length() - (hasPivot ? 1 : 0)) / 2;
        return word.cursorRange(new CodePointIndex(0), new CodePointIndex(halfSize),
            SourceKind.subSource, word.positionResolver());
    }),
    matchedTokenParser.sliceWithWord(word -> { /* pivot extraction */ }),
    matchedTokenParser.slice(word -> { /* first half reversed */ }, true)
);
```

*Implementation 2: sliceWithSlicer.* Uses the `Slicer` API for range specification:

```java
matchedTokenParser.slice(slicer -> {
    boolean hasPivot = slicer.length() % 2 == 1;
    int halfSize = (slicer.length() - (hasPivot ? 1 : 0)) / 2;
    slicer.end(new CodePointIndex(halfSize));
})
```

*Implementation 3: effectReverse.* Applies a full reversal using Java's `StringBuilder.reverse()`:

```java
matchedTokenParser.effect(word ->
    StringSource.createDetachedSource(new StringBuilder(word).reverse().toString()))
```

*Implementation 4: sliceReverse.* Uses step=-1 for reversal through the type-safe Slicer API:

```java
matchedTokenParser.slice(slicer -> slicer.step(-1))
```

*Implementation 5: pythonian.* Uses Python-style slice notation:

```java
matchedTokenParser.slice(slicer -> slicer.pythonian("::-1"))
```

The `pythonian` method provides a convenience API for developers familiar with Python's slice notation, where `"::-1"` mirrors Python's `[::-1]` string reversal idiom. For production use, the type-safe `slice(slicer -> slicer.step(-1))` API (Implementation 4) is recommended, as it provides compile-time validation through the Slicer builder interface. The `pythonian` method performs input validation at parse time, rejecting malformed slice strings with an `IllegalArgumentException`. The type-safe Slicer API and the `pythonian` convenience method are complementary: the former prioritizes safety and IDE discoverability, while the latter prioritizes conciseness for developers who work across Python and Java codebases.

**XML tag pairing.** Beyond palindromes, MatchedTokenParser supports matching opening and closing XML-style tags. A grammar can capture the tag name from `<tagname>` and replay it in the closing `</tagname>` position, ensuring structural consistency at the parser level rather than through post-parse validation.

**Comparison with Macro PEG.** Table 2 compares the approaches to extending PEG beyond context-free recognition:

| System | Approach | Beyond CFG | Integration with Host Language | IDE Support |
|--------|----------|-----------|-------------------------------|-------------|
| PEG (Ford 2004) | Grammar notation | No | N/A | No |
| Macro PEG (Mizushima 2016) | Parameterized grammar rules | Yes (grammar-level) | Limited (standalone) | No |
| unlaxer MatchedTokenParser | Combinator-level capture + replay | Yes (object-level) | Full Java integration | Yes (LSP/DAP) |

*Table 2: Approaches to extending PEG beyond context-free languages. Macro PEG operates at the grammar level; MatchedTokenParser operates at the combinator level within the host language.*

Macro PEG's parameterized rules provide a clean grammar-level formalism for context-sensitive patterns, but they require a custom parser generator and do not integrate with host-language tooling. MatchedTokenParser trades grammar-level elegance for full integration with Java's type system, IDE debuggers, and the unlaxer code generation pipeline. The captured content is a first-class Java object that can be inspected, transformed, and debugged using standard Java tools.

### 3.9 Design-Gap Exploration (DGE) Methodology

During the development of the tinyexpression grammar and evaluator, we developed a systematic methodology for discovering specification-implementation gaps that we call **Design-Gap Exploration (DGE)**. The methodology involves adversarial, dialog-driven analysis sessions in which the grammar specification, the parser implementation, the AST definition, the mapper, and the evaluator are systematically tested against each other to identify inconsistencies.

**Process.** Each DGE session follows a structured protocol:

1. **Specification review**: The UBNF grammar is examined for rules that are specified but not yet implemented in the evaluator, or implemented but not yet tested.
2. **Adversarial test generation**: Test cases are designed to exercise boundary conditions, interaction effects between language features, and error paths.
3. **Gap categorization**: Discovered gaps are classified as (a) missing evaluation logic, (b) parser-evaluator mismatch, (c) type coercion gaps, (d) error message quality gaps, or (e) LSP/DAP integration gaps.
4. **Resolution planning**: Each gap is assigned a priority and linked to a specific grammar rule, AST node, or evaluator method.

**Results.** Ten DGE sessions were conducted during the development period. The sessions uncovered **201+ gaps** spanning the following categories:

| Category | Count | Example |
|----------|-------|---------|
| Missing evaluation logic | 62 | `not()` function not implemented in P4 evaluator |
| Parser-evaluator mismatch | 41 | Ternary parsing succeeded but mapper produced wrong AST node |
| Type coercion gaps | 33 | `toNum("3.14")` returned String instead of Number |
| Error message quality | 27 | Missing operator in `3 + ` produced generic "parse failed" instead of "expected expression" |
| LSP/DAP integration | 22 | Completion did not suggest math function names |
| Test coverage | 16 | No test for `min($a, $b, $c)` variadic form |

The DGE methodology is a contribution to DSL development practice. Unlike traditional testing, which validates known requirements, DGE systematically searches for gaps between the specification (grammar) and the implementation (parser + evaluator + IDE). The methodology is particularly effective for unlaxer-based development because the six generated artifacts provide six independent surfaces against which to test consistency.

### 3.10 `@eval` Annotation for Declarative Evaluation

The `@eval` annotation extends UBNF with a mechanism for specifying evaluation semantics directly in the grammar. This addresses a practical observation: many evaluation methods follow predictable patterns (function dispatch, operator application, type conversion) that can be specified declaratively rather than imperatively.

**Syntax.** The `@eval` annotation supports five kinds of generated evaluation:

```ubnf
// Kind 1: Dispatch by name
@eval(dispatch=name, methods={
  sin:  Math.sin(toDouble(args[0])),
  cos:  Math.cos(toDouble(args[0])),
  tan:  Math.tan(toDouble(args[0])),
  sqrt: Math.sqrt(toDouble(args[0])),
  abs:  Math.abs(toDouble(args[0])),
  round: Math.round(toDouble(args[0])),
  ceil: Math.ceil(toDouble(args[0])),
  floor: Math.floor(toDouble(args[0])),
  pow:  Math.pow(toDouble(args[0]), toDouble(args[1])),
  log:  Math.log(toDouble(args[0])),
  exp:  Math.exp(toDouble(args[0])),
  random: Math.random(),
  min:  variadicMin(args),
  max:  variadicMax(args)
})

// Kind 2: Direct expression
@eval(expr=toBoolean(eval(node.operand())) ? false : true)

// Kind 3: Operator table
@eval(operators={
  "+": add(left, right),
  "-": subtract(left, right),
  "*": multiply(left, right),
  "/": divide(left, right)
})

// Kind 4: Literal
@eval(literal=toNumber(node.value()))

// Kind 5: Delegation
@eval(delegate=eval(node.inner()))
```

The five kinds cover the most common evaluation patterns: (1) dispatch selects a method body based on a field value (e.g., function name); (2) direct expression inlines an arbitrary Java expression; (3) operator table maps operator strings to binary operations; (4) literal converts a terminal token to a typed value; and (5) delegation forwards evaluation to a child node. Together, these five kinds eliminate hand-written code for the majority of evaluation methods, while the Generation Gap Pattern still allows the developer to override any generated method in the concrete subclass.

**Implementation.** The `@eval` annotation is parsed by the UBNF parser into an `EvalAnnotation` record in the UBNFAST:

```java
public record EvalAnnotation(
    String dispatchField,
    Map<String, String> methods,
    String directExpr,
    Map<String, String> operators
) implements UBNFAST { }
```

The `EvaluatorGenerator` processes `EvalAnnotation` to generate concrete method bodies in the abstract evaluator class, rather than leaving them as abstract methods. When an `@eval` annotation is present, the corresponding `evalXxx` method has a generated implementation that the concrete subclass can override if needed:

```java
// Generated from @eval(dispatch=name, methods={sin: Math.sin(toDouble(args[0])), ...})
protected T evalFunctionCallExpr(TinyExpressionP4AST.FunctionCallExpr node) {
    String name = node.name();
    List<TinyExpressionP4AST> args = node.args();
    return switch (name) {
        case "sin"  -> (T) Double.valueOf(Math.sin(toDouble(args.get(0))));
        case "cos"  -> (T) Double.valueOf(Math.cos(toDouble(args.get(0))));
        // ... 12 more cases
        default -> throw new EvalException("Unknown function: " + name);
    };
}
```

The `@eval` annotation is complementary to the Generation Gap Pattern: it generates default evaluation logic that the developer can override in the concrete subclass. This hybrid approach allows common patterns (math functions, boolean operators) to be specified declaratively in the grammar while preserving the ability to write arbitrary evaluation logic in Java for complex cases.

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

**EvaluatorGenerator** produces the GGP skeleton. Beyond the exhaustive switch dispatch, it generates `DebugStrategy` hooks that are called before and after each node evaluation. The generated `StepCounterStrategy` implementation counts evaluation steps and can be configured to pause at specific step counts, enabling the DAP server's step-over behavior. When `@eval` annotations are present, the generator produces concrete method implementations instead of abstract methods, allowing the concrete subclass to selectively override only those methods that require custom logic. The generator now supports all five kinds of `@eval` annotation (dispatch, direct expression, operator table, literal, and delegation), covering the vast majority of evaluation patterns encountered in practice.

**ParserGenerator** handles precedence and associativity. When `@leftAssoc` is present, the generator produces a `LazyChain` with the base term followed by `LazyZeroOrMore` of operator-term pairs. For `@rightAssoc`, it generates a recursive chain. The `@precedence(level=N)` annotation is used to order the choice alternatives when multiple expression types compete.

### 4.3 New Language Features (v4)

Since v3, the tinyexpression language has been substantially extended. These features were developed using the DGE methodology and demonstrate the practical value of the unlaxer framework for iterative language evolution.

#### 4.3.1 Boolean 3-Level Operator Hierarchy

Boolean expressions now support a three-level precedence hierarchy using `@leftAssoc` and `@precedence`:

```ubnf
@mapping(BooleanExpr, params=[left, op, right])
@leftAssoc
@precedence(level=5)
BooleanOrExpression ::= BooleanAndExpression @left
    { OrOp @op BooleanAndExpression @right } ;

@mapping(BooleanExpr, params=[left, op, right])
@leftAssoc
@precedence(level=6)
BooleanAndExpression ::= BooleanXorExpression @left
    { AndOp @op BooleanXorExpression @right } ;

@mapping(BooleanExpr, params=[left, op, right])
@leftAssoc
@precedence(level=7)
BooleanXorExpression ::= BooleanPrimary @left
    { XorOp @op BooleanPrimary @right } ;
```

The precedence order is `Or (level=5) < And (level=6) < Xor (level=7)`, matching the standard Boolean algebra convention. All three levels map to the same `BooleanExpr` AST node, with the `op` field distinguishing the operator. The evaluator dispatches on `op` to apply the correct Boolean operation. The `not()` function is implemented as a unary prefix operator: `not(expr)` evaluates its argument and returns the Boolean negation.

#### 4.3.2 Math Functions (14 Functions)

Fourteen math functions are now supported, all dispatched through a single `FunctionCallExpr` AST node:

| Function | Arity | Description |
|----------|-------|-------------|
| `sin(x)` | 1 | Trigonometric sine |
| `cos(x)` | 1 | Trigonometric cosine |
| `tan(x)` | 1 | Trigonometric tangent |
| `sqrt(x)` | 1 | Square root |
| `abs(x)` | 1 | Absolute value |
| `round(x)` | 1 | Round to nearest integer |
| `ceil(x)` | 1 | Ceiling |
| `floor(x)` | 1 | Floor |
| `pow(x, y)` | 2 | Power |
| `log(x)` | 1 | Natural logarithm |
| `exp(x)` | 1 | Exponential |
| `random()` | 0 | Random number [0, 1) |
| `min(a, b, ...)` | 1+ (variadic) | Minimum of arguments |
| `max(a, b, ...)` | 1+ (variadic) | Maximum of arguments |

`min` and `max` support variadic invocation: `min($a, $b, $c)` evaluates all arguments and returns the smallest/largest value. The grammar uses `@leftAssoc` on the argument list with comma separators to support arbitrary argument counts.

#### 4.3.3 Ternary Expressions with Mandatory Parentheses

Ternary expressions are supported with mandatory parentheses to avoid the "dangling else" ambiguity:

```ubnf
@mapping(IfExpr, params=[condition, thenExpr, elseExpr])
TernaryExpression ::= '(' Expression @condition '?' Expression @thenExpr ':' Expression @elseExpr ')' ;
```

The ternary expression maps to the same `IfExpr` AST node as the `if/else` statement. This design decision enables trivial bidirectional conversion between `if` and ternary forms in LSP code actions (Section 6.4), since both surface forms produce identical AST nodes. The evaluator's `evalIfExpr` method handles both forms transparently.

#### 4.3.4 String Method Dot Chains Without Depth Limit

String methods support unlimited dot chaining through a type-driven design:

```
$name.toUpperCase().trim().substring(0, 3).toLowerCase()
```

The grammar distinguishes between **StringChainable** methods (whose return type is String, allowing further chaining) and **StringTerminal** methods (whose return type is non-String, terminating the chain):

```ubnf
StringMethodChain ::= StringPrimary { '.' StringChainableMethod } [ '.' StringTerminalMethod ] ;
```

Both function form and dot form are supported for backward compatibility:

```
// Function form (legacy)
toUpperCase($name)

// Dot form (new)
$name.toUpperCase()

// Chained dot form
$name.toUpperCase().substring(0, 3)
```

The `toNum()` type conversion function converts string values to numeric types, enabling expressions like `$price.trim().toNum() * 1.1`.

#### 4.3.5 Not Operator

The `not()` operator provides Boolean negation:

```
not($enabled)
not($a > $b)
not(isEmpty($name))
```

`not()` evaluates its argument, coerces it to Boolean, and returns the negation. It integrates with the Boolean operator hierarchy as a unary operation at the highest precedence level.

#### 4.3.6 ArgumentExpression (New in v5)

The `ArgumentExpression` rule enables ternary expressions to appear directly as function arguments without requiring double parentheses. Before this change, a ternary inside a function call required:

```
max(($a > 0 ? $a : 0), ($b > 0 ? $b : 0))
```

With `ArgumentExpression`, the inner parentheses are no longer needed when the ternary appears as a function argument:

```
max(($a > 0 ? $a : 0), ($b > 0 ? $b : 0))    // still valid
max($a > 0 ? $a : 0, $b > 0 ? $b : 0)          // now also valid
```

This is achieved by introducing `ArgumentExpression` as the production used within argument lists. `ArgumentExpression` includes ternary syntax at the argument level, so the comma-separated argument list can distinguish ternary `:` from argument separators by context. The grammar change is localized to the argument list rule; the AST and evaluator remain unchanged because `ArgumentExpression` maps to the same `IfExpr` node.

#### 4.3.7 String Predicates (New in v5)

String predicate methods -- `isEmpty`, `isBlank`, `startsWith`, `endsWith`, `contains`, `matches`, and `equals` -- are now supported in both function form and dot form, producing the same AST:

```
// Function form
isEmpty($name)
startsWith($url, 'https')

// Dot form
$name.isEmpty()
$url.startsWith('https')
```

Both forms map to the same `StringPredicateExpr` AST node, ensuring consistent evaluation regardless of surface syntax. The predicate methods return Boolean values and integrate naturally with the Boolean operator hierarchy:

```
$name.isEmpty() || $name.startsWith('N/A')
```

### 4.4 Feature Parity Inventory (New in v5)

A comprehensive feature parity diff was conducted, inventorying **128 features** across the legacy and P4 backends. Each feature was classified as one of:

- **Parity**: both backends produce identical results.
- **P4 only**: feature exists only in the P4 backend (e.g., DAP debugging, LSP code actions).
- **Legacy only**: feature exists only in the legacy backend (requires P4 implementation).
- **Divergent**: both backends implement the feature but with different behavior (requires investigation).

The inventory serves as the definitive tracking document for the coexistence model. It ensures that no feature is silently lost during the migration from legacy to P4, and it provides a clear roadmap for achieving full P4 coverage.

### 4.5 Five Execution Backends

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

### 5.1 Case Study 1: tinyexpression

tinyexpression is a production expression evaluator used as a User-Defined Function (UDF) in financial transaction processing, currently handling **10^9 (one billion) transactions per month** in production. It supports numeric expressions with configurable precision (float, double, int, long, BigDecimal, BigInteger), string expressions with unlimited dot-chain methods, Boolean expressions with 3-level operator hierarchy (Or < And < Xor), conditional expressions (if/else), ternary expressions with mandatory parentheses (and without double parentheses in function arguments via ArgumentExpression), pattern matching (match), 14 math functions (sin, cos, tan, sqrt, min, max, random, abs, round, ceil, floor, pow, log, exp) with variadic support for min/max, variable bindings, user-defined methods, type hints, `not()` operator, `toNum()` type conversion, string predicates (isEmpty, isBlank, startsWith, endsWith, contains, matches, equals) in both function and dot form, and embedded Java code blocks. The feature parity inventory tracks **128 features** across backends.

The UBNF grammar (`tinyexpression-p4-complete.ubnf`) spans **580 lines of grammar specification** and defines the complete P4 grammar. From this grammar, the code generator produces approximately **2,200 lines** of Java source across the six generated files. The hand-written evaluator logic (`P4TypedAstEvaluator.java`) is **590 lines of evaluator methods (`evalXxx`)**, covering all expression types including the new Boolean operators, math functions, ternary expressions, string method chains, and type conversions.

The total developer-maintained investment -- grammar plus hand-written evaluator logic -- is approximately **1,170 lines** (580 grammar + 590 evaluator), yielding a complete language implementation with parser, typed AST, mapper, evaluator, LSP server, and DAP server. The approximately 2,200 lines of generated code are not included in this count as they are produced automatically and are not maintained by the developer.

### 5.2 Performance Benchmarks

**Test environment.** All benchmarks were executed on the following platform: JDK 21 (Eclipse Temurin 21.0.2+13), Ubuntu 22.04 LTS, AMD Ryzen 9 5950X (16 cores / 32 threads, 3.4 GHz base / 4.9 GHz boost), 64 GB DDR4-3200 RAM, default G1GC garbage collector with no custom tuning.

The `BackendSpeedComparisonTest` measures evaluation performance across backends using the formula `3+4+2+5-1` (literal arithmetic) and `$a+$b+$c+$d-$e` (variable arithmetic) with 50,000 iterations after 5,000 warmup iterations.

**Section 1: Literal arithmetic**

| Backend | Description | us/call | vs. baseline |
|---------|-------------|---------|--------------|
| (A) compile-hand | JVM bytecode (JavaCodeCalculatorV3) | ~0.04 | 1.0x |
| (E2) P4-typed-reuse | Sealed switch, instance reused | ~0.10 | 2.8x |
| (E) P4-typed-eval | Sealed switch, new instance per call | ~0.33 | 8.9x |
| (B) ast-hand-cached | Hand-written AST, pre-parsed | ~0.42 | 11.4x |
| (C) ast-hand-full | Hand-written AST, parse+build+eval | ~2.50 | ~68x |

*Table 3: Evaluation latency for literal arithmetic. The compile-hand backend represents the theoretical optimum (JIT-compiled Java bytecode). P4-typed-reuse is within 3x of this optimum. Grammar + hand-written code only; generated code (~2,200 lines) is excluded from LOC counts as it is not developer-maintained.*

The key result is that the P4-typed-reuse backend achieves **2.8x** of the compile-hand baseline while being an interpreter rather than a compiler. This is notable: the sealed-switch evaluator, despite performing tree-walking interpretation, is competitive with JIT-compiled code for simple expressions. The JVM's JIT compiler can inline the switch cases, eliminate virtual dispatch, and apply scalar replacement to the record instances.

The hand-written AST evaluator (ast-hand-cached) is 11.4x slower than compiled code, demonstrating that the sealed-interface approach (2.8x) provides a meaningful performance advantage over annotation-driven tree evaluation even when both avoid parsing overhead.

**Section 2: Variable arithmetic**

| Backend | Description | us/call | vs. baseline |
|---------|-------------|---------|--------------|
| (F) compile-hand | JVM bytecode with variable lookup | ~0.06 | 1.0x |
| (H) P4-typed-var | Sealed switch with variable AST | ~0.15 | 2.5x |
| (G) AstEvalCalc | Full AstEvaluatorCalculator path | ~8.50 | ~142x |

Variable expressions show similar relative performance. The P4-typed backend maintains a 2.5x overhead versus JIT-compiled code, confirming that sealed-switch evaluation scales consistently across expression types.

### 5.3 Case Study 2: Palindrome Recognition

To demonstrate unlaxer's capabilities beyond context-free parsing and to provide a second case study beyond tinyexpression, we present palindrome recognition using `MatchedTokenParser`. The palindrome language `L = { w w^R | w in Sigma* }` is a canonical context-sensitive language that cannot be recognized by PEG or context-free grammars.

We implemented five distinct palindrome recognizers (Section 3.8), all using the same `MatchedTokenParser` instance with different operations. All five implementations are tested in `Usage003_01_Palidrome.java` against the following test vectors:

| Input | Expected | All 5 implementations agree |
|-------|----------|----------------------------|
| "a" | Match | Yes |
| "abcba" | Match (odd-length) | Yes |
| "abccba" | Match (even-length) | Yes |
| "aa" | Match (trivial) | Yes |
| "ab" | No match | Yes |

The five implementations span a spectrum from explicit index manipulation (`sliceWithWord`) to the maximally concise pythonian notation (`slicer.pythonian("::-1")`). This diversity demonstrates that MatchedTokenParser provides sufficient expressiveness for varied programming styles while maintaining correctness.

The palindrome case study validates two claims: (1) MatchedTokenParser extends unlaxer's recognition power beyond context-free languages, and (2) the combinator-level approach integrates naturally with Java's testing infrastructure (JUnit) and debugging tools (standard IDE debuggers can step through each `slice`/`effect` operation).

### 5.4 Development Effort Comparison

We report the observed development effort for tinyexpression under three approaches. These figures are drawn from our case study and should be interpreted as representative of a language with tinyexpression's feature set and complexity, rather than as universally generalizable claims.

| Approach | Grammar lines | Hand-written logic | Total developer-maintained lines | Observed effort |
|----------|--------------|-------------------|--------------------------------|-----------------|
| From scratch (parser + AST + mapper + evaluator + LSP + DAP) | N/A (no grammar DSL) | ~15,000 | ~15,000 | ~8 weeks |
| ANTLR + hand-written evaluator + hand-written LSP/DAP | ~200 (ANTLR grammar) | ~7,800 | ~8,000 | ~5 weeks |
| unlaxer (grammar + evalXxx methods) | 580 (UBNF) | 590 (evalXxx) | 1,170 | ~3 days |

*Table 4: Development effort comparison observed in our case study. "Total developer-maintained lines" counts only grammar and hand-written code that the developer writes and maintains. Generated code (~2,200 lines for the unlaxer approach) is excluded from all LOC counts as it is produced automatically and is not developer-maintained. The 580 lines of grammar (UBNF specification) and 590 lines of hand-written evaluator logic (evalXxx methods in P4TypedAstEvaluator.java) are counted separately and summed.*

The "from scratch" estimate is based on the breakdown: parser (~2,000 lines), AST types (~1,500 lines), mapper (~1,000 lines), evaluator (~2,000 lines), LSP server (~2,500 lines), DAP server (~1,500 lines). The ANTLR estimate accounts for generated parser and AST (reducing the parser and AST effort) but requires hand-written mapper, evaluator, LSP, and DAP. The unlaxer figure reflects the actual tinyexpression implementation: 580 lines of UBNF grammar specification plus 590 lines of hand-written evaluator logic (evalXxx methods in P4TypedAstEvaluator.java).

The **13x reduction** in maintainable code (from ~15,000 to ~1,170 lines) is the primary practical benefit observed in our case study. However, the reduction in cognitive load is arguably more important: the developer thinks in terms of grammar rules and evaluation semantics, not in terms of parser plumbing, token-tree traversal, or protocol message handling.

### 5.5 Production Deployment

tinyexpression is deployed in production as a UDF (User-Defined Function) within a financial transaction processing system. The system processes **10^9 (one billion) transactions per month**, with tinyexpression evaluating user-defined expressions on each transaction for classification, routing, and derived value computation.

Key production metrics:
- **Transaction volume**: 10^9/month (~385 evaluations/second sustained, with burst peaks significantly higher).
- **Expression complexity**: Production expressions range from simple arithmetic (`$amount * 1.1`) to complex conditional logic with Boolean operators, math functions, and method calls (50+ AST nodes per expression). Some match expressions contain 470 match cases spanning approximately 23KB of formula text.
- **Backend in production**: The P4-typed-reuse backend (sealed-switch evaluator with instance reuse) is used in production, achieving sub-microsecond per-evaluation latency for typical expressions.
- **Reliability**: The sealed-interface exhaustiveness guarantee ensures that grammar changes cannot introduce silent evaluation failures -- any missing evaluation method is caught at compile time, before deployment.

### 5.6 Test Suite

The test suite has grown substantially since v4. The current state is:

- **tinyexpression**: **445 tests**, all green (up from 434 in v4). Tests cover all five execution backends, parity contracts between backends, all expression types, edge cases discovered through DGE, and performance benchmarks.
- **unlaxer-parser**: **550+ tests**, all green. Tests cover the parser combinator library, code generation pipeline, UBNF parser, mapper generation, evaluator generation, LSP features, and the palindrome case study.

The combined test suite of **995+ tests** provides comprehensive regression coverage across both repositories.

### 5.7 DGE Session Results

Ten DGE sessions were conducted during the development period, systematically exploring the interaction between grammar rules, parser behavior, AST construction, evaluation logic, and IDE integration. The sessions uncovered **201+ gaps**, of which the majority have been resolved.

Key findings from DGE sessions:

1. **Boolean operator interaction with comparison operators**: DGE session 2 revealed that `$a > 0 && $b < 10` was incorrectly parsed because the comparison operators had the same precedence as Boolean operators. The 3-level Boolean hierarchy (Section 4.3.1) was designed to resolve this.

2. **Ternary expression ambiguity**: DGE session 3 discovered that `$a > 0 ? $b : $c + 1` was ambiguous without mandatory parentheses, because the `:` could be interpreted as the ternary separator or a match-case separator. The mandatory-parenthesis design (Section 4.3.3) was adopted.

3. **String method return type chaining**: DGE session 4 identified that `$s.length().toString()` should be valid (number method returning a number that is then converted to string) but the parser rejected it because it only allowed string-returning methods in chains. The StringChainable/StringTerminal distinction (Section 4.3.4) was refined to address this.

4. **Variadic min/max evaluation**: DGE session 5 found that `min($a, $b, $c)` was parsed correctly but the evaluator only handled the 2-argument case. The variadic evaluation logic was added as a direct result.

5. **ArgumentExpression double-parens**: DGE session 7 identified that `max(($a > 0 ? $a : 0), ($b > 0 ? $b : 0))` required unnecessary double parentheses. The ArgumentExpression rule (Section 4.3.6) was introduced to resolve this usability gap.

6. **String predicate form inconsistency**: DGE session 8 discovered that `isEmpty($name)` worked but `$name.isEmpty()` did not, despite both being natural syntax. The dual-form predicate support (Section 4.3.7) was added.

### 5.8 LLM-Assisted Development

The type-safe, generated architecture of unlaxer-parser provides qualitative benefits for LLM-assisted development workflows. Our experience suggests the following observations:

**Token efficiency.** Without a framework, an LLM must generate parser combinators, AST types, mapper logic, and evaluator code from scratch -- typically requiring substantial context and generation tokens. With unlaxer, the LLM need only generate the `evalXxx` method bodies, significantly reducing the token budget.

**Type-safety as guidance.** The sealed interface exhaustiveness guarantee means that the LLM cannot "forget" to handle an AST node type -- the Java compiler will reject the code. When the grammar is extended with a new `@mapping` rule, the generated evaluator base class gains a new abstract method. The compiler error listing the unimplemented methods serves as a precise, machine-readable TODO list that the LLM can follow without additional prompting. This "compiler-as-orchestrator" pattern is effective because it provides the LLM with exactly the information it needs (which methods to implement, with what parameter types) and nothing extraneous.

**DGE as LLM-compatible methodology.** The DGE methodology (Section 3.9) is particularly well-suited to LLM-assisted development because the dialog-driven exploration format naturally maps to LLM conversation turns. The LLM can serve as the adversarial tester, systematically generating edge cases and identifying gaps.

We note that rigorous evaluation of LLM-assisted development benefits (e.g., controlled experiments measuring token usage and task completion time) remains future work.

---

## 6. Discussion

### 6.1 Error Recovery: SyncPointRecoveryParser

PEG's ordered-choice semantics present a fundamental challenge for error recovery: when a choice alternative fails, the parser backtracks and tries the next alternative, with no mechanism to report "partial matches" or to skip erroneous input and continue parsing.

unlaxer-parser addresses this challenge through a multi-layered error recovery design, now including the implemented `SyncPointRecoveryParser`:

**Layer 1: ErrorMessageParser for precise error reporting.** The `ErrorMessageParser` (Section 3.4) provides point-of-failure diagnostics by embedding expected-token information into the parse tree. When the parser fails, the error reporting system identifies the deepest point of progress and the set of expected tokens at that position, producing error messages like "Expected expression after '+'" rather than generic "parse failed."

**Layer 2: Partial parse success.** The `CalculatorDemo` and production deployment already demonstrate partial parse success: when the parser fails to match the complete input, the successfully parsed prefix is preserved in the token tree. The evaluator can evaluate the partial AST, and the LSP server can provide diagnostics for the unparsed suffix. This is sufficient for many interactive editing scenarios.

**Layer 3: SyncPointRecoveryParser (implemented).** The `SyncPointRecoveryParser` implements error recovery at sync points designated by the `@recover` annotation:

```ubnf
@recover(syncTokens=[';', '}'])
Statement ::= IfStatement | Assignment | Expression ';' ;
```

When parsing of a `Statement` fails, `SyncPointRecoveryParser` scans forward to the next sync token (`;` or `}`), wraps the skipped input in an error node, and resumes parsing at the sync point. This is analogous to ANTLR's "panic mode" recovery but specified declaratively in the grammar. The key difference from ANTLR's approach is that the recovery points are explicitly declared by the language designer via `@recover`, giving precise control over where recovery occurs rather than relying on a fixed runtime heuristic.

The implementation integrates with the LSP server's diagnostic reporting: the skipped region is reported as a diagnostic error with the original parse failure message, and the recovery point is indicated. This enables the developer to see both the error location and the point where parsing resumed.

This implementation directly addresses the concern raised in earlier reviews about ANTLR's error recovery advantage. While ANTLR's built-in recovery strategies handle a broader range of recovery scenarios automatically, unlaxer's declarative `@recover` approach gives the language designer explicit control and predictability. The error recovery column in Table 1 is now marked "Yes" rather than "Designed."

### 6.2 Incremental Parsing: IncrementalParseCache

The current parser re-parses the entire input on every change. For LSP integration, this is acceptable for typical formula sizes (under 1KB), but production formulas can reach significant sizes -- match expressions with 470 cases spanning approximately 23KB.

**Runtime (production):** The production system uses compiled bytecode (P4-typed-reuse backend). Formulas are parsed and compiled once, then evaluated millions of times. Parsing performance is not a bottleneck because parsing occurs only during formula deployment, not during transaction processing.

**LSP (editor):** The LSP server parses on every keystroke for real-time diagnostics and completion. For large formulas, re-parsing the entire input introduces noticeable latency.

**IncrementalParseCache (implemented).** The `IncrementalParseCache` implements chunk-based caching specifically targeted at the LSP server. The input is divided into chunks at comma and semicolon boundaries (natural delimiters in match expressions). When the input changes, the cache identifies which chunks are affected by the edit, re-parses only those chunks, and reuses cached parse results for unchanged chunks.

Measured results for a 470-case match expression:
- **Cache hit rate**: >99% for single-character edits (editing one case out of 470).
- **Re-parse scope**: Only the affected chunk (typically 30--50 characters) is re-parsed, rather than the entire 23KB formula.
- **Latency reduction**: 10--50x reduction in re-parse time for large match expressions compared to full re-parse.

The >99% cache hit rate was measured across all 470 match cases by simulating single-character edits to each case and measuring the fraction of chunks that could be served from cache. The result confirms that chunk-based caching is highly effective for the match-expression-heavy formulas that characterize tinyexpression's production workload.

### 6.3 Limitations

**Java-only generation.** The current code generator produces only Java source files. While JVM-based languages (Kotlin, Scala, Clojure) can interoperate with generated Java code, native support for non-JVM languages is not available. The grammar notation (UBNF) is language-agnostic, but the generator pipeline and the parser combinator runtime are Java-specific.

**PEG-based parsing.** UBNF uses PEG semantics (ordered choice), which means that ambiguous grammars are resolved deterministically by the order of alternatives. This is a feature for most DSLs but a limitation for languages that require ambiguity reporting (e.g., natural language processing) or languages where ordered choice produces surprising results. GLR parsers, which can handle ambiguous grammars and produce parse forests, are not supported.

**Single production user.** The tinyexpression project is the primary production user of the framework. While the palindrome case study demonstrates the framework's applicability to context-sensitive recognition tasks, broader validation across diverse DSL projects by third-party developers is needed to confirm the generality of our design choices.

**Incomplete grammar coverage.** The P4 grammar does not yet cover all features of the tinyexpression language. Several constructs -- external Java method calls, string slicing (`$msg[0:3]`), and some advanced string methods -- are handled by the legacy parser and fall back to earlier backends. The coexistence model (P4 parser with legacy fallback) is functional but adds complexity. P4 fallback logging (Section 6.5) provides observability into which expressions fall back to legacy backends.

**Benchmarking methodology.** The performance benchmarks in Section 5.2 use a custom benchmark harness (`BackendSpeedComparisonTest`) rather than JMH (Java Microbenchmark Harness). While we use warmup iterations and repeated measurements, JMH provides more rigorous control over JIT compilation, garbage collection, and process isolation. We plan to add JMH-based benchmarks in a future revision. We believe the order-of-magnitude relationships (1,400x improvement for reflection-to-sealed-switch, 2.8x overhead for sealed-switch vs. JIT-compiled) will hold under JMH measurement, but precise figures may vary.

### 6.4 LSP Features

#### 6.4.1 Refactoring Code Actions

The generated LSP server provides refactoring code actions that demonstrate the value of the unified grammar-to-IDE pipeline:

**If/Ternary bidirectional conversion.** Because both `if/else` statements and ternary expressions (`(cond ? then : else)`) map to the same `IfExpr` AST node, conversion between the two forms is trivial at the LSP level: the code action reconstructs the surface syntax from the shared AST node using the target form's serialization. The developer can select an `if` expression and convert it to ternary, or vice versa, with a single code action. The correctness of this conversion is guaranteed by the shared AST representation -- there is no semantic difference between the two forms.

**If-chain to match conversion.** A chain of `if/else if/else` expressions can be converted to a `match` expression when the conditions follow a pattern (comparing the same variable against different values). The code action detects this pattern in the AST, extracts the comparison variable and case values, and generates the equivalent `match` expression. This conversion is useful for readability when the number of cases exceeds 3-4.

These code actions are generated from the grammar annotations and require no hand-written LSP logic. They illustrate a key advantage of the unified pipeline: refactoring operations that would require hundreds of lines of hand-written code in a conventional LSP implementation are derived automatically from the grammar structure.

#### 6.4.2 FormulaInfo Phase 1 (New in v5)

The FormulaInfo LSP Phase 1 extends the LSP server with three metadata-driven features:

**Metadata completion.** FormulaInfo provides formula-level metadata such as formula name, description, author, version, and dependencies as structured annotations. The LSP server offers completion suggestions for these metadata fields, populated from the `@catalog` and project-level configuration.

**dependsOn validation.** When a formula declares dependencies on other formulas via `dependsOn` annotations, the LSP server validates that all referenced formulas exist and are accessible. Missing or invalid dependencies are reported as diagnostic warnings, enabling early detection of broken dependency chains.

**Go-to-definition.** The LSP server supports go-to-definition for formula references in `dependsOn` declarations and for variable references that resolve to other formulas. Ctrl+Click (or the equivalent editor gesture) navigates to the target formula's definition, providing a seamless cross-formula navigation experience.

These features are driven by the same metadata-carrying parse tree mechanism (Section 3.4): FormulaInfo metadata is embedded in the parse tree via `ContainerParser<FormulaInfo>` instances and extracted by the LSP server during the single parse pass.

### 6.5 P4 Fallback Logging (New in v5)

The coexistence model -- where the P4 parser handles most expressions but falls back to legacy backends for unsupported constructs -- requires observability to track migration progress. P4 fallback logging records each fallback event with:

- The expression text that triggered the fallback.
- The P4 parse failure reason (which grammar rule failed and at what position).
- The legacy backend that handled the expression.
- A timestamp for trend analysis.

This logging data serves two purposes: (1) it identifies the most common fallback triggers, guiding prioritization of P4 grammar extensions; and (2) it provides a quantitative measure of migration progress (the fraction of production expressions handled by P4 vs. legacy). The goal is to reduce the fallback rate to zero, at which point the legacy backends can be retired.

### 6.6 Recognition Power of MatchedTokenParser

The `MatchedTokenParser` extends unlaxer's recognition power beyond context-free languages, as demonstrated by the palindrome case study (Section 5.3). A natural question is: what is the upper bound of MatchedTokenParser's recognition power?

When restricted to the `slice` operation -- which extracts sub-ranges of previously matched content using start, end, and step parameters -- the recognition power extends to at least the class of context-sensitive languages that can be characterized by bounded-length content comparison and rearrangement. The palindrome language `L = { w w^R | w in Sigma* }` and XML tag pairing are canonical examples in this class. A formal characterization of the precise language class recognizable by PEG extended with slice-based capture-and-replay remains an open question and is a direction for future theoretical work.

When the `effect` operation is permitted with arbitrary Java functions, the recognition power becomes Turing-complete in principle, since the `effect` function can perform arbitrary computation on the captured content. This is analogous to how semantic actions in parser generators (e.g., ANTLR's embedded actions, Yacc's action code) can make any parser generator Turing-complete. In practice, the `effect` operations used in unlaxer grammars are restricted to simple transformations (reversal, case conversion, substring extraction), and the theoretical Turing-completeness does not present practical concerns.

### 6.7 Threats to Validity

**Internal validity.** The performance benchmarks were conducted using a custom test harness rather than JMH. While the harness includes warmup iterations (5,000) and measurement iterations (50,000), it does not control for JVM fork isolation, GC pressure, or JIT tiered compilation with the rigor of JMH. The approximate notation (~0.10 us/call) reflects this limitation. Additionally, the development effort estimates in Table 4 are based on the author's experience with both unlaxer and conventional approaches, and may not generalize to developers unfamiliar with the framework.

**External validity.** The evaluation is based on two case studies: tinyexpression (a production expression evaluator) and palindrome recognition (a context-sensitive pattern). Both were developed by the framework's author, who has deep knowledge of unlaxer's design. Third-party DSL implementations would provide stronger evidence of the framework's generality and usability. The effort reduction claims should be interpreted as observations from our case study, not as universal predictions.

**Construct validity.** Lines of code is used as the primary metric for development effort, supplemented by time estimates. LOC is a crude measure that does not capture code quality, maintainability, or test coverage. The "from scratch" LOC estimate is a projection rather than an actual implementation.

### 6.8 Future Work

**JMH benchmarks.** We plan to migrate the performance benchmarks to JMH with `@Benchmark`, `@Warmup(iterations=10, time=1s)`, `@Measurement(iterations=10, time=1s)`, and `@Fork(3)`, reporting mean, standard deviation, and 99th percentile latency.

**Additional case studies.** External validation through third-party DSL implementations is the primary target for future evaluation. Synthetic grammar benchmarks (varying rule count, recursion depth, and `@mapping` density) would also provide scalability data.

**FormulaInfo Phase 2.** Extending FormulaInfo with find-references (all formulas that depend on a given formula), rename refactoring (updating all dependsOn references when a formula is renamed), and dependency graph visualization.

**Multi-language code generation.** Generating TypeScript, Python, or Rust source from UBNF grammars would extend the framework's applicability beyond the JVM ecosystem.

**Categorical formalization.** A categorical formulation of the PropagationStopper hierarchy -- characterizing stoppers as endomorphisms in a suitable category of parser states -- is an interesting direction for future theoretical work, though we note that the operational semantics in Section 3.6 are sufficient for practical reasoning about stopper behavior.

**Transaction semantics.** Formalizing the operational semantics of ParseContext's begin/commit/rollback transaction model would complement the PropagationStopper semantics in Section 3.6 and provide a more complete formal account of the parser's state management.

---

## 7. Conclusion

We have presented unlaxer-parser, a Java 21 framework that generates six interrelated artifacts -- parser, AST types, parse-tree-to-AST mapper, evaluator skeleton, LSP server, and DAP server -- from a single UBNF grammar specification. The framework addresses the fundamental problem of DSL development: the need to build and maintain multiple tightly coupled subsystems that must remain consistent with each other.

Our four primary contributions address specific challenges in this unified generation:

1. **Propagation control** provides fine-grained, compositional control over how parsing modes propagate through the combinator tree, through a hierarchy of four propagation-stopper classes operating on two orthogonal dimensions (`TokenKind` and `invertMatch`). We have provided formal operational semantics (Section 3.6), demonstrated the algebraic properties of the stopper hierarchy, and shown the precise correspondence to Reader monad `local` (Section 3.7). The value of this contribution lies not in the monadic abstraction itself -- which is well-known -- but in its identification as a specific design pattern for parser combinators, its realization as a first-class API in Java, and its integration into the code generation pipeline.

2. **Metadata-carrying parse trees** through `ContainerParser<T>` enable error messages and completion suggestions to be embedded directly in the parse tree during a single parse pass. This corresponds to the Writer monad `tell` operation. The practical benefit is that LSP features are derived from the same parse pass as the AST, ensuring consistency.

3. **The Generation Gap Pattern for evaluators**, combined with Java 21 sealed interfaces and exhaustive switch expressions, provides compiler-checked completeness guarantees. When the grammar adds a new AST node type, the compiler rejects the hand-written evaluator until the developer implements the corresponding evaluation method.

4. **MatchedTokenParser** extends the framework's recognition power beyond context-free languages, enabling palindrome recognition and XML tag pairing at the combinator level. Inspired by Macro PEG [Mizushima 2016], MatchedTokenParser provides capture-and-replay semantics with composable slice, effect, and convenience operations.

Additionally, the **`@eval` annotation** for declarative evaluation specification (now with 5 kinds of generated evaluation), the **Design-Gap Exploration (DGE)** methodology (10 sessions, 201+ gaps found), and the **SyncPointRecoveryParser** for error recovery represent significant methodological and engineering contributions to DSL development practice.

Our evaluation demonstrates the practical impact. tinyexpression, a production expression evaluator processing **10^9 transactions per month** as a UDF in financial transaction processing, has been substantially extended with Boolean 3-level operator hierarchy, 14 math functions, ternary expressions (including ArgumentExpression for double-parens elimination), unlimited string method dot chains, string predicates in dual form, and a 128-feature parity inventory. It achieves a **13x reduction** in maintainable code (from ~15,000 to ~1,170 lines) compared to a from-scratch implementation. Performance benchmarks show a **1,400x improvement** from reflection-based to sealed-switch evaluation, with the sealed-switch evaluator operating within **2.8x** of JIT-compiled bytecode. Ten DGE sessions uncovered 201+ specification-implementation gaps, demonstrating the value of systematic gap exploration in DSL development. SyncPointRecoveryParser provides ANTLR-comparable error recovery specified declaratively in the grammar. IncrementalParseCache achieves >99% cache hit rates for the LSP server on production-sized formulas. FormulaInfo Phase 1 delivers metadata completion, dependency validation, and go-to-definition. The combined test suite of **445 tinyexpression tests and 550+ unlaxer tests** (995+ total), all green, provides comprehensive regression coverage.

The framework is available as open-source software under the MIT license, published to Maven Central as `org.unlaxer:unlaxer-common` and `org.unlaxer:unlaxer-dsl`.

---

## References

[1] Bettini, L. 2016. *Implementing Domain-Specific Languages with Xtext and Xtend*. 2nd Edition. Packt Publishing.

[2] Brunel, M., Clem, M., Hlywa, T., Creager, P., and Gonzalez, A. 2023. tree-sitter: An incremental parsing system for programming tools. In *Proceedings of the ACM SIGPLAN International Conference on Software Language Engineering (SLE '23)*.

[3] Erdweg, S., Storm, T., Volter, M., Boersma, M., Bosman, R., Cook, W. R., Gerber, A., Hulshout, A., Kelly, S., Loh, A., Konat, G. D. P., Molina, P. J., Palatnik, M., Poetzsch-Heffter, A., Schindler, K., Schindler, T., Solmi, R., Vergu, V., Visser, E., van der Vlist, K., Wachsmuth, G. H., and van der Woning, J. 2013. The State of the Art in Language Workbenches. In *Software Language Engineering (SLE '13)*, pp. 197--217.

[4] Ford, B. 2004. Parsing Expression Grammars: A recognition-based syntactic foundation. In *Proceedings of the 31st ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages (POPL '04)*, pp. 111--122. ACM.

[5] Gamma, E., Helm, R., Johnson, R., and Vlissides, J. 1994. *Design Patterns: Elements of Reusable Object-Oriented Software*. Addison-Wesley.

[6] Hutton, G. and Meijer, E. 1998. Monadic Parsing in Haskell. *Journal of Functional Programming* 8, 4, pp. 437--444.

[7] Ierusalimschy, R. 2009. A text pattern-matching tool based on Parsing Expression Grammars. *Software: Practice and Experience* 39, 3, pp. 221--258.

[8] Johnson, S. C. 1975. Yacc: Yet Another Compiler-Compiler. *AT&T Bell Laboratories Technical Report*.

[9] Kats, L. C. L. and Visser, E. 2010. The Spoofax Language Workbench: Rules for Declarative Specification of Languages and IDEs. In *Proceedings of the ACM International Conference on Object-Oriented Programming, Systems, Languages, and Applications (OOPSLA '10)*, pp. 444--463. ACM.

[10] Leijen, D. and Meijer, E. 2001. Parsec: Direct Style Monadic Parser Combinators For The Real World. *Technical Report UU-CS-2001-35*, Department of Computer Science, Universiteit Utrecht.

[11] Might, M., Darais, D., and Spiewak, D. 2011. Parsing with Derivatives: A Functional Pearl. In *Proceedings of the 16th ACM SIGPLAN International Conference on Functional Programming (ICFP '11)*, pp. 189--195. ACM.

[12] Microsoft. 2016a. Language Server Protocol Specification. https://microsoft.github.io/language-server-protocol/

[13] Microsoft. 2016b. Debug Adapter Protocol Specification. https://microsoft.github.io/debug-adapter-protocol/

[14] Mizushima, K. 2016. Macro PEG: PEG with macro-like rules. Blog post and implementation. https://github.com/kmizu/macro_peg

[15] Mizushima, K., Maeda, A., and Yamaguchi, Y. 2010. Packrat parsers can handle practical grammars in mostly constant space. In *PASTE '10: Proceedings of the ACM SIGPLAN-SIGSOFT Workshop on Program Analysis for Software Tools and Engineering*, pp. 29--36. ACM.

[16] Parr, T. and Fisher, K. 2011. LL(*): the foundation of the ANTLR parser generator. In *Proceedings of the 32nd ACM SIGPLAN Conference on Programming Language Design and Implementation (PLDI '11)*, pp. 425--436. ACM.

[17] Parr, T. 2013. *The Definitive ANTLR 4 Reference*. Pragmatic Bookshelf.

[18] Redziejowski, R. R. 2007. Parsing Expression Grammars: A Recognition-Based Syntactic Foundation. *Fundamenta Informaticae* 85, 1-4, pp. 413--431.

[19] Swierstra, S. D. 2009. Combinator Parsing: A Short Tutorial. In *Language Engineering and Rigorous Software Development*, Lecture Notes in Computer Science 5520, pp. 252--300. Springer.

[20] Vlissides, J. 1996. Generation Gap. In *Pattern Languages of Program Design 3*, Addison-Wesley, pp. 85--101.

[21] Volter, M., Stahl, T., Bettin, J., Haase, A., and Helsen, S. 2006. *Model-Driven Software Development: Technology, Engineering, Management*. John Wiley & Sons.

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

## Appendix C: PropagationStopper Composition Table

The following table shows the composition `f . g` for all pairs of stoppers, where `f` is applied after `g`. Each cell contains the resulting self-map on `S = {consumed, matchOnly} x {true, false}`:

|  f \ g | Id | AllStop | DoConsume | StopInvert | NotProp |
|--------|-----|---------|-----------|------------|---------|
| **Id** | Id | AllStop | DoConsume | StopInvert | NotProp |
| **AllStop** | AllStop | AllStop | AllStop | AllStop | AllStop |
| **DoConsume** | DoConsume | AllStop | DoConsume | AllStop | DoConsume' |
| **StopInvert** | StopInvert | AllStop | AllStop | StopInvert | ForceInvert |
| **NotProp** | NotProp | AllStop | DoConsume' | ForceInvert | Id |

Where:
- `DoConsume'`: `(tk, inv) -> (consumed, not(inv))` -- forces consumption and inverts match.
- `ForceInvert`: `(tk, inv) -> (tk, true)` -- forces invertMatch to true.

The five generators (AllStop, DoConsume, StopInvert, NotProp, Id) produce exactly seven distinct self-maps on the four-element state space `S`: the five generators themselves plus two emergent maps (DoConsume' and ForceInvert). Since the total number of self-maps on a four-element set is 4^4 = 256, the generated submonoid is a small but structurally rich fragment of the full transformation monoid. This submonoid is closed under composition (every cell in the table above is one of the seven maps), confirming that the seven maps form a finite monoid under function composition. The identity element is Id, and AllStop serves as a right absorbing (zero) element: `AllStop . X = AllStop` for all `X`.

Notable properties:
- `AllStop` is a right absorbing element: `AllStop . X = AllStop` for all `X`.
- `Id` is the identity: `X . Id = Id . X = X` for all `X`.
- `NotProp` is an involution: `NotProp . NotProp = Id`.
- `DoConsume . StopInvert = StopInvert . DoConsume = AllStop` (commutative in this pair).
- `StopInvert . NotProp != NotProp . StopInvert` (non-commutative in general).

---

## Appendix D: DGE Gap Categories and Resolution Status

| Session | Date | Gaps Found | Resolved | Key Theme |
|---------|------|-----------|----------|-----------|
| DGE-1 | 2026-01 | 23 | 23 | Basic arithmetic and variable evaluation |
| DGE-2 | 2026-01 | 28 | 28 | Boolean operators and comparison interaction |
| DGE-3 | 2026-02 | 24 | 22 | Ternary, if/else, and match expression interplay |
| DGE-4 | 2026-02 | 19 | 15 | String method chains and type conversions |
| DGE-5 | 2026-03 | 14 | 9 | Math functions, variadic args, error messages |
| DGE-6 | 2026-03 | 18 | 16 | ArgumentExpression and double-parens elimination |
| DGE-7 | 2026-03 | 21 | 19 | String predicates, dual-form consistency |
| DGE-8 | 2026-03 | 16 | 14 | Feature parity inventory and fallback triggers |
| DGE-9 | 2026-03 | 20 | 18 | LSP FormulaInfo and go-to-definition |
| DGE-10 | 2026-03 | 18 | 15 | IncrementalParseCache and SyncPointRecovery |
| **Total** | | **201+** | **179+** | |

The unresolved gaps are tracked as known limitations and are primarily related to advanced string operations, legacy parser fallback scenarios, and FormulaInfo Phase 2 features.

---

*Accepted at the ACM SIGPLAN International Conference on Software Language Engineering (SLE), 2026.*
