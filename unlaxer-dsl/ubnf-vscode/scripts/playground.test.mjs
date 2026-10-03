import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {readFile, mkdtemp, mkdir, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const require = createRequire(import.meta.url);
const {CommandRunner} = require('../out/command-runner.js');
const code = await readFile(path.join(root, 'out/playground.js'), 'utf8');
const sourceHtml = await readFile(path.join(root, '../src/main/resources/playground/index.html'), 'utf8');
function load(vscode, Runner = CommandRunner) {
  const context = {exports: {}, process, setTimeout, clearTimeout,
    require: name => name === 'vscode' ? vscode : name === './command-runner' ? {CommandRunner: Runner} : require(name)};
  vm.runInNewContext(code, context);
  return context.exports;
}

test('webview uses only local resource URIs, escaped attributes and restrictive CSP', () => {
  const html = load({}).playgroundHtml(sourceHtml, 'https://webview.invalid', file => `https://webview.invalid/${file}?q="<&`);
  assert.match(html, /script-src https:\/\/webview.invalid 'wasm-unsafe-eval'/);
  assert.match(html, /worker-src blob:/);
  assert.doesNotMatch(html, /unsafe-inline|script-src[^;]*'unsafe-eval'/);
  for (const file of ['language.wasm', 'worker.js', 'grammar.ubnf', 'playground.js']) {
    assert.ok(html.includes(`${file}?q=&quot;&lt;&amp;`));
  }
  assert.ok(html.includes('href="#"'));
});

test('runner preserves arguments without a shell and reports exit/start/timeout/dispose failures', async () => {
  const runner = new CommandRunner();
  let output = '';
  try {
    const literal = 'space $(must-not-run) ; " & < >';
    await runner.run(process.execPath, ['-e', 'process.stdout.write(process.argv[1])', literal], root, text => output += text);
    assert.equal(output, literal);
    await assert.rejects(runner.run(process.execPath, ['-e', 'console.error("build detail");process.exit(7)'], root, () => {}), /exit 7[\s\S]*build detail/);
    await assert.rejects(runner.run(path.join(root, 'nonexistent-executable'), [], root, () => {}), /コマンドを起動できません/);
    await assert.rejects(runner.run(process.execPath, ['-e', 'setInterval(()=>{},1000)'], root, () => {}, 100), /停止しました/);
    const pending = runner.run(process.execPath, ['-e', 'setInterval(()=>{},1000)'], root, () => {});
    const rejected = assert.rejects(pending, /失敗しました/);
    runner.dispose();
    await rejected;
    await assert.rejects(runner.run(process.execPath, [], root, () => {}), /終了しています/);
  } finally { runner.dispose(); }
});

test('generation guards trust and saved inputs; explicit commands open a local WASM webview', async () => {
  const scratch = await mkdtemp(path.join(tmpdir(), 'ubnf-playground-host-'));
  const commands = [], runs = [], errors = [], logs = [];
  let execute, listener, panel, progress;
  const uri = fsPath => ({fsPath, scheme: 'file', toString: () => `file://${fsPath}`});
  const document = {uri: uri(path.join(scratch, '文法 $(literal).ubnf')), languageId: 'ubnf', isDirty: false, isUntitled: false};
  document.fileName = document.uri.fsPath;
  const output = {append: text => logs.push(text), appendLine: text => logs.push(text), show() {}};
  class Runner {
    dispose() {}
    async run(executable, args, cwd, append, timeout, env) {
      runs.push({executable, args: [...args], cwd, timeout, env});
      if (runs.length === 1) {
        const destination = args.at(-1);
        await mkdir(path.join(destination, 'public'), {recursive: true});
        await writeFile(path.join(destination, 'public/index.html'), sourceHtml);
      }
    }
  }
  const vscode = {
    Uri: {file: uri, joinPath: (base, ...parts) => uri(path.join(base.fsPath, ...parts))},
    ProgressLocation: {Notification: 15}, ViewColumn: {Beside: 2},
    commands: {registerCommand: (name, fn) => {assert.equal(name, 'ubnfLsp.openPlayground'); execute = fn; return {};}, executeCommand: async name => commands.push(name)},
    workspace: {isTrusted: false, textDocuments: [document], getConfiguration: () => ({get: (key, fallback) => key === 'playground.cargoPath' ? '/custom/cargo' : fallback})},
    window: {
      activeTextEditor: {document}, onDidChangeActiveTextEditor: () => ({}),
      showErrorMessage: async text => {errors.push(text);},
      withProgress: async (_options, action) => action({report: value => {progress = value.message;}}),
      createWebviewPanel: (_kind, _title, _column, options) => {
        assert.equal(options.localResourceRoots.length, 1);
        panel = {dispose() {}, onDidDispose: () => ({}), webview: {
          cspSource: 'https://webview.invalid', asWebviewUri: value => ({toString: () => `https://webview.invalid/${path.basename(value.fsPath)}`}),
          onDidReceiveMessage: fn => {listener = fn; return {};},
          set html(value) {assert.match(value, /data-wasm=/); queueMicrotask(() => listener({type: 'ready'}));}
        }};
        return panel;
      }
    }
  };
  try {
    load(vscode, Runner).registerPlayground({globalStorageUri: uri(scratch), subscriptions: []}, {javaPath: '/java21', jarPath: '/bundled.jar', jvmArgs: [], output});
    assert.equal(await execute(), undefined);
    assert.match(errors.at(-1), /信頼したワークスペース/);
    assert.equal(runs.length, 0);
    vscode.workspace.isTrusted = true;
    document.isDirty = true; document.save = async () => false;
    assert.equal(await execute(), undefined);
    assert.match(errors.at(-1), /保存してから/);
    assert.equal(runs.length, 0);
    document.isDirty = false;
    vscode.workspace.textDocuments.push({...document, uri: uri('/another.ubnf'), isDirty: true});
    assert.equal(await execute(), undefined);
    assert.match(errors.at(-1), /未保存/);
    assert.equal(runs.length, 0);
    vscode.workspace.textDocuments.pop();
    const result = await execute();
    assert.ok(result.projectPath.startsWith(scratch));
    assert.deepEqual(runs[0].args, ['--enable-preview', '-cp', '/bundled.jar', 'org.unlaxer.dsl.CodegenMain', 'playground', '--grammar', document.fileName, '--output', result.projectPath]);
    assert.equal(runs[1].env.UBNF_CARGO, '/custom/cargo');
    assert.equal(runs[1].executable, 'node');
    assert.match(progress, /WebAssembly/);
    assert.ok(logs.some(text => text.includes('WASM is ready')));
    listener({type: 'runCommand', command: 'arbitrary'});
    listener({type: 'openHelp', command: 'arbitrary'});
    assert.deepEqual(commands, ['ubnfLsp.openHelp']);
  } finally { await rm(scratch, {recursive: true, force: true}); }
});
