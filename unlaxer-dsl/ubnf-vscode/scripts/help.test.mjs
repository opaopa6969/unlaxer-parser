import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

test('webview host only opens bundled examples in new documents', async () => {
  const root = fileURLToPath(new URL('../', import.meta.url));
  let command, handler, panel, disposal;
  const opened = [], shown = [], commands = [];
  const uri = fsPath => ({fsPath});
  const vscode = {
    Uri: {joinPath: (base, ...parts) => uri(path.join(base.fsPath, ...parts))},
    ViewColumn: {One: 1, Beside: -2},
    commands: {registerCommand: (_id, fn) => {command = fn; return {}; }, executeCommand: async id => commands.push(id)},
    window: {
      createWebviewPanel: (_id, _title, _column, options) => {
        assert.deepEqual(JSON.parse(JSON.stringify(options.localResourceRoots)), [uri(path.join(root, 'help-dist'))]);
        panel = {onDidDispose: fn => { disposal = fn; }, reveal() {}, dispose() { disposal(); }, webview: {
          cspSource: 'https://test.invalid', asWebviewUri: value => ({toString: () => `https://test.invalid/${path.basename(value.fsPath)}`}),
          onDidReceiveMessage: fn => {handler = fn;}
        }};
        return panel;
      },
      showTextDocument: async (...args) => shown.push(args),
      showErrorMessage: message => assert.fail(message)
    },
    workspace: {openTextDocument: async value => {opened.push(value); return value;}}
  };
  const sandbox = {exports: {}, require: name => name === 'vscode' ? vscode : name === 'node:fs/promises' ? {readFile} : assert.fail(name)};
  vm.runInNewContext(await readFile(path.join(root, 'out/help.js'), 'utf8'), sandbox);
  sandbox.exports.registerHelp({extensionUri: uri(root), subscriptions: []});
  await command();
  assert.match(panel.webview.html, /default-src 'none'/);
  assert.match(panel.webview.html, /data-catalog="https:\/\/test.invalid\/catalog.json"/);
  assert.doesNotMatch(panel.webview.html, /unsafe-inline|unsafe-eval/);
  for (const message of [null, 'bad', {}, {type:'command', id:'hello'}, {type:'openExample', id:'../../file'}, {type:'openExample', id:3}]) await handler(message);
  assert.equal(opened.length, 0);
  await handler({type: 'openExample', id: 'hello', source: 'untrusted'});
  assert.equal(opened.length, 1);
  assert.match(opened[0].content, /^grammar Hello/);
  assert.equal(opened[0].language, 'ubnf');
  assert.equal(shown[0][1], 1);
  assert.equal('uri' in opened[0], false, 'never writes over an existing document');
  await handler({type: 'generatePlayground', command: 'untrusted', path: '/untrusted'});
  assert.deepEqual(commands, ['ubnfLsp.openPlayground'], 'only the fixed host command is allowed');
});
