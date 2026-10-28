"""#913: pinned cache and explicit local artifacts share bounded-reader wire fixtures."""
import hashlib
import json
import os
import subprocess

LIMIT = 8 * 1024 * 1024


def materialize(path, kind, content):
    """Create only under the caller's private temporary directory; never read special files."""
    if kind == 'missing':
        return
    if kind in ('fifo', 'fifo-link'):
        target = path if kind == 'fifo' else path.with_name(path.name + '-pipe')
        subprocess.run(['mkfifo', str(target)], check=True, timeout=5)
        if kind == 'fifo-link':
            path.symlink_to(target.name)
    elif kind == 'device-link':
        path.symlink_to('/dev/zero')
    elif kind in ('directory', 'directory-link'):
        target = path if kind == 'directory' else path.with_name(path.name + '-directory')
        target.mkdir()
        if kind == 'directory-link':
            path.symlink_to(target.name)
    elif kind in ('oversized', 'oversized-link'):
        target = path if kind == 'oversized' else path.with_name(path.name + '-large')
        with target.open('wb') as stream:
            stream.truncate(LIMIT + 1)
        if kind == 'oversized-link':
            path.symlink_to(target.name)
    elif kind == 'dangling-link':
        path.symlink_to('absent-artifact')
    elif kind == 'loop':
        path.symlink_to(path.name)
    elif kind == 'regular-link':
        target = path.with_name(path.name + '-regular')
        target.write_bytes(content)
        path.symlink_to(target.name)
    else:
        path.write_bytes(content)


