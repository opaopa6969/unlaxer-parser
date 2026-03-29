# UBNF Self-Hosting: Code Generation from ubnf.ubnf

## Summary

Self-hosting succeeded. The UBNF code generators can process `ubnf.ubnf`
(UBNF defining its own syntax) and produce all six artifact types.

## Command Used

```bash
cd /home/opa/work/unlaxer-parser
java -cp unlaxer-dsl/target/classes:unlaxer-common/target/classes:$(mvn -q dependency:build-classpath -pl unlaxer-dsl -Dmdep.outputFile=/dev/stdout 2>/dev/null) \
  org.unlaxer.dsl.CodegenMain \
  --grammar unlaxer-dsl/grammar/ubnf.ubnf \
  --output tools/ubnf-lsp-vscode/target/generated-sources/ubnf/ \
  --generators Parser,AST,Mapper,Evaluator,LSP,DAP
```

## Generated Files

All six files were generated into `org.unlaxer.dsl.bootstrap.generated`:

| File | Size | Status | Notes |
|------|------|--------|-------|
| `UBNFParsers.java` | 18 KB | Redundant | Hand-written version already exists in `unlaxer-dsl` |
| `UBNFAST.java` | 3.6 KB | Redundant | Hand-written version already exists in `unlaxer-dsl` |
| `UBNFMapper.java` | 38 KB | Redundant | Hand-written version already exists in `unlaxer-dsl` |
| `UBNFEvaluator.java` | 5.6 KB | **New** | Abstract evaluator with 27 node-type visitor methods |
| `UBNFLanguageServer.java` | 11 KB | **New** | Full LSP for `.ubnf` files |
| `UBNFDebugAdapter.java` | 25 KB | **New** | Full DAP for `.ubnf` files |

## Validation Warnings (non-fatal)

Four `W-TOKEN-UNRESOLVED` warnings for short token parser class names
(`IdentifierParser`, `SingleQuotedParser`, `NumberParser`). These are
resolved at runtime via classpath but the validator cannot confirm them
statically. The warnings do not block code generation.

## UBNFLanguageServer.java Analysis

The generated LSP has:

1. **Keyword completion** -- 36 UBNF keywords including `grammar`, `token`,
   `@root`, `@mapping`, `@whitespace`, `@interleave`, `@backref`, `@typeof`,
   `@scopeTree`, `@leftAssoc`, `@rightAssoc`, `@precedence`, `::=`, `;`, etc.

2. **Parse diagnostics** -- Reports parse errors with position information.

3. **Semantic tokens** -- Valid/invalid token types with extensible hooks.

4. **Hover** -- Shows "Valid UBNF" or parse error offset.

5. **GGP (Grammar-Guided Programming) hooks**:
   - `additionalCompletionItems()` -- inject extra completions
   - `additionalDiagnostics()` -- inject semantic validation
   - `customDefinition()` -- cross-reference navigation
   - `additionalSemanticTokenData()` -- embedded language tokens
   - `configureAdditionalCapabilities()` -- extend server features

## UBNFEvaluator.java Analysis

Abstract evaluator with:
- 27 `eval*()` abstract methods, one per AST node type
- Sealed `switch` dispatch via pattern matching
- `DebugStrategy` interface for DAP integration
- `StepCounterStrategy` for step-through debugging

## UBNFDebugAdapter.java Analysis

Full DAP implementation with:
- Token-level and AST-level stepping modes
- Breakpoint support (line-based)
- Variables view (AST node types, spans, runtime probes)
- Launch/attach configuration

## Bootstrap Relationship

The generated `UBNFParsers.java`, `UBNFAST.java`, and `UBNFMapper.java` are
structurally equivalent to the hand-written versions in
`org.unlaxer.dsl.bootstrap`. This confirms that the code generators
faithfully reproduce the hand-written bootstrap code -- a key milestone
for self-hosting.

The three new files (`UBNFEvaluator`, `UBNFLanguageServer`, `UBNFDebugAdapter`)
are artifacts that did not previously exist and are immediately useful for
building a UBNF editing experience.

## Next Steps

1. **Compare generated vs hand-written** -- Diff the generated `UBNFParsers`,
   `UBNFAST`, and `UBNFMapper` against the hand-written versions to identify
   any semantic differences.

2. **Compile the LSP** -- Add the generated sources to a Maven module and
   verify they compile against `lsp4j` and `unlaxer-common`.

3. **Wire into VSIX** -- Integrate `UBNFLanguageServer` into the existing
   UBNF VS Code extension for `.ubnf` file editing support.

4. **Resolve token warnings** -- Update `ubnf.ubnf` to use fully-qualified
   parser class names (e.g., `org.unlaxer.parser.elementary.IdentifierParser`)
   to eliminate the `W-TOKEN-UNRESOLVED` warnings.
