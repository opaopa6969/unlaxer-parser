[English](./tutorial-ubnf-to-lsp-dap-dialogue.en.md) | [日本語](./tutorial-ubnf-to-lsp-dap-dialogue.ja.md) | [Index](./INDEX.md)

---

# From UBNF to LSP/DAP -- An unlaxer-parser Tutorial through Dialogue

> **Characters**
> - **Senior**: The designer of unlaxer-parser and tinyexpression. Built the code generation pipeline from scratch
> - **Newcomer**: A junior developer skilled in Java but new to parser generators

---

## Part 1: UBNF Grammar Basics

**Newcomer:** Senior, there are files with a `.ubnf` extension in the project. What are these? I learned about EBNF and PEG in college, but...

**Senior:** UBNF stands for Unlaxer BNF. It's based on EBNF, but it includes annotations not just for parser generation, but for generating AST and evaluators too. That's the biggest difference.

**Newcomer:** So it's an extended version of EBNF?

**Senior:** Yes. The basic syntax is the same as EBNF. But it uses ordered choice (priority-based selection) like PEG for parsing. So there's no ambiguous grammar. Let's look at an actual file.

```ubnf
grammar TinyExpressionP4 {

  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser
  token EOF        = EndOfSourceParser
```

**Newcomer:** You declare a name with `grammar` and specify the Java package with `@package`. What are the `token` declarations?

**Senior:** `token` declares lexical-level parsers. You can directly reference existing Java classes. `NumberParser` reads numeric literals, `IdentifierParser` reads identifiers. unlaxer-parser has built-in parser classes that these reference.

**Newcomer:** I see. So how do you write syntax rules?

**Senior:** Like this.

```ubnf
  @root
  Formula ::= { CodeBlock } { ImportDeclaration } { VariableDeclaration }
              { Annotation } Expression { MethodDeclaration } EOF ;
```

**Newcomer:** What's `@root`?

**Senior:** The entry point for parsing. It specifies the root rule for the entire grammar. For tinyexpression, `Formula` is the root. User-written expressions must match this `Formula` rule.

**Newcomer:** What are the curly braces in `{ CodeBlock }`? In EBNF, they're for grouping, right?

**Senior:** In UBNF, `{ X }` means **ZeroOrMore** -- "zero or more repetitions of X." Same meaning as `{ }` in EBNF.

**Newcomer:** Oh, that's straightforward. What other operators are there?

**Senior:** Here's a summary.

| Notation | Meaning | Example |
|----------|---------|---------|
| `{ X }` | ZeroOrMore (0 or more) | `{ CodeBlock }` |
| `X +` | OneOrMore (1 or more) | `Digit +` |
| `[ X ]` | Optional (0 or 1) | `[ NumberTypeHint ]` |
| `X \| Y` | Choice (selection) | `'var' \| 'variable'` |
| `X Y Z` | Sequence (concatenation) | `'$' IDENTIFIER` |

**Newcomer:** These correspond to PEG's `*`, `+`, `?`. Just with EBNF-style notation.

**Senior:** Right. But note that Choice is "ordered choice that tries matches from left to right," just like PEG. It doesn't become ambiguous like EBNF. If the left alternative matches, the right one is never tried.

**Newcomer:** Got it. Next, I'm curious about the `@mapping` annotation.

**Senior:** This is UBNF's signature feature. Look.

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**Newcomer:** `@mapping(BinaryExpr, params=[left, op, right])`... BinaryExpr is... an AST node name?

**Senior:** Correct. `@mapping` is an instruction that says "when this grammar rule successfully parses, create the corresponding AST record." A Java record named `BinaryExpr` is auto-generated, with `left`, `op`, and `right` as its fields.

**Newcomer:** There are `@left`, `@op`, and such inside the rule -- what are those?

**Senior:** Parameter bindings. They link grammar elements to AST record fields. `NumberTerm @left` means "put the part that matched NumberTerm into the `left` field."

**Newcomer:** Ah, so that's why it's `BinaryExpr(left, op, right)`! Being able to see the AST shape just by reading the grammar is convenient.

**Senior:** Right? With ANTLR, you have to write the grammar and the Visitor separately, but UBNF lets you declaratively write the mapping within the grammar.

**Newcomer:** What are `@leftAssoc` and `@precedence(level=10)`?

**Senior:** Operator associativity and precedence. `NumberExpression` handles addition/subtraction (`+`, `-`) with precedence 10.

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=20)
  NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;
```

**Newcomer:** `NumberTerm` handles multiplication/division with precedence 20. Higher precedence means it matches first?

**Senior:** Yes. Higher numbers bind more tightly. For `3 + 4 * 2`, `NumberTerm` (precedence 20) first captures `4 * 2`, then `NumberExpression` (precedence 10) processes `3 + 8`. Same as mathematical operator precedence.

**Newcomer:** `@leftAssoc` means left-associative. `3 - 2 - 1` becomes `(3 - 2) - 1`.

**Senior:** Exactly. If you specified `@rightAssoc`, it would become `3 - (2 - 1)`. But arithmetic operations are normally left-associative, so we use `@leftAssoc`.

**Newcomer:** There are various other annotations too. Like `@interleave` and `@scopeTree`.

**Senior:** `@interleave(profile=javaStyle)` is an instruction to "automatically skip whitespace and comments between elements of this rule." Java style skips `//` comments, `/* */` comments, and whitespace.

```ubnf
  @interleave(profile=javaStyle)
  ImportDeclaration ::=
      ImportDeclarationWithMethod
    | ImportDeclarationBare ;
```

**Newcomer:** What about `@scopeTree(mode=lexical)`?

**Senior:** For rules that have scope, like method declarations. Used for resolving variables in lexical scope.

```ubnf
  @scopeTree(mode=lexical)
  MethodDeclaration ::=
      NumberMethodDeclaration
    | StringMethodDeclaration
    | BooleanMethodDeclaration
    | ObjectMethodDeclaration ;
```

**Newcomer:** UBNF has more information density than I thought. You can declare not just grammar, but also AST structure and scope.

**Senior:** That's what makes it more than "just a grammar definition language." UBNF is like a "blueprint for a language processor." By writing grammar alone, you get the skeleton of a parser, AST, mapper, and evaluator.

**Newcomer:** The `$` notation for variable references is interesting too.

```ubnf
  @mapping(VariableRefExpr, params=[name])
  VariableRef ::= '$' IDENTIFIER @name [ TypeHint ] ;
```

**Senior:** This is tinyexpression's variable syntax. Variables are referenced with a `$` prefix like `$price`. The mapping puts the identifier into the `name` field. TypeHint is optional.

**Newcomer:** It's like PHP.

**Senior:** Ha, maybe so. But this was designed for use as an Excel formula engine, and `$` was chosen to avoid confusion with cell references.

---

## Part 2: The Code Generation Pipeline

**Newcomer:** Senior, how is Java code generated from UBNF files?

**Senior:** Four generators work in coordination.

