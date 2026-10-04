import {assess} from './evaluator.js';
const $ = id => document.getElementById(id);
const element = (tag, text) => { const node = document.createElement(tag); node.textContent = text; return node; };
let course, state, storageKey, wasm, index = 0, activeJob, generation = 0, courseRequest = 0;
const current = () => course.steps[index];
const draft = () => state.drafts[current().id];

export function validateCourse(value) {
  if (value.schemaVersion !== 1 || !/^[a-z0-9-]+$/.test(value.id) || !Number.isInteger(value.version) || value.version < 1 ||
      typeof value.title !== 'string' || !Array.isArray(value.steps) || !value.steps.length || value.steps.length > 32) throw new Error('教材の形式・バージョンが不正です');
  const ids = new Set();
  for (const step of value.steps) {
    if (!/^[a-z0-9-]+$/.test(step.id) || ids.has(step.id)) throw new Error('ステップ ID は重複しない名前にしてください');
    ids.add(step.id);
    for (const key of ['title', 'subtitle', 'task', 'hint', 'starter', 'solution', 'sample']) if (typeof step[key] !== 'string') throw new Error(`${step.id}: ${key} が必要です`);
    if (!Array.isArray(step.explanation) || !step.explanation.length || step.explanation.some(p => typeof p !== 'string')) throw new Error('説明が必要です');
    const q = step.question;
    if (!q || typeof q.prompt !== 'string' || !Array.isArray(q.options) || q.options.length < 2 ||
        !Number.isInteger(q.answer) || !q.options[q.answer] || q.options.some(o => typeof o.text !== 'string' || typeof o.feedback !== 'string')) throw new Error('問いの定義が不正です');
    if (!Array.isArray(step.cases) || !step.cases.length || step.cases.length > 32 || step.cases.some(c =>
      typeof c.input !== 'string' || typeof c.accept !== 'boolean' || (c.accept && !(Number.isFinite(c.value) || typeof c.evaluationError === 'string')))) throw new Error('試験入力と期待値が必要です');
  }
  return value;
}

