#!/usr/bin/env node
import http from 'node:http';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const PORT = 19228;
const BASE = `http://127.0.0.1:${PORT}`;

let passed = 0, failed = 0;
function ok(name) { passed++; console.log(`  ✓ ${name}`); }
function ng(name, msg) { failed++; console.error(`  ✗ ${name}: ${msg}`); }

async function checkHealth() {
  return new Promise((resolve) => {
    http.get(`${BASE}/healthz`, (res) => {
      let data = '';
      res.on('data', (c) => data += c);
      res.on('end', () => {
        if (res.statusCode === 200 && JSON.parse(data).ok) {
          ok('healthz 200');
        } else {
          ng('healthz', `status=${res.statusCode} body=${data}`);
        }
        resolve();
      });
    }).on('error', (e) => { ng('healthz', e.message); resolve(); });
  });
}

async function main() {
  console.log('Starting MCP server on port', PORT);
  const { spawn } = await import('node:child_process');
  const proc = spawn('node', ['server.mjs', '--http', String(PORT)], {
    cwd: new URL('.', import.meta.url).pathname,
    env: { ...process.env, PORT: String(PORT) },
    stdio: ['pipe', 'pipe', 'inherit'],
  });
  proc.stdout.on('data', (d) => process.stderr.write(d));

  await new Promise(r => setTimeout(r, 2000));

  try {
    await checkHealth();

    const transport = new StreamableHTTPClientTransport(new URL(`${BASE}/mcp`));
    const client = new Client({ name: 'test-client', version: '1.0.0' });
    await client.connect(transport);
    ok('MCP initialize');

    // tools/list
    const toolsRes = await client.listTools();
    const toolNames = toolsRes.tools.map(t => t.name).sort();
    const expected = ['convert_to_ebnf', 'export_parser_ir', 'generate', 'generate_railroad', 'init', 'validate', 'validate_parser_ir'];
    if (JSON.stringify(toolNames) === JSON.stringify(expected)) {
      ok(`tools/list (${toolNames.length} tools)`);
    } else {
      ng('tools/list', `expected ${JSON.stringify(expected)} got ${JSON.stringify(toolNames)}`);
    }

    // validate
    try {
      const r = await client.callTool({ name: 'validate', arguments: { grammar_file: 'story/code/calc.ubnf' } });
      const parsed = JSON.parse(r.content[0].text);
      if (parsed.ok) { ok('validate'); } else { ng('validate', JSON.stringify(parsed).slice(0, 200)); }
    } catch (e) { ng('validate', e.message); }

    // generate (dry_run)
    try {
      const r = await client.callTool({ name: 'generate', arguments: { grammar_file: 'story/code/calc.ubnf', output_dir: '/tmp/mcp-test-gen', dry_run: true } });
      const parsed = JSON.parse(r.content[0].text);
      if (parsed.ok) { ok('generate (dry_run)'); } else { ng('generate (dry_run)', JSON.stringify(parsed).slice(0, 200)); }
    } catch (e) { ng('generate (dry_run)', e.message); }

    // export_parser_ir
    try {
      const r = await client.callTool({ name: 'export_parser_ir', arguments: { grammar_file: 'story/code/calc.ubnf' } });
      const parsed = JSON.parse(r.content[0].text);
      if (parsed.ok && parsed.parser_ir) { ok('export_parser_ir'); } else { ng('export_parser_ir', JSON.stringify(parsed).slice(0, 200)); }
    } catch (e) { ng('export_parser_ir', e.message); }

    // validate_parser_ir
    try {
      const irRes = await client.callTool({ name: 'export_parser_ir', arguments: { grammar_file: 'story/code/calc.ubnf' } });
      const irParsed = JSON.parse(irRes.content[0].text);
      const irJson = JSON.stringify(irParsed.parser_ir);
      const r = await client.callTool({ name: 'validate_parser_ir', arguments: { parser_ir: irJson } });
      const parsed = JSON.parse(r.content[0].text);
      if (parsed.ok) { ok('validate_parser_ir'); } else { ng('validate_parser_ir', JSON.stringify(parsed).slice(0, 200)); }
    } catch (e) { ng('validate_parser_ir', e.message); }

    // generate_railroad
    try {
      const r = await client.callTool({ name: 'generate_railroad', arguments: { grammar_file: 'story/code/calc.ubnf', output_dir: '/tmp/mcp-test-railroad', format: 'svg' } });
      const parsed = JSON.parse(r.content[0].text);
      if (parsed.ok) { ok('generate_railroad'); } else { ng('generate_railroad', JSON.stringify(parsed).slice(0, 200)); }
    } catch (e) { ng('generate_railroad', e.message); }

    // convert_to_ebnf
    try {
      const r = await client.callTool({ name: 'convert_to_ebnf', arguments: { grammar_file: 'story/code/calc.ubnf' } });
      const parsed = JSON.parse(r.content[0].text);
      if (parsed.ok && parsed.ebnf) { ok('convert_to_ebnf'); } else { ng('convert_to_ebnf', JSON.stringify(parsed).slice(0, 200)); }
    } catch (e) { ng('convert_to_ebnf', e.message); }

    // init
    try {
      const r = await client.callTool({ name: 'init', arguments: { name: 'testdsl', output_dir: '/tmp/mcp-test-init' } });
      const parsed = JSON.parse(r.content[0].text);
      if (parsed.ok) { ok('init'); } else { ng('init', JSON.stringify(parsed).slice(0, 200)); }
    } catch (e) { ng('init', e.message); }

    // --- security: path traversal / arbitrary path rejection ---
    async function expectRejected(name, toolName, args) {
      try {
        const r = await client.callTool({ name: toolName, arguments: args });
        if (r.isError) {
          ok(`sec: ${name}`);
          return;
        }
        const parsed = JSON.parse(r.content[0].text);
        if (parsed && parsed.isError) {
          ok(`sec: ${name}`);
        } else {
          ng(`sec: ${name}`, `expected rejection, got: ${JSON.stringify(parsed).slice(0, 200)}`);
        }
      } catch (e) { ng(`sec: ${name}`, e.message); }
    }

    // grammar_file traversal (read outside repo)
    await expectRejected('validate traversal', 'validate', { grammar_file: '../../../etc/passwd' });
    await expectRejected('validate absolute', 'validate', { grammar_file: '/etc/passwd' });
    await expectRejected('convert_to_ebnf traversal', 'convert_to_ebnf', { grammar_file: '../secret.ubnf' });
    await expectRejected('export_parser_ir traversal', 'export_parser_ir', { grammar_file: '../../../etc/shadow' });

    // output_dir / output_file outside /tmp (write anywhere)
    await expectRejected('generate output_dir', 'generate', { grammar_file: 'story/code/calc.ubnf', output_dir: '/etc/unlaxer-evil', dry_run: true });
    await expectRejected('export_parser_ir output_file', 'export_parser_ir', { grammar_file: 'story/code/calc.ubnf', output_file: '/etc/unlaxer-evil.json' });
    await expectRejected('generate_railroad output_dir', 'generate_railroad', { grammar_file: 'story/code/calc.ubnf', output_dir: '/root/unlaxer-evil' });
    await expectRejected('init output_dir', 'init', { name: 'evil', output_dir: '/etc/unlaxer-evil' });

    // init name with path separators (writes to arbitrary dir)
    await expectRejected('init name slash', 'init', { name: '../../../etc/unlaxer-evil', output_dir: '/tmp/mcp-sec-init' });
    await expectRejected('init name dot', 'init', { name: '..', output_dir: '/tmp/mcp-sec-init' });

    // validate_parser_ir file path outside /tmp
    await expectRejected('validate_parser_ir path', 'validate_parser_ir', { parser_ir: '/etc/passwd' });

    // resources
    try {
      const res = await client.readResource({ uri: 'unlaxer://spec' });
      const spec = JSON.parse(res.contents[0].text);
      if (spec.namespace === 'unlaxer' && spec.capabilities.length >= 7) { ok('resource unlaxer://spec'); } else { ng('resource spec', JSON.stringify(spec).slice(0, 200)); }
    } catch (e) { ng('resource spec', e.message); }

    try {
      const res = await client.readResource({ uri: 'unlaxer://guide' });
      if (res.contents[0].text.includes('# unlaxer MCP')) { ok('resource unlaxer://guide'); } else { ng('resource guide', 'unexpected content'); }
    } catch (e) { ng('resource guide', e.message); }

    try {
      const res = await client.readResource({ uri: 'unlaxer://ubnf-guide' });
      if (res.contents[0].text.includes('UBNF')) { ok('resource unlaxer://ubnf-guide'); } else { ng('resource ubnf-guide', 'unexpected content'); }
    } catch (e) { ng('resource ubnf-guide', e.message); }

    try {
      const res = await client.readResource({ uri: 'skill://write_ubnf_grammar' });
      if (res.contents[0].text.includes('write_ubnf_grammar')) { ok('resource skill://write_ubnf_grammar'); } else { ng('resource skill write_ubnf', 'unexpected content'); }
    } catch (e) { ng('resource skill write_ubnf', e.message); }

    try {
      const res = await client.readResource({ uri: 'skill://build_dsl_with_unlaxer' });
      if (res.contents[0].text.includes('build_dsl_with_unlaxer')) { ok('resource skill://build_dsl_with_unlaxer'); } else { ng('resource skill build_dsl', 'unexpected content'); }
    } catch (e) { ng('resource skill build_dsl', e.message); }

    await client.close();
  } catch (e) {
    ng('MCP client', e.message);
  } finally {
    proc.kill();
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch(e => { console.error(e); process.exit(1); });
