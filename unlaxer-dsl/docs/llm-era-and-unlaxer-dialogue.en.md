[English](./llm-era-and-unlaxer-dialogue.en.md) | [日本語](./llm-era-and-unlaxer-dialogue.ja.md) | [Index](./INDEX.ja.md)

---

# The LLM Era and Unlaxer -- A Dialogue on "Do We Still Need Frameworks?"

> **Characters**
> - **Senior**: The designer of unlaxer-parser and tinyexpression. Built the code generation pipeline from scratch. Has a knack for self-deprecating humor
> - **Newcomer**: Comfortable with Java, but convinced LLMs can do everything. Doesn't hold back on the tough questions

---

## Part 1: Can LLMs Write Language Processing Systems?

**Newcomer:** Senior, can I ask you something? Recently I told ChatGPT "make a calculator language" and it produced a decent result. Parser, evaluator, the whole thing. Doesn't that make Unlaxer unnecessary?

**Senior:** Oh, going straight for the existential question.

**Newcomer:** Sorry, but I had to know. Because if you say "write a four-function arithmetic parser using recursive descent," it actually produces working code.

**Senior:** Yeah, it does. Let's take a look. If you actually ask an LLM "write a four-function arithmetic parser in Java, with an AST too," you get something like this.

```java
// A four-function arithmetic parser written raw by an LLM (typical output)
public class Calculator {
    private String input;
    private int pos;

    public double evaluate(String expression) {
        this.input = expression;
        this.pos = 0;
        double result = parseExpression();
        if (pos < input.length()) {
            throw new RuntimeException("Unexpected character: " + input.charAt(pos));
        }
        return result;
    }

    private double parseExpression() {
        double left = parseTerm();
        while (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
            char op = input.charAt(pos++);
            double right = parseTerm();
            if (op == '+') left += right;
            else left -= right;
        }
        return left;
    }

    private double parseTerm() {
        double left = parseFactor();
        while (pos < input.length() && (input.charAt(pos) == '*' || input.charAt(pos) == '/')) {
            char op = input.charAt(pos++);
            double right = parseFactor();
            if (op == '*') left *= right;
            else left /= right;
        }
        return left;
    }

    private double parseFactor() {
        if (input.charAt(pos) == '(') {
            pos++; // skip '('
            double result = parseExpression();
            pos++; // skip ')'
            return result;
        }
        // Parse number
        int start = pos;
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        return Double.parseDouble(input.substring(start, pos));
    }
}
```

**Newcomer:** See, it works just fine! Recursive descent, with operator precedence. What's the problem?

**Senior:** It works. As a "four-function arithmetic calculator." Now here's a question. Can you add variables?

**Newcomer:** Just ask the LLM, right? "Add a variable `$x`."

**Senior:** Yeah, it'll add it. Now, add a ternary operator `condition ? a : b`. After that, `if/else` too. String types too. Comparison operators too. Method definitions too. External Java method calls too. Type hints too. Import statements too.

**Newcomer:** ...You want all of that added?

**Senior:** The tinyexpression UBNF grammar is over 350 lines and defines 26 types of AST nodes. What do you think happens if you tell the LLM "add everything"?

**Newcomer:** Hmm... it'll probably break partway through.

**Senior:** Exactly. Let's organize what LLMs are good at and what they struggle with.

| What LLMs Are Good At | What LLMs Struggle With |
|:---|:---|
| Code generation following known patterns | Maintaining consistent architecture |
| Implementing short functions | Managing consistency across 20+ node types |
| Using well-documented APIs | "Inventing" new structures |
| Localized bug fixes | Keeping parser/AST/mapper/evaluator in sync |

**Newcomer:** What do you mean by "struggling to invent structures"?

**Senior:** LLMs are good at reproducing patterns from their training data. But a structure like "design a 26-node AST with sealed interfaces, generate an Evaluator base class with exhaustive switches, and separate hand-written parts using GGP" doesn't come out unless you ask for it. And even when you do, consistency breaks down midway.

**Newcomer:** But if you tell it to do it, won't it...

**Senior:** It will. But let's compare. Here's what the same thing looks like with Unlaxer.

```ubnf
grammar TinyExpressionP4 {

  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser

  @root
  Formula ::= { CodeBlock } { ImportDeclaration } { VariableDeclaration }
              { Annotation } [ Expression ] { MethodDeclaration } ;

  // Arithmetic
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=20)
  NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;

  // Variable reference
  @mapping(VariableRefExpr, params=[name])
  VariableRef ::= '$' IDENTIFIER @name [ TypeHint ] ;

  // Ternary operator
  @mapping(TernaryExpr, params=[condition, thenExpr, elseExpr])
  TernaryExpression ::=
    BooleanFactor @condition '?' NumberExpression @thenExpr ':' NumberExpression @elseExpr ;

  // ... remaining 300 lines of grammar definition
}
```

**Newcomer:** A single grammar file.

**Senior:** From these 350 lines of UBNF, the following are auto-generated.

| Generated Artifact | Content | Lines (approx.) |
|:---|:---|:---|
| `TinyExpressionP4Parsers.java` | Parser combinator chain | ~800 lines |
| `TinyExpressionP4AST.java` | sealed interface + 26 records | ~400 lines |
| `TinyExpressionP4Mapper.java` | Token -> AST conversion | ~600 lines |
| `TinyExpressionP4Evaluator.java` | abstract base class (for GGP) | ~200 lines |
| LSP/DAP servers | Language Server + Debugger | ~500 lines |

**Newcomer:** Over 2,500 lines from 350 lines of grammar...

**Senior:** And they're all connected by types. If you add one AST record, compile errors appear in both the Mapper and the Evaluator, telling you about missing implementations. Try doing that with an LLM-written Calculator class?

**Newcomer:** You'd have to manually maintain consistency across everything.

**Senior:** Exactly. An LLM can answer "make me a calculator." But it can't answer "design me a language processing system." Pattern reproduction and structural design are different things.

---

## Part 2: Token Efficiency

**Newcomer:** But Senior, honestly, if you just keep asking the LLM, won't you eventually get there? It might take a while, but still.

