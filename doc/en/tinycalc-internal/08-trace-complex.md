[<- 07 - Complete Trace: 1+2*3](./07-trace-1plus2mul3.md) | [Table of Contents](./index.md) | [09 - Lazy Parsers and Recursive Grammar ->](./09-lazy-and-recursion.md)

# 08 - Complete Trace: `var x set 10; sin(x) + sqrt(3.14)`

## Input

```
var x set 10; sin(x) + sqrt(3.14)
```

This input uses all core TinyCalc features:

- variable declaration (with initialization)
- function call (single argument)
- arithmetic operations
- identifier reference
- floating-point number

## Full Parser Call Flow

```
TinyCalcParser.parse("var x set 10; sin(x) + sqrt(3.14)")
|
|- VariableDeclarationsParser (ZeroOrMore)
|  |- loop1: VariableDeclarationParser
|  |  |- [Space] -> <EMPTY>
|  |  |- VarKeywordParser (Choice)
|  |  |  |- "variable" -> input "var" has 3 chars, "variable" has 8 -> fail
|  |  |  `- "var" ok
|  |  |- [Space] -> " " (one space)
|  |  |- IdentifierParser: "x" ok
|  |  |- [Space] -> " "
|  |  |- Optional(VariableInitializerParser)
|  |  |  |- VariableInitializerParser
|  |  |  |  |- [Space] -> <EMPTY>
|  |  |  |  |- WordParser("set"): "set" ok
|  |  |  |  |- [Space] -> " "
|  |  |  |  |- ExpressionParser: "10" ok
|  |  |  |  |- [Space] -> <EMPTY>
|  |  |  |  `- result: "set 10" ok
|  |  |  `- result: "set 10" ok
|  |  |- [Space] -> <EMPTY>
|  |  |- SemicolonParser: ";" ok
|  |  |- [Space] -> <EMPTY>
|  |  `- result: "var x set 10;" ok
|  |- loop2: VariableDeclarationParser
|  |  |- VarKeywordParser -> "sin" is not a keyword -> fail
|  |  `- backtrack -> stop loop
|  `- result: 1 match
|
|- [Space] -> " "
|
|- ExpressionParser.parse("sin(x) + sqrt(3.14)")
|  |- TermParser
|  |  |- FactorParser
|  |  |  |- FunctionCallParser (LazyChoice)
|  |  |  |  |- TwoArgFunctionParser
|  |  |  |  |  |- TwoArgFuncName -> "sin" is not max/min/pow -> fail
|  |  |  |  |  `- backtrack
|  |  |  |  |- SingleArgFunctionParser
|  |  |  |  |  |- SingleArgFuncName -> "sin" ok
|  |  |  |  |  |- '(' ok
|  |  |  |  |  |- ExpressionParser -> "x" ok (identifier)
|  |  |  |  |  |- ')' ok
|  |  |  |  |  `- result: "sin(x)" ok
|  |  |  |  `- result: SingleArgFunctionParser ok
|  |  |  `- result: "sin(x)" ok
|  |  |- ZeroOrMore(MulOp Factor)
|  |  |  |- " " -> MulOpParser: "+" is not '*'/'/' -> fail
|  |  |  `- result: 0 matches
|  |  `- result: "sin(x)" ok
|  |- ZeroOrMore(AddOp Term)
|  |  |- loop1:
|  |  |  |- AddOpParser: "+" ok
|  |  |  |- TermParser
|  |  |  |  |- FactorParser
|  |  |  |  |  |- FunctionCallParser
|  |  |  |  |  |  |- TwoArgFunctionParser -> fail
|  |  |  |  |  |  |- SingleArgFunctionParser
|  |  |  |  |  |  |  |- SingleArgFuncName -> "sqrt" ok
|  |  |  |  |  |  |  |- '(' ok
|  |  |  |  |  |  |  |- ExpressionParser -> "3.14" ok (number)
|  |  |  |  |  |  |  |- ')' ok
|  |  |  |  |  |  |  `- result: "sqrt(3.14)" ok
|  |  |  |  |  |  `- result: SingleArgFunctionParser ok
|  |  |  |  |  `- result: "sqrt(3.14)" ok
|  |  |  |  |- ZeroOrMore -> 0 matches
|  |  |  |  `- result: "sqrt(3.14)" ok
|  |  |  `- loop1 success: matched "+ sqrt(3.14)"
|  |  |- loop2: end of input -> stop loop
|  |  `- result: 1 match
|  `- result: "sin(x) + sqrt(3.14)" ok
|
`- result: full match ok
```

## Key Points

### 1. End of variable declaration loop

`VariableDeclarationsParser` is `ZeroOrMore`, so it keeps reading declarations.
On loop 2, it tries to parse `"sin"` as a variable declaration keyword and fails, so the loop stops.

### 2. Candidate order in `VarKeywordParser`

```java
new WordParser("variable"),
new WordParser("var")
```

Putting `"variable"` first prevents `"variable"` from being misread as `"var" + "iable"`.
If input is `"var "`, the first candidate fails and the second succeeds.

### 3. Backtracking in `FunctionCallParser`

While parsing `sin(x)`, `FunctionCallParser` first tries `TwoArgFunctionParser`.
`TwoArgFunctionNameParser` (`min|max|pow`) does not match `"sin"`, so it immediately backtracks and tries `SingleArgFunctionParser`.

### 4. Expression inside function calls

Inside `sin(x)`, `x` is parsed as:
`ExpressionParser -> TermParser -> FactorParser -> IdentifierParser`.

Inside `sqrt(3.14)`, `3.14` is parsed via the same path into `NumberParser`.

### 5. Whitespace handling

With `WhiteSpaceDelimitedLazyChain`, spaces are skipped at positions like:

```
var[space]x[space]set[space]10[space];[space]sin(x)[space]+[space]sqrt(3.14)
```

These spaces appear in the token tree as `SpaceDelimitor`, but are removed from the AST.

## Performance View

Backtracking counts in this example:

| Parser | Backtracking reason | Count |
|----------|-------------------|------|
| VarKeywordParser | returns from `"variable"` to `"var"` | 1 |
| FunctionCallParser | returns after `TwoArgFunction` failure | 2 |
| FactorParser | returns after FuncCall/Unary failure | several |
| Choice inside NumberParser | decimal form trials fail | several |

Backtracking stays roughly linear with input length in this grammar.
TinyCalc does not show exponential blow-up here.

---

[<- 07 - Complete Trace: 1+2*3](./07-trace-1plus2mul3.md) | [Table of Contents](./index.md) | [09 - Lazy Parsers and Recursive Grammar ->](./09-lazy-and-recursion.md)
