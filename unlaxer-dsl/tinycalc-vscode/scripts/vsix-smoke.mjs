import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, writeFile, rm, cp, readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {runTests} from '@vscode/test-electron';
const root = fileURLToPath(new URL('../', import.meta.url));
const artifact = path.resolve(process.argv[2] ?? path.join(root, 'target/tinycalc-smoke.vsix'));
const scratch = await mkdtemp(path.join(tmpdir(), 'tinycalc-ide-'));
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
  execFileSync('unzip', ['-q', artifact, '-d', scratch]);
  const packaged = path.join(scratch, 'extension');
  const results = [];
  for (const backend of ['java', 'rust']) {
    const folder = path.join(scratch, backend); await mkdir(folder);
    const pids = path.join(folder, 'pids.jsonl'); await writeFile(pids, '');
    const wrapper = async (name, executable, protocol) => {
      const file = path.join(folder, `${name} with spaces`);
      await writeFile(file, `#!/usr/bin/env node\nconst {spawn}=require('node:child_process');\nconst child=spawn(${JSON.stringify(executable)},process.argv.slice(2),{stdio:'inherit',shell:false});\nchild.on('spawn',()=>require('node:fs').appendFileSync(${JSON.stringify(pids)},JSON.stringify({pid:child.pid,protocol:${JSON.stringify(protocol)}})+'\\n'));\nchild.on('exit',code=>process.exit(code??1));\nchild.on('error',error=>{process.stderr.write(String(error));process.exit(1);});\nprocess.on('SIGTERM',()=>child.kill('SIGTERM'));\n`, {mode: 0o755});
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
      // Java LSP uses -jar; DAP uses -cp, so record each independently.
      const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
      settings['server.javaPath'] = await wrapper('java', java, 'java');
      const file = settings['server.javaPath'];
      await writeFile(file, (await readFile(file, 'utf8')).replace('protocol:"java"', 'protocol:process.argv.includes("-jar")?"lsp":"dap"'), {mode: 0o755});
    } else {
      const platform = `${process.platform}-${process.arch}`;
      settings['server.rustLspPath'] = await wrapper('lsp', path.join(packaged, 'server-dist', platform, 'tinycalc-lsp'), 'lsp');
      settings['server.rustDapPath'] = await wrapper('dap', path.join(packaged, 'server-dist', platform, 'tinycalc-dap'), 'dap');
    }
    const result = path.join(folder, 'result.json');
    try { await runTests({version: '1.96.4', extensionDevelopmentPath: packaged, extensionTestsPath: path.join(root, 'scripts/vsix-suite.cjs'),
      extensionTestsEnv: {TINYCALC_PACKAGED_ROOT: packaged, TINYCALC_SCRATCH: folder, TINYCALC_SETTINGS: JSON.stringify(settings), TINYCALC_PIDS: pids, TINYCALC_UNRESPONSIVE: unresponsive, TINYCALC_RESULT: result},
      launchArgs: ['--disable-extensions', '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes', '--no-sandbox', `--user-data-dir=${path.join(folder, 'user')}`, `--extensions-dir=${path.join(folder, 'extensions')}`]});
      const logs = await editorLogs(path.join(folder, 'user/logs'));
      await writeFile(path.join(root, 'target', `ide-${backend}-editor.log`), logs);
      assert.doesNotMatch(logs, /Invalid debug adapter|[Uu]nhandled[Pp]romise[Rr]ejection|[Uu]nhandled promise rejection/, `${backend} adapter shutdown must not leave editor errors`);
    } catch (error) { await cp(folder, path.join(root, 'target', `failed-${backend}`), {recursive: true}); throw error; }
    const observation = JSON.parse(await readFile(result, 'utf8')); results.push(observation);
    await writeFile(path.join(root, 'target', `ide-${backend}.json`), JSON.stringify(observation, null, 2));
  }
  assert.deepEqual(results[0], results[1], 'Java/Rust observations from actual VS Code agree');
  console.log('Packaged Java/Rust IDE launch, protocol, restart and process cleanup passed.');
} finally {
  // Only terminate recorded children whose live command still belongs to this private scratch directory.
  // In particular, never signal a reused PID or an unrelated runner process.
  if (process.platform === 'linux') {
    const owned = async pid => {
      try { return (await readFile(`/proc/${pid}/cmdline`, 'utf8')).includes(scratch); }
      catch (error) { if (error.code === 'ENOENT' || error.code === 'ESRCH') return false; throw error; }
    };
    const pids = new Set();
    for (const backend of ['java', 'rust']) {
      try { for (const line of (await readFile(path.join(scratch, backend, 'pids.jsonl'), 'utf8')).split('\n').filter(Boolean)) pids.add(JSON.parse(line).pid); }
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
