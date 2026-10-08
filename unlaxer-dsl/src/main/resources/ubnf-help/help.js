/* The same offline assets are used by the VSIX and generated playgrounds. */
'use strict';
(() => {
  const host = typeof acquireVsCodeApi === 'function' ? acquireVsCodeApi() : null;
  const byId = id => document.getElementById(id);
  const node = (tag, text, className) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    if (className) element.className = className;
    return element;
  };
  const button = (label, action, primary = false) => {
    const element = node('button', label, primary ? 'primary' : '');
    element.type = 'button'; element.addEventListener('click', action); return element;
  };
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); byId('status').textContent = 'コピーしました。'; }
    catch { byId('status').textContent = 'コピーできませんでした。コードを選択してコピーしてください。'; }
  }
  function openExample(example) {
    if (host) { host.postMessage({type: 'openExample', id: example.id}); return; }
    const url = URL.createObjectURL(new Blob([example.source], {type: 'text/plain;charset=utf-8'}));
    const link = node('a'); link.href = url; link.download = example.name + '.ubnf'; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    byId('status').textContent = '文法ファイルをダウンロードしました。UBNF 拡張を入れた VS Code で開けます。';
  }
  function showLesson(catalog, lesson) {
    for (const item of byId('lessons').children) item.setAttribute('aria-pressed', String(item.dataset.lesson === lesson.id));
    const article = byId('lesson'); article.replaceChildren(node('h2', lesson.title), node('p', lesson.goal));
    const steps = node('ol'); for (const step of lesson.steps) steps.append(node('li', step)); article.append(steps);
    const example = catalog.examples.find(item => item.id === lesson.example);
    article.append(node('h3', 'このまま使える文法（全文）'), node('pre', example.source));
    const actions = node('div', undefined, 'actions');
    actions.append(button(host ? '新規文書で開く' : '.ubnf をダウンロード', () => openExample(example), true), button('文法をコピー', () => copy(example.source)));
    article.append(actions, node('h3', '試験入力と期待する結果'));
    const cases = node('ul', undefined, 'cases');
    for (const item of example.cases) {
      const row = node('li'); const ok = Object.hasOwn(item, 'fields');
      row.append(node('span', ok ? '成功' : '失敗', ok ? 'pass' : 'fail'), node('code', item.input === '' ? '（空の入力）' : item.input)); cases.append(row);
    }
    article.append(cases, node('p', 'これは共通テストの期待値です。このヘルプ画面で解析を実行した結果ではありません。'), node('p', lesson.check, 'check'));
    const index = catalog.lessons.indexOf(lesson);
    if (index + 1 < catalog.lessons.length) article.append(button('次のレッスン →', () => showLesson(catalog, catalog.lessons[index + 1])));
  }
  function renderEntries(catalog) {
    const query = byId('search').value.trim().toLocaleLowerCase();
    const entries = catalog.entries.filter(entry => JSON.stringify(entry).toLocaleLowerCase().includes(query));
    byId('entries').replaceChildren();
    byId('count').textContent = `${entries.length} 件 / ${catalog.entries.length} 件${entries.length ? ' · クリックすると詳しい説明が開きます。' : ' · 「数字」「空白」「反復」など短い語で検索してください。'}`;
    for (const entry of entries) {
      const detail = node('details'); detail.open = query.length > 0;
      detail.append(node('summary', entry.title), node('span', entry.category, 'category'), node('p', entry.summary), node('pre', entry.syntax));
      for (const paragraph of entry.details) detail.append(node('p', paragraph));
      if (entry.insertText) detail.append(button('コードをコピー', () => copy(entry.insertText)));
      byId('entries').append(detail);
    }
  }
  async function start() {
    const response = await fetch(document.body.dataset.catalog || 'catalog.json');
    if (!response.ok) throw new Error(`catalog: HTTP ${response.status}`);
    const catalog = await response.json();
    byId('intro').textContent = catalog.intro;
    for (const text of catalog.workflow) byId('workflow').append(node('li', text));
    for (const text of catalog.setup) byId('setup').append(node('p', text));
    if (host) {
      byId('generate').hidden = false;
      byId('generate').addEventListener('click', () => host.postMessage({type: 'generatePlayground'}));
    }
    for (const lesson of catalog.lessons) {
      const item = button(lesson.title, () => showLesson(catalog, lesson)); item.dataset.lesson = lesson.id;
      byId('lessons').append(item);
    }
    for (const item of catalog.troubleshooting) {
      const article = node('article'); article.append(node('h3', item.title), node('p', item.why), node('p', item.fix, 'check')); byId('errors').append(article);
    }
    for (const tab of document.querySelectorAll('[data-tab]')) tab.addEventListener('click', () => {
      for (const other of document.querySelectorAll('[data-tab]')) other.setAttribute('aria-pressed', String(other === tab));
      for (const page of document.querySelectorAll('.page')) page.hidden = page.id !== tab.dataset.tab;
    });
    byId('search').addEventListener('input', () => renderEntries(catalog));
    showLesson(catalog, catalog.lessons[0]); renderEntries(catalog);
  }
  start().catch(error => { byId('intro').textContent = 'catalog を読み込めませんでした。VS Code では拡張を再インストールしてください。単独利用では HTTP サーバーから開いてください。'; byId('status').textContent = String(error); });
})();
