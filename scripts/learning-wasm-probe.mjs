import {readFile} from 'node:fs/promises';
import readline from 'node:readline';
const [grammar, wasm] = process.argv.slice(2);
const engine = (await WebAssembly.instantiate(await readFile(wasm), {})).instance.exports;
function call(text, method) {
  const bytes = new TextEncoder().encode(text);
  const pointer = engine.pg_input(bytes.length);
  if (!pointer) throw new Error('input limit');
  new Uint8Array(engine.memory.buffer, pointer, bytes.length).set(bytes);
  engine[method]();
  return JSON.parse(new TextDecoder().decode(new Uint8Array(engine.memory.buffer, engine.pg_output(), engine.pg_output_len())));
}
const compiled = call(await readFile(grammar, 'utf8'), 'pg_compile');
if (!compiled.compiled) throw new Error(JSON.stringify(compiled));
for await (const line of readline.createInterface({input: process.stdin, crlfDelay: Infinity})) {
  const result = call(Buffer.from(line, 'hex').toString('utf8'), 'pg_parse');
  if (result.mappingError || result.runtimeError) throw new Error(JSON.stringify(result));
  console.log(JSON.stringify({...result, ast: result.ast ?? null}));
}
