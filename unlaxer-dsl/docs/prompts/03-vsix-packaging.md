# Prompt Template: Package Grammar as VSCode Extension (VSIX)

## System Context

You are packaging an unlaxer-parser grammar and its generated LSP/DAP server as a VSCode extension (.vsix).

Reference: `unlaxer-dsl/docs/llm-reference.md`

## Prompt

Package this grammar as a VSCode extension:

Grammar name: {{GRAMMAR_NAME}}
Language ID: {{LANGUAGE_ID}}
File extension: {{FILE_EXTENSION}}
Package name: {{PACKAGE_NAME}}

Generate the following files:

### 1. package.json

Create a VSCode extension manifest with:
- Language registration (id, aliases, extensions, configuration)
- TextMate grammar reference
- LSP server configuration (javaPath, jarPath, jvmArgs)
- DAP debugger registration (launch with program and stopOnEntry)
- Dependencies: `vscode-languageclient` ^9.0.1
- DevDependencies: `@types/vscode`, `@types/node`, `typescript`, `@vscode/vsce`

Key settings to expose:
```json
"{{LANGUAGE_ID}}Lsp.server.javaPath": "java",
"{{LANGUAGE_ID}}Lsp.server.jarPath": "",
"{{LANGUAGE_ID}}Lsp.server.jvmArgs": []
```

### 2. language-configuration.json

```json
{
  "comments": {
    "lineComment": "//"
  },
  "brackets": [
    ["{", "}"],
    ["[", "]"],
    ["(", ")"]
  ],
  "autoClosingPairs": [
    { "open": "{", "close": "}" },
    { "open": "[", "close": "]" },
    { "open": "(", "close": ")" },
    { "open": "'", "close": "'", "notIn": ["string"] }
  ],
  "surroundingPairs": [
    ["{", "}"],
    ["[", "]"],
    ["(", ")"],
    ["'", "'"]
  ]
}
```

### 3. syntaxes/{{LANGUAGE_ID}}.tmLanguage.json

Create a TextMate grammar with these scopes:
- `comment.line` for `//` comments
- `keyword.control` for control-flow keywords (if, else, match, etc.)
- `keyword.declaration` for declaration keywords (var, variable, import, etc.)
- `keyword.operator` for operators (+, -, *, /, ==, !=, etc.)
- `constant.numeric` for number literals
- `string.quoted.single` for single-quoted strings
- `variable.other` for `$identifier` references
- `entity.name.function` for function/method names
- `constant.language.boolean` for true/false

### 4. src/extension.ts

Create the extension entry point that:
1. Reads LSP configuration from `{{LANGUAGE_ID}}Lsp.server.*`
2. Resolves the JAR path (configured or bundled in `server-dist/`)
3. Starts the LSP client with `vscode-languageclient`
4. Registers a DAP adapter factory that launches the DAP main class via `-cp`
5. Handles `activate()` and `deactivate()` lifecycle

```typescript
// LSP server options
const serverOptions: ServerOptions = {
  command: javaPath,
  args: [...jvmArgs, "--enable-preview", "-jar", jarPath]
};

// DAP adapter (uses -cp, not -jar, to specify main class)
new vscode.DebugAdapterExecutable(javaPath, [
  ...jvmArgs, "--enable-preview",
  "-cp", jarPath,
  "{{PACKAGE_NAME}}.generated.{{GRAMMAR_NAME}}DapLauncher"
]);
```

### 5. tsconfig.json

```json
{
  "compilerOptions": {
    "module": "commonjs",
    "target": "es2020",
    "outDir": "out",
    "lib": ["es2020"],
    "sourceMap": true,
    "rootDir": "src",
    "strict": true
  },
  "exclude": ["node_modules", ".vscode-test"]
}
```

### Build Steps

```bash
# 1. Build the Java LSP/DAP server
cd {{LANGUAGE_ID}}-server
mvn package -DskipTests
mkdir -p ../{{LANGUAGE_ID}}-vscode/server-dist
cp target/{{LANGUAGE_ID}}-lsp-server.jar ../{{LANGUAGE_ID}}-vscode/server-dist/

# 2. Build the extension
cd ../{{LANGUAGE_ID}}-vscode
npm install
npm run compile

# 3. Package as VSIX
npx @vscode/vsce package
# Output: {{LANGUAGE_ID}}-lsp-0.1.0.vsix

# 4. Install
code --install-extension {{LANGUAGE_ID}}-lsp-0.1.0.vsix
```

### Checklist Before Returning

- [ ] `package.json` has correct `engines.vscode` version
- [ ] `activationEvents` matches `onLanguage:{{LANGUAGE_ID}}`
- [ ] Language `id` in contributes matches documentSelector in extension.ts
- [ ] TextMate grammar `scopeName` matches `source.{{LANGUAGE_ID}}`
- [ ] Extension uses `--enable-preview` JVM flag (required for sealed interfaces)
- [ ] DAP factory uses `-cp` (not `-jar`) to specify main class
- [ ] `server-dist/` directory contains the fat JAR
- [ ] `deactivate()` stops the LSP client
