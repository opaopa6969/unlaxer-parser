[<- 06 - How Each Combinator Works](./06-combinators.md) | [Table of Contents](./index.md) | [08 - Complete Trace: var x set 10; sin(x) + sqrt(3.14) ->](./08-trace-complex.md)

# 07 - Complete Trace: `1+2*3`

## Input

```
1+2*3
```

Expected parse: `1 + (2 * 3)` - multiplication binds first.

## Full Parser Call Flow

```
ExpressionParser.parse("1+2*3")
|- [Space] -> <EMPTY>
|- TermParser.parse("1+2*3")
|  |- [Space] -> <EMPTY>
|  |- FactorParser.parse("1+2*3")
|  |  |- FunctionCallParser -> fail (number is not a function name) -> backtrack
|  |  |- UnaryExpressionParser -> fail (not '+' or '-') -> backtrack
|  |  |- NumberParser.parse("1+2*3")
|  |  |  |- [Optional Sign] -> <EMPTY>
|  |  |  |- Choice(digits-point-digits | digits-point | digits | point-digits)
|  |  |  |  |- digits-point-digits: "1" -> next '+' is not '.' -> fail
|  |  |  |  |- digits-point: "1" -> next '+' is not '.' -> fail
|  |  |  |  `- digits: "1" ok
|  |  |  |- [Optional Exponent] -> <EMPTY>
|  |  |  `- result: "1" ok
|  |  `- result: NumberParser matched "1" ok
|  |- [Space] -> <EMPTY>
|  |- ZeroOrMore(MulOp Factor)
|  |  |- loop1: "+" -> MulOp(*|/) -> fail -> stop loop
|  |  `- result: 0 matches (stopped)
|  |- [Space] -> <EMPTY>
|  `- result: TermParser matched "1" ok
|- [Space] -> <EMPTY>
|- ZeroOrMore(AddOp Term)
|  |- loop1:
|  |  |- [Space] -> <EMPTY>
|  |  |- AddOpParser: "+" ok
|  |  |- [Space] -> <EMPTY>
|  |  |- TermParser.parse("2*3")
|  |  |  |- [Space] -> <EMPTY>
|  |  |  |- FactorParser.parse("2*3")
|  |  |  |  |- FunctionCallParser -> fail -> backtrack
|  |  |  |  |- UnaryExpressionParser -> fail -> backtrack
|  |  |  |  |- NumberParser: "2" ok
|  |  |  |  `- result: NumberParser matched "2" ok
|  |  |  |- [Space] -> <EMPTY>
|  |  |  |- ZeroOrMore(MulOp Factor)
|  |  |  |  |- loop1:
|  |  |  |  |  |- [Space] -> <EMPTY>
|  |  |  |  |  |- MulOpParser: "*" ok
|  |  |  |  |  |- [Space] -> <EMPTY>
|  |  |  |  |  |- FactorParser.parse("3")
|  |  |  |  |  |  |- FunctionCallParser -> fail -> backtrack
|  |  |  |  |  |  |- UnaryExpressionParser -> fail -> backtrack
|  |  |  |  |  |  |- NumberParser: "3" ok
|  |  |  |  |  |  `- result: NumberParser matched "3" ok
|  |  |  |  |  `- loop1 success: matched "*3"
|  |  |  |  |- loop2: end of input -> stop loop
|  |  |  |  `- result: 1 match
|  |  |  |- [Space] -> <EMPTY>
|  |  |  `- result: TermParser matched "2*3" ok
|  |  `- loop1 success: matched "+2*3"
|  |- loop2: end of input -> stop loop
|  `- result: 1 match
|- [Space] -> <EMPTY>
`- result: ExpressionParser matched full "1+2*3" ok
```

## Backtracking Details

This example includes the following backtracking points:

### 1. `FunctionCallParser` attempts in `FactorParser` (3 times)

For each number (`1`, `2`, `3`), `FactorParser` (`LazyChoice`) first tries `FunctionCallParser`.
Since number-leading input does not match function names, it fails immediately and backtracks.

### 2. `UnaryExpressionParser` attempts in `FactorParser` (3 times)

`UnaryExpressionParser` requires leading `+` or `-`.
Number-leading input fails and backtracks.

### 3. `Choice` inside `NumberParser` (3 times)

`NumberParser` tries number forms in `Choice`:
1. `digits.digits` (decimal) -> fails without `.`
2. `digits.` (trailing dot) -> fails without `.`
3. `digits` (integer) -> success

## Actual Output: Token Tree

```
'1+2*3' : ExpressionParser
 '1' : TermParser
  '1' : FactorParser
   '1' : NumberParser
    <EMPTY> : optional-signParser
    '1' : Choice
     '1' : digits
      '1' : any-digit
       '1' : DigitParser
    <EMPTY> : Optional
  <EMPTY> : ZeroOrMore
 '+2*3' : ZeroOrMore
  '+2*3' : WhiteSpaceDelimitedChain
   '+' : AddOpParser
    '+' : PlusParser
   '2*3' : TermParser
    '2' : FactorParser
     '2' : NumberParser
      <EMPTY> : optional-signParser
      '2' : Choice
       '2' : digits
        '2' : any-digit
         '2' : DigitParser
      <EMPTY> : Optional
    '*3' : ZeroOrMore
     '*3' : WhiteSpaceDelimitedChain
      '*' : MulOpParser
       '*' : MultipleParser
      '3' : FactorParser
       '3' : NumberParser
        <EMPTY> : optional-signParser
        '3' : Choice
         '3' : digits
          '3' : any-digit
           '3' : DigitParser
        <EMPTY> : Optional
```

## Actual Output: AST (Reduced)

```
'1+2*3' : ExpressionParser
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

## Reading the AST

The AST encodes operator precedence as a tree:

```
      Expression(1+2*3)
      |- Term(1)
      |  `- Factor(1) -> Number(1)
      |- Plus(+)
      `- Term(2*3)
         |- Factor(2) -> Number(2)
         |- Multiple(*)
         `- Factor(3) -> Number(3)
```

From this structure:

- `2*3` is grouped as one `Term` -> multiplication executes first
- `1` and `2*3` are joined by `+` -> addition executes later
- So `1 + (2 * 3) = 7` is parsed correctly

---

[<- 06 - How Each Combinator Works](./06-combinators.md) | [Table of Contents](./index.md) | [08 - Complete Trace: var x set 10; sin(x) + sqrt(3.14) ->](./08-trace-complex.md)
