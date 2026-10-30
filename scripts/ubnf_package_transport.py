"""Java-reference HTTPS transport fixtures: local TLS, private receipts, real CLIs."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import json
import os
from pathlib import Path
import queue
import re
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
COUNT = 175
URI_COUNT = 133
UNICODE_COUNT = 80
TOKEN = 'transport-fixture-only-token'
BODY = b'transport-fixture-private-response'
HEADER = 'transport-fixture-private-header'
TRANSPORT = 'HTTPS artifact retrieval failed (TLS, timeout or size limit)'
LITERAL = '127.0.0.10'
STATUS = 'HTTPS artifact requires status 200; redirects are forbidden'
MISSING = 'E-PACKAGE: missing lock; run unlaxer deps resolve --manifest ubnf.json'
# Product deadlines (connect 5 s, whole call 20 s) are measured unchanged. These
# shapes only wait, so all of their invocations run concurrently in their own folders.
SLOW = {'stall': (19.5, 45), 'connect-stall': (4.5, 19)}
NO_BODY = [101, 204, 304]


def fixtures():
    cases = json.loads((ROOT / 'spec-corpus/package-https-transport.json').read_text())
    assert len(cases) == len({c['id'] for c in cases}) == COUNT, 'transport fixture missing'
    uri = [c for c in cases if c['kind'] == 'uri']
    assert len(uri) == URI_COUNT and sum(c.get('unicode960', False) for c in uri) == UNICODE_COUNT
    # Only the separately tracked NFC -> backtick backend boundary has host errors.
    assert {c['id'] for c in uri if 'rustError' in c} == {'uri-unicode-grave', 'uri-unicode-grave-mixed'}
    assert all(c.get('backendIssue') == 979 for c in uri if 'rustError' in c)
    assert not any('rustTargetSuffix' in c for c in uri), 'no raw Unicode targets'
    # Generation never fetches: the boundary is checked on a success and a redirect shape.
    generated = [c for c in cases if c.get('generate')]
    assert len(generated) == 4 and {c['error'] is None for c in generated} == {True, False}
    assert {c['kind'] for c in cases} >= set(SLOW), 'deadline fixtures missing'
    return cases


@contextmanager
def transport_server(directory, artifact):
    # Reuse the existing certificate setup; this server supplies deliberately broken
    # HTTP framing that BaseHTTPRequestHandler / HttpsServer cannot express.
    # 127.0.0.10 is the decimal reading Java gives `127.0.0.010` (getaddrinfo: octal 8).
    context = local_tls_context(directory, 'DNS:localhost,IP:' + LITERAL)
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
                    target = lines[0].split(b' ')[1]
                    # URI fixtures have a public case ID as their first path component.
                    path = target.decode('utf-8')
                    case_id = path[1:].split('/', 1)[0]
                    if case_id not in cases:
                        case_id = path[1:]
                    auth = [line.split(b':', 1)[1].strip() for line in lines[1:]
                            if line.lower().startswith(b'authorization:')]
                    authorized = auth == [('Bearer ' + TOKEN).encode()]
                    received = {'case': case_id, 'authorization':
                                'bearer' if authorized else 'absent' if not auth else 'unexpected'}
                    if case_id in cases and cases[case_id]['kind'] == 'uri':
                        received['targetHex'] = target.hex()
                        if 'hostHeader' in cases[case_id]:
                            received['host'] = [line.split(b':', 1)[1].strip().decode('ascii') for line in lines[1:]
                                                if line.lower().startswith(b'host:')]
                    receipts.append(received)
                    # A followed redirect is a test failure, including same-origin redirects.
                    if path == '/redirect-target':
                        stream.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n')
                        return
                    case = cases[case_id]
                    kind = case['kind']
                    if kind == 'disconnect':
                        return
                    status = case.get('status', 401 if kind == 'auth' and not authorized else 200)
                    data = (artifact + b' ' * (LIMIT - len(artifact))) if kind in ['limit', 'chunked-limit', 'close-limit'] else artifact
                    if kind in ['oversize', 'chunked-oversize', 'close-oversize']:
                        data = BODY + b' ' * (LIMIT + 1 - len(BODY))
                    framing = case.get('framing')
                    if kind == 'status' or kind == 'auth':
                        # 204 with a declared body: java.net.http fails the exchange (#956).
                        data = BODY if framing or status not in NO_BODY else b''
                    if kind == 'empty':
                        data = b''
                    if kind == 'chunked-truncated':
                        data = BODY
                    headers = [f'HTTP/1.1 {status} Fixture', 'Connection: close', 'X-Fixture: ' + HEADER]
                    if 300 <= status < 400:
                        headers.append(f'Location: https://localhost:{server.server_address[1]}/redirect-target')
                    chunked = kind.startswith('chunked-') or framing == 'chunked'
                    if chunked:
                        headers.append('Transfer-Encoding: chunked')
                    elif kind not in ['close-oversize', 'close-limit']:
                        length = len(data) + 1 if kind == 'truncated' else 1 if kind == 'stall' else len(data)
                        headers.append('Content-Length: ' + str(length))
                    stream.sendall(('\r\n'.join(headers) + '\r\n\r\n').encode())
                    if kind == 'stall':
                        stop.wait(90)  # Only the unmodified 20-second product deadline ends the call.
                        return
                    if chunked:
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

    with Server(('127.0.0.1', 0), Handler) as server, socket.socket() as refused, \
            socket.socket() as blackhole, Server((LITERAL, server.server_address[1]), Handler) as literal:
        refused.bind(('127.0.0.1', 0))  # Reserved but not listening: deterministic refusal.
        # Never accepted, and its queue is filled first: later SYNs get no answer, so only
        # the 5-second connect deadline ends the call (a queued one stalls in the handshake,
        # which the same deadline bounds in both clients).
        blackhole.bind(('127.0.0.1', 0))
        blackhole.listen(0)
        fillers = []
        for _ in range(4):
            filler = socket.socket()
            filler.setblocking(False)
            filler.connect_ex(blackhole.getsockname())
            fillers.append(filler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        literal_thread = threading.Thread(target=literal.serve_forever, daemon=True)
        literal_thread.start()
        try:
            yield (server.server_address[1], refused.getsockname()[1], blackhole.getsockname()[1],
                   ca, receipts, connections)
        finally:
            stop.set()
            for filler in fillers:
                filler.close()
            server.shutdown()
            literal.shutdown()
            thread.join(timeout=5)
            literal_thread.join(timeout=5)
            assert not thread.is_alive() and not literal_thread.is_alive(), 'transport server did not stop'


def public(output, stderr, code):
    assert all(value not in output for value in [TOKEN.encode(), BODY, HEADER.encode()]), 'private value leaked'
    last = (stderr.strip().splitlines() or [''])[-1]
    # Evidence contains only known public diagnostics, never an arbitrary server/JDK message.
    allowed = ['', MISSING, 'Generation failed: ' + MISSING] + ['E-PACKAGE: ' + e for e in
              [TRANSPORT, STATUS, 'expected object: document', 'invalid JSON',
               'invalid HTTPS artifact URL', 'invalid artifact credentials',
               'HTTPS artifact URL must omit credentials, query and fragment',
               'HTTPS artifact retrieval failed (TLS, timeout, status or redirect)',
               'HTTPS artifact retrieval failed (timeout or size limit)']]
    assert last in allowed, 'unexpected diagnostic (withheld to protect response/header values)'
    return {'exit': code, 'lastError': last}


def invoke(command, options, env, deadline=28):
    tick = time.monotonic()
    try:
        result = subprocess.run(command + options, capture_output=True, env=env, timeout=deadline)
    except subprocess.TimeoutExpired:
        raise AssertionError('transport process exceeded product deadline') from None
    actual = public(result.stdout + result.stderr, result.stderr.decode('utf-8'), result.returncode)
    return actual, time.monotonic() - tick


@contextmanager
def java_host(args, env, directory):
    """One JVM runs every sequential Java `deps resolve` (CodegenBatchHost); it re-reads
    the credentials override per call, so each case only rewrites that file."""
    process = subprocess.Popen([args.java, '--enable-preview', '-Djava.io.tmpdir=' + str(directory),
                                '-cp', args.classpath, 'org.unlaxer.dsl.CodegenBatchHost'],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    lines = queue.Queue()
    reader = threading.Thread(target=lambda: [lines.put(line) for line in process.stdout] + [lines.put(None)],
                              daemon=True)
    reader.start()
    stray = []  # Drained concurrently so a chatty JVM cannot block on a full pipe.
    drain = threading.Thread(target=lambda: stray.append(process.stderr.read()), daemon=True)
    drain.start()

    def call(options):
        tick = time.monotonic()
        process.stdin.write((json.dumps(options) + '\n').encode())
        process.stdin.flush()
        try:
            line = lines.get(timeout=28)
        except queue.Empty:
            raise AssertionError('transport process exceeded product deadline') from None
        assert line is not None, 'Java fixture host stopped'
        answer = json.loads(line)
        actual = public((answer['stdout'] + answer['stderr']).encode(), answer['stderr'], answer['exit'])
        return actual, time.monotonic() - tick

    try:
        yield call
    finally:
        process.stdin.close()
        try:
            code = process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            raise AssertionError('Java fixture host did not stop') from None
        drain.join(timeout=5)
        assert all(value not in b''.join(stray) for value in [TOKEN.encode(), BODY, HEADER.encode()]), \
            'private value leaked'
        assert code == 0, 'Java fixture host failed'


def pinned(artifact):
    return {'ubnf.lock.json': lock_bytes({'std/layout': artifact}, {'std/layout': '1.0.0'}).hex(),
            '.ubnf-cache/': 'directory', '.ubnf-cache/packages/': 'directory',
            '.ubnf-cache/packages/' + digest(artifact) + '.json': artifact.hex()}


def prepare(folder, case, ports, ca, transitive=False):
    port, refused, blackhole = ports
    folder.mkdir(parents=True)
    origin = 'https://' + case.get('hostname', 'localhost') + ':' + str(
        refused if case['kind'] == 'refused' else blackhole if case['kind'] == 'connect-stall' else port)
    entry = {}
    if case.get('trust', True):
        entry['caCertificate'] = str(ca)
    if case.get('token', True):
        entry['bearerToken'] = TOKEN
    if case.get('invalidToken'):
        entry['bearerToken'] = ''  # Accepted URI; stop before DNS / a fixed port is contacted.
    credential_origin = case.get('credentialOrigin', origin).format(port=port)
    credentials = encode({credential_origin: entry, origin + '9': {'bearerToken': TOKEN}})
    (folder / 'credentials.json').write_bytes(credentials)
    manifest = folder / 'ubnf.json'
    source = case.get('url', origin + '/' + case['id']).format(port=port, id=case['id'])
    dependency = {'version': '1.0.0', 'source': source}
    if transitive:
        parent = parent_bytes(source, case.get('rawUnicode', False))
        (folder / 'parent.json').write_bytes(parent)
        dependencies = {'test/parent': {'version': '1.0.0', 'source': 'local:parent.json'}}
    else:
        dependencies = {'std/layout': dependency}
    manifest.write_bytes(json.dumps({'schemaVersion': 1, 'dependencies': dependencies},
                                   ensure_ascii=not case.get('rawUnicode', False),
                                   separators=(',', ':')).encode())
    return manifest, credentials


def parent_bytes(source, raw_unicode=False):
    return json.dumps({'schemaVersion': 1, 'id': 'test/parent', 'version': '1.0.0',
                   'entry': 'parent.ubnf', 'files': {'parent.ubnf': "grammar Parent { @root Start ::= 'a'; }"},
                   'dependencies': {'std/layout': {'version': '1.0.0', 'source': source}}},
                      ensure_ascii=not raw_unicode, separators=(',', ':')).encode()


def expected_receipt(case, host='java', port=None, java17=False):
    if case['kind'] == 'uri':
        if (case.get('rustError', case['error']) if host == 'rust' else case['error']):
            return None
        suffix = case.get('targetSuffix', '')
        if host == 'java' and java17:
            suffix = case.get('javaJdk17TargetSuffix', suffix)
        wanted = {'case': case['id'], 'authorization': 'bearer',
                  'targetHex': ('/' + case['id'] + suffix).encode().hex()}
        if 'hostHeader' in case:  # Java sends getHost() as written, plus a non-default port.
            wanted['host'] = [case['hostHeader'].format(port=port)]
        return wanted
    if not case.get('trust', True) or 'hostname' in case or case['kind'] in ['refused', 'connect-stall']:
        return None  # The request must not pass the TCP/TLS boundary.
    return {'case': case['id'], 'authorization': 'bearer' if case.get('token', True) else 'absent'}


def check(directory, args, records):
    artifact = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    baseline = pinned(artifact)
    library = compile_passwd_shim(directory)
    started = time.monotonic()
    cases = [c for c in fixtures() if not args.case or c['id'] in args.case]
    slow_ids = {c['id'] for c in cases if c['kind'] in SLOW}
    home = directory / 'java-host'
    home.mkdir()
    host_env = dict(isolated_env(library, home, home), UBNF_CREDENTIALS_FILE=str(home / 'credentials.json'))
    java = [args.java, '--enable-preview', '-Djava.io.tmpdir=' + str(directory),
            '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']
    version = subprocess.check_output([args.java, '--version'], timeout=10).decode('ascii')
    java17 = re.search(r'\b17\.', version) is not None

    def resolved(case, existing, transitive, folder):
        if transitive:
            parent = (folder / 'parent.json').read_bytes()
            blobs = {'std/layout': artifact, 'test/parent': parent}
            result = pinned(artifact)
            result['ubnf.lock.json'] = lock_bytes(blobs, {'test/parent': '1.0.0'}).hex()
            result['.ubnf-cache/packages/' + digest(parent) + '.json'] = parent.hex()
            return result
        if case['kind'] in ['limit', 'chunked-limit', 'close-limit']:
            wanted = pinned(artifact + b' ' * (LIMIT - len(artifact)))
            if existing:  # The old cache entry is kept beside the new pinned artifact.
                wanted['.ubnf-cache/packages/' + digest(artifact) + '.json'] = artifact.hex()
            return wanted
        return pinned(artifact)

    def verify(case, host, existing, folder, actual, elapsed, transitive=False):
        error = case.get('rustError', case['error']) if host == 'rust' else case['error']
        expected = {'exit': 3 if error else 0, 'lastError': 'E-PACKAGE: ' + error if error else ''}
        if not args.oracle:
            assert actual == expected, (case['id'], host, 'diagnostic/exit differs', actual)
        after = snapshot(folder)
        assert after == ((baseline if existing else {}) if actual['exit'] else resolved(case, existing, transitive, folder)), \
            (case['id'], host, 'snapshot differs')
        return dict(host=host, operation='resolve', existingSnapshot=existing, transitive=transitive, **actual,
                    seconds=elapsed, snapshot=receipt(after))

    with transport_server(directory, artifact) as (port, refused, blackhole, ca, received, connections), \
            java_host(args, host_env, directory) as batch, ThreadPoolExecutor(max_workers=8) as pool:
        ports = (port, refused, blackhole)

        def slow_run(case, host, existing):
            folder = directory / case['id'] / f'{host}-{existing}'
            manifest, _ = prepare(folder, case, ports, ca)
            reset(folder, baseline if existing else {})
            env = dict(isolated_env(library, folder, folder), UBNF_CREDENTIALS_FILE=str(folder / 'credentials.json'))
            command = java if host == 'java' else [args.rust]
            if host == 'rust':
                env.update(PATH='', JAVA_HOME='/nonexistent-java')
            low, high = SLOW[case['kind']]
            actual, elapsed = invoke(command, ['deps', 'resolve', '--manifest', str(manifest)], env, high)
            assert elapsed >= low, (case['id'], host, 'finished before the product deadline')
            return verify(case, host, existing, folder, actual, elapsed)

        hosts = ['java'] + (['rust'] if args.rust else [])
        # Deadline shapes use the real CLIs (Java too) and overlap with the sequential shapes.
        waiting = [(case, pool.submit(slow_run, case, host, existing))
                   for case in cases if case['id'] in slow_ids for existing in [False, True] for host in hosts]
        record_of = {}
        for case in cases:
            record_of[case['id']] = {'case': case['id'], 'generate': bool(case.get('generate')), 'runs': []}
            records.append(record_of[case['id']])
            if case['id'] in slow_ids:
                continue
            print('HTTPS transport:', case['id'], flush=True)
            for transitive in ([False, True] if case['kind'] == 'uri' else [False]):
                folder = directory / case['id'] / str(transitive)
                manifest, credentials = prepare(folder, case, ports, ca, transitive)
                raw_manifest = manifest.read_bytes()
                raw_parent = (folder / 'parent.json').read_bytes() if transitive else None
                env = dict(isolated_env(library, folder, folder), UBNF_CREDENTIALS_FILE=str(folder / 'credentials.json'))
                for existing in [False, True]:
                    for host in hosts:
                        reset(folder, baseline if existing else {})
                        before = len(received)
                        connected = len(connections)
                        options = ['deps', 'resolve', '--manifest', str(manifest)]
                        if host == 'java':
                            (home / 'credentials.json').write_bytes(credentials)
                            actual, elapsed = batch(options)
                        else:
                            actual, elapsed = invoke([args.rust], options, dict(env, PATH='', JAVA_HOME='/nonexistent-java'))
                        run = verify(case, host, existing, folder, actual, elapsed, transitive)
                        assert manifest.read_bytes() == raw_manifest, 'resolve rewrote raw source URL'
                        if transitive:
                            assert (folder / 'parent.json').read_bytes() == raw_parent, 'resolve rewrote parent source'
                        receipts = [r for r in received[before:] if r.get('case') not in slow_ids]
                        wanted = expected_receipt(case, host, port, java17)
                        if wanted is None:
                            assert not receipts, (case['id'], host, 'request passed TLS boundary')
                            if case['kind'] == 'uri':
                                assert len(connections) == connected, (case['id'], host, 'rejected URI connected')
                        else:
                            assert receipts and all(r == wanted for r in receipts), \
                                (case['id'], host, 'request/auth/redirect/target differs')
                        wire = {'connections': len(connections) - connected,
                                'targets': [r['targetHex'] for r in receipts],
                                'sourceDocumentSha256': digest(raw_manifest),
                                'hostChecked': bool(receipts and 'hostHeader' in case)} if case['kind'] == 'uri' else {}
                        record_of[case['id']]['runs'].append(dict(run, requests=len(receipts), **wire))
        for case, future in waiting:
            print('HTTPS transport (deadline):', case['id'], flush=True)
            record_of[case['id']]['runs'].append(future.result())
        for case in cases:
            if case['id'] in slow_ids:
                receipts = [r for r in received if r.get('case') == case['id']]
                wanted = expected_receipt(case)
                count = len(hosts) * 2 if wanted else 0
                assert len(receipts) == count and all(r == wanted for r in receipts), \
                    (case['id'], 'request/auth differs')
        assert not any('serverError' in r or r.get('case') == 'redirect-target' for r in received)
        # Generation is read-only: lock/cache only, never the origin, with and without a pin.
        for case in cases:
            if not case.get('generate'):
                continue
            folder = directory / case['id'] / 'False'
            grammar = folder / 'main.ubnf'
            grammar.write_text("grammar Main { @import layout from 'pkg:std/layout' @ubnf: v2 "
                               "@root @mapping(Value) Start ::= 'a'; }\n")
            env = dict(isolated_env(library, folder, folder), UBNF_CREDENTIALS_FILE=str(folder / 'credentials.json'))
            commands = [('java', java, env)]
            if args.rust:
                commands.append(('rust', [args.rust], dict(env, PATH='', JAVA_HOME='/nonexistent-java')))
            for existing in [False, True]:
                initial = baseline if existing else {}
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
                        record_of[case['id']]['runs'].append(dict(
                            host=host, operation='flags' if flags else 'generate', existingSnapshot=existing,
                            **actual, seconds=elapsed, connections=0))
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
