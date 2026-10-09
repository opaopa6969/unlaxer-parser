import fs from 'node:fs';
import assert from 'node:assert/strict';
const engine=(await WebAssembly.instantiate(fs.readFileSync(process.argv[2]),{})).instance.exports;
const encode=new TextEncoder(),decode=new TextDecoder('utf8',{fatal:true});
const source='😀F{T[foo /*😀*/ far foo f ]T}F';
function query(input,cursor,operation,argument='',version=33) {
  const text=encode.encode(input),tail=encode.encode(argument),bytes=new Uint8Array(text.length+tail.length);bytes.set(text);bytes.set(tail,text.length);
  const ptr=engine.pg_input(bytes.length);new Uint8Array(engine.memory.buffer,ptr,bytes.length).set(bytes);
  engine.pg_query(cursor,version,operation,text.length);
  return JSON.parse(decode.decode(new Uint8Array(engine.memory.buffer,engine.pg_output(),engine.pg_output_len())));
}
const expectedCaps=['CODE_ACTION','COMPLETION','DEFINITION','FORMAT','HOVER','RENAME'];
const completed=query(source,24,1,'f');
assert.equal(completed.uri,'playground');assert.equal(completed.version,'33');assert.equal(completed.state,'COMPLETE');assert.deepEqual(completed.capabilities,expectedCaps);
assert.deepEqual(completed.items.map(i=>[i.label,i.edits]),[['far',[{span:[23,24],replacement:'far'}]],['foo',[{span:[23,24],replacement:'foo'}]]]);
for(const operation of [2,3]) {
  const result=query(source,20,operation,'foo');assert.deepEqual(result.items.map(i=>[i.label,i.locations]),[['foo',[{uri:'playground',version:'33',span:[5,8],exact:true}]]]);
}
for(const [operation,argument,edits] of [[4,'bar',[{span:[5,8],replacement:'bar'},{span:[19,22],replacement:'bar'}]],[5,'',[{span:[8,9],replacement:'\t'}]],[6,'',[{span:[23,24],replacement:'foo'}]]]) {
  const result=query(source,20,operation,argument);assert.equal(result.state,'PARTIAL');assert.deepEqual(result.items.flatMap(item=>item.edits),edits);
}
assert.equal(query(source,25,1).state,'UNAVAILABLE');
const open=source.slice(0,-4);assert.equal([...open].length,25);
assert.equal(query(open,25,1).state,'COMPLETE');
assert.ok(query(source,99,1).runtimeError);assert.ok(query(source,20,99).runtimeError);assert.ok(query(source,20,4,'!').runtimeError);
const unavailable=query('😀F{T[bad]T}F',6,1);assert.equal(unavailable.state,'UNAVAILABLE');assert.deepEqual(unavailable.capabilities,[]);
console.log('Playground query ABI: actual registry/providers, six capabilities, original CP edits, non-BMP, EOF/delimiter and failure checks passed');

// The byte-count split must never decode half a Unicode scalar or read outside the input buffer.
const encoded=encode.encode('😀'),pointer=engine.pg_input(encoded.length);new Uint8Array(engine.memory.buffer,pointer,encoded.length).set(encoded);
for(const length of [1,99]) { engine.pg_query(0,34,1,length);const result=JSON.parse(decode.decode(new Uint8Array(engine.memory.buffer,engine.pg_output(),engine.pg_output_len())));assert.ok(result.runtimeError); }
