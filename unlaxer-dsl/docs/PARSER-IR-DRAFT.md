# Parser IR Draft (Design Memo)

Status: Draft for discussion.

This memo proposes a hybrid approach for advanced parsing features in unlaxer-dsl:
- Syntax-level capabilities in grammar/BNF extensions
- Post-parse semantics and tooling integration in annotations
- A shared Parser Annotation IR so non-UBNF parsers can use the same downstream pipeline

Draft schema artifact: `docs/schema/parser-ir-v1.draft.json`
Draft sample payloads: `src/test/resources/schema/parser-ir/`
- `valid-minimal.json`: smallest valid payload.
- `valid-rich.json`: optional fields including `scopeEvents` and `diagnostics.related`.
- `invalid-*.json`: negative fixtures for required keys / blank source / span order / code format / invalid scope event / use missing symbol / define missing kind / use with forbidden kind / enter with forbidden fields / scope order violation / duplicate scope enter / unknown targetScopeId / unknown annotation targetId / broken related diagnostics / related span out-of-range / duplicate related diagnostics / duplicate node id / unknown parentId / unknown child id / duplicate child id / child self reference / parent/children mismatch / parent missing child link / unbalanced scopes / diagnostic span out-of-range.
- Annotation naming convention (draft): `^[a-z][a-zA-Z0-9-]*$`.
- Annotation uniqueness policy (draft): `(targetId, name)` pairs must be unique in one IR document.
- Annotation payload policy (draft): `payload` must be an object with at least one property.
- Diagnostic uniqueness policy (draft): `(code, span.start, span.end, message)` tuples must be unique.
- Diagnostic related uniqueness policy (draft): `(span.start, span.end, message)` tuples must be unique within each diagnostic.
- Scope event field policy (draft):
  - `use` and `define` require `symbol`.
  - `define` requires `kind`; `use` must not include `kind`.
  - `scopeMode` is allowed only on `enterScope` / `leaveScope`.
  - `enterScope` and `leaveScope` must not include `symbol`, `kind`, or `targetScopeId`.
  - `leaveScope` order must be nested (LIFO) within each event stream.

## 1. Design Goals

- Represent parser behavior that is hard to express in plain CFG:
  - interleave
  - backreference
  - scope-tree-aware resolution
- Keep generated pipeline compatibility for LSP/DAP and later stages.
- Allow non-UBNF parsers to plug into the same annotation-driven post-processing.

## 2. Placement Rule: BNF Extension vs Annotation

- Put it in BNF extension when it changes recognition semantics.
  - examples: interleave, backreference constraints, lexical/syntactic context gates
- Put it in annotation when it adds semantic interpretation or generation policy.
  - examples: symbol definition/use intent, scope policy, diagnostics policy

Practical rule:
- Parse-time truth -> BNF extension
- Post-parse meaning -> annotation

## 3. Feature Direction

### 3.1 Interleave

- Core mechanism belongs to grammar level (BNF extension), not only annotation.
- Annotation can expose named profiles:
  - `@whitespace: javaStyle`
  - `@interleave: commentsAndSpaces`

The profile declaration is annotation-level UX; the behavior model is grammar-level.

### 3.2 Backreference

- Needs grammar-level expression because it constrains matching.
- Annotation can attach validation intent or diagnostic metadata.

### 3.3 Scope Tree

- Scope model should be explicit in annotation IR.
- If scope state affects recognition, use grammar-level hooks plus annotation metadata.

## 4. Parser Annotation IR (Shared Contract)

Define a parser-agnostic IR consumed by downstream phases.

Minimum contract:
- parse result tree identity:
  - node id, kind, span (start/end offsets)
- token/trivia streams (optional but recommended)
- scope events:
  - enter scope, leave scope, define symbol, use symbol
- annotation map:
  - normalized key/value payload per node/rule
- diagnostics stream:
  - code, severity, span, message, hint

### 4.1 v1 Field Matrix (Draft)

Top-level:
- required:
  - `irVersion` (string, e.g. `1.0`)
  - `source` (path or logical id)
  - `nodes` (array)
  - `diagnostics` (array)
