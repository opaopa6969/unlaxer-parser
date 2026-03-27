## Addendum: Macro PEG Reference (to be merged into v2)

Add to Section 3.8 (Beyond Context-Free: MatchedTokenParser) or Related Work:

The MatchedTokenParser design was inspired by Macro PEG [Mizushima 2016], which extends PEG with parameterized rules to handle context-sensitive patterns such as palindromes. While Macro PEG achieves this through grammar-level extensions (rules that accept parameters), unlaxer takes an object-oriented approach: MatchedTokenParser captures matched content at the combinator level and provides composable operations (slice, effect, pythonian) for token manipulation. Both approaches extend PEG's recognition power beyond context-free languages, but unlaxer's design integrates naturally with Java's type system and IDE tooling.

### Reference to add:
- Mizushima, K. (2016). Macro PEG: PEG with macro-like rules. Blog post and implementation. https://github.com/kmizu/macro_peg
- Mizushima, K., Maeda, A., & Yamaguchi, Y. (2010). Packrat parsers can handle practical grammars in mostly constant space. PASTE '10.

### Comparison table to add to Related Work:

| System | Approach | Beyond CFG | IDE Support |
|--------|----------|-----------|-------------|
| PEG (Ford 2004) | Grammar notation | No | No |
| Macro PEG (Mizushima) | Parameterized rules | Yes (grammar-level) | No |
| unlaxer MatchedTokenParser | Combinator-level capture | Yes (object-level) | Yes (LSP/DAP) |
