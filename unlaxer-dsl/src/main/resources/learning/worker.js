import {evaluate} from './evaluator.js';
const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', {fatal: true});
self.onmessage = async ({data: {bytes, grammar, inputs, policy}}) => {
  try {
    // One instance per request bounds the lifetime of runtime &'static strings.
    const engine = (await WebAssembly.instantiate(bytes, {})).instance.exports;
    function invoke(text, method, limit) {
      const input = encoder.encode(text);
      if (input.length > limit) throw new Error(`入力サイズの上限 ${limit / 1024} KiB を超えました`);
      const pointer = engine.pg_input(input.length);
      if (!pointer) throw new Error('入力用メモリを確保できません');
      new Uint8Array(engine.memory.buffer, pointer, input.length).set(input);
      engine[method]();
      const size = engine.pg_output_len();
      if (size > 4 * 1024 * 1024) throw new Error('結果が 4 MiB を超えました');
      return JSON.parse(decoder.decode(new Uint8Array(engine.memory.buffer, engine.pg_output(), size)));
    }
    const compiled = invoke(grammar, 'pg_compile', 16384);
    if (!compiled.compiled) { self.postMessage(compiled); return; }
    const results = inputs.map(input => {
      const result = invoke(input, 'pg_parse', 8192);
      if (result.ok && !result.mappingError) {
        try { result.value = evaluate(result.ast, policy); }
        catch (error) { result.evaluationError = error.message; }
      }
      return result;
    });
    self.postMessage({results});
  } catch (error) { self.postMessage({runtimeError: String(error)}); }
};
