'use strict';
(() => {
  const host = typeof acquireVsCodeApi === 'function' ? acquireVsCodeApi() : null;
  const $ = id => document.getElementById(id);
  const element = (tag, text, className) => {
    const node = document.createElement(tag); if (text !== undefined) node.textContent = text;
    if (className) node.className = className; return node;
  };
  let worker, wasm, workerSource, workerUrl, catalog, request = 0, timeout, activeSource = '';
  function stop() { clearTimeout(timeout); worker?.terminate(); worker = null; if (workerUrl) URL.revokeObjectURL(workerUrl); }
  function fail(message) {
    host?.postMessage({type: 'error', error: message});
    $('status').textContent = `実行エラー：${message}`; $('hint').hidden = false;
    $('hint').textContent = '受理・拒否の判定は完了していません。入力を短くして再試行してください。';
    $('parse').disabled = !wasm;
  }
  function renderCatalog() {
    if (!catalog) return;
    const query = $('catalog-search').value.toLocaleLowerCase(); $('catalog').replaceChildren();
    let count = 0;
    catalog.rules.forEach((rule, index) => {
      if (!(rule.name + rule.docs.join(' ')).toLocaleLowerCase().includes(query)) return;
      count++;
      const card = element('article'); card.append(element('h3', rule.name + (index === catalog.root ? ' · 入口' : '')));
      for (const text of rule.docs.length ? rule.docs : ['説明は未記載です。文法のこのルールに @doc を付けると、ここに表示されます。']) card.append(element('p', text));
      $('catalog').append(card);
    });
    if (!count) $('catalog').append(element('p', '一致するルールがありません。短い語で検索してください。'));
  }
  function location(source, offset) {
    const before = [...source].slice(0, offset).join(''); const lines = before.split('\n');
    return `${lines.length} 行 ${[...lines.at(-1)].length + 1} 列 (code point ${offset})`;
  }
  function render(value) {
    $('result').textContent = JSON.stringify(value, null, 2);
    $('hint').hidden = true; $('position').textContent = ''; $('ast').textContent = 'AST はありません。'; $('cst').replaceChildren();
    if (value.runtimeError) { fail(value.runtimeError); return; }
    const stale = $('input').value !== activeSource;
    $('status').textContent = (stale ? '前の入力の結果（入力が変更されています）：' : '') + (value.ok ? '成功：入力全体を読み取りました。' : '不一致：この入力は文法を満たしていません。');
    $('position').textContent = `部分解析の消費位置：${value.prefix[1]} / ${[...activeSource].length} code points。最大一致位置：${value.prefix[2]}。`;
    if (!value.ok) {
      $('hint').hidden = false;
      $('hint').textContent = `${location(activeSource, value.diagnostic.offset)}：${value.diagnostic.kind}。期待：${value.diagnostic.expected.join(' / ') || '文法の条件を確認してください。'}`;
      return;
    }
    $('ast').textContent = value.mappingError ? `AST の投影に失敗：${value.mappingError}` : JSON.stringify(value.ast, null, 2);
    $('ast-panel').open = true;
    const points = [...activeSource];
    value.cst.nodes.slice(0, 500).forEach((node, index) => {
      const row = element('div', undefined, 'cst-row'); const select = element('button', `#${index} ${node.rule} [${node.span.join(', ')})${index === value.cst.root ? ' · root' : ''}`);
      select.type = 'button';
      const snapshot = activeSource;
      select.addEventListener('click', () => {
        if ($('input').value !== snapshot) { $('hint').hidden = false; $('hint').textContent = '入力が変更されています。もう一度解析してから範囲を選択してください。'; return; }
        $('input').focus(); $('input').setSelectionRange(points.slice(0, node.span[0]).join('').length, points.slice(0, node.span[1]).join('').length);
      });
      row.append(select, element('code', points.slice(node.span[0], node.span[1]).join('')));
      if (node.children.length) row.append(element('span', `子 node: ${node.children.join(', ')}`, 'small'));
      $('cst').append(row);
    });
    if (value.cst.nodes.length > 500) $('cst').append(element('p', '表示は先頭 500 node までです。全体は生の JSON で確認できます。'));
  }
  function send(type, extra = {}) {
    const id = ++request;
    timeout = setTimeout(() => { stop(); fail('3 秒の制限時間を超えたため Worker を停止しました。'); }, 3000);
    worker.postMessage({id, type, ...extra});
  }
  function startWorker(afterReady) {
    stop(); workerUrl = URL.createObjectURL(new Blob([workerSource], {type: 'text/javascript'})); worker = new Worker(workerUrl);
    worker.onerror = event => { stop(); fail(event.message); };
    worker.onmessage = event => {
      if (event.data.id !== request) return;
      clearTimeout(timeout);
      if (event.data.error) { stop(); fail(event.data.error); return; }
      if (event.data.catalog) {
        catalog = event.data.catalog;
        $('name').textContent = document.body.dataset.heading || `${catalog.name} · 言語を試す`;
        document.title = document.body.dataset.title || `${catalog.name} · UBNF Playground`;
        $('engine').textContent = '準備完了：生成 Rust parser を WebAssembly で実行します。入力はブラウザの中だけで処理します。';
        renderCatalog(); $('parse').disabled = false; afterReady?.();
        host?.postMessage({type: 'ready'});
      } else { $('parse').disabled = false; render(event.data.result); }
    };
    send('init', {bytes: wasm});
  }
  function parse() {
    activeSource = $('input').value;
    if (new TextEncoder().encode(activeSource).length > 65536) { fail('入力は 64 KiB (UTF-8) 以下にしてください。'); return; }
    const run = () => { $('parse').disabled = true; $('status').textContent = '解析中…'; send('parse', {input: activeSource}); };
    if (worker) run(); else startWorker(run);
  }
  $('parse').addEventListener('click', parse);
  $('clear').addEventListener('click', () => { $('input').value = ''; $('input').focus(); $('status').textContent = '入力を空にしました。解析するボタンで空入力を確認できます。'; });
  $('input').addEventListener('input', () => { $('status').textContent = '入力が変更されています。解析するボタンで確認してください。'; });
  $('catalog-search').addEventListener('input', renderCatalog);
  async function load(name, binary = false) {
    const response = await fetch(name); if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
    return binary ? response.arrayBuffer() : response.text();
  }
  if (host) document.querySelector('header a').addEventListener('click', event => { event.preventDefault(); host.postMessage({type: 'openHelp'}); });
  Promise.all([load(document.body.dataset.wasm || 'language.wasm', true), load(document.body.dataset.worker || 'worker.js'), load(document.body.dataset.grammar || 'grammar.ubnf')]).then(([bytes, source, grammar]) => {
    wasm = bytes; workerSource = source; $('grammar').textContent = grammar; startWorker();
  }).catch(error => { $('engine').textContent = 'parser を読み込めませんでした。生成 project で npm run build → npm start を実行し、表示された URL を開いてください。'; fail(String(error)); });
  addEventListener('pagehide', stop);
})();
