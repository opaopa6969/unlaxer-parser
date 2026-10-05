import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {chromium} from 'playwright';

const repo = fileURLToPath(new URL('../../../', import.meta.url));
const root = path.join(repo, 'build/learning-site');
const course = JSON.parse(await readFile(path.join(root, 'tiny-expression.json'), 'utf8'));
const mime = {'.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.json':'application/json', '.wasm':'application/wasm'};
// Serve under the actual project-site prefix to catch absolute asset paths.
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/unlaxer-parser/')) { res.writeHead(404).end(); return; }
    let relative = url.pathname.slice('/unlaxer-parser/'.length);
    if (!relative || relative.endsWith('/')) relative += 'index.html';
    const file = path.join(root, relative);
    if (!file.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
    const body = await readFile(file);
    res.writeHead(200, {'Content-Type':mime[path.extname(file)] || 'text/plain', 'X-Content-Type-Options':'nosniff'}).end(body);
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = process.env.UBNF_LEARNING_URL || `http://127.0.0.1:${server.address().port}/unlaxer-parser/`;
const browser = await chromium.launch();
try {
  const page = await browser.newPage({viewport:{width:1380, height:1000}});
  const errors = []; page.on('pageerror', error => errors.push(String(error)));
  page.on('dialog', () => assert.fail('content must not execute HTML'));
  await page.goto(url); await page.locator('#workspace').waitFor({state:'visible'});
  const check = async () => {
    await page.locator('#check').click();
    await page.waitForFunction(() => !document.getElementById('check').disabled);
  };
  await page.locator('#answers input').nth((course.steps[0].question.answer + 1) % 3).check();
  assert.ok(await page.locator('#next').isDisabled());
  assert.match(await page.locator('#answer-feedback').getAttribute('class'), /error/);
  for (let i = 0; i < course.steps.length; i++) {
    const step = course.steps[i];
    assert.equal(await page.locator('#step-title').textContent(), step.title);
    await check();
    assert.ok(await page.locator('#checks .failed').count() > 0, `${step.id}: starter needs work`);
    assert.ok(await page.locator('#next').isDisabled());
    await page.locator('#answers input').nth(step.question.answer).check();
    await page.locator('#grammar').fill(step.solution);
    if (step.semanticsExercise) await page.locator('#policy').selectOption('lazy');
    await check();
    assert.equal(await page.locator('#checks .failed').count(), 0, `${step.id}: ${await page.locator('#checks').textContent()}`);
    assert.equal(await page.locator('#checks li').count(), step.cases.length);
    assert.match(await page.locator('#check-status').textContent(), /合格！/);
    await page.locator('#input').fill(step.sample);
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.match(await page.locator('#run-status').textContent(), /計算結果/);
    const ast = JSON.parse(await page.locator('#ast').textContent());
    assert.deepEqual(ast.span, [0, [...step.sample].length]);
    if (i === 2) {
      await page.reload(); await page.locator('#workspace').waitFor({state:'visible'});
      assert.equal(await page.locator('#grammar').inputValue(), step.solution);
      assert.match(await page.locator('#progress-text').textContent(), /3 \/ 8/);
      assert.ok(!await page.locator('#next').isDisabled());
      await page.locator('#grammar').fill(step.solution + '\n');
      assert.ok(await page.locator('#next').isDisabled(), 'an edit invalidates the previous check');
      await check();
    }
    if (i + 1 < course.steps.length) await page.locator('#next').click();
  }
  assert.ok(await page.locator('#complete').isVisible());
  assert.match(await page.locator('#progress-text').textContent(), /8 \/ 8/);
  const downloaded = page.waitForEvent('download'); await page.locator('#download').click();
  const download = await downloaded;
  assert.equal(await readFile(await download.path(), 'utf8'), course.steps.at(-1).solution);
  await mkdir(new URL('../target/', import.meta.url), {recursive:true});
  await page.screenshot({path:new URL('../target/ubnf-learning-desktop.png', import.meta.url).pathname, fullPage:true});
  await page.setViewportSize({width:390, height:844});
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({path:new URL('../target/ubnf-learning-mobile.png', import.meta.url).pathname, fullPage:true});
  await page.locator('#input').fill('😀'); await page.locator('#run').click();
  await page.waitForFunction(() => !document.getElementById('run').disabled);
  assert.match(await page.locator('#run-status').textContent(), /不一致.*1 行 1 列/);
  await page.locator('#grammar').fill('grammar Broken {'); await check();
  assert.match(await page.locator('#check-status').textContent(), /文法を確認/);
  assert.equal(await page.locator('#complete').isVisible(), false);
  await page.locator('#grammar').fill(course.steps.at(-1).solution);
  await page.locator('#input').fill('x'.repeat(8193)); await page.locator('#run').click();
  await page.waitForFunction(() => !document.getElementById('run').disabled);
  assert.match(await page.locator('#run-status').textContent(), /実行エラー.*8 KiB/);
  await page.route('**/worker.js', route => route.fulfill({contentType:'text/javascript',body:'self.onmessage = () => {};'}));
  await check(); assert.match(await page.locator('#check-status').textContent(), /実行エラー.*5 秒/);
  await page.unroute('**/worker.js'); await check();
  assert.match(await page.locator('#check-status').textContent(), /合格！/);
  // A delayed result must never award progress to edited grammar or another step.
  await page.route('**/worker.js', async route => { await new Promise(resolve => setTimeout(resolve, 400)); await route.continue(); });
  await page.locator('#check').click(); await page.locator('#grammar').fill('grammar Changed {');
  await page.waitForTimeout(600);
  assert.equal(await page.locator('#complete').isVisible(), false);
  assert.match(await page.locator('#check-status').textContent(), /変更を保存/);
  await page.unroute('**/worker.js');
  const blockedStorage = await browser.newPage();
  await blockedStorage.addInitScript(() => { Object.defineProperty(window, 'localStorage', {get() { throw new Error('disabled'); }}); });
  await blockedStorage.goto(url); await blockedStorage.locator('#workspace').waitFor({state:'visible'});
  assert.match(await blockedStorage.locator('#storage-status').textContent(), /保存領域が使えません/);
  const noWorker = await browser.newPage();
  await noWorker.addInitScript(() => { window.Worker = class { constructor() { throw new Error('Worker disabled'); } }; });
  await noWorker.goto(url); await noWorker.locator('#workspace').waitFor({state:'visible'});
  await noWorker.locator('#check').click();
  assert.match(await noWorker.locator('#check-status').textContent(), /実行エラー.*Worker disabled/);
  assert.ok(!await noWorker.locator('#check').isDisabled(), 'worker startup failure remains retryable');
  const scenarios = await browser.newPage();
  const second = structuredClone(course); second.id = 'second-course'; second.title = '追加シナリオ';
  await scenarios.route('**/courses.json', route => route.fulfill({json:[
    {id:course.id,title:course.title,file:'tiny-expression.json'},
    {id:second.id,title:second.title,file:'second-course.json'}
  ]}));
  await scenarios.route('**/second-course.json', route => route.fulfill({json:second}));
  await scenarios.goto(url); await scenarios.locator('#workspace').waitFor({state:'visible'});
  const savedDraft = course.steps[0].starter + '\n// saved draft';
  await scenarios.locator('#grammar').fill(savedDraft);
  await scenarios.locator('#course').selectOption('second-course.json');
  await scenarios.waitForFunction(() => document.title.startsWith('追加シナリオ'));
  assert.equal(await scenarios.locator('#grammar').inputValue(), second.steps[0].starter);
  await scenarios.locator('#course').selectOption('tiny-expression.json');
  await scenarios.waitForFunction(() => document.title.startsWith('はじめての言語づくり'));
  assert.equal(await scenarios.locator('#grammar').inputValue(), savedDraft);
  const revised = structuredClone(course); revised.version++;
  await scenarios.route('**/tiny-expression.json', route => route.fulfill({json:revised}));
  await scenarios.reload(); await scenarios.locator('#workspace').waitFor({state:'visible'});
  assert.equal(await scenarios.locator('#grammar').inputValue(), course.steps[0].starter, 'new course version does not reuse stale progress');
  // Scenario text is rendered as text, and schema errors cannot start a broken course.
  const escaped = structuredClone(course); escaped.steps[0].explanation.push('<img src=x onerror=alert(1)>');
  await page.route('**/tiny-expression.json', route => route.fulfill({json:escaped}));
  await page.reload(); await page.locator('#workspace').waitFor({state:'visible'}); await page.locator('#steps button').first().click();
  assert.equal(await page.locator('#explanation img').count(), 0);
  assert.match(await page.locator('#explanation').textContent(), /<img/);
  escaped.steps[1].id = escaped.steps[0].id;
  await page.reload(); await page.waitForFunction(() => document.getElementById('load-status').textContent.includes('教材を開けません'));
  assert.ok(await page.locator('#workspace').isHidden());
  const free = await browser.newPage(); await free.goto(url + 'playground/');
  await free.waitForFunction(() => !document.getElementById('parse').disabled);
  await free.locator('#input').fill(course.steps.at(-1).sample); await free.locator('#parse').click();
  await free.waitForFunction(() => !document.getElementById('parse').disabled);
  assert.equal(JSON.parse(await free.locator('#result').textContent()).ok, true);
  assert.ok(await free.locator('a[href="https://opaopa6969.github.io/unlaxer-parser/ubnf/"]').count());
  const meta = await browser.newPage({viewport:{width:1380, height:1000}});
  meta.on('pageerror', error => errors.push(String(error)));
  await meta.goto(url);
  await meta.locator('a[href="ubnf/"]').click();
  assert.equal(new URL(meta.url()).pathname, '/unlaxer-parser/ubnf/');
  await meta.waitForFunction(() => !document.getElementById('parse').disabled && !document.getElementById('example').disabled);
  assert.equal(await meta.title(), 'UBNF 文法 Playground');
  assert.equal(await meta.locator('#name').textContent(), 'UBNF 文法を試す');
  const catalog = JSON.parse(await readFile(path.join(root, 'help/catalog.json'), 'utf8'));
  const parseMeta = async () => {
    await meta.locator('#parse').click();
    await meta.waitForFunction(() => !document.getElementById('parse').disabled);
    assert.doesNotMatch(await meta.locator('#status').textContent(), /実行エラー/);
    return JSON.parse(await meta.locator('#result').textContent());
  };
  assert.equal(await meta.locator('#input').inputValue(), catalog.examples[0].source);
  for (const example of catalog.examples) {
    await meta.locator('#example').selectOption(example.id);
    await meta.locator('#load-example').click();
    assert.equal(await meta.locator('#input').inputValue(), example.source);
    const result = await parseMeta();
    assert.equal(result.ok, true, example.id);
    assert.equal(result.mappingError, null, example.id);
    assert.equal(result.ast.type, 'UBNFFile');
    assert.ok(result.cst.nodes.length > 0);
  }
  await meta.locator('#example').selectOption('ubnf');
  await meta.locator('#load-example').click();
  const original = await readFile(path.join(root, 'ubnf/grammar.ubnf'), 'utf8');
  // Textarea values normalize CRLF from the checked-in meta-grammar.
  assert.equal(await meta.locator('#input').inputValue(), original.replace(/\r\n?/g, '\n'));
  assert.equal((await parseMeta()).ast.type, 'UBNFFile', 'the UBNF parser reads its own grammar');
  const unicode = catalog.examples[0].source.replace("'hello' @text", "'こんにちは😀' @text");
  await meta.locator('#input').fill(unicode);
  const unicodeResult = await parseMeta();
  await meta.locator('#cst-panel summary').click();
  // javaStyle nodes include the trailing whitespace they consumed.
  const stringNode = unicodeResult.cst.nodes.findIndex(node => node.rule === 'TerminalElement' && [...unicode].slice(...node.span).join('') === "'こんにちは😀' ");
  assert.ok(stringNode >= 0);
  await meta.locator('#cst button').nth(stringNode).click();
  assert.equal(await meta.locator('#input').evaluate(input => input.value.slice(input.selectionStart, input.selectionEnd)), "'こんにちは😀' ");
  await meta.locator('#example').selectOption('broken');
  assert.equal(await meta.locator('#input').inputValue(), unicode, 'selecting alone does not overwrite edits');
  await meta.locator('#load-example').click();
  assert.equal((await parseMeta()).ok, false);
  assert.match(await meta.locator('#hint').textContent(), /\d+ 行 \d+ 列/);
  // Syntactic recognition is deliberately distinct from semantic generation validation.
  await meta.locator('#input').fill('grammar Missing { @root Start ::= Undefined; }');
  assert.equal((await parseMeta()).ok, true);
  await meta.locator('#clear').click();
  assert.equal((await parseMeta()).ok, true, 'the meta grammar allows zero grammar declarations');
  await meta.locator('#example').selectOption('hello'); await meta.locator('#load-example').click(); await parseMeta();
  await meta.screenshot({path:new URL('../target/ubnf-meta-desktop.png', import.meta.url).pathname, fullPage:true});
  await meta.setViewportSize({width:390, height:844});
  assert.equal(await meta.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await meta.screenshot({path:new URL('../target/ubnf-meta-mobile.png', import.meta.url).pathname, fullPage:true});
  await meta.locator('header a[href="help/index.html"]').click();
  assert.ok(await meta.locator('a[href="https://opaopa6969.github.io/unlaxer-parser/ubnf/"]').count());
  // A slow example request must not replace a user's draft.
  const draft = await browser.newPage();
  let releaseExamples;
  const examplesPending = new Promise(resolve => { releaseExamples = resolve; });
  await draft.route('**/help/catalog.json', async route => { await examplesPending; await route.continue(); });
  await draft.goto(url + 'ubnf/');
  await draft.locator('#input').fill('grammar MyDraft {');
  releaseExamples();
  await draft.waitForFunction(() => !document.getElementById('example').disabled);
  assert.equal(await draft.locator('#input').inputValue(), 'grammar MyDraft {');
  await draft.route('**/help/catalog.json', route => route.fulfill({status:503,body:'unavailable'}));
  await draft.reload();
  await draft.waitForFunction(() => document.getElementById('example-status').textContent.includes('読み込めません'));
  await draft.waitForFunction(() => !document.getElementById('parse').disabled);
  await draft.locator('#input').fill(catalog.examples[0].source); await draft.locator('#parse').click();
  await draft.waitForFunction(() => !document.getElementById('parse').disabled);
  assert.equal(JSON.parse(await draft.locator('#result').textContent()).ok, true);
  assert.deepEqual(errors, []);
  console.log(`Learning browser: ${course.steps.length} lessons, ${course.steps.reduce((n,s) => n+s.cases.length,0)} shared cases, wrong answers, real grammar edits, resume, download, stale work, limits/retry, safe content, mobile and generated playground passed`);
  console.log('UBNF browser: six grammars, self-hosting, rejection location, Unicode CST selection, syntax-only boundary, empty file, mobile, links, preserved drafts and unavailable examples passed');
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