**Senior:** You will. The question is "how much will it cost?" Let's talk about token efficiency.

**Newcomer:** Tokens? You mean API costs?

**Senior:** That too, but there's a more fundamental point. Interactions with LLMs are measured in "tokens." Both input and output are counted in tokens. This directly impacts costs and also consumes context window.

Let's look at the actual data. The token count for building the P4 backend for tinyexpression.

**Newcomer:** You measured it?

**Senior:** Roughly.

### Without a Framework, Having the LLM Write Everything

```
Estimated token consumption: ~30,000 tokens

Breakdown:
  - Explaining/requesting initial implementation    5,000 tokens
  - LLM-generated code                             8,000 tokens
  - Bug reports + error messages                    3,000 tokens
  - LLM's fix code                                 4,000 tokens
  - Second bug report                              2,000 tokens
  - Second fix                                     3,000 tokens
  - Third bug + fix                                3,000 tokens
  - Test result feedback                           2,000 tokens
  ──────────────────────────────────────────────
  Bug fix round-trips:     ~10,000 tokens (33% of total)
```

**Newcomer:** 33% on bug fixes!

**Senior:** Right. Code written by LLMs inevitably has bugs like "parser precedence is reversed," "AST node type mismatch," "missing switch case." You report them, get fixes, new bugs appear... this back-and-forth eats tokens.

### With Unlaxer + LLM

```
Actual token consumption: ~3,000 tokens

Breakdown:
  - "Implement P4TypedAstEvaluator"                 500 tokens
  - LLM's implementation code                     1,200 tokens
  - "Do P4TypedJavaCodeEmitter too"                 300 tokens
  - LLM's implementation code                       800 tokens
  - Minor bug fix, 1 round                          200 tokens
  ──────────────────────────────────────────────
  Bug fix round-trips:       ~200 tokens (less than 5% of total)
```

**Newcomer:** One-tenth! But... why is the difference so big?

**Senior:** Three reasons.

**Reason 1: Types serve as guardrails.**

The `TinyExpressionP4Evaluator<T>` generated by Unlaxer is an abstract class where all 26 `evalXxx()` methods are defined as abstract. The LLM just needs to be told "implement all of these methods." It doesn't have to figure out what to implement.

```java
// Generated code: TinyExpressionP4Evaluator.java
public abstract class TinyExpressionP4Evaluator<T> {

    public T eval(TinyExpressionP4AST node) {
        return switch (node) {
            case BinaryExpr n       -> evalBinaryExpr(n);
            case VariableRefExpr n  -> evalVariableRefExpr(n);
            case IfExpr n           -> evalIfExpr(n);
            case TernaryExpr n      -> evalTernaryExpr(n);
            case SinExpr n          -> evalSinExpr(n);
            case StringConcatExpr n -> evalStringConcatExpr(n);
            // ... all 26 cases, compiler guarantees exhaustiveness
        };
    }

    protected abstract T evalBinaryExpr(BinaryExpr node);
    protected abstract T evalVariableRefExpr(VariableRefExpr node);
    protected abstract T evalIfExpr(IfExpr node);
    // ... all 26 methods
}
```

The LLM sees the signatures of these abstract methods and implements them one by one. It doesn't need to guess "what AST nodes exist."

**Reason 2: Bugs don't occur at the structural level.**

Inconsistencies between parser and AST are detected at generation time. Missing mapper conversions become compile errors. If the LLM "forgets one switch case," the compiler catches it. So bug-fix round-trips are drastically reduced.

**Reason 3: The context is small.**

All you pass to the LLM is "the generated abstract class" and "implement it like this." You don't need to pass the parser mechanics, the AST structure, or the mapper implementation. The framework handles all of that.

**Newcomer:** Makes sense... but learning Unlaxer also costs tokens, right? There's the cost of explaining "What is Unlaxer? What is UBNF?" to the LLM.

**Senior:** Sharp point. There is a first-time cost. Conveying the basic concepts of Unlaxer to the LLM takes about 500-1,000 tokens. But that's a one-time cost.

Think about it. Without a framework, every feature addition triggers round-trips through "parser fix -> AST fix -> mapper fix -> evaluator fix." With Unlaxer, you just modify the UBNF and regenerate. The cost gap widens with every addition.

```
Cumulative token cost:

Feature additions    Without framework    Unlaxer + LLM
────────────────    ──────────────────    ──────────────
1st                 30,000                3,000 + 1,000 (learning) = 4,000
2nd                 25,000                2,500
3rd                 25,000                2,500
4th                 20,000                2,000
5th                 20,000                2,000
────────────────    ──────────────────    ──────────────
Total              120,000               13,000
```

**Newcomer:** Almost a 10x difference after 5 feature additions...

**Senior:** And the framework-free approach also carries regression risk -- "the previous bug fix introduced a new bug." With Unlaxer, regenerated code is always consistent with the grammar, so there's no regression.

**Newcomer:** The accumulated overhead is overwhelmingly smaller -- that's what you're saying.

**Senior:** Right. LLM tokens are getting cheaper, but they'll never be "zero." A 10x efficiency gap doesn't disappear no matter how cheap the per-token price gets.

---

## Part 3: The Power of Types

**Newcomer:** Senior, you mentioned "types as guardrails" earlier, but can't you achieve the same thing with tests? If you drive LLM code generation with TDD, you can find bugs too.

**Senior:** Good question. Let me explain the difference between tests and types.

**Newcomer:** Is there a difference? They're both "ways to verify code is correct," right?

**Senior:** Tests are **verification**. Types are **proof**. Verification and proof are different things.

**Newcomer:** That sounds philosophical... can you be more specific?

**Senior:** OK, let's look at the four type-safety mechanisms Unlaxer uses.

### Mechanism 1: Exhaustive switch on sealed interfaces

```java
// TinyExpressionP4AST.java (generated code)
public sealed interface TinyExpressionP4AST permits
    BinaryExpr, VariableRefExpr, IfExpr, TernaryExpr,
    SinExpr, CosExpr, TanExpr, SqrtExpr, MinExpr, MaxExpr,
    RandomExpr, StringConcatExpr, StringLiteralExpr,
    ComparisonExpr, AndExpr, OrExpr, NotExpr,
    // ... all 26 nodes
    ExpressionExpr {
    // ...
}
```

