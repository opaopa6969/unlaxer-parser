'use strict';
(() => {
  const host = typeof acquireVsCodeApi === 'function' ? acquireVsCodeApi() : null;
  const $ = id => document.getElementById(id);
  const element = (tag, text, className) => {
    const node = document.createElement(tag); if (text !== undefined) node.textContent = text;
    if (className) node.className = className; return node;
  };
  let worker, wasm, workerSource, workerUrl, catalog, request = 0, timeout, activeSource = '', inputRevision = 0, activeRevision = 0;
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
  function renderVocabulary(snapshot) {
    const panel = $('vocabulary'); if (!panel) return; panel.replaceChildren();
    for (const policy of snapshot.whitespace) panel.append(element('p', `${policy.grammar}${policy.rule ? '.' + policy.rule : ' (既定)'}: ${policy.policy}`));
    for (const module of snapshot.modules) {
      const identity = module.identity;
      panel.append(element('h3', module.alias + ' · ' + (identity ? `${identity.id}@${identity.version}` : module.source)));
      if (identity) panel.append(element('p', `sha256 ${identity.sha256} · ${identity.file}`, 'small'));
      const codepoints = [...module.text];
      for (const definition of module.definitions) {
        const entry = element('details'); entry.append(element('summary', module.alias + '.' + definition.name));
        entry.append(element('pre', codepoints.slice(definition.start, definition.end).join(''))); panel.append(entry);
      }
    }
  }
  function location(source, offset) {
    const before = [...source].slice(0, offset).join(''); const lines = before.split('\n');
    return `${lines.length} 行 ${[...lines.at(-1)].length + 1} 列 (code point ${offset})`;
  }
  const queryPanel = element('section'); queryPanel.id = 'query-panel'; queryPanel.hidden = true;
  $('typed-completions')?.after(queryPanel);
  const queryOperations = {VALIDATE: [0, '診断'], COMPLETION: [1, '補完'], HOVER: [2, '型と説明'], DEFINITION: [3, '定義'], RENAME: [4, '名前を変更'], FORMAT: [5, '整形'], CODE_ACTION: [6, '修正候補']};
  function queryFailure(message) { $('hint').hidden = false; $('hint').textContent = message; }
  function cursorOffset(source) {
    const offset = $('input').selectionStart;
    if (offset > 0 && offset < source.length && /[\uD800-\uDBFF]/.test(source[offset - 1]) && /[\uDC00-\uDFFF]/.test(source[offset])) throw new Error('カーソルを文字の境界へ移動してください。');
    return [...source.slice(0, offset)].length;
  }
  function renderQuery(view) {
    queryPanel.replaceChildren(); queryPanel.hidden = !view;
    if (!view) return;
    queryPanel.append(element('h3', '言語の操作'));
    if (view.runtimeError) { queryPanel.append(element('p', view.runtimeError)); return; }
    const snapshot = activeSource, revision = activeRevision, version = String(request);
    const fresh = () => $('input').value === snapshot && inputRevision === revision && String(request) === version && view.version === version;
    const capabilities = Array.isArray(view.capabilities) ? view.capabilities.filter(name => queryOperations[name]) : [];
    queryPanel.append(element('p', `${view.region || 'この位置'} · ${view.state} · ${capabilities.map(name => queryOperations[name][1]).join(' / ') || '利用できる操作がありません'}`));
    const controls = element('div'); controls.className = 'actions';
    const renameLabel = element('label', '新しい名前：'), rename = element('input'); rename.type = 'text'; rename.setAttribute('aria-label', '新しい名前'); renameLabel.append(rename);
    if (capabilities.includes('RENAME')) queryPanel.append(renameLabel);
    for (const name of capabilities) {
      const button = element('button', queryOperations[name][1]); button.type = 'button'; button.dataset.operation = name;
      button.addEventListener('click', () => {
        if (!fresh()) { queryFailure('入力が変更されています。もう一度解析してください。'); return; }
        let argument = '';
        const source = $('input').value, offset = $('input').selectionStart;
        if (name === 'RENAME') argument = rename.value;
        if (['COMPLETION', 'HOVER', 'DEFINITION'].includes(name)) {
          const before = source.slice(0, offset).match(/[\p{ID_Continue}]+$/u)?.[0] || '';
          const after = source.slice(offset).match(/^[\p{ID_Continue}]+/u)?.[0] || '';
          argument = name === 'COMPLETION' ? before : before + after;
        }
        query(queryOperations[name][0], argument);
      }); controls.append(button);
    }
    queryPanel.append(controls);
    for (const item of view.items || []) {
      const row = element('article'); row.append(element('p', `${item.label}${item.detail ? ' · ' + item.detail : ''}`));
      for (const location of item.locations || []) row.append(element('p', `${location.uri} [${location.span.join(', ')})${location.exact ? '' : ' · おおよその位置'}`, 'small'));
      if (item.edits?.length && ['COMPLETE', 'PARTIAL'].includes(view.state)) {
        const apply = element('button', `${item.label} を適用`); apply.type = 'button'; apply.dataset.queryApply = item.label;
        apply.addEventListener('click', () => {
          if (!fresh()) { queryFailure('この編集は古い入力の結果です。もう一度解析してください。'); return; }
          const points = [...snapshot], edits = [...item.edits].sort((a, b) => a.span[0] - b.span[0] || a.span[1] - b.span[1]);
          let previous = null;
          for (const edit of edits) {
            const [start, end] = edit.span || [];
            if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || end > points.length || typeof edit.replacement !== 'string' || (previous && (start < previous[1] || start === previous[0]))) {
              queryFailure('編集範囲が不正か重複しています。適用できません。'); return;
            }
            previous = [start, end];
          }
          for (const edit of edits.reverse()) points.splice(edit.span[0], edit.span[1] - edit.span[0], ...edit.replacement);
          $('input').value = points.join(''); ++inputRevision; ++request;
          $('input').focus(); queryPanel.hidden = true; $('status').textContent = '編集を適用しました。解析するボタンで確認してください。';
        }); row.append(apply);
      }
      queryPanel.append(row);
    }
  }
  function query(operation, argument) {
    activeSource = $('input').value; activeRevision = inputRevision;
    let cursor; try { cursor = cursorOffset(activeSource); } catch (error) { queryFailure(error.message); return; }
    const run = () => { $('parse').disabled = true; send('query', {input: activeSource, cursor, operation, argument}); };
    if (worker) run(); else startWorker(run);
  }
  function render(value) {
    renderQuery(value.query);
    $('result').textContent = JSON.stringify(value, null, 2);
    $('languages-panel').hidden = !value.languages;
    $('languages').replaceChildren();
    if (value.languages) for (const region of value.languages.regions) {
      const labels = {COMPLETE: '解析済み', PARTIAL: '編集中', FAILED: '構文エラー', UNAVAILABLE: '解析器が未登録', UNSUPPORTED: '未対応', TIMEOUT: '時間切れ'};
      const button = element('button', `${region.grammar} · ${labels[region.state] || region.state}`);
      button.type = 'button'; const snapshot = activeSource;
      button.addEventListener('click', () => {
        if ($('input').value !== snapshot) { $('hint').hidden = false; $('hint').textContent = '入力が変更されています。もう一度解析してください。'; return; }
        const points = [...snapshot]; $('input').focus(); $('input').setSelectionRange(points.slice(0, region.body[0]).join('').length, points.slice(0, region.body[1]).join('').length);
      });
      $('languages').append(button);
    }
    $('typed-completions').hidden = !value.typed;
    if (value.typed) $('typed-completions').textContent = `期待型：${value.typed.expectedTypes.join(' / ') || '不明'}。補完：${value.typed.completions.map(item => item.label).join(' / ') || '候補なし'}。`;
    $('hint').hidden = true; $('position').textContent = ''; $('ast').textContent = 'AST はありません。'; $('cst').replaceChildren();
    if (value.runtimeError) { fail(value.runtimeError); return; }
    const stale = $('input').value !== activeSource;
    $('status').textContent = (stale ? '前の入力の結果（入力が変更されています）：' : '') + (value.ok ? '成功：入力全体を読み取りました。' : '不一致：この入力は文法を満たしていません。');
    $('position').textContent = `部分解析の消費位置：${value.prefix[1]} / ${[...activeSource].length} code points。最大一致位置：${value.prefix[2]}。`;
    if (!value.ok) {
      $('hint').hidden = false;
      $('hint').textContent = `${location(activeSource, value.diagnostic.offset)}：${value.diagnostic.kind}。期待：${value.diagnostic.expected.join(' / ') || '文法の条件を確認してください。'}`;
      if (!value.editor || value.editor.status === 'FAILED') return;
    }
    if (value.editor && value.editor.status === 'PARTIAL') {
      $('status').textContent = (stale ? '前の入力の結果：' : '') + '部分結果：入力は編集途中です。';
      value.cst = {root: -1, nodes: value.editor.nodes};
      $('ast').textContent = '編集途中です。missing / error node は生の解析結果で確認できます。';
    } else $('ast').textContent = value.mappingError ? `AST の投影に失敗：${value.mappingError}` : JSON.stringify(value.ast, null, 2);
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
      } else { $('parse').disabled = false; if (event.data.query) renderQuery(event.data.query); else render(event.data.result); }
    };
    send('init', {bytes: wasm});
  }
  function parse() {
    activeSource = $('input').value; activeRevision = inputRevision;
    let cursor; try { cursor = cursorOffset(activeSource); } catch (error) { queryFailure(error.message); return; }
    if (new TextEncoder().encode(activeSource).length > 65536) { fail('入力は 64 KiB (UTF-8) 以下にしてください。'); return; }
    const run = () => { $('parse').disabled = true; $('status').textContent = '解析中…'; send('parse', {input: activeSource, editor: $('editor-mode').checked, cursor}); };
    if (worker) run(); else startWorker(run);
  }
  $('parse').addEventListener('click', parse);
  $('clear').addEventListener('click', () => { $('input').value = ''; ++inputRevision; $('input').focus(); $('status').textContent = '入力を空にしました。解析するボタンで空入力を確認できます。'; });
  $('input').addEventListener('input', () => { ++inputRevision; $('status').textContent = '入力が変更されています。解析するボタンで確認してください。'; });
  $('catalog-search').addEventListener('input', renderCatalog);
  async function load(name, binary = false) {
    const response = await fetch(name); if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
    return binary ? response.arrayBuffer() : response.text();
  }
  if (document.body.dataset.profile) {
    $('profile-panel').hidden = false;
    load(document.body.dataset.profile).then(text => {
      const rows = text.trimEnd().split('\n').map(line => line.split('\t'));
      const value = name => rows.find(row => row[0] === name)?.slice(1).join(' · ') || '';
      $('profile').append(element('p', `${value('language')} · 対象 ${value('target')} · ${value('package')}`));
      const labels = {SUPPORTED: '対応', PARTIAL: '部分対応', EXTERNAL: '外部解析器が必要', UNSUPPORTED: '未対応'};
      const categories = {entry: '入口', capability: '操作', syntax: '構文'};
      const table = element('table');
      for (const row of rows.filter(row => categories[row[0]])) {
        const line = element('tr'); line.append(element('th', categories[row[0]]), element('td', row[1]), element('td', labels[row[2]])); table.append(line);
      }
      $('profile').append(table);
    })
      .catch(() => { $('profile').textContent = '対応範囲を読み込めません。解析結果から対応能力を推測しないでください。'; });
  }
  if (host) document.querySelector('header a').addEventListener('click', event => { event.preventDefault(); host.postMessage({type: 'openHelp'}); });
  Promise.all([load(document.body.dataset.wasm || 'language.wasm', true), load(document.body.dataset.worker || 'worker.js'), load(document.body.dataset.grammar || 'grammar.ubnf'), load(document.body.dataset.vocabulary || 'vocabulary.json')]).then(([bytes, source, grammar, origins]) => {
    wasm = bytes; workerSource = source; $('grammar').textContent = grammar; renderVocabulary(JSON.parse(origins)); startWorker();
  }).catch(error => { $('engine').textContent = 'parser を読み込めませんでした。生成 project で npm run build → npm start を実行し、表示された URL を開いてください。'; fail(String(error)); });
  addEventListener('pagehide', stop);
})();
