import assert from 'node:assert/strict';
import {mkdtemp, readdir, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {runTests} from '@vscode/test-electron';

const root = fileURLToPath(new URL('../', import.meta.url));
const files = (await readdir(path.join(root, 'target'))).filter(file => file.endsWith('.vsix'));
if (!process.argv[2]) assert.equal(files.length, 1, 'Specify a VSIX path when target has multiple packages.');
const artifact = process.argv[2] ? path.resolve(process.argv[2]) : path.join(root, 'target', files[0]);
const scratch = await mkdtemp(path.join(tmpdir(), 'ubnf-vsix-smoke-'));
try {
  execFileSync('unzip', ['-q', artifact, '-d', scratch]);
  const packaged = path.join(scratch, 'extension');
  const manifest = JSON.parse(await readFile(path.join(packaged, 'package.json'), 'utf8'));
  assert.equal(manifest.name, 'ubnf-lsp');
  // The historical .vscodeignore excluded all runtime dependencies. Loading the
  // extracted VSIX (not this checkout's node_modules) makes that failure visible.
  await runTests({
    extensionDevelopmentPath: packaged,
    extensionTestsPath: path.join(root, 'scripts/vsix-suite.cjs'),
    extensionTestsEnv: {UBNF_PACKAGED_ROOT: packaged},
    launchArgs: ['--disable-extensions', '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes',
      '--no-sandbox', '--user-data-dir', path.join(scratch, 'user'), '--extensions-dir', path.join(scratch, 'extensions')]
  });
} finally {
  // Only the directory created above: never touches a user's editor profile.
  await rm(scratch, {recursive: true, force: true});
}
