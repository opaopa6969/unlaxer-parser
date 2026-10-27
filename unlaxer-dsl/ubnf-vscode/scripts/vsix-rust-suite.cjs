const vscode = require('vscode');
const assert = require('node:assert/strict');
const path = require('node:path');

async function waitFor(action, predicate, name) {
  const deadline = Date.now() + 30000;
  do {
    const result = await action();
    if (predicate(result)) return result;
    await new Promise(resolve => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  throw new Error(`${name} timed out`);
}
exports.run = async function () {
  const extension = vscode.extensions.getExtension('your-name.ubnf-lsp');
  assert.equal(extension.extensionPath, process.env.UBNF_PACKAGED_ROOT);
  assert.equal(vscode.workspace.getConfiguration('ubnfLsp').get('server.backend'), 'rust');
  assert.equal(vscode.workspace.getConfiguration('ubnfLsp').get('server.javaPath'), '/nonexistent-java');
  await extension.activate();
  const file = vscode.Uri.file(path.join(process.env.UBNF_PACKAGED_ROOT, '..', 'native space 😀.ubnf'));
  const source = "grammar Main {\r\n @root @mapping(Value, params=[value])\r\n Start ::= '😀' Item @value;\r\n Item ::= 'x';\r\n}";
  await vscode.workspace.fs.writeFile(file, Buffer.from(source));
  const document = await vscode.workspace.openTextDocument(file);
  const editor = await vscode.window.showTextDocument(document);
  const completion = await waitFor(
    () => vscode.commands.executeCommand('vscode.executeCompletionItemProvider', file, new vscode.Position(2, 20)),
    c => c?.items.some(i => i.label === 'Item' || i.label?.label === 'Item'), 'Rust completion');
  assert.ok(completion.items.some(i => i.label === 'Start' || i.label?.label === 'Start'));
  const hover = await vscode.commands.executeCommand('vscode.executeHoverProvider', file, new vscode.Position(2, 17));
  assert.ok(hover.some(h => h.contents.some(c => c.value.includes('**rule Item**'))));
  const definitions = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', file, new vscode.Position(2, 17));
  assert.equal(definitions?.length ?? 0, 0, 'Rust first stage must not advertise definition');
  await editor.edit(edit => edit.replace(new vscode.Range(0, 0, document.lineCount, 0), 'grammar Broken {'));
  await waitFor(() => vscode.languages.getDiagnostics(file), ds => ds.some(d => d.code === 'ULX-PARSE-001'), 'native diagnostics');
  await editor.edit(edit => edit.replace(new vscode.Range(0, 0, document.lineCount, 0), source));
  await waitFor(() => vscode.languages.getDiagnostics(file), ds => ds.length === 0, 'diagnostics after repair');
  console.log('Packaged VSIX: Rust selection with invalid Java path, completion, hover, UTF-16, change diagnostics and capability boundary passed');
};
