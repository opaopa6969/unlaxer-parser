# UBNF Spec Notes (Working Draft)

> **Note**: この文書の内容は `specs/` ディレクトリに移動・拡充されました。
> - アノテーション仕様 → [specs/annotations.md](specs/annotations.md)
> - CLI 仕様 → [specs/cli.md](specs/cli.md)
> - バリデーション仕様 → [specs/validation.md](specs/validation.md)
> - その他の仕様 → [specs/overview.md](specs/overview.md)
>
> この文書は参照用として残されていますが、規範的な仕様は `specs/` を参照してください。

This document defines behavior that users should be able to rely on when writing grammars for `unlaxer-dsl`.

## Scope

- UBNF syntax and annotation semantics.
- Generator-facing constraints that are validated before code generation.
- LSP/DAP behavior guarantees and current limitations.

## Annotation Semantics

### `@mapping(ClassName, params=[...])`

Contract:

1. Every param in `params` must match an existing capture name (`@name`) in the same rule body.
2. Every capture name used in that rule body must be listed in `params`.
3. Duplicate param names are invalid.

Current behavior:

- Violations fail fast during code generation (`GrammarValidator.validateOrThrow`) with a clear error message, stable error code, and short fix hint.
- `GrammarValidator.validate(grammar)` returns structured issues (`code`, `message`, `hint`) without throwing.

### `@leftAssoc`

- Current status: contract-validated metadata.
- Parser generation remains grammar-driven.

### `@rightAssoc`

- Current status: partially consumed by parser generation.
- It uses the same capture/mapping contract as `@leftAssoc` (`left`, `op`, `right`).
- It is mutually exclusive with `@leftAssoc` on the same rule.
- Canonical shape `Base { Op Self }` is generated as right-recursive choice (`Base Op Self | Base`).
- Non-canonical shapes are rejected by validator.
- Mapper generator emits `foldRightAssoc<Class>` helper skeletons for right-associative mappings.

### `@precedence(level=...)`

Contract:

1. `level` is an integer (`0` or greater).
2. A rule may declare `@precedence` at most once.
3. `@precedence` currently requires either `@leftAssoc` or `@rightAssoc` on the same rule.
4. `@leftAssoc` and `@rightAssoc` cannot be used together on one rule.
5. If an operator rule references another operator rule and both have `@precedence`, the referenced rule must have a higher precedence level.
6. Every `@leftAssoc` / `@rightAssoc` rule must declare `@precedence`.
7. A single precedence level cannot mix left/right associativity.

Current behavior:

- Violations fail fast during code generation (`GrammarValidator.validateOrThrow`) with a clear error message.
- Parser generator emits `PRECEDENCE_<RULE_NAME>` constants for annotated rules.
- Parser generator emits operator metadata APIs: `getPrecedence(ruleName)` and `getAssociativity(ruleName)`.
- Parser generator emits sorted operator specs: `getOperatorSpecs()`.
- Parser generator emits `OperatorSpec` lookup helpers: `getOperatorSpec(ruleName)` and `isOperatorRule(ruleName)`.
- Parser generator emits precedence-step helper: `getNextHigherPrecedence(ruleName)`.
- Parser generator emits parser-resolution helpers: `getOperatorParser(ruleName)`, `getLowestPrecedenceOperator()`, and `getLowestPrecedenceParser()`.
- Parser generator emits precedence-level helpers: `getPrecedenceLevels()`, `getOperatorsAtPrecedence(level)`, and `getOperatorParsersAtPrecedence(level)`.

### `@whitespace`

- Global `@whitespace` in grammar settings controls delimiter insertion in generated parsers.
- Rule-level `@whitespace` overrides global behavior for that rule. `@whitespace(none)` disables auto delimiters, while `@whitespace` or `@whitespace(javaStyle)` enables auto delimiters.

### `@interleave(profile=...)`

- Current status: accepted as first-class rule annotation metadata.
- Intended role: declare interleave policy for parser-IR adapters and downstream tooling.
- Parser behavior is unchanged in this phase (metadata-only).
- Validator contract (current): at most one per rule, and `profile` must be `javaStyle` or `commentsAndSpaces`.

### `@backref(name=...)`

- Current status: accepted as first-class rule annotation metadata.
- Intended role: declare backreference intent for semantic constraints and diagnostics.
- Parser behavior is unchanged in this phase (metadata-only).
- Validator contract (current): at most one per rule.

