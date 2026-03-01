[<- 08 - Complete Trace: var x set 10; sin(x) + sqrt(3.14)](./08-trace-complex.md) | [Table of Contents](./index.md) | [10 - Debug and Listener System ->](./10-debug-system.md)

# 09 - Lazy Parsers and Recursive Grammar

## Recursive Grammar Problem

TinyCalc grammar has the following cyclic reference:

```
Expression -> Term -> Factor -> '(' Expression ')'
```

`Expression` includes `Factor`, and `Factor` includes `Expression`.
This is a cycle.

### Why is this a problem?

Normal `Chain` and `Choice` receive child parser instances in the constructor:

```java
// Works (no cycle)
Chain chain = new Chain(
    Parser.get(DigitParser.class),
    Parser.get(PlusParser.class),
    Parser.get(DigitParser.class)
);
```

But with cyclic references:

```java
// Can compile, but may fail at runtime
// ExpressionParser references FactorParser,
// and FactorParser references ExpressionParser
```

`Parser.get()` returns singletons.
If an instance is not available yet at first access, construction order becomes problematic.

## Solution with Lazy Parsers

unlaxer provides `LazyChain`, `LazyChoice`, and `LazyZeroOrMore`.
They solve this via **lazy initialization**.

### Normal vs Lazy

| | Chain | LazyChain |
|---|---|---|
| Child parser definition | constructor args | `getLazyParsers()` |
| Initialization timing | object creation | first parse execution |
| Cyclic reference support | no | yes |

### How `getLazyParsers()` works

```java
public static class ExpressionParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        // called on first parse() invocation
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

`getLazyParsers()` is executed when parsing actually starts.
By then, singleton instances like `TermParser` are already available.

## Full Cycle Map in TinyCalc

```
ExpressionParser --> TermParser --> FactorParser
       ^                              |
       |                              |- -> FunctionCallParser
       |                              |      |
       |                              |      |- -> SingleArgFunctionParser --> ExpressionParser (cycle)
       |                              |      `- -> TwoArgFunctionParser ----> ExpressionParser (cycle)
       |                              |
       |                              |- -> UnaryExpressionParser --> FactorParser (cycle)
       |                              |
       |                              `- -> ParenExpressionParser --> ExpressionParser (cycle)
       |
       `---------------------------------------------------------- cycle points
```

Main cycle points:

1. `ParenExpressionParser` -> `ExpressionParser`
2. `SingleArgFunctionParser` -> `ExpressionParser`
3. `TwoArgFunctionParser` -> `ExpressionParser`

`UnaryExpressionParser` -> `FactorParser` is also an indirect cycle.

## Types of Lazy Parsers

### LazyChain

Lazy version of concatenation parser.
All children must succeed in order.

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

`WhiteSpaceDelimitedLazyChain` is a `LazyChain` subclass that automates whitespace handling.

### LazyChoice

Lazy version of choice parser.
Any one candidate may succeed.

```java
public static class FactorParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(FunctionCallParser.class),
            Parser.get(UnaryExpressionParser.class),
            Parser.get(NumberParser.class),
            Parser.get(IdentifierParser.class),
            Parser.get(ParenExpressionParser.class)
        );
    }
}
```

### LazyZeroOrMore

Lazy version of repetition parser.
`getLazyParser()` defines one repeated unit.

```java
public static class VariableDeclarationsParser extends LazyZeroOrMore {
    @Override
    public Supplier<Parser> getLazyParser() {
        return new SupplierBoundCache<>(
            () -> Parser.get(VariableDeclarationParser.class)
        );
    }

    @Override
    public Optional<Parser> getLazyTerminatorParser() {
        return Optional.empty();
    }
}
```

`SupplierBoundCache` caches supplier results.
It creates the parser once, then reuses it.

## Preventing Infinite Recursion

Cyclic grammar can appear to allow infinite recursion, for example:

```
Expression -> Term -> Factor -> '(' Expression ')' -> Term -> Factor -> ...
```

In unlaxer, this is naturally bounded by:

### 1. Input consumption

Each parser consumes input.
`'('` consumes one character, so remaining input shrinks on each recursive step.
Recursion stops when input ends or no rule matches.

### 2. Failure in `Choice`

`FactorParser` is a `LazyChoice`.
The parenthesized branch requires `'('`.
If input does not start with `'('`, that branch fails immediately and others are tried.

### Example: parsing `((1+2))`

```
ExpressionParser("((1+2))")
`- TermParser -> FactorParser
   `- ParenExpressionParser
      |- '(' ok                  <- pos 0->1
      |- ExpressionParser("(1+2))")
      |  `- TermParser -> FactorParser
      |     `- ParenExpressionParser
      |        |- '(' ok         <- pos 1->2
      |        |- ExpressionParser("1+2)")
      |        |  `- ... matches "1+2"
      |        `- ')' ok         <- pos 5->6
      `- ')' ok                  <- pos 6->7
```

Nested parentheses are handled correctly.
No infinite nesting occurs because input is finite.

## Role of `getNotAstNodeSpecifier()`

Subclasses of `LazyChain` and `LazyChoice` must override `getNotAstNodeSpecifier()`:

```java
@Override
public Optional<RecursiveMode> getNotAstNodeSpecifier() {
    return Optional.empty();
}
```

Returning `Optional.empty()` keeps tokens generated by this parser in the AST.

`WhiteSpaceDelimitedLazyChain` already implements this in the base class,
so subclasses usually do not need to override it explicitly.

## Internal Behavior of `Parser.get()`

```java
Parser parser = Parser.get(ExpressionParser.class);
```

`Parser.get()` does:

1. look up the class in internal cache (`ConcurrentHashMap`)
2. if not found, instantiate via reflection using default constructor
3. register in cache
4. return cached instance

Therefore, the same parser class always uses the same instance.
With lazy parsers, child parsers are not fixed during instantiation;
`getLazyParsers()` runs on first `parse()` call.

---

[<- 08 - Complete Trace: var x set 10; sin(x) + sqrt(3.14)](./08-trace-complex.md) | [Table of Contents](./index.md) | [10 - Debug and Listener System ->](./10-debug-system.md)