| Generator | Input | Output |
|-----------|-------|--------|
| `ParserGenerator` | UBNF | `TinyExpressionP4Parsers.java` |
| `ASTGenerator` | UBNF | `TinyExpressionP4AST.java` |
| `MapperGenerator` | UBNF | `TinyExpressionP4Mapper.java` |
| `EvaluatorGenerator` | UBNF | `TinyExpressionP4Evaluator.java` |

**Newcomer:** Four! What does each one do?

**Senior:** Let me explain one by one. First, `ParserGenerator`. It reads UBNF rules and generates parser combinator Java classes.

```java
// TinyExpressionP4Parsers.java (generated code)
public class TinyExpressionP4Parsers {

    public static final int PRECEDENCE_NUMBEREXPRESSION = 10;
    public static final int PRECEDENCE_NUMBERTERM = 20;

    public enum Assoc { LEFT, RIGHT, NONE }

    public record OperatorSpec(String ruleName, int precedence, Assoc assoc) {}

    private static final java.util.List<OperatorSpec> OPERATOR_SPECS = java.util.List.of(
            new OperatorSpec("NumberExpression", 10, Assoc.LEFT),
            new OperatorSpec("NumberTerm", 20, Assoc.LEFT)
    );
    // ...
}
```

**Newcomer:** The `@precedence` and `@leftAssoc` are directly reflected in `OperatorSpec`!

**Senior:** Right. Grammar annotations are directly reflected in generated code. Calling `getRootParser()` gives you a parser that handles the entire UBNF.

**Newcomer:** Next is `ASTGenerator`?

**Senior:** `ASTGenerator` reads `@mapping` annotations and generates sealed interfaces and record groups.

```java
// TinyExpressionP4AST.java (generated code)
public sealed interface TinyExpressionP4AST permits
    TinyExpressionP4AST.CodeBlockExpr,
    TinyExpressionP4AST.BinaryExpr,
    TinyExpressionP4AST.VariableRefExpr,
    TinyExpressionP4AST.IfExpr,
    // ... all 26 records
    TinyExpressionP4AST.ExpressionExpr {

    record BinaryExpr(
        TinyExpressionP4AST.BinaryExpr left,
        List<String> op,
        List<TinyExpressionP4AST.BinaryExpr> right
    ) implements TinyExpressionP4AST {}

    record VariableRefExpr(
        String name
    ) implements TinyExpressionP4AST {}

    record IfExpr(
        TinyExpressionP4AST.BooleanExpr condition,
        TinyExpressionP4AST.ExpressionExpr thenExpr,
        TinyExpressionP4AST.ExpressionExpr elseExpr
    ) implements TinyExpressionP4AST {}
    // ...
}
```

**Newcomer:** Java 21's sealed interface! The `permits` clause explicitly lists which records are allowed.

**Senior:** Right. This pays off later with `switch` expression pattern matching. The compiler checks whether all cases are covered.

**Newcomer:** The third one, `MapperGenerator`?

**Senior:** It generates a mapper that converts the parse tree (Token tree structure) into AST.

```java
// TinyExpressionP4Mapper.java (generated code)
public class TinyExpressionP4Mapper {

    public static TinyExpressionP4AST parse(String source) {
        return parse(source, null);
    }

    public static TinyExpressionP4AST parse(String source, String preferredAstSimpleName) {
        Parser rootParser = TinyExpressionP4Parsers.getRootParser();
        ParseContext context = new ParseContext(createRootSourceCompat(source));
        Parsed parsed = rootParser.parse(context);
        // ... get Token tree from parse result
        Token rootToken = parsed.getRootToken(true);
        Token bestMappedToken = findBestMappedToken(rootToken, preferredAstSimpleName);
        TinyExpressionP4AST mapped = mapToken(bestMappedToken);
        return mapped;
    }

    private static TinyExpressionP4AST mapToken(Token token) {
        if (token.parser.getClass() == TinyExpressionP4Parsers.NumberExpressionParser.class) {
            return toBinaryExpr(token);  // NumberExpression -> BinaryExpr
        }
        if (token.parser.getClass() == TinyExpressionP4Parsers.NumberTermParser.class) {
            return toBinaryExpr(token);  // NumberTerm -> BinaryExpr too!
        }
        // ...
    }
}
```

**Newcomer:** The `parse()` method takes a string and returns an AST directly. Convenient.

**Senior:** Yes. Internally it's a 3-step process: "string -> parse -> Token tree -> AST," but from the outside, it's a single method call.

**Newcomer:** The last one, `EvaluatorGenerator`?

**Senior:** This is the most interesting one. It generates a base class with `evalXxx()` abstract methods for each AST node.

```java
// TinyExpressionP4Evaluator.java (generated code)
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
            case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
            case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
            case TinyExpressionP4AST.ComparisonExpr n -> evalComparisonExpr(n);
            // ... all 26 cases
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalVariableRefExpr(TinyExpressionP4AST.VariableRefExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
    // ... all 26 methods
}
```

**Newcomer:** Since there's a type parameter `<T>`, it can be an evaluator that returns values with `T = Object`, or an emitter that generates code with `T = String`!

**Senior:** Exactly right! That's the core of this design.

**Newcomer:** And how do you run these generations?

**Senior:** Maven's `exec:java` plugin calls `CodegenMain`.

```bash
# UBNF -> Java code generation (configured in tinyexpression's pom.xml)
cd /home/opa/work/tinyexpression && mvn compile

# Running manually
java -cp ... org.unlaxer.dsl.CodegenMain \
  --input tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf \
  --output target/generated-sources/tinyexpression-p4/runtime
```

**Newcomer:** Everything gets generated just by running `mvn compile`?

**Senior:** Yes. The `exec-maven-plugin` in pom.xml runs `CodegenMain` during the `generate-sources` phase, and the four generators run in sequence. Generated code goes into `target/generated-sources/`.

```
target/generated-sources/tinyexpression-p4/runtime/
  org/unlaxer/tinyexpression/generated/p4/
    TinyExpressionP4Parsers.java    <- parser combinators
    TinyExpressionP4AST.java        <- sealed interface + records
    TinyExpressionP4Mapper.java     <- Token -> AST mapping
    TinyExpressionP4Evaluator.java  <- abstract evaluation base class
```

**Newcomer:** What if I just want to run tests?

**Senior:** If you want to skip code generation, use `-Dexec.skip=true`.

```bash
mvn test -Dtest=AstEvaluatorTest -Dexec.skip=true
```

**Newcomer:** Is the generated code checked into Git?

**Senior:** It's under `target/`, so `.gitignore` ignores it. It's generated fresh on every build. You only need to version-control the UBNF file to ensure reproducibility.

---

## Part 3: AST Structure

**Newcomer:** Can you tell me more about the generated AST? What's the advantage of sealed interfaces?

**Senior:** Java 21's sealed interface enforces that "only the classes listed here can implement this type." That means `switch` expressions can cover all cases.

```java
public sealed interface TinyExpressionP4AST permits
    TinyExpressionP4AST.CodeBlockExpr,
    TinyExpressionP4AST.BinaryExpr,
    TinyExpressionP4AST.VariableRefExpr,
    // ... all 26
    TinyExpressionP4AST.ExpressionExpr {
```

