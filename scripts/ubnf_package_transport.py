"""Java-reference HTTPS transport fixtures: local TLS, private receipts, real CLIs."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import socket
import socketserver
import subprocess
import tempfile
import threading
import time

from ubnf_credentials_home import compile_passwd_shim, isolated_env, protect_real_credentials
from ubnf_package_credentials import ROOT, local_tls_context, receipt
from ubnf_package_json import digest, encode, lock_bytes, reset, snapshot

LIMIT = 8 * 1024 * 1024
COUNT = 38
TOKEN = 'transport-fixture-only-token'
BODY = b'transport-fixture-private-response'
HEADER = 'transport-fixture-private-header'
TRANSPORT = 'HTTPS artifact retrieval failed (TLS, timeout or size limit)'
STATUS = 'HTTPS artifact requires status 200; redirects are forbidden'
MISSING = 'E-PACKAGE: missing lock; run unlaxer deps resolve --manifest ubnf.json'


def fixtures():
    cases = json.loads((ROOT / 'spec-corpus/package-https-transport.json').read_text())
    assert len(cases) == len({c['id'] for c in cases}) == COUNT, 'transport fixture missing'
    return cases


@contextmanager
def transport_server(directory, artifact):
    # Reuse the existing certificate setup; this server supplies deliberately broken
    # HTTP framing that BaseHTTPRequestHandler / HttpsServer cannot express.
    context = local_tls_context(directory)
    ca = directory / 'ca.pem'
    receipts = []
    connections = []
    stop = threading.Event()
    cases = {c['id']: c for c in fixtures()}

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            connections.append(True)  # Count even a failed TLS handshake.
            try:
                with context.wrap_socket(self.request, server_side=True) as stream:
                    stream.settimeout(5)
                    request = b''
                    while b'\r\n\r\n' not in request and len(request) < 65536:
                        part = stream.recv(4096)
                        if not part:
                            return
                        request += part
                    lines = request.split(b'\r\n')
                    path = lines[0].split(b' ')[1].decode('ascii')
                    auth = [line.split(b':', 1)[1].strip() for line in lines[1:]
                            if line.lower().startswith(b'authorization:')]
                    authorized = auth == [('Bearer ' + TOKEN).encode()]
                    receipts.append({'case': path[1:], 'authorization':
                                     'bearer' if authorized else 'absent' if not auth else 'unexpected'})
                    # A followed redirect is a test failure, including same-origin redirects.
                    if path == '/redirect-target':
                        stream.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n')
                        return
                    case = cases[path[1:]]
                    kind = case['kind']
                    if kind == 'disconnect':
                        return
                    status = case.get('status', 401 if kind == 'auth' and not authorized else 200)
                    data = (artifact + b' ' * (LIMIT - len(artifact))) if kind in ['limit', 'chunked-limit', 'close-limit'] else artifact
                    if kind in ['oversize', 'chunked-oversize', 'close-oversize']:
                        data = BODY + b' ' * (LIMIT + 1 - len(BODY))
                    if kind == 'status' or kind == 'auth':
                        data = b'' if status in [204, 304] else BODY
                    if kind == 'empty':
                        data = b''
                    if kind == 'chunked-truncated':
                        data = BODY
                    headers = [f'HTTP/1.1 {status} Fixture', 'Connection: close', 'X-Fixture: ' + HEADER]
                    if 300 <= status < 400:
                        headers.append(f'Location: https://localhost:{server.server_address[1]}/redirect-target')
                    if kind.startswith('chunked-'):
                        headers.append('Transfer-Encoding: chunked')
                    elif kind not in ['close-oversize', 'close-limit']:
                        length = len(data) + 1 if kind == 'truncated' else 1 if kind == 'stall' else len(data)
                        headers.append('Content-Length: ' + str(length))
                    stream.sendall(('\r\n'.join(headers) + '\r\n\r\n').encode())
                    if kind == 'stall':
                        stop.wait(35)  # The unmodified 20-second product deadline must win.
                        return
                    if kind.startswith('chunked-'):
                        length = len(data) + (1 if kind == 'chunked-truncated' else 0)
                        stream.sendall(f'{length:x}\r\n'.encode() + data)
                        if kind != 'chunked-truncated':
                            stream.sendall(b'\r\n0\r\n\r\n')
                    else:
                        stream.sendall(data)
                        if kind == 'extra':
                            stream.sendall(BODY)  # Declared length ends at the valid artifact.
            except OSError:
                pass  # Client cancellation / trust failures; never log request/body values.

    class Server(socketserver.ThreadingTCPServer):
        daemon_threads = True

        def handle_error(self, *_):
            # Do not allow socketserver to print exception/request data.
            receipts.append({'serverError': True})

    with Server(('127.0.0.1', 0), Handler) as server, socket.socket() as refused:
        refused.bind(('127.0.0.1', 0))  # Reserved but not listening: deterministic refusal.
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server.server_address[1], refused.getsockname()[1], ca, receipts, connections
        finally:
            stop.set()
            server.shutdown()
            thread.join(timeout=5)
            assert not thread.is_alive(), 'transport server did not stop'


def invoke(command, options, env):
    tick = time.monotonic()
    try:
        result = subprocess.run(command + options, capture_output=True, env=env, timeout=28)
    except subprocess.TimeoutExpired:
        raise AssertionError('transport process exceeded product deadline') from None
    output = result.stdout + result.stderr
    assert all(value not in output for value in [TOKEN.encode(), BODY, HEADER.encode()]), 'private value leaked'
    last = (result.stderr.decode('utf-8').strip().splitlines() or [''])[-1]
    # Evidence contains only known public diagnostics, never an arbitrary server/JDK message.
    allowed = ['', MISSING, 'Generation failed: ' + MISSING] + ['E-PACKAGE: ' + e for e in
              [TRANSPORT, STATUS, 'expected object: document', 'invalid JSON',
               'HTTPS artifact retrieval failed (TLS, timeout, status or redirect)',
               'HTTPS artifact retrieval failed (timeout or size limit)']]
    assert last in allowed, 'unexpected diagnostic (withheld to protect response/header values)'
    return {'exit': result.returncode, 'lastError': last}, time.monotonic() - tick


def pinned(artifact):
    return {'ubnf.lock.json': lock_bytes({'std/layout': artifact}, {'std/layout': '1.0.0'}).hex(),
            '.ubnf-cache/': 'directory', '.ubnf-cache/packages/': 'directory',
            '.ubnf-cache/packages/' + digest(artifact) + '.json': artifact.hex()}


def check(directory, args, records):
    artifact = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    baseline = pinned(artifact)
    library = compile_passwd_shim(directory)
    started = time.monotonic()
    with transport_server(directory, artifact) as (port, refused, ca, received, connections):
        for case in fixtures():
            if args.case and case['id'] not in args.case:
                continue
            print('HTTPS transport:', case['id'], flush=True)
            folder = directory / case['id']
            folder.mkdir()
            origin = 'https://' + case.get('hostname', 'localhost') + ':' + str(
                refused if case['kind'] == 'refused' else port)
            credentials = folder / 'credentials.json'
            entry = {}
            if case.get('trust', True):
                entry['caCertificate'] = str(ca)
            if case.get('token', True):
                entry['bearerToken'] = TOKEN
            credentials.write_bytes(encode({origin: entry, origin + '9': {'bearerToken': TOKEN}}))
            manifest = folder / 'ubnf.json'
            manifest.write_bytes(encode({'schemaVersion': 1, 'dependencies': {
                'std/layout': {'version': '1.0.0', 'source': origin + '/' + case['id']}}}))
            grammar = folder / 'main.ubnf'
            grammar.write_text("grammar Main { @import layout from 'pkg:std/layout' @ubnf: v2 "
                               "@root @mapping(Value) Start ::= 'a'; }\n")
            env = isolated_env(library, folder, folder)
            env['UBNF_CREDENTIALS_FILE'] = str(credentials)
            java = [args.java, '--enable-preview', '-Djava.io.tmpdir=' + str(directory),
                    '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']
            commands = [('java', java, env)]
            if args.rust:
                commands.append(('rust', [args.rust], dict(env, PATH='', JAVA_HOME='/nonexistent-java')))
            record = {'case': case['id'], 'runs': []}
            records.append(record)
            for existing in [False, True]:
                initial = baseline if existing else {}
                for host, command, process_env in commands:
                    reset(folder, initial)
                    before = len(received)
                    actual, elapsed = invoke(command, ['deps', 'resolve', '--manifest', str(manifest)], process_env)
                    error = case['error']
                    expected = {'exit': 3 if error else 0, 'lastError': 'E-PACKAGE: ' + error if error else ''}
                    record['runs'].append(dict(host=host, operation='resolve', existingSnapshot=existing,
                                               **actual, seconds=elapsed))
                    if not args.oracle:
                        assert actual == expected, (case['id'], host, 'diagnostic/exit differs', actual)
                    after = snapshot(folder)
                    wanted = initial if actual['exit'] else pinned(
                        artifact + b' ' * (LIMIT - len(artifact)) if case['kind'] in ['limit', 'chunked-limit', 'close-limit'] else artifact)
                    if actual['exit'] == 0 and existing and case['kind'] in ['limit', 'chunked-limit', 'close-limit']:
                        wanted['.ubnf-cache/packages/' + digest(artifact) + '.json'] = artifact.hex()
                    assert after == wanted, (case['id'], host, 'snapshot differs')
                    record['runs'][-1]['snapshot'] = receipt(after)
                    receipts = received[before:]
                    no_request = not case.get('trust', True) or 'hostname' in case or case['kind'] == 'refused'
                    if no_request:
                        assert not receipts, (case['id'], host, 'request passed TLS boundary')
                    else:
                        expected_receipt = {'case': case['id'], 'authorization':
                                            'bearer' if case.get('token', True) else 'absent'}
                        assert receipts and all(r == expected_receipt for r in receipts), \
                            (case['id'], host, 'request/auth/redirect differs')
                    record['runs'][-1]['requests'] = len(receipts)
                # Each transport shape also exercises all three generation CLIs.
                reset(folder, initial)
                generated = []
                for host, command, process_env in commands:
                    for flags in ([False, True] if host == 'java' else [False]):
                        output = folder / (host + ('-flags' if flags else '-rust') + str(existing))
                        options = ['--grammar', str(grammar), '--output', str(output), '--generators', 'Parser'] if flags else [
                            'generate', '--target', 'rust', '--grammar', str(grammar), '--output', str(output)]
                        before = len(connections)
                        actual, elapsed = invoke(command, options, process_env)
                        expected = {'exit': 0 if existing else 4 if flags else 3,
                                    'lastError': '' if existing else ('Generation failed: ' if flags else '') + MISSING}
                        assert actual == expected, (case['id'], host, 'generation result differs')
                        assert snapshot(folder) == initial, 'generation changed snapshot'
                        assert len(connections) == before, 'generation connected to origin'
                        if existing:
                            files = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob('*') if p.is_file()}
                            assert files, 'no generated files'
                            assert all(TOKEN.encode() not in data for data in files.values()), 'credential leaked'
                            if not flags:
                                generated.append(files)
                        else:
                            assert not output.exists(), 'failed generation published files'
                        record['runs'].append(dict(host=host, operation='flags' if flags else 'generate',
                                                   existingSnapshot=existing, **actual, seconds=elapsed, connections=0))
                if len(generated) == 2:
                    assert generated[0] == generated[1], 'generated bytes differ'
    return time.monotonic() - started


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', action='append')
    parser.add_argument('--oracle', action='store_true', help='record Java independently of fixed expectations')
    args = parser.parse_args()
    assert not args.oracle or not args.rust, 'oracle must use Java only'
    records = []
    elapsed = None
    try:
        with protect_real_credentials(), tempfile.TemporaryDirectory(
                prefix='package-transport-', dir=os.environ.get('TMPDIR')) as temporary:
            elapsed = check(Path(temporary), args, records)
    finally:
        args.output.write_text(json.dumps({'seconds': elapsed, 'cases': records}, indent=2) + '\n')


if __name__ == '__main__':
    main()
