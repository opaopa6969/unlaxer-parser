---

[Table of Contents](./index.md) | [02 - TinyCalc BNF Definition ->](./02-bnf.md)

# 01 - Introduction

## About This Document

In this document series, we explain the internal structure of the parser combinator library **unlaxer** in detail through the implementation of a small calculator language called **TinyCalc**.

## What is unlaxer?

unlaxer is a Java parser combinator library inspired by RELAX NG.
It provides the following features:

- **Combinator pattern** - Build complex grammars by composing small parsers
- **Unlimited lookahead and backtracking** - Safe trial and rollback with a transaction stack
- **Recursive grammar support** - Resolve cyclic references with `LazyChain` and `LazyChoice`
- **Rich debugging features** - Visualize parsing via a listener system
- **Automatic token tree construction** - Obtain parse results as a tree structure

## What is TinyCalc?

TinyCalc is a small calculator language designed to explain how unlaxer works.
It provides the following features:

| Feature | Example |
|------|-----|
| Arithmetic operations | `1 + 2 * 3` |
| Parentheses | `(1 + 2) * 3` |
| Unary operators | `-5 + 3` |
| Floating-point numbers | `3.14 * 2` |
| Built-in functions (one argument) | `sin(3.14)`, `sqrt(2)` |
| Built-in functions (two arguments) | `max(1, 2)`, `pow(2, 8)` |
| Built-in functions (zero arguments) | `random()` |
| Variable declaration | `var x set 10;` |
| Identifier reference | `x + y * z` |

## Document Structure

| Chapter | Description |
|----|------|
| [02 - BNF Definition](02-bnf.md) | Define TinyCalc grammar in BNF |
| [03 - Building Parsers](03-building-parsers.md) | Convert BNF to parser definitions |
| [04 - Core Data Model](04-core-datamodel.md) | `Source`, `Cursor`, `Token`, and `Parsed` |
| [05 - Backtracking](05-backtracking.md) | How the transaction stack works |
| [06 - Combinators](06-combinators.md) | Detailed behavior of each combinator |
| [07 - Trace: 1+2*3](07-trace-1plus2mul3.md) | Complete parse trace |
| [08 - Trace: Complex Expression](08-trace-complex.md) | Variable declarations and function call trace |
| [09 - Lazy and Recursion](09-lazy-and-recursion.md) | Lazy parsers and recursive grammar |
| [10 - Debug System](10-debug-system.md) | Listener and debugging features |

## Source Code

Runnable source code is available in `examples/tinycalc/`:

```
examples/tinycalc/
  pom.xml                                          -- Maven build configuration
  src/main/java/org/unlaxer/tinycalc/
    TinyCalcParsers.java                           -- all parser definitions
    TinyCalcDemo.java                              -- demo entry point
```

### Build and Run

```bash
# Install unlaxer-common to local Maven repository
mvn -Dgpg.skip=true install

# Compile TinyCalc
mvn -f examples/tinycalc/pom.xml compile

# Run the demo
mvn -f examples/tinycalc/pom.xml exec:java \
  -Dexec.mainClass="org.unlaxer.tinycalc.TinyCalcDemo"
```

---

[Table of Contents](./index.md) | [02 - TinyCalc BNF Definition ->](./02-bnf.md)