**Newcomer:** If a class not listed in `permits` tries to `implements TinyExpressionP4AST`, it's a compile error?

**Senior:** Yes. And when you write a `switch` covering all cases, `default` becomes unnecessary. If you forget a case, it's a compile error -- safe.

**Newcomer:** The structure of `BinaryExpr` is a bit puzzling...

```java
record BinaryExpr(
    TinyExpressionP4AST.BinaryExpr left,
    List<String> op,
    List<TinyExpressionP4AST.BinaryExpr> right
) implements TinyExpressionP4AST {}
```

**Newcomer:** `left` is singular but `op` and `right` are lists? For `a + b + c`, does `op` become `["+", "+"]` and `right` become `[b, c]`?

**Senior:** Good question! Since `{ ... }` in UBNF is repetition, the `{ }` part of `NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right }` becomes a list.

```
Expression: 3 + 4 + 2

BinaryExpr(
    left  = BinaryExpr(null, ["3"], []),     <- leaf: numeric literal
    op    = ["+", "+"],
    right = [BinaryExpr(null, ["4"], []),     <- leaf
             BinaryExpr(null, ["2"], [])]     <- leaf
)
```

**Newcomer:** I see! `left` is the first term, and `op` and `right` are lists of the remaining operator-and-term pairs.

**Senior:** Right. The important thing here is the three encodings of BinaryExpr.

| Pattern | left | op | right | Meaning |
|---------|------|-----|-------|---------|
| **leaf** | `null` | `["3"]` | `[]` | Literal value |
| **wrap** | `BinaryExpr(...)` | `[]` | `[]` | Simple wrapper |
| **binary** | `BinaryExpr(...)` | `["+", "-"]` | `[BinaryExpr, BinaryExpr]` | Actual binary operation |

**Newcomer:** When it's a leaf, the literal value goes into `op`? Not the operator?

**Senior:** That's a slightly tricky aspect. Due to the P4 mapper design, terminal values are stored as literal strings in the `op` field. If `left = null` and `right` is empty, it's identified as a leaf.

**Newcomer:** Hmm, not very intuitive.

**Senior:** Agreed. But this is the result of directly projecting the grammar structure onto the AST. You need code on the evaluator side to detect leaf/wrap/binary, but it's a standard pattern that's the same every time.

```java
// From P4TypedAstEvaluator.java
private Number evalBinaryAsNumber(BinaryExpr node) {
    BinaryExpr left = node.left();
    List<String> op = node.op();
    List<BinaryExpr> right = node.right();

    // Leaf: left==null, op=[literal], right=[]
    if (left == null && right.isEmpty() && op.size() == 1) {
        return resolveLeafLiteral(op.get(0));
    }
    // Wrap: left!=null, op=[], right=[] -- unwrap
    if (left != null && op.isEmpty() && right.isEmpty()) {
        return evalBinaryAsNumber(left);
    }
    // Binary: left + op[i] + right[i] repeated
    Number current = evalBinaryAsNumber(left);
    int count = Math.min(op.size(), right.size());
    for (int i = 0; i < count; i++) {
        Number r = evalBinaryAsNumber(right.get(i));
        current = applyBinary(op.get(i), current, r);
    }
    return current;
}
```

**Newcomer:** Ah, I get it. First check for leaf and wrap, otherwise apply operations left to right. Since it's `@leftAssoc`, processing goes from left to right.

**Senior:** Perfect understanding!

**Newcomer:** By the way, isn't it a problem that both `NumberExpression` and `NumberTerm` map to `BinaryExpr`?

**Senior:** Good catch. This actually caused bugs during development. When the mapper encounters `NumberTerm` inside `NumberExpression`, both call `toBinaryExpr()`, but previously the mapping rule for `NumberTerm` was missing, so it wasn't mapped correctly.

```java
// TinyExpressionP4Mapper.java's mapToken()
if (token.parser.getClass() == TinyExpressionP4Parsers.NumberExpressionParser.class) {
    return toBinaryExpr(token);  // NumberExpression -> BinaryExpr
}
if (token.parser.getClass() == TinyExpressionP4Parsers.NumberTermParser.class) {
    return toBinaryExpr(token);  // NumberTerm -> BinaryExpr (this was missing!)
}
```

**Newcomer:** Since both map to `BinaryExpr`, both need to be registered in `allMappingRules`?

**Senior:** Right. The MapperGenerator collects all rules with `@mapping(BinaryExpr)` and matches them against Token parser classes. If only `NumberExpression` is registered and `NumberTerm` is forgotten, `3 * 4` won't be correctly converted to AST.

**Newcomer:** How is the `$` prefix of `VariableRefExpr` handled?

**Senior:** The `name` field of `VariableRefExpr` stores the name with `$` included. The `$` is removed at evaluation time.

```java
// VariableRef ::= '$' IDENTIFIER @name [ TypeHint ] ;
// -> VariableRefExpr(name="$price")

// Remove $ at evaluation time
private String extractVariableName(String raw) {
    if (raw != null && raw.startsWith("$")) {
        return raw.substring(1).strip();
    }
    return raw == null ? "" : raw.strip();
}
```

**Newcomer:** I see. In the UBNF grammar, `'$' IDENTIFIER @name` means `$` is just syntactic decoration, but the `@name` binding includes `$` in the `IDENTIFIER` part.

**Senior:** Precisely, it depends on how the Token text range is captured, but currently it's stored with `$` included. There are plans to support automatic removal via an annotation like `@eval(kind=variable_ref, strip_prefix="$")` in the future.

---

## Part 4: Generation Gap Pattern (GGP)

**Newcomer:** Senior, `TinyExpressionP4Evaluator` is an abstract class, right? Where do you write the actual processing?

**Senior:** This is where the **Generation Gap Pattern** comes in, or GGP for short.

**Newcomer:** Generation Gap?

**Senior:** The biggest problem with code generation is "when you regenerate, hand-written modifications are lost." If you add a new rule to UBNF and run `mvn compile`, `TinyExpressionP4Evaluator.java` gets overwritten. If there was hand-written code in that file, it's all gone.

**Newcomer:** Oh, that would be terrible...

**Senior:** GGP solves this problem with a two-layer structure.

```
[Generated code] TinyExpressionP4Evaluator<T>  <- abstract, regenerated every time
                    ^ extends
[Hand-written code] P4TypedAstEvaluator        <- concrete, written by humans, never regenerated
```

**Newcomer:** Extend the generated abstract class and create a concrete hand-written class!

**Senior:** Right. `TinyExpressionP4Evaluator<T>` is generated in `target/generated-sources/`, so it gets overwritten with every `mvn compile`. But `P4TypedAstEvaluator` is in `src/main/java/`, so human code is safe.

```java
// Generated code (placed in target/generated-sources/)
public abstract class TinyExpressionP4Evaluator<T> {
    // evalInternal()'s sealed switch is auto-generated
    // evalBinaryExpr() etc. are abstract
    protected abstract T evalBinaryExpr(BinaryExpr node);
    protected abstract T evalVariableRefExpr(VariableRefExpr node);
    // ...
}
```