def run_package_artifacts(clients, directory, java_command, native_command, root):
    from importlib import import_module
    position = import_module('check-ubnf-authoring-lsp').position
    cases = json.loads((root / 'spec-corpus/ubnf-authoring-lsp/package-artifacts.json').read_text())
    assert len(cases) == 39, 'artifact fixtures must not silently disappear'
    standard = (root / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    evidence = []
    for case in cases:
        print('package artifact fixture:', case['id'], flush=True)
        folder = directory / case['id']
        folder.mkdir()
        kind, mode = case['kind'], case['mode']
        artifact = (standard + b' ' * (LIMIT - len(standard)) if kind == 'exact' else
                    b'{' if kind == 'invalid-json' else
                    b'\xff' if kind == 'raw-hash-mismatch' else
                    standard + b' ' if kind == 'hash-mismatch' else standard)
        if kind == 'utf8':
            value = json.loads(standard)
            value['padding'] = '😀é'
            artifact = json.dumps(value, ensure_ascii=False).encode()
        digest = hashlib.sha256(standard if 'hash-mismatch' in kind else artifact).hexdigest()
        cache = folder / '.ubnf-cache/packages'
        cache.mkdir(parents=True)
        cached = cache / (digest + '.json')
        local = folder / 'artifact.json'
        # The same unsafe local source is ignored during ordinary pinned loading.
        # Only explicit deps resolve may open it. Cache mode instead exercises loaded().
        materialize(cached if mode == 'cache' else local, kind, artifact)
        (local if mode == 'cache' else cached).write_bytes(standard if mode == 'local' else artifact)
        if mode == 'local':
            # Healthy pinned bytes need their own original hash even when the local input changes.
            cached.unlink()
            digest = hashlib.sha256(standard).hexdigest()
            (cache / (digest + '.json')).write_bytes(standard)
        manifest = {'schemaVersion': 1, 'dependencies': {
            'std/layout': {'version': '1.0.0', 'source': 'local:artifact.json'}}}
        lock = {'schemaVersion': 1, 'roots': {'std/layout': '1.0.0'}, 'packages': {
            'std/layout': {'id': 'std/layout', 'version': '1.0.0', 'sha256': digest, 'dependencies': {}}}}
        if case.get('transitive'):
            parent = {'schemaVersion': 1, 'id': 'team/parent', 'version': '1.0.0', 'entry': 'parent.ubnf',
                      'files': {'parent.ubnf': "grammar Parent { @ubnf: v2 token Item ::= 'p'; }"},
                      'dependencies': {'std/layout': {'version': '1.0.0', 'source': 'local:../artifact.json'}}}
            (folder / 'nested').mkdir()
            (folder / 'nested/parent.json').write_text(json.dumps(parent))
            manifest['dependencies']['team/parent'] = {'version': '1.0.0', 'source': 'local:nested/parent.json'}
            # Only the parent is an explicit root, so resolve must actually traverse the child.
            del manifest['dependencies']['std/layout']
            parent_bytes = json.dumps(parent).encode()
            parent_hash = hashlib.sha256(parent_bytes).hexdigest()
            (cache / (parent_hash + '.json')).write_bytes(parent_bytes)
            lock['roots'] = {'team/parent': '1.0.0'}
            lock['packages']['team/parent'] = {'id': 'team/parent', 'version': '1.0.0',
                'sha256': parent_hash, 'dependencies': {'std/layout': '1.0.0'}}
        (folder / 'ubnf.json').write_text(json.dumps(manifest))
        (folder / 'ubnf.lock.json').write_text(json.dumps(lock))
        if case.get('manifest') == 'invalid':
            (folder / 'ubnf.json').write_text('{')
        if case.get('lock') == 'oversized':
            with (folder / 'ubnf.lock.json').open('wb') as stream:
                stream.truncate(LIMIT + 1)
        package = 'team/parent' if case.get('transitive') else 'std/layout'
        declaration = f"@import layout from 'pkg:{package}'"
        source = f"grammar Main {{ // 😀\r\n {declaration}\r\n @ubnf: v2\r\n @root @mapping(Value) Start ::= 'a';\r\n}}"
        path = folder / 'main space 😀.ubnf'
        path.write_text(source, newline='')
        uri = path.as_uri()
        params = {'textDocument': {'uri': uri}, 'position': {'line': 3, 'character': 1}}
        error = 'E-PACKAGE: ' + case['loadError'] if 'loadError' in case else None
        expected = [] if error is None else [{
            'range': {'start': position(source, source.index('@import')),
                      'end': position(source, source.index('@import') + len(declaration))},
            'severity': 1, 'code': 'E-MODULE', 'source': 'ubnf-module', 'message': 'import layout: ' + error}]
        for host, client in zip(['java', 'rust'], clients):
            client.timeout = 20
            client.send('textDocument/didOpen', {'textDocument': {
                'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
            assert (actual := client.diagnostics(uri)) == expected, (case['id'], host, actual, expected)
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
        if mode == 'local':
            # Each resolver starts with the same pinned snapshot. Failed resolution must not publish.
            lock_path = folder / 'ubnf.lock.json'
            before_lock = lock_path.read_bytes()
            before_cache = {p.name: p.read_bytes() for p in cache.iterdir()}
            locks = []
            for host, command in [('java', java_command), ('rust', native_command)]:
                result = subprocess.run(command + ['deps', 'resolve', '--manifest', str(folder / 'ubnf.json')],
                    capture_output=True, text=True, timeout=30,
                    env={**os.environ, **({'PATH': '', 'JAVA_HOME': '/nonexistent-java'} if host == 'rust' else {})})
                if 'resolveError' in case:
                    assert result.returncode != 0 and 'E-PACKAGE: ' + case['resolveError'] in result.stderr, (case['id'], host, result)
                    assert lock_path.read_bytes() == before_lock, (case['id'], host, 'failed resolve wrote lock')
                    assert {p.name: p.read_bytes() for p in cache.iterdir()} == before_cache, (case['id'], host, 'failed resolve wrote cache')
                else:
                    assert result.returncode == 0, (case['id'], host, result)
                    locks.append(lock_path.read_bytes())
                    resolved_hash = json.loads(locks[-1])['packages']['std/layout']['sha256']
                    assert resolved_hash == hashlib.sha256(artifact).hexdigest()
                    assert (cache / (resolved_hash + '.json')).read_bytes() == artifact
                    lock_path.write_bytes(before_lock)
                    for p in cache.iterdir():
                        p.unlink()
                    for name, data in before_cache.items():
                        (cache / name).write_bytes(data)
            if locks:
                assert len(locks) == 2 and locks[0] == locks[1], (case['id'], 'lock bytes differ')
        evidence.append({'case': case['id'], 'diagnostics': expected, 'servers': ['java', 'rust'],
                         'cli': ['java-rust', 'rust', 'java-flags'],
                         'depsResolve': ['java', 'rust'] if mode == 'local' else [],
                         'followupRequest': 'completion + hover after close'})
    return evidence
