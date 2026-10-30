"""#914/#926: raw package JSON through the reference/native CLI and stdio hosts."""
import argparse
from contextlib import nullcontext
import copy
import time
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
COUNT = 481


def fixtures():
    cases = json.loads((ROOT / 'spec-corpus/package-json-acceptance.json').read_text())
    assert len(cases) == COUNT, 'package JSON fixtures must not silently disappear'
    assert len({c['id'] for c in cases}) == COUNT
    added = [case for case in cases if case.get('surrogate939')]
    assert len(added) == 81 and sum(case.get('generate', False) for case in added) == 13
    assert {case.get('loadError') is None for case in added if case.get('generate')} == {True, False}
    return cases


def encode(value):
    # ASCII JSON escapes intentionally preserve unpaired UTF-16 code units.
    return json.dumps(value, ensure_ascii=True, separators=(',', ':')).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def lock_bytes(blobs, roots):
    packages = {}
    for name, data in blobs.items():
        document = json.loads(data.decode('utf-8-sig'))
        packages[name] = {'id': name, 'version': document['version'], 'sha256': digest(data),
                          'dependencies': {k: v['version'] for k, v in document['dependencies'].items()}}
    return (json.dumps({'schemaVersion': 1, 'roots': roots, 'packages': packages},
                      sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\n').encode()


def setup(folder, case, origin="https://localhost:1"):
    case = copy.deepcopy(case)
    for edit in case.get("edits", []):
        if isinstance(edit["value"], str):
            edit["value"] = edit["value"].replace("$ORIGIN", origin)
    folder.mkdir(parents=True)
    standard = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    transitive = case.get('transitive', False)
    package = case.get('package', 'team/parent' if transitive else 'std/layout')
    parent = {'schemaVersion': 1, 'id': package, 'version': '1.0.0', 'entry': 'parent.ubnf',
              'files': {'parent.ubnf': "grammar Parent { @ubnf: v2 token P ::= 'p'; }"},
              'dependencies': {'std/layout': {'version': '1.0.0', 'source': 'local:../artifact.json'}}}
    primary = encode(parent) if transitive else standard
    manifest = {'schemaVersion': 1, 'dependencies': {package: {'version': '1.0.0',
                'source': 'local:nested/parent.json' if transitive else 'local:artifact.json'}}}
    blobs = {'std/layout': standard, **({package: primary} if transitive else {})}
    additional = case.get('additionalArtifacts', {})
    if additional:
        blobs = {}
    for document in additional.values():
        blobs[document['id']] = encode(document)
    manifest = case.get('manifest', manifest)
    roots = {name: dependency['version'] for name, dependency in manifest['dependencies'].items()}
    lock = lock_bytes(blobs, roots)
    subject = case['subject']
    document = {'manifest': manifest, 'lock': json.loads(lock), 'artifact': json.loads(primary)}[subject]
    for edit in case.get('edits', []):
        field = document
        for key in edit['path'][:-1]:
            field = field.setdefault(key, {})
        key = edit['path'][-1]
        if case.get('rename'):
            field[edit['value']] = field.pop(key)
        else:
            field[key] = edit['value']
    if 'schema' in case:
        document['schemaVersion'] = case['schema']
    # Raw member lists preserve duplicates and escape spellings that a dict would discard.
    for index, edit in enumerate(case.get('rawEdits', [])):
        field = document
        for key in edit['path'][:-1]:
            field = field[key]
        field[edit['path'][-1]] = f'__raw_package_member_{index}__'
    raw = bytes.fromhex(case['bytesHex']) if 'bytesHex' in case else bytes.fromhex(case.get('prefixHex', '')) + encode(document) + case.get('suffix', '').encode()
    for index, edit in enumerate(case.get('rawEdits', [])):
        marker = encode(f'__raw_package_member_{index}__')
        assert raw.count(marker) == 1
        if 'nesting' in edit:
            nesting = edit['nesting']
            opening, closing = (b'[', b']') if nesting['shape'] == 'array' else (b'{"x":', b'}')
            leaf = nesting.get('leaf', '0').encode('ascii')
            budget = nesting.get('fillBytes', 0) - len(raw) + len(marker) - len(leaf)
            depth = nesting.get('depth', budget // (len(opening) + len(closing)))
            replacement = opening * depth + leaf + closing * depth
            if 'fillBytes' in nesting:
                replacement += b' ' * (budget - depth * (len(opening) + len(closing)))
        else:
            replacement = edit['json'].encode('ascii')
        raw = raw.replace(marker, replacement + edit.get('after', '').encode('ascii'))
    if case.get('interiorBom'):
        raw = raw.replace(b',', b',\xef\xbb\xbf', 1)
    if case.get('uppercase'):
        raw = raw.replace(b'\\ud800', b'\\uD800')
    if case.get('escapedKeys'):
        raw = raw.replace(b'"source"', b'"so\\u0075rce"').replace(b'local:', b'lo\\u0063al:')
    if case.get('duplicateSource'):
        raw = raw.replace(b'"source":', b'"source":"local:art\\ud800ifact.json","source":')
    if subject == 'manifest':
        manifest_bytes = raw
    else:
        manifest_bytes = encode(manifest)
    if subject == 'artifact':
        blobs[package] = raw
        primary = raw
        pinned = json.loads(lock)
        pinned['packages'][package]['sha256'] = digest(standard if case.get('hashMismatch') else raw)
        lock = encode(pinned)
    elif subject == 'lock':
        lock = raw
    (folder / 'ubnf.json').write_bytes(manifest_bytes)
    (folder / 'artifact.json').write_bytes(standard if transitive else primary)
    (folder / 'art😀ifact.json').write_bytes(standard if transitive else primary)
    (folder / 'nested').mkdir()
    (folder / 'nested/parent.json').write_bytes(primary)
    for name, document in additional.items():
        path = folder / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(encode(document))
    # A valid pair in a removed component is allowed even without that directory.
    cache = folder / '.ubnf-cache/packages'
    cache.mkdir(parents=True)
    for name, data in blobs.items():
        sha = digest(standard if name == package and case.get('hashMismatch') else data)
        (cache / (sha + '.json')).write_bytes(data + (b' ' if name in case.get('corruptCache', []) else b''))
    (folder / 'ubnf.lock.json').write_bytes(lock)
    declaration = f"@import layout from 'pkg:{package}'"
    reference = case.get("rootReference", "'a'")
    source = f"grammar Main {{ // 😀\r\n {declaration}\r\n @ubnf: v2\r\n @root @mapping(Value) Start ::= {reference};\r\n}}"
    grammar = folder / 'main space 😀.ubnf'
    grammar.write_text(source, newline='')
    return grammar, source, declaration, blobs, roots


def snapshot(folder):
    paths = [folder / 'ubnf.lock.json', folder / '.ubnf-cache']
    if paths[1].exists():
        paths.extend(paths[1].rglob('*'))
    return {str(p.relative_to(folder)) + ('/' if p.is_dir() else ''):
            'directory' if p.is_dir() else p.read_bytes().hex() for p in paths if p.exists()}


def reset(folder, before):
    (folder / 'ubnf.lock.json').unlink(missing_ok=True)
    shutil.rmtree(folder / '.ubnf-cache', ignore_errors=True)
    for name, data in before.items():
        path = folder / name
        if data == 'directory':
            path.mkdir(parents=True, exist_ok=True)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(bytes.fromhex(data))


def expected(case, host, operation):
    key = operation + 'Error'
    return case.get('rust' + key[0].upper() + key[1:], case.get(key)) if host == 'rust' else case.get(key)


def run(command, options, native):
    env = dict(os.environ)
    if native:
        env.update(PATH='', JAVA_HOME='/nonexistent-java')
    result = subprocess.run(command + options, capture_output=True, text=True, timeout=30, env=env)
    record = {'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr,
              'lastError': (result.stderr.strip().splitlines() or [''])[-1]}
    if '--output' in options:
        output = Path(options[options.index('--output') + 1])
        record['generatedHashes'] = {str(p.relative_to(output)): digest(p.read_bytes())
                                     for p in output.rglob('*') if p.is_file()}
    return record


def check_cli(directory, commands, evidence, cases=None):
    from ubnf_package_credentials import local_tls
    cases = fixtures() if cases is None else cases
    tls = directory / 'tls'
    tls.mkdir()
    standard = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    context = local_tls(tls, standard) if any(c.get('tls') for c in cases) else nullcontext(('https://localhost:1', None, []))
    previous = os.environ.get('UBNF_CREDENTIALS_FILE')
    try:
        with context as connection:
            _check_cli(directory, commands, evidence, cases, connection)
    finally:
        if previous is None:
            os.environ.pop('UBNF_CREDENTIALS_FILE', None)
        else:
            os.environ['UBNF_CREDENTIALS_FILE'] = previous


def _check_cli(directory, commands, evidence, cases, connection):
    origin, ca, received = connection
    for case in cases:
        print('package JSON CLI:', case['id'], flush=True)
        folder = directory / case['id']
        grammar, _, _, blobs, roots = setup(folder, case, origin)
        credentials = folder / 'credentials.json'
        credentials.write_bytes(case.get('credentials', '{"$ORIGIN":{"caCertificate":"$CA"}}')
                                .replace('$ORIGIN', origin).replace('$CA', str(ca))
                                .replace('$MISSING', str(folder / 'missing.pem')).encode())
        os.environ['UBNF_CREDENTIALS_FILE'] = str(credentials)
        before = snapshot(folder)
        record = {'case': case['id'], 'gap': case.get('gap', False), 'runs': []}
        started = time.monotonic()
        evidence.append(record)
        generated = []
        for host, command in (commands if case.get('generate', True) else []):
            for flags in ([False, True] if host == 'java' else [False]):
                output = folder / (host + ('-flags' if flags else '-rust'))
                args = ['--grammar', str(grammar), '--output', str(output), '--generators', 'Parser'] if flags else [
                    'generate', '--target', 'rust', '--grammar', str(grammar), '--output', str(output)]
                requests_before = len(received)
                actual = run(command, args, host == 'rust')
                assert len(received) == requests_before, (case['id'], 'fixed cache must not fetch')
                record['runs'].append(dict(host=host, operation='flags' if flags else 'generate', **actual))
                error = expected(case, host, 'load')
                prefix = case.get('rustLoadPrefix', case.get('loadPrefix', 'E-PACKAGE: ')) if host == 'rust' else case.get('loadPrefix', 'E-PACKAGE: ')
                exit_code = case.get('rustLoadExit', case.get('loadExit', 3)) if host == 'rust' else case.get('loadExit', 3)
                if flags:
                    error = case.get('flagsError', error)
                    prefix = case.get('flagsPrefix', 'Generation failed: ' + prefix)
                    exit_code = case.get('flagsExit', 4)
                message = (prefix + error).replace('$OUTPUT', str(output)) if error else ''
                assert actual['exit'] == (exit_code if error else 0), (case['id'], host, actual)
                assert actual['lastError'] == message, (case['id'], host, actual, message)
                if error:
                    if host == 'java' and case.get('partialOutput'):
                        assert output.exists(), (case['id'], host, 'Java partial output boundary changed')
                    else:
                        assert not output.exists(), (case['id'], host, 'partial output')
                elif not flags:
                    files = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob('*') if p.is_file()}
                    assert len(files) == 5, (case['id'], host, files.keys())
                    generated.append(files)
                else:
                    assert any(output.rglob('*.java'))
                assert snapshot(folder) == before, (case['id'], host, 'read-only snapshot changed')
        if len(generated) == 2:
            assert generated[0] == generated[1], (case['id'], 'generated bytes differ')
        if case['subject'] == 'lock' and not case.get('resolveLock'):
            continue
        for existing in [False, True]:
            initial = before if existing else {}
            results = []
            for host, command in commands:
                reset(folder, initial)
                requests_before = len(received)
                actual = run(command, ['deps', 'resolve', '--manifest', str(folder / 'ubnf.json')], host == 'rust')
                if case.get('tls'):
                    requests = received[requests_before:]
                    assert len(requests) == case['requests'], (case['id'], host, 'HTTP request count', requests)
                    if 'requestPath' in case:
                        assert requests[0]['path'] == case['requestPath'], (case['id'], host, requests)
                after = snapshot(folder)
                # Keep multi-MiB generated fixtures out of the evidence JSON; assertions
                # below still compare every raw byte, including failed snapshots.
                recorded = {name: {'bytes': len(data) // 2, 'sha256': digest(bytes.fromhex(data))}
                            if data != 'directory' else data for name, data in after.items()} if any(
                                'fillBytes' in edit.get('nesting', {}) for edit in case.get('rawEdits', [])) else after
                record['runs'].append(dict(host=host, operation='resolve', existingSnapshot=existing, snapshot=recorded, **actual))
                error = expected(case, host, 'resolve')
                assert actual['exit'] == (3 if error else 0), (case['id'], host, actual)
                assert actual['lastError'] == ('E-PACKAGE: ' + error if error else ''), (case['id'], host, actual)
                if error:
                    assert after == initial, (case['id'], host, 'failed resolve published files')
                else:
                    oracle = dict(initial)
                    oracle.update({'ubnf.lock.json': lock_bytes(blobs, roots).hex(),
                                   '.ubnf-cache/': 'directory', '.ubnf-cache/packages/': 'directory'})
                    oracle.update({'.ubnf-cache/packages/' + digest(data) + '.json': data.hex() for data in blobs.values()})
                    assert after == oracle, (case['id'], host, 'raw lock/cache byte oracle differs')
                results.append(after)
            if len(results) == 2 and expected(case, 'java', 'resolve') == expected(case, 'rust', 'resolve'):
                assert results[0] == results[1], (case['id'], 'Java/Rust lock/cache differ')
        record['seconds'] = round(time.monotonic() - started, 3)


def run_lsp(clients, directory):
    # Fixed cache loads must not even open a TCP/TLS connection, including the
    # Unicode URL controls. Reuse the transport listener's private connection count.
    from ubnf_package_transport import transport_server
    tls = directory / 'tls'
    tls.mkdir()
    artifact = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    with transport_server(tls, artifact) as (port, _, _, _, _, connections):
        result = _run_lsp(clients, directory, 'https://localhost:' + str(port), connections)
    assert not connections, 'fixed cache stdio connected'
    return result


def _run_lsp(clients, directory, origin, connections):
    from importlib import import_module
    position = import_module('check-ubnf-authoring-lsp').position
    evidence = []
    for case in fixtures():
        print('package JSON stdio:', case['id'], flush=True)
        folder = directory / case['id']
        grammar, source, declaration, _, _ = setup(folder, case, origin)
        before = snapshot(folder)
        if 'navigation' in case:
            reference = 'layout.' + case['navigation']['token']
            source = source.replace(case.get('rootReference', "'a'") + ';', reference + ';')
        uri = grammar.as_uri()
        params = {'textDocument': {'uri': uri}, 'position': {'line': 3, 'character': 1}}
        record = {'case': case['id'], 'gap': case.get('gap', False), 'servers': []}
        for host, client in zip(['java', 'rust'], clients):
            client.fixture = case['id']
            error = case.get('rustLspError', expected(case, host, 'load')) if host == 'rust' else case.get('lspError', case.get('loadError'))
            prefix = case.get('rustLoadPrefix', case.get('loadPrefix', 'E-PACKAGE: ')) if host == 'rust' else case.get('loadPrefix', 'E-PACKAGE: ')
            oracle = [] if error is None else [{
                'range': {'start': position(source, source.index('@import')),
                          'end': position(source, source.index('@import') + len(declaration))},
                'severity': 1, 'code': 'E-MODULE', 'source': 'ubnf-module',
                'message': 'import layout: ' + prefix + error}]
            client.timeout = 20
            client.send('textDocument/didOpen', {'textDocument': {
                'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
            actual = client.diagnostics(uri)
            assert actual == oracle, (case['id'], host, actual, oracle)
            completion = client.result('textDocument/completion', params)
            assert any(item['label'] == 'Start' for item in completion), (case['id'], host, completion)
            navigation = check_navigation(client, case, host, folder, uri, source, error) if 'navigation' in case else None
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            if navigation and navigation['uri']:
                assert client.result('ubnf/packageSource', {'uri': navigation['uri']}) is None
            assert client.result('textDocument/hover', params) is None
            assert snapshot(folder) == before
            assert not connections, (case['id'], host, 'fixed cache stdio connected')
            record['servers'].append({'host': host, 'diagnostics': actual, 'followup': 'completion + hover after close', 'navigation': navigation, 'connections': 0})
        evidence.append(record)
    return evidence


def check_navigation(client, case, host, folder, uri, source, error):
    from urllib.parse import quote
    position = __import__('importlib').import_module('check-ubnf-authoring-lsp').position
    definition = client.result('textDocument/definition', {'textDocument': {'uri': uri},
                               'position': position(source, source.index('layout.' + case['navigation']['token']))})
    if error:
        assert definition == [], (case['id'], host, definition)
        return {'definition': definition, 'uri': None}
    lock = json.loads((folder / 'ubnf.lock.json').read_bytes())
    sha = lock['packages']['std/layout']['sha256']
    document = json.loads((folder / '.ubnf-cache/packages' / (sha + '.json')).read_bytes())
    filename, token = case['navigation']['file'], case['navigation']['token']
    module = document['files'][filename]
    package_uri = 'ubnf-package:/' + sha + '/' + quote(filename, safe="/-._~!$&'()*+,;=:@")
    offset = module.index('token ' + token) + len('token ')
    def unit_position(offset):
        before = module[:offset]
        return {'line': before.count('\n'), 'character': len(before.rsplit('\n', 1)[-1].encode('utf-16-le', 'surrogatepass')) // 2}
    oracle = [{'uri': package_uri, 'range': {'start': unit_position(offset), 'end': unit_position(offset + len(token))}}]
    assert definition == oracle, (case['id'], host, definition, oracle)
    actual_source = client.result('ubnf/packageSource', {'uri': package_uri})
    # The unchanged Java stdio encoder replaces unpaired units only on the wire.
    # This is an oracle for transport, never a substituted input to either parser.
    expected_source = module.encode('utf-8', 'replace').decode() if host == 'java' else module
    assert actual_source == expected_source, (case['id'], host, 'packageSource differs')
    return {'definition': definition, 'uri': package_uri, 'packageSource': actual_source}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust')
    parser.add_argument('--output', required=True)
    parser.add_argument('--only-939', action='store_true')
    args = parser.parse_args()
    commands = [('java', [args.java, '--enable-preview', '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain'])]
    if args.rust:
        commands.append(('rust', [args.rust]))
    evidence = []
    try:
        with tempfile.TemporaryDirectory(prefix='package-json-', dir=os.environ.get('TMPDIR')) as temporary:
            check_cli(Path(temporary), commands, evidence, [c for c in fixtures() if c.get('surrogate939')] if args.only_939 else None)
    finally:
        Path(args.output).write_text(json.dumps(evidence, ensure_ascii=True, indent=2) + '\n')


if __name__ == '__main__':
    main()
