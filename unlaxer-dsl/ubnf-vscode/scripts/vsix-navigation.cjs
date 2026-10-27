const vscode = require('vscode');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');

function range(r) { return [r.start.line, r.start.character, r.end.line, r.end.character]; }
function outline(s) {
  return { name: s.name, kind: s.kind + 1, range: range(s.range), selectionRange: range(s.selectionRange),
    ...(s.children.length ? {children: s.children.map(outline)} : s.kind === vscode.SymbolKind.Namespace ? {children: []} : {}) };
}
exports.verify = async function () {
  const fixtures = JSON.parse(await fs.readFile(path.join(__dirname, '../../../spec-corpus/ubnf-authoring-lsp/navigation.json'), 'utf8'));
  const fixture = fixtures.find(c => c.id === 'rule-binding-order');
  const file = vscode.Uri.file(path.join(process.env.UBNF_PACKAGED_ROOT, '..', 'navigation space 😀.ubnf'));
  await vscode.workspace.fs.writeFile(file, Buffer.from(fixture.source));
  const document = await vscode.workspace.openTextDocument(file);
  await vscode.window.showTextDocument(document);
  const position = new vscode.Position(1, 17);
  const defs = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', file, position);
  assert.deepEqual(defs.map(d => [d.uri.toString(), range(d.range)]), [[file.toString(), [2, 1, 2, 5]]]);
  const refs = await vscode.commands.executeCommand('vscode.executeReferenceProvider', file, position);
  assert.deepEqual(refs.map(r => range(r.range)), [[1, 17, 1, 21], [1, 22, 1, 26], [2, 1, 2, 5]]);
  const edits = await vscode.commands.executeCommand('vscode.executeDocumentRenameProvider', file, position, 'Renamed_2');
  assert.deepEqual(edits.get(file).map(e => [range(e.range), e.newText]), fixture.bindings[0].references.map(r => [r, 'Renamed_2']));
  const symbols = await vscode.commands.executeCommand('vscode.executeDocumentSymbolProvider', file);
  assert.deepEqual(symbols.map(outline), fixture.symbols);
  const folds = await vscode.commands.executeCommand('vscode.executeFoldingRangeProvider', file);
  assert.deepEqual(folds.map(f => [f.start, f.end]), [[0, 3]]);
  // VS Code exposes this provider through its internal model/position command.
  const linked = await vscode.commands.executeCommand('_executeLinkedEditingProvider', file, {lineNumber: 2, column: 18});
  assert.deepEqual(linked.ranges.map(r => [r.startLineNumber - 1, r.startColumn - 1, r.endLineNumber - 1, r.endColumn - 1]), fixture.bindings[0].references);
  // Readonly package definition -> registered ubnf/packageSource content provider.
  // No release/install step: this is a private fixture inside the smoke scratch.
  const folder = path.join(process.env.UBNF_PACKAGED_ROOT, '..', 'package-navigation');
  const entry = 'layout space 😀.ubnf';
  const source = "grammar Layout { @ubnf: v2 token GAP ::= ' '+; }";
  const raw = JSON.stringify({schemaVersion: 1, id: 'team/layout', version: '1.2.3', entry, files: {[entry]: source}, dependencies: {}});
  const digest = crypto.createHash('sha256').update(raw).digest('hex');
  await fs.mkdir(path.join(folder, '.ubnf-cache/packages'), {recursive: true});
  await fs.writeFile(path.join(folder, '.ubnf-cache/packages', `${digest}.json`), raw);
  await fs.writeFile(path.join(folder, 'ubnf.json'), JSON.stringify({schemaVersion: 1, dependencies: {'team/layout': {version: '1.2.3', source: 'local:never-fetch.json'}}}));
  await fs.writeFile(path.join(folder, 'ubnf.lock.json'), JSON.stringify({schemaVersion: 1, roots: {'team/layout': '1.2.3'}, packages: {'team/layout': {id: 'team/layout', version: '1.2.3', sha256: digest, dependencies: {}}}}));
  const main = vscode.Uri.file(path.join(folder, 'main.ubnf'));
  await vscode.workspace.fs.writeFile(main, Buffer.from("grammar Main {\n @import layout from 'pkg:team/layout'\n @ubnf: v2\n @root Start ::= layout.GAP;\n}"));
  const imported = await vscode.workspace.openTextDocument(main);
  await vscode.window.showTextDocument(imported);
  const [definition] = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', main, new vscode.Position(3, 20));
  assert.equal(definition.uri.scheme, 'ubnf-package');
  assert.equal(definition.uri.path, `/${digest}/${entry}`);
  assert.deepEqual(range(definition.range), [0, 33, 0, 36]);
  const readonly = await vscode.workspace.openTextDocument(definition.uri);
  assert.equal(readonly.getText(), source);
  assert.equal(readonly.uri.scheme, 'ubnf-package');
  // The extension's file-only selector keeps the readonly package document out
  // of didOpen; the server serves precisely the verified source snapshot.
  assert.equal(await fs.readFile(path.join(folder, '.ubnf-cache/packages', `${digest}.json`), 'utf8'), raw);
  console.log('Packaged VSIX: definition/references, AST rename, outline, folding, linked editing and readonly verified package source passed');
};
