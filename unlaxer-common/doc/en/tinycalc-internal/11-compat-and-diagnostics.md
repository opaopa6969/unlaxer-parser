---

[<- 10 - Debug and Listener System](./10-debug-system.md) | [Table of Contents](./index.md)

# 11 - 2.4.0 Compatibility Layer and Diagnostic Extension

## Overview

In 2.4.0, we formalize a compatibility layer and diagnostic direction so existing projects
(including TinyExpression) can migrate incrementally.

## Compatibility APIs (for legacy code)

`org.unlaxer.Token`:

- compatibility fields: `tokenString`, `tokenRange` (`@Deprecated`)
- compatibility methods: `getToken()`, `getTokenRange()`, `getRangedString()`
- compatibility constructors:
  - `Token(TokenKind, List<? extends Token>, Parser)`
  - `Token(TokenKind, List<? extends Token>, Parser, int)`
  - `Token(TokenKind, Source, Parser, int)`
- compatibility method: `newCreatesOf(List<? extends Token>)`

`org.unlaxer.TypedToken`:

- compatibility method: `newCreatesOfTyped(List<? extends Token>)`

`org.unlaxer.StringSource`:

- compatibility constructor: `StringSource(String)` (treated as root source)

`org.unlaxer.listener.TransactionListener`:

- `TokenList` is the primary signature; legacy `List<Token>` is kept via default methods

`org.unlaxer.RangedString`:

- restored as a thin compatibility wrapper (based on `StringSource`) for legacy references

## Positioning of the compatibility layer

- New implementations should use `Source`, `CursorRange`, and `TokenList` directly.
- Compatibility APIs are intended only for migration periods.
- After migration, deprecated APIs are candidates for gradual removal.

## Diagnostic extension: farthest failure + stack context

To fix frequent `Ln1,col1` error reporting, keep failure context in addition to raw position.

Proposed diagnostic payload:

- `farthestOffset`
- `maxReachedStackElements` (parser stack snapshot at the deepest point)
- `expected` (expected tokens/rules)
- `contextWindow` (surrounding source text)

Update rules:

1. replace when a deeper `offset` is observed
2. for same `offset`, prefer the deeper stack
3. for ties, keep top N candidates

Implementation note (local 2.4.0 work):

- added `TerminalSymbol#expectedDisplayText()` so terminal parsers can provide expected text
- added `TerminalSymbol#expectedDisplayTexts()` so terminal parsers can provide multiple expected candidates
- `SingleCharacterParser` and `WordParser` provide default hint implementations
- `SuggestableParser` now returns all candidates as expected hints (for example `if`, `match`, `sin/cos/tan`)
- `SingleStringParser` auto-detects a single-character token and emits expected hints (for example `<`, `>`, `:`, `?`, `$`)
- added `ParseFailureDiagnostics.ExpectedHintCandidate`, so diagnostics can expose
  `displayHint` plus parser metadata (`parserClassName`, `parserQualifiedClassName`, `parserDepth`, `terminal`)
- on `Choice` failures, child expected hints are aggregated (for example `'sin', 'cos', 'tan'`)
- aggregation traversal uses iterative BFS + depth cap + visited to avoid `StackOverflowError`

## Impact on LSP/DAP

- LSP: diagnostics point closer to the actual syntax fault
- DAP: exposes not only current cursor but also expectation/stack evidence

## Next steps

1. add a formal deepest-failure API to `unlaxer-common`
2. replace TinyExpression/LSP heuristic location inference incrementally
3. inventory compatibility API usages and migrate to native APIs step by step
4. add first-class metadata on base combinators (`@FirstClass` style) with both static annotations and dynamic providers
