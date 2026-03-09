# Calculator DSL (LSP) - VS Code Extension

This is a minimal VS Code extension that launches a Java LSP server (LSP4J) via stdio.

## What is included
- VS Code client (TypeScript) using `vscode-languageclient`
- Minimal syntax highlighting for `.calc`
- A Maven module (`server/`) that builds a runnable jar for the LSP server
- A helper script that copies the built jar into `server-dist/`

## Quick start (dev)
1. Install prerequisites:
   - Node.js (LTS)
   - Java 21+
   - Maven

2. Install extension dependencies:
   ```bash
   npm install
   ```

3. Build the language server jar and copy it into the extension:
   ```bash
   npm run build:server
   ```

4. In VS Code, press `F5` to start the Extension Development Host.

5. Create a file `demo.calc` and type expressions like:
   ```
   1 + 2 * (3 + 4)
   ```

## Packaging to .vsix
```bash
npm run package
```

## Configuration
- `calculatorLsp.server.javaPath`: path to java executable (default: `java`)
- `calculatorLsp.server.jarPath`: optional path to an external server jar. If empty, uses the bundled jar in this extension.
- `calculatorLsp.server.jvmArgs`: extra JVM args (e.g. `-Xmx512m`)

## Notes for WSL / Windows
- If you develop in WSL but run VS Code on Windows, prefer launching the server jar with a Windows-side Java, or set `calculatorLsp.server.jarPath` to a jar reachable from Windows.


### Quick try (no build required)

If you just want to try the example without building the extension:

1. Install the VS Code extension from:
   `build/calculator-lsp-0.1.0.vsix`
2. Open a `.calc` file.

For development / rebuilding, see below.
