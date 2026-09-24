# Changelog

All notable changes to unlaxer-parser are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions are published to Maven Central (`org.unlaxer:unlaxer-common`, `org.unlaxer:unlaxer-dsl`).

---

## [Unreleased]

## [3.1.0] - 2026-09-25

### Added
- Java `Memoization.SAFE_FAILURES` also replays safe successes for exact classes directly implementing `SafeSuccessMemoizable`. Entries retain rule-local diagnostics, independent token snapshots, cursors, and choice selections.
- The Java generator proves success safety transitively, excludes listener/state-dependent rules and uncertified custom tokens, and marks generated whitespace delimitors. Runtime replay also covers their `Occurs` entry point. Memoization remains off by default; Rust behavior and generated Rust sources are unchanged.
- `Source.sourceRange()`: a default method that returns a token's `[start, start+length)` extent directly, without building an intermediate `CursorRange` (#286).
- `docs/engine-selection-guide-ja.md`: a decision guide between unlaxer Classic (this repository's combinator runtime) and the sibling `ubnfc` UBNF compiler — what each one offers, a decision procedure, and the maintenance policy (Classic performance work stops at round 6 below; ubnfc is where further speed work goes). Linked from the READMEs (#303).
- `.github/workflows/release-central.yml` (`workflow_dispatch`): publishes `org.unlaxer:unlaxer-parser` to Maven Central from GitHub Actions as an alternative to the local `scripts/release-central.sh` path, with a secrets-free `guard` job that checks the version/confirmation/monthly cap before any credentialed step (#304).
- AGENTS.md and test Javadoc now document that `RustUbnfFrontendConformanceTest` (and other `-DrustConformance=true` tests) are skipped under a plain `mvn test` and verified only in CI; a skipped run now prints an `[assumption] ...` line naming the property instead of silently passing (#283).
- `scripts/release-central.sh` now prefers `~/.m2/settings-central.xml` (or a `MAVEN_SETTINGS` override) for Maven Central credentials, since `~/.m2/settings.xml` on the release machine is rewritten by unrelated self-hosted-runner jobs' `actions/setup-java` steps and loses the `central` server entry (#305).

### Changed
- The parse tree itself is smaller. A sub-source is now a view over its root's code point array
  (start/length) with its String materialized on demand instead of a fresh `String` and `int[]`
  per source; empty child lists share one immutable backing list until first written; a source's
  depth and both offsets are int fields behind the same accessors; and the root position resolver
  keeps its three indices in `int[]` instead of `HashMap`s with one boxed entry per code point.
  On a 20 KB input the tree retained by a parse falls from 34.7 MB / 1,256,693 objects to
  23.2 MB / 756,303 objects (-33% / -40%). `TokenList.toSource` only takes the view when the
  children are verifiably one contiguous slice of the root and otherwise concatenates as before,
  so nothing about the produced tokens changes. Rust behavior is unchanged: `unlaxer-runtime`'s
  `Tree` already stores nodes as spans over one owned source.
- The packrat memo table no longer keeps every entry for the whole parse. Entries whose start
  position falls more than `unlaxer.memo.window` code points (default 1024) behind the furthest
  position the cursor reached are dropped, which bounds the memo live set by the grammar's
  backtracking distance instead of the input length; on a 20 KB input the peak entry count falls
  from 102,054 to 8,424 with identical memo hit counts. Eviction cannot change a parse result — a
  miss simply re-parses — and a probe below the watermark widens the window past twice the
  observed look-back, so a grammar that backtracks further degrades to the previous behaviour.
  Inputs shorter than the window are untouched. Set `-Dunlaxer.memo.evictBelowFrontier=false` to
  restore the previous table. Rust behavior is unchanged.
- The UBNF bootstrap (`UBNFMapper`/`UBNFParsers`) now rejects trailing unconsumed input after a
  grammar file's closing `}` (e.g. `grammar G { R ::= 'x'; } garbage`) with a positioned
  `IllegalArgumentException` (`line N, column M`), and accepts an empty grammar body
  (`grammar G { }`), matching both the spec (`unlaxer-dsl/specs/ubnf-syntax.md`) and the Rust
  `unlaxer-ubnf` frontend, which already behaved this way. Previously the Java bootstrap silently
  accepted a prefix of the input and rejected empty grammars. Trailing whitespace and trailing
  `//` comments remain accepted (#278, #280, #283).
  - The `RustUbnfFrontendConformanceTest` fixture that had encoded the old (incorrect) Java
    behaviour as a known cross-language difference is fixed to assert the new, spec-conforming
    rejection, including that Java and Rust report the same line/column (#283).
- Chained (3+ segment) dotted rule references (`a.b.Value`) in `unlaxer-dsl/grammar/ubnf.ubnf`
  now parse correctly through the *generated* self-hosting frontend, matching the hand-written
  `UBNFParsers`/Rust `unlaxer-ubnf` frontends, which already accepted them. The `QuantifiedRef`
  production changed from a single optional dot to an unbounded `{ IDENTIFIER '.' }` repeat
  (#284, #285).
- Deferred-diagnostics mode (`ParseOptions.Diagnostics.DETAILED_ON_FAILURE`) no longer builds or
  retains the bookkeeping objects that failure recording doesn't read on a mode where recording
  is already skipped: per-call `ParseFrame` push/pop and memo-transaction-frame management are
  trimmed to what transaction replay still needs semantically. Counted on `complex-x64`: no
  change in observable results, measurable reduction in objects created per parse (#263, #287).
- Deferred-diagnostics mode also now computes each parser's FIRST set (leading code points, as a
  fixed point over the combinator graph) once and uses it to skip a choice candidate, chain
  element, or repetition body whose leading code point cannot match — without opening a
  transaction, creating a `Token`, or touching the memo table for it. This only applies when
  diagnostics are deferred and no listener/action/trial recording is active; `DETAILED` mode
  executes exactly the same instructions as before. Disable with
  `-Dunlaxer.parser.firstSetExclusion.disabled=true` (#292, #302). See **Performance** below.
- Spec documentation corrections found by the sibling `ubnfc` project's spec-derived oracle
  corpus: several error-code names documented in `validation.md`/`annotations.md`
  (`E-MAPPING-EXTRA-CAPTURE`, `E-ASSOC-WITHOUT-PRECEDENCE`, `E-PRECEDENCE-WITHOUT-ASSOC`) did not
  match what `GrammarValidator` and ubnfc actually emit (`E-MAPPING-UNLISTED-CAPTURE`,
  `E-ASSOC-NO-PRECEDENCE`, `E-PRECEDENCE-NO-ASSOC`); the docs are renamed to the implemented
  names. Implementations are unchanged — both implementations already agreed with each other, so
  the spec text was the stale side (#277, #279).
- Release policy: the "one Maven Central publish per calendar month" rule is now a guideline, not
  a hard cap. `release/central-release-queue.yml`'s `maxPublishOperationsPerCalendarMonth` is
  raised to accommodate multiple ready candidates in the same month, and readiness (green CI,
  updated changelog, version bump) gates a release instead of a fixed publish count (#296, #299).

### Fixed
- A repetition body was skipped one iteration too eagerly: the "skip when nothing was consumed"
  check compared the wrong cursor pair, so a failed terminal that reset the matched-but-not the
  consumed cursor could make a following repetition wrongly treat its body as a no-op and accept
  input it should have rejected (e.g. `(T) ['x'] (U) 'ab'` incorrectly accepting `"ab"`). The
  check now requires the consumed and matched cursors to coincide before skipping. Found by the
  Rust conformance CI job's cross-language corpus, not by either implementation's own test suite
  (#302).
- `UBNFMapper`'s hand-written `toAtomicElement`/`toQuantifiedRef` only ever used the first two
  identifiers of a dotted rule reference (`namespace = segments[0]`, `name = segments[1]`),
  silently dropping segments beyond the second for 3+ segment references even though parsing
  itself already accepted them. Both call sites now share a `buildRuleRef()` helper: the last
  identifier is always `name`, everything before it joins with `.` into `namespace` (#285).

### Performance
Six rounds of profiling work against issue #276/#292 targeted the packrat combinator runtime's
opt-in deferred-diagnostics mode (`ParseOptions.Diagnostics.DETAILED_ON_FAILURE`), using the
`complex` (332 B) and `complex-x64` (20,923 B, `complex` concatenated 64x) tinyexpression P4
fixtures. Each round is its own A/B session on a shared, noisy host (load average observed
7-49), measured as wall-clock minimum over repeated interleaved runs; a round's "before" number
is that round's own baseline run, not necessarily the previous round's "after" number, so
`complex`'s value moves within measurement noise round to round while `complex-x64` shows the
cumulative effect of shrinking allocation and the live object set:

| Round | Case | PR | What changed | `complex` before → after | `complex-x64` before → after |
|---|---|---|---|---:|---:|
| 1 | 32 | #281 | Stop allocating always-empty per-token/per-diagnostic-frame containers (-12% allocation) | 14.38 → 14.54 ms | 1,552.84 → 1,390.95 ms (-10.4%) |
| 2 | 33 | #286 | Token ranges without `CursorRange`; expected-source index without `IdentityHashMap` (-27% allocation) | 12.28 → 9.95 ms (-18.9%) | 1,276.26 → 989.86 ms (-22.4%) |
| 3 | 34 | #287 | Stop building `DETAILED_ON_FAILURE` bookkeeping the mode never reads (#263) | 8.109 → 6.294 ms (-22.4%, median) | 664.836 → 568.791 ms (-14.4%, median) |
| 4 | 35 | #289 | Evict packrat memo entries outside the backtracking window (peak entries 102,054 → 8,424 on x64) | 5.920 → 5.998 ms (±noise) | 558.187 → 498.397 ms (-10.7%) |
| 5 | 36 | #291 | Shrink the retained CST itself: source views, shared empty lists, unboxed value objects (-33%/-40% retained memory) | 6.673 → 5.794 ms (-13.2%) | 550.735 → 466.352 ms (-15.3%) |
| 6 | 37 | #302 | Runtime FIRST-set candidate exclusion (rule evaluations -41%, transactions -47% on x64) | 7.129 → 5.087 ms (-28.6%) | 419.701 → 370.888 ms (-11.6%) |

Across the six rounds, `complex-x64` wall-clock minimum went from round 1's baseline (~1,553 ms)
to round 6's result (~371 ms) on this host; allocation, GC pause time, and operation counts each
fell by roughly a third to a half per round and are the more load-independent evidence for the
improvement (see `docs/performance-tuning-ja.md` cases 32-37 for the full JFR/GC-log breakdown).
**Issue #292's target of `complex-x64` ≤ 350 ms in deferred mode was not confirmed on this shared
host** — the best observed minimum was 370.9 ms; subtracting GC pause and extrapolating the
round's CPU-time improvement onto round 5's quieter-host baseline (466 ms) estimates roughly
290 ms, but this has not been directly measured.

Default (`DETAILED`) diagnostics mode was not the target of this work and is deliberately
untouched by the FIRST-set exclusion (round 6 changes zero instructions on that path). Round 6
re-verified no regression: operation counts and allocation are bit-identical between base and
candidate, and wall-clock time on the four fixtures moved by -0.5% to +4.8% (noise), e.g.
`complex-x64` 667.4 ms → 658.6 ms.

### Deprecated
- Nothing is removed in this release. `Token.tokenString`/`tokenRange` (deprecated since an
  earlier release in favor of `sourceRange()` and related accessors) remain as working bridges;
  their removal is deferred to 4.0, consistent with this being a backward-compatible minor
  release.

### Tests
- The spec-derived UBNF oracle is now a permanent test on both implementations. 129 cases derived
  from the normative sentences of `unlaxer-dsl/specs/{ubnf-syntax,annotations,validation}.md`
  (origin: the sibling `ubnfc` repository; see `spec-corpus/ubnf/SOURCE.md`) are committed under
  `unlaxer-dsl/src/test/resources/spec-corpus/ubnf/` and driven by `UbnfSpecCorpusTest` (Java
  bootstrap: 126 cases through `UBNFMapper` and `GrammarValidator`, including canonical-AST
  expectations) and `rust/unlaxer-ubnf/tests/spec_corpus.rs` (Rust frontend: the 108 parse-scope
  cases) from the same fixture files. Both tests also verify that every cited heading and sentence
  still exists verbatim in the spec, so editing the spec breaks this repository's tests rather
  than the sibling's. One documented deviation is pinned with its observed behaviour
  (`quantifier/huge-bound`: the Java mapper throws `NumberFormatException` before
  `E-BOUNDED-OVERFLOW` can be reported).
- `ParseEqualityGoldenTest` turns the round 3-5 one-off equality dumps into a committed golden
  file. 35 inputs x {shipped, memo-safe grammar} x {DETAILED, DETAILED_ON_FAILURE} x
  {memo OFF, SAFE_FAILURES} = 280 combinations dump the whole CST (kinds, parsers, source kinds,
  offsets, ranges, code point and char lengths, child counts, source strings), the memo and
  transaction counters, and every `ParseFailureDiagnostics` getter. Regenerate with
  `-Dunlaxer.golden.regenerate=true`.
- `ParserScalingSmokeTest` pins the "the parser itself is linear" result of cases 32-36 with
  load-independent counters: transactions, memo hits and entries, and `getThreadAllocatedBytes`
  must not grow by more than x1.3 per byte between x1, x4 and x16 concatenations, for both a
  generated parser (with memoization) and the shipped UBNF bootstrap.
- Non-BMP coverage for the offset conversions: `LSPNonBmpOffsetTest` drives a generated LSP server
  and pins that code point offsets become UTF-16 offsets before they reach `Position`, and
  `UbnfNonBmpOffsetTest` pins the bootstrap's line/column reporting across surrogate pairs. The
  DAP side was already covered by `DAPNonBmpLineMappingTest` and the Rust side by
  `rust/unlaxer-ubnf/tests/frontend.rs`.

## [3.0.15] - 2026-09-01

### Added
- Generated Mappers expose `mapParsedToken(Token[, preferredAstSimpleName])` and return both the selected token and mapped AST. Consumers can map an existing parse tree without reflection into private mapper methods or state.
- Public parser boundary behavior now has regression coverage for source cursors, repeat bounds, case-insensitive words, and supplementary code points.
- Java grammar settings can explicitly certify stateless custom token aliases for safe failure memoization with repeatable `@memoSafeToken: ALIAS` declarations; invalid, duplicate, and non-simple aliases are rejected deterministically.

### Deprecated
- Correctly spelled public APIs now replace `Source.sourceToStgring()`, `NonTerminallSymbol`, and `HierarcyLevel`. The misspelled symbols remain as source- and behavior-compatible deprecated bridges and will not be removed before 3.2.0.

### Changed
- `ScopeStore.addReference` / `addDiagnostic` no longer advance the memoization state version.
  References and symbol diagnostics are write-only during parsing (read after the parse), so
  recording them cannot change what a memoized rule produces; advancing the version on every
  `$var` reference made the rest of the parse miss the memo table. tinyexpression's five-level
  nested `if` formula parses in about 0.1 s instead of 4-6 s with `Memoization.SAFE_FAILURES`
  (#269). Declarations and scope depth still advance the version.
- Maven Central publication now defaults to bundle-only mode and requires the organization-wide monthly release guard to opt into upload. The Central publishing plugin is updated to 0.11.0 so token identifiers are not written to release logs.

### Fixed
- The `javaStyle` whitespace profile now generates both line and block comment parsers.
- Generated DAP adapters convert parser code-point offsets to UTF-16 coordinates, including non-BMP source characters.
- Rule/token parser-name collisions are diagnosed for regex, negation, and character-range tokens, and their duplicate wrappers are not emitted.
- `MappedSingleCharacterParser` no longer indexes its ASCII lookup table for non-ASCII input in inverted mode.
- The MCP server rejects unsafe input/output paths instead of allowing traversal outside its intended roots.

---

## [3.0.14] - 2026-08-26

### Added
- Generated DAP adapters can now override `resolveDebugSource(...)` to select a line-aligned DSL slice from a container document while retaining the original file path, line mapping, and breakpoint coordinates. The default hook preserves the prior whole-file behavior.

---

## [3.0.13] - 2026-08-26

### Added
- Generated LSP syntax diagnostics now expose the stable code `ULX-PARSE-001`, the farthest failure position, expected tokens/hints/parsers, and the deepest matched rule. The same values are included in `Diagnostic.data` as a deterministic repair envelope for editor extensions and LLM clients.
- Grammar validation now rejects rule/token names that resolve to the same generated parser class with `E-RULE-TOKEN-NAME-COLLISION`, preventing a generated parser from silently recursing into itself at runtime.
- Generated DAP adapters expose their runtime-variable refresh hook to language-specific adapters, allowing a typed application context to be edited and re-evaluated without hand-editing generated code.

### Fixed
- Generated LSP servers now diagnose failed empty input and underline only the failing character (or the zero-width EOF position) instead of marking the entire remaining document. Parser code-point offsets are converted to LSP UTF-16 positions, including after non-BMP characters. Hover uses the same actionable parse message as diagnostics.
- Railroad SVG node IDs are now deterministic traversal IDs instead of JVM identity hashes, so regenerating an unchanged grammar no longer creates review noise for humans or LLMs.

---

## [3.0.12] - 2026-08-26

### Added
- **Runnable generated DAP/VSIX path**: `DAPLauncherGenerator` now emits a concrete stdio launcher with `main()`, so a UBNF project generated with `DAP,DAPLauncher` can be started directly from a VSIX. The scaffold and TinyCalc example default to AST stepping and entry stop, and a generated-adapter smoke test covers launch, stop, stack, and variables.
- **Application runtime hook for generated DAP adapters**: generated adapters expose `runtimeVariables(source, runtimeMode, launchArguments)`. UBNF continues to generate protocol, source mapping, breakpoints, and structural stepping; language-specific evaluation and typed variables can be supplied without coupling `unlaxer-dsl` to an application runtime.

### Changed
- Java generated parsers now expose immutable, default-off `ParseOptions` with
  `Memoization.SAFE_FAILURES`. Generated dependency analysis marks only exact classes proven free
  of scope, declaration, back-reference, custom-parser, and other state-dependent behavior;
  failures replay rule-local diagnostics, successes are never cached, and the deprecated
  `enableMemoize()` / `memoize()` adapters obey the same fail-closed policy (#194).
- DAP launch uses standard `program` with `formulaSource` retained as a compatibility alias, and separates execution `runtimeMode` from structural `steppingMode`.
- AST stepping fails explicitly when AST mapping is unavailable instead of silently switching to token stepping.
- `StringSource.peek` no longer double-allocates: it returned `new StringSource(this, subSource(...), offset)`, wrapping an already-equivalent `subSource` in a second `StringSource` (a redundant String + int[] copy on every peek — a hot path in deeply nested grammars). Now returns the single `subSource` directly. Byte-for-byte equivalent; full `unlaxer-common` suite green (108 files). NOTE: this halves peek allocation but does **not** by itself bring the deeply nested-`if` formula (tinyexpression #19 example 5) under a second — that case is GC/allocation-bound and needs dedicated profiling (its cost is not `(rule,position)` re-derivation, which memoization already removes).

---

## [3.0.10] - 2026-06-28

### Added
- **Opt-in packrat memoization** (`unlaxer-common`, issue #40). Enable per parse session via `parseContext.enableMemoize()` or `new ParseContext(source, ParseContext.memoize())`. Memoizes the outcome of parsing a rule (`ChainInterface`/`ChoiceInterface`/`AbstractParser`) at a position, keyed by `(parser identity, consumed, matched, tokenKind, invertMatch)`, collapsing the exponential backtracking that ambiguous-paren expression grammars trigger (downstream hang in opaopa6969/tinyexpression#19). **Off by default — default parsing is byte-for-byte unaffected** (full `unlaxer-common` suite green: 108 test files, 0 failures).
  - **Failure memoization**: a rule that already failed at a position cannot succeed on a retry, so the retry short-circuits instead of re-deriving the whole sub-tree. A failing input that was ~2^depth (≈11.5s at depth 18, intractable beyond) parses in milliseconds. A `TransactionListener` (scope tree / declarations / back-reference) is never failure-memoized, since its outcome can depend on mutable scope state.
  - **Success memoization**: a rule that already succeeded at a position replays its cached tokens (deep-copied via `Token.deepCopy()` — `parent` is mutable, so shared instances would corrupt the tree) and advances the cursor, instead of re-deriving. Only applied to **success-memoizable** rules — those whose sub-tree contains no `TransactionListener` — so scope/declaration/back-reference side effects are never skipped. Token-tree parity (on vs off) is verified by test. NOTE: because expression rules that reference variables embed a `@backref` (`VariableRef`) listener, they are excluded from success memoization; failure memoization already collapses the exponential for those grammars (see `docs/packrat-memoization.md`).

---

## [3.0.9] - 2026-06-27

### Fixed

- **Multiple non-assoc rules mapping to one AST class only generated from one rule (tinyexpression #32, nested slice)**: when two `@mapping` rules target the same class with DIFFERENT capture structures — e.g. `SliceExpr`: `SliceBaseExpression`'s `@value` is a `SliceBaseReceiver` while `SliceNestedExpression`'s `@value` is a `SliceBaseExpression` (the mapped class itself) — the generated `to<Class>` was emitted from a single representative rule and mis-resolved tokens of the other rule (a nested slice `'gateman'[::-1][0:4]` dropped the inner `[::-1]`, grabbing the innermost receiver). `emitPlainMappingBody` now dispatches each additional non-assoc rule by `token.parser.getClass()` (resolution extracted to `emitPlainMappingResolution`), with the representative rule as the unguarded fallback — exactly one rule is guarded and the other falls through, so the result is correct regardless of representative choice. Single-rule and structurally-identical multi-rule classes (e.g. `IfExpr`'s `ArgumentTernary`) are behavior-unchanged.
- **`findCapturedToken` fallback leaked an absent capture into a nested sub-expression**: the global descendant fallback let an outer slice's ABSENT `@step` (`[0:4]`) match the inner `[::-1]`'s step. The fallback is now a BOUNDED descendant search (`findDescendantsBounded`) that stops at `CAPTURE_BOUNDARY_PARSERS` — the set of all rule parser classes that map to their own AST node — so a capture missing at this level cannot cross into a separate captured sub-node. Captures genuinely nested under anonymous wrappers (Optional/Group/Repeat) still resolve. `MapperRuleEmitter.emitUtilities` now takes `(parsersClass, mappedRuleNames)`.

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
