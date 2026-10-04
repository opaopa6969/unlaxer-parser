// A closed AST interpreter. Learner text is never passed to eval/Function.
export function evaluate(ast, policy = 'lazy') {
  const environment = new Map();
  const reserved = new Set(['let', 'if']);
  let budget = 10000;
  function visit(node, depth = 0) {
    if (--budget < 0 || depth > 256) throw new Error('評価の深さ・回数の制限を超えました');
    if (!node || typeof node !== 'object') throw new Error('評価できる AST がありません');
    const f = node.fields;
    const run = child => visit(child, depth + 1);
    let value;
    switch (node.type) {
      case 'Number':
        if (typeof f.value !== 'string' || !/^\d+$/.test(f.value)) throw new Error('Number.value は数字列にしてください');
        value = Number(f.value); break;
      case 'Sum': case 'Product': {
        if (!Array.isArray(f.ops) || !Array.isArray(f.rest) || f.ops.length !== f.rest.length) throw new Error('ops と rest は同じ長さの配列にしてください');
        value = run(f.first);
        for (let i = 0; i < f.ops.length; i++) {
          const right = run(f.rest[i]);
          const op = f.ops[i];
          if (op === '+') value += right;
          else if (op === '-') value -= right;
          else if (op === '*') value *= right;
          else if (op === '/') { if (right === 0) throw new Error('ゼロ除算です'); value /= right; }
          else throw new Error(`未対応の演算子: ${op}`);
          if (!Number.isFinite(value)) throw new Error('計算結果が有限値ではありません');
        }
        break;
      }
      case 'Variable':
        if (reserved.has(f.name)) throw new Error(`予約語は変数に使えません: ${f.name}`);
        if (!environment.has(f.name)) throw new Error(`未定義の変数: ${f.name}`);
        value = environment.get(f.name); break;
      case 'Program':
        if (!Array.isArray(f.bindings)) throw new Error('bindings は宣言の配列にしてください');
        for (const binding of f.bindings) {
          if (binding.type !== 'Binding') throw new Error('Binding が必要です');
          const {name, value: expression} = binding.fields;
          if (typeof name !== 'string' || !/^[a-z_][a-z0-9_]*$/.test(name)) throw new Error('変数名の形が不正です');
          if (reserved.has(name)) throw new Error(`予約語は変数に使えません: ${name}`);
          if (environment.has(name)) throw new Error(`二重宣言: ${name}`);
          environment.set(name, run(expression));
        }
        value = run(f.result); break;
      case 'Conditional': {
        const condition = run(f.condition);
        if (policy === 'eager') { const yes = run(f.yes), no = run(f.no); value = condition !== 0 ? yes : no; }
        else if (policy === 'lazy') value = run(condition !== 0 ? f.yes : f.no);
        else throw new Error('条件式の評価方法を選んでください');
        break;
      }
      default: throw new Error(`教材の評価器に未定義の AST: ${node.type}`);
    }
    if (!Number.isFinite(value)) throw new Error('計算結果が有限値ではありません');
    return value;
  }
  return visit(ast);
}

export function assess(test, result) {
  if (result.runtimeError || result.grammarError || result.mappingError) return false;
  if (test.accept !== result.ok) return false;
  if (!test.accept) return true;
  if (Object.hasOwn(test, 'evaluationError')) return typeof result.evaluationError === 'string' && result.evaluationError.includes(test.evaluationError);
  return !result.evaluationError && result.value === test.value;
}