```java
// Hand-written code (placed in src/main/java/)
public class P4TypedAstEvaluator extends TinyExpressionP4Evaluator<Object> {

    private final ExpressionType resultType;
    private final CalculationContext context;

    @Override
    protected Object evalBinaryExpr(BinaryExpr node) {
        return evalBinaryAsNumber(node);  // human implements this
    }

    @Override
    protected Object evalVariableRefExpr(VariableRefExpr node) {
        String varName = extractVariableName(node.name());
        return context.getNumber(varName).orElse(0);  // human implements this
    }
    // ...
}
```

**Newcomer:** I see! When a rule is added to UBNF, a new abstract method appears in `TinyExpressionP4Evaluator`, and a compile error occurs in `P4TypedAstEvaluator`, so you can't miss an implementation.

**Senior:** Exactly. With sealed interface + GGP, there's a double safety net.

**Newcomer:** Are there other concrete classes using GGP?

**Senior:** There's one more. `P4TypedJavaCodeEmitter` for code generation.

```java
// Hand-written code -- version that generates Java code strings
public class P4TypedJavaCodeEmitter extends TinyExpressionP4Evaluator<String> {

    @Override
    protected String evalBinaryExpr(BinaryExpr node) {
        // Instead of returning a number, returns a Java code string
        // "3 + 4" -> "(3.0f+4.0f)"
        BinaryExpr left = node.left();
        List<String> op = node.op();
        List<BinaryExpr> right = node.right();
        // ...
        String expr = evalBinaryExpr(left);
        for (int i = 0; i < count; i++) {
            String rightExpr = evalBinaryExpr(right.get(i));
            expr = "(" + expr + operator + rightExpr + ")";
        }
        return expr;
    }

    @Override
    protected String evalVariableRefExpr(VariableRefExpr node) {
        String varName = extractVariableName(node.name());
        return "calculateContext.getNumber(\"" + varName + "\").map(Number::floatValue).orElse(0.0f)";
    }
}
```

**Newcomer:** Two concrete classes branch from the same base class: `<Object>` and `<String>`. One computes values, the other outputs code. Fascinating!

**Senior:** The beauty of GGP is that the generator only manages which AST node types exist, and delegates each node's processing to humans. The boundary between generated and hand-written responsibilities is clear.

**Newcomer:** It's similar to ANTLR's Visitor pattern.

**Senior:** The concept is close. But ANTLR's `XxxBaseVisitor` receives an untyped `ParserRuleContext`. Ours uses sealed records, so it's type-safe. The compiler tells you "you forgot the case for `NumberMatchExpr`."

**Newcomer:** Type safety matters. You want to find issues at compile time rather than at runtime.

**Senior:** Precisely. Especially with 26 AST node types like tinyexpression, forgetting even one causes a runtime `MatchException`. Thanks to sealed interfaces, that's caught at compile time.

---

## Part 5: The 5 Backends

**Newcomer:** Senior, I heard there are actually 5 ways to evaluate expressions. Is that true?

**Senior:** True. There are historical reasons, but currently there are 5 families.

```
  Expression string "$a + $b * 2"
     |
  +--+--------------------------------------+
  |                                          |
  v                                          v
[compile family: expr->Java code->javac->exec]  [AST family: expr->AST->recursive eval]
  |                                          |
  +--[1] compile-hand                        +--[3] ast-hand
  |      JavaCodeCalculatorV3                |      AstNumberExpressionEvaluator
  |                                          |      (@TinyAstNode annotation-driven)
  +--[2] compile-dsl                         +--[4] P4-reflection
        DslJavaCodeCalculator                |      GeneratedP4ValueAstEvaluator
        -> P4TypedJavaCodeEmitter            |      (reflection, legacy)
                                             |
                                             +--[5] P4-typed *recommended
                                                    P4TypedAstEvaluator
                                                    (sealed switch, fast)
```

**Newcomer:** The compile family converts expressions to Java code and compiles them with javac. Sounds fast.

**Senior:** JIT makes it the fastest. `$a + $b * 2` becomes the following Java code, which is dynamically compiled.

```java
// Code generated by compile-hand
public class Expr_abc123 implements TokenBaseCalculator {
    @Override
    public float evaluate(CalculationContext calculateContext, Token token) {
        float answer = (float)
            (calculateContext.getNumber("a").map(Number::floatValue).orElse(0.0f)
            +(calculateContext.getNumber("b").map(Number::floatValue).orElse(0.0f)
            *2.0f));
        return answer;
    }
}
```

**Newcomer:** Since it becomes JVM bytecode, it runs at near-native speed.

**Senior:** Right. But there are downsides. Initial compilation takes time, and `javax.tools.JavaCompiler` is required. It doesn't work in JRE-only environments.

**Newcomer:** What's the difference between `compile-dsl` and `compile-hand`?

**Senior:** The code generation method differs. `compile-hand` uses legacy hand-written code generation logic (`OperatorOperandTreeCreator`). `compile-dsl` uses `P4TypedJavaCodeEmitter` via P4 AST. But both ultimately compile with javac.

```java
// DslJavaCodeCalculator.java
public class DslJavaCodeCalculator extends JavaCodeCalculatorV3 {
    @Override
    public String createJavaClass(String className, ...) {
        // Try code generation from AST using P4TypedJavaCodeEmitter
        Optional<EmittedJava> emitted = DslGeneratedAstJavaEmitter.tryEmit(...);
        if (emitted.isPresent()) {
            this.nativeEmitterUsed = true;
            return emitted.get().javaCode();
        }
        // Fall back to legacy if that doesn't work
        return super.createJavaClass(className, ...);
    }
}
```

**Newcomer:** How do the 3 AST-based backends differ?

**Senior:** `ast-hand` was the first AST evaluator built. It runs on custom AST nodes using `@TinyAstNode` annotations. Unrelated to P4 generated code.

**Newcomer:** `@TinyAstNode`?

**Senior:** Yes. A generation predating the P4 pipeline. Hand-written parsers create a Token tree, and `NumberGeneratedAstAdapter` converts it to records annotated with `@TinyAstNode`.

```java
// NumberGeneratedAstAdapter converts Token -> custom AST nodes
NumberGeneratedAstNode cachedAst = buildCachedAst("3+4+2+5-1");
Number result = AstNumberExpressionEvaluator.evaluateAst(cachedAst, ExpressionTypes._float, ctx);
```

**Newcomer:** So it's legacy.

**Senior:** Yes. Next is `P4-reflection`. It uses AST generated by the P4 mapper, but detects node types via reflection.

```java
// GeneratedP4ValueAstEvaluator (reflection version)
String rootSimpleName = mappedAst.getClass().getSimpleName();
if ("ExpressionExpr".equals(rootSimpleName)) {
    Object unwrapped = unwrapExpressionNode(mappedAst);
    return tryEvaluate(unwrapped, ...);
}
if ("BinaryExpr".equals(rootSimpleName)) {
    // ...
}
```

**Newcomer:** Using `getClass().getSimpleName()` for type detection... That must be slow.

