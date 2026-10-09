import fs from 'node:fs';
import assert from 'node:assert/strict';
const engine = (await WebAssembly.instantiate(fs.readFileSync(process.argv[2]), {})).instance.exports;
const cases = fs.readFileSync(process.argv[3], 'utf8').trimEnd().split('\n');
const encode = new TextEncoder(), decode = new TextDecoder('utf8', {fatal:true});
let version = 0;
for (const line of cases) {
  const [name, mode, source, expected] = line.split('\t'); ++version;
  if (mode !== 'all') continue;
  const bytes = encode.encode(source), pointer = engine.pg_input(bytes.length);
  new Uint8Array(engine.memory.buffer, pointer, bytes.length).set(bytes);
  engine.pg_editor_snapshot(Math.min(5, [...source].length), version);
  const value = JSON.parse(decode.decode(new Uint8Array(engine.memory.buffer, engine.pg_output(), engine.pg_output_len())));
  assert.ok(value.languages, name);
  assert.equal(value.languages.uri, 'playground', name);
  assert.equal(value.languages.version, String(version), name);
  const actual = value.languages.regions.map(r => `${r.grammar}:${r.state}:${r.full.join(':')}:${r.body.join(':')}`).join(',');
  assert.equal(actual, expected, name);
  if (name === 'remove-both-closes') assert.equal(value.languages.selected, 'Java');
  console.log(`${name}\t${actual}`);
}
