import {complete, vocabulary, insertion} from './completion-engine.mjs';

const input = document.getElementById('input');
const button = document.getElementById('complete-input');
const panel = document.getElementById('completion-panel');
const list = document.getElementById('completion-list');
const description = document.getElementById('completion-description');
const status = document.getElementById('completion-status');
let words, result, snapshot, cursor, active = 0, composing = false, applying = false;

function close() {
  panel.hidden = true;
  button.setAttribute('aria-expanded', 'false');
  input.removeAttribute('aria-activedescendant');
  result = null;
}

function select(index) {
  active = (index + result.items.length) % result.items.length;
  [...list.children].forEach((node, i) => node.setAttribute('aria-selected', String(i === active)));
  const node = list.children[active];
  input.setAttribute('aria-activedescendant', node.id);
  description.textContent = result.items[active].detail;
  // Scroll the list, without moving the page or the editor's caret.
  if (node.offsetTop < list.scrollTop) list.scrollTop = node.offsetTop;
  else if (node.offsetTop + node.offsetHeight > list.scrollTop + list.clientHeight) list.scrollTop = node.offsetTop + node.offsetHeight - list.clientHeight;
}

function show(manual = false) {
  close();
  if (!words || composing || input.selectionStart !== input.selectionEnd) return;
  snapshot = input.value; cursor = input.selectionStart;
  const candidates = complete(snapshot, cursor, words);
  if (!manual && candidates.from === cursor) return;
  if (!manual && candidates.to === cursor) candidates.items = candidates.items.filter(item => item.insertText !== snapshot.slice(candidates.from, cursor));
  if (!candidates.items.length) {
    if (manual) status.textContent = 'この位置には補完候補がありません。文法の空行や名前の途中で試してください。';
    return;
  }
  result = {...candidates, items: candidates.items.slice(0, 30)};
  list.replaceChildren();
  result.items.forEach((item, i) => {
    const option = document.createElement('li');
    option.id = `completion-option-${i}`; option.setAttribute('role', 'option');
    const label = document.createElement('strong'); label.textContent = item.label;
    const kind = document.createElement('span'); kind.textContent = item.kind;
    option.append(label, kind);
    option.addEventListener('pointerdown', event => event.preventDefault());
    option.addEventListener('click', () => accept(i));
    list.append(option);
  });
  panel.hidden = false; button.setAttribute('aria-expanded', 'true');
  select(0);
  status.textContent = `${candidates.items.length} 件の候補。上下キーで選び、Enter / Tab で挿入、Esc で閉じます。` + (candidates.items.length > 30 ? '先頭30件を表示しています。名前を入力すると絞り込めます。' : '');
}

function accept(index) {
  if (!result || composing) return;
  if (input.value !== snapshot || input.selectionStart !== cursor || input.selectionEnd !== cursor) { close(); return; }
  const item = result.items[index];
  const text = insertion(snapshot, result, item);
  const {from, to} = result;
  close(); applying = true; input.focus(); input.setSelectionRange(from, to);
  // Native insertion preserves the browser's undo history; setRangeText is the fallback.
  try { document.execCommand('insertText', false, text); } catch { /* Unsupported editing command. */ }
  if (input.value === snapshot) input.setRangeText(text, from, to, 'end');
  input.dispatchEvent(new Event('input', {bubbles: true}));
  applying = false;
  status.textContent = `${item.label} を挿入しました。内容を編集し、「解析する」で確かめてください。`;
}

button.addEventListener('click', () => { input.focus(); show(true); });
input.addEventListener('input', event => {
  if (applying || composing || event.isComposing || document.activeElement !== input || (event.inputType && !['insertText', 'deleteContentBackward'].includes(event.inputType))) { close(); return; }
  show();
});
input.addEventListener('compositionstart', () => { composing = true; close(); });
input.addEventListener('compositionend', () => { composing = false; close(); });
input.addEventListener('pointerdown', close);
input.addEventListener('blur', close);
input.addEventListener('keydown', event => {
  if (composing || event.isComposing || event.keyCode === 229) return;
  if ((event.ctrlKey || event.metaKey) && event.code === 'Space') { event.preventDefault(); show(true); return; }
  if (!result) return;
  if (event.key === 'Escape') { event.preventDefault(); close(); status.textContent = '補完候補を閉じました。'; }
  else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); select(active + (event.key === 'ArrowDown' ? 1 : -1)); }
  else if (event.key === 'Enter' || (event.key === 'Tab' && !event.shiftKey)) { event.preventDefault(); accept(active); }
  else if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown', 'Tab'].includes(event.key) || event.ctrlKey || event.metaKey) close();
});
document.getElementById('clear').addEventListener('click', close);
document.addEventListener('pointerdown', event => { if (event.target !== input && event.target !== button && !panel.contains(event.target)) close(); });

try {
  const response = await fetch('help/catalog.json');
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  words = vocabulary(await response.json()); button.disabled = false;
  status.textContent = '入力すると候補を表示します。Ctrl+Space または「補完候補」でも開けます。';
} catch {
  status.textContent = '補完用の説明を取得できませんでした。文法の入力・解析は引き続き使えます。';
}
