# UBNF Self-Hosting Milestone

> Historical record of how UBNF first generated itself.
> The current production setup lives under `unlaxer-dsl/ubnf-vscode/`
> (Maven-driven, integrated into the reactor build).

## Summary

The UBNF code generators can process `unlaxer-dsl/grammar/ubnf.ubnf`
(UBNF defining its own syntax) and produce **all six artifact types**
that unlaxer ships:

| Generator | Output |
|---|---|
| `Parser` | `UBNFParsers.java` — class-based parser combinators |
| `AST` | `UBNFAST.java` — sealed interface + record AST |
| `Mapper` | `UBNFMapper.java` — Token → AST mapping |
| `Evaluator` | `UBNFEvaluator.java` — abstract visitor over AST |
| `LSP` | `UBNFLanguageServer.java` — full Language Server Protocol implementation |
| `DAP` | `UBNFDebugAdapter.java` — full Debug Adapter Protocol implementation |

Issue: opaopa6969/unlaxer-parser#4
Initial milestone commit: 890b831 (2026-04-12)

## Why it matters

This is the moment unlaxer crosses the **bootstrap threshold**: the tool
is now expressive enough to specify itself. Concretely:

- The hand-written bootstrap `UBNFParsers` / `UBNFAST` / `UBNFMapper`
  in `org.unlaxer.dsl.bootstrap` are *structurally equivalent* to what
  the generators produce from `ubnf.ubnf` (Issue #8 takes the next step
  and replaces them with the generated versions).
- Three previously non-existent artifacts — `UBNFEvaluator`,
  `UBNFLanguageServer`, `UBNFDebugAdapter` — pop out of the same
  pipeline. They power the `.ubnf` IDE experience documented under
  `unlaxer-dsl/ubnf-vscode/`.

## Production build

The reactor build now produces the UBNF VSIX automatically:

```bash
mvn -B package
# → unlaxer-dsl/ubnf-vscode/target/ubnf-lsp-0.1.0.vsix
```

Internally:

```
ubnf.ubnf
   │  (mvn generate-sources)
   ▼
target/generated-sources/ubnf/org/unlaxer/dsl/bootstrap/generated/
   │ UBNFLanguageServer.java
   │ UBNFLspLauncher.java
   ▼  (mvn package — shade)
target/ubnf-lsp-server.jar (fat jar)
   ▼  (antrun copy)
server-dist/ubnf-lsp-server.jar
   ▼  (mvn verify — npm install + vsce package)
target/ubnf-lsp-0.1.0.vsix
```

## UBNFLanguageServer features

The generated LSP exposes:

1. **Keyword completion** — 36 UBNF keywords (`grammar`, `token`,
   `@root`, `@mapping`, `@whitespace`, `@interleave`, `@backref`,
   `@typeof`, `@scopeTree`, `@leftAssoc`, `@rightAssoc`,
   `@precedence`, `::=`, `;`, …).
2. **Parse diagnostics** — error position + message.
3. **Semantic tokens** — valid/invalid token classes with extension hooks.
4. **Hover** — "Valid UBNF" or parse error offset.
5. **Grammar-Guided Programming hooks** — extension points for custom
   completions, diagnostics, definition jumps, semantic tokens, and
   server capabilities (`additionalCompletionItems()` etc.).

## UBNFEvaluator

Abstract visitor with one `eval*()` method per AST node type, sealed
`switch` dispatch via pattern matching, plus `DebugStrategy` and
`StepCounterStrategy` interfaces for DAP integration.

## UBNFDebugAdapter

Full DAP server with:

- Token-level and AST-level stepping
- Line-based breakpoints
- Variables view (AST node types, source spans, runtime probes)
- Launch / attach configuration

## Reproducing the bootstrap manually

The reactor build is the supported path. If you ever need to regenerate
the artifacts by hand (e.g. to inspect them under `target/` without
running shade/vsce), use:

```bash
cd /path/to/unlaxer-parser
mvn -B -pl unlaxer-dsl/ubnf-vscode generate-sources
# generated files appear under
#   unlaxer-dsl/ubnf-vscode/target/generated-sources/ubnf/
```
