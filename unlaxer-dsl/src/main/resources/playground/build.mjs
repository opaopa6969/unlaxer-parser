import {spawnSync} from 'node:child_process';
import {copyFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root = new URL('./', import.meta.url);
const result = spawnSync(process.env.UBNF_CARGO || 'cargo', ['build', '--offline', '--release', '--target', 'wasm32-unknown-unknown'], {
  cwd: fileURLToPath(root), stdio: 'inherit', shell: false
});
if (result.error || result.status !== 0) {
  console.error('WASM build failed. Rust >=1.85 と wasm32 target が必要です。');
  console.error('準備: rustup target add wasm32-unknown-unknown');
  if (result.error) console.error(result.error.message);
  process.exit(1);
}
await copyFile(new URL('target/wasm32-unknown-unknown/release/ubnf_playground.wasm', root), new URL('public/language.wasm', root));
console.log('生成した parser を public/language.wasm に配置しました。npm start で開けます。');
