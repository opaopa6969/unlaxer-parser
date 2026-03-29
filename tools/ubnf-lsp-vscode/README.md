# UBNF Language Support for VSCode

Syntax highlighting, completion, and diagnostics for UBNF (Unlaxer BNF) grammar files.

## Features

### Phase 1 (current)
- TextMate grammar-based syntax highlighting for `.ubnf` files
- Bracket matching and auto-closing
- Comment toggling (`//`)
- Code folding for `{ }` blocks

### Phase 2 (planned)
- LSP-based go-to-definition for rule references
- Diagnostics: `@mapping` params vs capture name validation
- Diagnostics: undefined rule reference detection
- Diagnostics: duplicate rule name detection

### Phase 3 (planned)
- Rule name completion
- Annotation name completion
- Hover: show rule body on hover

### Phase 4 (planned)
- Railroad Diagram preview (WebView panel)

## Syntax Highlighting

The extension highlights:
- **Keywords**: `grammar`, `token`, `::=`
- **Annotations**: `@root`, `@mapping`, `@leftAssoc`, `@rightAssoc`, `@precedence`, `@eval`, `@interleave`, `@whitespace`, `@declares`, `@backref`, `@catalog`, `@scopeTree`, `@skip`, `@doc`, `@typeof`
- **Rule names**: identifiers before `::=`
- **Terminal strings**: `'quoted'`
- **Capture names**: `@name` in rule bodies
- **Comments**: `// line comments`
- **Token declarations**: `token NAME = ParserClass`
- **Annotation parameters**: `params=[...]`, `kind='...'`, `strategy='...'`

## Installation

### From source (development)
```bash
cd tools/ubnf-lsp-vscode
npm install -g @vscode/vsce
vsce package
code --install-extension ubnf-lsp-0.1.0.vsix
```

## Requirements

- VSCode 1.75.0 or later
- Phase 2+: JDK 17+ (for LSP server)
