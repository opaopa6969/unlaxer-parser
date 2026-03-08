# `@typeof` Element Annotation

## Overview

`@typeof(captureName)` is an element-level annotation that constrains a rule body element to use the same Choice alternative as another captured element in the same rule. It is evaluated at **mapper time** (runtime), not at parse time.

## Syntax

```ubnf
@typeof(captureName)
```

- Appears as a standalone element in a rule body sequence
- Must be followed by a capture name (`@ownCaptureName`) to name the constrained element
- `captureName` must refer to an existing capture in the same rule

**Full usage in a rule**:
```ubnf
'{' @typeof(thenExpr) @elseExpr '}'
```

Here, `@typeof(thenExpr)` is the element, and `@elseExpr` is its capture name.

## Motivation

Consider `if/else` expressions where both branches must evaluate to the same expression type:

```ubnf
Expression ::= BooleanExpression | NumberExpression | StringExpression | ObjectExpression ;

IfExpression ::=
  'if' '(' BooleanExpression @condition ')'
  '{' Expression @thenExpr '}'
  'else'
  '{' Expression @elseExpr '}' ;
```

Without `@typeof`, `thenExpr` and `elseExpr` are independently typed. An evaluator could receive `thenExpr=NumberExpression` and `elseExpr=StringExpression`, causing a type mismatch.

With `@typeof`:
```ubnf
IfExpression ::=
  'if' '(' BooleanExpression @condition ')'
  '{' Expression @thenExpr '}'
  'else'
  '{' @typeof(thenExpr) @elseExpr '}' ;
```

The generated mapper enforces at runtime that `thenExpr` and `elseExpr` are the same Java type.

## What Gets Generated

For the rule above with `@mapping(IfExpr, params=[condition, thenExpr, elseExpr])`, the mapper generates:

```java
public TinyExpressionP4AST.IfExpr toTinyExpressionP4AST_IfExpr(Token token) {
    // ... condition mapping ...

    Object thenExpr = null;
    boolean assigned_thenExpr = false;
    if (!assigned_thenExpr) {
        Token paramToken_thenExpr_0 = findDescendantByIndex(token, TinyExpressionP4Parsers.ExpressionParser.class, 0);
        if (paramToken_thenExpr_0 != null) {
            thenExpr = toTinyExpressionP4AST_Expression(paramToken_thenExpr_0);
            assigned_thenExpr = true;
        }
    }

    // elseExpr: resolved via @typeof(thenExpr) → same rule as thenExpr
    Object elseExpr = null;
    boolean assigned_elseExpr = false;
    if (!assigned_elseExpr) {
        Token paramToken_elseExpr_0 = findDescendantByIndex(token, TinyExpressionP4Parsers.ExpressionParser.class, 1);
        if (paramToken_elseExpr_0 != null) {
            elseExpr = toTinyExpressionP4AST_Expression(paramToken_elseExpr_0);
            assigned_elseExpr = true;
        }
    }

    // @typeof runtime assertion
    if (thenExpr != null && elseExpr != null && !thenExpr.getClass().equals(elseExpr.getClass())) {
        throw new IllegalArgumentException(
            "@typeof constraint violated: elseExpr must be same type as thenExpr, expected "
            + thenExpr.getClass().getSimpleName() + " but got " + elseExpr.getClass().getSimpleName());
    }

    TinyExpressionP4AST.IfExpr mapped = new TinyExpressionP4AST.IfExpr(condition, thenExpr, elseExpr);
    return registerNodeSourceSpan(mapped, token);
}
```

**Key behaviors**:
1. `elseExpr` uses the same parser class as `thenExpr` (ExpressionParser, with occurrence index incremented)
2. `elseExpr` uses the same mapper function as `thenExpr` (`toXxxAST_Expression`)
3. A runtime assertion checks that both have the same Java class

## Parser Generation

`@typeof(captureName)` is **invisible** to the parser generator. The parser matches the token structure as if the `@typeof` annotation weren't there. This means the input grammar is unchanged.

## Validation

The grammar validator enforces:

| Error Code | Condition |
|-----------|-----------|
| `E-TYPEOF-UNKNOWN-CAPTURE` | `captureName` does not exist as a capture in this rule |
| `E-TYPEOF-MISSING-CAPTURE` | `@typeof(name)` appears without a paired `@ownCapture` name |

## UBNF Self-Hosting Definition

The self-hosting grammar (`grammar/ubnf.ubnf`) defines TypeofElement as:

```ubnf
@mapping(TypeofElement, params=[captureName])
TypeofElement ::= '@typeof' '(' IDENTIFIER @captureName ')' ;
```

And it is included in `AtomicElement`:
```ubnf
AtomicElement ::=
    GroupElement
  | OptionalElement
  | RepeatElement
  | TerminalElement
  | TypeofElement
  | RuleRefElement ;
```

## Limitations

- **Parser-time only**: The type constraint is checked at mapper execution (AST construction), not during parsing. An invalid input will parse successfully but fail at mapping.
- **Same-rule scope**: `captureName` must refer to a capture in the same rule (not a parent or sibling rule).
- **No deep resolution**: If `thenExpr` itself is a TypeofElement (chained @typeof), behavior is undefined.

## Related

- [SPEC.md](../SPEC.md) — Formal specification
- [UBNFToRailroad](RAILROAD-DIAGRAMS.md) — Grammar visualization
