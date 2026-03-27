[English](./tutorial-parser-fundamentals-dialogue.en.md) | [日本語](./tutorial-parser-fundamentals-dialogue.ja.md)

---

# unlaxer-parser Tutorial: From Parser Fundamentals to Practice

> A dialogue-style introduction to the world of Parser Combinators
>
> Characters:
> - **Senior** -- The creator of unlaxer-parser. Well-versed in parser theory, with occasional humor
> - **Newcomer** -- Bright but new to parser theory. Fires off one candid question after another

---

## Table of Contents

- [Part 1: What Is a Parser?](#part-1-what-is-a-parser)
- [Part 2: The World of Parsing Techniques](#part-2-the-world-of-parsing-techniques)
- [Part 3: The Parser Combinator Approach](#part-3-the-parser-combinator-approach)
- [Part 4: Terminal Parsers](#part-4-terminal-parsers)
- [Part 5: Combinator Parsers](#part-5-combinator-parsers)
- [Part 6: Left Associativity, Right Associativity, and Operator Precedence](#part-6-left-associativity-right-associativity-and-operator-precedence)
- [Part 7: Tokens and Parse Trees](#part-7-tokens-and-parse-trees)
- [Part 8: Building Your Own Parser](#part-8-building-your-own-parser)
- [Part 9: AST Filtering and Scope](#part-9-ast-filtering-and-scope)
- [Part 10: Error Handling and Debugging](#part-10-error-handling-and-debugging)
- [Part 11: Advanced Topics -- The Road to UBNF](#part-11-advanced-topics--the-road-to-ubnf)
- [Part 12: Advanced Parsers -- The Forgotten Classes](#part-12-advanced-parsers--the-forgotten-classes)
- [Appendix A: Parser Glossary](#appendix-a-parser-glossary)
- [Appendix B: Complete Parser Reference for unlaxer-parser](#appendix-b-complete-parser-reference-for-unlaxer-parser)

---

## Part 1: What Is a Parser?

[Next: Part 2 The World of Parsing Techniques ->](#part-2-the-world-of-parsing-techniques)

---

**Newcomer**: Senior, I want to start learning about parsers today, but what exactly is a parser? If all you need to do is process strings in a program, aren't `String.split()` and regular expressions enough?

**Senior**: Great question. In a nutshell, a **parser** is a program that **converts strings into structured data**.

**Newcomer**: Structured data? Like JSON?

**Senior**: Yes, JSON is a perfect example. Consider this string.

```
3 + 4 * 2
```

**Senior**: A human can see this and understand "add 3 to the result of 4 times 2." But to a computer, it's initially just a sequence of bytes. The parser's job is to extract a **tree structure** from this flat string.

```
      (+)
      / \
     3  (*)
        / \
       4   2
```

**Newcomer**: I see. The tree takes operator precedence into account. Since `*` is computed first, `*` appears deeper in the tree.

**Senior**: Exactly. This tree is called a "syntax tree" or "parse tree." Without parsers, we couldn't process programming languages, configuration files, SQL, HTML -- anything.

---

### The Limitations of Regular Expressions

**Newcomer**: But Senior, regular expressions can do some pretty complex pattern matching, right? Like email address validation.

**Senior**: Regular expressions are indeed powerful. But they have one critical weakness: they **can't handle nested structures**.

**Newcomer**: Nested structures?

**Senior**: For example, consider matching parentheses.

```
((1 + 2) * (3 + 4))
```

**Senior**: Can you use a regular expression to detect "correctly matched parentheses"?

**Newcomer**: Hmm... `\(.*\)` would just match the first `(` and the last `)`, and I'd lose track of the nesting inside.

**Senior**: Right. Regular expressions are based on "finite automata." Finite automata have no "stack," so they can't count parenthesis depth. It's been mathematically proven that regular languages cannot recognize nested structures.

**Newcomer**: So parsing HTML tag nesting is impossible with regular expressions too?

**Senior**: Exactly. There's a famous Stack Overflow answer saying "you must not parse HTML with regex," and that's not a joke -- it has a theoretical basis. Regular expressions can only handle "regular languages." Parenthesis matching and recursive structures belong to "context-free languages," which require a different tool.

**Newcomer**: And that "different tool" is a parser.

**Senior**: Precisely, parsers operate based on context-free grammars. Think of them as a superset of regular expressions. Everything regular expressions can do, parsers can do too, plus parsers have the additional power to handle recursion and nesting.

---

### Context-Free Grammar (CFG) and BNF Notation

**Newcomer**: I've at least heard the name "context-free grammar." What is it concretely?

**Senior**: Context-Free Grammar (CFG for short) is a formal method for defining the structure of a language. It consists of four elements.

1. **Terminal symbols** -- Actual characters or tokens. Example: `+`, `-`, `0`-`9`
2. **Non-terminal symbols** -- Names representing structure. Example: `Expression`, `Term`, `Factor`
3. **Production rules** -- Rules that replace non-terminal symbols with terminal symbols or other non-terminals
4. **Start symbol** -- The non-terminal symbol that serves as the entry point for the entire grammar

**Newcomer**: That's pretty abstract, hard to picture...

**Senior**: Let me show you a concrete example. Here's a grammar for a calculator that can do addition and multiplication, written in BNF (Backus-Naur Form).

```bnf
<expression> ::= <term> (('+' | '-') <term>)*
<term>       ::= <factor> (('*' | '/') <factor>)*
<factor>     ::= NUMBER | '(' <expression> ')'
```

**Newcomer**: Oh, I've seen something like this before. Does `::=` mean "is defined as"?

**Senior**: Yes. The non-terminal on the left expands into the pattern on the right. `|` means "or" and `*` means "zero or more repetitions." The key thing is that `<expression>` appears inside the definition of `<factor>`.

**Newcomer**: Ah, it's recursive! `expression` -> `term` -> `factor` -> `(expression)` -- it's circular.

**Senior**: That's exactly the power of context-free grammars. This recursion is what allows expressing arbitrarily deep nesting like `((1 + 2) * (3 + (4 * 5)))`. Regular expressions can't do this.

**Newcomer**: Are there other similar notations besides BNF?

**Senior**: Yes. EBNF (Extended BNF) extends BNF to allow directly writing repetition `{...}` and optional `[...]`. It's standardized as ISO/IEC 14977. UBNF (Unlaxer BNF), which unlaxer-parser uses internally, can also be considered a variant of EBNF.

```ebnf
expression = term , { ("+" | "-") , term } ;
term       = factor , { ("*" | "/") , factor } ;
factor     = number | "(" , expression , ")" ;
```

---

### History of Parsers

**Newcomer**: How long have parsers been around?

**Senior**: Parsers have a surprisingly long history, going back to the dawn of computer science.

**Newcomer**: That old?

**Senior**: In the 1950s, Noam Chomsky systematized formal language theory. His "Chomsky hierarchy" clarified the distinction between regular languages and context-free languages. In the 1960s, automatic parser generation tools emerged for building programming language compilers.

Key milestones include:

- **1965**: Donald Knuth published the theory of LR parsers
- **1975**: Stephen Johnson developed **yacc** (Yet Another Compiler Compiler). The first practical parser generator on UNIX
- **1985**: **GNU Bison** appeared as a yacc-compatible tool
- **1992**: Terence Parr began developing **ANTLR** (ANother Tool for Language Recognition). Generates LL(k) parsers
- **2004**: Bryan Ford proposed **PEG** (Parsing Expression Grammar)
- **2013**: ANTLR4 introduced the ALL(*) algorithm

**Newcomer**: I've heard the name yacc before. "Yet Another Compiler Compiler" -- so there were already other tools?

**Senior**: Yes, the concept of a compiler-compiler existed before yacc. But yacc became the most widely used. It was used for the C language grammar definition, and many UNIX tools use parsers written with yacc.

**Newcomer**: ANTLR is well-known in the Java world.

**Senior**: ANTLR is widely used in both education and industry. It can generate parsers for many languages including Java, Python, and C#. However, ANTLR is a parser generator -- it takes the approach of automatically generating parser source code from grammar definition files (.g4). unlaxer-parser takes a different approach. We'll talk about that in detail in Part 3.

---

### Parse Tree (CST) vs Abstract Syntax Tree (AST)

**Newcomer**: You mentioned "syntax tree" earlier, but is that different from an AST (Abstract Syntax Tree)?

**Senior**: Good question. This distinction is important, so let's make sure you understand it.

A **parse tree (also called Concrete Syntax Tree / CST)** is a tree that **directly reflects** the grammar rules. Every symbol in the grammar appears as a node.

**Newcomer**: What do you mean by "directly"?

**Senior**: Let me show you an example. Here's the parse tree when `3 + 4 * 2` is parsed with the grammar we just saw.

```
Expression
+-- Term
|   +-- Factor
|       +-- NUMBER: "3"
+-- "+"
+-- Term
|   +-- Factor
|   |   +-- NUMBER: "4"
|   +-- "*"
|   +-- Factor
|       +-- NUMBER: "2"
```

**Senior**: On the other hand, an **Abstract Syntax Tree (AST)** is a tree with **semantically irrelevant information removed** from the parse tree. Parentheses, delimiters, and intermediate rule nodes that aren't needed for computation are stripped away.

```
BinaryOp(+)
+-- Number(3)
+-- BinaryOp(*)
    +-- Number(4)
    +-- Number(2)
```

**Newcomer**: Ah, the AST is much cleaner. The intermediate nodes like `Expression`, `Term`, and `Factor` are gone, leaving only operators and numbers.

**Senior**: Exactly. The AST is a representation focused on **semantics** -- "what to compute." The CST is a representation that preserves **syntax** -- "how it was written."

| Aspect | CST (Parse Tree) | AST (Abstract Syntax Tree) |
|------|-------------|----------------|
| Information content | Retains all syntactic information | Only semantically necessary information |
| Node count | Many | Few |
| Parentheses/delimiters | Present as nodes | Omitted |
| Use cases | Formatters, refactoring | Evaluation, code generation |

**Newcomer**: Which one should I use?

**Senior**: It depends on the purpose. For code evaluation (execution) or code generation, an AST is sufficient. But for source code formatting (indent alignment) or refactoring tools, you need whitespace and parenthesis information, so a CST is required.

**Senior**: unlaxer-parser takes an interesting approach -- it first generates a CST (parse tree), then provides a filtering mechanism called `filteredChildren` to extract a view equivalent to an AST. In other words, **you can access both CST and AST from a single parse result**.

**Newcomer**: Best of both worlds! How does the filtering work?

**Senior**: It uses markers called `ASTNode` and `NotASTNode`. We'll explain those in detail in Part 9.

**Newcomer**: Looking forward to it. But first, I want to get a better grasp of the overall picture of parsers.

**Senior**: All right, let's look at the world of parsing techniques next.

---

[<- Back to Table of Contents](#table-of-contents) | [Next: Part 2 The World of Parsing Techniques ->](#part-2-the-world-of-parsing-techniques)

---

## Part 2: The World of Parsing Techniques

[<- Part 1: What Is a Parser?](#part-1-what-is-a-parser) | [Next: Part 3 Parser Combinator ->](#part-3-the-parser-combinator-approach)

---

**Newcomer**: Senior, I've heard there are many kinds of parsers. How are they categorized?

**Senior**: There are two main schools: **top-down** and **bottom-up**.

---

### Top-Down vs Bottom-Up

**Newcomer**: Top-down and bottom-up? That sounds like management jargon.

**Senior**: That analogy is actually perfect.

A **top-down parser** starts from the grammar's start symbol (the topmost rule) and expands downward toward the input string. "This string is probably an Expression" -> "An Expression should consist of Terms" -> "A Term should consist of Factors" -> "A Factor should be a number" -> check the actual characters.

A **bottom-up parser** goes the opposite direction, starting from individual characters or tokens in the input and aggregating them upward according to grammar rules. "`3` is a NUMBER" -> "NUMBER is a Factor" -> "Factor is a Term" -> ...

**Newcomer**: Intuitively, top-down seems easier to understand.

**Senior**: Right. Top-down parsers are closer to how humans think, so they're easier to write by hand. On the other hand, bottom-up parsers have the advantage of handling a broader range of grammars.

---

### Recursive Descent Parser

**Newcomer**: What does an "easy to write by hand" parser look like?

**Senior**: The most basic top-down parser is the **recursive descent parser**. Each grammar rule is implemented as a function.

```java
// <expression> ::= <term> (('+' | '-') <term>)*
double parseExpression() {
    double result = parseTerm();
    while (currentChar() == '+' || currentChar() == '-') {
        char op = currentChar();
        advance();
        double right = parseTerm();
        if (op == '+') result += right;
        else result -= right;
    }
    return result;
}

// <term> ::= <factor> (('*' | '/') <factor>)*
double parseTerm() {
    double result = parseFactor();
    while (currentChar() == '*' || currentChar() == '/') {
        char op = currentChar();
        advance();
        double right = parseFactor();
        if (op == '*') result *= right;
        else result /= right;
    }
    return result;
}

// <factor> ::= NUMBER | '(' <expression> ')'
double parseFactor() {
    if (currentChar() == '(') {
        advance(); // skip '('
        double result = parseExpression();
        expect(')');
        return result;
    }
    return parseNumber();
}
```

**Newcomer**: Oh, the grammar rules literally become functions! The recursion is in `parseFactor()` calling `parseExpression()`?

**Senior**: Yes. Because the grammar is recursive, the parser is recursive too. That's the origin of the name "recursive descent." You create a function for each non-terminal symbol in the grammar, and they call each other.

**Newcomer**: I could probably write something like this myself.

**Senior**: In fact, many practical parsers are written this way. GCC's C++ parser and V8's (the JavaScript engine) parser are both based on recursive descent. It's simple and makes it easy to produce good error messages.

---

### LL(k) Parser

**Newcomer**: What's an LL(k) parser? What do the two L's mean?

**Senior**: Here's what LL(k) stands for.

- First **L**: Read input **L**eft to right
- Second **L**: Perform **L**eftmost derivation
- **(k)**: Look ahead **k** tokens

**Newcomer**: What's leftmost derivation?

**Senior**: When applying grammar rules, it's the method of always expanding the leftmost non-terminal first. Top-down parsers basically use this approach.

**Newcomer**: And looking ahead k tokens?

**Senior**: For example, an LL(1) parser looks at just the next character (or token) to decide which rule to apply. LL(2) looks up to 2 characters ahead.

```
// LL(1) decision example
// If the next character at the current position is '(', choose factor -> '(' expression ')'
// If it's a digit, choose factor -> NUMBER
```

**Senior**: ANTLR was originally a tool for generating LL(k) parsers. ANTLR4 upgraded to an improved version called ALL(*), which can look ahead as many characters as needed.

**Newcomer**: Is a larger lookahead more powerful?

**Senior**: Generally yes. But increasing the lookahead also increases computational cost. If the grammar works with LL(1), it's more efficient to use LL(1).

---

### LR Parser

**Newcomer**: What's the representative bottom-up parser?

**Senior**: The **LR parser**.

- **L**: Read input **L**eft to right
- **R**: Perform **R**ightmost derivation in reverse

**Newcomer**: The first L is the same, but R is different. Rightmost derivation "in reverse"?

**Senior**: Because it's bottom-up. Instead of "applying" rules, it "reduces" them. The core of an LR parser is the **shift-reduce** mechanism.

**Newcomer**: Shift-reduce?

**Senior**: It uses a stack and repeats two operations:

1. **Shift**: Read one token from the input and push it onto the stack
2. **Reduce**: When elements on top of the stack match the right side of a grammar rule, replace them with the non-terminal on the left side

```
Input: 3 + 4 * 2

Step   Stack              Input        Action
1      (empty)            3 + 4 * 2    Shift
2      3                  + 4 * 2      Reduce: 3 -> Factor -> Term
3      Term               + 4 * 2      Shift
4      Term +             4 * 2        Shift
5      Term + 4           * 2          Reduce: 4 -> Factor
6      Term + Factor      * 2          Shift (don't reduce! * has higher precedence)
7      Term + Factor *    2            Shift
8      Term + Factor * 2               Reduce: 2 -> Factor
9      Term + Factor * Factor          Reduce: Factor * Factor -> Term
10     Term + Term                     Reduce: Term + Term -> Expression
11     Expression                      Done!
```

**Newcomer**: Ooh, managed with a stack. But at step 5, how does it decide "don't reduce"?

**Senior**: Good question. LR parsers pre-compute a large **parser table**. This table tells you "given the current stack state and the next input token, whether to shift or reduce."

**Senior**: yacc and Bison are exactly the tools that auto-generate these parser tables. Feed in a grammar definition, and out comes C parser code including the table.

**Newcomer**: Pre-computing the table is what makes it fast.

**Senior**: Yes. LR parsers operate in linear time O(n). However, the tables tend to be huge, and debugging can be difficult. Countless developers have been tormented by yacc's "shift/reduce conflict" error messages.

---

### PEG (Parsing Expression Grammar)

**Newcomer**: I hear about PEG a lot. Is it different from CFG?

**Senior**: PEG (Parsing Expression Grammar) is a grammar formalism proposed by Bryan Ford in 2004. It looks similar to CFG but has one critical difference: **ordered choice**.

**Newcomer**: Ordered choice?

**Senior**: In CFG, the choice `A | B` is an **ambiguous** choice -- "either A or B is fine." If the input matches both A and B, the grammar alone doesn't determine which to choose.

In PEG, the choice `A / B` has an ordering: "**try A first; if A fails, try B**." If A succeeds, B is never tried.

```
# CFG choice (ambiguous)
A | B    ... What if the input matches both?

# PEG choice (ordered)
A / B    ... Try A first. If it succeeds, don't look at B.
```

**Newcomer**: I see, so PEG is always deterministic. No ambiguity.

**Senior**: Right. Another feature of PEG is **backtracking**. If you try A and it fails, you reset the read position and try B.

**Newcomer**: Isn't backtracking a performance concern? In the worst case it could be exponential time...

**Senior**: Sharp. Naive backtracking could indeed be that bad. That's where **Packrat parsing** comes in.

---

### Packrat Parsing -- Guaranteeing Linear Time with Memoization

**Senior**: Packrat parsing is a technique to make PEG backtracking efficient, proposed by Ford himself. The idea is simple: **memoization**.

**Newcomer**: Memoization -- the same thing used in dynamic programming? Caching results you've already computed.

**Senior**: Yes. Record the result of each parser at each position. If the same parser is called again at the same position, return the cached result.

```
Position 0: ExpressionParser -> success(consumed=7) [cached]
Position 0: TermParser -> success(consumed=1) [cached]
Position 2: TermParser -> success(consumed=5) [cached]
...
```

**Newcomer**: This means each position-parser combination is computed at most once, so overall it's O(n * m) (n=input length, m=number of parsers), which is linear in input length.

**Senior**: More precisely, memory consumption is O(n * m) -- trading memory for linear time.

**Newcomer**: Does unlaxer-parser use memoization?

**Senior**: `ParseContext` has a `doMemoize` flag that enables memoization. However, memoization isn't beneficial in all cases, so it's provided as an option.

---

### GLR Parser

**Newcomer**: What's GLR?

**Senior**: **GLR (Generalized LR)** is an extension of LR parsers that can handle **ambiguous grammars**. Where an LR parser would have a "shift/reduce conflict" requiring grammar modification, a GLR parser "splits" and tracks both possibilities in parallel.

**Newcomer**: Tracking in parallel? Like the parser cloning itself.

**Senior**: Nice description. Internally it uses a Graph-Structured Stack (GSS) to efficiently manage multiple parse paths. GLR is sometimes used for parsing languages with ambiguous syntax like C++.

**Newcomer**: The world of parsers is really deep...

---

### Why unlaxer-parser Is PEG-Based

**Newcomer**: So which approach does unlaxer-parser use?

**Senior**: unlaxer-parser is a **PEG-based Parser Combinator**.

**Newcomer**: Why did you choose PEG?

**Senior**: Several reasons.

**1. Deterministic with no ambiguity**

PEG's ordered choice ensures parse results are always unique. No need to worry "is this grammar ambiguous?"

**2. Intuitive to understand**

"Try A first, if it fails try B" is naturally close to human thinking.

**3. Great compatibility with Parser Combinators**

Each PEG construct (ordered choice, sequence, repetition, lookahead, negative lookahead) maps directly to a Java class.

**4. No parser generator required**

Unlike ANTLR or yacc, there's no need to generate code from a grammar file. You write parsers directly as Java code. IDE completion and refactoring work as-is.

**5. Incrementally extensible**

Start with small parsers and combine them into bigger ones as needed.

**Newcomer**: I can see the benefits. Are there any drawbacks?

**Senior**: Of course.

- **Left recursion** can't be written directly. We'll cover this in detail in Part 6
- There's backtracking cost (mitigated by memoization)
- Theoretically, some languages expressible in CFG can't be expressed in PEG (rarely a practical issue)

**Newcomer**: Not being able to write left recursion sounds significant...

**Senior**: There are practical workarounds, so don't worry. In unlaxer-parser, you can express it naturally using the repetition pattern (`ZeroOrMore`).

---

### Parsing Techniques Comparison Table

| Technique | Direction | Grammar | Ambiguity | Complexity | Representative Tools |
|------|------|------|--------|--------|-------------|
| Recursive Descent | Top-down | LL-equivalent | None | Case-dependent | Hand-written |
| LL(k) | Top-down | LL(k) | None | O(n) | ANTLR |
| LR | Bottom-up | LR(k) | None | O(n) | yacc, Bison |
| PEG | Top-down | PEG | None (ordered) | O(n) (Packrat) | unlaxer, PEG.js |
| GLR | Bottom-up | General CFG | Allowed | O(n^3) worst | Elkhound, Tree-sitter |
| Earley | Neither | General CFG | Allowed | O(n^3) worst | MARPA |

**Newcomer**: Looking at this comparison, PEG achieves both "top-down clarity" and "linear time efficiency."

**Senior**: Right. unlaxer-parser leverages PEG's advantages while using Java's type system to safely assemble parsers. It's a Parser Combinator. Let's look at the Parser Combinator approach next.

---

[<- Part 1: What Is a Parser?](#part-1-what-is-a-parser) | [Next: Part 3 Parser Combinator ->](#part-3-the-parser-combinator-approach)

---

## Part 3: The Parser Combinator Approach

[<- Part 2: The World of Parsing Techniques](#part-2-the-world-of-parsing-techniques) | [Next: Part 4 Terminal Parsers ->](#part-4-terminal-parsers)

---

**Newcomer**: Senior, what's a "Parser Combinator"? A combinator that combines parsers?

**Senior**: Yes, literally a "parser combinator." The idea is simple: **build small parsers as components and combine them to form larger parsers**.

**Newcomer**: Like LEGO blocks?

**Senior**: Exactly. Individual LEGO blocks are "terminal parsers," and the rules for connecting blocks together are "combinators."

---

### Origins: Haskell's Parsec

**Newcomer**: When did Parser Combinators start being used?

**Senior**: The concept developed in the functional programming world in the 1990s. The most famous one is **Parsec**, a Parser Combinator library written in Haskell, published by Daan Leijen in 2001.

**Newcomer**: Haskell is a functional language, right? Is there a connection between Parser Combinators and functional programming?

**Senior**: A deep one. In functional programming, it's natural to treat "functions as values." Since parsers are a kind of function, "higher-order functions" that take parsers as arguments and return parsers are easy to write.

```haskell
-- Haskell (Parsec) example
expr :: Parser Double
expr = do
  t <- term
  rest t
  where
    rest acc = (do char '+'; t <- term; rest (acc + t))
           <|> (do char '-'; t <- term; rest (acc - t))
           <|> return acc
```

**Newcomer**: Is `<|>` the choice combinator?

**Senior**: Yes. In Parsec, `<|>` corresponds to PEG's ordered choice -- "try A, if it fails try B." The `do` notation expresses sequencing (Chain).

---

### Scala's Parser Combinator

**Newcomer**: Scala has something similar too, right?

**Senior**: Scala had Parser Combinators in its standard library at one point (later separated). Since Scala is a JVM language with operator overloading support, you can write in a style close to Haskell.

```scala
// Scala example
def expr: Parser[Double] = term ~ rep("+" ~ term | "-" ~ term) ^^ { ... }
def term: Parser[Double] = factor ~ rep("*" ~ factor | "/" ~ factor) ^^ { ... }
def factor: Parser[Double] = number | "(" ~> expr <~ ")"
```

**Newcomer**: `~` is sequence, `|` is choice, `rep` is repetition?

**Senior**: Right. `~` corresponds to Chain, `|` to Choice, `rep` to ZeroOrMore. You could say unlaxer-parser's design realizes these concepts in Java.

---

### Realization in Java -- unlaxer's Design

**Newcomer**: But Java doesn't have operator overloading like Haskell or Scala. How do you implement Parser Combinators?

**Senior**: In Java, you use **class inheritance and composition**. Each combinator is defined as a class that receives child parsers through its constructor.

```java
// Haskell:  expr <|> term
// Scala:    expr | term
// unlaxer:  new Choice(exprParser, termParser)

// Haskell:  a >> b >> c
// Scala:    a ~ b ~ c
// unlaxer:  new Chain(aParser, bParser, cParser)

// Haskell:  many a
// Scala:    rep(a)
// unlaxer:  new ZeroOrMore(aParser)
```

**Newcomer**: The Java version is a bit more verbose, but it's doing the same thing.

**Senior**: Yes. It loses to Haskell in syntactic conciseness, but Java has its own advantages.

1. **IDE support** -- Completion, refactoring, debugging in IntelliJ or Eclipse
2. **Type safety** -- Parser construction errors detected at compile time
3. **Performance** -- JVM optimizations apply
4. **Ecosystem** -- Can be combined with Java's vast library ecosystem

---

### Parser.get() -- Retrieving Parsers as Singletons

**Newcomer**: I often see `Parser.get(SomeParser.class)` in unlaxer-parser code. What is that?

**Senior**: In unlaxer-parser, parsers are basically managed as **singletons**. `Parser.get()` is a factory method for getting parser instances.

```java
// Get a parser singleton
NumberParser numberParser = Parser.get(NumberParser.class);

// Internally managed by ParserFactoryByClass
public static <T extends Parser> T get(Class<T> clazz) {
    return ParserFactoryByClass.get(clazz);
}
```

**Newcomer**: Why singletons? Can't I just `new` them each time?

**Senior**: Two reasons.

**1. Memory efficiency**: Parsers are often stateless. There's no need to create multiple instances for the same grammar rule.

**2. Circular reference resolution**: This is the key reason. With circular references like `Expression` -> `Factor` -> `(Expression)`, creating instances with `new` would cause an infinite loop. With singletons, an already-created instance is returned, breaking the cycle.

**Newcomer**: Ah, so it's necessary to handle the grammar's recursive structure!

**Senior**: However, there's also a `Parser.newInstance()` method for intentionally creating new instances. It's used in tinyexpression's `AbstractNumberFactorParser` when defining the contents inside parentheses.

```java
// AbstractNumberFactorParser.java
parsers.add(new ParenthesesParser(
    Parser.newInstance(expresionParserClazz)
));
```

---

### Lazy vs Constructed -- Why Lazy Evaluation Is Needed

**Newcomer**: What's the difference between `LazyChain` and `Chain`, `LazyChoice` and `Choice`? I see lots of `Lazy` versions in your code.

**Senior**: The key difference is **when child parsers are initialized**.

**`Chain` (Constructed version)** receives child parsers **immediately** in its constructor:

```java
// Child parsers are determined immediately
new Chain(parserA, parserB, parserC)
```

**`LazyChain` (Lazy version)** **defers** obtaining child parsers. Child parsers aren't created until the `getLazyParsers()` method is called:

```java
public class MyParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        // Child parsers are constructed here for the first time
        return new Parsers(
            Parser.get(ParserA.class),
            Parser.get(ParserB.class)
        );
    }
}
```

**Newcomer**: Why is laziness needed?

**Senior**: The **circular reference** problem I mentioned earlier. Recall the calculator grammar:

```
Expression -> Term -> Factor -> '(' Expression ')'
```

If all parsers required child parsers in their constructors:
1. Try to create `ExpressionParser` -> needs `TermParser`
2. Try to create `TermParser` -> needs `FactorParser`
3. Try to create `FactorParser` -> needs `ExpressionParser`
4. Try to create `ExpressionParser` -> ... (infinite loop!)

**Newcomer**: The chicken-and-egg problem!

**Senior**: With `LazyChain` or `LazyChoice`, the parser objects themselves can be created first. Child parser reference resolution happens later (when parsing actually begins).

```
1. Create ExpressionParser instance (child parsers not yet resolved)
2. Create TermParser instance (child parsers not yet resolved)
3. Create FactorParser instance (child parsers not yet resolved)
4. When parsing starts, getLazyParsers() is called and mutual references are resolved
```

**Newcomer**: I see, that's why tinyexpression's parsers all inherit from `LazyChain` or `LazyChoice`.

**Senior**: On the other hand, when there's no circular reference (like a simple `Chain(wordA, wordB)` case), you can use the `Constructed` version without problems. In fact, `ZeroOrMore` and `WhiteSpaceDelimitedChain` are created directly with `new` inside `AbstractNumberExpressionParser`.

```java
// AbstractNumberExpressionParser.java
parsers.add(new ZeroOrMore(
    new WhiteSpaceDelimitedChain(
        new Choice(
            Parser.get(PlusParser.class),
            Parser.get(MinusParser.class)
        ),
        Parser.get(termParserClazz)
    )
));
```

**Newcomer**: The "outer frame" that might have cycles uses Lazy, while the "inner parts" that don't cycle can use Constructed. Got it.

**Senior**: Perfect understanding.

---

[<- Part 2: The World of Parsing Techniques](#part-2-the-world-of-parsing-techniques) | [Next: Part 4 Terminal Parsers ->](#part-4-terminal-parsers)

---

## Part 4: Terminal Parsers

[<- Part 3: Parser Combinator](#part-3-the-parser-combinator-approach) | [Next: Part 5 Combinator Parsers ->](#part-5-combinator-parsers)

---

**Newcomer**: Senior, what are terminal parsers?

**Senior**: Terminal parsers are the most basic parsers that **directly match strings**. In our LEGO analogy, they are the individual blocks themselves. They don't contain other parsers -- they judge whether the input string "matches or not."

**Newcomer**: So they're the "leaves" of the parser hierarchy.

**Senior**: Exactly. In unlaxer-parser, classes implementing the `TerminalSymbol` interface are terminal parsers. There are dozens of them. Let me introduce them by category.

---

### WordParser -- Literal String Match

**Newcomer**: What's the most basic parser?

**Senior**: `WordParser`. It checks for an exact match with a specified string.

```java
// A parser that matches the string "hello"
WordParser helloParser = new WordParser("hello");

// Case-insensitive version
WordParser helloIgnoreCase = new WordParser("hello", true);
```

**Newcomer**: Simple. Like a fixed string match in regular expressions.

**Senior**: Right. `WordParser` compares the contents of a `Source` object code point by code point. Internally it uses a `Slicer` class for efficient substring extraction.

**Newcomer**: Where is it used in tinyexpression?

**Senior**: For example, `PlusParser` and `MinusParser` use WordParser internally. They match the single-character literals `+` and `-`.

---

### SingleCharacterParser / MappedSingleCharacterParser -- Single Character Match

**Newcomer**: Is there a parser that matches just one character?

**Senior**: Yes. `SingleCharacterParser` is an abstract class used by overriding its `isMatch(char target)` method.

```java
public abstract class SingleCharacterParser extends AbstractTokenParser
    implements TerminalSymbol {

    public abstract boolean isMatch(char target);

    @Override
    public Token getToken(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Source peeked = parseContext.peek(tokenKind, new CodePointLength(1));
        Token token =
            peeked.isPresent() && (invertMatch ^ isMatch(peeked.charAt(0))) ?
                new Token(tokenKind, peeked, this) :
                Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
        return token;
    }
}
```

**Newcomer**: Is `invertMatch` for negation? Inverting the match result?

**Senior**: Yes. When `invertMatch` is `true`, it matches characters where `isMatch()` returns `false`. Used in combination with the Not parser.

**Senior**: `MappedSingleCharacterParser` is a variant of `SingleCharacterParser` that can apply a transformation (mapping) to the matched character.

---

### NumberParser -- Numeric Literals

**Newcomer**: Parsing numbers seems complex. Not just integers, but decimals too...

**Senior**: `NumberParser` absorbs exactly that complexity. Let's look at the implementation.

```java
public class NumberParser extends LazyChain implements StaticParser {

    static final Parser digitParser = new DigitParser();
    static final Parser signParser = new SignParser();
    static final Parser pointParser = new PointParser();
    static final OneOrMore digitsParser = new OneOrMore(Name.of("any-digit"), digitParser);

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // + or -
            new Optional(Name.of("optional-signParser"), signParser),
            new Choice(
                // 12.3
                new Chain(Name.of("digits-point-digits"), digitsParser, pointParser, digitsParser),
                // 12.
                new Chain(Name.of("digits-point"), digitsParser, pointParser),
                // 12
                new Chain(Name.of("digits"), digitsParser),
                // .3
                new Chain(Name.of("point-digits"), pointParser, digitsParser)
            ),
            // e-3
            new Optional(ExponentParser.class)
        );
    }
}
```

**Newcomer**: Whoa, this itself is composed of combinators! It says terminal parser, but it has internal structure.

**Senior**: Good observation. `NumberParser` technically inherits from `LazyChain` making it a non-terminal parser, but from a user's perspective it's usable as "one component that matches numeric literals." That's the power of parser abstraction.

**Newcomer**: Summarizing the formats it matches:
- `+3.14` (signed decimal)
- `-42` (negative integer)
- `12.` (integer + dot)
- `.5` (dot + fractional part)
- `1.5e-3` (exponential notation)

**Senior**: Right. Notice the order of the Choices too. `digits-point-digits` comes before `digits-point`. Since PEG uses ordered choice, we need to try longer patterns first. If `digits-point` came first, for `12.3` it would match only `12.` and stop.

**Newcomer**: That's important! Is that a PEG-specific gotcha?

**Senior**: Yes. We'll touch on it again in Part 10 under "common mistakes," but Choice ordering is one of the most important things to watch for when writing PEG parsers.

---

### IdentifierParser (clang/) -- C-Style Identifiers

**Newcomer**: How do you parse variable names?

**Senior**: The `IdentifierParser` in the `clang` package parses C-style identifiers.

```java
public class IdentifierParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AlphabetUnderScoreParser.class),
            new ZeroOrMore(AlphabetNumericUnderScoreParser.class)
        );
    }
}
```

**Newcomer**: "Starts with a letter or underscore, followed by zero or more alphanumeric characters or underscores." The rule shared by C/Java/Python.

**Senior**: In BNF:

```bnf
<identifier> ::= [a-zA-Z_] [a-zA-Z0-9_]*
```

**Senior**: `AlphabetUnderScoreParser` and `AlphabetNumericUnderScoreParser` are POSIX-based parsers that determine character types.

---

### SingleQuotedParser / DoubleQuotedParser -- Quoted Strings

**Newcomer**: How do you parse string literals (things in double quotes)?

**Senior**: There are `DoubleQuotedParser` and `SingleQuotedParser`.

```java
// Matches double-quoted strings like "hello world"
DoubleQuotedParser dqParser = new DoubleQuotedParser();

// Matches single-quoted strings like 'hello world'
SingleQuotedParser sqParser = new SingleQuotedParser();
```

**Newcomer**: Can it handle escape characters (like `\"`)?

**Senior**: There's an `EscapeInQuotedParser` that processes backslash escape sequences. `QuotedParser` uses it internally.

---

### EndOfSourceParser / StartOfSourceParser -- Boundaries

**Newcomer**: Are there parsers that match the beginning or end of input?

**Senior**: Yes. They correspond to `^` and `$` in regular expressions.

```java
// Matches the start of input (doesn't consume characters)
StartOfSourceParser start = new StartOfSourceParser();

// Matches the end of input
EndOfSourceParser end = new EndOfSourceParser();
```

**Newcomer**: What does "doesn't consume characters" mean?

**Senior**: Even though the parser determines "matched," it doesn't advance the cursor position. The next parser starts reading from the same position. Same concept as zero-width assertions in regular expressions.

---

### POSIX Parsers (21 types)

**Newcomer**: What are POSIX parsers?

**Senior**: Parser classes corresponding to POSIX character classes, in the `org.unlaxer.parser.posix` package. They correspond to regex patterns like `[:alpha:]` and `[:digit:]`.

| Parser | Matches | Regex equivalent |
|---------|----------|-------------|
| `DigitParser` | Digits 0-9 | `[0-9]` |
| `AlphabetParser` | Letters a-zA-Z | `[a-zA-Z]` |
| `AlphabetNumericParser` | Alphanumeric | `[a-zA-Z0-9]` |
| `AlphabetUnderScoreParser` | Letters + _ | `[a-zA-Z_]` |
| `AlphabetNumericUnderScoreParser` | Alphanumeric + _ | `[a-zA-Z0-9_]` |
| `UpperParser` | Uppercase A-Z | `[A-Z]` |
| `LowerParser` | Lowercase a-z | `[a-z]` |
| `SpaceParser` | Whitespace | `\s` |
| `BlankParser` | Space/tab | `[ \t]` |
| `PunctuationParser` | Punctuation | `[[:punct:]]` |
| `ControlParser` | Control characters | `[[:cntrl:]]` |
| `GraphParser` | Visible characters | `[[:graph:]]` |
| `PrintParser` | Printable characters | `[[:print:]]` |
| `AsciiParser` | ASCII characters | `[\x00-\x7F]` |
| `XDigitParser` | Hex digits | `[0-9a-fA-F]` |
| `WordParser` (posix) | Alphanumeric + _ | `\w` |
| `ColonParser` | Colon : | `:` |
| `CommaParser` | Comma , | `,` |
| `DotParser` | Dot . | `\.` |
| `HashParser` | Hash # | `#` |
| `SemiColonParser` | Semicolon ; | `;` |

**Newcomer**: There are so many! Are they all derived from `SingleCharacterParser`?

**Senior**: Basically yes. Each parser overrides `isMatch(char target)` to determine if the character belongs to the target class.

```java
// DigitParser example
public class DigitParser extends SingleCharacterParser {
    @Override
    public boolean isMatch(char target) {
        return Character.isDigit(target);
    }
}
```

---

### ASCII Parsers (12 types)

**Newcomer**: ASCII parsers are separate from POSIX?

**Senior**: They're in the `org.unlaxer.parser.ascii` package. Parsers matching specific ASCII symbol characters.

| Parser | Match character |
|---------|----------|
| `PlusParser` | `+` |
| `MinusParser` | `-` |
| `PointParser` | `.` |
| `GreaterThanParser` | `>` |
| `LessThanParser` | `<` |
| `EqualParser` | `=` |
| `DivisionParser` | `/` |
| `SlashParser` | `/` |
| `BackSlashParser` | `\` |
| `DoubleQuoteParser` | `"` |
| `LeftParenthesisParser` | `(` |
| `RightParenthesisParser` | `)` |

**Newcomer**: `DivisionParser` and `SlashParser` match the same character `/`, don't they?

**Senior**: Yes. The different names are for semantic distinction. Use `DivisionParser` (division) in arithmetic expressions, `SlashParser` for path separators. Parser names are reflected in parse tree nodes, so having separate classes is useful when you want to distinguish meaning.

---

### WildCard Parsers

**Newcomer**: There are WildCard parsers too? Wildcards that match anything?

**Senior**: Right. They correspond to `.` and `.*` in regular expressions.

| Parser | Behavior | Regex equivalent |
|---------|------|-------------|
| `WildCardCharacterParser` | Any single character | `.` |
| `WildCardStringParser` | Any string (up to a terminator) | `.*?` (non-greedy) |
| `WildCardLineParser` | Any string to end of line | `.*$` |
| `WildCardInterleaveParser` | Wildcard for unordered matching | (special) |

**Newcomer**: Is `WildCardStringParser` a non-greedy match?

**Senior**: Yes. `WildCardStringParser` is used in combination with `WildCardStringTerninatorParser` to express "anything between here and there." Making it greedy would consume everything to the end of the input.

---

### Practical Example: How Terminal Parsers Are Used in tinyexpression

**Newcomer**: Which terminal parsers are specifically used in tinyexpression?

**Senior**: Here are the main ones.

**Numeric literals**: `NumberParser` is used as one of the choices in `AbstractNumberFactorParser`:

```java
// AbstractNumberFactorParser.java
parsers.add(NumberParser.class);
```

**Variable names**: `NumberVariableParser` internally uses a pattern equivalent to `IdentifierParser`:

```java
parsers.add(NumberVariableParser.class);
```

**Operators**: `PlusParser`, `MinusParser`, `MultipleParser`, `DivisionParser` are defined in tinyexpression's parser package. They use `WordParser` internally.

**Newcomer**: Is tinyexpression's `PlusParser` different from `org.unlaxer.parser.ascii.PlusParser`?

**Senior**: Good question. tinyexpression has its own `PlusParser` at `org.unlaxer.tinyexpression.parser.PlusParser`. It may have additional behavior like whitespace handling in the tinyexpression context. Same name but different package means different class.

---

[<- Part 3: Parser Combinator](#part-3-the-parser-combinator-approach) | [Next: Part 5 Combinator Parsers ->](#part-5-combinator-parsers)

---

## Part 5: Combinator Parsers

[<- Part 4: Terminal Parsers](#part-4-terminal-parsers) | [Next: Part 6 Left/Right Associativity ->](#part-6-left-associativity-right-associativity-and-operator-precedence)

---

**Newcomer**: If terminal parsers are the "LEGO blocks," then combinators are "the rules for connecting blocks," right? What combinators are available?

**Senior**: unlaxer-parser provides a rich set of combinators, all in the `org.unlaxer.parser.combinator` package. Let's go through them one by one.

---

### LazyChain (Sequence) -- Match A B C in Order

**Newcomer**: What's the most basic combinator?

**Senior**: `LazyChain` (and `Chain`). **Sequence** -- applies multiple parsers in order, and the **whole thing succeeds only if all succeed**.

```bnf
A B C
```

```java
public class MyParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AParser.class),
            Parser.get(BParser.class),
            Parser.get(CParser.class)
        );
    }
}

// Constructed version (immediate)
Parser myParser = new Chain(aParser, bParser, cParser);
```

**Newcomer**: All of A, B, C must match, right?

**Senior**: Right. If A matches but B fails, the entire Chain fails, and the portion consumed by A is rolled back. This is PEG's backtracking.

**Newcomer**: Rollback?

**Senior**: It uses the `ParseContext`'s transaction mechanism. Start a transaction with `begin()`, if all child parsers succeed do `commit()`, if any fails do `rollback()` to restore the cursor position.

```
Input: "hello world"
Chain(WordParser("hello"), SpaceParser, WordParser("world"))

1. begin()
2. "hello" matches -> advance cursor by 5
3. " " matches -> advance cursor by 1
4. "world" matches -> advance cursor by 5
5. All succeeded -> commit() -> 11 characters consumed total
```

```
Input: "hello earth"
Chain(WordParser("hello"), SpaceParser, WordParser("world"))

1. begin()
2. "hello" matches -> advance cursor by 5
3. " " matches -> advance cursor by 1
4. "world" doesn't match -> fail
5. rollback() -> restore cursor to original position
```

---

### LazyChoice (Choice) -- One of A | B | C

**Newcomer**: How do you do "A or B or C" selection?

**Senior**: `LazyChoice` (and `Choice`). Implements PEG's **ordered choice**. Tries child parsers from the beginning and adopts the first one that matches.

```bnf
A | B | C
```

```java
public class MyChoiceParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AParser.class),
            Parser.get(BParser.class),
            Parser.get(CParser.class)
        );
    }
}

// Constructed version
Parser myParser = new Choice(aParser, bParser, cParser);
```

**Newcomer**: They're tried in order, right? Can things go wrong if the order is wrong?

**Senior**: Yes. The classic problem is "a shorter pattern consuming what a longer pattern should match."

```java
// Bad example
new Choice(
    new WordParser("if"),        // Matches "if"
    new WordParser("ifdef")      // "ifdef" is never tried!
)

// Correct example
new Choice(
    new WordParser("ifdef"),     // Try the longer one first
    new WordParser("if")
)
```

**Newcomer**: The first 2 characters of "ifdef" match "if". Since PEG adopts the first successful choice...

**Senior**: Exactly. This is one of the most important things to watch when writing PEG parsers. **Put longer patterns first** -- remember that.

---

### ZeroOrMore -- { A } -- Zero or More Repetitions

**Newcomer**: Is there a repetition combinator?

**Senior**: `ZeroOrMore` (and `LazyZeroOrMore`) handles zero or more repetitions. Equivalent to EBNF's `{A}` or regex's `A*`.

```bnf
{ A }
```

```java
// Match zero or more digits
Parser digits = new ZeroOrMore(Parser.get(DigitParser.class));

// Lazy version
public class MyRepeater extends LazyZeroOrMore {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(Parser.get(SomeParser.class));
    }
}
```

**Newcomer**: If zero is OK, does it succeed even when nothing matches?

**Senior**: Yes. `ZeroOrMore` always succeeds. If nothing matches, it returns an empty token list and succeeds.

**Newcomer**: How does it work internally?

**Senior**: It's a derived class of `LazyOccurs` where `min()` returns 0 and `max()` returns `Integer.MAX_VALUE`:

```java
public abstract class LazyZeroOrMore extends LazyOccurs {
    @Override
    public int min() { return 0; }

    @Override
    public int max() { return Integer.MAX_VALUE; }
}
```

**Newcomer**: `max()` is `Integer.MAX_VALUE` -- effectively unlimited.

**Senior**: Yes. But there's a caveat: **if the parser inside ZeroOrMore matches the empty string, it becomes an infinite loop**.

```java
// Dangerous! Optional matches the empty string, so ZeroOrMore loops forever
new ZeroOrMore(new Optional(someParser))  // Infinite loop!
```

**Newcomer**: That's scary...

**Senior**: unlaxer-parser has a safety measure that checks whether the cursor advanced in each iteration and terminates the loop if it didn't. But it's better to be careful at design time.

---

### OneOrMore -- A+ -- One or More

**Newcomer**: Is there a "one or more" version?

**Senior**: `OneOrMore` (and `LazyOneOrMore`). Fails unless it matches at least once. Equivalent to regex `A+`.

```java
// One or more digits -> digit string
Parser oneOrMoreDigits = new OneOrMore(Name.of("digits"), Parser.get(DigitParser.class));
```

**Senior**: In `NumberParser`, `digitsParser` is exactly this:

```java
static final OneOrMore digitsParser = new OneOrMore(Name.of("any-digit"), digitParser);
```

**Newcomer**: `min()` is 1 and `max()` is `Integer.MAX_VALUE`.

```java
public abstract class LazyOneOrMore extends LazyOccurs {
    @Override
    public int min() { return 1; }

    @Override
    public int max() { return Integer.MAX_VALUE; }
}
```

---

### Optional / ZeroOrOne -- [ A ] -- Present or Absent

**Newcomer**: What about a "may or may not be present" parser?

**Senior**: `Optional` (also known as `ZeroOrOne`). Equivalent to EBNF's `[A]` or regex's `A?`.

```java
// Sign (+/-) is optional
Parser optionalSign = new Optional(Name.of("optional-sign"), signParser);
```

**Senior**: Used in `NumberParser`:

```java
new Optional(Name.of("optional-signParser"), signParser)
```

**Newcomer**: Match zero or one time. Like `ZeroOrMore` but max once.

**Senior**: Internally `min()=0`, `max()=1`. `Optional` always succeeds. Even if it doesn't match, it's treated as success.

---

### NonOrdered (Interleave) -- Any Order

**Newcomer**: What about "I don't care about the order, but everything should appear"?

**Senior**: `NonOrdered`. Same concept as RelaxNG's interleave pattern -- matches child parsers in **any order**.

```java
// A, B, C in any order is OK
Parser nonOrdered = new NonOrdered(aParser, bParser, cParser);

// "B A C", "C B A", "A B C" all match
```

**Newcomer**: Handy! Could be used for things like HTML attributes where order doesn't matter.

**Senior**: Exactly. But all child parsers must match exactly once each. Missing even one means failure.

---

### Not -- Negative Lookahead

**Newcomer**: Is there a parser to check "not this character"?

**Senior**: The `Not` parser. Corresponds to PEG's negative lookahead `!A`. **Doesn't consume characters** -- just verifies that the child parser doesn't match.

```java
// Verify the next character is not a digit (doesn't consume the character)
Parser notDigit = new Not(Parser.get(DigitParser.class));
```

**Senior**: Let's look at the internal implementation:

```java
public class Not extends ConstructedSingleChildParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.startParse(this, parseContext, tokenKind, invertMatch);
        parseContext.begin(this);

        Parsed parsed = getChild().parse(parseContext, TokenKind.matchOnly, invertMatch);

        if (parsed.isSucceeded()) {
            // Child succeeded -> Not fails. Rollback
            parseContext.rollback(this);
            return Parsed.FAILED;
        }

        // Child failed -> Not succeeds (no characters consumed)
        Parsed committed = new Parsed(parseContext.commit(this, TokenKind.matchOnly));
        return committed;
    }
}
```

**Newcomer**: It inverts the child parser's result, and uses `TokenKind.matchOnly` to not consume characters.

**Senior**: Right. `Not` is an assertion that "this pattern doesn't appear here." Same as regex's negative lookahead `(?!...)`.

---

### MatchOnly -- Positive Lookahead (No Consumption)

**Newcomer**: Is `Not` the only one that "checks without consuming characters"?

**Senior**: There's also `MatchOnly`. This is positive lookahead, corresponding to PEG's `&A`. Verifies that the child parser matches but **doesn't advance the cursor**.

```java
// Verify the next character is a digit, but don't consume it
Parser lookahead = new MatchOnly(Parser.get(DigitParser.class));
```

**Senior**: Internally calls the child parser with `TokenKind.matchOnly`:

```java
public class MatchOnly extends ConstructedSingleChildParser implements MetaFunctionParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.begin(this);
        Parsed parsed = getChild().parse(parseContext, TokenKind.matchOnly, invertMatch);
        if (parsed.isFailed()) {
            parseContext.rollback(this);
            return Parsed.FAILED;
        }
        // Matched but not consumed
        ...
    }
}
```

**Newcomer**: The relationship between `Not` and `MatchOnly`:

| Parser | Child succeeds | Child fails | Consumes |
|---------|---------|---------|------|
| `MatchOnly` | Success | Failure | No |
| `Not` | Failure | Success | No |

**Senior**: Perfect understanding.

---

### Repeat -- Count-Specified Repetition

**Newcomer**: What about "exactly 3 times" or "2 to 5 times"?

**Senior**: `Repeat` (and `LazyRepeat`). You can specify `min` and `max`.

```java
// Exactly 3 digits (e.g., "123")
Parser threeDigits = new Repeat(3, 3, digitParser);

// 2 to 5 digits (e.g., "12", "12345")
Parser twoToFiveDigits = new Repeat(2, 5, digitParser);
```

**Newcomer**: `ZeroOrMore` is `Repeat(0, MAX)`, `OneOrMore` is `Repeat(1, MAX)`, and `Optional` is `Repeat(0, 1)`, right?

**Senior**: Exactly. Internally they're all derived classes of `LazyOccurs`, expressed through `min()` and `max()`.

---

### ASTNode / NotASTNode -- AST Filtering

**Newcomer**: What are `ASTNode` and `NotASTNode`?

**Senior**: They're wrappers that control whether tokens generated by a parser are "included in the AST (abstract syntax tree) or not."

```java
// This parser's tokens ARE included in the AST
Parser astVisible = new ASTNode(someParser);

// This parser's tokens are NOT included in the AST (parentheses, whitespace, etc.)
Parser astHidden = new NotASTNode(someParser);
```

**Newcomer**: This is the mechanism for "accessing both CST and AST" from Part 1.

**Senior**: Right. Only tokens generated by parsers marked with `ASTNode` are included in `Token.filteredChildren`. Tokens marked with `NotASTNode` are excluded from `filteredChildren` but still present in `children` (original children).

**Newcomer**: Parentheses `(` `)` are syntactically necessary but semantically unnecessary for the AST.

**Senior**: There are also `ASTNodeRecursive` and `NotASTNodeRecursive`, which propagate AST marking recursively to descendant nodes. And `ASTNodeRecursiveGrandChildren` is a variant that applies only to grandchildren and beyond.

---

### WhiteSpaceDelimitedChain -- Whitespace-Delimited Sequence

**Newcomer**: In programming languages, whitespace is often used as a delimiter. Is there a combinator that automatically skips whitespace?

**Senior**: `WhiteSpaceDelimitedChain` (and `WhiteSpaceDelimitedLazyChain`). A Chain that allows whitespace (spaces, tabs, newlines) between child parsers.

```java
// "3 + 4", "3+4", and "3  +  4" all match
Parser expr = new WhiteSpaceDelimitedChain(
    numberParser,
    new WordParser("+"),
    numberParser
);
```

**Newcomer**: Is whitespace automatically skipped?

**Senior**: More precisely, optional whitespace parsers are inserted between each child parser. It matches whether whitespace is present or not.

**Senior**: Used in tinyexpression's `AbstractNumberExpressionParser`:

```java
parsers.add(new ZeroOrMore(
    new WhiteSpaceDelimitedChain(
        new Choice(
            Parser.get(PlusParser.class),
            Parser.get(MinusParser.class)
        ),
        Parser.get(termParserClazz)
    )
));
```

**Newcomer**: A `Choice` inside a `WhiteSpaceDelimitedChain`, and then wrapped in `ZeroOrMore` for repetition. Combining combinators!

---

### Practical Example: tinyexpression's NumberExpressionParser

**Newcomer**: Using the combinators we've learned, I'd like to see the overall picture of tinyexpression's expression parser.

**Senior**: Sure. In BNF:

```bnf
<expression> ::= <term> (('+' | '-') <term>)*
<term>       ::= <factor> (('*' | '/') <factor>)*
<factor>     ::= NUMBER | VARIABLE | '(' <expression> ')' | sin(...) | cos(...) | ...
```

**Senior**: In unlaxer-parser code:

```java
// AbstractNumberExpressionParser -- <expression>
public Parsers getLazyParsers(boolean withNakedVariable) {
    Parsers parsers = new Parsers();

    // <term>
    parsers.add(termParserClazz);

    // (('+' | '-') <term>)*
    parsers.add(new ZeroOrMore(
        new WhiteSpaceDelimitedChain(
            new Choice(
                Parser.get(PlusParser.class),   // '+'
                Parser.get(MinusParser.class)    // '-'
            ),
            Parser.get(termParserClazz)          // <term>
        )
    ));

    return parsers;
}
```

```java
// AbstractNumberTermParser -- <term>
public Parsers getLazyParsers(boolean withNakedVariable) {
    Parsers parsers = new Parsers();

    // <factor>
    parsers.add(Parser.get(factorParserClazz));

    // (('*' | '/') <factor>)*
    parsers.add(new ZeroOrMore(
        new WhiteSpaceDelimitedChain(
            new Choice(
                Parser.get(MultipleParser.class),   // '*'
                Parser.get(DivisionParser.class)     // '/'
            ),
            Parser.get(factorParserClazz)            // <factor>
        )
    ));

    return parsers;
}
```

```java
// AbstractNumberFactorParser -- <factor>
public Parsers getLazyParsers(boolean withNakedVariable) {
    Parsers parsers = new Parsers();

    parsers.add(NumberSideEffectExpressionParser.class);  // Side-effect expression
    parsers.add(NumberIfExpressionParser.class);           // if expression
    parsers.add(StrictTypedNumberMatchExpressionParser.class); // match expression
    parsers.add(NumberParser.class);                        // Numeric literal
    parsers.add(NumberVariableParser.class);                // $variableName

    if (withNakedVariable) {
        parsers.add(ExclusiveNakedVariableParser.class);   // Bare variable name
    }

    parsers.add(new ParenthesesParser(                     // '(' <expression> ')'
        Parser.newInstance(expresionParserClazz)
    ));

    parsers.add(SinParser.class);     // sin(...)
    parsers.add(CosParser.class);     // cos(...)
    parsers.add(TanParser.class);     // tan(...)
    parsers.add(SquareRootParser.class); // sqrt(...)
    parsers.add(MinParser.class);     // min(...)
    parsers.add(MaxParser.class);     // max(...)
    parsers.add(RandomParser.class);  // random()

    return parsers;
}
```

**Newcomer**: Ooh, so this can parse expressions like `3 + 4 * sin(0.5)`.

**Senior**: Right. Each parser has a small responsibility, and combinators compose them into a large parser. That's the power of Parser Combinators.

---

### Combinator Summary Table

| Combinator | BNF/PEG equivalent | Description | Lazy version |
|------------|-------------|------|--------|
| `Chain` | `A B C` | Sequence (succeeds if all succeed) | `LazyChain` |
| `Choice` | `A / B / C` | Ordered choice (adopts first success) | `LazyChoice` |
| `ZeroOrMore` | `A*` / `{A}` | Zero or more repetitions | `LazyZeroOrMore` |
| `OneOrMore` | `A+` | One or more repetitions | `LazyOneOrMore` |
| `Optional` | `A?` / `[A]` | Zero or one | `LazyOptional` |
| `ZeroOrOne` | `A?` | Alias for Optional | `LazyZeroOrOne` |
| `Repeat` | `A{m,n}` | Count-specified repetition | `LazyRepeat` |
| `Not` | `!A` | Negative lookahead | -- |
| `MatchOnly` | `&A` | Positive lookahead | -- |
| `NonOrdered` | (interleave) | Unordered match | -- |
| `ASTNode` | -- | AST filter (include) | -- |
| `NotASTNode` | -- | AST filter (exclude) | -- |
| `WhiteSpaceDelimitedChain` | -- | Whitespace-delimited sequence | `WhiteSpaceDelimitedLazyChain` |
| `Flatten` | -- | Flatten nested structures | -- |
| `Reverse` | -- | Reverse-order match | -- |
| `TagWrapper` | -- | Add tags | `RecursiveTagWrapper` |
| `ParserWrapper` | -- | Wrap a parser | -- |
| `ContainerParser` | -- | Parser container | -- |
| `PropagationStopper` | -- | Stop propagation | -- |

---

[<- Part 4: Terminal Parsers](#part-4-terminal-parsers) | [Next: Part 6 Left/Right Associativity ->](#part-6-left-associativity-right-associativity-and-operator-precedence)

---

## Part 6: Left Associativity, Right Associativity, and Operator Precedence

[<- Part 5: Combinator Parsers](#part-5-combinator-parsers) | [Next: Part 7 Tokens and Parse Trees ->](#part-7-tokens-and-parse-trees)

---

**Newcomer**: Senior, what's the answer to `3 - 2 - 1`?

**Senior**: 0. `(3 - 2) - 1 = 0`.

**Newcomer**: Right. But it wouldn't be `3 - (2 - 1) = 2`, would it? Why do we calculate from the left?

**Senior**: Good question. That's **left associativity**. Subtraction and division are defined as left-associative. When operators of the same precedence are adjacent, they **associate from left to right**.

---

### Left Associativity

**Senior**: Left-associative operators:

```
3 - 2 - 1   =   (3 - 2) - 1   =   0
12 / 4 / 3  =   (12 / 4) / 3  =   1
```

**Newcomer**: So with left associativity, the tree structure looks like:

```
Left associative: 3 - 2 - 1

        (-)
       /   \
     (-)    1
    /   \
   3     2
```

**Senior**: Right. The left side is deeper in the tree. Whatever is "computed first" appears at a deeper position.

---

### Right Associativity

**Newcomer**: Are there right-associative operators too?

**Senior**: Yes. The classic example is **exponentiation**.

```
2 ^ 3 ^ 4   =   2 ^ (3 ^ 4)   =   2 ^ 81   =   2417851639229258349412352
```

**Newcomer**: Wait, you calculate from the right?! `(2^3)^4 = 8^4 = 4096` is a completely different result.

**Senior**: Right-associative tree structure:

```
Right associative: 2 ^ 3 ^ 4

   (^)
  /   \
 2    (^)
     /   \
    3     4
```

**Newcomer**: The right side is at a deeper position.

**Senior**: Other right-associative operators include the assignment operator `=`. `a = b = c = 5` is interpreted as `a = (b = (c = 5))`.

---

### Operator Precedence

**Newcomer**: In `3 + 4 * 2`, `*` being computed first is a matter of precedence, right?

**Senior**: Yes. **Operator precedence** determines which operator associates first when different kinds of operators are present.

```
Common precedence (highest first):
1. Parentheses ()
2. Exponentiation ^
3. Unary operators +x, -x
4. Multiplication/Division *, /
5. Addition/Subtraction +, -
6. Comparison <, >, <=, >=
7. Equality ==, !=
8. Logical AND &&
9. Logical OR ||
10. Assignment =
```

**Newcomer**: Operators with higher precedence are "computed first" = appear at deeper positions in the tree.

**Senior**: Exactly. To express this precedence in parser grammar, you use **grammar hierarchy**.

---

### Implementation in tinyexpression

**Senior**: tinyexpression uses this hierarchy:

```
NumberExpressionParser (+, -)    ... Low precedence (top of tree)
    +-- NumberTermParser (*, /)  ... Medium precedence (middle of tree)
        +-- NumberFactorParser   ... High precedence (bottom of tree)
            +-- NUMBER
            +-- VARIABLE
            +-- '(' Expression ')'
```

**Newcomer**: The nesting of "Expression contains Term, Term contains Factor" expresses precedence.

**Senior**: Right. The grammar rules themselves encode precedence. `*` and `/` are handled at the `Term` level, so they bind before `+` and `-` at the `Expression` level.

```
Input: 3 + 4 * 2

Expression parse:
+-- Term parse: "3" -> Factor(3) only -> Term = 3
+-- "+"
+-- Term parse: "4 * 2"
    +-- Factor(4)
    +-- "*"
    +-- Factor(2)
    -> Term = 4 * 2

Resulting tree:
    Expression(+)
    +-- Term -> Factor(3)
    +-- Term(*)
        +-- Factor(4)
        +-- Factor(2)
```

**Newcomer**: Since `*` is handled inside `Term`, it appears at a deeper position in the tree.

---

### Why Left Recursion Is a Problem in PEG

**Newcomer**: What's left recursion?

**Senior**: When a grammar rule contains itself at the **left edge** of its definition, it's called "left recursion."

```bnf
# Left recursion example (direct left recursion)
<expression> ::= <expression> '+' <term> | <term>
```

**Newcomer**: `expression` appears at the beginning of `expression`'s definition.

**Senior**: Mathematically, this is a valid grammar that expresses left-associative addition. LR parsers (yacc/Bison) can use this form naturally.

But in PEG (and top-down parsers in general), it's a big problem. Because:

```
To parse expression...
-> First need to parse expression
  -> First need to parse expression
    -> First need to parse expression
      -> ... (infinite recursion!)
```

**Newcomer**: It never ends! It recurses without consuming anything.

**Senior**: Exactly. In PEG-based parsers, left recursion causes a **stack overflow**.

---

### Workaround in unlaxer (Repetition Pattern)

**Newcomer**: Then how do you express left-associative operators?

**Senior**: Rewrite as a **repetition pattern**. Left-recursive grammar:

```bnf
# Left recursion (can't use in PEG)
<expression> ::= <expression> '+' <term> | <term>
```

Convert to repetition:

```bnf
# Repetition (works in PEG)
<expression> ::= <term> ('+' <term>)*
```

**Newcomer**: They express the same thing semantically!

**Senior**: Right. `<term> ('+' <term>)*` means "first one term, then `'+' term` repeated zero or more times."

tinyexpression's `AbstractNumberExpressionParser` uses exactly this pattern:

```java
// <expression> ::= <term> (('+' | '-') <term>)*
parsers.add(termParserClazz);  // First <term>
parsers.add(new ZeroOrMore(     // (('+' | '-') <term>)*
    new WhiteSpaceDelimitedChain(
        new Choice(
            Parser.get(PlusParser.class),
            Parser.get(MinusParser.class)
        ),
        Parser.get(termParserClazz)
    )
));
```

**Newcomer**: `ZeroOrMore` replaces left recursion.

**Senior**: Right. This is the standard technique for building **operator-precedence parsers** in PEG-based parsers.

---

### Expressing Associativity in the AST

**Newcomer**: With the repetition pattern, the parse tree is a flat list. How do you build the left-associative tree structure?

**Senior**: At the parse tree stage it is indeed a flat list:

```
Input: 3 - 2 - 1

Parse tree:
Expression
+-- Term(3)      <- First term
+-- "-"
+-- Term(2)      <- Repetition round 1
+-- "-"
+-- Term(1)      <- Repetition round 2
```

**Senior**: Converting this to a left-associative AST is done in the **AST transformation (AST mapping)** phase after parsing. unlaxer-parser provides utilities like `RecursiveZeroOrMoreBinaryOperator` and `OperatorOperandPattern` to support this conversion.

```
Convert to left-associative:
    (-)
   /   \
 (-)    1
/   \
3    2
```

**Newcomer**: Parsing and AST transformation are separate phases.

**Senior**: Right. The parser focuses on the "string -> parse tree" transformation, while "parse tree -> AST" conversion is done in a separate layer. Separation of concerns.

**Newcomer**: Is right associativity the same pattern?

**Senior**: Right associativity is slightly different. After parsing with the repetition pattern, you associate from the right:

```
Input: 2 ^ 3 ^ 4

Parse tree:
Power
+-- Factor(2)
+-- "^"
+-- Factor(3)
+-- "^"
+-- Factor(4)

Convert to right-associative:
   (^)
  /   \
 2    (^)
     /   \
    3     4
```

**Senior**: This conversion can also be done in the AST mapping layer. There's a mechanism for specifying associativity with annotations like `@leftAssoc` and `@rightAssoc`.

---

### Adding Precedence Levels

**Newcomer**: What if I want to add a new operator?

**Senior**: Add a level to the grammar hierarchy. For example, to add exponentiation `^`:

```
Expression (+, -)       <- Lowest precedence
  +-- Term (*, /)       <- Medium precedence
      +-- Power (^)     <- High precedence (newly added)
          +-- Factor    <- Highest precedence
```

```java
// PowerParser (new)
public class PowerParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        Parsers parsers = new Parsers();
        parsers.add(Parser.get(FactorParser.class));
        parsers.add(new ZeroOrMore(
            new WhiteSpaceDelimitedChain(
                new WordParser("^"),
                Parser.get(FactorParser.class)
            )
        ));
        return parsers;
    }
}
```

**Newcomer**: And then replace Factor with Power in Term?

**Senior**: Exactly. Change `Term` to reference `Power` instead of `Factor`. Adding one level to the hierarchy adds one higher-precedence operator.

**Newcomer**: Parser hierarchy = operator precedence. Very intuitive.

---

[<- Part 5: Combinator Parsers](#part-5-combinator-parsers) | [Next: Part 7 Tokens and Parse Trees ->](#part-7-tokens-and-parse-trees)

---

## Part 7: Tokens and Parse Trees

[<- Part 6: Left/Right Associativity](#part-6-left-associativity-right-associativity-and-operator-precedence) | [Next: Part 8 Building Your Own Parser ->](#part-8-building-your-own-parser)

---

**Newcomer**: What data structure does the parser's result actually have?

**Senior**: unlaxer-parser's parse result consists of three classes: `Token`, `ParseContext`, and `Parsed`.

---

### The Token Class Structure

**Senior**: `Token` is a parse tree node. Arguably the most important class.

```java
public class Token implements Serializable {

    public final Source source;                  // Matched source text
    public Parser parser;                        // The parser that generated this token
    public Optional<Token> parent;               // Parent token
    private final TokenList originalChildren;    // All child tokens
    public final TokenList filteredChildren;     // Child tokens after AST filtering
    public final TokenKind tokenKind;            // Token type
}
```

**Newcomer**: It has a `parser` field. So you can tell which parser generated the token.

**Senior**: Very useful -- when traversing the parse tree you can check "was this node generated by NumberParser?"

```java
// Check if a token was generated by a specific parser
if (token.parser instanceof NumberParser) {
    // This is a numeric literal token
    String numberText = token.source.toString();
}
```

---

### originalChildren vs filteredChildren

**Newcomer**: What's the difference between `originalChildren` and `filteredChildren`?

**Senior**: `originalChildren` contains all child tokens generated by every grammar rule -- parentheses, whitespace, keywords, everything.

`filteredChildren` contains child tokens filtered based on `ASTNode` / `NotASTNode` markers. Only semantically meaningful nodes are included.

```
Input: (3 + 4) * 2

originalChildren:
ParenthesesParser
+-- WordParser: "("           <- NotASTNode
+-- ExpressionParser           <- ASTNode
|   +-- TermParser
|   |   +-- FactorParser
|   |       +-- NumberParser: "3"
|   +-- PlusParser: "+"
|   +-- TermParser
|       +-- FactorParser
|           +-- NumberParser: "4"
+-- WordParser: ")"           <- NotASTNode

filteredChildren:
ParenthesesParser
+-- ExpressionParser          <- Parentheses excluded
    +-- NumberParser: "3"
    +-- PlusParser: "+"
    +-- NumberParser: "4"
```

**Newcomer**: With `filteredChildren`, I can do semantic processing without worrying about parentheses.

---

### Source -- The Matched Text

**Newcomer**: What is `Source`?

**Senior**: `Source` is an abstraction representing text data. There's `StringSource` for the full input string and partial Sources for substrings.

```java
Token token = ...;

// Get the matched text
String matched = token.source.toString();

// Text length
int length = token.source.codePointLength().value();
```

**Senior**: `Source` handles characters at the code point level, so emojis and surrogate pairs are processed correctly.

**Newcomer**: Since Java's `String` is UTF-16, `String.length()` doesn't match the character count when surrogate pairs are involved.

**Senior**: Right. unlaxer-parser internally uses `CodePointIndex` and `CodePointLength` for code-point-based position management.

---

### ParseContext -- Managing Parse State

**Newcomer**: What class is `ParseContext`?

**Senior**: `ParseContext` manages the execution state of parsing. It holds the input source and cursor position, and provides a transaction mechanism.

```java
public class ParseContext implements
    Closeable, Transaction,
    ParserListenerContainer,
    GlobalScopeTree, ParserContextScopeTree {

    boolean doMemoize;                      // Enable/disable memoization
    public final Source source;             // Input source
    boolean createMetaToken = true;         // Meta token generation

    final Deque<TransactionElement> tokenStack = new ArrayDeque<>();  // Transaction stack
}
```

**Newcomer**: Transaction -- like database transactions?

**Senior**: Similar. It's the mechanism for implementing parser try-and-rollback.

**Transaction model**:

```
1. begin(parser)    -- Start transaction. Save current cursor position
2. (Child parsers attempt to parse)
3a. commit(parser)  -- Success. Finalize tokens and advance cursor position
3b. rollback(parser) -- Failure. Restore cursor to saved position
```

**Newcomer**: PEG's backtracking is implemented through this transaction mechanism.

**Senior**: Right. The `Choice` parser does `begin` -> (parse) -> `rollback` on failure, `commit` on success, for each alternative.

```
Choice(A, B, C) parse:

begin()
  Try A -> fails -> rollback()
begin()
  Try B -> succeeds -> commit()
  -> Choice as a whole succeeds
```

---

### Cursor Position Management

**Newcomer**: How is cursor position managed?

**Senior**: `ParseContext` has a cursor object internally, advancing as parsing progresses. The `peek()` method reads characters at the current position, and cursor advances as tokens are consumed.

```java
// Peek at 1 character from current position
Source peeked = parseContext.peek(tokenKind, new CodePointLength(1));

// Cursor advances at transaction commit time
```

**Newcomer**: Does `peek` just read without consuming?

**Senior**: It depends on `TokenKind`. `TokenKind.consumed` consumes (cursor advances at commit). `TokenKind.matchOnly` is lookahead only -- cursor doesn't advance.

---

### Parsed -- The Parse Result

**Newcomer**: What does the `Parsed` class represent?

**Senior**: It's the return value of a parser's `parse()` method. Contains information about whether parsing succeeded or failed, and the token if successful.

```java
public class Parsed extends Committed {

    public enum Status {
        succeeded,   // Parse succeeded
        stopped,     // Stopped (treated as success)
        failed;      // Parse failed

        public boolean isSucceeded() {
            return this == succeeded || this == stopped;
        }
    }

    public Status status;

    public static final Parsed FAILED = new Parsed(Status.failed);
    public static final Parsed SUCCEEDED = new Parsed(Status.succeeded);
}
```

**Newcomer**: What's `stopped`? Not success and not failure?

**Senior**: "Got this far with parsing but can't proceed further." `isSucceeded()` returns `true`, so it's treated as a kind of success. Used to indicate partial parse results.

**Newcomer**: Show me how to use parse results.

**Senior**: Like this:

```java
// Get the parser
Parser parser = Parser.get(NumberExpressionParser.class);

// Prepare input
Source source = StringSource.createSource("3 + 4 * 2");
ParseContext context = new ParseContext(source);

// Execute parse
Parsed parsed = parser.parse(context);

// Check result
if (parsed.isSucceeded()) {
    Token rootToken = parsed.getToken();
    System.out.println("Matched text: " + rootToken.source);

    // Traverse child tokens
    for (Token child : rootToken.filteredChildren) {
        System.out.println("Child node: " + child.parser.getClass().getSimpleName());
    }
}
```

---

### Traversing the Parse Tree

**Newcomer**: How do I traverse the parse tree?

**Senior**: The `Token` class has several traversal methods.

```java
// Find the first descendant node (depth-first)
Optional<Token> found = token.findFirstDescendant(
    t -> t.parser instanceof NumberParser
);

// Find all descendant nodes matching a condition
List<Token> allNumbers = token.findDescendants(
    t -> t.parser instanceof NumberParser
);
```

**Newcomer**: Usable like Java's Stream API.

**Senior**: There's also a `TokenPredicators` utility class with commonly used predicates.

```java
// Search by parser class
Predicate<Token> isNumber = TokenPredicators.byParserClass(NumberParser.class);

// Execute search
List<Token> numbers = token.findDescendants(isNumber);
```

---

### Visualizing the Parse Tree

**Newcomer**: Is there a way to display the parse tree for debugging?

**Senior**: There are `TokenPrinter` and `ParsedPrinter`.

```java
// Display token tree as string
String tree = TokenPrinter.get(token, 0, OutputLevel.detail, false);
System.out.println(tree);
```

**Senior**: The output is an indented tree format:

```
NumberExpressionParser "3 + 4 * 2"
  NumberTermParser "3"
    NumberFactorParser "3"
      NumberParser "3"
  PlusParser "+"
  NumberTermParser "4 * 2"
    NumberFactorParser "4"
      NumberParser "4"
    MultipleParser "*"
    NumberFactorParser "2"
      NumberParser "2"
```

**Newcomer**: With this, it's easy to verify parser behavior.

---

[<- Part 6: Left/Right Associativity](#part-6-left-associativity-right-associativity-and-operator-precedence) | [Next: Part 8 Building Your Own Parser ->](#part-8-building-your-own-parser)

---

## Part 8: Building Your Own Parser

[<- Part 7: Tokens and Parse Trees](#part-7-tokens-and-parse-trees) | [Next: Part 9 AST Filtering ->](#part-9-ast-filtering-and-scope)

---

**Newcomer**: Senior, I'm ready to write my own parser!

**Senior**: Great, let's build a simple calculator parser from scratch, step by step.

---

### Step 1: Inherit LazyChain to Create a Simple Parser

**Newcomer**: Where do I start?

**Senior**: The most basic non-terminal parser inherits `LazyChain`. Just return a list of child parsers from the `getLazyParsers()` method.

```java
package com.example.calculator;

import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.NumberParser;

// The simplest parser -- only parses numeric literals
public class NumberLiteralParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            NumberParser.class  // Use unlaxer's built-in number parser
        );
    }
}
```

**Newcomer**: Just pass a class to the `Parsers` constructor?

**Senior**: Yes. When `Parsers` receives a class, it internally uses `Parser.get()` to obtain the singleton instance. You can also pass instances directly:

```java
return new Parsers(
    Parser.get(NumberParser.class)  // Passing an instance directly
);
```

---

### Step 2: Returning Child Parsers from getLazyParsers()

**Newcomer**: How do I make a more complex parser?

**Senior**: Return multiple child parsers. For example, a parser for "signed numbers":

```java
public class SignedNumberParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // Sign (optional)
            new Optional(
                new Choice(
                    new WordParser("+"),
                    new WordParser("-")
                )
            ),
            // Number
            Parser.get(NumberParser.class)
        );
    }
}
```

**Newcomer**: An `Optional` containing a `Choice`, containing `WordParsers`. Nesting combinators.

**Senior**: Exactly. Building parsers through **composition** is the essence of Parser Combinators.

---

### Step 3: Get Singletons with Parser.get(MyParser.class)

**Newcomer**: How do I use the parser I created?

**Senior**: Get the singleton instance with `Parser.get()`.

```java
// Get parser instance
SignedNumberParser parser = Parser.get(SignedNumberParser.class);

// You can also use new directly, but use singletons when circular references exist
SignedNumberParser parser2 = new SignedNumberParser(); // Not recommended
```

---

### Step 4: Writing Tests

**Newcomer**: How do I write parser tests?

**Senior**: Create a `ParseContext`, call `parse()`, and check the result.

```java
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SignedNumberParserTest {

    @Test
    void testPositiveNumber() {
        Parser parser = Parser.get(SignedNumberParser.class);
        Source source = StringSource.createSource("42");
        ParseContext context = new ParseContext(source);

        Parsed parsed = parser.parse(context);

        assertTrue(parsed.isSucceeded());
        assertEquals("42", parsed.getToken().source.toString());
    }

    @Test
    void testNegativeNumber() {
        Parser parser = Parser.get(SignedNumberParser.class);
        Source source = StringSource.createSource("-3.14");
        ParseContext context = new ParseContext(source);

        Parsed parsed = parser.parse(context);

        assertTrue(parsed.isSucceeded());
        assertEquals("-3.14", parsed.getToken().source.toString());
    }

    @Test
    void testInvalidInput() {
        Parser parser = Parser.get(SignedNumberParser.class);
        Source source = StringSource.createSource("abc");
        ParseContext context = new ParseContext(source);

        Parsed parsed = parser.parse(context);

        assertTrue(parsed.isFailed());
    }
}
```

**Newcomer**: Simple. Create a `ParseContext`, call `parse()`, check with `isSucceeded()`.

**Senior**: You can also verify other things in tests:

```java
// Check the length of matched text
assertEquals(5, parsed.getToken().source.codePointLength().value());

// Check the number of child tokens
assertEquals(2, parsed.getToken().filteredChildren.size());

// Check a specific parser's child token
Token numberToken = parsed.getToken().findFirstDescendant(
    t -> t.parser instanceof NumberParser
).orElseThrow();
```

---

### Step 5: Create Choices with LazyChoice

**Newcomer**: Next I want to create a choice like "number or parenthesized expression."

**Senior**: Use `LazyChoice`:

```java
public class FactorParser extends LazyChoice {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // Numeric literal
            NumberParser.class,
            // Parenthesized expression: '(' Expression ')'
            new ParenthesesParser(
                Parser.newInstance(ExpressionParser.class)
            )
        );
    }
}
```

**Newcomer**: Using `Parser.newInstance()` here instead of `Parser.get()`. Why?

**Senior**: The `ExpressionParser` inside the `ParenthesesParser` may need an independent instance from this `FactorParser` itself. The expression inside parentheses should be treated as a "new context." Same pattern as tinyexpression's `AbstractNumberFactorParser`.

---

### Step 6: Create Repetitions with ZeroOrMore

**Newcomer**: How do I create operator-and-term repetitions?

**Senior**: Same pattern as tinyexpression:

```java
public class TermParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // First Factor
            FactorParser.class,
            // ('*' | '/') Factor repetition
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    new Choice(
                        new WordParser("*"),
                        new WordParser("/")
                    ),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
}
```

---

### Step 7: Build a Complete Calculator Parser from Scratch

**Newcomer**: I want to connect everything and build a complete calculator parser.

**Senior**: OK, here are all the classes. The grammar:

```bnf
Expression = Term { ('+' | '-') Term }
Term       = Factor { ('*' | '/') Factor }
Factor     = NUMBER | '(' Expression ')'
```

**CalcExpressionParser.java**:

```java
package com.example.calculator;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedChain;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;

public class CalcExpressionParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        // Expression = Term { ('+' | '-') Term }
        return new Parsers(
            CalcTermParser.class,
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    new Choice(
                        new WordParser("+"),
                        new WordParser("-")
                    ),
                    Parser.get(CalcTermParser.class)
                )
            )
        );
    }
}
```

**CalcTermParser.java**:

```java
package com.example.calculator;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedChain;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;

public class CalcTermParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        // Term = Factor { ('*' | '/') Factor }
        return new Parsers(
            CalcFactorParser.class,
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    new Choice(
                        new WordParser("*"),
                        new WordParser("/")
                    ),
                    Parser.get(CalcFactorParser.class)
                )
            )
        );
    }
}
```

**CalcFactorParser.java**:

```java
package com.example.calculator;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.NumberParser;
import org.unlaxer.parser.elementary.ParenthesesParser;

public class CalcFactorParser extends LazyChoice {

    @Override
    public Parsers getLazyParsers() {
        // Factor = NUMBER | '(' Expression ')'
        return new Parsers(
            NumberParser.class,
            new ParenthesesParser(
                Parser.newInstance(CalcExpressionParser.class)
            )
        );
    }
}
```

**Newcomer**: A complete calculator parser in just 3 classes!

**Senior**: Right. Let's write tests:

```java
@Test
void testSimpleExpression() {
    Parser parser = Parser.get(CalcExpressionParser.class);
    Source source = StringSource.createSource("3 + 4 * 2");
    ParseContext context = new ParseContext(source);

    Parsed parsed = parser.parse(context);

    assertTrue(parsed.isSucceeded());
    assertEquals("3 + 4 * 2", parsed.getToken().source.toString());
}

@Test
void testNestedParentheses() {
    Parser parser = Parser.get(CalcExpressionParser.class);
    Source source = StringSource.createSource("(1 + 2) * (3 + 4)");
    ParseContext context = new ParseContext(source);

    Parsed parsed = parser.parse(context);

    assertTrue(parsed.isSucceeded());
}

@Test
void testDeeplyNested() {
    Parser parser = Parser.get(CalcExpressionParser.class);
    Source source = StringSource.createSource("((1 + 2) * 3) / (4 - (5 + 6))");
    ParseContext context = new ParseContext(source);

    Parsed parsed = parser.parse(context);

    assertTrue(parsed.isSucceeded());
}
```

**Newcomer**: Regular expressions couldn't do this. Parsing arbitrarily deep nested parentheses is something only a parser can do.

**Senior**: Exactly. Three classes and a few dozen lines of code, and we've broken through the regex barrier. That's the power of Parser Combinators.

---

### Best Practices for Parser Creation

**Senior**: Let me summarize some best practices.

**1. Name classes to match grammar rule names**

```
Expression -> ExpressionParser
Term       -> TermParser
Factor     -> FactorParser
```

**2. Use Lazy versions when there are circular references**

```java
// Expression -> ... -> Factor -> '(' Expression ')'  cycle
// -> Use LazyChain, LazyChoice
```

**3. Watch the order in Choice**

```java
// Put longer patterns first
new Choice(
    longPattern,    // Try first
    shortPattern    // Try second
)
```

**4. Make constants with StaticParser**

```java
// Make frequently used parsers static fields
static final Parser digitParser = new DigitParser();
static final OneOrMore digitsParser = new OneOrMore(Name.of("digits"), digitParser);
```

**5. Add Names for easier debugging**

```java
new Optional(Name.of("optional-sign"), signParser)
new OneOrMore(Name.of("digits"), digitParser)
```

**Newcomer**: What's `Name.of()` for?

**Senior**: Naming parsers makes it easier to tell "what was this node's parser for" when viewing parse trees or debugging. In `NumberParser`, `Name.of("any-digit")` and `Name.of("digits-point-digits")` are used for this reason.

---

[<- Part 7: Tokens and Parse Trees](#part-7-tokens-and-parse-trees) | [Next: Part 9 AST Filtering ->](#part-9-ast-filtering-and-scope)

---

## Part 9: AST Filtering and Scope

[<- Part 8: Building Your Own Parser](#part-8-building-your-own-parser) | [Next: Part 10 Error Handling ->](#part-10-error-handling-and-debugging)

---

**Newcomer**: Senior, in Part 1 you said "you can access both CST and AST." How does that work concretely?

**Senior**: Let's look at unlaxer-parser's AST filtering mechanism in detail.

---

### ASTNode vs NotASTNode

**Senior**: `ASTNode` and `NotASTNode` are markers that wrap parsers to specify "should this parser's tokens be included in the AST or not."

```java
// This parser's tokens ARE included in the AST
Parser important = new ASTNode(someParser);

// This parser's tokens are NOT included in the AST
Parser syntaxOnly = new NotASTNode(someParser);
```

**Newcomer**: In what situations would you use each?

**Senior**: For example, a semicolon `;` is syntactically necessary but semantically unnecessary:

```java
public class StatementParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            new ASTNode(ExpressionParser.class),     // The expression is meaningful -> include in AST
            new NotASTNode(new WordParser(";"))       // Semicolon is syntax only -> exclude
        );
    }
}
```

**Newcomer**: The full parse tree (CST) has the semicolon token, but `filteredChildren` (AST) doesn't.

---

### NodeKind enum

**Senior**: Internally managed by a `NodeKind` enum:

```java
public enum NodeKind {
    node,      // Include in AST
    notNode    // Exclude from AST
}
```

**Newcomer**: Simple.

**Senior**: Each parser reports its `NodeKind` through the `NodeReduceMarker` interface.

---

### filteredChildren vs All children

**Newcomer**: Can you explain the difference between `filteredChildren` and `originalChildren` in more detail?

**Senior**: Consider parsing `if (condition) { body }`:

```
originalChildren (all children):
IfStatementParser
+-- WordParser: "if"         <- Keyword
+-- WordParser: "("          <- Opening paren
+-- ConditionParser: "x > 0" <- Condition
+-- WordParser: ")"          <- Closing paren
+-- WordParser: "{"          <- Opening brace
+-- BodyParser: "return 1"   <- Body
+-- WordParser: "}"          <- Closing brace

filteredChildren (AST nodes only):
IfStatementParser
+-- ConditionParser: "x > 0"
+-- BodyParser: "return 1"
```

**Newcomer**: Use `originalChildren` for a formatter, `filteredChildren` for an evaluator or code generator.

**Senior**: Exactly. Different views from a single parse result, depending on your purpose.

---

### ScopeTree -- Variable Scope Management

**Newcomer**: Is variable scope management also done by the parser?

**Senior**: `ParseContext` implements `GlobalScopeTree` and `ParserContextScopeTree`, allowing scope information management during parsing.

```java
public class ParseContext implements
    Closeable, Transaction,
    GlobalScopeTree, ParserContextScopeTree {

    Map<Parser, Map<Name, Object>> scopeTreeMapByParser = new HashMap<>();
}
```

**Newcomer**: Scopes per parser.

**Senior**: Right. Variable declaration parsers register variables in the scope, and variable reference parsers look up variables from the scope. tinyexpression's `NumberVariableMatchedWithVariableDeclarationParser` uses this mechanism.

---

### SuggestsCollectorParser -- Collecting Suggestions for Code Completion

**Newcomer**: Can this be used for code completion?

**Senior**: There's a `SuggestsCollectorParser` that collects "what can be entered here" candidates during parsing.

```java
public interface SuggestsCollectorParser {
    // Even on parse failure, provides how far it matched and what was expected next
}
```

**Newcomer**: Useful for LSP (Language Server Protocol) auto-completion.

**Senior**: Exactly. There's a mechanism for building LSP servers from unlaxer-parser. Parsers implementing the `SuggestableParser` interface can return candidates (`Suggests`).

```java
public interface SuggestableParser {
    Suggests getSuggests();
}
```

**Newcomer**: The parser definition itself becomes the data source for input completion. Not just two birds with one stone, but three.

---

[<- Part 8: Building Your Own Parser](#part-8-building-your-own-parser) | [Next: Part 10 Error Handling ->](#part-10-error-handling-and-debugging)

---

## Part 10: Error Handling and Debugging

[<- Part 9: AST Filtering](#part-9-ast-filtering-and-scope) | [Next: Part 11 Advanced Topics ->](#part-11-advanced-topics--the-road-to-ubnf)

---

**Newcomer**: When I get errors writing parsers, how do I debug them?

**Senior**: Parser debugging has its own unique challenges. But unlaxer-parser has several tools.

---

### Reading ParseExceptions

**Newcomer**: How do I read a `ParseException`?

**Senior**: A `ParseException` contains the position where parsing failed and information about what parser was expected.

```
ParseException: Expected NumberParser at position 5
Input: "3 + + 4"
              ^  <- Failed here
```

**Newcomer**: Having position information helps.

**Senior**: There's also an `ErrorMessageParser` that generates custom error messages at specific positions. Useful for providing clear error messages at certain grammar points.

---

### Partial Parse and Consumed Length

**Newcomer**: When parsing stops midway, can I tell how far it matched?

**Senior**: You can judge from the `Parsed`'s `Status.stopped` or the `source` length of the matched token.

```java
Parsed parsed = parser.parse(context);

if (parsed.isSucceeded()) {
    int consumed = parsed.getToken().source.codePointLength().value();
    int total = source.codePointLength().value();

    if (consumed < total) {
        System.out.println("Partial match: " + consumed + " / " + total + " characters");
        System.out.println("Remaining: " + source.toString().substring(consumed));
    }
}
```

**Newcomer**: Check whether the entire input was parsed by looking at the consumed length.

**Senior**: Right. To guarantee complete parsing, add `EndOfSourceParser` at the end of your grammar:

```java
public class CompleteExpressionParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            CalcExpressionParser.class,
            EndOfSourceParser.class  // Verify input was consumed to the end
        );
    }
}
```

---

### ParserPrinter -- Visualizing Parser Hierarchy

**Newcomer**: Is there a way to examine the parser structure itself?

**Senior**: `ParserPrinter` can serialize the parser's hierarchy into a string.

```java
Parser parser = Parser.get(CalcExpressionParser.class);
String hierarchy = ParserPrinter.get(parser, OutputLevel.detail);
System.out.println(hierarchy);
```

**Newcomer**: The grammar structure displayed as a tree. Useful for verifying parsers are composed as expected.

---

### Common Mistakes and How to Fix Them

**Senior**: Let me summarize common mistakes when writing parsers.

---

#### Mistake 1: Wrong LazyChoice Order

**Senior**: As discussed earlier, the most common bug with PEG's ordered choice.

```java
// Bad example: Short keyword consumes what the long keyword should match
new Choice(
    new WordParser("else"),
    new WordParser("elseif")   // "elseif" matches as "else" and is never reached
)

// Correct example: Longer pattern first
new Choice(
    new WordParser("elseif"),  // Try first
    new WordParser("else")     // Only tried if "elseif" fails
)
```

**Newcomer**: This is opposite to regex's `|` (longest match). PEG adopts "the first one that succeeds."

**Senior**: Right. Rules of thumb:
- Keywords: longer ones first
- More specific patterns first
- More general patterns later

---

#### Mistake 2: Infinite Loop in ZeroOrMore

**Senior**: If the parser inside `ZeroOrMore` matches the empty string, the cursor doesn't advance on each iteration, potentially causing an infinite loop.

```java
// Dangerous: Optional can match the empty string
new ZeroOrMore(
    new Optional(someParser)  // someParser doesn't match -> Optional succeeds empty
                               // -> ZeroOrMore goes to next iteration -> infinite loop!
)

// Safe: OneOrMore consumes at least 1 character
new ZeroOrMore(
    new OneOrMore(someParser)  // Fails unless it matches at least once -> safe
)
```

**Newcomer**: Does unlaxer-parser have infinite loop detection?

**Senior**: It has a safety measure that detects iterations where the cursor didn't advance and terminates the loop. But it's best to avoid it at design time.

---

#### Mistake 3: Forgetting Lazy Versions with Circular References

**Senior**: This is common too.

```java
// Bad example: Chain with circular reference
public class ExprParser extends Chain {  // Chain is Constructed
    // Constructor requires child parsers
    // -> FactorParser's constructor needs ExprParser
    // -> Infinite recursion!
}

// Correct example: Use LazyChain
public class ExprParser extends LazyChain {  // Lazy version
    @Override
    public Parsers getLazyParsers() {
        // Lazy evaluation -> circular references OK
        return new Parsers(
            FactorParser.class
        );
    }
}
```

**Newcomer**: Circular references -> `Lazy` version. Got it.

---

#### Mistake 4: Forgetting Whitespace Handling

**Senior**: Programming languages have whitespace between tokens. Forgetting to include whitespace-skipping means `3+4` parses but `3 + 4` doesn't.

```java
// Bad example: Doesn't account for whitespace
new Chain(numberParser, new WordParser("+"), numberParser)
// "3+4" -> OK, "3 + 4" -> NG

// Correct example: Use WhiteSpaceDelimitedChain
new WhiteSpaceDelimitedChain(numberParser, new WordParser("+"), numberParser)
// "3+4" -> OK, "3 + 4" -> OK, "3  +  4" -> OK
```

---

#### Mistake 5: Forgetting EndOfSource

**Newcomer**: This came up earlier.

**Senior**: Without `EndOfSourceParser`, the parser may parse only part of the input and report "success."

```java
// Parsing "3 + 4 @@@"
// Without EndOfSource -> Matches "3 + 4" and succeeds ("@@@" ignored)
// With EndOfSource -> Fails because "@@@" remains
```

---

### Debugging Tips

**Senior**: Finally, some tips for debugging parsers.

**1. Start with small parsers**

Don't write a large grammar all at once. Start with the smallest parser and add features one at a time. Write tests after each addition.

**2. Use ParserListeners**

Add a `ParserListener` to `ParseContext` to receive callbacks each time a parser's `parse()` is called. Traces the parsing flow.

**3. Minimize failing test cases**

If parsing fails on some input, reduce it to the shortest possible input. If `"abc def ghi"` fails, try just `"abc"`.

**4. Test each Choice alternative individually**

If `Choice(A, B, C)` isn't working as expected, test A, B, C each individually. Identify which alternative is the problem, then adjust the order.

---

[<- Part 9: AST Filtering](#part-9-ast-filtering-and-scope) | [Next: Part 11 Advanced Topics ->](#part-11-advanced-topics--the-road-to-ubnf)

---

## Part 11: Advanced Topics -- The Road to UBNF

[<- Part 10: Error Handling](#part-10-error-handling-and-debugging) | [Next: Part 12 Advanced Parsers ->](#part-12-advanced-parsers--the-forgotten-classes)

---

**Newcomer**: Senior, we've been writing parsers in Java code, but isn't there an easier way to define grammars?

**Senior**: There is. unlaxer-parser has a grammar definition language called **UBNF (Unlaxer BNF)**. You can write the same things as hand-written Java parsers in a more concise notation.

---

### From Hand-Written Parsers to UBNF Grammar

**Newcomer**: What would the calculator parser look like in UBNF?

**Senior**: Like this:

```ubnf
Expression = Term { ('+' | '-') Term } ;
Term       = Factor { ('*' | '/') Factor } ;
Factor     = NUMBER | '(' Expression ')' ;
```

**Newcomer**: Wait, that's it? What took 3 classes and dozens of lines in Java is now 3 lines!

**Senior**: In UBNF, grammars are written declaratively. The UBNF compiler automatically generates parser objects like `LazyChain`, `LazyChoice`, `ZeroOrMore` from this definition.

---

### Comparing Hand-Written and UBNF for the Same Language

**Senior**: Let's make a comparison table.

| Aspect | Hand-written (Java) | UBNF |
|------|-------------|------|
| Code volume | Large (3 classes, 50+ lines) | Small (3 lines) |
| Flexibility | High (any Java code is possible) | Limited to grammar definition |
| Custom processing | Can insert arbitrary processing during parsing | Standard parsing only |
| IDE support | All Java IDE features | UBNF-specific editor support |
| Debugging | Direct step-through with Java debugger | Debug generated code |
| Performance | Room for optimization | Standard performance |

---

### When to Use Hand-Written, When to Use UBNF?

**Newcomer**: Which should I use?

**Senior**: Here's a guideline:

**Use UBNF when**:
- The grammar is expressible in standard BNF/EBNF
- You want rapid prototyping
- Grammar changes frequently
- You also want LSP (language server) or DAP (debugger) beyond just parsing

**Use hand-written when**:
- Custom logic during parsing is needed (scope resolution, type checking, etc.)
- Fine-grained performance tuning is required
- Non-standard syntax (indent-based languages, etc.)
- Integrating into an existing Java codebase

**Newcomer**: tinyexpression is hand-written, right? Why?

**Senior**: tinyexpression has a lot of custom logic in the parser. `VariableTypeSelectable`, `TypedParser`, `SideEffectExpressionParser` and others perform type information resolution and scope management during parsing. Hand-written is more flexible for such advanced processing.

**Newcomer**: But the pure "grammar definition" parts could be written in UBNF too, right?

**Senior**: Yes. In fact, unlaxer-dsl has a mechanism to auto-generate LSP servers and DAP servers from UBNF grammars.

---

### Guide to unlaxer-dsl

**Newcomer**: I want to learn more.

**Senior**: The unlaxer-dsl tutorial `tutorial-ubnf-to-lsp-dap-dialogue.ja.md` covers detailed UBNF usage. It teaches:

1. Full UBNF syntax
2. Generating LSP servers from UBNF
3. Generating DAP (debug adapter) from UBNF
4. Auto-generating VS Code extensions
5. Examples of defining real languages in UBNF

All in dialogue format.

**Newcomer**: Now that I've learned the parser fundamentals, I should be able to understand UBNF smoothly.

**Senior**: Exactly. Understanding "how parsers work internally" is a prerequisite for understanding UBNF. With the knowledge from this tutorial, UBNF will just be a "convenient shortcut."

---

### Summary: Parser Learning Roadmap

**Senior**: Let me organize a roadmap of what this tutorial covered.

```
Part 1:  What Is a Parser?
         String -> structured data, regex limits, CFG/BNF
                |
Part 2:  Parsing Techniques
         Top-down vs bottom-up, PEG, Packrat
                |
Part 3:  Parser Combinator
         Combining small parsers, Lazy vs Constructed
                |
Part 4:  Terminal Parsers
         WordParser, NumberParser, IdentifierParser, POSIX/ASCII
                |
Part 5:  Combinators
         Chain, Choice, ZeroOrMore, Optional, Not, MatchOnly
                |
Part 6:  Operators
         Left/right associativity, precedence, avoiding left recursion
                |
Part 7:  Tokens and Parse Trees
         Token, ParseContext, Parsed, tree traversal
                |
Part 8:  Practice
         Building a calculator parser from scratch
                |
Part 9:  AST
         Filtering, scope, code completion
                |
Part 10: Debugging
         Common mistakes, debugging tools
                |
Part 11: Advanced
         Migration to UBNF, auto-generating LSP/DAP
```

**Newcomer**: Thank you, Senior. I had no idea the world of parsers was this deep. But thanks to unlaxer-parser, I feel like I can step in without fear.

**Senior**: Parsers look intimidating at first, but ultimately they're a technology that's faithful to the fundamentals of software engineering -- "combining small components." Understand each individual parser, combine them, and you can parse any language.

**Newcomer**: I'll start by extending the calculator parser to add variables and functions.

**Senior**: Great. When you get stuck, refer to the tinyexpression source code. It's like a textbook of Parser Combinators in action.

---

[<- Part 10: Error Handling](#part-10-error-handling-and-debugging) | [Next: Part 12 Advanced Parsers ->](#part-12-advanced-parsers--the-forgotten-classes)

---

## Part 12: Advanced Parsers -- The Forgotten Classes

[<- Part 11: Advanced Topics](#part-11-advanced-topics--the-road-to-ubnf) | [Next: Appendix A Glossary ->](#appendix-a-parser-glossary)

---

**Senior**: Actually, I'd forgotten about this, but... there are quite a few parsers in unlaxer-parser that we haven't covered yet.

**Newcomer**: Wait, there are more? I thought we'd covered everything through Part 11.

**Senior**: No, there are some where even I go "Huh, did I write this class?" They're utility-type parsers -- the unsung heroes working behind the scenes.

**Newcomer**: Parsers that even their creator forgot about... Now I'm really curious.

**Senior**: Alright, let me recall them one by one.

---

### 12.1 Flatten -- Flattening Nested Structures

**Senior**: First up is Flatten. It flattens a Chain within a Chain by one level.

**Newcomer**: What do you mean by "flatten"?

**Senior**: For example, Chain(Chain(A, B), C) creates a token tree that's 3 levels deep. With Flatten, the child parser's children become direct children of the Flatten node, making the tree one level shallower.

```java
// Before: Chain(Chain(A, B), C) → Token tree is 3 levels deep
// After:  Flatten(Chain(A, B)) → A, B are direct children, tree is 2 levels
```

**Newcomer**: That seems useful for refactoring when the parser hierarchy gets too deep.

**Senior**: Exactly. When the AST has unnecessary nesting, it's harder to process. Flatten cleans it up nicely.

---

### 12.2 Reverse -- Reverse Order Matching

**Senior**: Next is Reverse. As the name suggests, it reverses the order of a Chain's child parsers.

**Newcomer**: Reverse? As in it internally uses Collections.reverse() to flip the child list?

**Senior**: Yes. Normally matching proceeds left to right, but sometimes you want to try lower-priority parsers first.

**Newcomer**: Couldn't you just write them in that order to begin with...

**Senior**: When you're building parsers programmatically, you sometimes want to change the order after the fact. That's when this comes in handy.

---

### 12.3 TagWrapper / RecursiveTagWrapper -- Controlling the AST with Tags

**Senior**: This one is important. TagWrapper adds or removes a tag (metadata) from a single parser.

**Newcomer**: What kind of tag?

**Senior**: Information used in AST filtering. You specify add or remove using TagWrapperAction. ASTNode and NotASTNode are actually concrete subclasses of this.

**Newcomer**: Oh, so the ASTNode from Part 9 connects here!

**Senior**: Right. And RecursiveTagWrapper applies tags recursively to all descendants. You can control the recursion scope with RecursiveMode -- ALL_CHILDREN for all descendants, DIRECT_CHILDREN for immediate children only.

```java
// ASTNode(parser) → This parser's tokens are included in filteredChildren
// NotASTNode(parser) → This parser's tokens are excluded from filteredChildren
// ASTNodeRecursive(parser) → All descendants are included in filteredChildren
```

**Newcomer**: There's even an ASTNodeRecursive. You can really fine-tune AST control.

---

### 12.4 ParserWrapper -- Forcing Parameter Overrides

**Senior**: ParserWrapper ignores the TokenKind and invertMatch propagated from the parent and uses fixed values instead.

**Newcomer**: Ignores propagation?

**Senior**: For example, it's used internally by QuotedParser. There are cases where you want the inner parser to operate in consumed mode even when the parent is in matchOnly mode.

```java
// Even if the parent is in matchOnly mode, the inner parser runs in consumed mode
new ParserWrapper(name, innerParser, TokenKind.consumed, false)
```

**Newcomer**: How is this different from MatchOnly?

**Senior**: MatchOnly forces "don't consume." ParserWrapper lets you choose which mode to force. It's a more general-purpose control.

---

### 12.5 ContainerParser\<T\> -- Putting Non-Parser Things in the Parse Tree

**Senior**: ContainerParser is an interesting one. It extends NoneChildParser, so it has no child parsers. Instead, get() returns data of any type T.

**Newcomer**: A parser that doesn't parse?

**Senior**: It's a mechanism for putting error messages and metadata into the parse tree. ErrorMessageParser is a real-world example of this.

```java
// ErrorMessageParser extends ContainerParser<String>
// → Inserts error messages as Tokens during parsing
```

**Newcomer**: I see, so you can use the parse tree as a data container too.

---

### 12.6 PropagationStopper -- Controlling Propagation (All 4 Types)

**Senior**: This is the one I'd forgotten about the most, haha.

**Newcomer**: It makes me nervous when you say that with a laugh...

**Senior**: Remember how TokenKind and invertMatch propagate from parent to child during parsing? Stoppers are parsers that halt this propagation. There are 4 types in total.

| Class | TokenKind | invertMatch | Use Case |
|--------|-----------|-------------|------|
| AllPropagationStopper | Stopped -> consumed | Stopped -> false | Complete isolation |
| DoCounsumePropagationStopper | Stopped -> consumed | Passes through | Force consume mode |
| InvertMatchPropagationStopper | Passes through | Stopped -> false | Disable inversion logic |
| NotPropagatableSource | Passes through | Inverted | Logical NOT |

**Newcomer**: DoCounsume... Senior, that's a typo, right? It should be "Consume."

**Senior**: Yeah, it's a typo of Consume. But fixing it would break API compatibility, so it stays.

**Newcomer**: The joys of historical baggage...

**Senior**: A classic programmer's dilemma.

---

### 12.7 Ordered -- The Opposite of NonOrdered

**Senior**: Ordered is almost the same as Chain, but it's a marker that explicitly states "order matters."

**Newcomer**: How is it different from Chain?

**Senior**: Functionally, they're nearly identical. But by using it in contrast with NonOrdered (Interleave), the intent becomes clear. NonOrdered accepts any order; Ordered strictly goes left to right.

**Newcomer**: So it's mainly about documentation value.

**Senior**: Code is written for the people who read it.

---

### 12.8 ChildOccursWithTerminator -- Repetition with Terminators

**Senior**: ChildOccursWithTerminator is the common base class for ZeroOrMore, OneOrMore, Optional, and Repeat.

**Newcomer**: The parent of all repetition parsers.

**Senior**: Right. Its distinctive feature is that it can hold a terminator parser. This lets you express the pattern "repeat until this character appears."

```java
// Example: Repeat elements until a semicolon appears
new ZeroOrMore(elementParser, () -> Parser.get(SemiColonParser.class))
```

**Newcomer**: With a terminator, you can explicitly state when the repetition should end.

**Senior**: It's really useful for parsing CSVs and delimiter-separated lists.

---

### 12.9 MatchOnly vs Not -- The Lookahead Twins

**Senior**: MatchOnly and Not are the twins of lookahead. Neither one consumes input.

**Newcomer**: Lookahead -- those are PEG's & and !, right?

**Senior**: Exactly. Let's look at a concrete example.

```
Input: "hello"

MatchOnly(WordParser("hello"))
  → Success (but does not consume. Cursor stays at the start of "hello")

Not(WordParser("hello"))
  → Failure (the child succeeded, so Not fails)

Not(WordParser("world"))
  → Success (the child failed, so Not succeeds. Does not consume)
```

**Newcomer**: MatchOnly is positive lookahead, and Not is negative lookahead.

**Senior**: Precisely. MatchOnly = positive lookahead, Not = negative lookahead. They correspond directly to PEG's & and !.

---

### 12.10 MappedSingleCharacterParserHolder -- Customizing Character Classes

**Senior**: Last one -- MappedSingleCharacterParserHolder. It's a wrapper around MappedSingleCharacterParser.

**Newcomer**: That's quite a long name...

**Senior**: The functionality is simple. newWithout() lets you create a new parser that excludes specific characters.

```java
// Alphabet but excluding 'x'
AlphabetParser alphabet = Parser.get(AlphabetParser.class);
MappedSingleCharacterParserHolder holder = new MappedSingleCharacterParserHolder(alphabet);
Parser noX = holder.newWithout('x');
```

**Newcomer**: So you can do something like the regex `[a-wyz]` programmatically.

**Senior**: Exactly. It's useful when you need to dynamically customize character classes.

---

**Newcomer**: So there were this many utility parsers. Ten in total...

**Senior**: In everyday use, the star players like Chain and Or are usually enough, but when you want to do something sophisticated, these supporting cast members really shine.

**Newcomer**: I'm glad these forgotten parsers finally got their moment in the spotlight.

**Senior**: As their creator, I'm reflecting on this. I need to properly maintain the documentation in the next version.

---

[<- Part 11: Advanced Topics](#part-11-advanced-topics--the-road-to-ubnf) | [Next: Appendix A Glossary ->](#appendix-a-parser-glossary)

---

## Appendix A: Parser Glossary

[<- Part 12: Advanced Parsers](#part-12-advanced-parsers--the-forgotten-classes) | [Next: Appendix B Complete Parser Reference ->](#appendix-b-complete-parser-reference-for-unlaxer-parser)

---

| Term | Original | Description |
|------|------|------|
| Parser | Parser | A program that converts strings into structured data (syntax trees) |
| Recursive Descent | Recursive Descent | A top-down parsing technique where each grammar rule is implemented as a function |
| Left Recursion | Left Recursion | A grammar pattern where a non-terminal contains itself at its left edge. Can't be written directly in PEG |
| Lookahead | Lookahead | Peeking at input ahead to decide the parsing direction. Characters are not consumed |
| Backtracking | Backtracking | Restoring the cursor to an earlier position when parsing fails and retrying |
| Memoization | Memoization | An optimization that caches parse results to avoid recomputation |
| Packrat | Packrat Parsing | A PEG parsing technique that guarantees linear time through memoization |
| PEG | Parsing Expression Grammar | A grammar formalism with ordered choice. Proposed by Bryan Ford in 2004 |
| CFG | Context-Free Grammar | A formal grammar that can express recursive structures |
| BNF | Backus-Naur Form | Standard notation for describing context-free grammars |
| EBNF | Extended BNF | Extended version of BNF adding repetition and optional notation |
| UBNF | Unlaxer BNF | unlaxer-parser's proprietary EBNF extension |
| LL | Left-to-right, Leftmost | Family of top-down parsers that read left-to-right and perform leftmost derivation |
| LR | Left-to-right, Rightmost | Family of bottom-up parsers that read left-to-right and perform rightmost derivation (in reverse) |
| GLR | Generalized LR | Generalization of LR. Can handle ambiguous grammars |
| AST | Abstract Syntax Tree | A tree structure retaining only semantically necessary information |
| CST | Concrete Syntax Tree | A parse tree retaining all syntactic information |
| Token | Token | A node in the parse tree generated by a parser |
| Parse Tree | Parse Tree | The tree structure generated by a parser. Synonymous with CST |
| Shift-Reduce | Shift-Reduce | Basic operations of an LR parser. Push tokens onto the stack (shift) or reduce by a rule (reduce) |
| Left Associative | Left Associative | Operators of the same precedence associate from the left. Example: `a-b-c = (a-b)-c` |
| Right Associative | Right Associative | Operators of the same precedence associate from the right. Example: `a^b^c = a^(b^c)` |
| Operator Precedence | Operator Precedence | The ordering of binding strength among different operators |
| Terminal Symbol | Terminal Symbol | The smallest symbol in a grammar that cannot be expanded further. Characters or keywords |
| Non-terminal Symbol | Non-terminal Symbol | A symbol in a grammar that expands into other symbols. Rule names |
| Production Rule | Production Rule | A rule defining a non-terminal in terms of terminals and other non-terminals |
| Derivation | Derivation | The process of applying production rules from the start symbol to generate strings |
| Ordered Choice | Ordered Choice | PEG's choice operator `/`. Prioritizes candidates written first |
| Combinator | Combinator | A higher-order function/class that takes parsers as arguments and returns a new parser |
| Singleton | Singleton | A pattern having only one instance per class |
| Lazy Evaluation | Lazy Evaluation | A technique that defers computation until the value is needed |
| Circular Reference | Circular Reference | Mutual dependency where A references B and B references A |
| Transaction | Transaction | A mechanism for managing state changes via begin/commit/rollback |

---

[<- Part 11: Advanced Topics](#part-11-advanced-topics--the-road-to-ubnf) | [Next: Appendix B Complete Parser Reference ->](#appendix-b-complete-parser-reference-for-unlaxer-parser)

---

## Appendix B: Complete Parser Reference for unlaxer-parser

[<- Appendix A: Glossary](#appendix-a-parser-glossary)

---

### Terminal Parsers

#### elementary package (`org.unlaxer.parser.elementary`)

| Class | Type | Description |
|---------|------|------|
| `WordParser` | Literal | Exact match with a specified string |
| `SingleCharacterParser` | Single char (abstract) | Base class for single-character matching |
| `MappedSingleCharacterParser` | Single char (with transform) | Applies transformation to matched character |
| `NumberParser` | Numeric | Integer, decimal, and exponential notation |
| `SignParser` | Symbol | `+` or `-` sign |
| `ExponentParser` | Numeric | Exponent part like `e-3`, `E+5` |
| `SingleQuotedParser` | String | Single-quoted strings |
| `DoubleQuotedParser` | String | Double-quoted strings |
| `QuotedParser` | String | Base for quoted strings |
| `EscapeInQuotedParser` | String | Escape sequence processing |
| `SingleQuoteParser` | Symbol | Single quote character |
| `SingleStringParser` | Literal | Single-character literal |
| `EndOfSourceParser` | Boundary | End of input |
| `StartOfSourceParser` | Boundary | Start of input |
| `EndOfLineParser` | Boundary | End of line |
| `StartOfLineParser` | Boundary | Start of line |
| `EmptyLineParser` | Boundary | Empty line |
| `EmptyParser` | Special | Always succeeds (consumes 0 characters) |
| `LineTerminatorParser` | Delimiter | Newline character |
| `SpaceDelimitor` | Delimiter | Whitespace delimiter |
| `WildCardCharacterParser` | Wildcard | Any single character |
| `WildCardStringParser` | Wildcard | Any string (up to terminator) |
| `WildCardLineParser` | Wildcard | Any string to end of line |
| `WildCardInterleaveParser` | Wildcard | For unordered matching |
| `WildCardStringTerninatorParser` | Wildcard | Wildcard string terminator |
| `MultipleParser` | Repetition | Multiple match |
| `ParenthesesParser` | Parentheses | Content wrapped in `( ... )` |
| `NamedParenthesesParser` | Parentheses | Named parentheses |
| `EParser` | Symbol | `e` or `E` (for exponents) |
| `IgnoreCaseWordParser` | Literal | Case-insensitive string match |
| `AbstractTokenParser` | Base | Abstract base class for terminal parsers |

#### POSIX package (`org.unlaxer.parser.posix`)

| Class | Matches | POSIX character class |
|---------|----------|---------------|
| `DigitParser` | Digits 0-9 | `[:digit:]` |
| `AlphabetParser` | Letters a-zA-Z | `[:alpha:]` |
| `AlphabetNumericParser` | Alphanumeric | `[:alnum:]` |
| `AlphabetUnderScoreParser` | Letters + underscore | -- |
| `AlphabetNumericUnderScoreParser` | Alphanumeric + underscore | -- |
| `UpperParser` | Uppercase A-Z | `[:upper:]` |
| `LowerParser` | Lowercase a-z | `[:lower:]` |
| `SpaceParser` | Whitespace | `[:space:]` |
| `BlankParser` | Space/tab | `[:blank:]` |
| `PunctuationParser` | Punctuation | `[:punct:]` |
| `ControlParser` | Control characters | `[:cntrl:]` |
| `GraphParser` | Visible characters | `[:graph:]` |
| `PrintParser` | Printable characters | `[:print:]` |
| `AsciiParser` | ASCII characters | -- |
| `XDigitParser` | Hex digits | `[:xdigit:]` |
| `WordParser` (posix) | Alphanumeric + _ | `\w` |
| `ColonParser` | Colon `:` | -- |
| `CommaParser` | Comma `,` | -- |
| `DotParser` | Dot `.` | -- |
| `HashParser` | Hash `#` | -- |
| `SemiColonParser` | Semicolon `;` | -- |

#### ASCII package (`org.unlaxer.parser.ascii`)

| Class | Match character |
|---------|----------|
| `PlusParser` | `+` |
| `MinusParser` | `-` |
| `PointParser` | `.` |
| `GreaterThanParser` | `>` |
| `LessThanParser` | `<` |
| `EqualParser` | `=` |
| `DivisionParser` | `/` |
| `SlashParser` | `/` |
| `BackSlashParser` | `\` |
| `DoubleQuoteParser` | `"` |
| `LeftParenthesisParser` | `(` |
| `RightParenthesisParser` | `)` |

#### clang package (`org.unlaxer.parser.clang`)

| Class | Description |
|---------|------|
| `IdentifierParser` | C-style identifier `[a-zA-Z_][a-zA-Z0-9_]*` |
| `BlockComment` | `/* ... */` block comment |
| `CPPComment` | `// ...` line comment |
| `CStyleDelimitedLazyChain` | C-style delimited Chain |
| `CStyleDelimitor` | C-style delimiter (whitespace/comments) |
| `CStyleDelimitorElements` | Delimiter element collection |

---

### Combinator Parsers

#### Basic Combinators (`org.unlaxer.parser.combinator`)

| Class | Lazy version | BNF/PEG equivalent | Description |
|---------|--------|-------------|------|
| `Chain` | `LazyChain` | `A B C` | Sequence. All children match in order |
| `Choice` | `LazyChoice` | `A / B / C` | Ordered choice. Adopts first success |
| `ZeroOrMore` | `LazyZeroOrMore` | `A*`, `{A}` | Zero or more repetitions |
| `OneOrMore` | `LazyOneOrMore` | `A+` | One or more repetitions |
| `Optional` | `LazyOptional` | `A?`, `[A]` | Zero or one |
| `ZeroOrOne` | `LazyZeroOrOne` | `A?` | Alias for Optional |
| `Zero` | `LazyZero` | -- | Zero times (always succeeds empty) |
| `Repeat` | `LazyRepeat` | `A{m,n}` | Count-specified repetition |
| `Not` | -- | `!A` | Negative lookahead (no consumption) |
| `MatchOnly` | -- | `&A` | Positive lookahead (no consumption) |
| `NonOrdered` | -- | interleave | Unordered match |
| `Ordered` | -- | -- | Ordered match |
| `Reverse` | -- | -- | Reverse-order match |

#### AST Filtering

| Class | Description |
|---------|------|
| `ASTNode` | Include this parser's tokens in AST |
| `NotASTNode` | Exclude this parser's tokens from AST |
| `ASTNodeRecursive` | Recursively include in AST |
| `NotASTNodeRecursive` | Recursively exclude from AST |
| `ASTNodeRecursiveGrandChildren` | Recursively include grandchildren and beyond in AST |
| `NotASTNodeRecursiveGrandChildren` | Recursively exclude grandchildren and beyond from AST |
| `NotASTChildrenOnlyLazyChain` | NotAST for children only |
| `NotASTChildrenOnlyLazyChoice` | NotAST for children only |
| `NotASTLazyChain` | NotAST LazyChain |
| `NotASTLazyChoice` | NotAST LazyChoice |

#### Whitespace Handling

| Class | Description |
|---------|------|
| `WhiteSpaceDelimitedChain` | Whitespace-delimited sequence (Constructed version) |
| `WhiteSpaceDelimitedLazyChain` | Whitespace-delimited sequence (Lazy version) |

#### Wrappers and Utilities

| Class | Description |
|---------|------|
| `ParserWrapper` | Wrap a parser in another parser |
| `ParserHolder` | Parser holder (for deferred reference) |
| `TagWrapper` | Add tags to a parser |
| `RecursiveTagWrapper` | Recursively add tags |
| `ContainerParser` | Parser container |
| `Flatten` | Flatten nested tokens |
| `PropagationStopper` | Stop propagation |
| `AllPropagationStopper` | Stop all propagation |
| `DoCounsumePropagationStopper` | Stop propagation on consumption |
| `InvertMatchPropagationStopper` | Stop propagation on inverted match |
| `NotPropagatableSource` | Non-propagatable source |
| `AbstractPropagatableSource` | Base for propagatable sources |
| `MappedSingleCharacterParserHolder` | Holder for mapped single-character parser |

#### Occurrence Count Management

| Class | Description |
|---------|------|
| `Occurs` | Occurrence management (Constructed version) |
| `LazyOccurs` | Occurrence management (Lazy version) |
| `ConstructedOccurs` | Constructed occurrence count |
| `ChildOccursWithTerminator` | Occurrence count with terminator |

#### Collection and Predicates

| Class | Description |
|---------|------|
| `SingleChildCollectingParser` | Parser collecting from a single child |
| `NoneChildCollectingParser` | Childless collecting parser |
| `NoneChildParser` | Childless parser |
| `PredicateAnyMatchForParsedParser` | Predicate-based any match |

#### Base Classes

| Class | Description |
|---------|------|
| `LazyCombinatorParser` | Base for Lazy combinators |
| `ConstructedCombinatorParser` | Base for Constructed combinators |
| `ConstructedSingleChildParser` | Base for single-child Constructed combinators |
| `ConstructedMultiChildParser` | Base for multi-child Constructed combinators |
| `ConstructedMultiChildCollectingParser` | Base for collecting multi-child Constructed combinators |
| `LazyMultiChildParser` | Base for multi-child Lazy combinators |
| `LazyMultiChildCollectingParser` | Base for collecting multi-child Lazy combinators |
| `ChainInterface` | Chain behavior interface |
| `ChoiceInterface` | Choice behavior interface |
| `ChoiceCommitAction` | Choice success action |

---

### Reference Parsers

| Class | Description |
|---------|------|
| `ReferenceParser` | Reference to another parser |
| `ReferenceByNameParser` | Reference by name |
| `MatchedTokenParser` | Reference to a matched token |
| `MatchedChoiceParser` | Reference to the matched choice alternative |
| `MatchedNonOrderedParser` | Reference to matched order in NonOrdered |
| `OldMatchedTokenParser` | Legacy matched token reference |
| `Referencer` | Reference interface |

---

### Core Interfaces and Classes

| Class | Description |
|---------|------|
| `Parser` | Top-level interface for all parsers. `parse()`, `get()`, `getChildren()` |
| `AbstractParser` | Abstract base class for parsers |
| `LazyAbstractParser` | Abstract base for Lazy parsers |
| `ConstructedAbstractParser` | Abstract base for Constructed parsers |
| `TerminalSymbol` | Marker interface for terminal parsers |
| `NonTerminallSymbol` | Marker interface for non-terminal parsers |
| `StaticParser` | Marker for statically initialized parsers |
| `Parsers` | Parser list |
| `ParserInitializer` | Parser initialization |
| `ParserFactoryByClass` | Factory creating parsers from classes |
| `ParserFactoryBySupplier` | Factory creating parsers from Suppliers |
| `ParseException` | Parse exception |
| `ParserPrinter` | Serializes parser hierarchy to string |
| `RootParserIndicator` | Root parser marker |
| `HasChildParser` | Interface for having a single child parser |
| `HasChildrenParser` | Interface for having multiple child parsers |
| `ErrorMessageParser` | Error message generating parser |
| `SuggestsCollectorParser` | Input suggestion collecting parser |
| `SuggestableParser` | Parser capable of providing suggestions |
| `Suggests` | Collection of input suggestions |
| `Suggest` | Individual input suggestion |
| `CollectingParser` | Collecting parser interface |
| `MetaFunctionParser` | Meta-function parser |
| `NodeReduceMarker` | Node reduce marker |
| `PseudoRootParser` | Pseudo root parser |
| `PositionedElements` | Positioned elements |

#### Lazy-Related Interfaces

| Class | Description |
|---------|------|
| `LazyInstance` | Lazy instance |
| `LazyParserChildSpecifier` | Lazy child parser specifier (singular) |
| `LazyParserChildrenSpecifier` | Lazy child parser specifier (plural) |
| `LazyOccursParserSpecifier` | Lazy occurrence count parser specifier |
| `ParsersSpecifier` | Parser group specifier |

#### Propagation-Related

| Class | Description |
|---------|------|
| `PropagatableSource` | Propagatable source |
| `PropagatableDestination` | Propagation destination |
| `ChainParsers` | Chain parser group |
| `ChoiceParsers` | Choice parser group |
| `AfterParse` | Post-parse processing |
| `Initializable` | Initializable |
| `ChildOccurs` | Child occurrence count |
| `GlobalScopeTree` | Global scope tree |

---

### AST-Related (`org.unlaxer.ast`)

| Class | Description |
|---------|------|
| `ASTMapper` | Conversion from parse tree to AST |
| `ASTMapperContext` | AST conversion context |
| `ASTNodeKind` | AST node kind |
| `ASTNodeKindTree` | AST node kind tree |
| `NodeKindAndParser` | Node kind and parser pair |
| `HierarcyLevel` | Hierarchy level |
| `OperatorOperandPattern` | Operator-operand pattern |
| `RecursiveZeroOrMoreBinaryOperator` | Recursive processing for ZeroOrMore binary operators |
| `RecursiveZeroOrMoreOperator` | Recursive processing for ZeroOrMore operators |

---

### Expression Tree (`org.unlaxer.expressiontree`)

Expression tree-related classes used by tinyexpression are in this package.

---

### Context (`org.unlaxer.context`)

| Class | Description |
|---------|------|
| `ParseContext` | Parse execution context. Manages source, cursor, and transactions |
| `Transaction` | Transaction interface (begin/commit/rollback) |
| `ParserContextScopeTree` | Parser context scope tree |

---

### Core Data (`org.unlaxer`)

| Class | Description |
|---------|------|
| `Token` | Parse tree node |
| `TokenList` | Token list |
| `TokenKind` | Token kind (consumed, matchOnly) |
| `TokenPrinter` | Serializes token tree to string |
| `TokenPredicators` | Token search predicate utilities |
| `Parsed` | Parse result (success/failure/stopped) |
| `ParsedPrinter` | Serializes parse result to string |
| `Committed` | Committed state |
| `Source` | Source text abstraction |
| `StringSource` | String-based source |
| `StringSource2` | Improved string source |
| `Range` | Range (start/end positions) |
| `CursorRange` | Cursor range |
| `CodePointIndex` | Code point position |
| `CodePointLength` | Code point length |
| `CodePointOffset` | Code point offset |
| `Name` | Named object |
| `Tag` | Tag |
| `PropagatedTag` | Propagated tag |

---

**Newcomer**: That's a lot of parsers available. I now have a picture of the whole landscape.

**Senior**: You don't need to memorize all of them. Start with the basics -- `Chain`, `Choice`, `ZeroOrMore`, `Optional` and `WordParser`, `NumberParser`, `DigitParser` -- and pull in other parsers as needed.

**Newcomer**: I'll use this list as a reference. Thank you, Senior!

**Senior**: Ask anytime. The world of parsers runs deep, but with unlaxer-parser, there's nothing to fear.

---

[<- Appendix A: Glossary](#appendix-a-parser-glossary) | [Back to Table of Contents ->](#table-of-contents)

---

> This tutorial is a learning document for unlaxer-parser.
> The actual source code is in the following repositories:
> - unlaxer-parser: `/home/opa/work/unlaxer-parser`
> - tinyexpression: `/home/opa/work/tinyexpression`
