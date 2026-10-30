"""#890: shared disk fixtures for real authoring servers and both generation CLIs."""
import json
import os
import socket
import subprocess

LIMIT = 64 * 1024 * 1024


def run_local_imports(clients, directory, java_command, native_command, root):
    from importlib import import_module
    position = import_module('check-ubnf-authoring-lsp').position
    cases = json.loads((root / 'spec-corpus/ubnf-authoring-lsp/local-imports.json').read_text())
    folder = directory / 'local-imports'
    folder.mkdir()
    # No checked-in FIFO, writer, or privileged device creation. The enclosing temporary
    # directory and this socket's context manager clean up even after timeouts/assertions.
    subprocess.run(['mkfifo', str(folder / 'pipe')], check=True, timeout=5)
    (folder / 'directory').mkdir()
    module = "grammar Library { @ubnf: v2 token Item ::= 'a'; }\n"
    (folder / 'tokens.ubnf').write_text(module)
    with (folder / 'large.ubnf').open('wb') as stream:
        stream.truncate(LIMIT + 1)
    # A valid module at the inclusive byte limit. Supplementary characters also
    # distinguish bytes from UTF-16 units / Unicode scalar counts.
    with (folder / 'exact.ubnf').open('wb') as stream:
        prefix = (module + '//').encode()
        stream.write(prefix)
        remaining = LIMIT - len(prefix)
        while remaining:
            count = min(1024 * 1024, remaining)
            stream.write(('😀' * (count // 4)).encode() + b'x' * (count % 4))
            remaining -= count
    for name, target in [('pipe-link', 'pipe'), ('directory-link', 'directory'),
                         ('device-link', '/dev/zero'), ('large-link.ubnf', 'large.ubnf'),
                         ('tokens-link.ubnf', 'tokens.ubnf'), ('chain-link', 'pipe-link')]:
        (folder / name).symlink_to(target)
    evidence = []
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as endpoint:
        # AF_UNIX paths are short even when the caller's TMPDIR/worktree is long.
        endpoint.bind(str(directory / 's'))
        (folder / 'socket').symlink_to(directory / 's')
        for case in cases:
            name = case['id']
            for client in clients:
                client.fixture = name
            print('local import fixture:', name, flush=True)
            declaration = f"@import x from '{case['path']}'"
            source = f"grammar Main {{ // 😀\r\n {declaration}\r\n @ubnf: v2\r\n @root @mapping(Value) Start ::= 'a';\r\n}}"
            path = folder / 'main space 😀.ubnf'
            path.write_text(source, newline='')
            uri = path.as_uri()
            params = {'textDocument': {'uri': uri}, 'position': {'line': 3, 'character': 1}}
            error = 'E-MODULE: local import ' + case['error'] if 'error' in case else None
            expected = [] if error is None else [{
                'range': {'start': position(source, source.index('@import')),
                          'end': position(source, source.index('@import') + len(declaration))},
                'severity': 1, 'code': 'E-MODULE', 'source': 'ubnf-module',
                'message': 'import x: ' + error}]
            for host, client in zip(['java', 'rust'], clients):
                print('  stdio:', host, flush=True)
                # The valid 64 MiB comment exercises the existing parser; it is
                # much slower than rejecting a device before open (#197 freeze).
                client.timeout = 480 if name == 'exact-limit' else 20
                client.send('textDocument/didOpen', {'textDocument': {
                    'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
                diagnostics = client.diagnostics(uri)
                assert diagnostics == expected, (name, host, diagnostics, expected)
                # This must arrive AFTER the diagnostic, on the same live stdio session.
                if name == 'exact-limit':
                    # An unrelated request proves responsiveness without re-parsing
                    # the large import merely to compute completion candidates.
                    followup = {**params, 'textDocument': {'uri': (folder / 'unopened.ubnf').as_uri()}}
                    assert client.result('textDocument/hover', followup) is None
                else:
                    completion = client.result('textDocument/completion', params)
                    labels = [item['label'] for item in completion]
                    assert 'Start' in labels, (name, host, labels)
                    assert ('x.Item' in labels) == (error is None), (name, host, labels)
                client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
                assert client.result('textDocument/hover', params) is None
            if case is cases[-1]:
                # All stdio assertions (including followups) are complete. Release
                # the large Java parser heap before starting the boundary CLIs;
                # keeping the idle server alive overlaps two multi-GiB JVMs.
                for client in clients:
                    assert client.result('shutdown') is None
                    client.send('exit')
                    assert client.process.wait(timeout=5) == 0
            generated = []
            for host, command in [('java', java_command), ('rust', native_command)]:
                output = folder / f'{name}-{host}'
                env = dict(os.environ)
                if host == 'rust':
                    env.update(PATH='', JAVA_HOME='/nonexistent-java')
                result = subprocess.run(command + ['generate', '--target', 'rust', '--grammar', str(path),
                    '--output', str(output)], capture_output=True, text=True, timeout=240 if name == 'exact-limit' else 30, env=env)
                if error:
                    assert result.returncode != 0 and error in result.stderr, (name, host, result)
                    assert not output.exists(), (name, host, 'partial output')
                else:
                    assert result.returncode == 0, (name, host, result)
                    generated.append({str(p.relative_to(output)): p.read_bytes() for p in output.rglob('*') if p.is_file()})
            if generated:
                assert len(generated[0]) == 5 and generated[0] == generated[1], name
            # Cover the existing Java flag CLI and its filesystem port as well.
            output = folder / f'{name}-java-flags'
            result = subprocess.run(java_command + ['--grammar', str(path), '--output', str(output),
                '--generators', 'Parser'], capture_output=True, text=True, timeout=240 if name == 'exact-limit' else 30)
            if error:
                assert result.returncode != 0 and error in result.stderr, (name, 'java-flags', result)
                assert not output.exists(), (name, 'java-flags', 'partial output')
            else:
                assert result.returncode == 0 and any(output.rglob('*.java')), (name, 'java-flags', result)
            evidence.append({'case': name, 'diagnostics': expected,
                             'servers': ['java', 'rust'], 'cli': ['java-rust', 'rust', 'java-flags'],
                             'followupRequest': ('unopened hover' if name == 'exact-limit' else 'completion') + ' + hover after close'})
    return evidence
