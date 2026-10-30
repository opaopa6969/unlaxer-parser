"""#908: the same package metadata files reach both stdio servers and all three CLIs."""
import hashlib
import json
import os
import subprocess

LIMIT = 8 * 1024 * 1024


def run_package_documents(clients, directory, java_command, native_command, root):
    from importlib import import_module
    position = import_module('check-ubnf-authoring-lsp').position
    cases = json.loads((root / 'spec-corpus/ubnf-authoring-lsp/package-documents.json').read_text())
    assert len(cases) == 27, 'package document fixtures must not silently disappear'
    artifact = (root / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    digest = hashlib.sha256(artifact).hexdigest()
    documents = {
        'manifest': {'schemaVersion': 1, 'dependencies': {
            'std/layout': {'version': '1.0.0', 'source': 'builtin:std/layout@1.0.0'}}},
        'lock': {'schemaVersion': 1, 'roots': {'std/layout': '1.0.0'}, 'packages': {
            'std/layout': {'id': 'std/layout', 'version': '1.0.0', 'sha256': digest, 'dependencies': {}}}},
    }
    evidence = []
    for case in cases:
        print('package document fixture:', case['id'], flush=True)
        folder = directory / case['id']
        folder.mkdir()
        cache = folder / '.ubnf-cache/packages'
        cache.mkdir(parents=True)
        (cache / (digest + '.json')).write_bytes(artifact)
        for subject, filename in [('manifest', 'ubnf.json'), ('lock', 'ubnf.lock.json')]:
            path, kind = folder / filename, case[subject]
            content = json.dumps(documents[subject]).encode()
            if kind == 'missing':
                continue
            if kind in ('fifo', 'fifo-link'):
                target = path if kind == 'fifo' else folder / (subject + '-pipe')
                subprocess.run(['mkfifo', str(target)], check=True, timeout=5)
                if kind == 'fifo-link':
                    path.symlink_to(target.name)
            elif kind == 'device-link':
                path.symlink_to('/dev/zero')
            elif kind in ('directory', 'directory-link'):
                target = path if kind == 'directory' else folder / (subject + '-directory')
                target.mkdir()
                if kind == 'directory-link':
                    path.symlink_to(target.name)
            elif kind in ('oversized', 'oversized-link'):
                target = path if kind == 'oversized' else folder / (subject + '-large')
                with target.open('wb') as stream:
                    stream.truncate(LIMIT + 1)
                if kind == 'oversized-link':
                    path.symlink_to(target.name)
            elif kind == 'exact':
                path.write_bytes(content + b' ' * (LIMIT - len(content)))
            elif kind == 'regular-link':
                target = folder / (subject + '-regular')
                target.write_bytes(content)
                path.symlink_to(target.name)
            else:
                path.write_bytes(b'{' if kind == 'invalid' else b'{"a":"\xff"}' if kind == 'invalid-utf8' else b'{"schemaVersion":2}' if kind == 'schema' else content)
        declaration = "@import layout from 'pkg:std/layout'"
        source = f"grammar Main {{ // 😀\r\n {declaration}\r\n @ubnf: v2\r\n @root @mapping(Value) Start ::= 'a';\r\n}}"
        path = folder / 'main space 😀.ubnf'
        path.write_text(source, newline='')
        uri = path.as_uri()
        params = {'textDocument': {'uri': uri}, 'position': {'line': 3, 'character': 1}}
        error = 'E-PACKAGE: ' + case['error'] if 'error' in case else None
        expected = [] if error is None else [{
            'range': {'start': position(source, source.index('@import')),
                      'end': position(source, source.index('@import') + len(declaration))},
            'severity': 1, 'code': 'E-MODULE', 'source': 'ubnf-module',
            'message': 'import layout: ' + error}]
        for host, client in zip(['java', 'rust'], clients):
            client.fixture = case['id']
            client.timeout = 20
            client.send('textDocument/didOpen', {'textDocument': {
                'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
            actual = client.diagnostics(uri)
            assert actual == expected, (case['id'], host, actual, expected)
            completion = client.result('textDocument/completion', params)
            assert any(item['label'] == 'Start' for item in completion), (case['id'], host, completion)
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            assert client.result('textDocument/hover', params) is None
        generated = []
        for host, command in [('java-rust', java_command), ('rust', native_command), ('java-flags', java_command)]:
            output = folder / host
            options = (['--grammar', str(path), '--output', str(output), '--generators', 'Parser']
                       if host == 'java-flags' else
                       ['generate', '--target', 'rust', '--grammar', str(path), '--output', str(output)])
            env = dict(os.environ)
            if host == 'rust':
                env.update(PATH='', JAVA_HOME='/nonexistent-java')
            result = subprocess.run(command + options, capture_output=True, text=True, timeout=30, env=env)
            if error:
                assert result.returncode != 0 and error in result.stderr, (case['id'], host, result)
                assert not output.exists(), (case['id'], host, 'partial output')
            else:
                assert result.returncode == 0, (case['id'], host, result)
                if host == 'java-flags':
                    assert any(output.rglob('*.java'))
                else:
                    generated.append({str(p.relative_to(output)): p.read_bytes() for p in output.rglob('*') if p.is_file()})
        if generated:
            assert len(generated[0]) == 5 and generated[0] == generated[1], case['id']
        # Explicit deps resolve reads manifests too. Check its rejection before any writes.
        if case['manifest'] not in ('valid', 'regular-link', 'exact'):
            manifest_error = ('unsupported schemaVersion' if case['manifest'] == 'schema' else
                              'invalid JSON' if case['manifest'] in ('invalid', 'invalid-utf8') else
                              'missing manifest; run unlaxer deps resolve --manifest ubnf.json' if case['manifest'] == 'missing' else
                              'manifest exceeds 8 MiB' if case['manifest'].startswith('oversized') else
                              'manifest is not a regular file')
            for host, command in [('java', java_command), ('rust', native_command)]:
                env = dict(os.environ)
                if host == 'rust':
                    env.update(PATH='', JAVA_HOME='/nonexistent-java')
                result = subprocess.run(command + ['deps', 'resolve', '--manifest', str(folder / 'ubnf.json')],
                    capture_output=True, text=True, timeout=30, env=env)
                assert result.returncode != 0 and 'E-PACKAGE: ' + manifest_error in result.stderr, (case['id'], host, result)
        evidence.append({'case': case['id'], 'diagnostics': expected, 'servers': ['java', 'rust'],
                         'cli': ['java-rust', 'rust', 'java-flags'], 'followupRequest': 'completion + hover after close'})
    return evidence
