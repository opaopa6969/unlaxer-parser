const vscode = require('vscode');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');

// Direct children of this extension host running the configured native executable.
function nativeChildren(executable) {
  const real = fs.realpathSync(executable);
  return fs.readdirSync('/proc').filter(name => /^\d+$/.test(name)).filter(pid => {
    try {
      const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
      return Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]) === process.pid
        && fs.readlinkSync(`/proc/${pid}/exe`) === real;
    } catch { return false; }
  }).map(Number);
}
function dead(pid) {
  try { process.kill(pid, 0); return false; } catch { return true; }
}

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
  const rustPath = vscode.workspace.getConfiguration('ubnfLsp').get('server.rustPath');
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
  const [server] = await waitFor(() => nativeChildren(rustPath), pids => pids.length === 1, 'one native server child process');
  const hover = await vscode.commands.executeCommand('vscode.executeHoverProvider', file, new vscode.Position(2, 17));
  assert.ok(hover.some(h => h.contents.some(c => c.value.includes('**rule Item**'))));
  const definitions = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', file, new vscode.Position(2, 17));
  assert.equal(definitions?.length ?? 0, 0, 'Rust first stage must not advertise definition');
  await editor.edit(edit => edit.replace(new vscode.Range(0, 0, document.lineCount, 0), 'grammar Broken {'));
  await waitFor(() => vscode.languages.getDiagnostics(file), ds => ds.some(d => d.code === 'ULX-PARSE-001'), 'native diagnostics');
  await editor.edit(edit => edit.replace(new vscode.Range(0, 0, document.lineCount, 0), source));
  await waitFor(() => vscode.languages.getDiagnostics(file), ds => ds.length === 0, 'diagnostics after repair');
  // Same cleanup bar as tinycalc-vscode (#874/#880): deactivate stops the owned process.
  await require(path.join(extension.extensionPath, 'out/extension.js')).deactivate();
  await waitFor(() => dead(server), Boolean, 'native server exit after deactivate');
  assert.deepEqual(nativeChildren(rustPath), [], 'no native server child remains');
  console.log('Packaged VSIX: Rust selection with invalid Java path, completion, hover, UTF-16, change diagnostics, capability boundary and process cleanup passed');
};
