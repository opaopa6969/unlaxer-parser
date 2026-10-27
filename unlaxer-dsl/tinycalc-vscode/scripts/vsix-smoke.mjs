import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, writeFile, rm, cp, readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {runTests} from '@vscode/test-electron';
const root = fileURLToPath(new URL('../', import.meta.url));
const output = path.join(root, 'target');
const artifact = path.resolve(process.argv[2] ?? path.join(output, 'tinycalc-smoke.vsix'));
// 1.96.4 throws on a valid delayed adapter exit after its session handle is removed.
// 1.97.2 includes the upstream post-close exit lifecycle fix (see rust-vscode-launch.md).
const version = '1.97.2';
const scratch = await mkdtemp(path.join(tmpdir(), 'tinycalc-ide-'));
const cases = [];
const failures = [];
const normalObservations = new Map();
async function editorLogs(directory) {
  const output = [];
  for (const entry of await readdir(directory, {withFileTypes: true})) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) output.push(await editorLogs(file));
    else if (entry.name.endsWith('.log')) output.push(`${file}\n${await readFile(file, 'utf8')}`);
  }
  return output.join('\n');
}

try {
  await mkdir(output, {recursive: true});
  execFileSync('unzip', ['-q', artifact, '-d', scratch]);
  const packaged = path.join(scratch, 'extension');
  for (const scenario of ['normal', 'late-exit']) {
    const results = [];
    for (const backend of ['java', 'rust']) {
      const label = `${backend}${scenario === 'normal' ? '' : '-late-exit'}`;
      const folder = path.join(scratch, label); await mkdir(folder);
      const pids = path.join(folder, 'pids.jsonl'); await writeFile(pids, '');
      const timeline = path.join(folder, 'timeline.jsonl'); await writeFile(timeline, '');
      const result = path.join(folder, 'result.json');
      const status = {backend, scenario, version, passed: false};
      // Repeated local runs cannot accidentally publish an older successful observation.
      for (const suffix of ['.json', '-editor.log', '-pids.jsonl', '-timeline.jsonl', '-status.json']) {
        await rm(path.join(output, `ide-${label}${suffix}`), {force: true});
      }
      await rm(path.join(output, `failed-${label}`), {recursive: true, force: true});
      const wrapper = async (name, executable, protocol) => {
        const file = path.join(folder, `${name} with spaces`);
        // Hold the fixture wrapper until the real DAP child exits AND VS Code stops it.
        // Deliver its exit later so the editor has already removed the session handle.
        // This exercises the editor race without modifying either backend or its protocol.
        await writeFile(file, `#!/usr/bin/env node
const {spawn}=require('node:child_process');
const fs=require('node:fs');
const protocol=${name === 'java' ? "process.argv.includes('-jar')?'lsp':'dap'" : JSON.stringify(protocol)};
const record=(event,extra={})=>fs.appendFileSync(${JSON.stringify(timeline)},JSON.stringify({at:Date.now(),event,protocol,pid:process.pid,child:child.pid,...extra})+'\\n');
const child=spawn(${JSON.stringify(executable)},process.argv.slice(2),{stdio:'inherit',shell:false});
child.on('spawn',()=>{
  for(const row of [{pid:process.pid,protocol:'wrapper'},{pid:child.pid,protocol}]) fs.appendFileSync(${JSON.stringify(pids)},JSON.stringify(row)+'\\n');
  record('spawn');
});
const hold=protocol==='dap' && ${scenario === 'late-exit'}?setInterval(()=>{},1000):undefined;
let exited=false,stopRequested=false,scheduled=false,exitCode=1;
const finish=()=>{
  if(!exited || scheduled || (protocol==='dap' && ${scenario === 'late-exit'} && !stopRequested)) return;
  scheduled=true;
  const exit=()=>{record('wrapper-exit');clearInterval(hold);process.exit(exitCode);};
  if(protocol==='dap' && ${scenario === 'late-exit'}) setTimeout(exit,500); else exit();
};
child.on('exit',(code,signal)=>{record('child-exit',{code,signal});exited=true;exitCode=code??1;finish();});
child.on('error',error=>{process.stderr.write(String(error));process.exit(1);});
process.on('SIGTERM',()=>{record('sigterm');stopRequested=true;child.kill('SIGTERM');finish();});
`, {mode: 0o755});
        return file;
      };
      const unresponsive = path.join(folder, 'unresponsive server with spaces');
      await writeFile(unresponsive, `#!/usr/bin/env node
const fs=require('node:fs');
const record=event=>fs.appendFileSync(${JSON.stringify(pids)},JSON.stringify({pid:process.pid,protocol:'lsp',fixture:'unresponsive',event})+'\\n');
record('spawn');
let input='',initialized=false;
process.stdin.on('data',chunk=>{input+=chunk.toString();if(!initialized && /"method"\\s*:\\s*"initialize"/.test(input)){initialized=true;record('initialize');}});
process.stdin.resume();
process.on('SIGTERM',()=>record('sigterm'));
`, {mode: 0o755});
      const settings = {'server.backend': backend, 'server.jarPath': path.join(packaged, 'server-dist/tinycalc-lsp-server.jar')};
      if (backend === 'java') {
        const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
        settings['server.javaPath'] = await wrapper('java', java, 'java');
      } else {
        const platform = `${process.platform}-${process.arch}`;
        settings['server.rustLspPath'] = await wrapper('lsp', path.join(packaged, 'server-dist', platform, 'tinycalc-lsp'), 'lsp');
        settings['server.rustDapPath'] = await wrapper('dap', path.join(packaged, 'server-dist', platform, 'tinycalc-dap'), 'dap');
      }
      try {
        await runTests({version, extensionDevelopmentPath: packaged, extensionTestsPath: path.join(root, 'scripts/vsix-suite.cjs'),
          extensionTestsEnv: {TINYCALC_PACKAGED_ROOT: packaged, TINYCALC_SCRATCH: folder, TINYCALC_SETTINGS: JSON.stringify(settings), TINYCALC_PIDS: pids, TINYCALC_UNRESPONSIVE: unresponsive, TINYCALC_RESULT: result, TINYCALC_TIMELINE: timeline},
          launchArgs: ['--password-store=basic', '--disable-extensions', '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes', '--no-sandbox', `--user-data-dir=${path.join(folder, 'user')}`, `--extensions-dir=${path.join(folder, 'extensions')}`]});
        const observation = JSON.parse(await readFile(result, 'utf8'));
        await writeFile(path.join(output, `ide-${label}.json`), JSON.stringify(observation, null, 2));
        if (scenario === 'normal') normalObservations.set(backend, observation);
        else if (normalObservations.has(backend)) assert.deepEqual(observation, normalObservations.get(backend), 'late exit preserves normal observations');
        if (scenario === 'late-exit') {
          const events = (await readFile(timeline, 'utf8')).trim().split('\n').map(JSON.parse);
          const ended = events.filter(row => row.event === 'session-terminated');
          const spawned = events.filter(row => row.protocol === 'dap' && row.event === 'spawn');
          assert.equal(ended.length, 2); assert.equal(spawned.length, 2);
          for (let i = 0; i < spawned.length; i++) {
            const exit = events.find(row => row.pid === spawned[i].pid && row.event === 'wrapper-exit');
            const childExit = events.find(row => row.pid === spawned[i].pid && row.event === 'child-exit');
            assert.ok(exit && childExit && exit.at > ended[i].at && exit.at >= childExit.at, 'fixture delivers actual adapter exit after session termination');
          }
        }
        results.push(observation);
      } catch (error) { status.error = String(error); }
      // Preserve and inspect logs even when the extension test runner fails first.
      try {
        const logs = await editorLogs(path.join(folder, 'user/logs'));
        await writeFile(path.join(output, `ide-${label}-editor.log`), logs);
        assert.doesNotMatch(logs, /Invalid debug adapter|[Uu]nhandled[Pp]romise[Rr]ejection|[Uu]nhandled promise rejection|Stopping (?:the )?server timed out/, `${label} adapter shutdown must not leave editor errors`);
      } catch (error) { status.error = [status.error, String(error)].filter(Boolean).join('\n'); }
      for (const [source, suffix] of [[pids, 'pids'], [timeline, 'timeline']]) {
        await cp(source, path.join(output, `ide-${label}-${suffix}.jsonl`));
      }
      status.passed = !status.error;
      cases.push(status);
      await writeFile(path.join(output, `ide-${label}-status.json`), JSON.stringify(status, null, 2));
      if (!status.passed) {
        await cp(folder, path.join(output, `failed-${label}`), {recursive: true});
        failures.push(new Error(`${label}: ${status.error}`));
      }
    }
    if (results.length === 2) {
      try { assert.deepEqual(results[0], results[1], `Java/Rust observations from actual VS Code agree (${scenario})`); }
      catch (error) { failures.push(error); }
    }
  }
  await writeFile(path.join(output, 'ide-results.json'), JSON.stringify({version, cases, passed: failures.length === 0}, null, 2));
  if (failures.length) throw new AggregateError(failures, 'Packaged IDE validation failed; all backend results and logs were preserved.');
  console.log('Packaged Java/Rust IDE launch, protocol, restart, late exit and process cleanup passed.');
} finally {
  // Never signal a reused PID or an unrelated runner process.
  if (process.platform === 'linux') {
    const owned = async pid => {
      try { return (await readFile(`/proc/${pid}/cmdline`, 'utf8')).includes(scratch); }
      catch (error) { if (error.code === 'ENOENT' || error.code === 'ESRCH') return false; throw error; }
    };
    const pids = new Set();
    for (const label of ['java', 'rust', 'java-late-exit', 'rust-late-exit']) {
      try { for (const line of (await readFile(path.join(scratch, label, 'pids.jsonl'), 'utf8')).split('\n').filter(Boolean)) pids.add(JSON.parse(line).pid); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    for (const signal of ['SIGTERM', 'SIGKILL']) {
      for (const pid of pids) if (await owned(pid)) {
        try { process.kill(pid, signal); } catch (error) { if (error.code !== 'ESRCH') throw error; }
      }
      if (signal === 'SIGTERM' && pids.size) await new Promise(resolve => setTimeout(resolve, 750));
    }
  }
  await rm(scratch, {recursive: true, force: true});
}