- optional:
  - `tokens` (array)
  - `trivia` (array)
  - `scopeEvents` (array)
  - `annotations` (array)

Node:
- required:
  - `id` (stable within one parse)
  - `kind`
  - `span.start`, `span.end` (UTF-16 offset by default)
- optional:
  - `parentId`
  - `children`
  - `text`
  - `attributes`

Scope event:
- required:
  - `event` (`enterScope` | `leaveScope` | `define` | `use`)
  - `scopeId`
  - `span`
- optional:
  - `scopeMode` (`lexical` | `dynamic`)
  - `symbol`
  - `kind`
  - `targetScopeId`

Diagnostic:
- required:
  - `code`
  - `severity` (`ERROR` | `WARNING` | `INFO`)
  - `span`
  - `message`
- optional:
  - `hint`
  - `related`

### 4.2 Invariants (Draft)

- node spans must be non-negative and `start <= end`.
- `nodes` must contain at least one node.
- if `parentId` exists, parent node must exist.
- scope enter/leave must be balanced per `scopeId`.
- scope enter/leave must also follow nested (stack/LIFO) order.
- scope event spans must be non-negative.
- scope event spans must satisfy `span.start <= span.end`.
- diagnostic spans must be within source span range.
- ids are stable only within the same parse result unless stated otherwise.

### 4.3 Versioning Policy (Draft)

- v1 focuses on compatibility, not maximal strictness.
- additive fields are backward-compatible.
- removing/renaming required fields requires major version bump.
- semantic changes (offset unit change, id stability semantics) require major version bump.
- adapters must declare supported `irVersion` range.

## 5. Non-UBNF Parser Integration

Introduce an adapter SPI:
- external parser output -> Parser Annotation IR
- same downstream steps as UBNF-generated parser

Suggested components:
- `ParserIrAdapter` interface
- conformance test kit:
  - fixture input
  - expected IR snapshots
  - required invariants (span order, scope balance, stable ids)

Current minimal runtime entry points:
- `org.unlaxer.dsl.ir.ParseRequest`
- `org.unlaxer.dsl.ir.ParserIrAdapter`
- `org.unlaxer.dsl.ir.ParserIrAdapterMetadata`
- `org.unlaxer.dsl.ir.ParserIrDocument`
- `org.unlaxer.dsl.ir.ParserIrConformanceValidator`
- `org.unlaxer.dsl.ir.ParserIrScopeEvents`
- `org.unlaxer.dsl.ir.GrammarToParserIrExporter`
- `org.unlaxer.dsl.ParserIrSchemaValidator`

Current exporter behavior notes:
- `GrammarToParserIrExporter` emits rule-level nodes and annotations.
- For rules with `@scopeTree(...)`, exporter currently emits synthetic balanced scope events:
  `enterScope` -> `leaveScope` with `scopeId = "scope:{GrammarName}::{RuleName}"`,
  plus `scopeMode` copied from `@scopeTree(mode=...)`.

Suggested SPI shape:
- `ParserIrAdapter#parseToIr(ParseRequest request): ParserIrDocument`
- adapter metadata:
  - adapter id
  - supported IR versions
  - supported feature flags (interleave/backreference/scope-events)
- Minimal executable sample:
  - `src/test/java/org/unlaxer/dsl/ParserIrAdapterContractTest.java` (`ScopeTreeSampleAdapter`)

## 6. Proposed Work Items

1. Define IR schema document and JSON examples.
2. Add adapter SPI in codegen/runtime boundary.
3. Implement one reference adapter for a non-UBNF parser.
4. Add conformance tests and golden snapshots.
5. Add docs for migration: UBNF-generated parser vs external parser.
6. Publish "minimal external parser adapter" example in `examples/`.
7. Add CI lane for parser IR conformance fixtures.

## 7. Open Questions

- How strict should node id stability be across parser versions?
- Should trivia be required in v1 IR or optional?
- Do we allow dynamic scope kinds, or fixed enum first?
- Backreference semantics: exact text equality vs normalized token equality?
- Offset unit default: UTF-16 vs UTF-8 byte offset?
- Should IR carry parser trace events, or keep a separate debug stream?
