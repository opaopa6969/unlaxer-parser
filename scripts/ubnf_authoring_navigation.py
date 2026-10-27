"""#886: independent binding/outline oracle over the two real authoring stdio servers."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RENAME_ERROR = {'code': -32602, 'message': '安全に名前変更できません。完成した文法のローカル宣言を選び、重複・予約語を避けてください。外部 module の token / import alias / capture は一括 rename の対象外です。'}
METHODS = ['definition', 'references', 'rename', 'linkedEditingRange', 'documentSymbol', 'foldingRange']


def point(p):
    return {'line': p[0], 'character': p[1]}


def span(r):
    return {'start': point(r[:2]), 'end': point(r[2:])}


def location(uri, r):
    return {'uri': uri, 'range': span(r)}


def symbol(s):
    return {k: span(v) if k in ('range', 'selectionRange') else [symbol(c) for c in v] if k == 'children' else v for k, v in s.items()}


def result(client, method, params, expected, label):
    actual = client.result('textDocument/' + method, params)
    assert actual == expected, (label, method, 'expected', expected, 'actual', actual)


def rename_rejected(client, params, label):
    actual = client.request('textDocument/rename', params)
    assert actual.get('error') == RENAME_ERROR, (label, 'rename refusal', actual)


def open_document(client, uri, source, version=1):
    client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': version, 'languageId': 'ubnf', 'text': source}})
    return client.diagnostics(uri)


def change(client, uri, source, version=2):
    client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': version}, 'contentChanges': [{'text': source}]})
    return client.diagnostics(uri)


def closed(client, params, label):
    for method in METHODS:
        expected = {'changes': {}} if method == 'rename' else None if method == 'linkedEditingRange' else []
        result(client, method, params, expected, label)


def run_navigation(clients, directory):
    cases = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/navigation.json').read_text())
    assert len(cases) == 28, 'authored navigation fixtures must not disappear'
    evidence = []
    for case in cases:
        uri = (directory / (case['id'] + ' space 😀.ubnf')).as_uri()
        for host, client in zip(('java', 'rust'), clients):
            label = (case['id'], host)
            open_document(client, uri, case['source'])
            base = {'textDocument': {'uri': uri}}
            result(client, 'documentSymbol', base, [symbol(s) for s in case['symbols']], label)
            result(client, 'foldingRange', base, case['folding'], label)
            for binding in case['bindings']:
                refs = binding['references']
                target = binding['target']
                for at in binding['points']:
                    params = {**base, 'position': point(at), 'newName': 'Renamed_2'}
                    result(client, 'definition', params, [] if target is None else [location(uri, target)], label)
                    for include in (False, True):
                        result(client, 'references', {**params, 'context': {'includeDeclaration': include}},
                               [location(uri, r) for r in refs if include or r != target], label)
                    result(client, 'linkedEditingRange', params, {'ranges': [span(r) for r in refs]} if binding['editable'] else None, label)
                    if binding['editable']:
                        result(client, 'rename', params, {'changes': {uri: [{'range': span(r), 'newText': 'Renamed_2'} for r in refs]}}, label)
                        for name in case.get('rejectNames', []):
                            rename_rejected(client, {**params, 'newName': name}, (*label, name))
                    else:
                        rename_rejected(client, params, label)
            # Editing a live document must replace all stale bindings; closing it
            # must remove them without importing disk content for a closed URI.
            change(client, uri, "grammar Fixed { @root Entry ::= 'x'; }")
            params = {**base, 'position': point([0, 22]), 'newName': 'Changed'}
            result(client, 'definition', params, [location(uri, [0, 22, 0, 27])], label)
            client.send('textDocument/didClose', base)
            closed(client, params, label)
        evidence.append({'case': case['id'], 'navigationContract': 'full-json-independent-oracle'})
    evidence.extend(import_navigation(clients, directory))
    evidence.extend(package_navigation(clients, directory))
    return evidence


def at(source, needle, length=None, occurrence=0):
    """Scenario oracle: explicitly selected text, independent of any AST/index."""
    start = -1
    for _ in range(occurrence + 1):
        start = source.index(needle, start + 1)
    def p(i):
        before = source[:i]
        return [before.count('\n'), len(before.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2]
    return p(start) + p(start + (len(needle) if length is None else length))


def imported_binding(client, uri, source, module_uri, module, marker, name, refs, label):
    params = {'textDocument': {'uri': uri}, 'position': point(at(source, marker)[:2]), 'newName': 'Renamed'}
    target = location(module_uri, at(module, name + ' ::=', len(name)))
    result(client, 'definition', params, [target], label)
    for include in (False, True):
        expected = [location(uri, r) for r in refs] + ([target] if include else [])
        result(client, 'references', {**params, 'context': {'includeDeclaration': include}}, expected, label)
    rename_rejected(client, params, label)
    result(client, 'linkedEditingRange', params, None, label)


def import_navigation(clients, directory):
    folder = directory / 'navigation-imports'
    folder.mkdir()
    module_file = folder / 'number space 😀.ubnf'
    module = "grammar N { @ubnf: v2 token EMOJI ::= '😀'; token DIGIT ::= '0'; token NUMBER ::= DIGIT+; }"
    module_file.write_text(module)
    source = "grammar A {\r\n @import a from 'number space 😀.ubnf'\r\n @import b from 'number space 😀.ubnf'\r\n @ubnf: v2\r\n @root Start ::= a.NUMBER b. NUMBER;\r\n}\r\ngrammar B {\r\n @import c from 'number space 😀.ubnf'\r\n @ubnf: v2\r\n @root Start ::= c.NUMBER;\r\n}"
    uri = (folder / 'main.ubnf').as_uri()
    refs = [at(source, v) for v in ['a.NUMBER', 'b. NUMBER', 'c.NUMBER']]
    for host, client in zip(('java', 'rust'), clients):
        label = ('imports-aliases-scopes-buffers', host)
        diagnostics = open_document(client, uri, source)
        assert diagnostics == [], (label, diagnostics)
        for marker in ('a.NUMBER', 'b. NUMBER', 'c.NUMBER'):
            imported_binding(client, uri, source, module_file.as_uri(), module, marker, 'NUMBER', refs, label)
        # A second open document's references do not turn this into a workspace index.
        peer = (folder / 'peer.ubnf').as_uri()
        open_document(client, peer, source)
        client.diagnostics(uri)
        imported_binding(client, uri, source, module_file.as_uri(), module, 'a.NUMBER', 'NUMBER', refs, label)
        client.send('textDocument/didClose', {'textDocument': {'uri': peer}})
        client.diagnostics(uri)
        # The alias declaration and a capture are not symbol bindings.
        params = {'textDocument': {'uri': uri}, 'position': point(at(source, 'a from', 1)[:2]), 'newName': 'alias'}
        result(client, 'definition', params, [], label)
        rename_rejected(client, params, label)
        changed = "// moved 😀\r\n" + module
        open_document(client, module_file.as_uri(), changed)
        client.diagnostics(uri)
        imported_binding(client, uri, source, module_file.as_uri(), changed, 'a.NUMBER', 'NUMBER', refs, label)
        change(client, module_file.as_uri(), changed.replace('NUMBER', 'INTEGER'))
        client.diagnostics(uri)
        params['position'] = point(at(source, 'a.NUMBER')[:2])
        result(client, 'definition', params, [], label)
        client.send('textDocument/didClose', {'textDocument': {'uri': module_file.as_uri()}})
        client.diagnostics(uri)
        imported_binding(client, uri, source, module_file.as_uri(), module, 'a.NUMBER', 'NUMBER', refs, label)
        # Disk changes are observed by the next navigation request, like ensureIndex.
        module_file.write_text(changed)
        imported_binding(client, uri, source, module_file.as_uri(), changed, 'a.NUMBER', 'NUMBER', refs, label)
        module_file.write_text(module)
        client.send('textDocument/didSave', {'textDocument': {'uri': module_file.as_uri()}})
        client.diagnostics(uri)
        imported_binding(client, uri, source, module_file.as_uri(), module, 'a.NUMBER', 'NUMBER', refs, label)
        client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
    assert module_file.read_text() == module
    evidence = [{'case': 'import-aliases-scopes-buffer-change-close-save', 'navigationContract': 'full-json-independent-oracle'}]
    for mode, modules in [
        ('missing', {}),
        ('cycle', {'bad.ubnf': "grammar Bad { @import self from 'bad.ubnf' @ubnf: v2 token N ::= 'x'; }"}),
        ('rules-not-exported', {'bad.ubnf': "grammar Bad { @root Start ::= 'x'; }"}),
        ('collision', {'bad.ubnf': "grammar Bad { @ubnf: v2 token N ::= 'x'; }"}),
        ('unresolved-use', {'bad.ubnf': "grammar Bad { @ubnf: v2 token N ::= 'x'; }"}),
    ]:
        folder = directory / ('nav-import-' + mode)
        folder.mkdir()
        for name, content in modules.items():
            (folder / name).write_text(content)
        duplicate = " @import bad from 'bad.ubnf'" if mode == 'collision' else ''
        use = 'Missing' if mode == 'unresolved-use' else 'N'
        source = "grammar G { @import bad from 'bad.ubnf'" + duplicate + " @ubnf: v2 @root Start ::= bad." + use + "; }"
        uri = (folder / 'main.ubnf').as_uri()
        for host, client in zip(('java', 'rust'), clients):
            label = (mode, host)
            diagnostics = open_document(client, uri, source)
            assert any(d['code'] == 'E-MODULE' for d in diagnostics), (label, diagnostics)
            params = {'textDocument': {'uri': uri}, 'position': point(at(source, 'bad.' + use)[:2]), 'newName': 'Other'}
            result(client, 'definition', params, [], label)
            result(client, 'references', {**params, 'context': {'includeDeclaration': True}}, [], label)
            rename_rejected(client, params, label)
            # Module failure also disables edits to otherwise-local declarations.
            params['position'] = point(at(source, 'Start')[:2])
            rename_rejected(client, params, label)
            result(client, 'linkedEditingRange', params, None, label)
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
        evidence.append({'case': 'import-refusal-' + mode, 'navigationContract': 'full-json-and-module-refusal'})
    return evidence


def package_navigation(clients, directory):
    import hashlib
    from urllib.parse import quote
    evidence = []
    for mode in ('valid', 'hash-mismatch', 'missing-lock', 'escape'):
        folder = directory / ('nav-package-' + mode)
        folder.mkdir()
        entry = "layout space 😀+!()@.ubnf"
        module = "grammar Layout { @ubnf: v2 token EMOJI ::= '😀'; token GAP ::= ' '+; }\r\n"
        if mode == 'escape':
            module = "grammar Layout { @import bad from '../../outside.ubnf' @ubnf: v2 token GAP ::= ' '+; }"
        raw = json.dumps({'schemaVersion': 1, 'id': 'team/layout', 'version': '1.2.3', 'entry': entry, 'files': {entry: module}, 'dependencies': {}}, ensure_ascii=False).encode()
        digest = hashlib.sha256(raw).hexdigest()
        package_uri = 'ubnf-package:/' + digest + '/' + quote(entry, safe="/-._~!$&'()*+,;=:@")
        (folder / 'ubnf.json').write_text(json.dumps({'schemaVersion': 1, 'dependencies': {'team/layout': {'version': '1.2.3', 'source': 'local:must-not-be-fetched.json'}}}))
        if mode != 'missing-lock':
            (folder / 'ubnf.lock.json').write_text(json.dumps({'schemaVersion': 1, 'roots': {'team/layout': '1.2.3'}, 'packages': {'team/layout': {'id': 'team/layout', 'version': '1.2.3', 'sha256': digest, 'dependencies': {}}}}))
        cache = folder / '.ubnf-cache/packages'
        cache.mkdir(parents=True)
        artifact = cache / (digest + '.json')
        artifact.write_bytes(raw + (b' ' if mode == 'hash-mismatch' else b''))
        before = {str(p.relative_to(folder)): p.read_bytes() for p in folder.rglob('*') if p.is_file()}
        source = "grammar Main {\r\n @import layout from 'pkg:team/layout'\r\n @ubnf: v2\r\n @whitespace: layout.GAP\r\n @root Start ::= 'x';\r\n}"
        uri = (folder / 'main.ubnf').as_uri()
        refs = [at(source, 'layout.GAP')]
        for host, client in zip(('java', 'rust'), clients):
            label = (mode, host)
            # Neither a known-looking hash nor arbitrary file/http URIs authorize I/O.
            invalid = [None, artifact.as_uri(), 'file:///dev/stdin', 'https://127.0.0.1:1/private',
                       'ubnf-package:/unknown/source.ubnf', package_uri + '/../ubnf.lock.json',
                       package_uri.replace('/' + digest + '/', '/' + digest + '/%2e%2e/')]
            for unknown in invalid + [package_uri]:
                assert client.result('ubnf/packageSource', {'uri': unknown}) is None, (label, unknown)
            open_document(client, uri, source)
            # refreshImporters removes the just-opened document's cached index.
            assert client.result('ubnf/packageSource', {'uri': package_uri}) is None, label
            params = {'textDocument': {'uri': uri}, 'position': point(refs[0][:2])}
            if mode != 'valid':
                result(client, 'definition', params, [], label)
                assert client.result('ubnf/packageSource', {'uri': package_uri}) is None, label
            else:
                imported_binding(client, uri, source, package_uri, module, 'layout.GAP', 'GAP', refs, label)
                assert client.result('ubnf/packageSource', {'uri': package_uri}) == module, label
                # The request must use the verified snapshot, even if the artifact
                # becomes unreadable/invalid afterwards. Only indexing revalidates it.
                artifact.write_bytes(raw + b' ')
                for unknown in invalid:
                    assert client.result('ubnf/packageSource', {'uri': unknown}) is None, (label, unknown)
                assert client.result('ubnf/packageSource', {'uri': package_uri}) == module, label
                result(client, 'definition', params, [], label)
                assert client.result('ubnf/packageSource', {'uri': package_uri}) is None, label
                artifact.write_bytes(raw)
                imported_binding(client, uri, source, package_uri, module, 'layout.GAP', 'GAP', refs, label)
                # The VSIX selector sends file documents only. An open buffer at
                # the virtual cache file must not replace the verified package.
                for spoof in [(folder / '.ubnf-cache/sources' / digest / entry).as_uri()]:
                    open_document(client, spoof, "grammar Fake { @ubnf: v2 token SPOOF ::= 'x'; }")
                    diagnostics = client.diagnostics(uri)
                    actual = client.result('ubnf/packageSource', {'uri': package_uri})
                    assert actual == module, (label, spoof, diagnostics, actual)
                    imported_binding(client, uri, source, package_uri, module, 'layout.GAP', 'GAP', refs, label)
                    client.send('textDocument/didClose', {'textDocument': {'uri': spoof}})
                    client.diagnostics(uri)
                # Complete recovered imports can expose verified source for readonly
                # viewing, but never authorize a rename in incomplete grammar.
                incomplete = source[:-1]
                change(client, uri, incomplete)
                client.result('textDocument/documentSymbol', {'textDocument': {'uri': uri}})
                assert client.result('ubnf/packageSource', {'uri': package_uri}) == module, label
                rename_rejected(client, {**params, 'newName': 'Changed'}, label)
                change(client, uri, "grammar Fixed { @root Start ::= 'x'; }")
                assert client.result('ubnf/packageSource', {'uri': package_uri}) is None, label
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            assert client.result('ubnf/packageSource', {'uri': package_uri}) is None, label
            assert before == {str(p.relative_to(folder)): p.read_bytes() for p in folder.rglob('*') if p.is_file()}, 'server mutated manifest/lock/cache'
        evidence.append({'case': 'package-navigation-' + mode, 'navigationContract': 'verified-snapshot-only-full-json'})
    return evidence
