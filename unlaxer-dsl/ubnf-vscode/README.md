# UBNF VS Code Extension — `unlaxer` editing `unlaxer`

This is the VS Code extension for `.ubnf` files — and it is **generated
from `ubnf.ubnf`**, the meta-grammar that describes the UBNF syntax in
UBNF itself.

In other words: this directory is where unlaxer eats its own dog food.
Every keyword completion, every diagnostic, every "go to definition"
that this extension provides was synthesized by feeding `ubnf.ubnf`
through the same code generators that ship to end-users via
`unlaxer init`.

## Why this exists

`.ubnf` files are unlaxer's primary input. Until this extension, editing
them meant trial-and-error against `mvn package` to find typos. With
this extension installed:

- Real-time parse diagnostics
- Keyword completion (`grammar`, `token`, `@root`, `@mapping`, …)
- Go-to-definition for rule references (`@backref`)
- Hover with parse status
- Semantic tokens (valid / invalid)

And — more importantly — it proves the unlaxer pipeline is **complete
enough to specify itself**.

## How it is built

```
unlaxer-dsl/grammar/ubnf.ubnf   ← source of truth
       │
       ▼  CodegenMain --generators LSP,Launcher
target/generated-sources/ubnf/org/unlaxer/dsl/bootstrap/generated/
       │ UBNFLanguageServer.java
       │ UBNFLspLauncher.java
       ▼  maven-shade-plugin
target/ubnf-lsp-server.jar (fat jar)
       ▼  antrun copy
server-dist/ubnf-lsp-server.jar
       ▼  npm install + vsce package
target/ubnf-lsp-0.1.0.vsix     ← install this in VS Code
```

All of this is wrapped in `pom.xml` so a top-level `mvn -B package`
produces the VSIX.

## Quick start

```bash
# from repo root
mvn -B install -DskipTests              # builds + installs into local m2
cd unlaxer-dsl/ubnf-vscode
mvn -B verify -Dgpg.skip=true           # produces target/ubnf-lsp-0.1.0.vsix

# install the extension (requires VS Code on PATH)
code --install-extension target/ubnf-lsp-0.1.0.vsix

# open any .ubnf file (e.g. unlaxer-dsl/grammar/ubnf.ubnf itself!)
```

## How it relates to `unlaxer init`

`unlaxer init <name>` (Issue #5) generates a brand-new VS Code extension
scaffold for an arbitrary DSL. This `ubnf-vscode/` is the **canonical
worked example** of that same pattern, applied to UBNF itself. The
template under `unlaxer-dsl/src/main/resources/scaffold/` and this
directory share the same shape: pom.xml (codegen + shade + vsce), a
`vscode-extension/` subdirectory, an `IMPLEMENTATION` doc.

## Historical record

The original self-hosting milestone (the moment `ubnf.ubnf` first
generated a working `UBNFLanguageServer`) is documented in
[`docs/ubnf-self-hosting.md`](../../docs/ubnf-self-hosting.md).

## Related issues

- opaopa6969/unlaxer-parser#4 — Grammar Editor LSP (this extension)
- opaopa6969/unlaxer-parser#5 — `unlaxer init` scaffold (the template)
- opaopa6969/unlaxer-parser#8 — replacing the hand-written
  `UBNFParsers.java` with the generated one