### `@typeof(captureName)`

- **Kind**: element-level annotation — appears as a standalone `AtomicElement` in a rule body sequence.
- **Syntax**: `@typeof(name)` where `name` is the capture name of another element in the same rule.
- **Semantics**: The annotated element (with its own `@ownCapture` suffix) must resolve to the same
  Java type as the element captured under `name` at runtime.
- **Parser behavior**: TypeofElement is invisible to the parser generator. It does not produce any
  parser code in the emitted Chain. The parser matches the token structure unchanged.
- **Mapper behavior**: The mapper generator resolves `@typeof(name)` to the same rule reference and
  mapper function as the `name` capture. After mapping both fields, it emits a runtime type assertion:
  ```java
  if (name != null && ownCapture != null && !name.getClass().equals(ownCapture.getClass())) {
      throw new IllegalArgumentException("@typeof constraint violated: ...");
  }
  ```
- **Use case**: Enforce that `then`/`else` branches of an `if` expression use the same Expression
  variant, preventing a type mismatch at evaluation time.
- **Example**:
  ```ubnf
  @mapping(IfExpr, params=[condition, thenExpr, elseExpr])
  IfExpression ::=
    'if' '(' BooleanExpression @condition ')'
    '{' Expression @thenExpr '}'
    'else'
    '{' @typeof(thenExpr) @elseExpr '}' ;
  ```
  In this example, `elseExpr` is mapped using `Expression`'s mapper (same as `thenExpr`), and a
  runtime assertion ensures `thenExpr.getClass() == elseExpr.getClass()`.
- **Validator error codes**:
  - `E-TYPEOF-UNKNOWN-CAPTURE` — `captureName` refers to a capture not defined in this rule.
  - `E-TYPEOF-MISSING-CAPTURE` — `@typeof(name)` element has no own capture name.

### `@scopeTree(mode=...)`

- Current status: accepted as first-class rule annotation metadata.
- Intended role: declare scope-tree-aware processing intent for symbol/tooling phases.
- Parser behavior is unchanged in this phase (metadata-only).
- Validator contract (current): at most one per rule, and `mode` must be `lexical` or `dynamic`.
- Parser generator emits scope-tree metadata APIs:
  `getScopeTreeMode(ruleName)`, `getScopeTreeModeEnum(ruleName)`, `getScopeTreeRules()`,
  `getScopeTreeModeByRule()`, `getScopeTreeSpec(ruleName)`, and `getScopeTreeSpecs()`.
- `ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRules(grammarName, scopeModeByRuleName, nodes)`
  converts rule-level scope metadata into parser-IR `scopeEvents`.
- `ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRulesAnyMode(grammarName, scopeModeByRuleName, nodes)`
  also accepts generated enum maps (`Map<String, ScopeMode>`) directly.

## Token Resolution

`token NAME = ParserClass` maps as follows:

- In generated parser code, references to `NAME` become `Parser.get(ParserClass.class)`.
- Import resolution currently checks known `unlaxer` parser packages.
- If a parser class cannot be resolved/imported, compilation fails in generated consumer projects.

## CLI (`CodegenMain`) Behavior

