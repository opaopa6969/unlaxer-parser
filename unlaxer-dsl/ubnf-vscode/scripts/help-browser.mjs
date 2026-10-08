import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import {chromium} from 'playwright';

const root = new URL('../help-dist/', import.meta.url);
const catalog = JSON.parse(await readFile(new URL('catalog.json', root), 'utf8'));
const files = {'/': ['index.html', 'text/html'], '/help.css': ['help.css', 'text/css'], '/help.js': ['help.js', 'text/javascript'], '/catalog.json': ['catalog.json', 'application/json']};
const server = createServer(async (request, response) => {
  const entry = files[request.url];
  if (!entry) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', `${entry[1]}; charset=utf-8`);
  response.end(await readFile(new URL(entry[0], root)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}/`;
let browser;
try {
  browser = await chromium.launch();
  const context = await browser.newContext({viewport: {width: 1280, height: 1000}});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.route('**/*', route => route.request().url().startsWith(url) ? route.continue() : route.abort());
  await page.goto(url);
  await page.locator('#lesson pre').waitFor();
  assert.equal(await page.locator('#generate').isVisible(), false, 'standalone help cannot launch host tools');
  assert.match(await page.locator('#setup').textContent(), /wasm32-unknown-unknown/);
  assert.equal(await page.locator('#lessons button').count(), 6);
  for (const lesson of catalog.lessons) {
    await page.locator(`[data-lesson="${lesson.id}"]`).click();
    const example = catalog.examples.find(item => item.id === lesson.example);
    assert.equal(await page.locator('#lesson pre').textContent(), example.source);
    assert.equal(await page.locator('.cases li').count(), example.cases.length);
  }
  await page.locator('[data-lesson="hello"]').click();
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', {name: '.ubnf をダウンロード'}).click();
  const download = await downloaded;
  assert.equal(download.suggestedFilename(), 'Hello.ubnf');
  assert.equal(await readFile(await download.path(), 'utf8'), catalog.examples[0].source);
  await page.getByRole('button', {name: '構文 catalog', exact: true}).click();
  await page.getByLabel('やりたいこと・構文を検索').fill('CHAR_RANGE');
  assert.match(await page.locator('#entries').textContent(), /1 文字の範囲/);
  await page.getByLabel('やりたいこと・構文を検索').fill('存在しない語句999');
  assert.match(await page.locator('#count').textContent(), /^0 件/);
  await page.getByRole('button', {name: '困ったとき', exact: true}).click();
  assert.equal(await page.locator('#errors article').count(), catalog.troubleshooting.length);
  await page.getByRole('button', {name: 'はじめの一歩', exact: true}).click();
  await mkdir(new URL('../target/', import.meta.url), {recursive: true});
  await page.screenshot({path: new URL('../target/ubnf-help-desktop.png', import.meta.url).pathname, fullPage: true});
  await page.setViewportSize({width: 390, height: 844});
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({path: new URL('../target/ubnf-help-mobile.png', import.meta.url).pathname, fullPage: true});
  // Same UI in a webview: only a validated example id crosses the bridge, never executable source/commands.
  await page.addInitScript(() => { window.messages = []; window.acquireVsCodeApi = () => ({postMessage: message => window.messages.push(message)}); });
  await page.reload();
  await page.getByRole('button', {name: '新規文書で開く', exact: true}).click();
  assert.deepEqual(await page.evaluate(() => window.messages), [{type: 'openExample', id: 'hello'}]);
  await page.getByRole('button', {name: '保存した文法の Playground を生成して開く', exact: true}).click();
  assert.deepEqual(await page.evaluate(() => window.messages.at(-1)), {type: 'generatePlayground'});
  assert.deepEqual(errors, []);
  await context.close();
  console.log('UBNF help browser: 6 lessons, examples, search, troubleshooting, download, mobile, webview bridge passed');
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
