"""#994: 手書きの wire 入力と独立全 JSON oracle。実測結果を期待値にしない。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_jsonrpc(clients, directory, omit_params):
    fixture = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/jsonrpc.json').read_text())
    configuration = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/configuration.json').read_text())
    assert (len(fixture['invalidParams']), len(fixture['versions']), len(fixture['methods'])) == (12, 47, 47)
    evidence = []
    hosts = list(zip(('java', 'rust'), clients))

    def response(client, method, params, expected):
        actual = client.request(method, params)
        # Only JVM/Gson implementation details are excluded. All other fields,
        # code/message, result, id and key presence remain exact comparisons.
        if client.backend == 'java' and 'error' in expected and expected['error']['code'] in (-32603, -32700):
            assert 'error' in actual, (client.backend, client.fixture, expected, actual)
            actual = {**actual, 'error': dict(actual['error'])}
            detail = actual['error'].pop('data')
            assert isinstance(detail, str if expected['error']['code'] == -32603 else dict), (client.fixture, detail)
        oracle = {'jsonrpc': '2.0', 'id': client.serial, **expected}
        assert actual == oracle, (client.backend, client.fixture, method, oracle, actual)

    def hover(client, uri, document):
        response(client, 'textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': len(document['source'])}}, {'result': document['hover']})
        assert not client.pending and client.messages.empty(), (client.fixture, 'unexpected notification')

    def diagnostic(client, uri, expected):
        actual = client.diagnostics_params(uri)
        oracle = {'uri': uri, 'version': 0, 'diagnostics': expected}
        assert actual == oracle, (client.backend, client.fixture, oracle, actual)

    def configure(client, profile, method='unlaxer/configure'):
        params = {'capabilities': {}}
        if profile != 'default':
            params['initializationOptions'] = {'languageProfile': {'entry': 'UBNFFile', 'tsv': configuration['profiles'][profile]}}
        response(client, method, [params] if method == 'initialize' else params, {'result': configuration['expected'][profile][client.backend]})

    early = {'error': {'code': -32002, 'message': 'Server not initialized'}}
    for case in fixture['invalidParams']:
        for _, client in hosts:
            client.fixture = 'jsonrpc/initialize/' + case['id']
            params = omit_params if case.get('omitParams') else case['params']
            response(client, 'initialize', params, case['expected'])
            # Failed initialization must not admit any newly sent request.
            response(client, 'unlaxer/configure', {}, early)
            response(client, 'textDocument/hover', {}, early)
            client.send('textDocument/didOpen', {'textDocument': {'uri': (directory / ('early-' + case['id'] + '.ubnf')).as_uri(), 'version': 1, 'text': fixture['freshDocument']['source']}})
        evidence.append({'case': 'initialize-' + case['id']})
    for _, client in hosts:
        client.fixture = 'jsonrpc/retry-initialize'
        configure(client, 'restricted', 'initialize')
        client.send('initialized')
        for case in fixture['invalidParams']:
            response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': (directory / ('early-' + case['id'] + '.ubnf')).as_uri()}}, {'result': []})
        assert not client.pending and client.messages.empty()
    evidence.append({'case': 'retry-initialize'})

    kept = configuration['document']
    for _, client in hosts:
        client.send('textDocument/didOpen', {'textDocument': {'uri': (directory / 'kept.ubnf').as_uri(), 'version': 17, 'text': kept['source']}})
        diagnostic(client, (directory / 'kept.ubnf').as_uri(), [])
    for case in fixture['invalidParams']:
        for _, client in hosts:
            client.fixture = 'jsonrpc/configure/' + case['id']
            params = omit_params if case.get('omitParams') else case['params']
            response(client, 'unlaxer/configure', params, case['expected'])
            hover(client, (directory / 'kept.ubnf').as_uri(), kept)
            response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': (directory / 'kept.ubnf').as_uri()}}, {'result': fixture['keptSymbols']})
            # New input after each failure still uses the old VALIDATE gate.
            fresh = fixture['freshDocument']
            uri = (directory / ('fresh-' + case['id'] + '.ubnf')).as_uri()
            client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 900, 'text': fresh['source']}})
            diagnostic(client, uri, [])
            hover(client, uri, fresh)
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
        evidence.append({'case': 'configure-' + case['id']})
    # A later successful configure restores validation without replacing buffers.
    for _, client in hosts:
        client.fixture = 'jsonrpc/recover-configure'
        configure(client, 'default')
        uri = (directory / 'kept.ubnf').as_uri()
        client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': -1}, 'contentChanges': [{'text': kept['source']}]})
        diagnostic(client, uri, kept['diagnostics'])
        hover(client, uri, kept)
        fresh = fixture['freshDocument']
        uri = (directory / 'fresh-enabled.ubnf').as_uri()
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 900, 'text': fresh['source']}})
        diagnostic(client, uri, fresh['diagnostics'])
        client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
    evidence.append({'case': 'recover-configure'})

    for kind in ('open', 'change'):
        for case in fixture['versions']:
            for _, client in hosts:
                client.fixture = 'jsonrpc/' + kind + '/' + case['id']
                uri = (directory / (kind + '-' + case['id'] + '.ubnf')).as_uri()
                # Dropped changes must leave an existing, observably different buffer.
                old = fixture['oldDocument']['source']
                if kind == 'change':
                    client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 1, 'text': old}})
                    diagnostic(client, uri, [])
                doc = {'uri': uri}
                if not case.get('omitVersion'):
                    doc['version'] = case['version']
                if kind == 'open':
                    params = {'textDocument': {**doc, 'text': kept['source']}}
                else:
                    params = {'textDocument': doc, 'contentChanges': [{'text': kept['source']}]}
                client.send('textDocument/did' + kind.capitalize(), params)
                if case['accepted']:
                    diagnostic(client, uri, kept['diagnostics'])
                    hover(client, uri, kept)
                else:
                    response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': uri}}, {'result': [] if kind == 'open' else fixture['oldDocument']['symbols']})
                    assert not client.pending and client.messages.empty(), (client.fixture, 'dropped notification published')
                client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            evidence.append({'case': kind + '-' + case['id']})
    for case in fixture['methods']:
        for _, client in hosts:
            client.fixture = 'jsonrpc/method/' + case['method']
            response(client, case['method'], {}, case['expected'])
        evidence.append({'case': case['method']})
    for _, client in hosts:
        client.fixture = 'jsonrpc/shutdown-exit'
        response(client, 'shutdown', {}, {'result': None})
        response(client, '$/foo', {}, {'error': {'code': -32600, 'message': 'Server has shut down'}})
        client.send('exit')
        assert client.process.wait(timeout=5) == 0
        assert not client.pending
    evidence.append({'case': 'shutdown-exit'})
    return evidence
