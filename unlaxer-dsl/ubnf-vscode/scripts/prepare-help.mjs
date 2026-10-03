import {cp, mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const source = new URL('../../src/main/resources/ubnf-help/', import.meta.url);
const target = new URL('../help-dist/', import.meta.url);
await mkdir(target, {recursive: true});
for (const file of ['catalog.json', 'index.html', 'help.css', 'help.js']) {
  await cp(new URL(file, source), new URL(file, target));
}
console.log(`UBNF authoring help: ${fileURLToPath(target)}`);
