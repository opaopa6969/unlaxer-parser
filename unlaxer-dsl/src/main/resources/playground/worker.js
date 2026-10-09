/* Runs only the generated parser; grammar source is never evaluated as JavaScript. */
'use strict';
let engine;
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', {fatal: true});
function result() {
  const size = engine.pg_output_len();
  if (size > 4 * 1024 * 1024) throw new Error('結果のサイズ制限を超えました。');
  return JSON.parse(decoder.decode(new Uint8Array(engine.memory.buffer, engine.pg_output(), size)));
}
self.onmessage = async event => {
  const {id, type, bytes, input, editor, cursor} = event.data;
  try {
    if (type === 'init') {
      engine = (await WebAssembly.instantiate(bytes, {})).instance.exports;
      engine.pg_catalog(); self.postMessage({id, catalog: result()});
    } else if (type === 'parse') {
      if (!engine || typeof input !== 'string') throw new Error('parser が準備できていません。');
      const encoded = encoder.encode(input);
      if (encoded.length > 65536) throw new Error('入力は 64 KiB (UTF-8) 以下にしてください。');
      const pointer = engine.pg_input(encoded.length);
      if (!pointer) throw new Error('入力用メモリを確保できませんでした。');
      new Uint8Array(engine.memory.buffer, pointer, encoded.length).set(encoded);
      if (editor && typeof engine.pg_editor_snapshot === 'function' && Number.isSafeInteger(id) && id >= 0 && id <= 0xffffffff) engine.pg_editor_snapshot(cursor, id); else if (editor && typeof engine.pg_editor === 'function') engine.pg_editor(cursor); else engine.pg_parse(); self.postMessage({id, result: result()});
    } else throw new Error('不明な操作です。');
  } catch (error) { self.postMessage({id, error: String(error)}); }
};
