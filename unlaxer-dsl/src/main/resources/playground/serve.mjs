import {createServer} from 'node:http';
import {readFile, realpath} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = await realpath(fileURLToPath(new URL('./public/', import.meta.url)));
const mime = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.wasm':'application/wasm','.ubnf':'text/plain; charset=utf-8'};
const port = Number(process.env.PORT || 0);
if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('PORT must be 0..65535');
const server = createServer(async (request, response) => {
  if (request.method !== 'GET' && request.method !== 'HEAD') { response.writeHead(405).end(); return; }
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const requested = await realpath(path.join(root, pathname.endsWith('/') ? pathname + 'index.html' : pathname));
    if (!requested.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
    const content = await readFile(requested);
    response.setHeader('Content-Type', mime[path.extname(requested)] || 'application/octet-stream');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Cache-Control', 'no-store');
    response.writeHead(200).end(request.method === 'HEAD' ? undefined : content);
  } catch { response.writeHead(404).end('Not found'); }
});
server.listen(port, '127.0.0.1', () => console.log(`UBNF_PLAYGROUND_URL=http://127.0.0.1:${server.address().port}/`));
