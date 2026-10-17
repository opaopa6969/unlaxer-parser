import {execFileSync} from 'node:child_process';
import {mkdir, copyFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const extension = fileURLToPath(new URL('../', import.meta.url));
const repository = path.resolve(extension, '../..');
const cargo = args => execFileSync('cargo', args, {cwd: extension, stdio: 'inherit'});
cargo(['run', '--locked', '--manifest-path', path.join(repository, 'rust/Cargo.toml'), '-p', 'unlaxer-generator', '--bin', 'unlaxer', '--',
  'generate', '--target', 'rust', '--grammar', path.join(extension, 'grammar/tinycalc.ubnf'),
  '--output', path.join(extension, 'rust/src/generated'), '--lsp', '--dap']);
const host = execFileSync('rustc', ['-vV'], {encoding: 'utf8'}).match(/^host: (.+)$/m)?.[1];
if (!host) throw new Error('Cannot determine the Rust host target.');
cargo(['build', '--locked', '--release', '--target', host, '--manifest-path', path.join(extension, 'rust/Cargo.toml')]);
const target = process.env.CARGO_TARGET_DIR ? path.resolve(extension, process.env.CARGO_TARGET_DIR) : path.join(extension, 'rust/target');
const suffix = process.platform === 'win32' ? '.exe' : '';
const output = path.join(extension, 'server-dist', `${process.platform}-${process.arch}`);
await mkdir(output, {recursive: true});
for (const name of ['tinycalc-lsp', 'tinycalc-dap']) await copyFile(path.join(target, host, 'release', name + suffix), path.join(output, name + suffix));
console.log(`Rust stdio servers: ${output}`);
