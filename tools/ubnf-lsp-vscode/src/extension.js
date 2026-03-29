const vscode = require('vscode');

/**
 * UBNF Language Support - VSCode Extension
 *
 * Phase 1: TextMate grammar-based syntax highlighting
 * Phase 2 (future): LSP client connecting to Java-based UBNF language server
 */

/**
 * @param {vscode.ExtensionContext} context
 */
function activate(context) {
    console.log('UBNF Language Support activated');

    // Phase 2: LSP client connection will go here
    // The LSP server will be a Java process using unlaxer-parser's
    // UBNFParsers for parsing and UBNFAST for semantic analysis.
    //
    // Features planned for Phase 2:
    // - Go-to-definition for rule references
    // - Diagnostics: @mapping params vs capture name validation
    // - Diagnostics: undefined rule reference detection
    // - Diagnostics: duplicate rule name detection
}

function deactivate() {
    // Phase 2: Stop LSP client here
}

module.exports = {
    activate,
    deactivate
};