- All `grammar` blocks in a single `.ubnf` file are processed (not only the first one).
- Missing values for `--grammar`, `--output`, or `--generators` are treated as CLI errors.
- `--generators` values are comma-split with whitespace trimming; empty entries are rejected as CLI errors.
- `--dry-run` previews generated file paths without writing outputs.
- `--clean-output` removes planned target files before generation starts.
- `--overwrite never|if-different|always` controls overwrite behavior for existing files.
- `--fail-on none|warning|skipped|conflict|cleaned|warnings-count>=N` applies extra failure policy checks after execution.
- `--help`/`-h` prints usage and exits with code `0`.
- `--version`/`-v` prints resolved tool version and exits with code `0`.
- `--strict` treats validation warnings as failures (exit code `5`).
- Grammar validation runs before generation for each grammar block.
- Validation failures are aggregated across grammar blocks and reported in one error.
- `--validate-only` runs grammar validation without writing generated sources.
- `--validate-parser-ir <path>` validates Parser IR JSON directly without parsing `.ubnf`.
- `--export-parser-ir <path>` exports Parser IR JSON from a `.ubnf` grammar without code generation.
- `--report-format json` emits machine-readable validation output (especially useful with `--validate-only`).
- `--report-format ndjson` emits newline-delimited JSON events (`file` events plus summary report event).
- `--export-parser-ir` with `--report-format ndjson` emits `parser-ir-export` event with `source`, `output`, `grammarCount`, `nodeCount`, and `annotationCount`.
- In `ndjson`, CLI failures (usage/argument errors and runtime failures such as unknown generator) are emitted as `cli-error` events.
- `cli-error` events expose stable fields: `code`, `message`, nullable `detail`, and `availableGenerators`.
- `cli-error.code` follows `E-[A-Z0-9-]+`.
- Typical `cli-error` codes are `E-CLI-USAGE`, `E-CLI-UNKNOWN-GENERATOR`, `E-CLI-UNSAFE-CLEAN-OUTPUT`, `E-PARSER-IR-EXPORT`, `E-IO`, and `E-RUNTIME`.
- In `ndjson` mode, `stdout` remains JSON-lines only (human progress text is suppressed).
- In `ndjson` generation mode, conflict/fail-on human error messages are suppressed from `stderr`.
- In `ndjson` validation failure paths, `stderr` also remains JSON-lines only.
- In `ndjson` generation failure paths, events are emitted to `stdout` and `stderr` remains empty.
- With `--report-file` and `ndjson`, the persisted report content is the raw JSON payload (without NDJSON event wrapper).
- For warning-only successful validation (`--fail-on none`), `--report-file` stores the final `validate` success payload.
- `--report-file <path>` writes the final report payload (text/json) to a file.
- `--output-manifest <path>` writes action manifest JSON (written/skipped/conflict/dry-run files and counts).
- `--manifest-format json|ndjson` controls manifest output format when `--output-manifest` is used.
- `--report-version 1` selects JSON schema version (currently only version `1` is supported).
- `--report-schema-check` validates JSON payload schema before emitting/writing reports.
- `--warnings-as-json` emits warning diagnostics as JSON to stderr even when `--report-format text` is used.
- On schema-check failure, CLI emits stable error codes prefixed with `E-REPORT-SCHEMA-*`.
- In normal generation mode with `--report-format json`, CLI emits generation summary (`generatedCount`, `generatedFiles`).
- JSON report schema includes stable top-level fields:
  `reportVersion`, `schemaVersion`, `schemaUrl`, `toolVersion`, `argsHash`, `generatedAt` (UTC ISO-8601), and `mode` (`validate` or `generate`).
- `argsHash` is SHA-256 of normalized semantic CLI settings (not raw argv).
- `argsHash` includes: `grammar`, `output`, `generators`, `validate-only`, `dry-run`, `clean-output`, `strict`,
  `validate-parser-ir`, `export-parser-ir`, `report-format`, `manifest-format`, `report-version`, `report-schema-check`, `warnings-as-json`,
  `overwrite`, `fail-on`, and warnings threshold.
- `argsHash` excludes destination-only paths and non-executing flags (for example: `--report-file`, `--output-manifest`, `--help`, `--version`).
- `argsHash` normalization is versioned internally (`version=1`) for forward-compatible evolution.
- JSON report payloads include `warningsCount` for warning aggregation.
- Generation payloads include overwrite/dry-run stats: `writtenCount`, `skippedCount`, `conflictCount`, `dryRunCount`.
- Fail-on-triggered report payloads include `failReasonCode` (for example, `FAIL_ON_CONFLICT`).
- `toolVersion` is sourced from artifact `Implementation-Version`; fallback is `dev`.
- Validation failure `issues[]` entries include structured metadata:
  `rule`, `code`, `severity`, `category`, `message`, and `hint` (plus `grammar`).
- Validation failure reports include aggregate summaries:
  `severityCounts` and `categoryCounts`.
- Validation `issues[]` order is deterministic (sorted by `grammar`, `rule`, `code`, `message`).
- Process exit codes are explicit:
  `0` success, `2` CLI usage error, `3` validation error, `4` generation/runtime error, `5` strict validation error.