**Senior:** It is. String matching via reflection. Compared to sealed switch pattern matching, it's orders of magnitude slower.

**Newcomer:** And `P4-typed` is the recommended version?

**Senior:** Yes. It uses GGP's `P4TypedAstEvaluator`. Dispatches via sealed switch, no reflection needed.

```java
// TinyExpressionP4Evaluator.evalInternal() (generated code)
return switch (node) {
    case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
    case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
    case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
    // ... compiler guarantees exhaustive case coverage
};
```

**Newcomer:** What are the benchmark results?

**Senior:** Measured in `BackendSpeedComparisonTest`. Literal `3+4+2+5-1` with 50,000 iterations.

```
===================================================
 Backend Speed Comparison
===================================================
iterations: 50,000  (warmup: 5,000)

--- Section 1: Literal arithmetic [3+4+2+5-1] ---
(A) compile-hand   [JVM bytecode]    :   0.0050 us/call  (baseline)
(B) ast-hand-cached[tree eval only]  :   0.0300 us/call  x6.0
(C) ast-hand-full  [parse+build+eval]:   5.0000 us/call  x1000.0
(D) P4-reflection  [mapper+reflect]  :   7.0000 us/call  x1400.0
(E) P4-typed-eval  [sealed switch]   :   0.0100 us/call  x2.0
(E2)P4-typed-reuse [instance reused] :   0.0050 us/call  x1.0
```

**Newcomer:** P4-typed-reuse matches compile-hand!? That's 1400x faster than the reflection version!

**Senior:** Right. When the evaluator instance is reused, it approaches JIT-compiled bytecode speed. The reflection version has string matching on every method call, making it orders of magnitude slower.

**Newcomer:** There's no reason not to use P4-typed.

**Senior:** That's right for now. That's why P4-reflection is treated as deprecated. New code should always use P4-typed.

**Newcomer:** What about variable expressions?

**Senior:** For the variable expression `$a+$b+$c+$d-$e`:

```
--- Section 2: Variable formula [$a+$b+$c+$d-$e] ---
(F) compile-hand   [JVM bytecode]    :   0.0080 us/call  (baseline)
(G) AstEvalCalc    [full path]       :   0.5000 us/call  x62.5
(H) P4-typed-var   [sealed switch]   :   0.0300 us/call  x3.75
```

**Newcomer:** P4-typed is 3.75x compile-hand. For not needing compilation, this speed is practically useful.

**Senior:** Plus compile-hand has an initial compilation cost (javac invocation) of tens of milliseconds. P4-typed only needs parsing, so it's fast even the first time. For repeated execution, compile-hand wins; for one-shot, P4-typed is better.

**Newcomer:** What about code generation performance?

**Senior:** Look at Section 3.

```
--- Section 3: Code generation [10,000 iterations] ---
(I) P4-typed-emit  [literal]         :   0.5000 us/call
(J) P4-typed-emit  [variable]        :   0.8000 us/call
```

**Newcomer:** Under 1 microsecond for code generation? Fast.

**Senior:** `P4TypedJavaCodeEmitter` is just string concatenation. The actual javac compilation cost isn't included, but the code generation portion alone is instant.

---

## Part 6: @eval Strategy Design

**Newcomer:** Senior, I understand that GGP keeps hand-written code safe, but isn't it tedious to hand-write all 26 `evalXxx()` methods in `P4TypedAstEvaluator`?

**Senior:** It is. Moreover, the leaf/wrap/binary detection for `BinaryExpr` is a completely fixed pattern. `VariableRefExpr` processing is always "get the name, strip `$`, look up the value from context."

**Newcomer:** Making people hand-write boilerplate code... that violates the DRY principle.

**Senior:** Exactly. That's why I'm designing the `@eval` annotation. A proposal to be able to write evaluation strategies in UBNF.

```ubnf
// Proposal: @eval annotation
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@precedence(level=10)
@eval(kind=binary_arithmetic, strategy=default)
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**Newcomer:** What are `kind` and `strategy` in `@eval`?

**Senior:** `kind` describes what type of processing the rule performs. `binary_arithmetic` means binary arithmetic operation. `strategy` is the code generation method.

| Strategy | Description |
|----------|-------------|
| `default` | Generator produces standard implementation |
| `template("file.java.tmpl")` | Expanded from external template |
| `manual` | Left as abstract, human writes it |

**Newcomer:** With `default`, the generator handles everything?

**Senior:** Yes. With `@eval(kind=binary_arithmetic, strategy=default)`, the generator produces all the code for leaf/wrap/binary detection and `applyBinary()` calls. No hand-writing needed.

```java
// evalBinaryExpr() generated by strategy=default (image)
@Override
protected Object evalBinaryExpr(BinaryExpr node) {
    BinaryExpr left = node.left();
    List<String> op = node.op();
    List<BinaryExpr> right = node.right();

    if (left == null && right.isEmpty() && op.size() == 1) {
        return resolveLeafLiteral(op.get(0));  // leaf
    }
    if (left != null && op.isEmpty() && right.isEmpty()) {
        return eval(left);  // wrap
    }
    Object current = eval(left);
    for (int i = 0; i < Math.min(op.size(), right.size()); i++) {
        Object r = eval(right.get(i));
        current = applyBinary(op.get(i), current, r);
    }
    return current;
}
```

**Newcomer:** That's almost identical to the current `P4TypedAstEvaluator` code!

**Senior:** Right. Most of the currently hand-written code is boilerplate patterns. Once `@eval` is implemented, 80% of methods can be auto-generated.

**Newcomer:** When would you use the `template` strategy?

**Senior:** When custom processing is needed that standard patterns can't handle. For example, specifying rounding modes with BigDecimal.

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@precedence(level=20)
@eval(kind=binary_arithmetic, strategy=template("custom-term-eval.java.tmpl"))
NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;
```

Placeholders can be used in template files.

```java
// custom-term-eval.java.tmpl
BigDecimal current = toBigDecimal({{eval(left)}});
for (int i = 0; i < {{op}}.size(); i++) {
    BigDecimal r = toBigDecimal({{eval(right[i])}});
    current = current.{{op[i] == "*" ? "multiply" : "divide"}}(r,
        new MathContext({{context}}.scale(), {{context}}.roundingMode()));
}
return current;
```

**Newcomer:** You can write `{{eval(left)}}` in the template!

**Senior:** Yes. The template engine expands placeholders into Java code. It balances the freedom of hand-writing with the efficiency of code generation.

**Newcomer:** `manual` is the same as now. Left as abstract for humans to write.

**Senior:** Right. For method invocation resolution, external service integration, processing that doesn't fit into patterns.

```ubnf
@mapping(MethodInvocationExpr, params=[name])
@eval(kind=invocation, strategy=manual)
MethodInvocation ::= MethodInvocationHeader IDENTIFIER @name '(' [ Arguments ] ')' ;
```

**Newcomer:** The kind list is interesting too.

| kind | Description |
|------|-------------|
| `binary_arithmetic` | Left-associative binary operation |
| `variable_ref` | Variable reference (including `$` prefix strip) |
| `conditional` | if/else branching |
| `match_case` | Pattern matching |
| `literal` | Literal value |
| `comparison` | Comparison operation |
| `invocation` | Method invocation |
| `passthrough` | Return value as-is |

