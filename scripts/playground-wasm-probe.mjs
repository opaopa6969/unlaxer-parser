// Test-only probe: the same generated WASM ABI used by the browser Worker.
import {readFile} from 'node:fs/promises';
import {createInterface} from 'node:readline';
import assert from 'node:assert/strict';
import path from 'node:path';
const bytes = await readFile(path.join(process.argv[2], 'public/language.wasm'));
const engine = (await WebAssembly.instantiate(bytes, {})).instance.exports;
const read = () => JSON.parse(new TextDecoder().decode(new Uint8Array(engine.memory.buffer, engine.pg_output(), engine.pg_output_len())));
engine.pg_catalog();
const catalog = read();
assert.ok(catalog.rules[catalog.root], 'root has catalog entry');
if (process.argv.includes('--require-docs')) assert.ok(catalog.rules.some(rule => rule.docs.length), 'sample documentation is retained');
for await (const line of createInterface({input: process.stdin})) {
  const input = Buffer.from(line, 'hex');
  const pointer = engine.pg_input(input.length);
  assert.ok(pointer);
  new Uint8Array(engine.memory.buffer, pointer, input.length).set(input);
  engine.pg_parse();
  const result = read();
  assert.equal(result.runtimeError, undefined);
  console.log(JSON.stringify({prefix: result.prefix, ast: result.ast ?? null}));
}
