import assert from 'node:assert/strict';

export async function verifyCompletion(browser, url) {
  const page = await browser.newPage({viewport:{width:1100, height:950}, hasTouch:true});
  const errors = []; page.on('pageerror', error => errors.push(String(error)));
  await page.goto(url + 'ubnf/');
  await page.waitForFunction(() => !document.getElementById('complete-input').disabled && !document.getElementById('parse').disabled);
  const input = page.locator('#input'), panel = page.locator('#completion-panel');
  const position = async marked => {
    await input.fill(marked.replace('¦', ''));
    await input.evaluate((node, cursor) => { node.focus(); node.setSelectionRange(cursor, cursor); }, marked.indexOf('¦'));
  };
  const parse = async () => {
    await page.locator('#parse').click(); await page.waitForFunction(() => !document.getElementById('parse').disabled);
    assert.equal(JSON.parse(await page.locator('#result').textContent()).ok, true);
  };
  await input.fill('gra');
  await panel.waitFor({state:'visible'}); await input.press('Enter');
  assert.match(await input.inputValue(), /^grammar MyLanguage/); await parse();
  const marked = "grammar G {\n  token NUMBER ::= CHAR_RANGE('0', '9')+;\n  @root\n  @mapping(Value, params=[text])\n  Start ::= NU¦ @text;\n}";
  await position(marked); await input.press('Control+Space');
  assert.equal(await page.locator('#completion-list [aria-selected=true] strong').textContent(), 'NUMBER');
  await input.press('Tab');
  assert.equal(await input.inputValue(), marked.replace('NU¦', 'NUMBER'));
  await input.press('Control+z'); assert.equal(await input.inputValue(), marked.replace('¦', ''), 'completion preserves native undo');
  await input.press('Control+Shift+z'); assert.equal(await input.inputValue(), marked.replace('NU¦', 'NUMBER'));
  await parse();
  await position('grammar G {\n  @ma¦\n}'); await input.press('Control+Space');
  await page.getByRole('option', {name:'@mapping 構文'}).click();
  assert.equal(await input.inputValue(), 'grammar G {\n  @mapping(Value, params=[text])\n}');
  await position('grammar G {\n  @whitespace: ¦\n  Start ::= \'hello\';\n}');
  await page.locator('#complete-input').click();
  assert.match(await page.locator('#completion-description').textContent(), /\/\/.*\/\*/);
  await input.press('ArrowDown');
  assert.equal(await page.locator('#completion-list [aria-selected=true] strong').textContent(), 'none');
  await input.press('ArrowUp'); await input.press('Enter'); await parse();
  await position('grammar G { @ro¦ }'); await input.press('Control+Space');
  await input.press('Escape'); assert.ok(await panel.isHidden()); assert.equal(await input.inputValue(), 'grammar G { @ro }');
  for (const source of ["grammar G { // @ma¦", "grammar G { Start ::= '@ma¦'; }"]) {
    await position(source); await input.press('Control+Space'); assert.ok(await panel.isHidden());
  }
  await position('grammar G { @ma¦ }'); await input.press('Control+Space');
  await input.dispatchEvent('compositionstart'); assert.ok(await panel.isHidden());
  await input.press('Control+Space'); assert.ok(await panel.isHidden());
  await input.dispatchEvent('compositionend');
  await input.press('Control+Space'); await page.locator('#load-example').click();
  assert.ok(await panel.isHidden()); assert.match(await input.inputValue(), /^grammar Hello/);
  await position('grammar G { @ma¦ }'); await input.press('Control+Space');
  await input.press('ArrowLeft'); assert.ok(await panel.isHidden(), 'moving the caret dismisses stale suggestions');
  await page.setViewportSize({width:390, height:844});
  await position('grammar G { @whitespace: ja¦ }'); await page.locator('#complete-input').click();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({path:new URL('../target/ubnf-completion-mobile.png', import.meta.url).pathname, fullPage:true});
  await page.getByRole('option', {name:'javaStyle 設定値'}).tap();
  assert.match(await input.inputValue(), /@whitespace: javaStyle/);
  assert.deepEqual(errors, []);
  await page.close();
  console.log('UBNF completion browser: snippets, references, keyboard/click, native undo/redo, whitespace help, real WASM, comments, IME, dismissal, examples and mobile passed');
}
