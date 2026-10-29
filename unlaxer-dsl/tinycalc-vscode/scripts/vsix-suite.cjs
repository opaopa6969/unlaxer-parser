const vscode = require('vscode');
const trace = (event, details={}) => require('node:fs').appendFileSync(process.env.TINYCALC_TIMELINE, JSON.stringify({at:Date.now(),event,...details})+'\n');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const childProcess = require('node:child_process');
const originalSpawn = childProcess.spawn;
const {performance} = require('node:perf_hooks');
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
  let ownedLsp;
  const unresponsiveChildren = [];
  // Capture the actual owned wrapper. A fast Rust child can answer initialize
  // before its wrapper has recorded the spawn in the PID journal.
  childProcess.spawn = function(command, args) {
    const child = originalSpawn.apply(this, arguments);
    if (command === process.env.TINYCALC_UNRESPONSIVE) {
      unresponsiveChildren.push(child);
      child.once('spawn', () => trace('unresponsive-spawn', {pid: child.pid}));
      child.once('exit', (code, signal) => trace('unresponsive-exit', {pid: child.pid, code, signal}));
    }
    if (command === settings['server.rustLspPath'] ||
        (command === settings['server.javaPath'] && args?.includes('-jar'))) ownedLsp = child;
    return child;
  };
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
  let completedShutdowns = 0, shutdownDelayMs = 0;
  const shutdowns = [];
  const observeStop = function(timeout) {
    assert.ok(ownedLsp?.pid, 'healthy LSP process was captured at spawn');
    assert.equal(ownedLsp.exitCode, null, 'the observed LSP process has not already exited');
    const started = performance.now();
    const shutdown = {wrapperPid: ownedLsp.pid,
      startedAt: Date.now(), backend: settings['server.backend'], timeout, injectedDelayMs: shutdownDelayMs};
    ownedLsp.once('exit', (code, signal) => {
      shutdown.ownedElapsedMs = Math.round(performance.now() - started);
      shutdown.ownedExitCode = code;
      shutdown.ownedExitSignal = signal;
      trace('normal-lsp-owned-process-exited', shutdown);
    });
    trace('normal-lsp-shutdown-started', shutdown);
    return originalStop.call(this, timeout).then(() => {
      completedShutdowns++;
      shutdowns.push(shutdown);
      trace('normal-lsp-shutdown-completed', {...shutdown, elapsedMs: Math.round(performance.now() - started)});
    }, error => {
      trace('normal-lsp-shutdown-failed', {...shutdown, elapsedMs: Math.round(performance.now() - started), error: String(error)});
      throw error;
    });
  };
  const assertNormalExit = async () => {
    const shutdown = shutdowns.at(-1);
    assert.ok(shutdown, 'a real protocol shutdown completed');
    const events = await until(async () => {
      const rows = (await fs.readFile(process.env.TINYCALC_TIMELINE, 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);
      return rows.some(row => row.pid === shutdown.wrapperPid && row.event === 'wrapper-exit') && rows;
    }, 'normally stopped LSP process and wrapper exit');
    const exit = events.find(row => row.pid === shutdown.wrapperPid && row.event === 'child-exit');
    assert.equal(exit.code, 0, 'normal shutdown exits the real backend with code 0');
    assert.equal(exit.signal, null, 'normal shutdown does not kill the real backend');
    assert.ok(!events.some(row => row.pid === shutdown.wrapperPid && row.event === 'sigterm'), 'normal shutdown must not fall back to SIGTERM');
    assert.equal(shutdown.ownedExitCode, 0, 'normal shutdown joins the owned wrapper with code 0');
    assert.equal(shutdown.ownedExitSignal, null, 'the owned wrapper is not killed');
    trace('normal-lsp-process-exited', {...shutdown, elapsedMs: shutdown.ownedElapsedMs, backendPid: exit.child});
  };
  LanguageClient.prototype.stop = observeStop;
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
    await assertNormalExit();
    await delay(1500); // Let Node report any unhandled rejection before asserting the transition.
    assert.equal(prematureShutdowns, 0, 'failed initialization cannot send an LSP shutdown request');
  } finally {
    StreamMessageWriter.prototype.write = originalWrite;
    LanguageClient.prototype.stop = originalStop;
    if (failedWriter) failedWriter.writable.stream = originalStream;
  }
  await healthy();
  observations.initializationFailure = {rejected: true, prematureShutdowns, recovered: true, normalShutdownCompleted: true};
  // Delay dispatch of a real shutdown request beyond the former 1000 ms budget.
  // Keep the real backend response/exit and force-cleanup assertions: merely
  // delaying stop() resolution would not exercise languageclient's shutdown timer.
  shutdownDelayMs = 1500;
  const completedBeforeDelay = completedShutdowns;
  LanguageClient.prototype.stop = observeStop;
  StreamMessageWriter.prototype.write = function(message) {
    if (message.method === 'shutdown') {
      trace('delayed-shutdown-request', {delayMs: shutdownDelayMs});
      return delay(shutdownDelayMs).then(() => originalWrite.call(this, message));
    }
    return originalWrite.call(this, message);
  };
  try {
    await bounded(restart(), 'delayed normal shutdown');
    assert.ok(completedShutdowns > completedBeforeDelay, 'a shutdown exchange beyond 1000 ms still completes normally');
    await assertNormalExit();
  } finally {
    StreamMessageWriter.prototype.write = originalWrite;
    LanguageClient.prototype.stop = originalStop;
  }
  observations.shutdownLifecycle = {delayedProtocolCompleted: true, exitedNormally: true};
  const startupTimeoutMs = vscode.workspace.getConfiguration('tinycalcLsp').get('server.startupTimeoutMs', 30000);
  const restore = async () => {
    // Restore the normal startup budget before any setting can launch a healthy backend.
    await config.update('server.startupTimeoutMs', startupTimeoutMs, vscode.ConfigurationTarget.Global);
    await config.update('server.javaPath', settings['server.javaPath'] ?? 'java', vscode.ConfigurationTarget.Global);
    await config.update('server.rustLspPath', settings['server.rustLspPath'] ?? '', vscode.ConfigurationTarget.Global);
    await config.update('server.backend', settings['server.backend'], vscode.ConfigurationTarget.Global);
  };
  const configureHang = async timeout => {
    const offset = (await journal()).length;
    await config.update('server.startupTimeoutMs', timeout, vscode.ConfigurationTarget.Global);
    await config.update(settings['server.backend'] === 'java' ? 'server.javaPath' : 'server.rustLspPath', unresponsive, vscode.ConfigurationTarget.Global);
    await config.update('server.backend', settings['server.backend'], vscode.ConfigurationTarget.Global);
    return offset;
  };
  // Receipt is durable history: startup timeout/cleanup may finish before the
  // configuration updates return or before the journal is first read.
  const receivedInitialize = (offset, pid) => until(async () => (await journal()).slice(offset).find(row =>
    row.fixture === 'unresponsive' && row.event === 'initialize' && (pid === undefined || row.pid === pid)),
  'unresponsive server actually receives initialize');
  const hang = async timeout => {
    const offset = await configureHang(timeout);
    // Only interruption tests need a live pending start. Configuration updates
    // may cancel earlier starts, so wait for a live initialized replacement.
    return until(async () => (await journal()).slice(offset).find(row =>
      row.fixture === 'unresponsive' && row.event === 'initialize' && !dead(row.pid)),
    'unresponsive startup is still pending before interruption');
  };
  const stoppedHangs = async () => until(async () => (await journal()).filter(row => row.fixture === 'unresponsive').every(({pid}) => dead(pid)), 'all unresponsive children terminate', 10000);
  // No initialize response: explicit startup fails with the configured timeout, not an unrelated error.
  trace('timeout-test-start');
  const timeoutOffset = await configureHang(1000);
  const previousHangs = unresponsiveChildren.length;
  await assert.rejects(bounded(restart(), 'unresponsive startup timeout'), /Server initialization exceeded 1000 ms\./);
  trace('timeout-test-rejected');
  assert.ok(unresponsiveChildren.length > previousHangs, 'explicit restart spawned an unresponsive child');
  const timedOut = unresponsiveChildren.at(-1);
  assert.equal(timedOut.signalCode, 'SIGKILL', 'timed-out child ignores TERM and requires bounded force cleanup');
  // Always observe receipt AFTER the explicit restart has joined its child. This
  // exercises a late journal read on every run without adding sleeps or budgets.
  const initialized = await receivedInitialize(timeoutOffset, timedOut.pid);
  assert.ok(dead(initialized.pid), 'initialize receipt remains observable after child termination');
  trace('unresponsive-initialize-observed-after-exit', {pid: initialized.pid, initializedAt: initialized.at});
  await stoppedHangs();
  assert.ok((await journal()).some(row => row.pid === timedOut.pid && row.fixture === 'unresponsive' && row.event === 'sigterm'), 'the same initialized child received and ignored TERM');
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
  } finally { childProcess.spawn = originalSpawn; clearTimeout(timer); process.off('unhandledRejection', record); }
};
