[<- 04 - Core Data Model](./04-core-datamodel.md) | [Table of Contents](./index.md) | [06 - How Each Combinator Works ->](./06-combinators.md)

# 05 - Transaction Stack and Backtracking

## What is Backtracking?

Backtracking means that a parser advances through input, then returns to a previous position when it decides that interpretation was wrong.

For example, when parsing `sin(x)`:

1. `FunctionCallParser` first tries `TwoArgFunctionParser`
2. It reaches `sin(x` but cannot find comma `,` -> fail
3. Cursor rolls back to before `sin` (backtrack)
4. Then it tries `SingleArgFunctionParser` -> success

## Transaction Mechanism in unlaxer

In unlaxer, backtracking is implemented with a **transaction stack**.
This is similar to database transactions:

| Operation | Database | unlaxer |
|------|-------------|---------|
| Start | `BEGIN` | start transaction (save cursor position) |
| Confirm | `COMMIT` | match succeeded; keep advanced cursor |
| Cancel | `ROLLBACK` | match failed; restore cursor |

### Transaction Flow

```
Input: parse "sin(x)" with FunctionCallParser

FunctionCallParser (LazyChoice) starts
|
|- Try TwoArgFunctionParser
|  BEGIN transaction (save position 0)
|  |- TwoArgFuncName: "sin" ok (pos 3)
|  |- '(': "(" ok (pos 4)
|  |- Expression: "x" ok (pos 5)
|  |- ',': missing -> fail
|  ROLLBACK (back to pos 0)
|
|- Try SingleArgFunctionParser
|  BEGIN transaction (save position 0)
|  |- SingleArgFuncName: "sin" ok (pos 3)
|  |- '(': "(" ok (pos 4)
|  |- Expression: "x" ok (pos 5)
|  |- ')': ")" ok (pos 6)
|  COMMIT (keep pos 6)
|
`- Result: SingleArgFunctionParser succeeded
```

## Backtracking in `Choice`

`Choice` tries candidates top-down.
For each candidate, it starts a transaction and rolls back on failure.

```
Choice(A, B, C):

1. BEGIN
   try A -> fail -> ROLLBACK
2. BEGIN
   try B -> success -> COMMIT -> return B
3. C is not tried
```

### Important Properties

- `Choice` returns the **first successful** candidate (not longest match)
- Candidate order matters; put longer candidates first when prefixes overlap
- Example: put `"sqrt"` before `"sin"`

## Backtracking in `Chain`

`Chain` requires all children to succeed in sequence.
If any child fails, the whole chain fails.

```
Chain(A, B, C):

BEGIN
|- try A -> success
|- try B -> fail
ROLLBACK (including what A consumed)
```

## Backtracking in `ZeroOrMore`

`ZeroOrMore` repeats a child parser until failure.

```
ZeroOrMore(A):

loop:
  BEGIN
  try A -> success -> COMMIT -> continue
  ...
  BEGIN
  try A -> fail -> ROLLBACK -> stop loop

Result: success with all matched repetitions (including zero)
```

## Nested Transactions

Transactions are nested as a stack.
Inner parsers start/finish transactions inside outer parser transactions.

```
Parse "1+2" with ExpressionParser:

[depth0] ExpressionParser BEGIN
  [depth1] TermParser BEGIN
    [depth2] FactorParser BEGIN
      [depth3] NumberParser BEGIN
        match "1" -> COMMIT [depth3]
      COMMIT [depth2]
    [depth2] ZeroOrMore BEGIN
      no mul/div -> ROLLBACK -> stopped [depth2]
    COMMIT [depth1]
  [depth1] ZeroOrMore BEGIN
    [depth2] Chain BEGIN
      match "+"
      [depth3] TermParser BEGIN
        match "2" -> ... -> COMMIT [depth3]
      COMMIT [depth2]
    next loop fails -> ROLLBACK [depth2]
    COMMIT [depth1]
  COMMIT [depth0]
```

## Performance Impact

Backtracking is powerful, but worst-case time can become exponential.
unlaxer mitigates this with:

1. **Choice ordering optimization** - put likely candidates first
2. **Lazy evaluation with `LazyChoice`** - instantiate parsers only when needed
3. **Efficient transaction implementation** - save/restore cursor only; no input copy

### TinyCalc Considerations

`FactorParser` candidate order is intentionally chosen:

```java
Parser.get(FunctionCallParser.class),
Parser.get(UnaryExpressionParser.class),
Parser.get(NumberParser.class),
Parser.get(IdentifierParser.class),
Parser.get(ParenExpressionParser.class)
```

With this order, `sin(x)` is recognized as a function call first,
not as an identifier `sin`.

---

[<- 04 - Core Data Model](./04-core-datamodel.md) | [Table of Contents](./index.md) | [06 - How Each Combinator Works ->](./06-combinators.md)