```java
// TinyExpressionP4Evaluator.java (generated code)
private T evalInternal(TinyExpressionP4AST node) {
    return switch (node) {
        case BinaryExpr n       -> evalBinaryExpr(n);
        case VariableRefExpr n  -> evalVariableRefExpr(n);
        case IfExpr n           -> evalIfExpr(n);
        // ... all 26 cases
    };
    // ^ Forget even one case and you get a compile error!
}
```

**Newcomer:** Java 21's sealed switch. If you don't cover every type in the `permits` clause with a `case`, compilation fails.

**Senior:** Right. With tests, "I forgot to write a test for this case" can happen. But a sealed switch makes forgetting impossible. The compiler won't allow it.

**Newcomer:** But the body of the case could still be wrong, right?

**Senior:** Of course. Types only guarantee "structural consistency," not "semantic correctness" -- you still need tests for that. But think about which breaks first: structure or semantics.

**Newcomer:** Structure. Forgetting a case, type mismatch, that sort of thing.

**Senior:** Right. The power of types is that they eliminate the most common bugs at the earliest stage (compile time).

### Mechanism 2: Typo prevention through records

```java
// Generated record
public record BinaryExpr(
    BinaryExpr left,
    List<String> op,
    List<BinaryExpr> right
) implements TinyExpressionP4AST {}
```

```java
// Code the LLM writes -- what if there's a typo?
Object result = node.lefft();  // Compile error! Typo of left()
Object result = node.left();   // OK
```

**Newcomer:** Ah, since it's a record, accessor methods are auto-generated, and typos become compile errors.

**Senior:** With a Map-based data-passing approach, typos become runtime errors. And they show up as hard-to-diagnose errors like `NullPointerException` or `ClassCastException`. With records, you catch them immediately.

**Newcomer:** You'd have the same problem if you used reflection to handle the AST.

**Senior:** Actually, tinyexpression used to have a reflection-based evaluator. It specified field names as strings like `getClass().getDeclaredField("left")`. This was slow and typos weren't caught until runtime. P4TypedAstEvaluator replaced this with record pattern matching.

### Mechanism 3: @mapping consistency checks

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**Newcomer:** What happens if the `@mapping` parameter names don't match the `@xxx` in the grammar?

**Senior:** You get an error at generation time.

```
Error: @mapping parameter 'lefft' has no corresponding @binding in rule 'NumberExpression'
       Available bindings: left, op, right
```

**Newcomer:** At generation time! Before even building!

**Senior:** Right. Inconsistencies between grammar and AST are detected when the UBNF is processed. By the time you ask the LLM to write code, consistency is already guaranteed.

### Mechanism 4: The safety net of GGP (Generation Gap Pattern)

```
[Generated code]    TinyExpressionP4Evaluator<T>  <- abstract, regenerated every time
       ^ extends
[Hand-written code] P4TypedAstEvaluator           <- concrete, written by humans/LLMs
```

**Newcomer:** GGP came up in the previous tutorial too.

**Senior:** The key point of GGP is "regeneration doesn't break the hand-written parts." When you modify the UBNF and regenerate, new abstract methods appear in the base class. Forget to implement them in the hand-written subclass? Compile error.

**Newcomer:** What happens with tests?

**Senior:** With tests, "I added a new node type but forgot to add test cases" happens routinely. The test suite stays green while the new feature has zero test coverage.

**Newcomer:** Ah, that definitely happens. "Tests are passing, so we're fine" -- only to discover the tests were insufficient in the first place.

**Senior:** Types don't allow the state of "forgot to write a test." Sealed switches require all cases, abstract methods require all implementations.

**Newcomer:** Tests are verification, types are proof. Proof is stronger... that's certainly true.

**Senior:** Let me give a concrete example. P4TypedAstEvaluator has 28 `evalXxx()` methods.

```java
// P4TypedAstEvaluator.java evalXxx() method list (28 total)
evalBinaryExpr(BinaryExpr)
evalVariableRefExpr(VariableRefExpr)
evalIfExpr(IfExpr)
evalTernaryExpr(TernaryExpr)
evalSinExpr(SinExpr)
evalCosExpr(CosExpr)
evalTanExpr(TanExpr)
evalSqrtExpr(SqrtExpr)
evalMinExpr(MinExpr)
evalMaxExpr(MaxExpr)
evalRandomExpr(RandomExpr)
evalToNumExpr(ToNumExpr)
evalStringConcatExpr(StringConcatExpr)
evalStringLiteralExpr(StringLiteralExpr)
evalComparisonExpr(ComparisonExpr)
evalAndExpr(AndExpr)
evalOrExpr(OrExpr)
evalNotExpr(NotExpr)
evalMethodInvocationExpr(MethodInvocationExpr)
evalExternalBooleanInvocationExpr(ExternalBooleanInvocationExpr)
evalExternalNumberInvocationExpr(ExternalNumberInvocationExpr)
evalExternalStringInvocationExpr(ExternalStringInvocationExpr)
evalExternalObjectInvocationExpr(ExternalObjectInvocationExpr)
evalSideEffectNumberExpr(SideEffectNumberExpr)
evalSideEffectStringExpr(SideEffectStringExpr)
evalSideEffectBooleanExpr(SideEffectBooleanExpr)
evalNumberMatchExpr(NumberMatchExpr)
evalStringMatchExpr(StringMatchExpr)
```

**Newcomer:** 28 of them! Covering all of those with tests would be a lot of work.

**Senior:** You still write tests, of course. But "the existence of all 28 eval methods" is guaranteed by the compiler, not by tests. Tests can focus on "verifying that each eval produces the correct computation result."

**Newcomer:** Separation of concerns. The compiler handles structure, tests handle semantics.

**Senior:** Right. And when you have an LLM write code, since the compiler guarantees "structure," the LLM can focus on "semantics." This directly translates to token efficiency.

---