**Newcomer:** When `@eval` is implemented, will the GGP class hierarchy change?

**Senior:** It will. It becomes a 3-layer structure.

```
TinyExpressionP4Evaluator<T>           <- generated (abstract, sealed switch dispatch)
  |
  +-- P4DefaultAstEvaluator<Object>    <- generated (@eval default/template implementations)
  |     evalBinaryExpr()               <- generated by @eval(kind=binary_arithmetic)
  |     evalVariableRefExpr()          <- generated by @eval(kind=variable_ref)
  |     evalMethodInvocationExpr()     <- abstract (strategy=manual)
  |
  +-- MyCustomEvaluator<Object>        <- hand-written (extends P4DefaultAstEvaluator)
        evalMethodInvocationExpr()     <- manual implementation
        evalBinaryExpr()               <- overridable if needed
```

**Newcomer:** A middle layer `P4DefaultAstEvaluator` gets generated, reducing the abstract methods in the human-written class!

**Senior:** Yes. Out of 26, only the 2-3 marked `manual` would need hand-writing. A dramatic improvement in developer experience.

**Newcomer:** You mentioned JavaCodeBuilder too, right?

**Senior:** Ah, the code generation side. Currently `P4TypedJavaCodeEmitter` builds code via string concatenation, but `JavaCodeBuilder` could make it more structural. A builder class that automatically handles indentation and import management.

```java
// Current code (string concatenation)
expr = "(" + expr + operator + rightExpr + ")";

// With JavaCodeBuilder (concept)
builder.expression(() -> {
    builder.binary(expr, operator, rightExpr);
});
```

**Newcomer:** String concatenation is definitely error-prone. Especially matching parentheses.

**Senior:** Especially with deep nesting. `@eval` + JavaCodeBuilder should improve code generation quality too.

---

## Part 7: LSP Integration

**Newcomer:** Senior, LSP is the Language Server Protocol, right? The thing for editor autocompletion and error display. Can a LSP server be generated from UBNF too?

**Senior:** Yes. `LSPGenerator` generates the entire LSP server Java code from the UBNF grammar declaration.

```java
// LSPGenerator.java (unlaxer-parser side)
public class LSPGenerator implements CodeGenerator {
    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String serverClass = grammarName + "LanguageServer";
        // ...
    }
}
```

**Newcomer:** What gets generated?

**Senior:** `TinyExpressionP4LanguageServer.java` is generated. It's an lsp4j-based LSP server with these auto-generated features:

1. **Keyword completion** -- Automatically collected from keyword literals in the grammar
2. **Real-time syntax diagnostics** -- Parses using the generated parser and returns error positions as Diagnostics
3. **Hover information** -- Displays token type and corresponding grammar rule
4. **Semantic tokens** -- Color-codes valid/invalid tokens

**Newcomer:** Keyword completion is pulled from UBNF literals?

**Senior:** Yes. `LSPGenerator` traverses all grammar rules and collects literals enclosed in single quotes.

```java
// Part of generated code
private static final List<String> KEYWORDS = List.of(
    "import", "as", "var", "variable", "set", "if", "not", "exists",
    "description", "match", "default", "true", "false",
    "external", "returning", "call", "internal", "else",
    "number", "float", "string", "boolean", "object"
);
```

**Newcomer:** The `'import'` and `'var'` written in UBNF become the keyword list directly!

**Senior:** Right. No need for humans to maintain a keyword list. When you add a keyword to the grammar, the next `mvn compile` auto-updates the LSP keyword completion.

**Newcomer:** How does the diagnostics feature work?

**Senior:** Every time the user changes text in the editor, `parseDocument()` is called. It parses using the generated parser and returns error positions as Diagnostics on failure.

```java
// Generated code -- parseDocument()
public ParseResult parseDocument(String uri, String content) {
    Parser parser = TinyExpressionP4Parsers.getRootParser();
    ParseContext context = new ParseContext(createRootSourceCompat(content));
    Parsed result = parser.parse(context);
    int consumedLength = result.isSucceeded()
        ? result.getConsumed().source.sourceAsString().length() : 0;
    // ...
    if (client != null) {
        publishDiagnostics(uri, content, parseResult);
    }
    return parseResult;
}
```

**Newcomer:** If the parse result's `consumedLength` doesn't match the full source length, it's an error?

**Senior:** Right. If `consumedLength < content.length()`, everything from that position onward couldn't be parsed. A red squiggly line appears in VSCode.

**Newcomer:** What are semantic tokens?

**Senior:** An LSP feature that provides semantic coloring to tokens. The generated code defines two types: "valid token" and "invalid token."

```java
// Generated code
semanticTokensOptions.setLegend(new SemanticTokensLegend(
    List.of("valid", "invalid"), List.of()));
```

**Newcomer:** That's different from TextMate syntax highlighting?

**Senior:** Different. TextMate is static highlighting based on regular expressions. Semantic tokens are dynamic highlighting based on parser analysis results. The accuracy is different.

**Newcomer:** Senior, an honest question... How much work would it take to do the same thing with ANTLR?

**Senior:** To build a full-featured LSP server with ANTLR, you'd need thousands of lines of hand-written code. With incremental parsing and error recovery, potentially tens of thousands of lines.

**Newcomer:** And with UBNF it's generated in one shot...

**Senior:** Well, it's not as refined as ANTLR's LSP capabilities at this stage. Error recovery is insufficient since it's PEG-based. But the experience of "auto-generating an LSP from a single grammar file" is unique.

---

## Part 8: DAP Integration

**Newcomer:** After LSP comes DAP. That's the Debug Adapter Protocol, right?

**Senior:** Yes. `DAPGenerator` generates a DAP server from UBNF. Step execution and breakpoints for expressions work in VSCode.

```java
// DAPGenerator.java
public class DAPGenerator implements CodeGenerator {
    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String adapterClass = grammarName + "DebugAdapter";
        // ...
    }
}
```

**Newcomer:** Does an expression language even need a debugger?

**Senior:** With complex expressions, yes. When match expressions are nested or external method calls are involved, you want to trace what's happening where.

**Newcomer:** What can the generated DAP server do?

**Senior:** It's documented in `DAPGenerator`'s comments. There are two modes.

```
stopOnEntry: false (default)
  launch -> configurationDone -> parse -> output result to Debug Console -> terminated

stopOnEntry: true (step execution)
  launch -> configurationDone -> parse -> stopped(entry)
  -> [F10] next -> stopped(step) -> ... -> terminated
  -> [F5]  continue -> terminated
```

**Newcomer:** With `stopOnEntry: true`, you can step through! Advance one step at a time with F10 in VSCode.

**Senior:** Right. Each token in the parse tree becomes a step point. The current token's line and column are returned as a stack trace, which gets highlighted in the editor.

