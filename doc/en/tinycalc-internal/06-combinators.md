[<- 05 - Transaction Stack and Backtracking](./05-backtracking.md) | [Table of Contents](./index.md) | [07 - Complete Trace: 1+2*3 ->](./07-trace-1plus2mul3.md)

# 06 - How Each Combinator Works

## Combinator List

| Combinator | BNF form | Description |
|-------------|---------|------|
| `Chain` | `A B C` | all elements must succeed in order |
| `Choice` | `A \| B \| C` | any one candidate may succeed |
| `ZeroOrMore` | `{ A }` | repeat zero or more times |
| `OneOrMore` | `A+` | repeat one or more times |
| `Optional` | `[ A ]` | zero or one |
| `WhiteSpaceDelimitedChain` | `A B C` (whitespace tolerant) | chain with automatic whitespace |
| `LazyChain` | `A B C` (lazy) | chain with cyclic-reference support |
| `LazyChoice` | `A \| B \| C` (lazy) | choice with cyclic-reference support |
| `LazyZeroOrMore` | `{ A }` (lazy) | repetition with cyclic-reference support |

## Chain - Concatenation

### Behavior

All child parsers must succeed in order.
If one fails, the whole parser fails.

### Example in TinyCalc

```java
// BNF: Digits '.' Digits
new Chain(
    digitsParser,
    pointParser,
    digitsParser
)
```

### Parse Process

Input `"3.14"`:

```
Chain start
|- digitsParser: "3" ok (pos 1)
|- pointParser: "." ok (pos 2)
|- digitsParser: "14" ok (pos 4)
`- result: success, matched "3.14"
```

Input `"3+4"`:

```
Chain start
|- digitsParser: "3" ok (pos 1)
|- pointParser: "+" fail
`- result: fail, backtrack
```

## Choice - Selection

### Behavior

Tries candidates from top to bottom and returns the first success.
Fails only when all candidates fail.

### Example in TinyCalc

```java
// BNF: '*' | '/'
public static class MulOpParser extends Choice {
    public MulOpParser() {
        super(
            MultipleParser.class,
            DivisionParser.class
        );
    }
}
```

### Parse Process

Current input `"*3"`:

```
Choice start
|- MultipleParser: "*" ok
`- result: success, matched "*"
```

Current input `"/3"`:

```
Choice start
|- MultipleParser: "/" fail -> backtrack
|- DivisionParser: "/" ok
`- result: success, matched "/"
```

## ZeroOrMore - Zero or More Repetition

### Behavior

Applies child parser repeatedly until failure.
Returns success even with zero matches (`stopped`).

### Example in TinyCalc

```java
// BNF: { ('*' | '/') Factor }
new ZeroOrMore(
    new WhiteSpaceDelimitedChain(
        Parser.get(MulOpParser.class),
        Parser.get(FactorParser.class)
    )
)
```

### Parse Process

Input `"*3/2"`:

```
ZeroOrMore start
|- loop1: "*3" ok (pos 2)
|- loop2: "/2" ok (pos 4)
|- loop3: end of input -> stop
`- result: success (2 matches)
```

Input `"+3"` (no mul/div):

```
ZeroOrMore start
|- loop1: "+" fail -> stop
`- result: success (0 matches, stopped)
```

## OneOrMore - One or More Repetition

### Behavior

Same as `ZeroOrMore`, but requires at least one match.

### Example in TinyCalc

```java
// BNF: Digit+
new OneOrMore(DigitParser.class)
```

### Parse Process

Input `"123"`:

```
OneOrMore start
|- loop1: "1" ok
|- loop2: "2" ok
|- loop3: "3" ok
|- loop4: next is not a digit -> stop
`- result: success (3 matches)
```

## Optional - Optional Element

### Behavior

Tries child parser. Even if it fails, the whole parser still succeeds.

### Example in TinyCalc

```java
// BNF: ['set' Expression]
new Optional(
    Parser.get(VariableInitializerParser.class)
)
```

### Parse Process

When `set` exists:

```
Optional start
|- VariableInitializerParser: "set 10" ok
`- result: success, matched "set 10"
```

When `set` does not exist (semicolon immediately follows):

```
Optional start
|- VariableInitializerParser: ";" fail -> backtrack
`- result: success (stopped), no match
```

## WhiteSpaceDelimitedChain - Whitespace-Tolerant Chain

### Behavior

Like `Chain`, but auto-inserts `SpaceDelimitor` (zero or more spaces) before/after each child parser.

### Internal Conversion

```
Definition: WhiteSpaceDelimitedChain(A, B, C)

Internal form:
  Chain(Space, A, Space, B, Space, C, Space)
```

`Space` is `SpaceDelimitor` (a subclass of `LazyZeroOrMore`).

### Example in TinyCalc

```java
// BNF: Factor { ('*' | '/') Factor }
// both "2 * 3" and "2*3" are valid
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

### How `SpaceDelimitor` Works

`SpaceDelimitor` extends `LazyZeroOrMore` and repeats `SpaceParser` zero or more times.

```java
public class SpaceDelimitor extends LazyZeroOrMore {
    @Override
    public Supplier<Parser> getLazyParser() {
        return new SupplierBoundCache<>(SpaceParser::new);
    }
}
```

`SpaceDelimitor` is tagged with `NodeKind.notNode`,
so it is automatically removed from the AST (`getRootToken(true)`).

## Combinator Composition

TinyCalc's expression parser is built by combining composable parsers:

```
ExpressionParser
  = WhiteSpaceDelimitedLazyChain(
      TermParser,
      ZeroOrMore(
        WhiteSpaceDelimitedChain(
          AddOpParser,
          TermParser
        )
      )
    )
```

This structure correctly parses all of the following:

- `1+2`
- `1 + 2`
- `1+2+3+4`
- `1 + 2*3 - 4/5`

---

[<- 05 - Transaction Stack and Backtracking](./05-backtracking.md) | [Table of Contents](./index.md) | [07 - Complete Trace: 1+2*3 ->](./07-trace-1plus2mul3.md)
