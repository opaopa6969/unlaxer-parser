import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {complete, vocabulary, insertion} from '../../src/main/resources/ubnf-playground/completion-engine.mjs';
const catalog = JSON.parse(await readFile(new URL('../../src/main/resources/ubnf-help/catalog.json', import.meta.url)));
const words = vocabulary(catalog);
function at(marked) {
  const cursor = marked.indexOf('¦');
  return complete(marked.replace('¦', ''), cursor, words);
}
const labels = result => result.items.map(item => item.label);

test('catalog examples supply snippets; whitespace settings explain their actual behavior', () => {
  assert.equal(at('gra¦').items[0].insertText, catalog.entries.find(entry => entry.id === 'grammar').insertText);
  assert.deepEqual(labels(at('grammar G { @ma¦ }')), ['@mapping']);
  assert.deepEqual(labels(at('grammar G { @whitespace: ja¦ }')), ['javaStyle']);
  assert.match(at('grammar G { @whitespace: ja¦ }').items[0].detail, /\/\/.*\/\*/);
  assert.deepEqual(labels(at('grammar G { @whitespace(no¦) Start ::= \'a\'; }')), ['none']);
});

test('references are scoped to one grammar, forward declarations work, token bodies exclude rules', () => {
  const source = 'grammar A { token OTHER ::= \'x\'; } grammar B { token NUM ::= CHAR_RANGE(\'0\',\'9\'); Rule ::= \'x\'; Start ::= N¦; }';
  assert.deepEqual(labels(at(source)), ['NUM']);
  assert.deepEqual(labels(at('grammar G { Start ::= La¦; Later ::= \'x\'; }')), ['Later']);
  const lexical = labels(at('grammar G { token N ::= ¦; Rule ::= \'a\'; }'));
  assert.ok(lexical.includes('CHAR_RANGE')); assert.ok(!lexical.includes('Rule')); assert.ok(!lexical.includes('token'));
  assert.deepEqual(labels(at('grammar G { @import num from \'n.ubnf\' Start ::= num.N¦; }')), []);
  assert.deepEqual(labels(at('grammar G { token N¦ }')), []);
});

test('comments, escaped quotes, annotation arguments and capture positions do not leak names', () => {
  for (const source of ["grammar G { // @ma¦", "grammar G { /* @ma¦ */ }", "grammar G { Start ::= 'it\\'s @ma¦'; }", "grammar G { @doc('token FAKE ::= X;') Start ::= FA¦; }", 'grammar G { @mapping(Ty¦) Start ::= \'x\'; }']) assert.deepEqual(labels(at(source)), [], source);
  assert.deepEqual(labels(at('grammar G { @mapping(Value, params=[text]) Start ::= \'a\' @te¦; }')), ['@text']);
  assert.deepEqual(labels(at('grammar G { Start ::= \'a\' @ro¦; }')), []);
  assert.ok(labels(at('grammar G { // comment\n  @ro¦ }')).includes('@root'));
});

test('edits use UTF-16 textarea positions and replace the complete word without losing surrounding text', () => {
  const marked = "// 😀\ngrammar G { token NUMBER ::= '1'; Start ::= NU¦mber; }";
  const source = marked.replace('¦', ''); const result = at(marked);
  const item = result.items.find(item => item.label === 'NUMBER');
  assert.equal(source.slice(0, result.from) + insertion(source, result, item) + source.slice(result.to), "// 😀\ngrammar G { token NUMBER ::= '1'; Start ::= NUMBER; }");
  const nested = at('grammar G {\n  gra¦\n}');
  assert.ok(!labels(nested).includes('grammar'));
  assert.deepEqual(complete('x'.repeat(65537), 65537, words).items, []);
});
