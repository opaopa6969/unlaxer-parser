"""Credentials JSON: real CLIs, local TLS, private dummy headers and snapshots (#943)."""
import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import time

from ubnf_package_json import digest, encode, lock_bytes, reset, snapshot
from ubnf_credentials_home import compile_passwd_shim, isolated_env, protect_real_credentials

try:
    from java_tmp import java_command
except ModuleNotFoundError:
    from scripts.java_tmp import java_command


ROOT = Path(__file__).resolve().parents[1]
COUNT = 65
TOKEN = 'credentials-fixture-only-token'
TLS_ERROR = {
    'java': 'HTTPS artifact retrieval failed (TLS, timeout or size limit)',
    'rust': 'HTTPS artifact retrieval failed (TLS, timeout or size limit)',
}


def fixtures():
    cases = json.loads((ROOT / 'spec-corpus/package-credentials.json').read_text())
    assert len(cases) == len({case['id'] for case in cases}) == COUNT
    # Generation never opens credentials, so it is exercised on a few representative
    # forms (accepted, default path, missing override, unreadable, malformed) instead of
    # repeating the same read-only check for every JSON form (#954 keeps CI time tight).
    generated = [case for case in cases if case.get('generate')]
    assert {case['error'] is None for case in generated} == {True, False}, 'generation needs both outcomes'
    assert any(case.get('defaultPath') for case in generated), 'generation needs the default path'
    return cases


def permissions_apply(directory):
    probe = directory / 'permission-probe'
    probe.write_bytes(b'probe')
    probe.chmod(0)
    try:
        try:
            probe.read_bytes()
            return False
        except PermissionError:
            return True
    finally:
        probe.chmod(0o600)
        probe.unlink()


def credential_bytes(case, origin, ca, missing):
    if 'bytesHex' in case:
        data = bytes.fromhex(case['bytesHex'])
    else:
        text = case.get('json', '').replace('$ORIGIN', origin).replace('$TOKEN', TOKEN)
        text = text.replace('$CA', str(ca)).replace('$MISSING', str(missing))
        text = text.replace('$NUMBER', '9' * case.get('numberLength', 0))
        # The root object is the first container; include a lone surrogate at the leaf.
        depth = case.get('depth', 1) - 1
        text = text.replace('$NESTED', '[' * depth + '"\\ud800"' + ']' * depth)
        data = text.encode('utf-8')
    return data + b' ' * (case.get('padBytes', 0) - len(data))


