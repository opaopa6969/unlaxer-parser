import assert from 'node:assert/strict';
import {mkdtemp, readFile, writeFile, mkdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const run=promisify(execFile),repo=fileURLToPath(new URL('../../../',import.meta.url));
const scratch=await mkdtemp(path.join(tmpdir(),'unlaxer-context-wasm-')),target='wasm32-unknown-unknown',runtime=path.join(scratch,'libunlaxer_runtime.rlib');
const evidence=['fixture\tcase\tmode\tpreserve_trivia\taccepted\tconsumed\tmatched\tfarthest'];
try {
 await run('rustc',['--edition=2021','--target',target,'--crate-type=rlib','--crate-name=unlaxer_runtime',path.join(repo,'rust/unlaxer-runtime/src/lib.rs'),'-o',runtime]);
 const fixtures=JSON.parse(await readFile(path.join(repo,'spec-corpus/contextual-lexing/corpus.json'),'utf8'));
 for(const fixture of fixtures) {
  const folder=path.join(scratch,fixture.name);await mkdir(folder);
  const grammar=fixture.grammarFile?path.join(repo,'spec-corpus/contextual-lexing',fixture.grammarFile):path.join(folder,'input.ubnf');
  if(!fixture.grammarFile)await writeFile(grammar,fixture.grammar);
  await run(path.join(repo,'rust/target/debug/unlaxer'),['generate','--grammar',grammar,'--output',path.join(folder,'generated')]);
  const entry=fixture.grammar.includes('@tokenStream: enabled')?'generated::parser::parse_with_lexing(&source,options)':'unlaxer_runtime::lexing::parse_contextual(generated::parser::grammar(),0,false,&source,options,vec![].into())';
  await writeFile(path.join(folder,'lib.rs'),`
mod generated;
use std::cell::RefCell;
use unlaxer_runtime::{json_string,lexing::{Mode,Options}};
thread_local! {static BUFFERS:RefCell<(Vec<u8>,Vec<u8>)>=RefCell::new((Vec::new(),Vec::new()));}
#[no_mangle] pub extern "C" fn pg_input(length:usize)->usize {BUFFERS.with(|buffers|{let mut buffers=buffers.borrow_mut();buffers.0.resize(length,0);buffers.0.as_mut_ptr() as usize})}
#[no_mangle] pub extern "C" fn pg_output()->usize {BUFFERS.with(|buffers|buffers.borrow().1.as_ptr() as usize)}
#[no_mangle] pub extern "C" fn pg_output_len()->usize {BUFFERS.with(|buffers|buffers.borrow().1.len())}
#[no_mangle] pub extern "C" fn pg_parse(mode:usize,keep:bool) {
 let source=BUFFERS.with(|buffers|String::from_utf8(buffers.borrow().0.clone()).unwrap());
 let options=Options{mode:[Mode::Direct,Mode::TriviaCache,Mode::TokensLazy,Mode::TokensEager][mode],preserve_trivia:keep};
 let mut result=${entry}.unwrap();
 let ast=if result.succeeded {generated::mapper::map(result.tree.as_ref().unwrap()).unwrap().canonical_json()} else {"null".into()};
 let captures=if result.succeeded {let tree=result.tree.as_ref().unwrap();tree.nodes[tree.root].captures.iter().map(|capture|format!(r#"{{"name":{},"span":[{},{}]}}"#,json_string(capture.name),capture.span.start,capture.span.end)).collect::<Vec<_>>().join(",")} else {String::new()};
 let lexemes=result.session.lexemes().iter().map(|lexeme|json_string(result.session.text(lexeme))).collect::<Vec<_>>().join(",");
 let output=format!(r#"{{"accepted":{},"consumed":{},"matched":{},"farthest":{},"ast":{},"captures":[{}],"lexemes":[{}]}}"#,result.succeeded,result.consumed,result.matched,result.farthest,ast,captures,lexemes);
 BUFFERS.with(|buffers|buffers.borrow_mut().1=output.into_bytes());
}
`);
  const wasm=path.join(folder,'language.wasm');
  await run('rustc',['--edition=2021','--target',target,'--crate-type=cdylib','--extern',`unlaxer_runtime=${runtime}`,path.join(folder,'lib.rs'),'-o',wasm]);
  const engine=(await WebAssembly.instantiate(await readFile(wasm),{})).instance.exports;
  for(const row of fixture.cases)for(const mode of fixture.modes?[0]:[0,1,2,3])for(const keep of [true,false]) {
   const bytes=new TextEncoder().encode(row.input),pointer=engine.pg_input(bytes.length);new Uint8Array(engine.memory.buffer,pointer,bytes.length).set(bytes);engine.pg_parse(mode,keep);
   const result=JSON.parse(new TextDecoder().decode(new Uint8Array(engine.memory.buffer,engine.pg_output(),engine.pg_output_len()))),name=`${fixture.name}/${row.name}/${mode}/${keep}`;
   assert.equal(result.accepted,row.accepted,name);assert.equal(result.consumed,row.accepted?row.sourceLength:row.consumed??0,name);assert.equal(result.matched,row.accepted?row.sourceLength:row.matched??0,name);
   if(row.accepted){assert.deepEqual(result.ast,row.ast,name);assert.deepEqual(result.captures,row.captures,name);}else {assert.equal(result.farthest,row.farthest,name);assert.equal(result.ast,null,name);}
   if(keep)assert.equal(result.lexemes.join(''),row.input,name);
   evidence.push(`${fixture.name}\t${row.name}\t${mode}\t${keep}\t${result.accepted}\t${result.consumed}\t${result.matched}\t${result.farthest}`);
  }
 }
 await mkdir(new URL('../target/',import.meta.url),{recursive:true});await writeFile(new URL('../target/contextual-lexing-wasm.tsv',import.meta.url),evidence.join('\n')+'\n');
 console.log(`Contextual lexing WASM: ${evidence.length-1} authored-oracle comparisons passed with raw Unicode/CRLF, captures, AST spans, failures, imports, trivia and rollback`);
} finally {await rm(scratch,{recursive:true,force:true});}
