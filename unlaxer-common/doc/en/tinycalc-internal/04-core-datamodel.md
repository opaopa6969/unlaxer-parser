[<- 03 - Building Parsers from BNF](./03-building-parsers.md) | [Table of Contents](./index.md) | [05 - Transaction Stack and Backtracking ->](./05-backtracking.md)

# 04 - Core Data Model

## Overview

The unlaxer parsing process is built around these core data structures:

```
Source (input string)
  ->
Parser.parse(ParseContext) -> Parsed (parse result)
  ->
Parsed.getRootToken() -> Token (token tree)
```

## Source - Input String

`Source` is an interface representing the input string to be parsed.

### Creation

```java
Source source = StringSource.createRootSource("1+2*3");
```

### Kinds

| SourceKind | Purpose |
|------------|------|
| `root` | top-level source string |
| `subSource` | substring extracted as a parse result |
| `attached` | source attached to another source |
| `detached` | independent source |

`Source` is accessed by code points, so Unicode surrogate pairs are handled correctly.

## Cursor - Position Tracking

`Cursor` tracks the current position inside a source.

### Key Information

| Method | Description |
|----------|------|
| `position()` | code point index |
| `lineNumber()` | line number |
| `positionInLine()` | column position |
| `positionInRoot()` | absolute position from root source |

### Cursor Kind

```java
public enum CursorKind {
    startInclusive,
    endExclusive
}
```

## ParseContext - Parse Execution Environment

`ParseContext` is the context object that manages parsing state.

### Creation

```java
ParseContext context = new ParseContext(
    StringSource.createRootSource("1+2*3")
);
```

### Main Responsibilities

1. **Source management** - stores input text
2. **Cursor management** - tracks current read position
3. **Token stack management** - maintains token hierarchy during parsing
4. **Transaction management** - save points for backtracking
5. **Debug listener management** - event notifications during parsing

## Parsed - Parse Result

`Parsed` represents the result returned by a parser.

### Status

```java
public enum Status {
    succeeded,
    stopped,
    failed;
}
```

### Key Methods

```java
Parsed parsed = parser.parse(context);

parsed.isSucceeded();
parsed.isFailed();

Token rootToken = parsed.getRootToken();
Token astToken = parsed.getRootToken(true);
```

### `succeeded` vs `stopped`

- `succeeded` - parser consumed input and succeeded
- `stopped` - parser stopped with zero matches in a repetition parser (`ZeroOrMore`) but still counts as success

## Token - Token Tree

`Token` represents parse output as a tree.
Each node contains the matched substring and parser information.

### Key Fields

```java
public class Token {
    public final Source source;
    public Parser parser;
    public Optional<Token> parent;
    private final TokenList originalChildren;
    public final TokenList filteredChildren;
}
```

### Example Token Tree

Parsing `1+2*3`:

```
'1+2*3' : ExpressionParser
 '1' : TermParser
  '1' : FactorParser
   '1' : NumberParser
    '1' : DigitParser
  <EMPTY> : ZeroOrMore
 '+2*3' : ZeroOrMore
  '+2*3' : WhiteSpaceDelimitedChain
   '+' : AddOpParser
    '+' : PlusParser
   '2*3' : TermParser
    '2' : FactorParser
     '2' : NumberParser
      '2' : DigitParser
    '*3' : ZeroOrMore
     '*3' : WhiteSpaceDelimitedChain
      '*' : MulOpParser
       '*' : MultipleParser
      '3' : FactorParser
       '3' : NumberParser
        '3' : DigitParser
```

### Full Token Tree vs AST

`getRootToken()` returns all tokens.
`getRootToken(true)` returns the AST with meta nodes removed.

```
AST: '1+2*3' : ExpressionParser
 '1' : TermParser
  '1' : FactorParser
   '1' : NumberParser
    '1' : DigitParser
 '+' : PlusParser
 '2*3' : TermParser
  '2' : FactorParser
   '2' : NumberParser
    '2' : DigitParser
  '*' : MultipleParser
  '3' : FactorParser
   '3' : NumberParser
    '3' : DigitParser
```

### TokenPrinter

`TokenPrinter` prints token trees in a human-readable format.

```java
String treeText = TokenPrinter.get(parsed.getRootToken());
System.out.println(treeText);
```

## Data Flow Summary

```
         input string
            |
     StringSource.createRootSource("1+2*3")
            |
            v
    +-----------------+
    |   ParseContext   |
    +-----------------+
            |
     parser.parse(context)
            |
            v
    +-----------------+
    |     Parsed      |
    +-----------------+
            |
     parsed.getRootToken()
            |
            v
    +-----------------+
    |   Token Tree    |
    +-----------------+
            |
     TokenPrinter.get(token)
            |
            v
       text output
```

---

[<- 03 - Building Parsers from BNF](./03-building-parsers.md) | [Table of Contents](./index.md) | [05 - Transaction Stack and Backtracking ->](./05-backtracking.md)
