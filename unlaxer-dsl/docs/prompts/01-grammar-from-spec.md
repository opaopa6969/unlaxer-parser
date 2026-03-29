# Prompt Template: Create UBNF Grammar from Language Specification

## System Context

You are writing a UBNF grammar for unlaxer-parser. UBNF is based on EBNF with PEG-style ordered choice and annotations for AST generation.

Reference: `unlaxer-dsl/docs/llm-reference.md`

## Prompt

Given this language specification:

{{SPEC}}

Write a complete UBNF grammar file (`.ubnf`) for unlaxer-parser. Follow these rules strictly:

### Structure Rules

1. Start with `grammar LanguageName {`
2. Declare `@package:`, `@whitespace: javaStyle`, and optionally `@comment: { line: '//' }`
3. Declare all tokens using built-in parsers:
   - `NumberParser` for numeric literals
   - `IdentifierParser` for identifiers
   - `SingleQuotedParser` for single-quoted strings
   - `EndOfSourceParser` for end of input
4. Mark exactly one rule with `@root`
5. End every rule with `;`
6. Close with `}`

### AST Mapping Rules

1. Use `@mapping(NodeName, params=[field1, field2, ...])` on rules that produce AST nodes
2. Use `@fieldName` captures in the rule body to bind elements to AST fields
3. Every field listed in `params` must have a corresponding `@fieldName` in the rule body
4. Rules without `@mapping` are structural only (choices, grouping) and do not produce AST nodes

### Operator Precedence Rules

1. Express precedence through rule nesting: `Expression` -> `Term` -> `Factor`
2. Lower rules in the chain have higher precedence
3. Use `@leftAssoc` for left-associative binary operators (arithmetic, string concat)
4. Use `@rightAssoc` only for assignment or exponentiation
5. Optionally add `@precedence(level=N)` with higher N for tighter binding

### PEG Ordered Choice Rules

1. Alternatives (`|`) are tried left to right; first match wins
2. **Put `NumberExpression` before `BooleanExpression` in top-level Expression choice** -- numeric expressions with binary operators consume more input
3. Put more specific alternatives before less specific ones
4. Literal keywords before identifier references

### Avoiding Left Recursion

1. Never write `Rule ::= Rule Op Term` (direct left recursion)
2. Use repetition instead: `Rule ::= Term { Op Term }`
3. This naturally combines with `@leftAssoc`

### @eval Hints (Optional)

1. Add `@eval(kind=binary_arithmetic)` for binary operator rules
2. Add `@eval(kind=variable_ref)` for variable reference rules
3. Add `@eval(kind=conditional)` for if/else rules
4. Add `@eval(kind=passthrough)` for wrapper/delegation rules
5. Add `@eval(kind=literal)` for literal value rules

### Output Format

```ubnf
grammar {{LANGUAGE_NAME}} {

  @package: {{PACKAGE}}
  @whitespace: javaStyle
  @comment: { line: '//' }

  // Token declarations
  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  // ... add as needed

  // Root rule
  @root
  ...

  // Expression rules (with @mapping, @leftAssoc)
  ...

  // Factor/atom rules
  ...

  // Operator choice rules
  ...
}
```

### Checklist Before Returning

- [ ] Exactly one `@root` rule
- [ ] All rules end with `;`
- [ ] All `@mapping` params have matching `@` captures in rule body
- [ ] No left recursion
- [ ] Binary operator rules have `@leftAssoc` (or `@rightAssoc`)
- [ ] Expression alternatives ordered: most-consuming first
- [ ] Token names are UPPER_CASE; rule names are PascalCase
