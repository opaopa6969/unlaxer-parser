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
  const {id, type, bytes, input, editor, cursor, operation, argument = ''} = event.data;
  try {
    if (type === 'init') {
      engine = (await WebAssembly.instantiate(bytes, {})).instance.exports;
      engine.pg_catalog(); self.postMessage({id, catalog: result()});
    } else if (type === 'parse' || type === 'query') {
      if (!engine || typeof input !== 'string') throw new Error('parser が準備できていません。');
      if ((editor || type === 'query') && (!Number.isSafeInteger(cursor) || cursor < 0 || cursor > [...input].length)) throw new Error('カーソル位置が入力の外です。');
      const invalidUnicode = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u;
      if (typeof argument !== 'string' || invalidUnicode.test(input) || invalidUnicode.test(argument)) throw new Error('入力に不正な Unicode が含まれています。');
      const source = encoder.encode(input), parameter = type === 'query' ? encoder.encode(argument) : new Uint8Array();
      const encoded = new Uint8Array(source.length + parameter.length); encoded.set(source); encoded.set(parameter, source.length);
      if (encoded.length > 65536) throw new Error('入力は 64 KiB (UTF-8) 以下にしてください。');
      const pointer = engine.pg_input(encoded.length);
      if (!pointer) throw new Error('入力用メモリを確保できませんでした。');
      new Uint8Array(engine.memory.buffer, pointer, encoded.length).set(encoded);
      if (type === 'query') {
        if (typeof engine.pg_query !== 'function' || !Number.isInteger(operation) || operation < 0 || operation > 6 || !Number.isSafeInteger(id) || id < 0 || id > 0xffffffff) throw new Error('この言語の問い合わせは利用できません。');
        engine.pg_query(cursor, id, operation, source.length); self.postMessage({id, query: result()}); return;
      }
      if (editor && typeof engine.pg_editor_snapshot === 'function' && Number.isSafeInteger(id) && id >= 0 && id <= 0xffffffff) engine.pg_editor_snapshot(cursor, id); else if (editor && typeof engine.pg_editor === 'function') engine.pg_editor(cursor); else engine.pg_parse(); self.postMessage({id, result: result()});
    } else throw new Error('不明な操作です。');
  } catch (error) { self.postMessage({id, error: String(error)}); }
};
