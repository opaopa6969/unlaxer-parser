import assert from 'node:assert/strict';
import {mkdtemp,writeFile,copyFile,mkdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const repo=fileURLToPath(new URL('../../../',import.meta.url));
const scratch=await mkdtemp(path.join(tmpdir(),'playground-queries-'));let browser,server;
try {
  const fixtures=path.join(repo,'docs/fixtures/playground-queries'), embedded=path.join(repo,'docs/fixtures/embedded-grammars'),project=path.join(scratch,'project');
  const generator=path.join(repo,'rust/target/debug/unlaxer');
  await promisify(execFile)(generator,['playground','--grammar',path.join(embedded,'FormulaInfoPlayground.ubnf'),'--output',project]);
  for(const [name,module,directory] of [['TinyExpression','tiny',embedded],['Java','java',fixtures]]) await promisify(execFile)(generator,['generate','--grammar',path.join(directory,name+'.ubnf'),'--output',path.join(project,'src',module)]);
  await copyFile(path.join(fixtures,'query_adapter.rs'),path.join(project,'src/query_adapter.rs'));
  await promisify(execFile)(process.execPath,[path.join(project,'build.mjs')],{timeout:60000});
  server=spawn(process.execPath,[path.join(project,'serve.mjs')],{stdio:['ignore','pipe','pipe']});
  const url=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('server did not start')),10000);let log='';server.stdout.on('data',data=>{log+=data;const match=log.match(/UBNF_PLAYGROUND_URL=(http:\/\/127\.0\.0\.1:\d+\/)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.once('error',error=>{clearTimeout(timer);reject(error);});});
  browser=await chromium.launch();const page=await browser.newPage(), errors=[];page.on('pageerror',error=>errors.push(String(error)));await page.goto(url);
  await page.waitForFunction(()=>!document.getElementById('parse').disabled);await page.locator('#editor-mode').check();
  const source='😀F{T[foo /*😀*/ far foo f ]T}F', input=page.locator('#input');
  const ready=()=>page.waitForFunction(()=>!document.getElementById('parse').disabled);
  async function analyze(text=source,cursor=24) {
    await input.fill(text);await input.evaluate((node,cp)=>{const offset=[...node.value].slice(0,cp).join('').length;node.setSelectionRange(offset,offset);},cursor);
    await page.locator('#parse').click();await ready();
  }
  await analyze();
  assert.deepEqual(await page.locator('[data-operation]').evaluateAll(nodes=>nodes.map(n=>n.dataset.operation)),['CODE_ACTION','COMPLETION','DEFINITION','FORMAT','HOVER','RENAME']);
  await page.locator('[data-query-apply="foo"]').click();assert.equal(await input.inputValue(),'😀F{T[foo /*😀*/ far foo foo ]T}F');
  await analyze();await page.getByLabel('新しい名前',{exact:true}).fill('bar');await page.locator('[data-operation="RENAME"]').click();await ready();
  await page.locator('[data-query-apply="RENAME"]').click();assert.equal(await input.inputValue(),'😀F{T[bar /*😀*/ far bar f ]T}F');
  await analyze();await page.locator('[data-operation="FORMAT"]').click();await ready();await page.locator('[data-query-apply="FORMAT"]').click();assert.equal(await input.inputValue(),'😀F{T[foo\t/*😀*/ far foo f ]T}F');
  await analyze();await page.locator('[data-operation="CODE_ACTION"]').click();await ready();await page.locator('[data-query-apply="CODE_ACTION"]').click();assert.equal(await input.inputValue(),'😀F{T[foo /*😀*/ far foo foo ]T}F');
  // Returning to identical text is a newer editor revision and cannot revive an old edit.
  await analyze();await input.fill(source+' ');await input.fill(source);await page.locator('[data-query-apply="foo"]').click();
  assert.equal(await input.inputValue(),source);assert.match(await page.locator('#hint').textContent(),/古い入力/);
  await analyze(source,25);assert.equal(await page.locator('[data-operation]').count(),0);
  await analyze('😀F{T[bad]T}F',6);assert.equal(await page.locator('[data-operation]').count(),0);
  await analyze(source.slice(0,-4),25);assert.ok(await page.locator('[data-operation="COMPLETION"]').isVisible());
  assert.deepEqual(errors,[]);await mkdir(new URL('../target/',import.meta.url),{recursive:true});
  await writeFile(new URL('../target/playground-queries.tsv',import.meta.url),'completion\tPASS\nrename\tPASS\nformat\tPASS\ncode-action\tPASS\nstale-revision\tREJECTED\nclosed-delimiter\tUNAVAILABLE\nmissing-provider\tUNAVAILABLE\nopen-eof\tCOMPLETE\n');
  console.log('Playground query browser: real WASM registry/provider capabilities and snapshot-safe source edits passed');
}finally{await browser?.close();if(server&&server.exitCode===null){const exited=new Promise(resolve=>server.once('exit',resolve));server.kill();await exited;}await rm(scratch,{recursive:true,force:true});}