## Part 4: Other Frameworks (ANTLR, etc.) + LLM vs Unlaxer + LLM

**Newcomer:** Senior, I understand that frameworks matter. But it doesn't have to be Unlaxer, right? There are well-known ones like ANTLR and Tree-sitter.

**Senior:** Yeah, that comparison is definitely necessary. Let's think about what happens when you combine them with LLMs.

### ANTLR + LLM

**Newcomer:** ANTLR is famous, right? Lots of answers on Stack Overflow.

**Senior:** ANTLR is an excellent parser generator. Write a grammar and a parser is generated. Visitor and Listener patterns are auto-generated, so tree traversal is easy.

**Newcomer:** Then if we have the LLM write the ANTLR grammar and use the generated code...

**Senior:** Up to the parser, that's fine. But think about what comes after.

```
What ANTLR generates for you:
  OK  Lexer
  OK  Parser
  OK  Visitor/Listener base classes

What ANTLR does NOT generate for you:
  NO  Typed AST (sealed interface + records)
  NO  Parse tree -> AST mapper
  NO  Evaluator base class
  NO  LSP server
  NO  DAP server
```

**Newcomer:** Ah, you have the Visitor, but everything beyond that is hand-written...

**Senior:** Right. With ANTLR + LLM, you'd ask the LLM to "build a typed AST using the ANTLR Visitor." It can do it, but there's no type enforcement. If you forget one Visitor method, it still compiles (the default implementation just returns null).

```java
// ANTLR's Visitor -- missing cases don't cause compile errors
public class MyVisitor extends CalcBaseVisitor<Object> {
    @Override
    public Object visitAddExpr(CalcParser.AddExprContext ctx) {
        // implement
    }
    // Forgot visitMulExpr, but it compiles fine!
    // The default visitChildren(ctx) is called instead
}
```

**Newcomer:** That is indeed scary.

### Tree-sitter + LLM

**Senior:** Tree-sitter specializes in editor integration. It's great at incremental parsing and is used for syntax highlighting in VS Code and Neovim.

**Newcomer:** That sounds good.

**Senior:** The editor integration is strong. But there are several issues.

```
Tree-sitter characteristics:
  OK  Incremental parsing (editor-friendly)
  OK  Multi-language bindings (C, Rust, JS, ...)
  NO  Not Java-native (C-based + bindings)
  NO  No AST type safety (generic node types)
  NO  Evaluator is a completely separate concern
  NO  LSP is hand-written
  NO  DAP is a different world entirely
```

**Newcomer:** It's not Java?

**Senior:** Right. Tree-sitter is written in C, and Java bindings are community-maintained at best. There's also JNI overhead. It's not a fit when you want to "stay within the Java ecosystem," like with tinyexpression.

### Unlaxer + LLM

**Senior:** Let me put it in a comparison table.

```
+---------------------+--------------+--------------+--------------+
|                     |  ANTLR + LLM | Tree-sitter  | Unlaxer + LLM|
|                     |              |   + LLM      |              |
+---------------------+--------------+--------------+--------------+
| Parser generation   | OK  Solid    | OK  Solid    | OK  Solid    |
| Typed AST           | NO  Manual   | NO  Generic  | OK  Auto     |
| AST mapper          | NO  Manual   | NO  Manual   | OK  Auto     |
| Evaluator base      | NO  None     | NO  None     | OK  Auto     |
| exhaustive switch   | NO           | NO           | OK           |
| GGP                 | NO           | NO           | OK           |
| LSP                 | NO  Manual   | ~   Partial  | OK  Auto     |
| DAP                 | NO  Manual   | NO  Manual   | OK  Auto     |
| Java native         | OK           | NO  C + JNI  | OK           |
| LLM learning cost   | Low          | Medium       | Medium       |
| Layers where LLM    | AST/Eval     | All layers   | eval layer   |
|   can introduce bugs|  layers      |              |  only        |
+---------------------+--------------+--------------+--------------+
```

**Newcomer:** "Layers where LLM can introduce bugs" is interesting. With Unlaxer, it's the eval layer only.

**Senior:** Right. The parser, AST, and mapper are generated, so the LLM can't introduce bugs there. The only thing the LLM touches is the inside of evalXxx(). And even that is constrained by types. The room for bugs is structurally small.

**Newcomer:** But ANTLR has Stack Overflow answers. The LLM's training data must include tons of them. Unlaxer has...

**Senior:** One user, so it's not in the training data. I know what you're getting at.

**Newcomer:** Yes, that's my concern.

**Senior:** But here's the thing -- LLMs are **better at reading types than documentation**.

**Newcomer:** What?

**Senior:** Stack Overflow answers are natural language. "In this case, do this." But whether the LLM correctly interprets that is uncertain. On the other hand, Java type information is unambiguous.

```java
// To an LLM, this is a "complete specification"
protected abstract Object evalBinaryExpr(BinaryExpr node);

// Definition of BinaryExpr:
public record BinaryExpr(
    BinaryExpr left,
    List<String> op,
    List<BinaryExpr> right
) implements TinyExpressionP4AST {}
```

**Newcomer:** True... if it knows the parameter types and return type, the LLM can infer what to do.

**Senior:** `BinaryExpr`'s `left` is of type `BinaryExpr`, `op` is `List<String>`, `right` is `List<BinaryExpr>`. Recursive structure, operators are a string list. From this type information alone, the LLM can figure out "ah, I recursively eval left, then pair up op and right and apply them in sequence."

**Newcomer:** Even without Stack Overflow answers, the types speak for themselves.

**Senior:** Exactly. ANTLR's name recognition is a weapon, but for LLMs, "constraints via types" is a more reliable guide than "memories of past answers."

---

## Part 5: What the LLM Actually Did with Unlaxer (Real-World Examples)

**Newcomer:** Senior, I understand the theory. But did an LLM actually build something with Unlaxer? I want to see concrete results.

**Senior:** I'll show you. In a single LLM session (a few hours), we did all of the following.

### Results List

