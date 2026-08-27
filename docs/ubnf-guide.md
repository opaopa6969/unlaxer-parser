[English](./ubnf-guide.md) | [日本語](./ubnf-guide-ja.md)

---

# UBNF Language Guide

**Version**: 3.0.14

UBNF (Unlaxer BNF) is the grammar definition language for unlaxer-parser. It is a typed, annotation-driven extension of EBNF designed to express not just recognition semantics but also code generation intent.

---

## Table of Contents

- [Grammar File Structure](#grammar-file-structure)
- [Token Declarations](#token-declarations)
- [Rule Declarations](#rule-declarations)
- [Syntax Reference](#syntax-reference)
- [Annotations](#annotations)
- [Operator Associativity](#operator-associativity)
- [Whitespace and Comment Handling](#whitespace-and-comment-handling)
- [Grammar Import](#grammar-import)
- [Feature Matrix](#feature-matrix)
- [Complete Example: TinyCalc](#complete-example-tinycalc)

---

## Grammar File Structure

A `.ubnf` file contains one or more `grammar` blocks:

```ubnf
grammar GrammarName {
  // Global settings
  @package: com.example.pkg
  @whitespace: javaStyle
  @comment: { line: '//' }

  // Token declarations
  token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
  token NUMBER     = org.unlaxer.parser.elementary.NumberParser

  // Rule declarations
  @root
  Start ::= ... ;

  Rule ::= ... ;
}
```

A grammar block can `@import` rules from another grammar:

```ubnf
@import base from 'path/to/base.ubnf'
```

---

## Token Declarations

Tokens are the leaf parsers that recognize character sequences. They are declared with the `token` keyword and must reference a Java class that implements `Parser`.

### Simple token (class reference)

```ubnf
token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
token NUMBER     = org.unlaxer.parser.elementary.NumberParser
```

The short form (unqualified class name) is allowed but produces a `W-TOKEN-UNRESOLVED` warning. Use fully qualified names to suppress warnings. When the short name matches a class in a bundled parser package (`org.unlaxer.parser.elementary`, `posix`, `clang`, `combinator`, `ascii`), the warning's hint lists the fully qualified candidates, e.g. `Did you mean 'org.unlaxer.parser.elementary.NumberParser'?`.

### UNTIL token

Matches any character sequence up to (but not including) a terminator string:

```ubnf
token CODE_BODY = UNTIL('```')
token LINE      = UNTIL('\n')
```

### NEGATION token

Matches any character that is *not* in the referenced parser's match set:

```ubnf
token NON_QUOTE = NEGATION(SingleQuotedParser)
```

### LOOKAHEAD token

Succeeds if the next input matches the referenced parser, but consumes nothing:

```ubnf
token BEFORE_SEMICOLON = LOOKAHEAD(SemicolonParser)
```

### NEGATIVE_LOOKAHEAD token

Succeeds if the next input does *not* match the referenced parser, and consumes nothing:

```ubnf
token NOT_KEYWORD = NEGATIVE_LOOKAHEAD(KeywordParser)
```

---

## Rule Declarations

Rules define the recursive grammar. Each rule ends with `;`.

```ubnf
RuleName ::= body ;
```

---

## Syntax Reference

### Sequence (implicit)

Elements written adjacently form a sequence:

```ubnf
Assignment ::= IDENTIFIER '=' Expression ;
```

### Alternation `|`

```ubnf
Literal ::= NUMBER | STRING | 'true' | 'false' ;
```

### Grouping `(...)`

```ubnf
Factor ::= '(' Expression ')' | NUMBER ;
```

### Zero or more `{...}` or `*`

```ubnf
Statements ::= { Statement } ;     // UBNF brace syntax
Words       ::= Word* ;            // postfix syntax
```

### One or more `+`

```ubnf
Digits ::= DIGIT+ ;
```

### Zero or one `[...]` or `?`

```ubnf
OptSign ::= [SignParser] ;         // bracket syntax
OptSign ::= SignParser? ;          // postfix syntax
```

### String literal `'...'`

```ubnf
Assign ::= IDENTIFIER '=' Expression ;
Plus   ::= '+' ;
```

Escape sequences inside literals: `\n`, `\t`, `\r`, `\\`, `\'`.

### Capture `@name`

Marks a parse-tree element for extraction by `@mapping`:

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
BinaryExpr ::= Expression @left Operator @op Expression @right ;
```

### Comments

Line comments with `//`:

```ubnf
// This is a comment
Rule ::= A B ; // inline comment
```

---

## Annotations

Annotations appear directly before a rule declaration and control code generation behavior.

### `@root`

Marks the grammar entry point. Exactly one rule must be annotated `@root`.

```ubnf
@root
Program ::= { Statement } EOF ;
```

### `@mapping(TypeName, params=[field1, field2, ...])`

Generates a Java `record TypeName(FieldType field1, FieldType field2, ...)` and a corresponding `evalXxx` method in the evaluator skeleton. The `params` list must match capture names (`@name`) in the rule body.

```ubnf
@mapping(FunctionCall, params=[name, args])
FunctionCall ::= IDENTIFIER @name '(' ArgList @args ')' ;
```

Field types are inferred:
- `NumberParser` capture → `int`
- Rule reference capture → the mapped record type of that rule
- Token capture without type → `String` (the matched text)

### `@leftAssoc` / `@rightAssoc`

Controls operator associativity for binary expression rules. Without this annotation, repeated operator sequences are right-associative by default.

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
Addition ::= Term @left { AddOp @op Term @right } ;

@mapping(Power, params=[base, exp])
@rightAssoc
Exponent ::= Factor @base '^' Factor @exp ;
```

### `@whitespace: style`

Inserts implicit whitespace skipping between rule elements. Supported styles:

| Style | Behavior |
|-------|----------|
| `javaStyle` | Skips spaces, tabs, and newlines |
| `none` | No implicit whitespace skipping |

```ubnf
@whitespace: javaStyle
```

### `@comment: { line: '//' }`

Inserts implicit comment skipping. The block form accepts a `line` key:

```ubnf
@comment: { line: '//' }
```

### `@enum`

Generates a Java `enum` from the alternatives of a rule. All alternatives must be string literals.

```ubnf
@enum
BoolLiteral ::= 'true' | 'false' ;
```

This produces:

```java
public enum BoolLiteral { TRUE, FALSE }
```

### `@commonField`

Lifts a field that appears in all `@mapping` variants of a sealed interface into a common interface method:

```ubnf
@commonField(name)
@mapping(FunctionCall, params=[name, args])
FunctionCall ::= IDENTIFIER @name '(' ArgList @args ')' ;

@commonField(name)
@mapping(VariableRef, params=[name])
VariableRef ::= IDENTIFIER @name ;
```

The generated sealed interface will have `String name();` as a default interface method.

### `@scopeTree(mode=lexical|dynamic)`

Marks a rule as a scope boundary. The code generator emits `enterScope` and `leaveScope` events in the ParserIR output.

```ubnf
@scopeTree(mode=lexical)
Block ::= '{' { Statement } '}' ;
```

### `@declares(symbol=capture)`

Marks a capture as a symbol definition within the current scope (used with `@scopeTree`). The `symbol=` argument names the capture that holds the declared name, and an optional `description=` string may follow:

```ubnf
@declares(symbol=name)
VarDecl ::= 'var' IDENTIFIER @name '=' Expression ;
```

### `@backref(name=capture)`

Marks a capture as a symbol use that must have been defined earlier in scope. The `name=` argument names the capture that holds the referenced name:

```ubnf
@backref(name=name)
VarRef ::= IDENTIFIER @name ;
```

### `@eval(key: 'value', ...)`

Attaches key/value evaluation metadata to a rule; consumed by the evaluator generator. Each entry is `identifier: 'string'`:

```ubnf
@eval(kind: 'binary')
Expression ::= Term @left { AddOp @op Term @right } ;
```

### `@precedence(level=N)`

Assigns an integer precedence level to a rule (used together with the associativity annotations for operator-precedence handling). `N` is an unsigned integer:

```ubnf
@precedence(level=2)
@leftAssoc
Term ::= Factor @left { MulOp @op Factor @right } ;
```

### `@interleave(profile=name)`

Marks a rule for interleaved parsing under the named profile (e.g. whitespace/comment interleaving policy). The `profile=` argument is an identifier:

```ubnf
@interleave(profile=default)
Document ::= { Element } ;
```

### `@doc('...')`

Attaches a documentation string to a rule; the generator carries it through to generated artifacts (e.g. hover text). Takes a single string literal:

```ubnf
@doc('A top-level statement.')
Statement ::= Assignment | Expression ;
```

### `@recovery(sync='...' | mode)`

Marks a rule for error recovery. Either give a sync-token string via `sync='...'`, or a bare recovery-mode identifier. The parser generator emits a recovery wrapper for the rule:

```ubnf
@recovery(sync=';')
Statement ::= Assignment ';' ;
```

### `@catalog(context='...')`

Tags a rule with a catalog context string, consumed by the LSP generator to drive context-aware completion. Takes a single string literal:

```ubnf
@catalog(context='functions')
FunctionName ::= IDENTIFIER @name ;
```

### `@skip`

Marks a rule so that **no** AST record is generated for it and the mapper skips it (the rule participates in parsing but is elided from the AST). Takes no arguments:

```ubnf
@skip
Separator ::= ',' ;
```

### `@typeof(capture)` (element-level)

An element-level annotation written **inside** a rule body (not on the rule). It correlates the annotated element's type with the named capture:

```ubnf
Assignment ::= IDENTIFIER @name @typeof(value) '=' Expression @value ;
```

---

## Operator Associativity

For left-associative operators (most arithmetic), use `@leftAssoc` with the following pattern:

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
Expression ::= Term @left { Operator @op Term @right } ;
```

The `{ Operator @op Term @right }` repetition combined with `@leftAssoc` causes the generator to build a left-folding loop rather than a right-recursive structure.

For right-associative operators (exponentiation, assignment):

```ubnf
@mapping(Power, params=[base, exp])
@rightAssoc
Power ::= Factor @base '^' Power @exp ;
```

---

## Whitespace and Comment Handling

When `@whitespace: javaStyle` is set globally, whitespace is skipped between every sequence element. You can override per-rule:

```ubnf
grammar MyGrammar {
  @whitespace: javaStyle

  // This rule gets javaStyle whitespace skipping
  Statement ::= KEYWORD Expression ';' ;

  // Override: no whitespace skipping inside a raw string
  // (not yet supported per-rule in 3.0.1 — global setting only)
}
```

---

## Grammar Import

Import rules from another grammar file using `@import`:

```ubnf
grammar ExtendedCalc {
  @import base from 'common/expressions.ubnf'

  @root
  Program ::= base.Expression EOF ;
}
```

The imported rules are available under the alias namespace.

---

## Feature Matrix

| Feature | Syntax | Status |
|---------|--------|--------|
| Sequence | implicit adjacency | Stable |
| Alternation | `\|` | Stable |
| Group | `(...)` | Stable |
| Zero or more | `{...}` / `*` | Stable |
| One or more | `+` | Stable (v2.8+) |
| Zero or one | `[...]` / `?` | Stable |
| String literal | `'...'` | Stable |
| Capture | `@name` | Stable |
| `@root` | annotation | Stable |
| `@mapping` | annotation | Stable |
| `@leftAssoc` / `@rightAssoc` | annotation | Stable |
| `@whitespace` | global setting | Stable |
| `@comment` | global setting | Stable |
| `@enum` | annotation | Stable (v3.0+) |
| `@commonField` | annotation | Stable (v3.0+) |
| `@scopeTree` | annotation | Stable (v2.8+) |
| `@declares(symbol=...)` | annotation | Stable (v2.8+) |
| `@backref(name=...)` | annotation | Stable (v2.8+) |
| `@eval` | annotation | Recognized (generator support) |
| `@precedence(level=...)` | annotation | Recognized (generator support) |
| `@interleave(profile=...)` | annotation | Recognized (generator support) |
| `@doc('...')` | annotation | Recognized (generator support) |
| `@recovery(...)` | annotation | Recognized (generator support) |
| `@catalog(context=...)` | annotation | Recognized (generator support) |
| `@skip` | annotation | Recognized (generator support) |
| `@typeof(...)` | element-level annotation | Recognized (generator support) |
| `UNTIL(...)` token | token form | Stable (v2.8+) |
| `NEGATION(...)` token | token form | Stable (v2.8+) |
| `LOOKAHEAD(...)` token | token form | Stable (v2.8+) |
| `NEGATIVE_LOOKAHEAD(...)` token | token form | Stable (v2.8+) |
| `@import` | grammar directive | Stable (v3.0+) |

---

## Complete Example: TinyCalc

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc
  @whitespace: javaStyle
  @comment: { line: '//' }

  token NUMBER     = org.unlaxer.parser.elementary.NumberParser
  token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
  token EOF        = org.unlaxer.parser.elementary.EndOfSourceParser

  @root
  @mapping(Program, params=[expr])
  Program ::= Expression @expr EOF ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  Factor ::=
      NUMBER
    | IDENTIFIER
    | '(' Expression ')'
    | '-' Factor ;

  @enum
  AddOp ::= '+' | '-' ;

  @enum
  MulOp ::= '*' | '/' ;
}
```
