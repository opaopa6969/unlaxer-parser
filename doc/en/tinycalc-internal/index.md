# TinyCalc Parser Internals

This document series explains the internal architecture of the unlaxer parser combinator library through the implementation of the TinyCalc calculator language.

## Table of Contents

1. [Introduction](01-introduction.md)
2. [TinyCalc BNF Definition](02-bnf.md)
3. [Building Parsers from BNF](03-building-parsers.md)
4. [Core Data Model](04-core-datamodel.md)
5. [Transaction Stack and Backtracking](05-backtracking.md)
6. [How Each Combinator Works](06-combinators.md)
7. [Complete Trace: 1+2*3](07-trace-1plus2mul3.md)
8. [Complete Trace: var x set 10; sin(x) + sqrt(3.14)](08-trace-complex.md)
9. [Lazy Parsers and Recursive Grammar](09-lazy-and-recursion.md)
10. [Debug and Listener System](10-debug-system.md)
11. [2.4.0 Compatibility Layer and Diagnostic Extension](11-compat-and-diagnostics.md)

## Source Code

Runnable code is available at [`examples/tinycalc/`](../../../examples/tinycalc/).