```java
// Generated code -- configurationDone()
if (stopOnEntry && !stepPoints.isEmpty()) {
    StoppedEventArguments stopped = new StoppedEventArguments();
    stopped.setReason("entry");
    stopped.setThreadId(1);
    stopped.setAllThreadsStopped(true);
    client.stopped(stopped);
}
```

**Newcomer:** Can you use breakpoints too?

**Senior:** Yes. Breakpoints are set via `setBreakpoints()` at specified lines, and execution stops when a token on that line is reached.

```java
// Generated code -- breakpoint handling
} else if (!breakpointLines.isEmpty()) {
    int bp = findBreakpointIndex(-1);
    if (bp >= 0) {
        stepIndex = bp;
        StoppedEventArguments stopped = new StoppedEventArguments();
        stopped.setReason("breakpoint");
        stopped.setThreadId(1);
        stopped.setAllThreadsStopped(true);
        client.stopped(stopped);
    }
}
```

**Newcomer:** Amazing. Getting a debugger just by writing grammar...

**Senior:** What's even more interesting is the `DebugStrategy` built into `TinyExpressionP4Evaluator`.

```java
// TinyExpressionP4Evaluator.java (generated code)
public interface DebugStrategy {
    void onEnter(TinyExpressionP4AST node);
    void onExit(TinyExpressionP4AST node, Object result);

    DebugStrategy NOOP = new DebugStrategy() {
        public void onEnter(TinyExpressionP4AST node) {}
        public void onExit(TinyExpressionP4AST node, Object result) {}
    };
}
```

**Newcomer:** `onEnter` and `onExit` let you monitor entering and exiting AST nodes. It's the Strategy pattern!

**Senior:** Default is `NOOP` which does nothing. The implementation is swapped only during debugging. `StepCounterStrategy` is an example.

```java
// TinyExpressionP4Evaluator.java (generated code)
public static class StepCounterStrategy implements DebugStrategy {
    private int step = 0;
    private final java.util.function.BiConsumer<Integer, TinyExpressionP4AST> onStep;

    public StepCounterStrategy(
        java.util.function.BiConsumer<Integer, TinyExpressionP4AST> onStep
    ) {
        this.onStep = onStep;
    }

    @Override
    public void onEnter(TinyExpressionP4AST node) {
        onStep.accept(step++, node);
    }

    @Override
    public void onExit(TinyExpressionP4AST node, Object result) {}
}
```

**Newcomer:** `step++` counts step numbers and notifies the callback. This is how DAP's "next step" is realized.

**Senior:** Right. Since `debugStrategy.onEnter(node)` is called inside the evaluator's `eval()` method, there's no overhead during normal execution (NOOP), and step counting only runs during debugging.

```java
// TinyExpressionP4Evaluator.eval() -- generated code
public T eval(TinyExpressionP4AST node) {
    debugStrategy.onEnter(node);      // <- debug hook
    T result = evalInternal(node);
    debugStrategy.onExit(node, result); // <- debug hook
    return result;
}
```

**Newcomer:** Not impacting performance is important.

**Senior:** `TinyExpressionDapRuntimeBridge` bridges DAP and actual expression evaluation.

```java
// TinyExpressionDapRuntimeBridge.java
public static Map<String, String> debugVariables(String formulaSource, String runtimeMode) {
    LinkedHashMap<String, String> vars = new LinkedHashMap<>();
    vars.put("bridgeAttached", "true");

    ExecutionBackend backend =
        ExecutionBackend.fromRuntimeMode(runtimeMode).orElse(ExecutionBackend.JAVA_CODE);
    vars.put("selectedExecutionBackend", backend.name());

    Calculator calculator = CalculatorCreatorRegistry.forBackend(backend).create(...);
    Object value = calculator.apply(CalculationContext.newConcurrentContext());
    vars.put("evaluationResult", String.valueOf(value));
    // ...
}
```

**Newcomer:** Do evaluation results and backend info show up in DAP's Variables pane?

**Senior:** Yes. The Map returned by `debugVariables()` is displayed in VSCode's Variables view. You can see which backend is being used, what the evaluation result is, whether the P4 mapper was available, and more.

**Newcomer:** Can you change `runtimeMode` during debugging to compare?

**Senior:** You can specify `runtimeMode` in launch.json. `"token"` for token level, `"p4-ast"` for P4 AST level, `"javacode"` for code generation path, etc.

**Newcomer:** LSP and DAP auto-generated from grammar... that goes beyond what a parser generator does.

**Senior:** unlaxer-parser's goal is an "integrated development environment for DSL development." A world where writing grammar alone gives you parser, AST, evaluator, LSP, and DAP. Still a work in progress, but the direction is clear.

---

## Part 9: Putting It All Together

**Newcomer:** Senior, can you summarize the overall flow? From when a user writes an expression to when the result comes back.

**Senior:** OK. Let's say the expression `"$price * 1.1"` comes in. The overall flow looks like this.

```
1. User inputs expression: "$price * 1.1"
          |
2. AstEvaluatorCalculator is the entry point
          |
   +------+------------------------------+
   |                                      |
   v                                      v
3a. P4 AST path                        3b. JavaCode fallback path
   |                                      |
   TinyExpressionP4Mapper.parse()         JavaCodeCalculatorV3
   |                                      |
   Token tree -> sealed AST               expr -> Java code -> javac -> .class
   |                                      |
   P4TypedAstEvaluator.eval()             .class.evaluate(context)
   |                                      |
   +----------+---------------------------+
              |
4. Retrieve $price value from CalculationContext
              |
5. Return calculation result
```

**Newcomer:** `AstEvaluatorCalculator` first tries the P4 AST path and falls back to JavaCode if it fails?

**Senior:** Exactly.

```java
// AstEvaluatorCalculator.java
public class AstEvaluatorCalculator implements Calculator {

    private final boolean generatedAstRuntimeAvailable;
    private volatile JavaCodeCalculatorV3 delegate;

    public AstEvaluatorCalculator(Source source, String className,
        SpecifiedExpressionTypes specifiedExpressionTypes, ClassLoader classLoader) {
        // ...
        this.generatedAstRuntimeAvailable = GeneratedAstRuntimeProbe.isAvailable(classLoader);
    }
}
```

**Newcomer:** `GeneratedAstRuntimeProbe.isAvailable()` checks whether the P4 runtime is available.

**Senior:** Right. If P4 generated code is on the classpath, the P4 path is used. Otherwise, it falls back to the traditional JavaCode path. This maintains backward compatibility.

**Newcomer:** What does the `apply()` method look like inside?

**Senior:** Roughly like this.

```java
// AstEvaluatorCalculator.apply() overview
@Override
public CalculateResult apply(CalculationContext context) {
    if (generatedAstRuntimeAvailable) {
        // Try P4 AST path
        try {
            TinyExpressionP4AST ast = TinyExpressionP4Mapper.parse(source.expression());
            P4TypedAstEvaluator evaluator = new P4TypedAstEvaluator(specifiedExpressionTypes, context);
            Object result = evaluator.eval(ast);
            return new CalculateResult(result);
        } catch (Exception e) {
            // fallthrough to delegate
        }
    }
    // JavaCode fallback
    return ensureDelegate().apply(context);
}
```

