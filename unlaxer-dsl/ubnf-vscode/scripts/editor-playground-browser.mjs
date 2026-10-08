import assert from 'node:assert/strict';
import {mkdtemp, readFile, writeFile, copyFile, mkdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {execFile, spawn} from 'node:child_process';
import {promisify} from 'node:util';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const repo=fileURLToPath(new URL('../../../',import.meta.url));
const scratch=await mkdtemp(path.join(tmpdir(),'editor-playground-browser-'));let browser,server;
try {
  const fixture=JSON.parse(await readFile(path.join(repo,'spec-corpus/editor-pipeline/corpus.json'),'utf8'));const project=path.join(scratch,'project');
  await promisify(execFile)(path.join(repo,'rust/target/debug/unlaxer'),['playground','--grammar',path.join(repo,'spec-corpus/editor-pipeline/model.ubnf'),'--output',project]);
  await copyFile(path.join(repo,'examples/semantic-model/editor_adapter.rs'),path.join(project,'src/typed_adapter.rs'));
  await copyFile(path.join(repo,'examples/semantic-model/playground_editor_adapter.rs'),path.join(project,'src/editor_adapter.rs'));
  await promisify(execFile)(process.execPath,[path.join(project,'build.mjs')],{timeout:60000});
  server=spawn(process.execPath,[path.join(project,'serve.mjs')],{stdio:['ignore','pipe','pipe']});
  const url=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('server did not start')),10000);let log='';server.stdout.on('data',data=>{log+=data;const match=log.match(/UBNF_PLAYGROUND_URL=(http:\/\/127\.0\.0\.1:\d+\/)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.once('error',error=>{clearTimeout(timer);reject(error);});});
  browser=await chromium.launch();const page=await browser.newPage();const errors=[];page.on('pageerror',error=>errors.push(String(error)));await page.goto(url);
  await page.waitForFunction(()=>!document.getElementById('parse').disabled);await page.locator('#editor-mode').check();const evidence=[];
  for(const row of fixture.cases.filter(row=>!['inserted-identifier','crossing-name','limit','keyword-fragment'].includes(row.id))) {
    const raw=fixture.prefix+row.tail;const source=raw.replace(/\r\n/g,'\n');const cursor=row.cursor-[...raw].slice(0,row.cursor).filter(value=>value==='\r').length;await page.getByLabel('試験入力',{exact:true}).fill(source);
    await page.locator('#input').evaluate((input,cursor)=>input.setSelectionRange(cursor,cursor),[...source].slice(0,cursor).join('').length);
    await page.getByRole('button',{name:'解析する',exact:true}).click();await page.waitForFunction(()=>!document.getElementById('parse').disabled);
    const result=JSON.parse(await page.locator('#result').textContent());assert.equal(result.editor.status,row.status,row.id);assert.equal(result.typed.status,row.status,row.id);
    assert.deepEqual(result.typed.expectedTypes,row.expectedTypes,row.id);assert.deepEqual(result.typed.completions.map(item=>item.label),row.completions,row.id);assert.equal(result.editor.sourceLength,[...source].length,row.id);
    for(const node of result.editor.nodes)for(const capture of node.captures){assert.ok(capture.span[1]<=[...source].length,row.id);assert.equal(capture.text,[...source].slice(...capture.span).join(''),row.id);}
    for(const item of result.typed.completions)assert.ok([...source].slice(...item.span).join('').trim().startsWith(`let ${item.label}:`),row.id);
    if(row.status==='PARTIAL'){assert.match(await page.locator('#status').textContent(),/部分結果/,row.id);assert.equal(result.ast??null,null,row.id);assert.equal(result.editor.defects[0].kind,row.id==='broken-sibling'?'ERROR':'MISSING',row.id);}
    if(row.completions.length)assert.match(await page.locator('#typed-completions').textContent(),new RegExp(row.completions[0]),row.id);
    evidence.push(`${row.id}\t${JSON.stringify(result.typed)}\t${JSON.stringify(result.editor.defects)}`);
  }
  const source=fixture.prefix+'call process(ctx, ';await page.getByLabel('試験入力',{exact:true}).fill(source);await page.locator('#editor-mode').uncheck();
  await page.getByRole('button',{name:'解析する',exact:true}).click();await page.waitForFunction(()=>!document.getElementById('parse').disabled);
  const strict=JSON.parse(await page.locator('#result').textContent());assert.equal(strict.ok,false);assert.equal(strict.editor,undefined);assert.equal(strict.typed,undefined);
  assert.deepEqual(errors,[]);await mkdir(new URL('../target/',import.meta.url),{recursive:true});await writeFile(new URL('../target/editor-playground.tsv',import.meta.url),evidence.join('\n')+'\n');
  console.log('Editor playground: actual generated WASM, partial CST, typed completion, Unicode/CRLF spans, error siblings, original-source values and strict-mode compatibility passed');
}finally{await browser?.close();if(server&&server.exitCode===null){const exited=new Promise(resolve=>server.once('exit',resolve));server.kill();await exited;}await rm(scratch,{recursive:true,force:true});}
