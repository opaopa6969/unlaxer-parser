"""#894/#1024: full root/header/module JSON; body syntax remains explicitly pending.

The oracle is authored from the fixed Java source, never from observed replies.
Only URI/path substitution and LF/CRLF coordinate conversion are mechanical.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE_PREFIX = 'import を解決できません。保存場所・alias・token 名を確認してください: '


def position(source, at):
    before = source[:at]
    return {'line': before.count('\n'),
            'character': len(before.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2}


def diagnostic(source, start, end, message):
    return {'range': {'start': position(source, start), 'end': position(source, end)},
            'severity': 1, 'code': 'E-MODULE', 'source': 'ubnf-module', 'message': message}


def published(client, uri, expected):
    actual = client.diagnostics_params(uri)
    oracle = {'uri': uri, 'version': 0, 'diagnostics': expected}
    assert actual == oracle, (client.fixture, 'expected', oracle, 'actual', actual)


def opened(client, uri, source, expected):
    client.send('textDocument/didOpen', {'textDocument': {
        'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
    published(client, uri, expected)


def hover(client, uri, source, expected):
    actual = client.result('textDocument/hover', {
        'textDocument': {'uri': uri}, 'position': position(source, len(source))})
    assert actual == expected, (client.fixture, expected, actual)


def run_syntax_status(clients, directory):
    fixture = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/syntax-status.json').read_text())
    assert len(fixture['rootFailures']) == 14 and len(fixture['hoverOnly']) == 9
    assert len(fixture['modules']) == 30
    assert len(fixture['headerFailures']) == 29
    config = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/configuration.json').read_text())
    evidence = []
    for host, client in zip(('java', 'rust'), clients):
        assert client.result('initialize', {'capabilities': {}}) == config['expected']['default'][host]
        client.send('initialized')
    for case in fixture['rootFailures'] + fixture['headerFailures']:
        uri = (directory / (case['id'] + '.ubnf')).as_uri()
        for client in clients:
            client.fixture = 'syntax-root/' + case['id']
            opened(client, uri, case['source'], case['diagnostics'])
            if 'hover' in case:
                hover(client, uri, case['source'], case['hover'])
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
        evidence.append({'case': case['id'], 'contract': 'header-full-diagnostic-json'
                         if case in fixture['headerFailures'] else 'root-full-json-and-hover'})
    for case in fixture['hoverOnly']:
        uri = (directory / (case['id'] + '.ubnf')).as_uri()
        for client in clients:
            client.fixture = 'syntax-status/' + case['id']
            client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'text': case['source']}})
            assert any(d['code'] == 'ULX-PARSE-001' for d in client.diagnostics(uri))
            hover(client, uri, case['source'], case['hover'])
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
        # This deliberately does NOT count as complete syntax conformance.
        evidence.append({'case': case['id'], 'contract': 'hover-only',
                         'syntaxDiagnostic': 'pending-1024'})
    for case in fixture['modules']:
        for ending in ('\n', '\r\n'):
            folder = directory / (case['id'] + ('-crlf' if ending == '\r\n' else '-lf'))
            folder.mkdir()
            for name, text in case.get('files', {}).items():
                (folder / name).write_bytes(text.replace('\n', ending).encode())
            if 'package' in case:
                package(folder, case['package'])
            declarations = [f"@import {alias} from '{path}'" for alias, path in case['imports']]
            source = ending.join(['// 😀', 'grammar Main {', *[' ' + d for d in declarations],
                                  " @ubnf: v2", " @root Start ::= " + case.get('body', "'x'") + ';', '}'])
            source = case.get('prelude', '').replace('\n', ending) + case.get('source', source)
            uri = case.get('uri', (folder / 'main space 😀.ubnf').as_uri())
            if 'globalError' in case:
                oracle = [diagnostic(source, 0, 1, MODULE_PREFIX + case['globalError'])]
            else:
                oracle = [diagnostic(source, source.index(decl), source.index(decl) + len(decl),
                                     error.replace('$DIR', str(folder)))
                          for decl, error in zip(declarations, case['errors'])]
            if case.get('recovered'):
                # The editor's recovery/outer catch is one UTF-16 unit,
                # while the root syntax diagnostic is one whole scalar.
                for d in oracle:
                    d['range'] = {'start': {'line': 0, 'character': 0},
                                  'end': {'line': 0, 'character': 1}}
                root = json.loads(json.dumps(fixture['rootFailures'][2]['diagnostics'][0]))
                root['data']['totalLength'] = len(source.encode('utf-16-le')) // 2
                oracle.insert(0, root)
            before = {p: p.read_bytes() for p in folder.rglob('*') if p.is_file()}
            for client in clients:
                client.fixture = 'module/' + folder.name
                opened(client, uri, source, oracle)
                hover(client, uri, source, {'contents': {'kind': 'markdown', 'value':
                      'Parse error at offset 0' if case.get('recovered') else 'Valid UBNF'}})
                client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            assert before == {p: p.read_bytes() for p in folder.rglob('*') if p.is_file()}
            evidence.append({'case': folder.name, 'contract': 'module-full-json-read-only'})
    evidence.extend(buffers(clients, directory))
    for client in clients:
        assert client.result('shutdown') is None
        response = client.request('textDocument/hover', {})
        assert response == {'jsonrpc': '2.0', 'id': client.serial, 'error': {
            'code': -32600, 'message': 'Server has shut down'}}
        client.send('exit')
        assert client.process.wait(timeout=5) == 0
        assert not client.pending, client.pending
        assert all(isinstance(item, EOFError) for item in list(client.messages.queue))
    evidence.append({'case': 'shutdown-rejection-and-exit', 'contract': 'full-response'})
    return evidence


def buffers(clients, directory):
    """Multiple failures refresh after open/change/save/close, using local overlays."""
    folder = directory / 'buffers'
    folder.mkdir()
    module = folder / 'tokens.ubnf'
    disk = "grammar T { @ubnf: v2 token A ::= 'a'; }"
    module.write_text(disk)
    uri = (folder / 'main.ubnf').as_uri()
    peer = (folder / 'peer.ubnf').as_uri()
    source = "grammar Main { @import a from 'tokens.ubnf' @ubnf: v2 @root Start ::= a.A; }"
    changed = disk.replace('token A', 'token B')
    failure = [diagnostic(source, 0, 1, MODULE_PREFIX + 'E-MODULE: undefined imported token: a.A')]
    for client in clients:
        client.fixture = 'module-read-only-overlay-lifecycle'
        for target in (uri, peer):
            opened(client, target, source, [])
        published(client, uri, [])
        opened(client, module.as_uri(), changed, [])
        for target in (uri, peer):
            published(client, target, failure)
        client.send('textDocument/didChange', {'textDocument': {'uri': module.as_uri(), 'version': 0},
                                              'contentChanges': [{'text': disk}]})
        published(client, module.as_uri(), [])
        for target in (uri, peer):
            published(client, target, [])
        client.send('textDocument/didChange', {'textDocument': {'uri': module.as_uri(), 'version': 0},
                                              'contentChanges': [{'text': changed}]})
        published(client, module.as_uri(), [])
        for target in (uri, peer):
            published(client, target, failure)
        # didSave text does not replace the open buffer or write to disk.
        client.send('textDocument/didSave', {'textDocument': {'uri': module.as_uri()}, 'text': disk})
        for target in (uri, peer):
            published(client, target, failure)
        client.send('textDocument/didClose', {'textDocument': {'uri': module.as_uri()}})
        for target in (uri, peer):
            published(client, target, [])
        for target in (uri, peer):
            client.send('textDocument/didClose', {'textDocument': {'uri': target}})
            assert client.result('textDocument/hover', {'textDocument': {'uri': target},
                                                       'position': {'line': 0, 'character': 0}}) is None
        # Closing the first importer re-diagnoses the second importer.
        # Consume the notification before checking the transport barrier.
        published(client, peer, [])
        assert not client.pending and client.messages.empty(), (client.process.args[0], client.pending, list(client.messages.queue))
    assert module.read_text() == disk
    return [{'case': 'multiple-documents-open-change-save-close', 'contract': 'full-module-json'}]


def package(folder, mode):
    module = "grammar T { @ubnf: v2 token A ::= 'a'; }"
    deps = {'team/peer': {'version': '1.0.0', 'source': 'local:must-not-read.json'}} if mode == 'dependencies' else {}
    artifact = {'schemaVersion': 1, 'id': 'team/wrong' if mode == 'identity' else 'team/test',
                'version': '1.0.0', 'entry': 'tokens.ubnf', 'files': {'tokens.ubnf': module}, 'dependencies': deps}
    raw = json.dumps(artifact).encode()
    digest = hashlib.sha256(raw).hexdigest()
    (folder / 'ubnf.json').write_text(json.dumps({'schemaVersion': 1, 'dependencies': {
        'team/test': {'version': '1.0.0', 'source': 'local:must-not-read.json'}}}))
    lock = {'schemaVersion': 1, 'roots': {} if mode == 'stale-lock' else {'team/test': '1.0.0'},
            'packages': {'team/test': {'id': 'team/test', 'version': '1.0.0', 'sha256': digest, 'dependencies': {}}}}
    if mode != 'missing-lock':
        (folder / 'ubnf.lock.json').write_text(json.dumps(lock))
    cache = folder / '.ubnf-cache/packages'
    cache.mkdir(parents=True)
    (cache / (digest + '.json')).write_bytes(raw + (b' ' if mode == 'hash' else b''))
