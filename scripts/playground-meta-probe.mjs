import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const engine = (await WebAssembly.instantiate(await readFile(process.argv[2]), {})).instance.exports;
const catalog = JSON.parse(await readFile(process.argv[3], 'utf8'));
function parse(source) {
  const bytes = new TextEncoder().encode(source), pointer = engine.pg_input(bytes.length);
  new Uint8Array(engine.memory.buffer, pointer, bytes.length).set(bytes); engine.pg_parse();
  return JSON.parse(new TextDecoder().decode(new Uint8Array(engine.memory.buffer, engine.pg_output(), engine.pg_output_len())));
}
for (const example of catalog.examples) {
  const result = parse(example.source);
  assert.equal(result.ok, true, example.id); assert.equal(result.ast.type, 'UBNFFile');
  assert.equal(result.mappingError, null);
}
assert.equal(parse(await readFile(process.argv[4], 'utf8')).ok, true, 'meta grammar accepts its own source');
assert.equal(parse('grammar Broken {').ok, false);
console.log('Meta-grammar generated WASM: all catalog grammars, itself, and an invalid grammar verified');
