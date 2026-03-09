[<- 02 - TinyCalc BNF Definition](./02-bnf.md) | [Table of Contents](./index.md) | [04 - Core Data Model ->](./04-core-datamodel.md)

# 03 - Building Parsers from BNF

## Mapping BNF to unlaxer

| BNF notation | unlaxer class | Purpose |
|---------|----------------|------|
| `A B C` | `Chain` / `LazyChain` | concatenation |
| `A \| B \| C` | `Choice` / `LazyChoice` | selection |
| `{ A }` | `ZeroOrMore` | zero or more |
| `A+` | `OneOrMore` | one or more |
| `[ A ]` | `Optional` | optional |
| `'word'` | `WordParser("word")` | keyword |
| `'c'` (single character) | concrete subclass of `SingleCharacterParser` | single character |

`LazyChain` / `LazyChoice` behave like normal `Chain` / `Choice`,
but solve cyclic references by deferring child parser construction.

## Step 1: Leaf Parsers (Using Existing Classes)

unlaxer already provides common character parsers:

```java
// package org.unlaxer.parser.ascii
PlusParser              // '+'
MinusParser             // '-'
LeftParenthesisParser   // '('
RightParenthesisParser  // ')'
DivisionParser          // '/'
PointParser             // '.'

// package org.unlaxer.parser.elementary
MultipleParser          // '*'
NumberParser            // number (supports sign, decimal point, exponent)

// package org.unlaxer.parser.posix
DigitParser                       // 0-9
AlphabetUnderScoreParser          // a-z A-Z _
AlphabetNumericUnderScoreParser   // a-z A-Z 0-9 _
CommaParser                       // ','
```

## Step 2: Define New Leaf Parsers

Define parsers required by TinyCalc that do not already exist.
Extending `SingleCharacterParser` is the easiest way:

```java
public static class SemicolonParser extends SingleCharacterParser {
    private static final long serialVersionUID = 1L;

    @Override
    public boolean isMatch(char target) {
        return ';' == target;
    }
}
```

**Code style**: This codebase uses Yoda conditions, such as `';' == target`.

## Step 3: Keyword Parsers

Use `WordParser` for string keywords:

```java
new WordParser("var")
new WordParser("variable")
new WordParser("set")
new WordParser("sin")
new WordParser("random")
```

`WordParser` checks exact string equality.

## Step 4: Identifier Parser

BNF:
```
Identifier ::= AlphabetUnderScore { AlphabetNumericUnderScore }
```

Implementation in unlaxer:

```java
public static class IdentifierParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AlphabetUnderScoreParser.class),
            new ZeroOrMore(
                Parser.get(AlphabetNumericUnderScoreParser.class)
            )
        );
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}
```

### What is `Parser.get()`?

`Parser.get(SomeParser.class)` returns a singleton parser instance.
The same parser class always returns the same instance, so parser nodes can be efficiently shared.

### What is `getNotAstNodeSpecifier()`?

When overriding `LazyChain` / `LazyChoice`,
`getNotAstNodeSpecifier()` controls whether produced tokens are treated as AST nodes.
Returning `Optional.empty()` keeps this parser's tokens in the AST.

## Step 5: Choice Parser

BNF:
```
SingleArgFuncName ::= 'sin' | 'cos' | 'tan' | 'sqrt' | 'abs' | 'log'
```

Implementation in unlaxer:

```java
public static class SingleArgFunctionNameParser extends Choice {
    public SingleArgFunctionNameParser() {
        super(
            Name.of("singleArgFunctionName"),
            new WordParser("sqrt"),
            new WordParser("sin"),
            new WordParser("cos"),
            new WordParser("tan"),
            new WordParser("abs"),
            new WordParser("log")
        );
    }
}
```

**Important**: `Choice` tries candidates in order.
If candidates share a prefix, put longer candidates first.

## Step 6: Parsers with Cyclic References (`LazyChain`)

Function call BNF:
```
SingleArgFunction ::= SingleArgFuncName '(' Expression ')'
```

Even though `Expression` is recursively referenced, `LazyChain` handles it:

```java
public static class SingleArgFunctionParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(SingleArgFunctionNameParser.class),
            Parser.get(LeftParenthesisParser.class),
            Parser.get(ExpressionParser.class),
            Parser.get(RightParenthesisParser.class)
        );
    }
}
```

`getLazyParsers()` is called only when parsing actually starts,
so `ExpressionParser` is already available by then.

## Step 7: Expression Parsers (Operator Precedence)

### Term (multiplication/division)

```java
public static class TermParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(FactorParser.class),
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    Parser.get(MulOpParser.class),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
}
```

### Expression (addition/subtraction)

```java
public static class ExpressionParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(TermParser.class),
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    Parser.get(AddOpParser.class),
                    Parser.get(TermParser.class)
                )
            )
        );
    }
}
```

### `WhiteSpaceDelimitedLazyChain` / `WhiteSpaceDelimitedChain`

These classes automatically insert `SpaceDelimitor` (zero or more whitespace) between child parsers.
That allows both `1+2*3` and `1 + 2 * 3` to parse identically.

Internally:
```
Original: [Factor, ZeroOrMore(...)]
Converted by WhiteSpaceDelimited:
Actual: [Space, Factor, Space, ZeroOrMore(...), Space]
```

## Step 8: Variable Declaration

```java
public static class VariableDeclarationParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(VarKeywordParser.class),
            Parser.get(IdentifierParser.class),
            new Optional(
                Parser.get(VariableInitializerParser.class)
            ),
            Parser.get(SemicolonParser.class)
        );
    }
}
```

## Step 9: Root Parser

```java
public static class TinyCalcParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(VariableDeclarationsParser.class),
            Parser.get(ExpressionParser.class)
        );
    }
}
```

## Full Parser Graph

```
TinyCalcParser (WhiteSpaceDelimitedLazyChain)
|- VariableDeclarationsParser (LazyZeroOrMore)
|  `- VariableDeclarationParser (WhiteSpaceDelimitedLazyChain)
|     |- VarKeywordParser (Choice): "variable" | "var"
|     |- IdentifierParser (LazyChain)
|     |- Optional
|     |  `- VariableInitializerParser: "set" Expression
|     `- SemicolonParser
`- ExpressionParser (WhiteSpaceDelimitedLazyChain)
   |- TermParser (WhiteSpaceDelimitedLazyChain)
   |  |- FactorParser (LazyChoice)
   |  |  |- FunctionCallParser (LazyChoice)
   |  |  |  |- TwoArgFunctionParser
   |  |  |  |- SingleArgFunctionParser
   |  |  |  `- NoArgFunctionParser
   |  |  |- UnaryExpressionParser (LazyChain)
   |  |  |- NumberParser (existing)
   |  |  |- IdentifierParser (LazyChain)
   |  |  `- ParenExpressionParser <- Expression (cycle)
   |  `- ZeroOrMore: MulOp Factor
   `- ZeroOrMore: AddOp Term
```

---

[<- 02 - TinyCalc BNF Definition](./02-bnf.md) | [Table of Contents](./index.md) | [04 - Core Data Model ->](./04-core-datamodel.md)
