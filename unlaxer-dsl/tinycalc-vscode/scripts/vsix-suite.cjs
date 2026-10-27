const vscode = require('vscode');
const trace = (event, details={}) => require('node:fs').appendFileSync(process.env.TINYCALC_TIMELINE, JSON.stringify({at:Date.now(),event,...details})+'\n');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, label, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await check(); if (value) return value; await delay(100); }
  throw new Error(`Timed out: ${label}`);
}
async function bounded(promise, label, timeout = 12000) {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), timeout); })]); }
  finally { clearTimeout(timer); }
}
function dead(pid) {
  try { process.kill(pid, 0); return false; }
  catch (error) { if (error.code === 'ESRCH') return true; throw error; }
}
const run = async () => {
  const settings = JSON.parse(process.env.TINYCALC_SETTINGS);
  const config = vscode.workspace.getConfiguration('tinycalcLsp');
  for (const [key, value] of Object.entries(settings)) await config.update(key, value, vscode.ConfigurationTarget.Global);
  const extension = vscode.extensions.getExtension('your-name.tinycalc-lsp');
  assert.ok(extension, 'packaged extension is installed');
  assert.equal(extension.extensionPath, process.env.TINYCALC_PACKAGED_ROOT);
  await vscode.workspace.getConfiguration('editor').update('wordBasedSuggestions', 'off', vscode.ConfigurationTarget.Global);
  await extension.activate();
  const journal = async () => (await fs.readFile(process.env.TINYCALC_PIDS, 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);
  const uri = vscode.Uri.file(path.join(process.env.TINYCALC_SCRATCH, 'sample with spaces.tcalc'));
  await fs.writeFile(uri.fsPath, 'var x set 1;\r\nx + 2');
  const document = await vscode.workspace.openTextDocument(uri);
  await vscode.window.showTextDocument(document);
  assert.equal(document.languageId, 'tinycalc');
  const completion = await until(async () => {
    const items = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', uri, new vscode.Position(1, 0));
    return items?.items.some(item => (item.label.label ?? item.label) === 'var') && items;
  }, 'LSP completion from real generated server');
  await until(async () => {
    const hover = await vscode.commands.executeCommand('vscode.executeHoverProvider', uri, new vscode.Position(1, 0));
    return hover?.some(item => item.contents.some(content => content.value.includes('Valid')));
  }, 'valid hover after document synchronization');
  const definitions = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', uri, new vscode.Position(1, 0));
  assert.deepEqual(definitions ?? [], [], 'default server has no definition provider');
  const observations = {completion: completion.items.map(item => item.label.label ?? item.label).sort(), hoverValid: true, definitions: []};
  const bad = new vscode.WorkspaceEdit(); bad.replace(uri, new vscode.Range(0, 0, 1, 5), 'var x set 1;\r\nx + 😀');
  assert.ok(await vscode.workspace.applyEdit(bad));
  const diagnostics = await until(() => vscode.languages.getDiagnostics(uri).length && vscode.languages.getDiagnostics(uri), 'Unicode syntax diagnostic');
  observations.diagnostics = diagnostics.map(item => ({range: [item.range.start.line, item.range.start.character, item.range.end.line, item.range.end.character], severity: item.severity, code: item.code}));
  assert.equal(observations.diagnostics[0].range[0], 1);
  const repair = new vscode.WorkspaceEdit(); repair.replace(uri, new vscode.Range(0, 0, 1, 6), 'var x set 1;\r\nx + 2');
  assert.ok(await vscode.workspace.applyEdit(repair)); await document.save();
  await until(() => vscode.languages.getDiagnostics(uri).length === 0, 'diagnostics cleared after repair');
  observations.dap = [];
  const terminated = vscode.debug.onDidTerminateDebugSession(session=>trace('session-terminated',{id:session.id,name:session.name}));
  for (const mode of ['ast', 'token']) {
    let stops = 0;
    const tracker = vscode.debug.registerDebugAdapterTrackerFactory('tinycalc', {
      createDebugAdapterTracker: session => ({onWillStopSession:()=>trace('will-stop',{id:session.id,mode}),onExit:code=>trace('adapter-exit',{id:session.id,mode,code}),onWillReceiveMessage:message=>trace('request',{id:session.id,mode,command:message.command}),onDidSendMessage: message => { trace('message',{id:session.id,mode,type:message.type,command:message.command,dapEvent:message.event}); if (message.event === 'stopped') stops++; }})
    });
    try {
      assert.ok(await vscode.debug.startDebugging(undefined, {type: 'tinycalc', request: 'launch', name: `Inspect ${mode}`, program: uri.fsPath, stopOnEntry: true, steppingMode: mode}));
      await until(() => stops > 0 && vscode.debug.activeDebugSession, `${mode} initial stop`);
      const session = vscode.debug.activeDebugSession;
      const stack = await session.customRequest('stackTrace', {threadId: 1});
      assert.equal(stack.stackFrames.length, 1);
      const frame = stack.stackFrames[0];
      assert.equal(frame.source.path, uri.fsPath);
      const scopes = await session.customRequest('scopes', {frameId: frame.id});
      assert.ok(scopes.scopes.length > 0);
      const variables = await session.customRequest('variables', {variablesReference: scopes.scopes[0].variablesReference});
      assert.ok(variables.variables.some(value => value.name === 'steppingMode' && value.value === mode));
      observations.dap.push({mode, line: frame.line, column: frame.column});
      await session.customRequest('next', {threadId: 1});
      await until(() => stops > 1, `${mode} next stop`);
      await vscode.debug.stopDebugging(session);
      await until(() => !vscode.debug.activeDebugSession, `${mode} disconnect`);
    } finally { tracker.dispose(); }
  }
  terminated.dispose();
  const before = (await journal()).filter(row => row.protocol === 'lsp');
  process.kill(before.at(-1).pid, 'SIGKILL');
  await until(async () => (await journal()).filter(row => row.protocol === 'lsp').length > before.length, 'automatic restart after unexpected exit');
  await vscode.commands.executeCommand('tinycalcLsp.restartServer');
  // Invalid settings keep a working process and allow a later correction/restart.
  await config.update('server.backend', 'invalid', vscode.ConfigurationTarget.Global);
  await assert.rejects(vscode.commands.executeCommand('tinycalcLsp.restartServer'));
  await config.update('server.backend', settings['server.backend'], vscode.ConfigurationTarget.Global);
  await vscode.commands.executeCommand('tinycalcLsp.restartServer');
  const unresponsive = process.env.TINYCALC_UNRESPONSIVE;
  assert.ok(unresponsive, 'actual unresponsive executable fixture is configured');
  const restart = () => vscode.commands.executeCommand('tinycalcLsp.restartServer');
  const healthy = async () => {
    trace('healthy-restart-start', {timeout: vscode.workspace.getConfiguration('tinycalcLsp').get('server.startupTimeoutMs')});
    try { await bounded(restart(), 'healthy explicit restart'); }
    catch (error) { trace('healthy-restart-failed', {error: String(error)}); throw error; }
    trace('healthy-restart-complete');
    await until(async () => {
      const hover = await vscode.commands.executeCommand('vscode.executeHoverProvider', uri, new vscode.Position(1, 0));
      return hover?.some(item => item.contents.some(content => content.value.includes('Valid')));
    }, 'functional LSP after interrupted startup');
  };
  // Fail the initialized notification after the real backend has answered initialize.
  // Use an actual destroyed Node stream while keeping close delivery independent: a
  // writer failure may reach the client before its reader reports the connection close.
  const {createRequire} = require('node:module');
  const {PassThrough} = require('node:stream');
  const packagedRequire = createRequire(path.join(extension.extensionPath, 'package.json'));
  const {StreamMessageWriter} = packagedRequire('vscode-jsonrpc/node');
  const {LanguageClient} = packagedRequire('vscode-languageclient/node');
  const originalStop = LanguageClient.prototype.stop;
  let completedShutdowns = 0;
  LanguageClient.prototype.stop = function(timeout) {
    return originalStop.call(this, timeout).then(() => {
      completedShutdowns++;
      trace('normal-lsp-shutdown-completed', {backend: settings['server.backend']});
    });
  };
  const originalWrite = StreamMessageWriter.prototype.write;
  let failedWriter, originalStream, injected = false, prematureShutdowns = 0;
  StreamMessageWriter.prototype.write = function(message) {
    if (!injected && message.method === 'initialized') {
      injected = true;
      failedWriter = this;
      originalStream = this.writable.stream;
      const destroyed = new PassThrough();
      destroyed.destroy();
      this.writable.stream = destroyed;
      trace('injected-initialized-stream-destroyed', {backend: settings['server.backend']});
    }
    if (this === failedWriter && message.method === 'shutdown') {
      prematureShutdowns++;
      trace('premature-shutdown-request');
    }
    return originalWrite.call(this, message);
  };
  try {
    await assert.rejects(bounded(restart(), 'initialized stream failure'), /stream was destroyed/);
    assert.ok(injected, 'real backend completed initialize before the injected transport failure');
    assert.ok(completedShutdowns >= 1, 'the preceding healthy client still completes normal protocol shutdown');
    await delay(1500); // Let Node report any unhandled rejection before asserting the transition.
    assert.equal(prematureShutdowns, 0, 'failed initialization cannot send an LSP shutdown request');
  } finally {
    StreamMessageWriter.prototype.write = originalWrite;
    LanguageClient.prototype.stop = originalStop;
    if (failedWriter) failedWriter.writable.stream = originalStream;
  }
  await healthy();
  observations.initializationFailure = {rejected: true, prematureShutdowns, recovered: true, normalShutdownCompleted: true};
  const startupTimeoutMs = vscode.workspace.getConfiguration('tinycalcLsp').get('server.startupTimeoutMs', 30000);
  const restore = async () => {
    // Restore the normal startup budget before any setting can launch a healthy backend.
    await config.update('server.startupTimeoutMs', startupTimeoutMs, vscode.ConfigurationTarget.Global);
    await config.update('server.javaPath', settings['server.javaPath'] ?? 'java', vscode.ConfigurationTarget.Global);
    await config.update('server.rustLspPath', settings['server.rustLspPath'] ?? '', vscode.ConfigurationTarget.Global);
    await config.update('server.backend', settings['server.backend'], vscode.ConfigurationTarget.Global);
  };
  const hang = async timeout => {
    const offset = (await journal()).length;
    await config.update('server.startupTimeoutMs', timeout, vscode.ConfigurationTarget.Global);
    await config.update(settings['server.backend'] === 'java' ? 'server.javaPath' : 'server.rustLspPath', unresponsive, vscode.ConfigurationTarget.Global);
    await config.update('server.backend', settings['server.backend'], vscode.ConfigurationTarget.Global);
    return until(async () => (await journal()).slice(offset).find(row => row.fixture === 'unresponsive' && row.event === 'initialize' && !dead(row.pid)), 'unresponsive server actually receives initialize');
  };
  const stoppedHangs = async () => until(async () => (await journal()).filter(row => row.fixture === 'unresponsive').every(({pid}) => dead(pid)), 'all unresponsive children terminate', 10000);
  // No initialize response: explicit startup fails with the configured timeout, not an unrelated error.
  trace('timeout-test-start');
  await hang(1000);
  await assert.rejects(bounded(restart(), 'unresponsive startup timeout'), /Server initialization exceeded 1000 ms\./);
  trace('timeout-test-rejected');
  await stoppedHangs();
  assert.ok((await journal()).some(row => row.fixture === 'unresponsive' && row.event === 'sigterm'), 'fixture ignores TERM and exercises bounded force cleanup');
  // A healthy start may take longer than the timeout deliberately used above. Hold
  // completion of the real LanguageClient start past 1000 ms on both backends so
  // forgetting to restore the normal budget fails deterministically, even on fast CI.
  const originalStart = LanguageClient.prototype.start;
  LanguageClient.prototype.start = function() {
    return Promise.all([originalStart.call(this), delay(1500)]).then(() => {});
  };
  try { await restore(); await healthy(); }
  finally { LanguageClient.prototype.start = originalStart; }
  // A configuration correction interrupts a 30-second pending start instead of waiting for it.
  await hang(30000);
  await bounded(restore(), 'configuration update during startup');
  await healthy(); await stoppedHangs();
  // Deactivation also interrupts pending initialize and joins all owned children.
  await hang(30000);
  await vscode.commands.executeCommand('workbench.action.closeAllEditors');
  await bounded(require(path.join(extension.extensionPath, 'out/extension.js')).deactivate(), 'deactivate during pending initialize');
  await stoppedHangs();
  await until(async () => (await journal()).every(({pid}) => dead(pid)), 'all LSP and DAP child processes terminate');
  observations.startupLifecycle = {timeoutRecovered: true, configurationChangeRecovered: true, deactivateStoppedStarting: true};
  await fs.writeFile(process.env.TINYCALC_RESULT, JSON.stringify(observations, null, 2));
};

exports.run = async () => {
  let timer;
  const unhandled = [];
  const record = reason => unhandled.push(String(reason));
  process.on('unhandledRejection', record);
  try {
    await Promise.race([run(), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('IDE smoke exceeded 180 seconds')), 180000); })]);
    await delay(1500);
    assert.deepEqual(unhandled, [], 'startup interruption must not leave unhandled rejections');
  } finally { clearTimeout(timer); process.off('unhandledRejection', record); }
};
