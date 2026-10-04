const vscode = require('vscode');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

exports.run = async function () {
  const extension = vscode.extensions.getExtension('your-name.ubnf-lsp');
  assert.ok(extension, 'packaged UBNF extension must be present');
  assert.equal(extension.extensionPath, process.env.UBNF_PACKAGED_ROOT);
  await extension.activate();
  assert.ok(extension.isActive, 'packaged runtime dependencies must resolve');
  const catalog = JSON.parse(await fs.readFile(path.join(extension.extensionPath, 'help-dist/catalog.json'), 'utf8'));
  await vscode.commands.executeCommand('ubnfLsp.openHelp');
  const tabDeadline = Date.now() + 10000;
  while (!vscode.window.tabGroups.all.flatMap(group => group.tabs).some(tab => tab.label === 'UBNF · はじめの一歩') && Date.now() < tabDeadline) {
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(vscode.window.tabGroups.all.flatMap(group => group.tabs).some(tab => tab.label === 'UBNF · はじめの一歩'),
    `help webview opens: ${vscode.window.tabGroups.all.flatMap(group => group.tabs).map(tab => tab.label)}`);
  const file = vscode.Uri.file(path.join(process.env.UBNF_PACKAGED_ROOT, '..', 'sample.ubnf'));
  await vscode.workspace.fs.writeFile(file, Buffer.from(catalog.examples.find(item => item.id === 'number').source));
  const document = await vscode.workspace.openTextDocument(file);
  await vscode.window.showTextDocument(document);
  assert.equal(document.languageId, 'ubnf');
  const deadline = Date.now() + 30000;
  let completion;
  do {
    completion = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', file, new vscode.Position(4, 18));
    if (completion?.items.some(item => item.label === 'CHAR_RANGE' || item.label?.label === 'CHAR_RANGE')) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  assert.ok(completion?.items.some(item => item.label === 'CHAR_RANGE' || item.label?.label === 'CHAR_RANGE'), 'bundled Java LSP provides v2 vocabulary');
  let generationTimer;
  const generated = await Promise.race([
    vscode.commands.executeCommand('ubnfLsp.openPlayground'),
    new Promise((_, reject) => {generationTimer = setTimeout(() => reject(new Error('Playground command timed out')), 210000);})
  ]).finally(() => clearTimeout(generationTimer));
  assert.ok(generated?.projectPath, 'generation must finish after the real WASM webview reports ready');
  const wasm = await fs.readFile(path.join(generated.projectPath, 'public/language.wasm'));
  assert.deepEqual([...wasm.subarray(0, 4)], [0, 97, 115, 109]);
  assert.ok(vscode.window.tabGroups.all.flatMap(group => group.tabs).some(tab => tab.label.startsWith('UBNF Playground')));
  assert.equal(await fs.readFile(file.fsPath, 'utf8'), catalog.examples.find(item => item.id === 'number').source);
  console.log('Packaged VSIX: activation, catalog, LSP, generation, WASM build and live playground webview passed');
};
