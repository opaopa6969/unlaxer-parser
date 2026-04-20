# Changelog

All notable changes to unlaxer-parser are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions are published to Maven Central (`org.unlaxer:unlaxer-common`, `org.unlaxer:unlaxer-dsl`).

---

## [3.0.2] - 2026-04-20

### Added

- **Left-recursion detection** (#25, #26): `GrammarValidator.validateWithWarnings(GrammarDecl)` new method that detects direct and indirect left-recursive cycles in a grammar and returns `Optional<List<String>>` warnings. **Warning only — never throws.** Existing grammars continue to parse without modification.
- **`@Generated` annotation** (#24): All codegen output files now carry `@javax.annotation.processing.Generated("<GeneratorClass>")`. Enables IDEs and tools (e.g. Checkstyle, SonarQube) to skip generated code. No breaking API change.

### Notes

- No API changes from 3.0.1. Safe to upgrade without code changes.
- CI workflow already present at `.github/workflows/maven.yml`; no new workflow needed.

---

## [3.0.1] - 2026-04-19

### Fixed

- **Simple wrapper generation**: `ParserGenerator` now skips generating a Simple wrapper class when the wrapper name conflicts with an existing rule name (issue #22). This was causing a compile error in grammars where a rule and its derived wrapper shared the same identifier.

### Notes

- No API changes from 3.0.0. Safe to upgrade from 3.0.0 without code changes.
- Downstream projects still on **2.x** should review the [2.8.0 → 3.0.0 breaking changes](#3-0-0---2026-04-xx) before upgrading.

---

## [3.0.0] - 2026-04-18

### Added

- **Bootstrap / self-hosting**: `unlaxer-dsl/grammar/ubnf.ubnf` (the UBNF grammar written in UBNF) now drives generation of `UBNFParsers.java`, `UBNFAST.java`, and `UBNFMapper.java` inside `org.unlaxer.dsl.bootstrap.generated`. The hand-written bootstrap files are retained as a verification target but are no longer the authoritative source.
- **`@enum` annotation**: Generate Java `enum` types from grammar rule alternatives. Replaces hand-written switch boilerplate in evaluators.
- **`@commonField` annotation**: Lift shared fields from multiple `@mapping` variants into a common sealed-interface method.
- **`AtomicElement` re-architecture**: `UBNFAST` body types now use a sealed `AtomicElement` hierarchy; `QuantifiedRef` covers postfix quantifiers (`?`, `*`, `+`) uniformly.
- **Token type inference**: `NumberParser` tokens are now inferred as `int` in `ASTGenerator` and `MapperTypeResolver`, eliminating manual casts.
- **Plan S — Simple token wrappers**: For every token rule without a `@mapping`, a lightweight value-record wrapper (e.g., `NumberToken(int value)`) is generated, making AST nodes fully typed without boilerplate.
- **Grammar `@import`**: Import rules from another `.ubnf` file with `@import alias from 'path'`. Enables grammar composition and shared rule libraries (issue #9).
- **Incremental parsing**: LSP `didChange` events are now connected to an incremental parse layer, reducing re-parse cost on keystroke (issue #10).

### Changed

- **`CodegenMain` replaces `UbnfCodeGenerator`** as the CLI entry point. Update your Maven `exec-maven-plugin` configuration:
  - Old: `<mainClass>org.unlaxer.dsl.UbnfCodeGenerator</mainClass>`
  - New: `<mainClass>org.unlaxer.dsl.CodegenMain</mainClass>`
- **`UBNFAST.TokenDecl`** is now a sealed interface (`Simple` / `Until` / `Negation` / `Lookahead` / `NegativeLookahead`). Code that `instanceof`-checks `TokenDecl` against the old class must be updated.
- **`@mapping` params order** is now strictly positional and validated at codegen time. Grammars with out-of-order `params=` lists will produce a build warning and may generate incorrect mappers.
- `groupId` and `developers` are now explicit in child POM files (Maven Central coordinate resolution fix).

### Removed

- `UbnfCodeGenerator` (old CLI class) — removed. Use `CodegenMain`.

### Downstream Drift Warning

> The following projects were validated against **2.x** and have not been tested against 3.0.0 / 3.0.1. Breaking changes in `CodegenMain`, `UBNFAST`, and `@mapping` params ordering may affect them.
>
> - [tinyexpression](https://github.com/opaopa6969/tinyexpression) — last validated 2.8.0
> - [onigiri-parser](https://github.com/opaopa6969/onigiri-parser) — last validated 2.6.0
> - [fraud-alert](https://github.com/opaopa6969/fraud-alert) — last validated 2.8.0

---

## [2.8.0] - 2026-04-10

### Added

- `@scopeTree` / `@declares` / `@backref` annotations: semantic scope modeling for symbol definition, resolution, and backreference constraints in generated LSP diagnostics.
- UBNF extension Tier 1–4 completions: `UNTIL`, `+` quantifier, `NEGATION`, `LOOKAHEAD`, `NEGATIVE_LOOKAHEAD` token forms (see `UBNF-EXTENSION-ROADMAP.md`).
- `IntegerValue.lessEquals` fix + `CodePointIndex.of(int)` / `CodePointIndex.ZERO` (issue #15, #16).

### Changed

- `WildCardStringTerminatorParser` now takes a constructor argument for the terminator string (breaking change for direct users of this elementary parser).

---

## [2.6.0] - 2026-03-15

### Added

- Self-hosting milestone: `ubnf.ubnf` can be processed by unlaxer-dsl to generate all six artifact types (Parsers, AST, Mapper, Evaluator, LSP, DAP). See `docs/ubnf-self-hosting.md`.
- Railroad diagram export (`RailroadMain`, `UBNFToRailroad`).
- BNF converter (`UBNFToBNFConverter`) for documentation tooling.

### Fixed

- `IntegerValue.lessEquals` off-by-one.

---

## [2.0.0] - 2026-01-20

### Added

- Parser IR (`ParserIrDocument`, `ParserIrAdapter` SPI) — a parser-agnostic intermediate representation allowing non-UBNF parsers to plug into the codegen pipeline.
- `GrammarToParserIrExporter` — exports UBNF grammar rules as ParserIR nodes with annotations.
- `ParserIrConformanceValidator` — validates IR documents against the v1 schema.
- `GrammarValidator` — static analysis of token declarations and rule references, emitting structured warnings (e.g., `W-TOKEN-UNRESOLVED`).

### Changed

- Minimum Java version raised from 17 to **21**.
- `unlaxer-dsl` package root reorganized: `codegen/`, `bootstrap/`, `ir/`, `runtime/` subpackages.

---

## [1.0.0] - 2025-09-01

### Added

- Initial public release of `unlaxer-common` and `unlaxer-dsl`.
- Core parser combinators: `Chain`, `Choice`, `ZeroOrMore`, `OneOrMore`, `Optional`, `NonOrdered`, `Not`, `Flatten`.
- Elementary parsers: `SingleCharacterParser`, `WordParser`, `NumberParser`, `QuotedParser`, `EndOfSourceParser`, `WildCardStringParser`, and more.
- UBNF grammar format with `@root`, `@mapping`, `@leftAssoc`, `@rightAssoc`, `@whitespace`.
- Code generators: `ParserGenerator`, `ASTGenerator`, `MapperGenerator`, `EvaluatorGenerator`, `LSPGenerator`, `DAPGenerator`.
- Published to Maven Central.