function save() {
  state.index = index;
  try { localStorage.setItem(storageKey, JSON.stringify(state)); }
  catch { $('storage-status').textContent = 'ブラウザの保存領域が使えません。この画面を閉じると進捗が失われるので、文法を保存してください。'; }
}
function restore() {
  state = {drafts: {}, index: 0};
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) || 'null');
    if (value && typeof value.drafts === 'object' && value.drafts && !Array.isArray(value.drafts)) state = value;
  } catch { $('storage-status').textContent = '保存データを読めませんでした。新しい進捗で開始します。'; }
  for (const step of course.steps) {
    const d = state.drafts[step.id];
    state.drafts[step.id] = {
      grammar: typeof d?.grammar === 'string' ? d.grammar : step.starter,
      input: typeof d?.input === 'string' ? d.input : step.sample,
      answer: Number.isInteger(d?.answer) && step.question.options[d.answer] ? d.answer : null,
      policy: d?.policy === 'lazy' ? 'lazy' : step.semanticsExercise ? 'eager' : 'lazy',
      passed: d?.passed === true && typeof d.grammar === 'string' && ['eager', 'lazy'].includes(d.policy)
    };
  }
  index = Number.isInteger(state.index) && course.steps[state.index] ? state.index : 0;
}
function busy(value) { $('check').disabled = value || !wasm; $('run').disabled = value || !wasm; }
function cancel() {
  generation++;
  if (activeJob) { clearTimeout(activeJob.timer); activeJob.worker.terminate(); activeJob.reject(new Error('中断')); activeJob = null; }
  busy(false);
}
function job(inputs) {
  busy(true);
  const {grammar, policy} = draft();
  return new Promise((resolve, reject) => {
    let worker;
    try { worker = new Worker(new URL('worker.js', import.meta.url), {type: 'module'}); }
    catch (error) { busy(false); reject(error); return; }
    const finish = (result, error) => {
      clearTimeout(timer); worker.terminate();
      if (activeJob?.worker === worker) { activeJob = null; busy(false); }
      if (error) reject(error); else resolve(result);
    };
    const timer = setTimeout(() => finish(null, new Error('5 秒の制限時間を超えました。入力や文法を短くして再試行してください。')), 5000);
    activeJob = {worker, timer, reject};
    worker.onmessage = ({data}) => finish(data);
    worker.onerror = event => finish(null, new Error(event.message || 'Worker を起動できません'));
    try { worker.postMessage({bytes: wasm, grammar, inputs, policy}); }
    catch (error) { finish(null, error); }
  });
}
function solved(step) { const d = state.drafts[step.id]; return d.passed && d.answer === step.question.answer; }
function progress() {
  const count = course.steps.filter(solved).length;
  $('progress').max = course.steps.length; $('progress').value = count;
  $('progress-text').textContent = `${count} / ${course.steps.length} ステップ完了`;
  $('steps').replaceChildren();
  course.steps.forEach((step, i) => {
    const button = element('button', `${solved(step) ? '✓' : String(i + 1).padStart(2, '0')}  ${step.title}`);
    if (i === index) button.setAttribute('aria-current', 'step');
    button.onclick = () => navigate(i);
    const li = document.createElement('li'); li.append(button); $('steps').append(li);
  });
  $('previous').disabled = index === 0;
  $('next').disabled = !solved(current()) || index === course.steps.length - 1;
  $('next').textContent = index === course.steps.length - 1 ? '最終ステップ' : '次のステップへ →';
  $('complete').hidden = count !== course.steps.length;
}
function answerFeedback() {
  const selected = draft().answer;
  $('answer-feedback').textContent = selected === null ? 'まず予想してみましょう。何度でも答え直せます。' : current().question.options[selected].feedback;
  $('answer-feedback').className = selected !== null && selected !== current().question.answer ? 'error' : '';
}
function policyCode() {
  $('policy-code').textContent = draft().policy === 'lazy' ? 'condition = evaluate(ast.condition)\nreturn condition != 0\n  ? evaluate(ast.yes)\n  : evaluate(ast.no)' : 'condition = evaluate(ast.condition)\nyes = evaluate(ast.yes)\nno = evaluate(ast.no)\nreturn condition != 0 ? yes : no';
}
function navigate(next, focus = true) {
  cancel(); index = next;
  const step = current(), d = draft();
  $('step-number').textContent = `STEP ${String(index + 1).padStart(2, '0')} / ${course.steps.length}`;
  $('step-title').textContent = step.title; $('subtitle').textContent = step.subtitle;
  $('explanation').replaceChildren(...step.explanation.map(p => element('p', p)));
  $('question-title').textContent = step.question.prompt;
  $('answers').replaceChildren();
  const legend = element('legend', '問いへの回答'); legend.className = 'sr-only'; $('answers').append(legend);
  step.question.options.forEach((option, i) => {
    const label = document.createElement('label'), input = document.createElement('input');
    input.type = 'radio'; input.name = 'answer'; input.value = String(i); input.checked = d.answer === i;
    input.onchange = () => { d.answer = i; save(); answerFeedback(); progress(); };
    label.append(input, element('span', option.text)); $('answers').append(label);
  });
  answerFeedback();
  $('task').textContent = step.task; $('hint').textContent = step.hint;
  $('grammar').value = d.grammar; $('input').value = d.input; $('solution').textContent = step.solution;
  $('solution').parentElement.open = false;
  $('semantics').hidden = !step.semanticsExercise; $('policy').value = d.policy; policyCode();
  $('checks').replaceChildren(); $('check-status').textContent = d.passed ? '課題の合格を保存しています。編集したら、もう一度チェックしましょう。' : '問いに答え、文法を編集してチェックしましょう。';
  $('run-status').textContent = ''; $('ast').textContent = '試すと、ここに構造が現れます。';
  progress(); save(); if (focus) $('step-title').focus();
}
function invalidate() {
  cancel(); draft().passed = false; save(); progress(); $('checks').replaceChildren();
  $('check-status').textContent = '変更を保存しました。課題をもう一度チェックしてください。';
  $('run-status').textContent = '文法・評価方法が変わりました。もう一度試してください。'; $('ast').textContent = '再解析すると表示します。';
}
function describe(result, input) {
  if (result.runtimeError) return `実行エラー: ${result.runtimeError}`;
  if (result.grammarError) return `文法を確認してください: ${result.grammarError}`;
  if (result.mappingError) return `認識成功 / AST 投影エラー: ${result.mappingError}`;
  if (!result.ok) {
    const offset = result.diagnostic?.offset ?? 0;
    const lines = [...input].slice(0, offset).join('').split('\n');
    return `不一致: ${lines.length} 行 ${[...lines.at(-1)].length + 1} 列 (code point ${offset})。期待: ${result.diagnostic?.expected?.join(' / ') || '文法を確認してください'}`;
  }
  return result.evaluationError ? `認識成功 / 評価エラー: ${result.evaluationError}` : `認識成功 / 計算結果: ${result.value}`;
}
$('grammar').oninput = () => { draft().grammar = $('grammar').value; invalidate(); };
$('policy').onchange = () => { draft().policy = $('policy').value; policyCode(); invalidate(); };
$('input').oninput = () => { if (activeJob) $('check-status').textContent = '実行を中断しました。必要なら課題を再チェックしてください。'; cancel(); draft().input = $('input').value; save(); $('run-status').textContent = '入力が変わりました。もう一度試してください。'; $('ast').textContent = '再解析すると表示します。'; };
$('previous').onclick = () => navigate(index - 1);
$('next').onclick = () => { if (solved(current()) && index + 1 < course.steps.length) navigate(index + 1); };
$('reset').onclick = () => { draft().grammar = current().starter; $('grammar').value = draft().grammar; invalidate(); };
$('download').onclick = () => {
  const url = URL.createObjectURL(new Blob([draft().grammar], {type: 'text/plain;charset=utf-8'}));
  const a = document.createElement('a'); a.href = url; a.download = `${course.id}-${current().id}.ubnf`; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
$('check').onclick = async () => {
  cancel(); const token = generation;
  draft().passed = false; save(); progress(); $('checks').replaceChildren(); $('check-status').textContent = '文法と試験入力をチェックしています…';
  try {
    const response = await job(current().cases.map(test => test.input));
    if (generation !== token) return;
    if (!response.results) { $('check-status').textContent = describe(response, ''); return; }
    response.results.forEach((result, i) => {
      const test = current().cases[i], ok = assess(test, result);
      const expectation = !test.accept ? '拒否' : test.evaluationError ? `評価エラー「${test.evaluationError}」` : `値 ${test.value}`;
      const li = element('li', `${ok ? '✓' : '×'} ${JSON.stringify(test.input)} → 期待: ${expectation}\n${describe(result, test.input)}`);
      li.className = ok ? '' : 'failed'; $('checks').append(li);
    });
    const passed = response.results.length === current().cases.length && response.results.every((result, i) => assess(current().cases[i], result));
    draft().passed = passed; save(); progress();
    $('check-status').textContent = passed ? draft().answer === current().question.answer ? '合格！ 説明と結果を確かめたら、次へ進めます。' : '実習は合格です。問いにも答えて理解を確認しましょう。' : 'まだ合格していない試験があります。期待と結果を比べ、ヒントも使って直してみましょう。';
  } catch (error) { if (generation === token) $('check-status').textContent = `実行エラー: ${error.message}`; }
};
$('run').onclick = async () => {
  cancel(); const token = generation, input = draft().input;
  $('run-status').textContent = '解析しています…';
  try {
    const response = await job([input]);
    if (generation !== token) return;
    const result = response.results?.[0] || response;
    $('run-status').textContent = describe(result, input);
    $('ast').textContent = JSON.stringify(result.ast || result, null, 2);
  } catch (error) { if (generation === token) $('run-status').textContent = `実行エラー: ${error.message}`; }
};
async function loadCourse(file) {
  const request = ++courseRequest; cancel(); $('workspace').hidden = true; $('load-status').textContent = '教材を読み込んでいます…';
  try {
    const response = await fetch(file); if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const next = validateCourse(await response.json()); if (request !== courseRequest) return;
    course = next; storageKey = `ubnf-learning:${course.id}:v${course.version}`;
    restore(); $('description').textContent = `${course.description} ${course.duration}`;
    document.title = `${course.title} · UBNF Playground`;
    $('workspace').hidden = false; $('load-status').textContent = '準備完了。文法を自分で編集しながら進めましょう。'; navigate(index, false);
  } catch (error) { if (request === courseRequest) $('load-status').textContent = `教材を開けません: ${error.message}。再読み込みしてください。`; }
}
try {
  const [manifest, binary] = await Promise.all([fetch('courses.json'), fetch('learning.wasm')]);
  if (!manifest.ok || !binary.ok) throw new Error('教材または WASM を取得できません');
  wasm = await binary.arrayBuffer();
  const courses = await manifest.json();
  if (!Array.isArray(courses) || !courses.length || courses.some(c => !/^[a-z0-9-]+\.json$/.test(c.file))) throw new Error('シナリオ一覧が不正です');
  for (const item of courses) { const option = element('option', item.title); option.value = item.file; $('course').append(option); }
  $('course').disabled = false; $('course').onchange = () => loadCourse($('course').value);
  await loadCourse(courses[0].file);
} catch (error) { $('load-status').textContent = `読み込みに失敗しました: ${error.message}。再読み込みしてください。`; }
addEventListener('pagehide', cancel);