- JSON payload creation is centralized in `ReportJsonWriter` and versioned via `ReportJsonWriterV1`.
- `ReportJsonSchemaCompatibilityTest` pins top-level JSON schema order/keys for report version 1.
- The public JSON schema contract for v1 lives at `docs/schema/report-v1.json`.
- NDJSON event schema contract lives at `docs/schema/report-v1.ndjson.json`.
- Manifest schema contracts live at `docs/schema/manifest-v1.json` and `docs/schema/manifest-v1.ndjson.json`.
- Parser IR / advanced grammar extension design draft lives at `docs/PARSER-IR-DRAFT.md`.
- Parser IR draft schema artifact lives at `docs/schema/parser-ir-v1.draft.json`.
- `CodegenMain.runWithClock(...)` exists for deterministic timestamp testing.

### JSON Report Examples
Regenerate from live CLI output:

```bash
./scripts/spec/refresh-json-examples.sh
```

<!-- JSON_REPORT_EXAMPLES_START -->
Validate success:

```json
{"reportVersion":1,"schemaVersion":"1.0","schemaUrl":"https://unlaxer.dev/schema/report-v1.json","toolVersion":"<toolVersion>","argsHash":"<argsHash>","generatedAt":"<generatedAt>","mode":"validate","ok":true,"grammarCount":1,"warningsCount":0,"issues":[]}
```

Validate failure:

```json
{"reportVersion":1,"schemaVersion":"1.0","schemaUrl":"https://unlaxer.dev/schema/report-v1.json","toolVersion":"<toolVersion>","argsHash":"<argsHash>","generatedAt":"<generatedAt>","mode":"validate","ok":false,"failReasonCode":null,"issueCount":1,"warningsCount":0,"severityCounts":{"ERROR":1},"categoryCounts":{"MAPPING":1},"issues":[{"grammar":"Invalid","rule":"Invalid","code":"E-MAPPING-MISSING-CAPTURE","severity":"ERROR","category":"MAPPING","message":"rule Invalid @mapping(RootNode) param 'missing' has no matching capture","hint":"Add @missing capture in the rule body or remove it from params."}]}
```

Generate success:

```json
{"reportVersion":1,"schemaVersion":"1.0","schemaUrl":"https://unlaxer.dev/schema/report-v1.json","toolVersion":"<toolVersion>","argsHash":"<argsHash>","generatedAt":"<generatedAt>","mode":"generate","ok":true,"failReasonCode":null,"grammarCount":1,"generatedCount":1,"warningsCount":0,"writtenCount":1,"skippedCount":0,"conflictCount":0,"dryRunCount":0,"generatedFiles":["/path/to/out/org/example/valid/ValidAST.java"]}
```
<!-- JSON_REPORT_EXAMPLES_END -->

## LSP / DAP Expectations

### LSP

- Diagnostics, hover, and keyword completion are supported.
- Completion includes DSL core keywords and annotation keywords (`@root`, `@mapping`, `@whitespace`, `@interleave`, `@backref`, `@scopeTree`, `@leftAssoc`, `@rightAssoc`, `@precedence`) plus grammar terminals.
- Semantic tokens currently return an empty token list to avoid invalid token encoding.

### DAP

- Breakpoints and stepping are based on parsed token stream.
- Step points are collected depth-first from parse tree leaves.

## Future Tightening Candidates

1. Rule-level whitespace override semantics.
2. Parser generation that actually consumes `@precedence` + associativity metadata.
3. Explicit token import namespace configuration.

## Golden Fixture Maintenance

- Snapshot fixtures are stored under `src/test/resources/golden/`.
- Generator fixtures are rewritten by `org.unlaxer.dsl.codegen.SnapshotFixtureWriter`.
- CLI report/manifest fixtures are rewritten by `org.unlaxer.dsl.CliFixtureWriter`.
- `SnapshotFixtureWriter` supports `--output-dir <path>` for safe dry-runs from tests or local verification.
- `scripts/refresh-golden-snapshots.sh` also supports `--output-dir <path>`.
- `scripts/check-golden-snapshots.sh` verifies committed fixtures against regenerated output.
- `SnapshotFixtureData` and `CliFixtureData` keep fixture grammars and file lists in one place.
- `SnapshotFixtureGoldenConsistencyTest` verifies writer output matches committed fixtures.
- `CliFixtureGoldenConsistencyTest` verifies CLI fixture writer output matches committed fixtures.
- Fixtures currently cover AST/Parser/Mapper/Evaluator/LSP/LSPLauncher/DAP/DAPLauncher (plus right-assoc parser/mapper variants).
