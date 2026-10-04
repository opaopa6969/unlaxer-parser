import {spawnSync} from 'node:child_process';
import {cp, mkdir, mkdtemp, readFile, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const repo = fileURLToPath(new URL('../', import.meta.url));
const output = path.join(repo, 'build/learning-site');
const resources = path.join(repo, 'unlaxer-dsl/src/main/resources');
function run(command, args, env = process.env) {
  const result = spawnSync(command, args, {cwd: repo, env, stdio: 'inherit', shell: false});
  if (result.error || result.status !== 0) throw result.error || new Error(`${command} failed (${result.status})`);
}
run('cargo', ['build', '--locked', '--manifest-path', 'rust/Cargo.toml', '-p', 'unlaxer-generator']);
run('cargo', ['build', '--locked', '--offline', '--manifest-path', 'rust/Cargo.toml', '-p', 'unlaxer-learning', '--release', '--target', 'wasm32-unknown-unknown'],
  {...process.env, CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS: '-C link-arg=--max-memory=268435456'});
await mkdir(output, {recursive: true});
await cp(path.join(resources, 'learning'), output, {recursive: true});
await cp(path.join(resources, 'ubnf-help'), path.join(output, 'help'), {recursive: true});
await cp(path.join(repo, 'rust/target/wasm32-unknown-unknown/release/unlaxer_learning.wasm'), path.join(output, 'learning.wasm'));
await writeFile(path.join(output, '.nojekyll'), '');
const scratch = await mkdtemp(path.join(tmpdir(), 'ubnf-learning-build-'));
try {
  const course = JSON.parse(await readFile(path.join(resources, 'learning/tiny-expression.json'), 'utf8'));
  const grammar = path.join(scratch, 'completed.ubnf');
  await writeFile(grammar, course.steps.at(-1).solution);
  const generated = path.join(scratch, 'playground');
  run(path.join(repo, 'rust/target/debug/unlaxer'), ['playground', '--grammar', grammar, '--output', generated]);
  run(process.execPath, [path.join(generated, 'build.mjs')]);
  await cp(path.join(generated, 'public'), path.join(output, 'playground'), {recursive: true});
} finally { await rm(scratch, {recursive: true, force: true}); }
console.log(`Learning site: ${output}`);
