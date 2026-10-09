import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
const root = path.resolve(process.argv[2]);
const language = process.argv[3];
const mime = {'.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.json':'application/json', '.wasm':'application/wasm', '.tsv':'text/plain'};
const server = createServer(async (req, res) => {
  try {
    const file = path.join(root, new URL(req.url, 'http://localhost').pathname.replace(/^\//, '') || 'index.html');
    if (!file.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
    res.writeHead(200, {'Content-Type':mime[path.extname(file)] || 'text/plain'}).end(await readFile(file));
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  const errors = []; page.on('pageerror', error => errors.push(String(error)));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.waitForFunction(() => !document.getElementById('parse').disabled);
  await page.locator('#profile table').waitFor();
  assert.match(await page.locator('#profile').textContent(), new RegExp(`lang/${language}.*0\\.1\\.0`));
  const capability = name => page.locator('#profile tr').filter({has:page.locator('td', {hasText:new RegExp(`^${name}$`)})});
  assert.match(await capability('PARSE').textContent(), /部分対応/);
  assert.match(await capability('VALIDATE').textContent(), /外部解析器が必要/);
  assert.match(await capability('EXECUTE').textContent(), /未対応/);
  const source = {java:'//日😀\r\nclass Main { String text = "日😀"; }', typescript:'//日😀\r\nconst text: string = "日😀";', rust:'//日😀\r\nfn main() { let text = "日😀"; }'}[language];
  await page.locator('#input').fill(source);
  await page.locator('#parse').click();
  await page.waitForFunction(() => !document.getElementById('parse').disabled);
  const result = JSON.parse(await page.locator('#result').textContent());
  assert.equal(result.ok, true);
  assert.equal(result.ast.type, 'Source');
  assert.equal(result.languages, undefined);
  await page.locator('#input').fill(source + '?');
  await page.locator('#parse').click();
  await page.waitForFunction(() => !document.getElementById('parse').disabled);
  assert.equal(JSON.parse(await page.locator('#result').textContent()).ok, false);
  assert.match(await page.locator('#profile-panel').textContent(), /その言語全体の構文エラーとは限りません/);
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  assert.deepEqual(errors, []);
  console.log(`${language}: fixed profile, partial/external/unsupported capabilities and actual generated WASM passed`);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