```
1. MapperGenerator bug fix
   - allMappingRules() was picking up non-direct descendants
   - Fixed the recursion logic in findDirectDescendants()
   -> Improved quality of generated Mapper

2. P4TypedAstEvaluator implementation
   - Extends TinyExpressionP4Evaluator<Object>
   - Full implementation of all 28 evalXxx() methods
   - Complete elimination of reflection, dispatch via sealed switch

3. P4TypedJavaCodeEmitter implementation
   - Extends TinyExpressionP4Evaluator<String>
   - AST -> Java source code conversion
   - Type-safe code generation

4. P4DefaultJavaCodeEmitter implementation
   - Simplified code generation based on default values

5. UBNF grammar Expression ordering fix
   - Fixed choice priority in NumberFactor
   - Changed to try TernaryExpression first

6. Changed DAP default to ast-evaluator
   - Changed the debugger's default backend
```

**Newcomer:** Six tasks! And how long did it take?

**Senior:** A few hours total. What matters is why this was possible.

**Newcomer:** Because of the framework?

**Senior:** Right. Let's look at each one.

### P4TypedAstEvaluator -- Why 28 Methods Could Be Written in a Few Hours

**Senior:** Here's everything that was passed to the LLM.

```
1. TinyExpressionP4Evaluator.java (generated base class)  -- type info
2. TinyExpressionP4AST.java (generated AST definitions)   -- record structures
3. A one-line explanation: "BinaryExpr is recursive arithmetic"
4. A one-line explanation: "VariableRefExpr gets its value from CalculationContext"
```

**Newcomer:** That's it?

**Senior:** That's it. The LLM inferred "what to do" from the type information and wrote all 28 methods. Zero compile errors. Only one logic bug (a special case for BinaryExpr leaf node handling was missing).

**Newcomer:** What if there were no framework?

**Senior:** First, you'd have a discussion about "what structure should the AST have?" "Should we use sealed interfaces? Abstract classes? Visitor pattern?" That discussion alone consumes several thousand tokens. And even after the structure is decided and implemented, consistency with the parser has to be checked manually.

### Benchmark: 1,400x Faster Than the Reflection Version

**Senior:** Let me show you the BackendSpeedComparisonTest results.

```
+----------------------+----------------+-----------+
| Backend              | Time/call      | Relative  |
+----------------------+----------------+-----------+
| compile-hand (JIT)   |    0.02 us     |   1.0x    |
| P4-typed-eval (new)  |    0.15 us     |   7.5x    |
| P4-typed-reuse       |    0.08 us     |   4.0x    |
| ast-hand-cached      |    0.30 us     |  15.0x    |
| ast-hand-full        |    5.00 us     | 250.0x    |
| P4-reflection        |  110.00 us     | 5500.0x   |
+----------------------+----------------+-----------+

P4-typed-reuse vs P4-reflection = approximately 1,400x faster
```

**Newcomer:** 1,400x?!

**Senior:** Calling `getClass().getDeclaredField()` via reflection every time versus having dispatch determined at compile time via sealed switch -- that's the kind of difference you get. The JIT can also optimize sealed switches much better.

**Newcomer:** So the P4TypedAstEvaluator written by the LLM is faster than the hand-written ast-hand-cached?

**Senior:** Yes. The reason is that record access is direct field reads. The hand-written AST nodes went through virtual dispatch via interfaces. record + sealed switch is the best possible treatment for the JIT.

**Newcomer:** Code written by the LLM is faster...

**Senior:** More precisely, "code the LLM wrote **following the framework's types** is faster." Because the framework forces the use of records and sealed interfaces, the LLM produces high-performance code without even thinking about performance.

### UBNF Grammar Fix -- Human Judgment + LLM Execution

**Senior:** The Expression ordering fix was an interesting case.

```ubnf
// Before fix: TernaryExpression comes after NumberExpression
NumberFactor ::=
    NumberMatchExpression
  | IfExpression
  | MathFunction
  | NUMBER
  | VariableRef
  | MethodInvocation
  | TernaryExpression      // <- At this position, NumberExpression matches first
  | '(' NumberExpression ')' ;  //   and consumes the condition part as a number

// After fix: Try TernaryExpression first
NumberFactor ::=
    TernaryExpression      // <- Try first
  | NumberMatchExpression
  | IfExpression
  | MathFunction
  | NUMBER
  | VariableRef
  | MethodInvocation
  | '(' NumberExpression ')' ;
```

**Newcomer:** Because it's ordered choice, the ordering matters.

**Senior:** Right. This "ordering issue" -- if you tell the LLM "the ternary operator isn't working for some reason," it can suggest a fix. But the fix is just modifying the UBNF grammar file. No parser or mapper changes needed. Regenerate and everything is fixed.

**Newcomer:** Without a framework, you'd have to fix the recursive descent parser code, fix the AST construction code, fix the Evaluator too...

**Senior:** And fight the risk of "the fix broke other tests" the whole time. With UBNF, you just move one line in the grammar. Zero impact radius.

### Everything Completed in One Session

**Newcomer:** And all of this was done in a single session.

**Senior:** It wouldn't have been possible without a framework. The MapperGenerator bug fix in particular required understanding the internals of Unlaxer's code generation pipeline. But the LLM could infer what was wrong by looking at the signatures of `allMappingRules()` and `findDirectDescendants()` and the expected test values.

**Newcomer:** Type information as a guide, here too.

**Senior:** Right. `allMappingRules()` returns `List<MappingRule>` and `findDirectDescendants()` returns `List<GrammarRule>`. Looking at the types, you can tell "ah, the relationship between MappingRule and GrammarRule is suspicious."

---

## Part 6: The Value of Frameworks in the Generative AI Era

**Newcomer:** Senior, listening to everything so far, what I thought was that "LLMs make frameworks unnecessary" is actually backwards.

**Senior:** Right. **Paradoxically, frameworks become more valuable precisely because of the LLM era.**

**Newcomer:** Why?

**Senior:** We said the LLM's greatest strength is "code generation following correct patterns." So who defines the "correct patterns"?

**Newcomer:** ...The framework?

**Senior:** Exactly. The framework defines "correct patterns" as types, and the LLM fills in code following those patterns. This is the most efficient division of labor.