**Newcomer:** try-catch to fall back if the P4 path fails. Robust design.

**Senior:** During the transition period, we always have this dual structure. Expressions that the P4 path can't cover (complex match expressions, external method calls, etc.) are still handled by the JavaCode path.

**Newcomer:** What fixes were made to the P4 mapper?

**Senior:** There were mainly two major fixes.

1. **Expression ordering fix** -- Put `NumberExpression` first in the `Expression` rule

```ubnf
  // NumberExpression first: matches hand-written ExpressionsParser ordering.
  // NumberExpression consumes "$a+$b" fully; BooleanExpression would only consume "$a".
  @mapping(ExpressionExpr, params=[value])
  Expression ::=
      NumberExpression @value     <- try this first
    | BooleanExpression @value
    | StringExpression @value
    | ObjectExpression @value
    | MethodInvocation @value
    | '(' Expression @value ')' ;
```

**Newcomer:** Why does order matter?

**Senior:** Because PEG uses ordered choice. If `BooleanExpression` matches first on `$a+$b`, it only consumes `$a` and leaves `+$b` remaining. If `NumberExpression` is tried first, it consumes all of `$a+$b`.

**Newcomer:** Ah, PEG "adopts the first matching alternative," so order has semantic meaning.

**Senior:** Right. This actually manifested as a bug. In tests, `$a+$b` caused a parse error, and tracing the cause led to the order of alternatives in `Expression`.

2. **Term decomposition fix** -- Map `NumberTerm` to `BinaryExpr` too

**Senior:** The `allMappingRules` issue I discussed in Part 3. Since both `NumberExpression` and `NumberTerm` have `@mapping(BinaryExpr)`, the mapper needs to recognize both parser classes, or multiplication/division won't be mapped.

**Newcomer:** Did these fixes make all tests pass?

**Senior:** `AstEvaluatorTest` went from 38/49 to 49/49. The remaining 11 were mainly resolved by these two fixes.

**Newcomer:** The `ExecutionBackend` enum manages all backends, right?

```java
public enum ExecutionBackend {
    JAVA_CODE,                    // compile-hand
    JAVA_CODE_LEGACY_ASTCREATOR,  // legacy
    AST_EVALUATOR,                // ast-hand
    DSL_JAVA_CODE,                // compile-dsl
    P4_AST_EVALUATOR,             // P4-typed *recommended
    P4_DSL_JAVA_CODE;             // P4-typed code generation

    public String runtimeModeMarker() {
        return switch (this) {
            case JAVA_CODE -> "javacode";
            case AST_EVALUATOR -> "ast-evaluator";
            case P4_AST_EVALUATOR -> "p4-ast";
            case P4_DSL_JAVA_CODE -> "p4-dsl-javacode";
            // ...
        };
    }
}
```

**Newcomer:** The DAP `runtimeMode` parameter maps to this enum.

**Senior:** Right. `TinyExpressionDapRuntimeBridge` converts the `runtimeMode` string to `ExecutionBackend` and creates the appropriate Calculator.

```java
// TinyExpressionDapRuntimeBridge.java
ExecutionBackend backend =
    ExecutionBackend.fromRuntimeMode(runtimeMode).orElse(ExecutionBackend.JAVA_CODE);
Calculator calculator = CalculatorCreatorRegistry.forBackend(backend).create(
    new Source(formulaSource), "Probe", types, classLoader);
```

**Newcomer:** The big picture is coming together. To summarize:

1. **UBNF** is the starting point for everything. Grammar + annotations
2. **4 generators** produce parser, AST, mapper, and evaluator
3. **GGP** separates generated code from hand-written code
4. **sealed interface** enables type-safe pattern matching
5. **5 backends** evaluate the same expression in different ways
6. **LSP/DAP** are also auto-generated from the grammar
7. **AstEvaluatorCalculator** integrates everything as the orchestrator

**Senior:** A perfect summary! This is the full architecture of tinyexpression + unlaxer-parser.

**Newcomer:** What's the future roadmap?

**Senior:** Three big items.

1. **Implement the `@eval` annotation** -- Dramatically reduce hand-written code
2. **Expand P4 path coverage** -- P4 support for match expressions, method calls, external calls
3. **v1.5.0 release** -- Confirm all tests green, then tag

**Newcomer:** Once `@eval` is implemented, creating new DSLs will be so much easier. You'll get a nearly working language processor just by writing UBNF.

**Senior:** Right. The ultimate goal is "a world where writing UBNF gives you a DSL environment with editor support in 30 minutes."

**Newcomer:** That's ambitious. But all the pieces you explained today are already in place. It's just connecting them.

**Senior:** Right. The parts are all here. `@eval` is the final piece.

**Newcomer:** Senior, thank you so much for today. I've grasped the big picture!

**Senior:** You're welcome. Ask anytime you have questions. Also, try running `BackendSpeedComparisonTest` yourself. Seeing the numbers deepens understanding.

```bash
cd /home/opa/work/tinyexpression
mvn test -Dtest=BackendSpeedComparisonTest -Dexec.skip=true
```

**Newcomer:** Will do!

---

## Appendix: File Map

List of key files referenced in this tutorial.

### UBNF Grammar

| File | Description |
|------|-------------|
| `tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf` | tinyexpression UBNF grammar definition |

### Generated Code (target/generated-sources/)

| File | Generator |
|------|-----------|
| `TinyExpressionP4Parsers.java` | ParserGenerator |
| `TinyExpressionP4AST.java` | ASTGenerator |
| `TinyExpressionP4Mapper.java` | MapperGenerator |
| `TinyExpressionP4Evaluator.java` | EvaluatorGenerator |

### Hand-written Code (src/main/java/)

| File | Description |
|------|-------------|
| `P4TypedAstEvaluator.java` | GGP concrete: AST evaluator |
| `P4TypedJavaCodeEmitter.java` | GGP concrete: Java code generation |
| `AstEvaluatorCalculator.java` | Backend orchestrator |
| `JavaCodeCalculatorV3.java` | compile-hand backend |
| `DslJavaCodeCalculator.java` | compile-dsl backend |
| `AstNumberExpressionEvaluator.java` | ast-hand backend |
| `GeneratedP4ValueAstEvaluator.java` | P4-reflection backend (deprecated) |
| `TinyExpressionDapRuntimeBridge.java` | DAP runtime bridge |
| `ExecutionBackend.java` | Backend enumeration |

### unlaxer-parser (Generator Side)

| File | Description |
|------|-------------|
| `ParserGenerator.java` | Parser combinator generation |
| `ASTGenerator.java` | sealed interface + records generation |
| `MapperGenerator.java` | Token -> AST mapper generation |
| `EvaluatorGenerator.java` | GGP base class generation |
| `LSPGenerator.java` | LSP server generation |
| `DAPGenerator.java` | DAP server generation |
| `CodegenMain.java` | CLI entry point |
| `CodegenRunner.java` | Generator orchestrator |

### Tests

| File | Description |
|------|-------------|
| `BackendSpeedComparisonTest.java` | 5-backend speed comparison |

---
[Index](./INDEX.md)
