"""#994/#1001/#1005: 手書きの wire 入力と独立全 JSON oracle。実測結果を期待値にしない。"""
import json
import queue
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_jsonrpc(clients, directory, omit_params):
    fixture = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/jsonrpc.json').read_text())
    configuration = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/configuration.json').read_text())
    assert (len(fixture['invalidParams']), len(fixture['versions']), len(fixture['methods'])) == (12, 47, 47)
    started = time.monotonic()
    evidence = []
    hosts = list(zip(('java', 'rust'), clients))

    def check(client, actual, expected, serial):
        # Only JVM/Gson implementation details are excluded. All other fields,
        # code/message, result, id and key presence remain exact comparisons.
        if client.backend == 'java' and 'error' in expected and expected['error']['code'] in (-32603, -32700):
            assert 'error' in actual, (client.backend, client.fixture, expected, actual)
            actual = {**actual, 'error': dict(actual['error'])}
            detail = actual['error'].pop('data')
            assert isinstance(detail, str if expected['error']['code'] == -32603 else dict), (client.fixture, detail)
        oracle = {'jsonrpc': '2.0', 'id': serial, **expected}
        assert actual == oracle, (client.backend, client.fixture, oracle, actual)

    def response(client, method, params, expected):
        actual = client.request(method, params)
        check(client, actual, expected, client.serial)

    reader = fixture['reader']
    dto = fixture['dto']
    # These wire fragments and oracles are independent of the generated DTO table.
    reader['dtoInvalid'] += ['{"capabilities":' + caps + '}' for caps in dto['capabilitiesInvalid']]
    reader['dtoAccepted'] += ['{"capabilities":' + caps + '}' for caps in dto['capabilitiesAccepted']]
    assert reader['javaRevision'] == '384fcf3eb1c0ce3e207ac580cec5fcac4b478b34'

    def quiet(client):
        # The preceding barrier proves the reader has processed the bad frame.
        # A bounded interval then rejects late responses as well as queued ones.
        assert not client.pending, (client.fixture, client.pending)
        try:
            item = client.messages.get(timeout=reader['silenceSeconds'])
        except queue.Empty:
            assert client.process.poll() is None, client.failure('unexpected process exit')
            return
        raise AssertionError((client.backend, client.fixture, 'unexpected frame', item))

    def raw(client, frame, expected, response_id=None):
        client.serial += 1
        serial = client.serial
        client.send_raw(frame.replace('$ID', str(serial)))
        if expected is not None:
            response_id = serial if response_id is None else response_id
            check(client, client.receive(lambda m: m.get('id') == response_id), expected, response_id)

    def order_frame(order, method, case, profile):
        options = {'initializationOptions': {}}
        if profile != 'default':
            options['initializationOptions']['languageProfile'] = {'entry': 'UBNFFile', 'tsv': configuration['profiles'][profile]}
        params = case['raw'].replace('$OPTIONS', json.dumps(options)[1:-1])
        return order.replace('$METHOD', method).replace('$PARAMS', params)

    def raw_dto(client, method, params, expected):
        raw(client, '{"jsonrpc":"2.0","id":$ID,"method":' + json.dumps(method) + ',"params":' + params + '}', expected)

    # Isolated lifecycle sessions allow every raw key order and positional arity
    # to prove initialize retry (including Java reader failures and native success).
    for case in reader['orderParams']:
        for index, order in enumerate(reader['orders']):
            for _, original in hosts:
                outcome = case[original.backend][index]
                # Failed attempts share the still-uninitialized real server.
                # Only accepted initialization needs a fresh session. This keeps
                # JVM startup and all silent-input deadlines below one minute.
                # Keep the suite-wide response timeout. Lowering it here would
                # also lower it for every later shared-session request and
                # make loaded self-hosted runners fail on slow JVM start/GC.
                client = type(original)(original.command, native=original.backend == 'rust') if outcome == 'success' else original
                client.fixture = f"jsonrpc/reader/initialize/{case['id']}/{index}"
                try:
                    outcome = case[client.backend][index]
                    expected = configuration['expected']['default'][client.backend]
                    raw(client, order_frame(order, 'initialize', case, 'default'),
                        {'result': expected} if outcome == 'success' else None if outcome == 'silent' else reader['oracles'][outcome])
                    if outcome != 'success':
                        response(client, 'textDocument/hover', {}, reader['oracles']['early'])
                    else:
                        response(client, 'initialize', {'capabilities': {}}, reader['oracles']['already'])
                    if outcome == 'success':
                        response(client, '$/reader-barrier', {}, {'result': None})
                        assert not client.pending and client.messages.empty(), client.fixture
                        response(client, 'shutdown', {}, {'result': None})
                        client.send('exit')
                        assert client.process.wait(timeout=client.timeout) == 0
                finally:
                    if client is not original:
                        client.close()
            evidence.append({'case': f"reader-initialize-{case['id']}-{index}"})

    for _, client in hosts:
        quiet(client)

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
    for index, params in enumerate(reader['dtoInvalid']):
        for _, client in hosts:
            client.fixture = f'jsonrpc/reader/initialize/dto-{index}'
            raw_dto(client, 'initialize', params, reader['oracles']['parse'])
            response(client, 'textDocument/hover', {}, reader['oracles']['early'])
        evidence.append({'case': f'reader-initialize-dto-{index}'})
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
    def retained(client, suffix):
        # Check the complete old document and newly submitted text under the old
        # restricted VALIDATE gate, not merely liveness or cached diagnostics.
        uri = (directory / 'kept.ubnf').as_uri()
        hover(client, uri, kept)
        response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': uri}}, {'result': fixture['keptSymbols']})
        uri = (directory / ('reader-fresh-' + suffix + '.ubnf')).as_uri()
        fresh = fixture['freshDocument']
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 1, 'text': fresh['source']}})
        diagnostic(client, uri, [])
        hover(client, uri, fresh)
        client.send('textDocument/didClose', {'textDocument': {'uri': uri}})

    for index, params in enumerate(reader['dtoInvalid']):
        for _, client in hosts:
            client.fixture = f'jsonrpc/reader/configure/dto-{index}'
            raw_dto(client, 'unlaxer/configure', params, reader['oracles']['parse'])
            retained(client, f'dto-{index}')
        evidence.append({'case': f'reader-configure-dto-{index}'})
    for case in reader['orderParams']:
        for index, order in enumerate(reader['orders']):
            for _, client in hosts:
                client.fixture = f"jsonrpc/reader/configure/{case['id']}/{index}"
                outcome = case[client.backend][index]
                raw(client, order_frame(order, 'unlaxer/configure', case, 'restricted'),
                    {'result': configuration['expected']['restricted'][client.backend]} if outcome == 'success'
                    else None if outcome == 'silent' else reader['oracles'][outcome])
                retained(client, case['id'] + str(index))
            evidence.append({'case': f"reader-configure-{case['id']}-{index}"})
    for index, case in enumerate(reader['duplicates']):
        for _, client in hosts:
            client.fixture = f'jsonrpc/reader/duplicate-{index}'
            raw(client, case['raw'], reader['oracles'][case['oracle']] if 'oracle' in case else case['expected'], case.get('responseId'))
            retained(client, f'duplicate-{index}')
        evidence.append({'case': f'reader-duplicate-{index}'})
    # All silent configure inputs share one short deadline. A barrier/request
    # after each input and the final drain detect missing, spurious and late ids.
    for _, client in hosts:
        quiet(client)

    dto_started = time.monotonic()
    # Error responses must retain both documents and the restricted VALIDATE gate.
    for index, case in enumerate(dto['nestedInvalid']):
        for _, client in hosts:
            client.fixture = f'jsonrpc/dto/nested-{index}'
            raw_dto(client, case['method'], case['raw'], reader['oracles']['parse'])
            retained(client, f'nested-{index}')
        evidence.append({'case': f'dto-nested-{index}'})
    for method in dto['workDoneMethods']:
        for token in dto['workDoneInvalid']:
            for _, client in hosts:
                client.fixture = f'jsonrpc/dto/token/{method}/{token}'
                raw_dto(client, method, '{"workDoneToken":' + token + '}', reader['oracles']['parse'])
                retained(client, 'token-' + str(client.serial))
            evidence.append({'case': f'dto-token-{method}-{token}'})
    for _, client in hosts:
        uri = (directory / 'dto-position.ubnf').as_uri()
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 1, 'text': dto['positionSource']}})
        diagnostic(client, uri, [])

    def position_case(method, line, character, expected):
        for _, client in hosts:
            client.fixture = f'jsonrpc/dto/position/{method}/{line}/{character}'
            uri = (directory / 'dto-position.ubnf').as_uri()
            params = '{"textDocument":{"uri":' + json.dumps(uri) + '},"position":{"line":' + line + ',"character":' + character + '}}'
            raw_dto(client, method, params, expected)
        evidence.append({'case': f'dto-position-{method}-{line}-{character}'})

    for method, oracle in [('textDocument/hover', 'hover'), ('textDocument/completion', 'completion'), ('textDocument/definition', 'navigation')]:
        for line in dto['zeroIntegers'] + dto['oneIntegers']:
            position_case(method, line, '2', dto[oracle])
        for character in dto['twoIntegers']:
            position_case(method, '0', character, dto[oracle])
        for character in dto['zeroIntegers']:
            if method != 'textDocument/completion':
                position_case(method, '0', character, dto[oracle])
        for value in dto['invalidIntegers']:
            position_case(method, value, '2', reader['oracles']['parse'])
            position_case(method, '0', value, reader['oracles']['parse'])
        position_case(method, '0', '-1', dto['negativeCharacter'] if method != 'textDocument/definition' else dto['navigation'])
        if method != 'textDocument/completion':
            position_case(method, '-1', '0', dto[oracle])
    for method in ('textDocument/hover', 'textDocument/completion', 'textDocument/definition'):
        for _, client in hosts:
            client.fixture = 'jsonrpc/dto/null-position/' + method
            uri = (directory / 'dto-position.ubnf').as_uri()
            raw_dto(client, method, '{"textDocument":{"uri":' + json.dumps(uri) + '},"position":null}', reader['oracles']['internal'])
        evidence.append({'case': 'dto-null-position-' + method})
    # Scalar tokens, unlike invalid numeric tokens, reach the method. The opened
    # malformed document gives a small, independently authored hover result.
    for token in dto['workDoneAccepted']:
        for _, client in hosts:
            client.fixture = 'jsonrpc/dto/accepted-token/' + token
            uri = (directory / 'dto-position.ubnf').as_uri()
            raw_dto(client, 'textDocument/hover', '{"textDocument":{"uri":' + json.dumps(uri) + '},"position":{"line":0,"character":2},"workDoneToken":' + token + '}', dto['hover'])
        evidence.append({'case': 'dto-accepted-token-' + token})
    for _, client in hosts:
        client.fixture = 'jsonrpc/dto/silent-token'
        uri = (directory / 'dto-position.ubnf').as_uri()
        raw_dto(client, 'textDocument/hover', '{"textDocument":{"uri":' + json.dumps(uri) + '},"position":{"line":0,"character":2},"workDoneToken":{}}',
                None if client.backend == 'java' else dto['hover'])
        response(client, '$/reader-barrier', {}, {'result': None})
        quiet(client)
    evidence.append({'case': 'dto-silent-token'})

    for index, scalar in enumerate(dto['scalarTexts']):
        for _, client in hosts:
            client.fixture = 'jsonrpc/dto/scalar-text/' + scalar
            uri = (directory / 'dto-scalar.ubnf').as_uri()
            client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 1, 'text': fixture['oldDocument']['source']}})
            diagnostic(client, uri, [])
            client.send_raw('{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":' + json.dumps(uri) + ',"version":2},"contentChanges":[{"text":' + scalar + '}]}}')
            diagnostic(client, uri, [])
            response(client, 'textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': 0}}, dto['hover'])
            response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': uri}}, {'result': []})
            # A later ill-typed DTO must discard even a coercible first text.
            client.send_raw('{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":' + json.dumps(uri) + ',"version":3},"contentChanges":[{"text":' + json.dumps(fixture['oldDocument']['source']) + '},{"text":[]}]}}')
            response(client, 'textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': 0}}, dto['hover'])
            assert not client.pending and client.messages.empty(), client.fixture
        evidence.append({'case': 'dto-scalar-text-' + str(index)})
    evidence.append({'case': 'dto-timing', 'elapsedSeconds': round(time.monotonic() - dto_started, 3)})

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
    for index, params in enumerate(reader['dtoAccepted']):
        for _, client in hosts:
            client.fixture = f'jsonrpc/reader/accepted-dto-{index}'
            raw_dto(client, 'unlaxer/configure', params, {'result': configuration['expected']['default'][client.backend]})
        evidence.append({'case': f'reader-accepted-dto-{index}'})
    for method in reader['typedMethods']:
        for index, params in enumerate(reader['typedInvalid']):
            for _, client in hosts:
                client.fixture = f'jsonrpc/reader/typed/{method}/{index}'
                raw_dto(client, method, params, reader['oracles']['parse'])
                response(client, '$/reader-barrier', {}, {'result': None})
            evidence.append({'case': f'reader-typed-{method}-{index}'})
    for case in reader['typedPositional']:
        for _, client in hosts:
            client.fixture = 'jsonrpc/reader/typed-positional/' + case['method']
            raw_dto(client, case['method'], '[{"textDocument":{"uri":"file:///none"}}]', case['expected'])
        evidence.append({'case': 'reader-typed-positional-' + case['method']})
    for index, params in enumerate(reader['typedNested']):
        for _, client in hosts:
            client.fixture = f'jsonrpc/reader/nested-{index}'
            raw_dto(client, 'textDocument/hover', params, reader['oracles']['parse'])
        evidence.append({'case': f'reader-nested-{index}'})
    for method in reader['nullIdMethods']:
        for _, client in hosts:
            client.fixture = 'jsonrpc/reader/null-id/' + method
            client.send_raw('{"jsonrpc":"2.0","id":null,"method":' + json.dumps(method) + ',"params":{}}')
            response(client, '$/reader-barrier', {}, {'result': None})
        evidence.append({'case': 'reader-null-id-' + method})
    for _, client in hosts:
        client.fixture = 'jsonrpc/reader/overflow-notification'
        uri = (directory / 'overflow.ubnf').as_uri()
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'version': 1, 'text': fixture['oldDocument']['source']}})
        diagnostic(client, uri, [])
        client.send_raw(reader['overflowNotification'].replace('$URI', json.dumps(uri)).replace('$SOURCE', json.dumps(kept['source'])))
        response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': uri}}, {'result': fixture['oldDocument']['symbols']})

        def change(changes, source):
            client.send_raw('{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":' + json.dumps(uri)
                            + ',"version":2},"contentChanges":' + changes.replace('$SOURCE', json.dumps(source)) + '}}')
        # Any ill-typed change DTO drops the whole notification: no diagnostics
        # (checked by the barrier and the final quiet) and the old document stays.
        for changes in reader['droppedChanges']:
            change(changes, kept['source'])
            response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': uri}}, {'result': fixture['oldDocument']['symbols']})
            assert not client.pending, (client.fixture, changes, client.pending)
        # Gson-coercible and null fields are accepted and apply the first text.
        for changes in reader['acceptedChanges']:
            change(changes, kept['source'])
            diagnostic(client, uri, kept['diagnostics'])
            response(client, 'textDocument/documentSymbol', {'textDocument': {'uri': uri}}, {'result': fixture['keptSymbols']})
            change('[{"text":$SOURCE}]', fixture['oldDocument']['source'])
            diagnostic(client, uri, [])
        quiet(client)
    evidence.append({'case': 'reader-overflow-notification'})
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
    evidence.append({'case': 'shutdown-exit', 'elapsedSeconds': round(time.monotonic() - started, 3)})
    return evidence
