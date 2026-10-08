import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import ts from 'typescript';
assert.equal(ts.version, '5.9.3', 'reference parser must remain pinned by package-lock.json');
const fixtures = JSON.parse(await readFile(new URL('../../../spec-corpus/contextual-lexing/corpus.json', import.meta.url), 'utf8'));
const evidence = ['case\treference\taccepted\tstatement_cp_span\tdiagnostic_cp_offsets'];
function codePoint(source, utf16) { return [...source.slice(0, utf16)].length; }
function verifyType(source, file, node, expected) {
  assert.equal(node.kind, ts.SyntaxKind.TypeReference);
  assert.equal(node.typeName.getText(file), expected.fields.name);
  assert.deepEqual([codePoint(source, node.getStart(file)), codePoint(source, node.end)], expected.span);
  assert.equal(node.typeArguments?.length ?? 0, expected.fields.arguments.length);
  for (let index = 0; index < expected.fields.arguments.length; index++) verifyType(source, file, node.typeArguments[index], expected.fields.arguments[index]);
}
for (const fixture of fixtures) for (const row of fixture.cases) {
  if (!row.reference) continue;
  const file = ts.createSourceFile(row.reference === 'TSX' ? 'input.tsx' : 'input.ts', row.input, ts.ScriptTarget.Latest, true,
    row.reference === 'TSX' ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const accepted = file.parseDiagnostics.length === 0;
  assert.equal(accepted, row.accepted, row.name);
  let span = null;
  if (accepted) {
    assert.equal(file.statements.length, 1, row.name);
    const statement = file.statements[0];
    span = [codePoint(row.input, statement.getStart(file)), codePoint(row.input, statement.end)];
    assert.deepEqual(span, row.referenceSpan ?? row.ast.span, row.name);
    if (row.ast.type === 'Alias') {
      assert.equal(statement.name.text, row.ast.fields.name, row.name);
      verifyType(row.input, file, statement.type, row.ast.fields.value);
    } else assert.equal(statement.getText(file), row.ast.fields.value, row.name);
  }
  evidence.push(`${fixture.name}/${row.name}\tTypeScript ${ts.version}/${row.reference}\t${accepted}\t${JSON.stringify(span)}\t${JSON.stringify(file.parseDiagnostics.map(diagnostic => codePoint(row.input, diagnostic.start)))}`);
}
await mkdir(new URL('../target/', import.meta.url), { recursive: true });
await writeFile(new URL('../target/contextual-lexing-reference.tsv', import.meta.url), evidence.join('\n') + '\n');
console.log(`Contextual lexing: ${evidence.length - 1} same-input fixtures match pinned TypeScript ${ts.version} syntax and authored code-point AST spans`);
