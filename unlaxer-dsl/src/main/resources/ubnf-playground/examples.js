'use strict';
(() => {
  const input = document.getElementById('input');
  const select = document.getElementById('example');
  const button = document.getElementById('load-example');
  const status = document.getElementById('example-status');
  let edited = false;
  input.addEventListener('input', () => { edited = true; });
  document.getElementById('clear').addEventListener('click', () => { edited = true; });
  async function load(url, json = false) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return json ? response.json() : response.text();
  }
  Promise.all([load('help/catalog.json', true), load('grammar.ubnf')]).then(([catalog, grammar]) => {
    const examples = catalog.examples.map(example => ({
      id: example.id,
      title: catalog.lessons.find(lesson => lesson.example === example.id).title,
      source: example.source
    }));
    examples.push(
      {id: 'ubnf', title: 'UBNF 自身の定義', source: grammar},
      {id: 'broken', title: '構文エラーの例（; がない）', source: "grammar Broken {\n  @ubnf: v2\n  @package: example.broken\n  @root\n  Start ::= 'hello'\n}\n"}
    );
    for (const example of examples) {
      const option = document.createElement('option');
      option.value = example.id; option.textContent = example.title; select.append(option);
    }
    function useExample() {
      input.value = examples.find(example => example.id === select.value).source;
      input.dispatchEvent(new Event('input', {bubbles: true}));
      status.textContent = '選んだ例を読み込みました。編集して「解析する」を押してください。別の例を読み込むと入力欄を置き換えます。';
    }
    select.disabled = false; button.disabled = false;
    button.addEventListener('click', () => { useExample(); input.focus(); });
    status.textContent = '「例を入力欄に読み込む」を押すと、現在の入力欄を選んだ文法で置き換えます。';
    // Loading examples must not discard text entered while their request was pending.
    if (!edited && !input.value) useExample();
  }).catch(() => {
    status.textContent = '文法例を読み込めませんでした。入力欄に文法を直接書いて解析できます。';
  });
})();
