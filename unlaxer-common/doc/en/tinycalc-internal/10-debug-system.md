---

[<- 09 - Lazy Parsers and Recursive Grammar](./09-lazy-and-recursion.md) | [Table of Contents](./index.md)

# 10 - Debug and Listener System

## Overview

unlaxer provides a debugging system to trace parsing behavior in detail.
It helps visualize how each parser interprets input.

## TokenPrinter - Displaying the Token Tree

The simplest debugging method is to print the token tree:

```java
Parser parser = TinyCalcParsers.getExpressionParser();
ParseContext context = new ParseContext(StringSource.createRootSource("1+2*3"));
Parsed parsed = parser.parse(context);

if (parsed.isSucceeded()) {
    // full token tree
    System.out.println(TokenPrinter.get(parsed.getRootToken()));

    // AST (meta nodes removed)
    System.out.println(TokenPrinter.get(parsed.getRootToken(true)));
}
```

### Output Format

```
'matched text' : ParserClassName
```

Indentation represents parent-child relationships.

### Full Token Tree vs AST

| | Full Token Tree | AST |
|---|---|---|
| Retrieval | `getRootToken()` | `getRootToken(true)` |
| Whitespace tokens | included | excluded |
| Empty match in Optional | included as `<EMPTY>` | excluded |
| Empty match in ZeroOrMore | included as `<EMPTY>` | excluded |
| Use case | debugging/internal behavior | semantic analysis/code generation |

## ParseContext Configuration

### CreateMetaTokenSpecifier

Controls whether meta tokens (intermediate parser results) are generated:

```java
// enable meta tokens (default: off)
ParseContext context = new ParseContext(
    StringSource.createRootSource("1+2*3"),
    CreateMetaTokenSpecifier.createMetaOn
);
```

With meta tokens enabled, intermediate tokens are produced at each parse step,
which gives more detailed trees.

## Parse Result Check Patterns

### Success/Failure Check

```java
Parsed parsed = parser.parse(context);

if (parsed.isSucceeded()) {
    System.out.println("succeeded: " + parsed.status);
    Token root = parsed.getRootToken();
    System.out.println("matched: " + root.source.toString());
} else {
    System.out.println("failed: " + parsed.status);
}
```

### Full-Match Check

To verify whether the parser consumed all input,
compare matched length with input length:

```java
if (parsed.isSucceeded()) {
    Token root = parsed.getRootToken();
    String matched = root.source.toString();
    if (matched.length() == input.length()) {
        System.out.println("full match");
    } else {
        System.out.println("partial match: " + matched);
    }
}
```

### Status Types

| Status | Meaning | Example |
|------------|------|-----|
| `succeeded` | succeeded after consuming input | `NumberParser` on `"123"` |
| `stopped` | succeeded with zero matches | `ZeroOrMore(Digit)` on `"abc"` |
| `failed` | match failed | `DigitParser` on `"abc"` |

## Test Helpers (`ParserTestBase`)

In unlaxer tests, classes can extend `ParserTestBase` for helper methods:

```java
public class MyTest extends ParserTestBase {

    @Test
    public void testFullMatch() {
        Parser parser = TinyCalcParsers.getExpressionParser();
        testAllMatch(parser, "1+2*3");
    }

    @Test
    public void testPartialMatch() {
        Parser parser = TinyCalcParsers.getExpressionParser();
        testPartialMatch(parser, "1 +", "1 ");
    }

    @Test
    public void testNoMatch() {
        Parser parser = TinyCalcParsers.getExpressionParser();
        testUnMatch(parser, "");
    }
}
```

### Main Helper Methods

| Method | Purpose |
|----------|------|
| `testAllMatch(parser, source)` | verify full input consumption |
| `testPartialMatch(parser, source, matched)` | verify specific partial match |
| `testUnMatch(parser, source)` | verify no match |
| `testSucceededOnly(parser, source)` | verify success only (consumption not checked) |

## TinyCalcDemo Implementation

`TinyCalcDemo.java` is a demo program that shows parser behavior with colored output.

### Run

```bash
mvn -f examples/tinycalc/pom.xml exec:java \
  -Dexec.mainClass="org.unlaxer.tinycalc.TinyCalcDemo"
```

### Output Example

```
=== TinyCalc Demo ===
--- Simple Expressions ---
  [FULL MATCH] Expression   "1+2*3" -> matched: "1+2*3"
  [FULL MATCH] Expression   "1 + 2 * 3" -> matched: "1 + 2 * 3"
  [FULL MATCH] Expression   "(1+2)*3" -> matched: "(1+2)*3"
--- Function Calls ---
  [FULL MATCH] Expression   "sin(3.14)" -> matched: "sin(3.14)"
  [FULL MATCH] Expression   "max(1,2)" -> matched: "max(1,2)"
  [FULL MATCH] Expression   "random()" -> matched: "random()"
--- Full TinyCalc (with variables) ---
  [FULL MATCH] TinyCalc     "var x set 10; sin(x) + sqrt(3.14)" -> ...
--- Expected Failures ---
  [FAILED]   Expression   ""
  [FAILED]   Expression   "+++"
  [PARTIAL]  Expression   "1 +" -> matched: "1 "
```

Color meaning:

- green: FULL MATCH
- yellow: PARTIAL
- red: FAILED

### Trace Output

You can also print a detailed token tree for specific input:

```java
static void parseExpressionWithTrace(String input) {
    Parser parser = TinyCalcParsers.getExpressionParser();
    ParseContext context = new ParseContext(StringSource.createRootSource(input));
    Parsed parsed = parser.parse(context);

    if (parsed.isSucceeded()) {
        Token rootToken = parsed.getRootToken();
        System.out.println("Token Tree:");
        System.out.println(TokenPrinter.get(rootToken));

        System.out.println("Reduced Token Tree (AST):");
        System.out.println(TokenPrinter.get(parsed.getRootToken(true)));
    }
}
```

## Debugging Tips

### 1. Investigating parse failures

When parsing fails, use partial match length to find where parsing stopped:

```java
Parsed parsed = parser.parse(context);
if (parsed.isSucceeded()) {
    String matched = parsed.getRootToken().source.toString();
    // matched.length() -> consumed length
    // input.length() - matched.length() -> remaining part
}
```

### 2. Verify structure via token tree

If parsing succeeds but structure is unexpected, print the token tree:

```java
System.out.println(TokenPrinter.get(parsed.getRootToken()));
```

Parser class names in nodes show which rules matched.

### 3. Test incrementally

For complex grammar debugging, test from sub-parsers upward:

```java
// first Number parser
testAllMatch(Parser.get(NumberParser.class), "3.14");

// then Factor
testAllMatch(Parser.get(FactorParser.class), "3.14");

// then Term
testAllMatch(Parser.get(TermParser.class), "2*3.14");

// finally Expression
testAllMatch(Parser.get(ExpressionParser.class), "1+2*3.14");
```

---

[<- 09 - Lazy Parsers and Recursive Grammar](./09-lazy-and-recursion.md) | [Table of Contents](./index.md)
