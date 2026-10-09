// Editing assistance only: recognition and validation stay in the generated parser.
function scan(source, cursor) {
  const code = source.split('');
  let blocked = false;
  for (let i = 0; i < source.length;) {
    const start = i;
    let closed = true;
    if (source[i] === "'") {
      i++; closed = false;
      while (i < source.length) {
        if (source[i] === '\\') { i = Math.min(i + 2, source.length); continue; }
        if (source[i++] === "'") { closed = true; break; }
      }
    } else if (source.startsWith('//', i)) {
      i += 2; while (i < source.length && !/[\r\n]/.test(source[i])) i++;
      closed = false;
    } else if (source.startsWith('/*', i)) {
      const end = source.indexOf('*/', i + 2);
      closed = end >= 0; i = closed ? end + 2 : source.length;
    } else { i++; continue; }
    if (cursor > start && (cursor < i || (!closed && cursor === i))) blocked = true;
    for (let j = start; j < i; j++) if (!/[\r\n]/.test(code[j])) code[j] = ' ';
  }
  return {code: code.join(''), blocked};
}

function grammarAt(code, cursor) {
  for (const match of code.matchAll(/\bgrammar\s+[A-Za-z_]\w*\s*\{/g)) {
    const start = match.index + match[0].length;
    let depth = 1, end = start;
    for (; end < code.length; end++) {
      if (code[end] === '{') depth++;
      if (code[end] === '}' && --depth === 0) break;
    }
    if (cursor >= start && cursor <= end) return {start, end};
  }
  return null;
}

export function vocabulary(catalog) {
  const entries = new Map(catalog.entries.map(entry => [entry.id, entry]));
  const item = (label, entryId, insertText = entries.get(entryId)?.insertText) => ({
    label, insertText, detail: entries.get(entryId)?.summary || '', kind: '構文'
  });
  return {
    grammar: [item('grammar', 'grammar')],
    declarations: [item('token', 'token'), item('rule', 'rule'), ...['root', 'mapping', 'doc', 'import'].map(id => item('@' + id, id)),
      item('@ubnf', 'settings', '@ubnf: v2'), item('@package', 'settings', '@package: guide.demo'),
      item('@whitespace', 'whitespace', '@whitespace: javaStyle')],
    lexical: ['CHAR_RANGE', 'NEGATION', 'ANY', 'CAPTURE'].map(id => item(id, id)).concat([
      item('LOOKAHEAD', 'LOOKAHEAD', "LOOKAHEAD('end')"), item('NEGATIVE_LOOKAHEAD', 'LOOKAHEAD'),
      item('SAME_AS', 'CAPTURE', 'SAME_AS(mark)'),
      ...['BOF', 'EOF', 'BOL', 'EOL'].map(label => item(label, 'positions', label))
    ]),
    ruleBody: [item('choice', 'choice'), item('optional', 'repeat', "[ 'hello' ]"), item('repeat', 'repeat', "{ 'hello' }")],
    whitespace: [
      {label: 'javaStyle', insertText: 'javaStyle', kind: '設定値', detail: '空白・タブ・改行・復帰・垂直タブ・改ページと、// 行コメント・/* … */ コメントをルールの境界で読み飛ばします。token 内は読み飛ばしません。'},
      {label: 'none', insertText: 'none', kind: '設定値', detail: 'この空白設定による自動読み飛ばしを無効にします。必要な空白は文法に書きます。'}
    ]
  };
}

export function complete(source, cursor, words) {
  const empty = {from: cursor, to: cursor, items: []};
  if (source.length > 65536 || cursor < 0 || cursor > source.length) return empty;
  const {code, blocked} = scan(source, cursor);
  if (blocked) return empty;
  const prefix = source.slice(0, cursor).match(/@?[A-Za-z_]\w*$|@$/)?.[0] || '';
  const from = cursor - prefix.length;
  let to = cursor;
  while (to < source.length && /[A-Za-z_0-9]/.test(source[to])) to++;
  // External modules are not loaded by this single-document playground.
  if (source[from - 1] === '.') return empty;
  const before = code.slice(0, from);
  let items;
  const scope = grammarAt(code, cursor);
  if (/@whitespace\s*(?::|\()\s*$/.test(before)) {
    const local = scope ? code.slice(scope.start, scope.end) : '';
    const definitions = [...local.matchAll(/\btoken\s+([A-Za-z_]\w*)\s*::=/g)].map(match => ({
      label: match[1], insertText: match[1], kind: 'token', detail: 'この grammar 内の宣言的 token を連接境界で繰り返し読み飛ばします。non-nullable が必要です。'
    }));
    items = words.whitespace.concat(definitions);
  } else if (!scope) {
    items = words.grammar;
  } else {
    const local = code.slice(scope.start, scope.end);
    const definitions = [...local.matchAll(/\b(token\s+)?([A-Za-z_]\w*)\s*::=/g)].map(match => ({
      label: match[2], insertText: match[2], kind: match[1] ? 'token' : 'ルール',
      detail: 'この grammar 内の定義', start: scope.start + match.index, body: scope.start + match.index + match[0].length
    }));
    const body = definitions.findLast(definition => definition.body <= from && !code.slice(definition.body, from).includes(';'));
    if (body && prefix.startsWith('@') && body.kind === 'ルール') {
      const annotations = code.slice(code.lastIndexOf(';', body.start) + 1, body.start);
      const params = [...annotations.matchAll(/@mapping\([^)]*params\s*=\s*\[([^\]]*)\]/g)].at(-1)?.[1] || '';
      items = params.split(',').map(name => name.trim()).filter(name => /^[A-Za-z_]\w*$/.test(name)).map(name => ({
        label: '@' + name, insertText: '@' + name, kind: 'capture', detail: '@mapping の params に指定された名前'
      }));
    } else if (body && !prefix.startsWith('@')) {
      items = definitions.filter(item => body.kind !== 'token' || item.kind === 'token')
        .concat(body.kind === 'token' ? words.lexical : words.ruleBody);
    } else if (body || /@\w+\([^)]*$/.test(before) || /@\w+\s*:\s*[^\n;]*$/.test(before) || /\btoken\s+$/.test(before)) {
      items = [];
    } else {
      items = words.declarations.filter(item => !prefix.startsWith('@') || item.label.startsWith('@'));
    }
  }
  const query = prefix.toLowerCase().replace(/^@/, '');
  const unique = new Map();
  for (const item of items) {
    if (item.label.toLowerCase().replace(/^@/, '').startsWith(query) && !unique.has(item.label)) unique.set(item.label, item);
  }
  return {from, to, items: [...unique.values()]};
}

export function insertion(source, result, item) {
  const indent = source.slice(source.lastIndexOf('\n', result.from - 1) + 1, result.from).match(/^\s*/)[0];
  return item.insertText.replace(/\n/g, '\n' + indent);
}
