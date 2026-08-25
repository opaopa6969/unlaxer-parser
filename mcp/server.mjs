#!/usr/bin/env node
import http from 'node:http';
import { randomUUID, webcrypto } from 'node:crypto';
import { execFile } from 'node:child_process';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

if (!globalThis.crypto) {
  globalThis.crypto = webcrypto;
}

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');
const VERSION = '1.0.0';

function log(...a) {
  process.stderr.write('[unlaxer-mcp] ' + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n');
}

let _classpath = null;
async function getClasspath() {
  if (_classpath) return _classpath;
  const classesDir = path.join(REPO_ROOT, 'unlaxer-dsl', 'target', 'classes');
  const commonDir = path.join(REPO_ROOT, 'unlaxer-common', 'target', 'classes');
  let depsCp = '';
  const cpFile = path.join(__dirname, 'classpath.txt');
  if (existsSync(cpFile)) {
    try { depsCp = (await readFile(cpFile, 'utf8')).trim(); } catch {}
  }
  _classpath = depsCp ? `${classesDir}:${commonDir}:${depsCp}` : `${classesDir}:${commonDir}`;
  return _classpath;
}

function runJava(mainClass, args, opts = {}) {
  return new Promise((resolve) => {
    getClasspath().then(cp => {
      const javaArgs = ['--enable-preview', '-cp', cp, mainClass, ...args];
      execFile('java', javaArgs, {
        cwd: opts.cwd || REPO_ROOT,
        maxBuffer: 10 * 1024 * 1024,
        timeout: opts.timeout || 60000,
        env: { ...process.env },
      }, (err, stdout, stderr) => {
        resolve({
          exitCode: err ? (err.code || 1) : 0,
          stdout: stdout || '',
          stderr: stderr || '',
        });
      });
    }).catch(err => {
      resolve({ exitCode: 1, stdout: '', stderr: String(err) });
    });
  });
}

function parseNdjson(text) {
  const lines = text.trim().split('\n').filter(Boolean);
  return lines.map(l => { try { return JSON.parse(l); } catch { return { raw: l }; } });
}

function textResult(text) {
  return { content: [{ type: 'text', text }] };
}

function jsonResult(obj) {
  return { content: [{ type: 'text', text: JSON.stringify(obj, null, 2) }] };
}

function errorResult(msg) {
  return { isError: true, content: [{ type: 'text', text: msg }] };
}

function createServer() {
  const server = new McpServer({ name: 'unlaxer-parser', version: VERSION });

  server.tool(
    'validate',
    'UBNF文法を検証する（read・副作用なし）',
    { grammar_file: z.string().describe('UBNF文法ファイルのパス（リポジトリルート相対または絶対）') },
    async (args) => {
      const res = await runJava('org.unlaxer.dsl.CodegenMain', [
        '--validate-only', '--grammar', args.grammar_file, '--report-format', 'ndjson',
      ]);
      if (res.exitCode === 0) {
        const events = parseNdjson(res.stdout);
        return jsonResult({ ok: true, events, stdout: res.stdout });
      }
      const events = parseNdjson(res.stdout);
      return jsonResult({ ok: false, exitCode: res.exitCode, events, stderr: res.stderr });
    }
  );

  server.tool(
    'generate',
    'UBNF文法からコードを生成する（write・dry_runデフォルトtrue）',
    {
      grammar_file: z.string().describe('UBNF文法ファイルのパス'),
      output_dir: z.string().describe('出力ディレクトリ'),
      generators: z.string().optional().describe('カンマ区切り: AST,Parser,Mapper,Evaluator,LSP,Launcher,DAP,DAPLauncher'),
      dry_run: z.boolean().optional().describe('true=生成計画のみ（デフォルトtrue）'),
      clean_output: z.boolean().optional().describe('true=出力ディレクトリをクリーン（confirm必要）'),
      confirm: z.boolean().optional().describe('clean_output=trueのとき必須'),
    },
    async (args) => {
      const cliArgs = ['--grammar', args.grammar_file, '--output', args.output_dir, '--report-format', 'ndjson'];
      if (args.generators) cliArgs.push('--generators', args.generators);
      if (args.dry_run !== false) cliArgs.push('--dry-run');
      if (args.clean_output) {
        if (!args.confirm) {
          return errorResult('clean_output=true の場合は confirm:true が必要です（dry-run）');
        }
        cliArgs.push('--clean-output');
      }
      const res = await runJava('org.unlaxer.dsl.CodegenMain', cliArgs);
      const events = parseNdjson(res.stdout);
      return jsonResult({ ok: res.exitCode === 0, exitCode: res.exitCode, events, stderr: res.stderr });
    }
  );

  server.tool(
    'export_parser_ir',
    'Parser IRをエクスポートする（read・JSONを返す）',
    {
      grammar_file: z.string().describe('UBNF文法ファイルのパス'),
      output_file: z.string().optional().describe('出力ファイルパス（省略時は標準出力に返す）'),
    },
    async (args) => {
      const irFile = args.output_file || path.join('/tmp', `parser-ir-${Date.now()}.json`);
      const res = await runJava('org.unlaxer.dsl.CodegenMain', [
        '--grammar', args.grammar_file, '--export-parser-ir', irFile, '--report-format', 'ndjson',
      ]);
      if (res.exitCode === 0) {
        const events = parseNdjson(res.stdout);
        let parserIr = null;
        try { parserIr = JSON.parse(await readFile(irFile, 'utf8')); } catch {}
        return jsonResult({ ok: true, events, parser_ir: parserIr, output_file: irFile });
      }
      const events = parseNdjson(res.stdout);
      return jsonResult({ ok: false, exitCode: res.exitCode, events, stderr: res.stderr });
    }
  );

  server.tool(
    'validate_parser_ir',
    'Parser IRを検証する（read・副作用なし）',
    { parser_ir: z.string().describe('Parser IR JSON文字列 または ファイルパス') },
    async (args) => {
      let irPath = args.parser_ir;
      if (args.parser_ir.trim().startsWith('{')) {
        irPath = path.join('/tmp', `parser-ir-validate-${Date.now()}.json`);
        await writeFile(irPath, args.parser_ir);
      }
      const res = await runJava('org.unlaxer.dsl.CodegenMain', [
        '--validate-parser-ir', irPath, '--report-format', 'ndjson',
      ]);
      const events = parseNdjson(res.stdout);
      return jsonResult({ ok: res.exitCode === 0, exitCode: res.exitCode, events, stderr: res.stderr });
    }
  );

  server.tool(
    'generate_railroad',
    'UBNFからrailway diagramを生成する（write・SVG/PNG/markdown）',
    {
      grammar_file: z.string().describe('UBNF文法ファイルのパス'),
      output_dir: z.string().describe('出力ディレクトリ'),
      format: z.enum(['svg', 'png', 'both', 'markdown']).optional().describe('出力フォーマット（デフォルトsvg）'),
    },
    async (args) => {
      const cliArgs = [args.grammar_file, args.output_dir];
      if (args.format) cliArgs.push('--format', args.format);
      const res = await runJava('org.unlaxer.dsl.tools.railroad.RailroadMain', cliArgs);
      return jsonResult({ ok: res.exitCode === 0, exitCode: res.exitCode, stdout: res.stdout, stderr: res.stderr });
    }
  );

  server.tool(
    'convert_to_ebnf',
    'UBNFをEBNFに変換する（read・テキストを返す）',
    {
      grammar_file: z.string().describe('UBNF文法ファイルのパス'),
      keep_annotations: z.boolean().optional().describe('true=アノテーションを保持'),
    },
    async (args) => {
      const cliArgs = [args.grammar_file];
      if (args.keep_annotations) cliArgs.push('--keep-annotations');
      const res = await runJava('org.unlaxer.dsl.tools.bnf.UBNFToBNFMain', cliArgs);
      return jsonResult({ ok: res.exitCode === 0, exitCode: res.exitCode, ebnf: res.stdout, stderr: res.stderr });
    }
  );

  server.tool(
    'init',
    'DSLプロジェクトをscaffoldする（write・confirm必要）',
    {
      name: z.string().describe('DSL名'),
      package: z.string().optional().describe('Javaパッケージ名（省略時: org.example.<name>）'),
      output_dir: z.string().optional().describe('出力ディレクトリ（省略時: ./<name>）'),
      with_dap: z.boolean().optional().describe('DAP debug supportを含める'),
      force: z.boolean().optional().describe('既存ディレクトリを上書き（confirm必要）'),
      confirm: z.boolean().optional().describe('force=trueのとき必須'),
    },
    async (args) => {
      const cliArgs = ['init', args.name];
      if (args.package) cliArgs.push('--package', args.package);
      if (args.output_dir) cliArgs.push('--output-dir', args.output_dir);
      if (args.with_dap) cliArgs.push('--with-dap');
      if (args.force) {
        if (!args.confirm) {
          return errorResult('force=true の場合は confirm:true が必要です（dry-run）');
        }
        cliArgs.push('--force');
      }
      const res = await runJava('org.unlaxer.dsl.CodegenMain', cliArgs);
      return jsonResult({ ok: res.exitCode === 0, exitCode: res.exitCode, stdout: res.stdout, stderr: res.stderr });
    }
  );

  server.resource(
    'spec',
    'unlaxer://spec',
    { mimeType: 'application/json', description: '能力の機械可読仕様' },
    async () => {
      const spec = {
        namespace: 'unlaxer',
        name: 'unlaxer-parser',
        version: VERSION,
        summary: 'UBNF文法からParser/AST/Mapper/Evaluator/LSP/DAPを生成するコードジェネレータ',
        capabilities: [
          { kind: 'tool', name: 'validate', summary: 'UBNF文法を検証する', input: '{grammar_file: string}', output: '{ok, errors[], warnings[]}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'VIEWER' },
          { kind: 'tool', name: 'generate', summary: 'UBNF文法からコードを生成する', input: '{grammar_file, output_dir, generators?, dry_run?, clean_output?, confirm?}', output: '{generated_files[], report, ok}', side_effect: 'write', long_running: false, dry_run: true, min_role: 'MEMBER' },
          { kind: 'tool', name: 'export_parser_ir', summary: 'Parser IRをエクスポートする', input: '{grammar_file, output_file?}', output: '{ok, parser_ir, grammarCount, nodeCount}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'VIEWER' },
          { kind: 'tool', name: 'validate_parser_ir', summary: 'Parser IRを検証する', input: '{parser_ir: string}', output: '{ok, errors[]}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'VIEWER' },
          { kind: 'tool', name: 'generate_railroad', summary: 'UBNFからrailway diagramを生成する', input: '{grammar_file, output_dir, format?}', output: '{ok, files[], count}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'MEMBER' },
          { kind: 'tool', name: 'convert_to_ebnf', summary: 'UBNFをEBNFに変換する', input: '{grammar_file, keep_annotations?}', output: '{ok, ebnf}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'VIEWER' },
          { kind: 'tool', name: 'init', summary: 'DSLプロジェクトをscaffoldする', input: '{name, package?, output_dir?, with_dap?, force?, confirm?}', output: '{ok, project_dir, files[]}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'MEMBER' },
        ],
        compositions: [
          { title: '文法定義→コード生成→プロジェクト立ち上げ', flow: ['unlaxer__validate', 'unlaxer__generate', 'scaffolding__create_project'], note: 'UBNF文法を検証→コード生成→他サービスがプロジェクトを立ち上げる' },
          { title: 'Parser IR エクスポート→他サービスで解析', flow: ['unlaxer__export_parser_ir', 'other__analyze_ir'], note: 'Parser IR を中間表現として他サービスに渡す' },
          { title: 'Railway diagram→ドキュメント埋め込み', flow: ['unlaxer__generate_railroad', 'design__compose_page_starter'], note: '生成した railroad diagram をドキュメントに埋め込む' },
        ],
        depends_on: [
          { namespace: 'tinyexpr', capability: 'tinyexpr__compile' },
        ],
        health: '/healthz',
        docs: ['unlaxer://guide', 'unlaxer://ubnf-guide'],
      };
      return { contents: [{ uri: 'unlaxer://spec', mimeType: 'application/json', text: JSON.stringify(spec, null, 2) }] };
    }
  );

  server.resource(
    'guide',
    'unlaxer://guide',
    { mimeType: 'text/markdown', description: '使い方ガイド' },
    async () => {
      const guide = `# unlaxer MCP 使い方ガイド

## 概要

unlaxer-parser は UBNF (Unlaxer BNF) 文法から Parser/AST/Mapper/Evaluator/LSP/DAP コードを自動生成する Java ツール群です。この MCP サーバは既存 CLI（CodegenMain, RailroadMain, UBNFToBNFMain）を薄く包んで MCP プロトコルで提供します。

## tools

### validate
UBNF 文法ファイルを検証します。副作用なし。
\`\`\`json
{ "grammar_file": "story/code/calc.ubnf" }
\`\`\`

### generate
UBNF 文法からコードを生成します。デフォルトは dry_run（生成計画のみ）。
\`\`\`json
{ "grammar_file": "story/code/calc.ubnf", "output_dir": "/tmp/output", "dry_run": true }
\`\`\`
clean_output=true の場合は confirm:true が必要です。

### export_parser_ir
Parser IR を JSON でエクスポートします。
\`\`\`json
{ "grammar_file": "story/code/calc.ubnf" }
\`\`\`

### validate_parser_ir
Parser IR を検証します。JSON 文字列またはファイルパスを渡せます。

### generate_railroad
Railway diagram を SVG/PNG/markdown で生成します。
\`\`\`json
{ "grammar_file": "story/code/calc.ubnf", "output_dir": "/tmp/railroad", "format": "svg" }
\`\`\`

### convert_to_ebnf
UBNF を EBNF に変換します。

### init
DSL プロジェクトを scaffold します。force=true の場合は confirm:true が必要です。
\`\`\`json
{ "name": "mydsl", "output_dir": "/tmp/mydsl" }
\`\`\`

## 組み合わせ例

1. \`unlaxer__validate → unlaxer__generate\` — 文法検証後にコード生成
2. \`unlaxer__export_parser_ir → (他サービス)__analyze_ir\` — Parser IR を中間表現として渡す
3. \`unlaxer__generate_railroad → (design)__compose_page_starter\` — diagram をドキュメントに埋め込む

## 前提

- Java 21+ がインストールされていること
- リポジトリがビルド済み（unlaxer-dsl/target/classes が存在）
`;
      return { contents: [{ uri: 'unlaxer://guide', mimeType: 'text/markdown', text: guide }] };
    }
  );

  server.resource(
    'ubnf-guide',
    'unlaxer://ubnf-guide',
    { mimeType: 'text/markdown', description: 'UBNF文法記法ガイド' },
    async () => {
      const guide = `# UBNF (Unlaxer BNF) 文法記法ガイド

## 概要

UBNF は BNF を拡張した文法記述言語で、パーサ・AST・LSP・DAP を自動生成するためのアノテーションを持ちます。

## 基本構文

### 文法ブロック
\`\`\`
grammar <名前> {
  <ルール定義>
}
\`\`\`

### ルール定義
\`\`\`
<ルール名> = <ボディ> ;
\`\`\`

### 要素
- 終端記号: 文字列リテラル または 正規表現
- 非終端記号: 他のルール名
- 選択: \`A | B\`
- 結合: \`A , B\`
- 繰り返し: \`{ A }\` (0回以上), \`[ A ]\` (0or1), \`A * \` (1回以上)
- 区切り繰り返し: \`A % B\` (B で区切られた A の繰り返し)

### アノテーション
\`@\` プレフィックスで AST ノードのマッピングを指定:
- \`@left\`, \`@right\` — 演算子の左辺・右辺
- \`@op\` — 演算子ノード
- \`@mapping\` — DAP マッピング

### トークン定義
\`\`\`
(* token: <名前> = <Java クラス> *)
\`\`\`

### パッケージ指定
\`\`\`
(* @package: <パッケージ名> *)
\`\`\`

### ホワイトスペース
\`\`\`
(* @whitespace: javaStyle *)
\`\`\`

## 例

\`\`\`
(* @package: story.calc *)
(* @whitespace: javaStyle *)

(* token: NUMBER = org.unlaxer.parser.elementary.NumberParser *)
(* token: EOF = org.unlaxer.parser.elementary.EndOfSourceParser *)

grammar TinyCalc {
  Formula = Expression , EOF ;
  Expression = Term @left , { AddOp @op , Term @right } ;
  Term = Factor @left , { MulOp @op , Factor @right } ;
  Factor = NUMBER | '(' , Expression , ')' ;
  AddOp = '+' | '-' ;
  MulOp = '*' | '/' ;
}
\`\`\`

## 注意

- CRLF のファイルはパースエラーになる場合があります（LF に変換してください）
- Java 21+ が必要です
`;
      return { contents: [{ uri: 'unlaxer://ubnf-guide', mimeType: 'text/markdown', text: guide }] };
    }
  );

  server.resource(
    'skill-write-ubnf-grammar',
    'skill://write_ubnf_grammar',
    { mimeType: 'text/markdown', description: 'skill: UBNF文法を書く手順' },
    async () => {
      const skill = [
        '---',
        'name: write_ubnf_grammar',
        'description: UBNF文法を定義・編集する手順',
        'volta:',
        '  version: 1',
        '  namespace: unlaxer',
        '  locality: global',
        '  applies_when: "UBNF文法を定義・編集するとき"',
        '  requires:',
        '    tools: [unlaxer__validate, unlaxer__convert_to_ebnf]',
        '  min_role: VIEWER',
        '---',
        '# UBNF文法を書く手順',
        '',
        '1. 文法ファイル（.ubnf）を作成する',
        '2. `(* @package: ... *)` でパッケージを指定する',
        '3. `(* @whitespace: javaStyle *)` でホワイトスペース処理を指定する',
        '4. トークン定義 `(* token: ... *)` を書く',
        '5. `grammar <名前> { ... }` ブロックでルールを定義する',
        '6. `unlaxer__validate` で検証する',
        '7. `unlaxer__convert_to_ebnf` でEBNFに変換して確認する',
        '8. エラーがあれば修正して再検証',
        '',
        '## 注意',
        '- CRLF はパースエラーになる場合がある（LF を使う）',
        '- `@left`, `@right`, `@op` で演算子の結合を指定する',
        '',
      ].join('\n');
      return { contents: [{ uri: 'skill://write_ubnf_grammar', mimeType: 'text/markdown', text: skill }] };
    }
  );

  server.resource(
    'skill-build-dsl',
    'skill://build_dsl_with_unlaxer',
    { mimeType: 'text/markdown', description: 'skill: unlaxerでDSLを構築する手順' },
    async () => {
      const skill = [
        '---',
        'name: build_dsl_with_unlaxer',
        'description: UBNFからDSLプロジェクトを立ち上げる手順',
        'volta:',
        '  version: 1',
        '  namespace: unlaxer',
        '  locality: global',
        '  applies_when: "UBNFからDSLプロジェクトを立ち上げるとき"',
        '  requires:',
        '    tools: [unlaxer__init, unlaxer__validate, unlaxer__generate]',
        '  min_role: MEMBER',
        '---',
        '# unlaxerでDSLを構築する手順',
        '',
        '1. `unlaxer__init` でプロジェクトをscaffoldする',
        '2. 生成された `grammar/` ディレクトリに UBNF 文法を書く',
        '3. `unlaxer__validate` で文法を検証する',
        '4. `unlaxer__generate` で Parser/AST/Mapper/Evaluator コードを生成する',
        '5. （必要に応じて）`unlaxer__generate_railroad` でドキュメント用図を生成する',
        '6. （必要に応じて）`unlaxer__export_parser_ir` で Parser IR を他サービスに渡す',
        '',
        '## 生成されるコード',
        '- `*Parsers.java` — パーサクラス',
        '- `*LanguageServer.java` — LSP サーバ',
        '- `*LspLauncher.java` — LSP ランチャ',
        '',
      ].join('\n');
      return { contents: [{ uri: 'skill://build_dsl_with_unlaxer', mimeType: 'text/markdown', text: skill }] };
    }
  );

  return server;
}

async function serveStdio() {
  const server = createServer();
  await server.connect(new StdioServerTransport());
  log('stdio started');
}

async function serveHttp(port) {
  const transports = new Map();
  const httpServer = http.createServer(async (req, res) => {
    res.setHeader('content-encoding', 'identity');
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    try {
      if (url.pathname === '/healthz') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ ok: true, name: 'unlaxer-parser', version: VERSION }));
      }
      if (url.pathname !== '/mcp') {
        res.writeHead(404, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'not found' }));
      }
      const sid = req.headers['mcp-session-id'];
      if (sid && transports.has(sid)) {
        return await transports.get(sid).handleRequest(req, res);
      }
      if (req.method === 'POST' && !sid) {
        const transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: () => randomUUID(),
          enableJsonResponse: true,
          onsessioninitialized: (id) => { transports.set(id, transport); log('session open', id); },
          onsessionclosed: (id) => { transports.delete(id); log('session closed', id); },
        });
        const server = createServer();
        transport.onclose = () => {
          if (transport.sessionId) transports.delete(transport.sessionId);
          server.close().catch(() => {});
        };
        await server.connect(transport);
        return await transport.handleRequest(req, res);
      }
      res.writeHead(sid ? 404 : 400, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: sid ? 'unknown session' : 'missing mcp-session-id' }));
    } catch (e) {
      log('request failed', { path: url.pathname, error: String(e?.stack || e) });
      if (!res.headersSent) { res.writeHead(500); res.end(JSON.stringify({ error: 'internal error' })); }
      else res.end();
    }
  });
  httpServer.listen(port, '0.0.0.0', () => log('http listening', { url: `http://0.0.0.0:${port}/mcp` }));
}

const argv = process.argv.slice(2);
if (argv.includes('--stdio')) {
  serveStdio().catch((e) => { log('stdio failed', String(e?.stack || e)); process.exit(1); });
} else if (argv.includes('--http')) {
  const i = argv.indexOf('--http');
  const port = Number(argv[i + 1] || 9228);
  serveHttp(port).catch((e) => { log('http failed', String(e?.stack || e)); process.exit(1); });
} else {
  const port = Number(process.env.PORT || 9228);
  serveHttp(port).catch((e) => { log('http failed', String(e?.stack || e)); process.exit(1); });
}
