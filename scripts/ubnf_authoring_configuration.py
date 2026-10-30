"""#895: independent full JSON oracle for the actual Java/native stdio sessions."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_configuration(clients, directory):
    fixture = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/configuration.json').read_text())
    assert len(fixture['invalid']) == 40 and len(fixture['ignoredOptions']) == 10
    evidence = []
    hosts = list(zip(('java', 'rust'), clients))
    profiles = fixture['profiles']

    def options(name):
        return {'initializationOptions': {'languageProfile': {'entry': 'UBNFFile', 'tsv': profiles[name]}}}

    def response(client, method, params, expected, label):
        actual = client.request(method, params)
        # IDs are independently assigned by Client; no response fields are discarded.
        oracle = {'jsonrpc': '2.0', 'id': client.serial, **expected}
        assert actual == oracle, (label, 'expected', oracle, 'actual', actual)

    def configuration(client, host, name, method='unlaxer/configure', params=None):
        response(client, method, options(name) if params is None else params,
                 {'result': fixture['expected'][name][host]}, (host, name, method))

    uri = (directory / 'diagnostics.ubnf').as_uri()
    peer = (directory / 'peer.ubnf').as_uri()
    document = fixture['document']
    for host, client in hosts:
        for method in ('unlaxer/configure', 'shutdown', 'textDocument/hover'):
            response(client, method, {}, {'error': {'code': -32002, 'message': 'Server not initialized'}}, (host, 'early', method))
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 1, 'text': document['source']}})
        client.send('initialize', options('local'))
        client.send('unlaxer/configure', options('local'))
    evidence.append({'case': 'pre-initialize-requests-and-notifications'})
    for case in fixture['invalid']:
        for host, client in hosts:
            response(client, 'initialize', {'initializationOptions': case['options']}, {'error': case['error']}, (host, case['id'], 'retry'))
        evidence.append({'case': case['id'], 'contract': 'initialize-and-configure-full-error-json'})
    for host, client in hosts:
        initial = options('restricted')
        initial['initializationOptions']['unknown'] = {'source': 'https://example.invalid/not-fetched'}
        configuration(client, host, 'restricted', 'initialize', initial)
        client.send('initialized')
        client.send('initialized')
        response(client, 'initialize', options('local'), {'error': {'code': -32600, 'message': 'Server already initialized'}}, (host, 'duplicate'))
        assert client.result('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': 0}}) is None
        for method in ('textDocument/didOpen', 'initialized', 'workspace/didChangeConfiguration'):
            response(client, method, {}, {'error': {'code': -32600, 'message': 'Notification method requires a notification'}}, (host, 'notification-request', method))
        client.send('unlaxer/configure', options('local'))
        client.send('shutdown')
        for target in (uri, peer):
            client.send('textDocument/didOpen', {'textDocument': {'uri': target, 'version': 5, 'text': document['source']}})
            assert client.diagnostics(target) == [], (host, 'restricted diagnostics')
    # Rejected configure must retain the disabled VALIDATE gate and both buffers.
    for case in fixture['invalid']:
        for host, client in hosts:
            response(client, 'unlaxer/configure', {'initializationOptions': case['options']}, {'error': case['error']}, (host, case['id'], 'configure'))
            client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 5}, 'contentChanges': [{'text': document['source']}]})
            assert client.diagnostics(uri) == [], (host, case['id'], 'rollback')
    for i, ignored in enumerate(fixture['ignoredOptions']):
        for host, client in hosts:
            configuration(client, host, 'default', params={'initializationOptions': ignored})
        evidence.append({'case': 'ignored-options-' + str(i)})
    for name in ('restricted', 'mixed', 'local'):
        for host, client in hosts:
            configuration(client, host, name)
            # configure does not publish; the barrier checks no latent notifications.
            assert client.result('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': len(document['source'])}}) == document['hover']
            assert not client.pending and client.messages.empty(), (host, name, 'configure unexpectedly published')
            for target in (uri, peer):
                for version in (5, 1):
                    client.send('textDocument/didChange', {'textDocument': {'uri': target, 'version': version}, 'contentChanges': [{'text': document['source']}]})
                    assert client.diagnostics(target) == ([] if name == 'restricted' else document['diagnostics']), (host, name, version)
        evidence.append({'case': 'profile-' + name, 'contract': 'full-capabilities-and-diagnostics'})
    # Omitted options replace the profile rather than merge it. Buffers survive.
    for host, client in hosts:
        configuration(client, host, 'default', params={'capabilities': {'textDocument': {'semanticTokens': {'multilineTokenSupport': True, 'overlappingTokenSupport': True}}}})
        for target in (uri, peer):
            client.send('textDocument/didChange', {'textDocument': {'uri': target, 'version': 0}, 'contentChanges': [{'text': document['source'], 'range': {'start': {'line': 0, 'character': 0}, 'end': {'line': 0, 'character': 0}}}, {'text': 'ignored second change'}]})
            assert client.diagnostics(target) == document['diagnostics']
        client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': -1}, 'contentChanges': []})
        assert client.result('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': len(document['source'])}}) == document['hover']
        for target in (uri, peer):
            client.send('textDocument/didClose', {'textDocument': {'uri': target}})
            assert client.result('textDocument/hover', {'textDocument': {'uri': target}, 'position': {'line': 0, 'character': 0}}) is None
    evidence.append({'case': 'multiple-documents-range-full-change-same-decreasing-version'})
    evidence.extend(import_queries(hosts, directory, configuration))
    evidence.extend(package_snapshots(hosts, directory, configuration))
    for host, client in hosts:
        assert client.result('shutdown') is None
        client.send('unlaxer/configure', options('local'))
        for method in ('unlaxer/configure', 'initialize', 'shutdown', 'textDocument/hover'):
            response(client, method, options('local'), {'error': {'code': -32600, 'message': 'Server has shut down'}}, (host, 'stopped', method))
        client.send('exit')
        assert client.process.wait(timeout=5) == 0
        assert not client.pending, (host, 'unexpected wire output at exit')
    evidence.append({'case': 'shutdown-rejection-and-exit'})
    # Actual transports stop at exit, so no post-exit request can reach either server.
    for host, previous in hosts:
        for mode in ('early-exit', 'profile-initialize-eof', 'unknown-initialize'):
            client = type(previous)(previous.process.args, native=host == 'rust')
            try:
                if mode == 'early-exit':
                    client.send('exit')
                elif mode == 'unknown-initialize':
                    configuration(client, host, 'default', 'initialize', {'initializationOptions': {'profile': 'missing'}})
                    client.process.stdin.close()
                else:
                    initial = options('mixed')
                    initial['capabilities'] = {'textDocument': {'semanticTokens': {'multilineTokenSupport': True, 'overlappingTokenSupport': True}}}
                    configuration(client, host, 'mixed', 'initialize', initial)
                    client.process.stdin.close()
                assert client.process.wait(timeout=5) == 1, (host, mode)
            finally:
                client.close()
    evidence.extend([{'case': 'early-exit'}, {'case': 'profile-initialize-eof'}])
    return evidence


def import_queries(hosts, directory, configuration):
    """Queries are extension overrides; profile advertisement does not gate them."""
    module = directory / 'module.ubnf'
    module.write_text("grammar M { @ubnf: v2 token A ::= 'a'; }")
    main = directory / 'main.ubnf'
    source = "grammar Main { @import m from 'module.ubnf' @ubnf: v2 @root Start ::= m.A; }"
    uri = main.as_uri()
    start = source.index('m.A')
    for host, client in hosts:
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 8, 'text': source}})
        assert client.diagnostics(uri) == []
        for name in ('local', 'restricted', 'mixed'):
            configuration(client, host, name)
            for token, version in (('A', 8), ('B', 8), ('C', 2)):
                text = source.replace('m.A', 'm.' + token)
                mod = "grammar M { @ubnf: v2 token " + token + " ::= 'a'; }"
                client.send('textDocument/didOpen', {'textDocument': {'uri': module.as_uri(), 'version': version, 'text': mod}})
                assert client.diagnostics(module.as_uri()) == []
                client.diagnostics(uri)  # Importer may briefly reference the previous token.
                client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': version}, 'contentChanges': [{'text': text}]})
                assert client.diagnostics(uri) == []
                p = {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': start + 3}}
                completion = [{'label': 'm.' + token, 'kind': 21, 'detail': 'token · ' + module.as_uri(), 'documentation': {'kind': 'markdown', 'value': "```ubnf\ntoken " + token + " ::= 'a';\n```"}, 'textEdit': {'range': {'start': {'line': 0, 'character': start}, 'end': {'line': 0, 'character': start + 3}}, 'newText': 'm.' + token}}]
                assert client.result('textDocument/completion', p) == completion, (host, name, 'completion')
                p['position']['character'] -= 1
                hover = {'contents': {'kind': 'markdown', 'value': '**token ' + token + "**\n\n```ubnf\ntoken " + token + " ::= 'a';\n```\n\n" + module.as_uri() + '\n\n1 reference(s) in this document'}}
                assert client.result('textDocument/hover', p) == hover, (host, name, 'hover')
        client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
        client.send('textDocument/didClose', {'textDocument': {'uri': module.as_uri()}})
    return [{'case': 'reconfigure-import-buffers-hover-completion-full-json'}]


def package_snapshots(hosts, directory, configuration):
    folder = directory / 'package'
    folder.mkdir()
    source = "grammar M { @ubnf: v2 token A ::= 'a'; }"
    raw = json.dumps({'schemaVersion': 1, 'id': 'team/config', 'version': '1.0.0', 'entry': 'module.ubnf', 'files': {'module.ubnf': source}, 'dependencies': {}}).encode()
    digest = hashlib.sha256(raw).hexdigest()
    (folder / 'ubnf.json').write_text(json.dumps({'schemaVersion': 1, 'dependencies': {'team/config': {'version': '1.0.0', 'source': 'local:must-not-fetch.json'}}}))
    (folder / 'ubnf.lock.json').write_text(json.dumps({'schemaVersion': 1, 'roots': {'team/config': '1.0.0'}, 'packages': {'team/config': {'id': 'team/config', 'version': '1.0.0', 'sha256': digest, 'dependencies': {}}}}))
    cache = folder / '.ubnf-cache/packages'
    cache.mkdir(parents=True)
    artifact = cache / (digest + '.json')
    artifact.write_bytes(raw)
    uri = (folder / 'main.ubnf').as_uri()
    package = 'ubnf-package:/' + digest + '/module.ubnf'
    text = "grammar Main { @import m from 'pkg:team/config' @ubnf: v2 @root Start ::= m.A; }"
    before = {p: p.read_bytes() for p in folder.rglob('*') if p.is_file()}
    for host, client in hosts:
        configuration(client, host, 'local')
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 1, 'text': text}})
        assert client.diagnostics(uri) == []
        assert client.result('ubnf/packageSource', {'uri': package}) is None
        client.result('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': text.index('m.A') + 2}})
        assert client.result('ubnf/packageSource', {'uri': package}) == source
        configuration(client, host, 'restricted')
        client.send('textDocument/didSave', {'textDocument': {'uri': (folder / 'unopened.ubnf').as_uri()}})
        assert client.diagnostics(uri) == []
        assert client.result('ubnf/packageSource', {'uri': package}) is None, (host, 'disabled validation populated package index')
        configuration(client, host, 'local')
        client.result('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': text.index('m.A') + 2}})
        assert client.result('ubnf/packageSource', {'uri': package}) == source
        artifact.write_bytes(raw + b' ')
        configuration(client, host, 'restricted')
        assert client.result('ubnf/packageSource', {'uri': package}) == source, (host, 'configure erased verified snapshot')
        configuration(client, host, 'local')
        # A new query must revalidate imports, even with identical document/version.
        client.result('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': text.index('m.A') + 2}})
        assert client.result('ubnf/packageSource', {'uri': package}) is None
        artifact.write_bytes(raw)
        client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
    assert before == {p: p.read_bytes() for p in folder.rglob('*') if p.is_file()}
    return [{'case': 'configuration-package-snapshot-lifetime-and-revalidation'}]