def local_tls_context(directory, names='DNS:localhost'):
    # Same local CA / hostname trust boundary as HttpsPackageConformanceTest.
    for command in [
        ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'ca.key', '-out', 'ca.pem',
         '-days', '1', '-subj', '/CN=credentials-fixture-ca'],
        ['req', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'server.key', '-out', 'server.csr',
         '-subj', '/CN=localhost'],
        ['x509', '-req', '-in', 'server.csr', '-CA', 'ca.pem', '-CAkey', 'ca.key', '-CAcreateserial',
         '-out', 'server.pem', '-days', '1', '-extfile', 'extensions'],
    ]:
        (directory / 'extensions').write_text('subjectAltName=' + names + '\nbasicConstraints=CA:FALSE\n'
                                            'keyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
        result = subprocess.run(['openssl'] + command, cwd=directory, capture_output=True, timeout=30)
        assert result.returncode == 0, 'local TLS certificate setup failed'
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(directory / 'server.pem', directory / 'server.key')
    return context


@contextmanager
def local_tls(directory, artifact, tokens=None):
    tokens = {TOKEN: "bearer"} if tokens is None else tokens
    context = local_tls_context(directory)
    received = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Never write request headers or response content to logs.

        def do_GET(self):
            authorization = self.headers.get_all('Authorization', [])
            # Compare the actual bytes-as-header-text in memory; receipts contain labels only.
            received.append({'authorization': next((label for token, label in tokens.items()
                                                    if authorization == ['Bearer ' + token]),
                                                   'absent' if not authorization else 'unexpected'),
                             'extraHeader': any(k.lower().startswith('x-') for k in self.headers),
                             'path': self.path})
            self.send_response(200)
            self.send_header('Content-Length', str(len(artifact)))
            self.end_headers()
            self.wfile.write(artifact)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield 'https://localhost:' + str(server.server_port), directory / 'ca.pem', received
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive(), 'local TLS fixture did not stop'


def run(command, options, env):
    try:
        result = subprocess.run(command + options, capture_output=True, timeout=30, env=env)
    except subprocess.TimeoutExpired:
        raise AssertionError('credentials fixture process timed out') from None
    # Never put stdout/stderr (including failures) into assertion messages or evidence.
    assert all(value not in result.stdout + result.stderr for value in [TOKEN.encode(), b'dummy']), \
        'credential leaked into process output'
    stderr = result.stderr.decode('utf-8', errors='strict')
    return {'exit': result.returncode, 'lastError': (stderr.strip().splitlines() or [''])[-1]}


def receipt(files):
    return {name: data if data == 'directory' else {'bytes': len(data) // 2, 'sha256': digest(bytes.fromhex(data))}
            for name, data in files.items()}


def check(directory, args, evidence, excluded):
    artifact = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    baseline = {'ubnf.lock.json': lock_bytes({'std/layout': artifact}, {'std/layout': '1.0.0'}).hex(),
                '.ubnf-cache/': 'directory', '.ubnf-cache/packages/': 'directory',
                '.ubnf-cache/packages/' + digest(artifact) + '.json': artifact.hex()}
    started = time.monotonic()
    denied_works = permissions_apply(directory)
    library = compile_passwd_shim(directory)
    with local_tls(directory, artifact) as (origin, ca, received):
        for case in fixtures():
            if args.case and case['id'] not in args.case:
                continue
            if case.get('file') == 'unreadable' and not denied_works:
                # chmod(000) is not an I/O failure for root/CAP_DAC_OVERRIDE. Do not claim it was tested.
                excluded.append({'case': case['id'], 'reason':
                                 'The execution user can read chmod(000); this permission combination is outside this run.'})
                continue
            print('credentials CLI:', case['id'], flush=True)
            folder = directory / case['id']
            folder.mkdir()
            home = folder / 'home'
            home.mkdir()
            credentials = home / '.config/unlaxer/credentials.json' if case.get('defaultPath') else folder / 'credentials.json'
            credentials.parent.mkdir(parents=True, exist_ok=True)
            kind = case.get('file')
            if kind == 'directory':
                credentials.mkdir()
            elif kind != 'missing':
                credentials.write_bytes(credential_bytes(case, origin, ca, directory / 'missing.pem'))
                if kind == 'unreadable':
                    credentials.chmod(0)
            manifest = folder / 'ubnf.json'
            manifest.write_bytes(encode({'schemaVersion': 1, 'dependencies': {
                'std/layout': {'version': '1.0.0', 'source': origin + '/artifact'}}}))
            grammar = folder / 'main.ubnf'
            grammar.write_text("grammar Main { @import layout from 'pkg:std/layout' @ubnf: v2 "
                               "@root @mapping(Value) Start ::= 'a'; }\n")
            # Override only child-process homes, never the developer's real configuration.
            env = isolated_env(library, home, home)
            env.pop('UBNF_CREDENTIALS_FILE', None)
            if not case.get('defaultPath'):
                env['UBNF_CREDENTIALS_FILE'] = str(credentials)
            java = java_command(args.java) + ['--enable-preview', '-Duser.home=' + str(home),
                    '-Djava.io.tmpdir=' + str(directory), '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']
            commands = [('java', java, env)]
            if args.rust:
                commands.append(('rust', [args.rust], dict(env, PATH='', JAVA_HOME='/nonexistent-java')))
            record = {'case': case['id'], 'runs': []}
            evidence.append(record)
            try:
                for existing in [False, True]:
                    initial = baseline if existing else {}
                    resolved = []
                    for host, command, process_env in commands:
                        reset(folder, initial)
                        before_requests = len(received)
                        actual = run(command, ['deps', 'resolve', '--manifest', str(manifest)], process_env)
                        error = case['error']
                        if error == 'tls':
                            error = TLS_ERROR[host]  # Java transport classification (#956).
                        expected = {'exit': 3 if error else 0, 'lastError': 'E-PACKAGE: ' + error if error else ''}
                        assert actual == expected, (case['id'], host, 'resolve result differs', actual)
                        after = snapshot(folder)
                        assert after == (initial if error else baseline), (case['id'], host, 'snapshot changed')
                        headers = received[before_requests:]
                        wanted = [] if error else [{'authorization': case['header'], 'extraHeader': False, 'path': '/artifact'}]
                        assert headers == wanted, (case['id'], host, 'request headers/count differ')
                        record['runs'].append(dict(host=host, operation='resolve', existingSnapshot=existing,
                                                   **actual, headers=headers, snapshot=receipt(after)))
                        resolved.append((actual, after, headers))
                    if len(resolved) == 2:
                        assert resolved[0] == resolved[1], (case['id'], 'reference/native result differs')
                # Generation is read-only and must never parse credentials, with or without a pin.
                for existing in ([False, True] if case.get('generate') else []):
                    initial = baseline if existing else {}
                    reset(folder, initial)
                    generated = []
                    for host, command, process_env in commands:
                        for flags in ([False, True] if host == 'java' else [False]):
                            output = folder / (host + ('-flags' if flags else '-rust') + str(existing))
                            options = ['--grammar', str(grammar), '--output', str(output), '--generators', 'Parser'] if flags else [
                                'generate', '--target', 'rust', '--grammar', str(grammar), '--output', str(output)]
                            before_requests = len(received)
                            actual = run(command, options, process_env)
                            missing = 'E-PACKAGE: missing lock; run unlaxer deps resolve --manifest ubnf.json'
                            expected = {'exit': 0 if existing else 4 if flags else 3,
                                        'lastError': '' if existing else ('Generation failed: ' if flags else '') + missing}
                            assert actual == expected, (case['id'], host, 'generate result differs', actual)
                            assert snapshot(folder) == initial, (case['id'], host, 'generation changed snapshot')
                            assert len(received) == before_requests, 'generation used the network'
                            if existing:
                                files = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob('*') if p.is_file()}
                                assert files and all(TOKEN.encode() not in data for data in files.values()), 'credential leaked into generated output'
                                if not flags:
                                    generated.append(files)
                            else:
                                assert not output.exists(), 'failed generation published files'
                            record['runs'].append(dict(host=host, operation='flags' if flags else 'generate',
                                                       existingSnapshot=existing, **actual, headers=[]))
                    if len(generated) == 2:
                        assert generated[0] == generated[1], 'generated bytes differ'
            finally:
                if kind == 'unreadable':
                    credentials.chmod(0o600)
    return time.monotonic() - started


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', action='append')
    args = parser.parse_args()
    evidence = []
    excluded = []
    elapsed = None
    try:
        with protect_real_credentials(), tempfile.TemporaryDirectory(
                prefix='package-credentials-', dir=os.environ.get('TMPDIR')) as temporary:
            elapsed = check(Path(temporary), args, evidence, excluded)
    finally:
        args.output.write_text(json.dumps({'seconds': elapsed, 'cases': evidence, 'excluded': excluded},
                                          ensure_ascii=True, indent=2) + '\n')


if __name__ == '__main__':
    main()