```
Human's job:
  1. Decide what to parse (language design)
  2. Write the UBNF grammar
  3. Decide AST shapes with @mapping
  4. Review generated code

Framework's (Unlaxer's) job:
  1. UBNF -> parser generation
  2. UBNF -> AST generation (sealed interface + records)
  3. UBNF -> mapper generation
  4. UBNF -> Evaluator base class generation
  5. UBNF -> LSP server generation
  6. UBNF -> DAP server generation

LLM's job:
  1. Implement the body of evalXxx() methods
  2. Implement tests
  3. Write documentation
  4. Localized bug fixes
```

**Newcomer:** The roles of the three are clearly defined.

### @eval strategy: Future Automation

**Senior:** Looking further ahead, Unlaxer has an `@eval` strategy on its roadmap.

```ubnf
// Future UBNF syntax (concept stage)
@mapping(BinaryExpr, params=[left, op, right])
@eval(strategy=binary_arithmetic, left=left, op=op, right=right)
@leftAssoc
@precedence(level=10)
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**Newcomer:** `@eval(strategy=binary_arithmetic)`? That means...

**Senior:** Declaring the evaluation strategy directly in the grammar. For `binary_arithmetic`, the standard pattern of "recursively eval left, pair up op and right, and apply arithmetic operations" would be auto-implemented in the Evaluator.

```java
// Code auto-generated by @eval strategy (future)
@Override
protected Object evalBinaryExpr(BinaryExpr node) {
    if (node.left() == null && node.right().isEmpty()) {
        return evalLeaf(node.op().get(0));  // leaf: literal or variable
    }
    Object current = eval(node.left());
    for (int i = 0; i < node.op().size(); i++) {
        Object right = eval(node.right().get(i));
        current = applyBinaryOp(node.op().get(i), current, right);
    }
    return current;
}
```

**Newcomer:** If this were implemented, the LLM wouldn't even need to write evalXxx()?

**Senior:** For standard patterns, yes. Common patterns like `binary_arithmetic`, `variable_lookup`, `passthrough` can be automated.

| strategy | Meaning | Example |
|:---|:---|:---|
| `binary_arithmetic` | Recursive evaluation of binary operations | `1 + 2 * 3` |
| `variable_lookup` | Resolve variable from context | `$price` |
| `literal` | Return literal value as-is | `42`, `'hello'` |
| `function_call` | Function invocation | `sin($x)` |
| `conditional` | Conditional branching | `if/else`, `? :` |
| `passthrough` | Return child node as-is | Wrapper rules |
| `invocation` | Method invocation | `call myFunc()` |

**Newcomer:** Seven strategies that seem to cover most cases.

**Senior:** Of the 28 methods in P4TypedAstEvaluator, over 20 could be auto-generated with these strategies. Only the remaining special cases would need to be written by the LLM or humans.

### JavaCodeBuilder: Type-Safe Even for Code-Generating Code

**Newcomer:** Unlaxer generates code that generates code, right? Is that type-safe too?

**Senior:** There's `JavaCodeBuilder`.

```java
// How to use JavaCodeBuilder
JavaCodeBuilder java = new JavaCodeBuilder("com.example");
java.imports("java.util.List", "java.util.Optional");
java.publicClass("MyMapper", cls -> {
    cls.field("private static final", "Map<String,String>", "CACHE", "new HashMap<>()");
    cls.blankLine();
    cls.method("public static", "MyAST", "parse", m -> {
        m.param("String", "source");
        m.body(b -> {
            b.varDecl("Parser", "parser", "getParser()");
            b.ifBlock("parser == null", ib -> {
                ib.throwNew("IllegalArgumentException", "\"No parser\"");
            });
            b.returnStmt("parser.parse(source)");
        });
    });
});
String javaSource = java.build();
```

**Newcomer:** Instead of concatenating strings with `StringBuilder`, you assemble code using a structured API.

**Senior:** Right. If you don't write `m.body()` inside `cls.method()`, you get a compile error. Indentation is automatic. Closing braces are automatic. When the LLM uses this API to write code generation logic, there's no risk of producing syntactically broken Java code.

**Newcomer:** The philosophy of enforcing correctness through types is thoroughgoing.

### The Future: A World Where Everything Comes from UBNF

**Senior:** The ultimate goal looks like this.

```
UBNF grammar file (350 lines)
  | unlaxer-dsl codegen
  |-- Parsers.java        -- Parser combinator chain
  |-- AST.java            -- sealed interface + records
  |-- Mapper.java         -- Token -> AST conversion
  |-- Evaluator.java      -- abstract base (@eval automates most)
  |-- LSPServer.java      -- Language Server Protocol
  |-- DAPServer.java      -- Debug Adapter Protocol
  +-- VSCode Extension    -- Editor plugin

