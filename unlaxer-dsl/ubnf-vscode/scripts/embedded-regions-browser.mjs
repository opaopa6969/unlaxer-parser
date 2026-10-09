import assert from 'node:assert/strict';
import {mkdtemp, readFile, writeFile, copyFile, mkdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {execFile, spawn} from 'node:child_process';
import {promisify} from 'node:util';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const repo=fileURLToPath(new URL('../../../',import.meta.url));
const scratch=await mkdtemp(path.join(tmpdir(),'embedded-regions-browser-'));let browser,server;
try {
  const fixtures=path.join(repo,'docs/fixtures/embedded-grammars'), project=path.join(scratch,'project');
  const generator=path.join(repo,'rust/target/debug/unlaxer');
  await promisify(execFile)(generator,['playground','--grammar',path.join(fixtures,'FormulaInfoPlayground.ubnf'),'--output',project]);
  for(const [name,module] of [['TinyExpression','tiny'],['Java','java']]) await promisify(execFile)(generator,['generate','--grammar',path.join(fixtures,name+'.ubnf'),'--output',path.join(project,'src',module)]);
  await copyFile(path.join(fixtures,'region_adapter.rs'),path.join(project,'src/region_adapter.rs'));
  await promisify(execFile)(process.execPath,[path.join(project,'build.mjs')],{timeout:60000});
  server=spawn(process.execPath,[path.join(project,'serve.mjs')],{stdio:['ignore','pipe','pipe']});
  const url=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('server did not start')),10000);let log='';server.stdout.on('data',data=>{log+=data;const match=log.match(/UBNF_PLAYGROUND_URL=(http:\/\/127\.0\.0\.1:\d+\/)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.once('error',error=>{clearTimeout(timer);reject(error);});});
  browser=await chromium.launch();const page=await browser.newPage();const errors=[];page.on('pageerror',error=>errors.push(String(error)));await page.goto(url);
  await page.waitForFunction(()=>!document.getElementById('parse').disabled);await page.locator('#editor-mode').check();const evidence=[];
  let version=0;
  for(const line of (await readFile(path.join(fixtures,'editor.tsv'),'utf8')).trimEnd().split('\n')) {
    const [name,mode,source,expected]=line.split('\t');if(mode!=='all')continue;
    await page.locator('#input').fill(source);
    if(version && name==='remove-parent-close') {
      await page.locator('#languages button').last().click();assert.match(await page.locator('#hint').textContent(),/入力が変更/);
    }
    await page.getByRole('button',{name:'解析する',exact:true}).click();await page.waitForFunction(()=>!document.getElementById('parse').disabled);
    const result=JSON.parse(await page.locator('#result').textContent());assert.ok(result.languages,name);
    assert.ok(Number(result.languages.version)>version,name);version=Number(result.languages.version);
    const actual=result.languages.regions.map(r=>`${r.grammar}:${r.state}:${r.full.join(':')}:${r.body.join(':')}`).join(',');assert.equal(actual,expected,name);
    assert.equal(await page.locator('#languages button').count(),result.languages.regions.length,name);
    for(let index=0;index<result.languages.regions.length;index++) {
      const region=result.languages.regions[index];await page.locator('#languages button').nth(index).click();
      const selection=await page.locator('#input').evaluate(input=>[input.selectionStart,input.selectionEnd]);
      assert.deepEqual(selection,region.body.map(cp=>[...source].slice(0,cp).join('').length),name);
    }
    if(name==='remove-both-closes')assert.match(await page.locator('#languages').textContent(),/編集中/);
    if(name==='add-block')assert.match(await page.locator('#languages').textContent(),/構文エラー/);
    evidence.push(`${name}\t${actual}`);
  }
  await page.locator('#editor-mode').uncheck();await page.getByRole('button',{name:'解析する',exact:true}).click();await page.waitForFunction(()=>!document.getElementById('parse').disabled);assert.equal(await page.locator('#languages-panel').isVisible(),false);
  assert.deepEqual(errors,[]);await mkdir(new URL('../target/',import.meta.url),{recursive:true});await writeFile(new URL('../target/embedded-regions.tsv',import.meta.url),evidence.join('\n')+'\n');
  console.log('Embedded regions: generated WASM, original-body selection, partial/deleted/moved blocks, stale selection and strict-mode compatibility passed');
}finally{await browser?.close();if(server&&server.exitCode===null){const exited=new Promise(resolve=>server.once('exit',resolve));server.kill();await exited;}await rm(scratch,{recursive:true,force:true});}
