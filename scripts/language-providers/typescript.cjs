// Analysis-only language service: project snapshots and the pinned compiler's standard library.
const fs = require('node:fs');
const path = require('node:path');
const req = JSON.parse(fs.readFileSync(0, 'utf8'));
const capabilities = ['PARSE', 'TYPE_CHECK', 'FLOW_NARROWING', 'HOVER', 'DEFINITION', 'COMPLETION'];
const empty = status => ({status, capabilities, diagnostics: [], items: []});
function run() {
  let ts;
  try { ts = require(path.resolve(process.argv[2])); } catch (_) { return empty('UNAVAILABLE'); }
  if (ts.version !== '5.9.3' || req.provider !== 'typescript' || req.version !== '5.9.3') return empty('UNAVAILABLE');
  if (req.language.id !== 'typescript' || req.language.entry !== 'SourceFile' || req.execute || !['PARSE','VALIDATE','HOVER','DEFINITION','COMPLETION'].includes(req.operation)) return empty('UNSUPPORTED');
  if (Object.keys(req.config).some(k => !['strict', 'target'].includes(k)) || Object.keys(req.parameters).some(k => !['fileName','prefix','timeoutMs'].includes(k))) return empty('UNSUPPORTED');
  if (req.config.strict && !['true', 'false'].includes(req.config.strict) || req.config.target && req.config.target !== 'ES2022') return empty('UNSUPPORTED');
  const options = {strict: req.config.strict !== 'false', target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Node10, noEmit: true, noErrorTruncation: true};
  const sources = new Map();
  const name = (snapshot, fallback) => {
    let file;
    try { file = decodeURIComponent(new URL(snapshot.uri).pathname); } catch (_) { file = snapshot.uri; }
    if (!file.endsWith('.ts')) file = fallback;
    if (!file || file.split('/').includes('..')) throw new Error('invalid virtual file name');
    return path.posix.join('/virtual', file.replace(/^\/+/, ''));
  };
  const primary = name(req.snapshot, req.parameters.fileName || 'main.ts');
  sources.set(primary, req.snapshot);
  for (const snapshot of Object.values(req.documents)) {
    if (snapshot.uri === req.snapshot.uri || !snapshot.uri.endsWith('.ts')) continue;
    const file = name(snapshot, 'dependency.ts');
    if (sources.has(file)) throw new Error('duplicate virtual file name');
    sources.set(file, snapshot);
  }
  const library = path.dirname(ts.getDefaultLibFilePath(options));
  const trustedLibrary = file => path.dirname(path.resolve(file)) === library && /^lib\..*\.d\.ts$/.test(path.basename(file));
  const read = file => sources.get(file)?.text ?? (trustedLibrary(file) ? fs.readFileSync(file, 'utf8') : undefined);
  const host = {
    getScriptFileNames: () => [...sources.keys()], getScriptVersion: file => String(sources.get(file)?.version ?? '0'),
    getScriptSnapshot: file => { const text = read(file); return text === undefined ? undefined : ts.ScriptSnapshot.fromString(text); },
    getCurrentDirectory: () => '/virtual', getCompilationSettings: () => options,
    getDefaultLibFileName: opts => ts.getDefaultLibFilePath(opts),
    fileExists: file => sources.has(file) || trustedLibrary(file) && fs.existsSync(file), readFile: read,
    directoryExists: directory => directory === library || [...sources.keys()].some(file => file.startsWith(directory + '/')),
    getScriptKind: () => ts.ScriptKind.TS, useCaseSensitiveFileNames: () => true,
  };
  const service = ts.createLanguageService(host);
  function cp(text, offset) {
    if (offset < 0 || offset > text.length || offset > 0 && offset < text.length && /[\ud800-\udbff]/.test(text[offset - 1]) && /[\udc00-\udfff]/.test(text[offset])) throw new Error('invalid UTF16 boundary');
    return Array.from(text.slice(0, offset)).length;
  }
  function location(file, span) {
    const snapshot = sources.get(file); if (!snapshot) return undefined;
    return {uri: snapshot.uri, version: snapshot.version, start: cp(snapshot.text, span.start), end: cp(snapshot.text, span.start + span.length)};
  }
  const diagnostics = [...service.getSyntacticDiagnostics(primary), ...(req.operation === 'PARSE' ? [] : service.getSemanticDiagnostics(primary))].map(d => {
    const loc = d.file ? location(d.file.fileName, {start: d.start, length: d.length}) : location(primary, {start: 0, length: 0});
    if (!loc) throw new Error('diagnostic outside explicit project');
    return {code: `TS${d.code}`, message: ts.flattenDiagnosticMessageText(d.messageText, '\n'), severity: ts.DiagnosticCategory[d.category].toUpperCase(), locations: [loc]};
  });
  const items = [];
  const cursor = Array.from(req.snapshot.text).slice(0, req.cursor).join('').length;
  const definitions = () => (service.getDefinitionAtPosition(primary, cursor) || []).map(d => location(d.fileName, d.textSpan)).filter(Boolean);
  if (req.operation === 'HOVER') {
    const info = service.getQuickInfoAtPosition(primary, cursor);
    if (info) items.push({label: req.snapshot.text.slice(info.textSpan.start, info.textSpan.start + info.textSpan.length), detail: ts.displayPartsToString(info.displayParts), locations: definitions(), edits: []});
  } else if (req.operation === 'DEFINITION') {
    for (const definition of service.getDefinitionAtPosition(primary, cursor) || []) {
      const loc = location(definition.fileName, definition.textSpan);
      if (loc) items.push({label: definition.name, detail: definition.kind, locations: [loc], edits: []});
    }
  } else if (req.operation === 'COMPLETION') {
    const prefix = req.parameters.prefix || '';
    if (cursor >= prefix.length && req.snapshot.text.slice(cursor - prefix.length, cursor) === prefix) {
      const completion = service.getCompletionsAtPosition(primary, cursor, {});
      for (const entry of (completion?.entries || []).filter(e => e.name.startsWith(prefix)).sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0).slice(0, 100)) {
        const replacement = entry.replacementSpan || completion.optionalReplacementSpan || {start: cursor - prefix.length, length: prefix.length};
        items.push({label: entry.name, detail: entry.kind, locations: [], edits: [{location: location(primary, replacement), text: entry.insertText || entry.name}]});
      }
    }
  }
  service.dispose();
  return {status: diagnostics.length ? 'DIAGNOSTICS' : 'OK', capabilities, diagnostics, items};
}
try { process.stdout.write(JSON.stringify(run())); } catch (_) { process.stdout.write(JSON.stringify(empty('FAILED'))); }