What humans write:
  - UBNF grammar
  - Special eval logic (things @eval can't cover)

What LLMs write:
  - Implementation of special eval logic
  - Tests
  - Documentation
```

**Newcomer:** Write a UBNF and you get a language processing system + LSP + DAP. That means...

**Senior:** The barrier to "creating your own language" drops dramatically.

**Newcomer:** But Senior, if that happens, can't the LLM just write the UBNF grammar, making even humans unnecessary...

**Senior:** ......

**Newcomer:** Sorry, did I go too far?

**Senior:** No, that's an important question. Here's the answer. **Grammar design is a human job.** Deciding what to parse isn't the AI's call.

**Newcomer:** Why not? If you tell the LLM "create an Excel formula language"...

**Senior:** What is "an Excel formula language"? Does it support `=SUM(A1:A10)`? What about `=VLOOKUP`? `=LAMBDA`? Array formulas? Deciding the boundaries of "Excel formula language" is a business requirement, not a technical problem.

**Newcomer:** Ah... you're right. "What to build" is a human decision.

**Senior:** The reason tinyexpression uses the `$` prefix for variable references isn't technical -- it's an operational decision to avoid confusion with Excel cell references. Making `if/else` an expression was because tinyexpression is an "expression language." These are design decisions, not code generation problems.

**Newcomer:** Humans design, the framework guarantees structure, the LLM implements.

**Senior:** Right. But implementation alone can be delegated. Thinking through each of the 28 methods in P4TypedAstEvaluator one by one isn't a human's job. Writing the body according to the signature provided by the types -- the LLM actually makes fewer mistakes at that.

**Newcomer:** Focus on what only humans can do.

**Senior:** Exactly.

---

## Part 7: The One-User Problem

**Newcomer:** Senior...

**Senior:** Hmm?

**Newcomer:** You make good points, but... Unlaxer has only one user, doesn't it? You.

**Senior:** ...You knew?

**Newcomer:** I looked at the GitHub. Star count: 1.

**Senior:** That star is mine.

**Newcomer:** ......

**Senior:** ......

**Newcomer:** No, but seriously. It's a problem that nobody's using such a good framework, isn't it? ANTLR has thousands of stars and is used by companies.

**Senior:** The correctness of an architecture has nothing to do with user count.

**Newcomer:** That sounds cool, but practically speaking, depending on a framework with one user is a risk. What if maintenance stops? What if there's a bug?

**Senior:** Yeah, those are valid concerns. But let me offer a few counterpoints.

### Counterpoint 1: Generated code has no dependency

**Senior:** The code Unlaxer generates doesn't depend on Unlaxer itself.

**Newcomer:** What?

**Senior:** The generated `TinyExpressionP4Parsers.java` uses parser combinators from `unlaxer-common`, but the AST records are pure Java code. `TinyExpressionP4AST.java` is composed entirely of sealed interfaces and records with zero external dependencies.

```java
// Generated AST -- no external dependencies
public sealed interface TinyExpressionP4AST permits ... {
    record BinaryExpr(BinaryExpr left, List<String> op, List<BinaryExpr> right)
        implements TinyExpressionP4AST {}
    // ...
}
```

**Newcomer:** True, it's just the Java standard library.

**Senior:** Even if Unlaxer maintenance stops, the already-generated code continues to work. In the worst case, you can discard UBNF and switch to manually maintaining the generated code. The lock-in is small.

### Counterpoint 2: I was envious of Scala's parser combinators

**Senior:** Let me share a bit of history. I started building Unlaxer because I was envious of Scala's parser combinators.

```scala
// Scala's parser combinator (conceptual)
def expr: Parser[Int] = term ~ rep("+" ~ term | "-" ~ term) ^^ {
  case t ~ rest => rest.foldLeft(t) {
    case (acc, "+" ~ r) => acc + r
    case (acc, "-" ~ r) => acc - r
  }
}
```

**Newcomer:** Scala has pattern matching built in, and case classes too, so parser combinators feel natural in the language.

**Senior:** But in Java in the 2010s, none of that existed. No pattern matching, no sealed classes, no records. Unlaxer had to fight within the constraints of Java 8.

**Newcomer:** And then Java 21 came along...

**Senior:** It finally caught up. Actually, I think it's even surpassed Scala.

```java
// Java 21's sealed switch -- on par with or better than Scala's pattern matching
return switch (node) {
    case BinaryExpr(var left, var op, var right) -> evalBinary(left, op, right);
    case VariableRefExpr(var name)               -> lookupVariable(name);
    case IfExpr(var cond, var then, var else_)    -> evalIf(cond, then, else_);
    // ... exhaustive -- compiler guarantees coverage
};
```

**Newcomer:** Record destructuring patterns! The same things as Scala's case classes can be done in Java.

**Senior:** Moreover, the exhaustive check on sealed interfaces was a feature that even Scala didn't have until recently. I believe Java 21 "evolved in the right direction." Unlaxer leverages that evolution to the fullest.

### Counterpoint 3: There are two of us now

**Newcomer:** Two? Who?

**Senior:** Me, and the LLM.

**Newcomer:** ......

**Senior:** I'm not joking. I think of the LLM as a "user" of Unlaxer. It reads type information, implements evalXxx(), and writes tests. Even with zero Stack Overflow answers, as long as there are types -- the "complete documentation" -- the LLM can master Unlaxer.

**Newcomer:** Counting the LLM as a user is a novel idea...

**Senior:** Seriously though, it's true that OSS "has value because people use it." But "it has no value because nobody uses it" is not logically sound.

**Newcomer:** ?

**Senior:** Mathematical theorems are correct even if nobody knows them. The same goes for correct architecture. Whether sealed interface + GGP + exhaustive switch is the right approach has nothing to do with user count.

**Newcomer:** But practically, you need to get the word out...

**Senior:** Yeah. That's why I'm writing tutorials. Making them dialogue-style is so that LLMs can easily read them.

**Newcomer:** Ah, I see. This conversation itself could become LLM training data?

**Senior:** I don't know if it will, but at least if you put it in an LLM's context, it can understand it. UBNF notation, the GGP pattern, how to use @mapping -- it's all explained in this dialogue.

**Newcomer:** "Documentation designed to be read by LLMs" -- a new category of documentation.

**Senior:** Right. API references are hard for both humans and LLMs to read. Stack Overflow is fragmented. But dialogues naturally include the context of "why things are the way they are." I think it's the format LLMs understand best.

### The Benefits of Publishing as OSS

**Newcomer:** So Senior, what are the benefits of continuing to publish Unlaxer as OSS? Even if the user count doesn't grow.

**Senior:** There are several.

```
1. Published on Maven Central
   - Anyone can use it by just adding a <dependency>
   - Stable version management for my own projects

2. LLMs can reference it
   - If the source code is public, LLMs can read the type information
   - "Fix the Unlaxer MapperGenerator bug" becomes viable

3. Design records
   - The reasoning behind "why sealed interfaces" and "why GGP" is
     preserved in code and documentation
   - The best documentation for my future self 10 years from now

4. Technical challenge
   - Proof that one person can build "parser combinator + code generation
     + LSP + DAP in Java"
   - The most eloquent proof of technical ability
```

**Newcomer:** Number 4 would look great on a resume.

**Senior:** I'm not planning to change jobs though.

**Newcomer:** But saying "I built something like ANTLR by myself in Java" would wow any interviewer. And it has features ANTLR doesn't (auto-generated typed AST, GGP, LSP/DAP).

**Senior:** ...Maybe I should browse some job sites.

**Newcomer:** I'm kidding!

---

## Summary: Three Principles for Frameworks in the LLM Era

**Newcomer:** Senior, can you wrap this up? What should be expected of frameworks in the LLM era?

**Senior:** Let me summarize it in three principles.

### Principle 1: Enforce patterns through types

```
LLMs are good at "following correct patterns."
If the framework defines "correct patterns" as types,
the LLM will automatically write correct code.

Unlaxer in practice:
  - sealed interface -> Forces enumeration of all AST nodes
  - abstract method  -> Forces implementation of all eval methods
  - record           -> Prevents typos in field access
  - @mapping         -> Guarantees grammar-AST consistency at generation time
```

### Principle 2: Structurally reduce the room for bugs

```
The narrower the code the LLM touches, the fewer the bugs.
The more the framework generates, the smaller the part the LLM writes.

Unlaxer in practice:
  - Parser:         100% generated (no room for LLM bugs)
  - AST:            100% generated (no room for LLM bugs)
  - Mapper:         100% generated (no room for LLM bugs)
  - Evaluator base: 100% generated (dispatch logic)
  - evalXxx() body: Written by LLM (this part only)
```

### Principle 3: Don't break hand-written parts when regenerating

```
GGP (Generation Gap Pattern) separates generated code from hand-written code.
Even when UBNF is changed and regenerated, hand-written evalXxx() stays safe.
New nodes trigger compile error notifications.

Unlaxer in practice:
  - Base class: Regenerated every time (TinyExpressionP4Evaluator)
  - Concrete class: Hand-written / LLM-written (P4TypedAstEvaluator)
  - Contract: Connected by abstract methods -> additions detected, deletions detected
```

**Newcomer:** Simple. Enforce with types, narrow the scope, survive regeneration.

**Senior:** Any framework that satisfies these three would work, not just Unlaxer. But as far as I know, no other framework generates parser + AST + mapper + Evaluator + LSP + DAP, all connected by types.

**Newcomer:** ANTLR goes up to the parser, Tree-sitter up to the editor. The one that does it all is...

**Senior:** Unlaxer. One user only, though.

**Newcomer:** Two, if you include the LLM.

**Senior:** ...Thank you.

**Newcomer:** And actually, I'm thinking I might become the third.

**Senior:** What?

**Newcomer:** Can I try writing my own DSL in UBNF sometime? I want to create a small language for configuration files.

**Senior:** ...Of course. Start with the tinycalc example. It's in `unlaxer-dsl/examples/tinycalc.ubnf`.

**Newcomer:** Thanks! Oh, but I have one condition.

**Senior:** What?

**Newcomer:** Can I ask ChatGPT when I get stuck?

**Senior:** ...Obviously. This entire conversation has been recommending that.

**Newcomer:** Right? Then I'll give it a try as a framework user in the LLM era.

**Senior:** Welcome. That makes three of us.

**Newcomer:** Four, actually. I'll be using an LLM too.

**Senior:** ...That counting seems off, but sure, why not.

---

## Appendix: Technical Terms Mentioned in This Dialogue

| Term | Description |
|:---|:---|
| **UBNF** | Unlaxer BNF. A grammar description language based on EBNF that includes AST mapping and scope definitions |
| **sealed interface** | A Java 17+ feature. An interface that only permits specified types to implement it |
| **record** | A Java 16+ feature. An immutable data carrier that automatically generates accessors, equals, and hashCode |
| **exhaustive switch** | A switch expression over a sealed interface. The compiler verifies that all cases are covered |
| **GGP** | Generation Gap Pattern. A pattern that separates generated code (base class) from hand-written code (subclass) |
| **@mapping** | A UBNF annotation. Declares the correspondence between a grammar rule and an AST record |
| **@eval strategy** | A UBNF annotation (concept stage). Declares evaluation strategy in the grammar to auto-generate the Evaluator |
| **ordered choice** | PEG's choice operator. Tries matches from left to right and adopts the first one that succeeds |
| **JavaCodeBuilder** | A type-safe Java source code builder for code generation |
| **token (LLM)** | The unit of LLM input/output. A text fragment. Directly impacts API cost and context window consumption |
| **LSP** | Language Server Protocol. A protocol for providing language support (completion, go-to-definition, etc.) to editors |
| **DAP** | Debug Adapter Protocol. A protocol for providing debug features (breakpoints, step execution, etc.) to editors |

---

## Appendix: Referenced File List

### tinyexpression (user side)

| File | Description |
|:---|:---|
| `docs/ubnf/tinyexpression-p4-complete.ubnf` | UBNF grammar definition for the P4 generation (350+ lines) |
| `P4TypedAstEvaluator.java` | GGP concrete: AST evaluator (28 evalXxx methods) |
| `P4TypedJavaCodeEmitter.java` | GGP concrete: Java code generation |
| `P4DefaultJavaCodeEmitter.java` | GGP concrete: Default value code generation |
| `BackendSpeedComparisonTest.java` | Speed comparison test across 5 backends |

### unlaxer-parser (framework side)

| File | Description |
|:---|:---|
| `ParserGenerator.java` | UBNF -> parser combinator generation |
| `ASTGenerator.java` | UBNF -> sealed interface + records generation |
| `MapperGenerator.java` | UBNF -> Token -> AST mapper generation |
| `EvaluatorGenerator.java` | UBNF -> GGP base class generation |
| `JavaCodeBuilder.java` | Type-safe Java source code builder |
| `LSPGenerator.java` | UBNF -> LSP server generation |
| `DAPGenerator.java` | UBNF -> DAP server generation |

---

> This dialogue is based on actual experience developing unlaxer-parser / tinyexpression with an LLM (Claude).
> Token consumption figures are estimates and may vary depending on the model and prompt design.
> Benchmark values are relative comparisons based on BackendSpeedComparisonTest results.

---

[English](./llm-era-and-unlaxer-dialogue.en.md) | [日本語](./llm-era-and-unlaxer-dialogue.ja.md) | [Index](./INDEX.ja.md)
