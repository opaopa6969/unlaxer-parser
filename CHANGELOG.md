# Changelog

All notable changes to unlaxer-parser are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions are published to Maven Central (`org.unlaxer:unlaxer-common`, `org.unlaxer:unlaxer-dsl`).

---

## [Unreleased]

---

## [3.0.8] - 2026-06-27

### Fixed

- **Mapper captures used a global descendant index that miscounted across nesting (tinyexpression #32, load-bearing `if` shadow)**: scalar/optional capture resolution emitted `findDescendantByIndex(token, ParserClass, n)`, which recurses through ALL descendants. When the captured parser class also appears nested inside a *sibling* capture's subtree, the global Nth-of-class index points at the wrong node. Concretely, `IfExpression`'s `@thenExpr`/`@elseExpr` (`Expression`) were mis-resolved to an `Expression` nested inside the `@condition` — e.g. `if(min(0,0)==0){1}else{0}` mapped `thenExpr` to a `0` from the condition instead of the literal `1`, so the pure-AST evaluation took the wrong branch. Captures are direct children of the rule's Chain, so resolution is now **structural-position based**: new `findCapturedToken` prefers the rule token's direct children (`findDirectDescendants`) and only falls back to the global descendant index when no direct child of the class exists — backward-compatible where the old index was already correct. Fixes if-branch and slice-index resolution on the AST path (downstream tinyexpression: `min`/`max` and many `if(...)` cases now evaluate faithfully without the source shadow).
- **`@name`/identifier-token captures resolved to empty on the AST path (#43 family; tinyexpression #32)**: for a fully-qualified token parser, the parse tree holds the generated *wrapper* subclass (`<Grammar>Parsers.IdentifierParser`, per ParserTokenEmitter / `resolveParserClass` "Plan S"), but `MapperElementUtil.parserClassLiteral` referenced the base `org.unlaxer.parser.clang.IdentifierParser`. Since `findDescendants` matches by exact `getClass()`, the capture silently came back empty (e.g. `VariableRefExpr.name`, import `@alias`/`@method`), and only the source-snippet shadow recovered it. It now mirrors `resolveParserClass` exactly: the wrapper class for fully-qualified token parsers, the base class for short-named ones.

---

## [3.0.7] - 2026-06-26

### Fixed

- **Associative folds flattened transparent-choice operands to source text (#43 family; tinyexpression #32)**: a `@leftAssoc`/`@rightAssoc` rule whose operands reference a transparent mapped choice with no `@mapping` of its own (e.g. `StringExpression ::= StringTerm @left { '+' @op StringTerm @right }`, where `StringTerm ::= StringMatchExpression | SliceExpression | VariableRef | …`) inferred its operand fields as `String` and emitted `stripQuotes(firstTokenText(token))`, silently dropping the matched node. Such operands are now inferred as `Object` and resolved to their real AST node via the existing `mapTransparentValue(token)` helper. The same widening applies to comparison rules over transparent choices (e.g. `BooleanEqualityExpression ::= BooleanComparable @left EqualityOp @op BooleanComparable @right`). Downstream (tinyexpression #32) this makes string concatenation and boolean-equality operands evaluate faithfully on the AST instead of via a source re-parse.
  - `MapperTypeResolver.inferTypeFromElement` and `ASTGenerator.inferTypeFromElement` now yield `Object` (not `String`) for a reference to a transparent mapped choice, via the new `MapperTypeResolver.isTransparentMappedChoice` (same ≥2-distinct-mapped-alternatives criterion as `MapperElementUtil.isTransparentMappedChoice`).
  - `MapperRuleEmitter`'s assoc-fold emission now maps left/right operands through `mapExpressionForTargetType` (which routes `Object` + transparent choice to `mapTransparentValue`), delegating to the prior `mapExpressionForElement` behavior for AST-class / `String` operand types — so number-style and node-operand folds are unchanged.

---

## [3.0.6] - 2026-06-26

### Fixed

- **MapperGenerator dropped heterogeneous `@value` captures to source text (#43 family)**: a `@value` bound to a transparent choice rule with no `@mapping` of its own whose alternatives map to several different AST classes (e.g. `BooleanFactor ::= … | BooleanComparable @value`, where `BooleanComparable ::= InMethod | IsPresentFunction | …`) was emitted as `stripQuotes(firstTokenText(token))`, silently flattening the matched node (an `InExpr`, `IsPresentExpr`, …) to its source string. The generated mapper now resolves the real node via a new `mapTransparentValue(token)` helper (`mapToken` → `findBestMappedToken` → text fallback), so the `Object`-typed field carries the actual AST node. Downstream (tinyexpression #32) this makes `.in()` / `isPresent` / dot-predicate boolean factors inside `if(...)` conditions evaluate faithfully on the AST instead of via a source re-parse. New `MapperElementUtil.isTransparentMappedChoice` gates the change (≥2 distinct mapped alternatives → `Object` field, so emitting a node-or-text value is type-safe). Golden mapper snapshots updated.

---

## [3.0.5] - 2026-06-25

### Fixed

- **MapperGenerator collapsed heterogeneous assoc operands to `firstTokenText` (#43 family)**: left-/right-associative rules (`@leftAssoc`/`@precedence`) whose operands were function or conditional factors (e.g. `abs(-3)+pow(2,3)`) dropped the function node or recursed into `StackOverflowError`. Operands are now mapped to their real AST node via `mapAssocOperand*` (#43 case 1).
- **Variadic `{ ',' X @rest }` capture re-collected the leading scalar (#43 case 3)**: a variadic list re-walked the first operand, so e.g. range-membership checks evaluated as always-true. The repeat capture now skips the leading scalar.
- **Generated `findDescendants` recursed past rule boundaries**: it collected nested matches, so `findDescendantByIndex(StringExpr, 1)` could pick a deeply-nested literal instead of the rule-level operand, breaking `node.left()/right()` resolution (e.g. string comparison). It now collects rule-level matches only.

### Added

- **Rich UBNF VS Code extension** (`ubnf-vscode` 0.2.0): the UBNF grammar editor is now on par with the tinyexpression extension. `UBNFLanguageServerExt` wires `GrammarValidator` into live diagnostics (all `E-*`/`W-*` codes incl. left-recursion), adds quick fixes (`W-TOKEN-UNRESOLVED` → insert fully qualified parser class), context-aware completion (annotation snippets, bundled parser FQNs, declared rule/token names, block snippets), hover docs for annotations and rules, go-to-definition / references / rename / linked editing, document outline, folding, signature help for annotation arguments, and a full semantic-token highlighter. The TextMate grammar was rewritten (15 scopes), client-side snippets added, and the extension now reports server start failures.

### Fixed (ubnf-vscode)

- **ubnf-vscode server jar was unlaunchable**: the shaded jar's `Main-Class` pointed at the abstract generated `UBNFLspLauncher` (no `main`). It now points at `UBNFLspLauncherExt`.

---

## [3.0.4] - 2026-06-12

### Fixed

- **Regression in 3.0.3: `@scopeTree`/`@declares`/`@backref` scope features silently broken** (found by tinyexpression downstream verification): `ChainInterface.parse()`'s self-notification (introduced in 3.0.3 via #31) passed the raw pre-collect child token list to `onCommit`, while the dispatcher path passed the collected rule token. Generated listeners therefore never registered symbol declarations — LSP definition / linked-editing / hover-set / completion features broke. Self-notification now lives in `TransactionListenerContainer` (the single source of begin/commit/rollback events), guaranteeing payload parity for all parser types. `ChainInterface.parse()` is reverted to its 3.0.2 body, which also removes an unconditional `TokenList` copy on every failed chain parse (performance).
- `ScopeStore.registerDispatcher` is now a deprecated **no-op**: the container's self-notification makes forwarding redundant, and keeping the dispatcher active would double-notify. Existing callers remain source- and behavior-compatible.

### Notes

- Downstream verification: tinyexpression `p4-smoke` (86 tests) and LSP module smoke (25 tests) pass against 3.0.4; both had failures against 3.0.3.

---

## [3.0.3] - 2026-06-12

### Fixed

- **`ParseContext.peek(CodePointIndex, CodePointIndex)` infinite recursion** (#30, #31, #32): the overload delegated to itself and threw `StackOverflowError` when called. It now delegates to the source.
- **`SingleCharacterParser` supplementary code point handling** (#30, #31, #33): matching is now code-point based via a new primary `isMatch(int codePoint)` hook; `isMatch(char)` remains as a `@Deprecated` backward-compatible bridge for existing subclasses. `WildCardCharacterParser` continues to match supplementary characters (e.g. emoji).
- **`ParserCursor.addMatchedPosition(Index)` infinite recursion** (#37): found by the newly introduced Error Prone check; now converts to `CodePointOffset` and delegates.
- **Generated `@declares`/`@backref` listeners** could throw `NoSuchElementException` on partially-built token structures: `ParserRuleEmitter` now emits `getChildWithParserAsOptional(...).orElse(null)` instead of the throwing `getChildWithParser(...)`.

### Added

- **`TransactionListener` auto-notification** (#30, #31, #34): `ChainInterface.parse()` now calls `onBegin`/`onCommit`/`onRollback` directly on parsers that implement `TransactionListener`. The `ScopeStore.registerDispatcher` workaround is still supported and now skips `ChainInterface` implementors to avoid double notification.
- **Left-recursion warnings in the codegen pipeline** (#36): `CodegenMain`/`CodegenRunner` now report `W-LEFT-RECURSION` issues through the standard validation report machinery (stderr summary, `--strict`, `--fail-on warning`, report files). New structured API: `GrammarValidator.detectLeftRecursionIssues(GrammarDecl)`.
- **`W-TOKEN-UNRESOLVED` fully-qualified-name suggestions** (#36): when a token declaration uses an unqualified parser class name that exists in a bundled parser package, the warning hint lists the candidates (e.g. `Did you mean 'org.unlaxer.parser.elementary.NumberParser'?`).
- **Migration guide** (#35): `docs/migration-2.x-to-3.x.md` (+ Japanese) consolidating all 2.x → 3.x breaking changes and a pre-flight validation procedure, based on downstream feedback (#27, #28).
- **Static analysis in CI** (#37): Error Prone (bug-class ERROR checks) + SpotBugs (`threshold=High`, baseline in `config/spotbugs-exclude.xml`) via the `static-analysis` Maven profile.

### Notes

- **3.0.2 was never published to Maven Central** (#27); its changes are included in this release. Upgrade directly from 3.0.1 to 3.0.3.
- **The parent POM `org.unlaxer:unlaxer-parser` is published again as of this release.** The 3.0.x child POMs previously referenced a parent version absent from Central (latest published parent was 2.8.0).
- The 3.0.0 "Removed" section below was amended retroactively: `new StringSource(String)`, `StringBase`/`StringSource2`/`StringIndexAccessor*`, and `WildCardStringTerninatorParser` were removed in 3.0.0 but previously undocumented (#27, #28).
- README now documents the API deprecation policy and clarifies the `foundation-poisonpills` artifact status.

---

## [3.0.2] - 2026-04-20 (not published to Maven Central — see 3.0.3)

### Added

- **Left-recursion detection** (#25, #26): `GrammarValidator.validateWithWarnings(GrammarDecl)` new method that detects direct and indirect left-recursive cycles in a grammar and returns `Optional<List<String>>` warnings. **Warning only — never throws.** Existing grammars continue to parse without modification.
- **`@Generated` annotation** (#24): All codegen output files now carry `@javax.annotation.processing.Generated("<GeneratorClass>")`. Enables IDEs and tools (e.g. Checkstyle, SonarQube) to skip generated code. No breaking API change.

### Notes

- No API changes from 3.0.1. Safe to upgrade **from 3.0.1** without code changes.
- Projects upgrading from **2.x** must review the [3.0.0 breaking changes](#300---2026-04-18) below and the [2.x → 3.x migration guide](./docs/migration-2.x-to-3.x.md). Earlier revisions of the 3.0.0 entry omitted several removals (e.g. `new StringSource(String)`); they are now listed under 3.0.0 Removed (reported by downstream feedback #27, #28).
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
- **`new StringSource(String)`** (single-argument constructor) — removed during the StringSource/StringSource2 unification. Use `StringSource.createRootSource(String)` instead. *(Added to this entry retroactively — reported by tinyexpression, #28.)*
- **`StringBase`, `StringIndexAccessor`, `StringIndexAccessorImpl`, `StringSource2`** — removed; replaced by Java 21 standard APIs and the unified `StringSource` (#7). *(Added retroactively — reported by onigiri-parser, #27.)*
- **`WildCardStringTerninatorParser`** (typo class name) — removed; use the correctly spelled `WildCardStringTerminatorParser`. Note the constructor signature is `WildCardStringTerminatorParser(boolean, Parser)`. *(Added retroactively — #27, #28.)*

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
