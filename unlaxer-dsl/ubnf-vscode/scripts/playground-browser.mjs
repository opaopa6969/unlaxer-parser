import assert from 'node:assert/strict';
import {mkdtemp, readFile, writeFile, mkdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {execFile, spawn} from 'node:child_process';
import {promisify} from 'node:util';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';

const repo = fileURLToPath(new URL('../../../', import.meta.url));
const scratch = await mkdtemp(path.join(tmpdir(), 'ubnf-playground-browser-'));
let browser, server;
try {
  const catalog = JSON.parse(await readFile(path.join(repo, 'unlaxer-dsl/src/main/resources/ubnf-help/catalog.json'), 'utf8'));
  const sample = catalog.examples.find(example => example.id === 'assignment');
  const grammar = path.join(scratch, 'language.ubnf'), project = path.join(scratch, 'project');
  await writeFile(grammar, sample.source.replace(/grammar\s+\w+\s*\{/, "$&\n  @import layout from 'pkg:std/layout'")
    .replace('@whitespace: javaStyle', '@whitespace: layout.SPACES_AND_COMMENTS')
    .replace("@root", "@root\n  @doc('<img src=x onerror=alert(1)>')"));
  const manifest = path.join(scratch, 'ubnf.json');
  await writeFile(manifest, JSON.stringify({schemaVersion:1,dependencies:{'std/layout':{version:'1.0.0',source:'builtin:std/layout@1.0.0'}}}));
  await promisify(execFile)(path.join(repo, 'rust/target/debug/unlaxer'), ['deps', 'resolve', '--manifest', manifest]);
  await promisify(execFile)(path.join(repo, 'rust/target/debug/unlaxer'), ['playground', '--grammar', grammar, '--output', project]);
  await promisify(execFile)(process.execPath, [path.join(project, 'build.mjs')], {timeout: 60000});
  server = spawn(process.execPath, [path.join(project, 'serve.mjs')], {stdio: ['ignore', 'pipe', 'pipe']});
  const url = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('server did not start')), 10000);
    let log = '';
    server.stdout.on('data', data => { log += data; const match = log.match(/UBNF_PLAYGROUND_URL=(http:\/\/127\.0\.0\.1:\d+\/)/); if (match) { clearTimeout(timer); resolve(match[1]); } });
    server.once('error', error => { clearTimeout(timer); reject(error); });
    server.once('exit', code => { clearTimeout(timer); reject(new Error(`server exited: ${code}`)); });
  });
  browser = await chromium.launch();
  const page = await browser.newPage({viewport: {width: 1400, height: 1000}});
  const errors = []; page.on('pageerror', error => errors.push(String(error)));
  page.on('dialog', () => assert.fail('grammar documentation must never execute HTML'));
  await page.goto(url);
  await page.getByRole('button', {name: '解析する', exact: true}).waitFor();
  await page.waitForFunction(() => !document.getElementById('parse').disabled);
  assert.match(await page.locator('#vocabulary').textContent(), /std\/layout@1\.0\.0/);
  assert.match(await page.locator('#vocabulary').textContent(), /layout\.SPACES_AND_COMMENTS/);
  assert.match(await page.locator('#vocabulary').textContent(), /sha256 [0-9a-f]{64}/);
  assert.equal(await page.locator('#vocabulary img').count(), 0);
  await page.getByText('空白・コメントの定義と出典', {exact:true}).click();
  await page.getByText('layout.SPACES', {exact:true}).click();
  assert.equal(await page.locator('#vocabulary pre').first().isVisible(), true);
  assert.match(await page.locator('#vocabulary pre').first().textContent(), /token SPACES/);
  assert.match(await page.locator('#catalog').textContent(), /price = 12/);
  assert.equal(await page.locator('#catalog img').count(), 0);
  assert.match(await page.locator('#catalog').textContent(), /<img/);
  for (const row of sample.cases) {
    await page.getByLabel('試験入力', {exact: true}).fill(row.input);
    await page.getByRole('button', {name: '解析する', exact: true}).click();
    await page.waitForFunction(() => !document.getElementById('parse').disabled);
    const result = JSON.parse(await page.locator('#result').textContent());
    assert.equal(result.ok, Object.hasOwn(row, 'fields'));
    if (row.fields) assert.deepEqual(result.ast.fields, row.fields);
    else assert.match(await page.locator('#hint').textContent(), /行 .*列 \(code point/);
  }
  await page.getByLabel('試験入力', {exact: true}).fill('price = 12;');
  await page.getByRole('button', {name: '解析する', exact: true}).click();
  await page.waitForFunction(() => !document.getElementById('parse').disabled);
  await page.locator('#cst-panel summary').click();
  await page.locator('#cst button').first().click();
  assert.ok(await page.locator('#input').evaluate(input => input.selectionEnd > input.selectionStart));
  await page.getByLabel('試験入力', {exact: true}).fill('changed');
  await page.locator('#cst button').first().click();
  assert.match(await page.locator('#hint').textContent(), /入力が変更/);
  await page.getByLabel('試験入力', {exact: true}).fill('x'.repeat(65537));
  await page.getByRole('button', {name: '解析する', exact: true}).click();
  assert.match(await page.locator('#status').textContent(), /実行エラー.*64 KiB/);
  await page.getByLabel('試験入力', {exact: true}).fill('price = 12;');
  await page.getByRole('button', {name: '解析する', exact: true}).click();
  await page.waitForFunction(() => !document.getElementById('parse').disabled);
  await page.getByLabel('ルール名・説明を検索').fill('missing999');
  assert.match(await page.locator('#catalog').textContent(), /一致するルールがありません/);
  await page.getByLabel('ルール名・説明を検索').fill('');
  await mkdir(new URL('../target/', import.meta.url), {recursive: true});
  await page.screenshot({path: new URL('../target/ubnf-playground-desktop.png', import.meta.url).pathname, fullPage: true});
  await page.setViewportSize({width: 390, height: 844});
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  const helpPage = await browser.newPage(); await helpPage.goto(url + 'help/index.html'); await helpPage.locator('#lesson pre').waitFor();
  assert.equal(await helpPage.locator('#lessons button').count(), catalog.lessons.length);
  // Simulate a non-responsive worker to verify the actual timeout and a subsequent retry.
  await page.route('**/worker.js', route => route.fulfill({contentType:'text/javascript', body:'self.onmessage = () => {};'}));
  await page.reload();
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('制限時間'));
  assert.match(await page.locator('#status').textContent(), /実行エラー/);
  await page.unroute('**/worker.js'); await page.reload();
  await page.waitForFunction(() => !document.getElementById('parse').disabled);
  assert.deepEqual(errors, []);
  console.log('Generated playground browser: real WASM, catalog, CST selection, AST, errors, limits, timeout/retry, HTML escaping, mobile, authoring help passed');
} finally {
  await browser?.close();
  if (server && server.exitCode === null) { const exited = new Promise(resolve => server.once('exit', resolve)); server.kill(); await exited; }
  await rm(scratch, {recursive: true, force: true});
}
