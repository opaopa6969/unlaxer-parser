[<- 01 - Introduction](./01-introduction.md) | [Table of Contents](./index.md) | [03 - Building Parsers from BNF ->](./03-building-parsers.md)

# 02 - TinyCalc BNF Definition

## Full BNF

```
TinyCalc              ::= VariableDeclarations Expression

VariableDeclarations  ::= { VariableDeclaration }
VariableDeclaration   ::= ('var' | 'variable') Identifier ['set' Expression] ';'

Expression            ::= Term { ('+' | '-') Term }
Term                  ::= Factor { ('*' | '/') Factor }
Factor                ::= FunctionCall
                        | UnaryExpression
                        | Number
                        | Identifier
                        | '(' Expression ')'

UnaryExpression       ::= ('+' | '-') Factor

FunctionCall          ::= SingleArgFunction
                        | TwoArgFunction
                        | NoArgFunction
SingleArgFunction     ::= SingleArgFuncName '(' Expression ')'
TwoArgFunction        ::= TwoArgFuncName '(' Expression ',' Expression ')'
NoArgFunction         ::= 'random' '(' ')'

SingleArgFuncName     ::= 'sin' | 'cos' | 'tan' | 'sqrt' | 'abs' | 'log'
TwoArgFuncName        ::= 'min' | 'max' | 'pow'

Identifier            ::= AlphabetUnderScore { AlphabetNumericUnderScore }
Number                ::= [Sign] ( Digits '.' Digits
                                 | Digits '.'
                                 | Digits
                                 | '.' Digits ) [Exponent]

Digits                ::= Digit { Digit }
Digit                 ::= '0' | '1' | ... | '9'
Sign                  ::= '+' | '-'
Exponent              ::= ('e' | 'E') [Sign] Digits
AlphabetUnderScore    ::= 'a'...'z' | 'A'...'Z' | '_'
AlphabetNumericUnderScore ::= AlphabetUnderScore | Digit
```

## Notation

| Notation | Meaning |
|------|------|
| `A B` | Concatenation (`Chain`) - B follows A |
| `A \| B` | Choice (`Choice`) - A or B |
| `{ A }` | Repetition (`ZeroOrMore`) - zero or more times |
| `[ A ]` | Optional (`Optional`) |
| `'...'` | Literal string (keyword or symbol) |

## Grammar Hierarchy

The TinyCalc grammar structurally represents **operator precedence**:

```
TinyCalc
|- VariableDeclarations (zero or more variable declarations)
|  `- VariableDeclaration
|     |- keyword: var | variable
|     |- identifier
|     |- [initializer: set Expression]
|     `- semicolon
`- Expression (lowest precedence: + -)
   `- Term (next precedence: * /)
      `- Factor (highest precedence)
         |- FunctionCall
         |- UnaryExpression
         |- Number
         |- Identifier
         `- '(' Expression ')'
```

### Operator Precedence

| Precedence | Operators | Rule in BNF |
|----------|--------|-------------|
| High | function call, parentheses, unary | Factor |
| Middle | `*`, `/` | Term |
| Low | `+`, `-` | Expression |

Because of this design, `1 + 2 * 3` is automatically parsed as `1 + (2 * 3)`.

## Cyclic References

The BNF contains the following cyclic (recursive) references:

```
Expression -> Term -> Factor -> '(' Expression ')'
                           -> FunctionCall -> SingleArgFunction -> Expression
                           -> FunctionCall -> TwoArgFunction   -> Expression
```

Variable declarations are also recursive:

```
VariableDeclaration -> 'set' Expression -> Term -> Factor -> ...
```

In unlaxer, these cyclic references are handled with `LazyChain` and `LazyChoice`.
See [09 - Lazy and Recursion](09-lazy-and-recursion.md) for details.

---

[<- 01 - Introduction](./01-introduction.md) | [Table of Contents](./index.md) | [03 - Building Parsers from BNF ->](./03-building-parsers.md)
