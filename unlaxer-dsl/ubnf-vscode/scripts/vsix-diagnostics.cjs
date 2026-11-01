const vscode = require('vscode');
const assert = require('node:assert/strict');
const path = require('node:path');

// Run the same consumer assertions against each actual packaged backend.
exports.verify = async function () {
  const uri = vscode.Uri.file(path.join(process.env.UBNF_PACKAGED_ROOT, '..', 'validator 😀.ubnf'));
  await vscode.workspace.fs.writeFile(uri, Buffer.from("// 😀\r\ngrammar G {\r\n Start ::= Missing;\r\n}"));
  const document = await vscode.workspace.openTextDocument(uri);
  const editor = await vscode.window.showTextDocument(document);
  async function diagnostics(count, matches = () => true) {
    const deadline = Date.now() + 30000;
    do {
      const values = vscode.languages.getDiagnostics(uri);
      if (values.length === count && matches(values)) return values;
      await new Promise(resolve => setTimeout(resolve, 100));
    } while (Date.now() < deadline);
    throw new Error(`validator diagnostics did not reach ${count}`);
  }
  const values = await diagnostics(2);
  assert.deepEqual(values.map(d => ({code:d.code, severity:d.severity, source:d.source, message:d.message,
    range:[d.range.start.line,d.range.start.character,d.range.end.line,d.range.end.character]})), [
    {code:'W-GENERAL-NO-ROOT',severity:vscode.DiagnosticSeverity.Warning,source:'ubnf-validator',
      message:'grammar G has no @root rule — Add @root to at least one entry rule.',range:[0,0,0,1]},
    {code:'E-RULE-UNDEFINED',severity:vscode.DiagnosticSeverity.Error,source:'ubnf-validator',
      message:"rule Start references undefined rule or token 'Missing' — Define a rule or token named 'Missing'.",range:[2,1,2,6]}
  ]);
  await editor.edit(edit => edit.replace(new vscode.Range(0,0,document.lineCount,0), "grammar G { @root Start ::= 'x'; }"));
  await diagnostics(0);
  // #894: a complete first grammar followed by a supplementary invalid scalar.
  // The hover reports the root's consumed UTF-16 prefix, while the diagnostic
  // highlights one complete scalar. Data/absent keys are checked over stdio.
  await editor.edit(edit => edit.replace(new vscode.Range(0,0,document.lineCount,0), "grammar A {}\r\n😀"));
  const syntax = await diagnostics(1);
  assert.deepEqual(syntax.map(d => ({code:d.code, severity:d.severity, source:d.source, message:d.message,
    range:[d.range.start.line,d.range.start.character,d.range.end.line,d.range.end.character]})), [
    {code:'ULX-PARSE-001',severity:vscode.DiagnosticSeverity.Error,source:'unlaxer',
      message:"Expected ' ' or '//' or 'grammar' or '{' or '}' (in WordParser)",range:[1,0,1,2]}
  ]);
  const hover = await vscode.commands.executeCommand('vscode.executeHoverProvider', uri, new vscode.Position(1, 2));
  assert.deepEqual(hover.flatMap(h => h.contents.map(c => c.value)), ['Parse error at offset 14']);
  // #1024: the header replay independently reaches the opening-brace attempt.
  await editor.edit(edit => edit.replace(new vscode.Range(0,0,document.lineCount,0), "grammar Main 😀"));
  const headerMessage = "Expected ' ' or '//' or '{' (in WordParser)";
  const header = await diagnostics(1, values => values[0].message === headerMessage);
  assert.deepEqual(header.map(d => ({code:d.code, severity:d.severity, source:d.source, message:d.message,
    range:[d.range.start.line,d.range.start.character,d.range.end.line,d.range.end.character]})), [
    {code:'ULX-PARSE-001',severity:vscode.DiagnosticSeverity.Error,source:'unlaxer',
      message:headerMessage,range:[0,13,0,15]}
  ]);
  await editor.edit(edit => edit.replace(new vscode.Range(0,0,document.lineCount,0), "grammar G { @root Start ::= 'x'; }"));
  await diagnostics(0);
  await document.save();
  console.log('Packaged validator: Java/Rust shared warning/error, messages, CRLF/UTF-16 ranges and repair passed');
};
