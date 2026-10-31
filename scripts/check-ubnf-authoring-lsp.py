#!/usr/bin/env python3
"""実 Java/Rust stdio server へ共通 fixture を送る。診断の残差は明示する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import signal
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PARAMS = object()
OMIT_PARAMS = object()


class Client:
    def __init__(self, command, native=False, backend=None):
        self.command = list(command)
        env = dict(os.environ)
        if native:
            env.update(PATH='', JAVA_HOME='/nonexistent-java')
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        self.backend = backend or ('rust' if native else 'java')
        self.fixture = 'initialization'
        self.last_request = 'none'
        self.stderr_tail = bytearray()
        self.stderr_lock = threading.Lock()
        self.messages = queue.Queue()
        self.serial = 0
        self.pending = []
        self.timeout = 20
        self.stderr_reader = threading.Thread(target=self.read_stderr, daemon=True)
        self.stderr_reader.start()
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read_stderr(self):
        # Drain continuously: a verbose failing server must never fill its pipe.
        while chunk := self.process.stderr.read1(1024):
            with self.stderr_lock:
                self.stderr_tail.extend(chunk)
                del self.stderr_tail[:-4096]

    def failure(self, reason):
        code = self.process.poll()
        if code is None and reason != 'response timeout':
            try:
                code = self.process.wait(timeout=0.2)
            except subprocess.TimeoutExpired:
                pass
        if code is not None:
            self.stderr_reader.join(timeout=1)
        status = 'running' if code is None else str(code)
        if code is not None and code < 0:
            try:
                status += ' (' + signal.Signals(-code).name + ')'
            except ValueError:
                pass
        with self.stderr_lock:
            tail = bytes(self.stderr_tail).decode('utf-8', errors='replace')
        # Stderr may echo grammar bodies or credentials. Report only recognized
        # resource/transport errors and exception types, never arbitrary payloads.
        safe = []
        for line in tail.splitlines():
            resource = re.search(r'OutOfMemoryError(?:: (?:Java heap space|GC overhead limit exceeded))?|'
                                 r'Cannot allocate memory|memory allocation of \d+ bytes failed|'
                                 r'unable to create native thread|Could not reserve enough space|'
                                 r'Broken pipe|StackOverflowError|panicked at', line)
            exception = re.match(r'^(?:Exception in thread "[^"\n]{1,40}" )?'
                                 r'((?:java\.|org\.|com\.)[\w.$]*(?:Exception|Error))\b', line)
            safe.append(resource.group() if resource else exception.group(1) if exception else '[stderr content omitted]')
        excerpt = '\n'.join(safe).encode()[-4096:].decode(errors='replace') or '(empty)'
        if reason == 'response timeout':
            reason += f' after {self.timeout}s'
        return RuntimeError(f'authoring server: backend={self.backend} fixture={self.fixture} '
                            f'last_request={self.last_request} exit={status}: {reason}\n'
                            f'stderr tail (sanitized, <=4096 bytes):\n{excerpt}')

    def read(self):
        try:
            while True:
                headers = {}
                while True:
                    line = self.process.stdout.readline()
                    if not line:
                        raise EOFError('server closed stdout')
                    if line == b'\r\n':
                        break
                    key, value = line.decode().split(':', 1)
                    headers[key.lower()] = value.strip()
                count = int(headers['content-length'])
                data = self.process.stdout.read(count)
                self.messages.put(json.loads(data))
        except Exception as error:
            self.messages.put(error)

    def send(self, method, params=DEFAULT_PARAMS, request=False):
        previous = self.last_request
        self.last_request = f'{method} (previous: {previous.split(" (previous:")[0]})'
        if self.process.poll() is not None:
            raise self.failure('unexpected process exit before send')
        message = {'jsonrpc': '2.0', 'method': method}
        if request:
            self.serial += 1
            message['id'] = self.serial
        if params is not OMIT_PARAMS:
            message['params'] = {} if params is DEFAULT_PARAMS else params
        data = json.dumps(message, ensure_ascii=False).encode()
        try:
            self.process.stdin.write(f'Content-Length: {len(data)}\r\n\r\n'.encode() + data)
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise self.failure('transport write failed') from error
        return self.serial

    def send_raw(self, frame):
        """Send authored JSON text unchanged, preserving order/duplicates/numbers."""
        self.last_request = 'raw frame: ' + self.fixture
        if self.process.poll() is not None:
            raise self.failure('unexpected process exit before send')
        data = frame.encode('utf-8')
        try:
            self.process.stdin.write(f'Content-Length: {len(data)}\r\n\r\n'.encode() + data)
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise self.failure('transport write failed') from error

    def receive(self, predicate):
        for i, item in enumerate(self.pending):
            if predicate(item):
                return self.pending.pop(i)
        deadline = time.monotonic() + self.timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise self.failure('response timeout')
            try:
                item = self.messages.get(timeout=remaining)
            except queue.Empty as error:
                raise self.failure('response timeout') from error
            if isinstance(item, Exception):
                # Parser exceptions can embed an arbitrary header/body in their
                # message. Keep transport context without echoing that payload.
                raise self.failure('server closed stdout or invalid protocol frame '
                                   f'({type(item).__name__})') from None
            if predicate(item):
                return item
            self.pending.append(item)

    def request(self, method, params=DEFAULT_PARAMS):
        serial = self.send(method, params, True)
        return self.receive(lambda message: message.get('id') == serial)

    def result(self, method, params=DEFAULT_PARAMS):
        response = self.request(method, params)
        assert 'result' in response, response
        return response['result']

    def diagnostics_params(self, uri):
        message = self.receive(lambda m: m.get('method') == 'textDocument/publishDiagnostics' and m['params']['uri'] == uri)
        params = message['params']
        assert message == {'jsonrpc': '2.0', 'method': 'textDocument/publishDiagnostics', 'params': params}, message
        return params

    def diagnostics(self, uri):
        params = self.diagnostics_params(uri)
        # Check every params field, including version and unexpected keys, even
        # in older suites whose independent oracle is a diagnostics array.
        assert params == {'uri': uri, 'version': 0, 'diagnostics': params['diagnostics']}, params
        return params['diagnostics']

    def close(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=5)
        # Buffered stdin may retry a failed write on close. Preserve the original
        # diagnostic and always release both servers, including after BrokenPipe.
        try:
            self.process.stdin.close()
        except OSError:
            pass
        self.reader.join(timeout=1)
        self.stderr_reader.join(timeout=1)
        self.process.stdout.close()
        self.process.stderr.close()


def position(source, offset):
    before = source[:offset]
    return {'line': before.count('\n'), 'character': len(before.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2}


def initialize(java, native):
    clients = [java, native]
    for client in clients:
        assert client.request('textDocument/hover')['error']['code'] == -32002
        cap = client.result('initialize', {'capabilities': {}})['capabilities']
        assert cap['textDocumentSync'] == 1 and cap['hoverProvider'] is True, cap
        assert cap['completionProvider'] == {'resolveProvider': False, 'triggerCharacters': ['@', '=', '.']}, cap
        if client is native:
            assert set(cap) == {'textDocumentSync', 'hoverProvider', 'completionProvider', 'definitionProvider', 'referencesProvider', 'renameProvider', 'documentSymbolProvider', 'foldingRangeProvider', 'linkedEditingRangeProvider', 'experimental'}, cap
            for key in ['definitionProvider', 'referencesProvider', 'renameProvider', 'documentSymbolProvider', 'foldingRangeProvider', 'linkedEditingRangeProvider']:
                assert cap[key] is True, cap
        else:
            for key in ['definitionProvider', 'referencesProvider', 'renameProvider', 'documentSymbolProvider', 'codeActionProvider', 'foldingRangeProvider', 'linkedEditingRangeProvider']:
                assert cap[key] is True, cap
            assert cap['signatureHelpProvider']['triggerCharacters'] == ['(', ',']
            assert cap['semanticTokensProvider'] == {'full': True, 'legend': {'tokenTypes': ['keyword', 'type', 'property', 'macro', 'string', 'number', 'operator', 'comment', 'function', 'namespace'], 'tokenModifiers': ['declaration']}}, cap
            assert set(cap) == {'textDocumentSync', 'hoverProvider', 'completionProvider', 'definitionProvider', 'referencesProvider', 'renameProvider', 'documentSymbolProvider', 'codeActionProvider', 'foldingRangeProvider', 'linkedEditingRangeProvider', 'signatureHelpProvider', 'semanticTokensProvider', 'experimental'}, cap
        assert client.request('initialize')['error']['code'] == -32600
        client.send('initialized')
    assert native.result('unlaxer/configure')['capabilities']['experimental'] == java.result('unlaxer/configure')['capabilities']['experimental']
    assert native.result('ubnf/packageSource', {'uri': 'ubnf-package:/unknown'}) is None
    for method in ['codeAction', 'signatureHelp', 'semanticTokens/full']:
        assert native.request('textDocument/' + method)['error']['code'] == -32601


def run(java, native, directory):
    initialize(java, native)
    clients = [java, native]
    cases = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/cases.json').read_text())
    evidence = []
    for case in cases:
        for client in clients:
            client.fixture = case['id']
        folder = directory / case['id']
        folder.mkdir()
        for name, text in case.get('files', {}).items():
            (folder / name).write_text(text)
        uri = (folder / 'main space 😀.ubnf').as_uri()
        source = case['source']
        offset = source.index(case['marker']) + (len(case['marker']) if case.get('after') else max(1, len(case['marker'].split()[0].rstrip('.'))))
        hover_offset = source.index(case['marker'])
        # Explicit LSP positions cover inputs a marker cannot name (past line/document end).
        params = {'textDocument': {'uri': uri}, 'position': case.get('position', position(source, offset))}
        hover_position = case.get('hoverPosition', position(source, hover_offset))
        completions, hovers, diagnostics = [], [], []
        for client in clients:
            client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
            diagnostic = client.diagnostics(uri)
            diagnostics.append(diagnostic)
            completion = client.result('textDocument/completion', params)
            completions.append(completion)
            labels = [item['label'] for item in completion]
            assert set(case['labels']) <= set(labels), (case['id'], case['labels'], labels)
            assert not set(case.get('absent', [])) & set(labels), (case['id'], labels)
            if case.get('syntaxError'):
                assert any(d['code'] == 'ULX-PARSE-001' and d['severity'] == 1 for d in diagnostic), (case['id'], diagnostic)
            elif case.get('moduleError'):
                assert any(d['code'] == 'E-MODULE' and d['severity'] == 1 for d in diagnostic), (case['id'], diagnostic)
            elif 'diagnostics' in case:
                assert [d['code'] for d in diagnostic] == case['diagnostics'], (case['id'], diagnostic)
            if 'hoverContains' in case:
                hover = client.result('textDocument/hover', {**params, 'position': hover_position})
                hovers.append(hover)
                expected = case.get('nativeHoverContains', case['hoverContains']) if client is native else case['hoverContains']
                assert expected in hover['contents']['value'], (case['id'], hover)
        assert completions[0] == completions[1], (case['id'], 'completion mismatch', [(i, a, b) for i, (a, b) in enumerate(zip(completions[0], completions[1])) if a != b][:2], [len(c) for c in completions])
        if hovers and 'nativeHoverContains' not in case:
            assert hovers[0] == hovers[1], (case['id'], 'hover mismatch', hovers)
        if 'diagnostics' in case:
            assert diagnostics[0] == diagnostics[1], (case['id'], 'diagnostic mismatch', diagnostics)
        if case.get('moduleError'):
            # #932: local import I/O and cycle messages also match Java (absolute path, no errno).
            select = lambda ds: [{k: d[k] for k in ('code', 'severity', 'source', 'range', 'message')} for d in ds if d['code'] == 'E-MODULE']
            assert select(diagnostics[0]) == select(diagnostics[1]), (case['id'], diagnostics)
        for client in clients:
            fixed = "grammar Fixed { @root @mapping(Value) Root ::= 'a'; }"
            client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 2}, 'contentChanges': [{'text': fixed}]})
            assert client.diagnostics(uri) == []
            changed = client.result('textDocument/completion', {'textDocument': {'uri': uri}, 'position': position(fixed, fixed.index('Root') + 2)})
            assert any(c['label'] == 'Root' for c in changed)
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            assert client.result('textDocument/hover', params) is None
            assert client.result('textDocument/completion', params) == []
        evidence.append({'case': case['id'], 'completionItems': len(completions[0]), 'diagnosticContract': 'syntax-accept-reject-only' if case.get('syntaxError') else 'module-exact' if case.get('moduleError') else 'validator-pending' if case.get('validatorGap') else 'exact'})
    evidence.extend(module_rejections(clients, directory))
    evidence.extend(import_buffers(clients, directory))
    evidence.extend(packages(clients, directory))
    evidence.extend(validator_diagnostics(clients, directory))
    for client in clients:
        assert client.result('shutdown') is None
        assert client.request('textDocument/hover')['error']['code'] == -32600
        client.send('exit')
        assert client.process.wait(timeout=10) == 0
    return evidence


def module_rejections(clients, directory):
    """#988/#999: the same independent CLI fixtures pin full authoring diagnostics."""
    from cli_input_errors import setup, snapshot
    from cli_stderr import module_fixture
    cases = [case for case in json.loads((ROOT / 'spec-corpus/cli-stderr.json').read_text())['cases']
             if case.get('moduleRejection')]
    assert len(cases) == 24, 'module rejection fixtures must not silently disappear'
    evidence = []
    for case in cases:
        for ending, separator in [('LF', '\n'), ('CRLF', '\r\n')]:
            folder = directory / (case['id'] + '-' + ending)
            with module_fixture(case, folder) as prepared:
                setup(folder, prepared, separator)
                before = snapshot(folder)
                uri = (folder / case.get('lspGrammar', 'main.ubnf')).as_uri()
                source = prepared['files']['main.ubnf'].replace('\n', separator)
                expected = json.loads(json.dumps(prepared['lspExpected'], ensure_ascii=False)
                                      .replace('${WORK}', str(folder)))
                observations = []
                for host, client in zip(('java', 'rust'), clients):
                    client.send('textDocument/didOpen', {'textDocument': {
                        'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
                    actual = client.diagnostics(uri)
                    assert actual == expected, (case['id'], ending, host, actual, expected)
                    observations.append(dict(host=host, diagnostics=actual))
                    client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 2},
                                'contentChanges': [{'text': "grammar Fixed { @root Start ::= 'x'; }"}]})
                    assert client.diagnostics(uri) == []
                    assert client.result('textDocument/completion', {'textDocument': {'uri': uri},
                        'position': {'line': 0, 'character': 37}}) is not None
                    client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
                    assert snapshot(folder) == before, (case['id'], ending, host, 'filesystem changed')
                evidence.append(dict(case=case['id'], ending=ending, observations=observations,
                                     before=before, after=snapshot(folder)))
            if case.get('networkProbe'):
                evidence[-1]['networkConnections'] = 0
    return evidence


def validator_diagnostics(clients, directory):
    """Independent authored full JSON, including absent data/relatedInformation fields."""
    cases = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/diagnostics.json').read_text())
    assert len(cases) == 76, 'validator fixtures must not silently disappear'
    features = json.loads((ROOT / 'spec-corpus/feature-diagnostics/authoring.json').read_text())
    assert len(features) == 24, 'feature fixtures must not silently disappear'
    cases += features
    evidence, failures = [], []
    for case in cases:
        for client in clients:
            client.fixture = case['id']
        uri = (directory / (case['id'] + ' 😀.ubnf')).as_uri()
        for host, client in zip(('java', 'rust'), clients):
            client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'ubnf', 'version': 8, 'text': case['source']}})
            actual = client.diagnostics(uri)
            if actual != case['expected']:
                failures.append({'case': case['id'], 'host': host, 'expected': case['expected'], 'actual': actual})
            # Java applies every Full change, even equal/decreasing versions. Check
            # replacement and diagnostic invalidation with a second open document.
            peer = uri + '.peer'
            fixed = "grammar Fixed { @root Start ::= 'x'; }"
            client.send('textDocument/didOpen', {'textDocument': {'uri': peer, 'languageId': 'ubnf', 'version': 1, 'text': fixed}})
            assert client.diagnostics(peer) == []
            for version, text, expected in [(8, fixed, []), (7, case['source'], case['expected']), (6, fixed, [])]:
                client.send('textDocument/didChange', {'textDocument': {'uri': uri, 'version': version}, 'contentChanges': [{'text': text}]})
                actual = client.diagnostics(uri)
                if actual != expected:
                    failures.append({'case': case['id'], 'host': host, 'version': version, 'expected': expected, 'actual': actual})
            client.send('textDocument/didSave', {'textDocument': {'uri': uri}})
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            assert client.result('textDocument/hover', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': 0}}) is None
            client.send('textDocument/didClose', {'textDocument': {'uri': peer}})
        evidence.append({'case': case['id'], 'diagnosticContract': 'exact-full-json-and-version-changes'})
    assert not failures, json.dumps(failures, ensure_ascii=False, indent=2)
    return evidence


def import_buffers(clients, directory):
    folder = directory / 'open-buffers'
    folder.mkdir()
    module = folder / 'module space.ubnf'
    disk = "grammar M { @ubnf: v2 token A ::= 'a'; }"
    buffer = "grammar M { @ubnf: v2 token B ::= 'b'; }"
    module.write_text(disk)
    uri = (folder / 'main.ubnf').as_uri()
    source = "grammar Main { @import m from 'module space.ubnf' @ubnf: v2 @root @mapping(Value) Start ::= m.A; }"
    params = {'textDocument': {'uri': uri}, 'position': position(source, source.index('m.A') + 2)}
    for client in clients:
        client.fixture = 'open-import-buffer-change-close-save'
        client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
        assert client.diagnostics(uri) == []
        client.send('textDocument/didOpen', {'textDocument': {'uri': module.as_uri(), 'languageId': 'ubnf', 'version': 1, 'text': buffer}})
        assert client.diagnostics(module.as_uri()) == []
        assert any(d['code'] == 'E-MODULE' for d in client.diagnostics(uri))
        completion = client.result('textDocument/completion', params)
        assert [c['label'] for c in completion] == ['m.B'], completion
        client.send('textDocument/didChange', {'textDocument': {'uri': module.as_uri(), 'version': 2}, 'contentChanges': [{'text': disk}]})
        assert client.diagnostics(module.as_uri()) == []
        assert client.diagnostics(uri) == []
        client.send('textDocument/didClose', {'textDocument': {'uri': module.as_uri()}})
        assert client.diagnostics(uri) == []
        assert [c['label'] for c in client.result('textDocument/completion', params)] == ['m.A']
        client.send('textDocument/didSave', {'textDocument': {'uri': module.as_uri()}})
        assert client.diagnostics(uri) == []
        client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
    assert module.read_text() == disk
    return [{'case': 'open-import-buffer-change-close-save', 'diagnosticContract': 'shared-code-and-refresh'}]


def packages(clients, directory):
    evidence = []
    for mode in ('valid', 'hash-mismatch', 'missing-lock', 'escape'):
        folder = directory / ('package-' + mode)
        folder.mkdir()
        module = "grammar Layout { @ubnf: v2 token GAP ::= ' '+; }"
        if mode == 'escape':
            module = "grammar Layout { @import bad from '../../outside.ubnf' @ubnf: v2 token GAP ::= ' '+; }"
        artifact = {'schemaVersion': 1, 'id': 'team/layout', 'version': '1.2.3', 'entry': 'layout space.ubnf', 'files': {'layout space.ubnf': module}, 'dependencies': {}}
        raw = json.dumps(artifact, ensure_ascii=False).encode()
        digest = hashlib.sha256(raw).hexdigest()
        (folder / 'ubnf.json').write_text(json.dumps({'schemaVersion': 1, 'dependencies': {'team/layout': {'version': '1.2.3', 'source': 'local:missing-artifact.json'}}}))
        lock = {'schemaVersion': 1, 'roots': {'team/layout': '1.2.3'}, 'packages': {'team/layout': {'id': 'team/layout', 'version': '1.2.3', 'sha256': digest, 'dependencies': {}}}}
        if mode != 'missing-lock':
            (folder / 'ubnf.lock.json').write_text(json.dumps(lock))
        cache = folder / '.ubnf-cache/packages'
        cache.mkdir(parents=True)
        (cache / (digest + '.json')).write_bytes(raw + (b' ' if mode == 'hash-mismatch' else b''))
        before = {str(p.relative_to(folder)): p.read_bytes() for p in folder.rglob('*') if p.is_file()}
        source = "grammar Main {\n @import layout from 'pkg:team/layout'\n @ubnf: v2\n @whitespace: layout.GAP\n @root @mapping(Value) Start ::= 'a';\n}"
        uri = (folder / 'main.ubnf').as_uri()
        params = {'textDocument': {'uri': uri}, 'position': position(source, source.index('layout.GAP') + 9)}
        all_completions, all_hovers, all_diagnostics = [], [], []
        for client in clients:
            client.fixture = 'package-' + mode
            # An open buffer at a virtual package path must not replace verified bytes.
            spoof = (folder / '.ubnf-cache/sources' / digest / 'layout space.ubnf').as_uri()
            client.send('textDocument/didOpen', {'textDocument': {'uri': spoof, 'languageId': 'ubnf', 'version': 1, 'text': "grammar Fake { @ubnf: v2 token SPOOF ::= 'x'; }"}})
            assert client.diagnostics(spoof) == []
            client.send('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': source}})
            diagnostics = client.diagnostics(uri)
            all_diagnostics.append([{k: d[k] for k in ('code', 'severity', 'source', 'range')} for d in diagnostics])
            completion = client.result('textDocument/completion', params)
            labels = [c['label'] for c in completion]
            assert 'layout.SPOOF' not in labels
            if mode == 'valid':
                assert diagnostics == [], diagnostics
                assert 'layout.GAP' in labels, labels
                hover = client.result('textDocument/hover', params)
                assert 'team/layout@1.2.3' in hover['contents']['value'] and digest in hover['contents']['value'], hover
                all_hovers.append(hover)
            else:
                assert any(d['code'] == 'E-MODULE' for d in diagnostics), (mode, diagnostics)
                assert 'layout.GAP' not in labels
            all_completions.append(completion)
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            client.send('textDocument/didClose', {'textDocument': {'uri': spoof}})
        assert all_completions[0] == all_completions[1], (mode, all_completions)
        assert all_diagnostics[0] == all_diagnostics[1], (mode, all_diagnostics)
        if all_hovers:
            assert all_hovers[0] == all_hovers[1], all_hovers
        assert before == {str(p.relative_to(folder)): p.read_bytes() for p in folder.rglob('*') if p.is_file()}, 'server wrote package files'
        evidence.append({'case': 'package-' + mode, 'diagnosticContract': 'shared-accept-reject'})
    return evidence


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--suite', choices=['baseline', 'navigation', 'local-imports', 'package-documents', 'package-artifacts', 'package-artifact-utf8', 'package-grammar-paths', 'package-json', 'configuration', 'jsonrpc'], default='baseline')
    args = parser.parse_args()
    # The 64 MiB boundary needs more than 4 GiB; bound each test JVM instead of
    # letting host-wide ergonomics reserve ~22 GiB per concurrently live JVM.
    java_command = [args.java, '-Xmx8g', '--enable-preview', '-cp', args.classpath]
    java = Client(java_command + ['org.unlaxer.dsl.lsp.ubnf.UBNFLspLauncherExt'])
    native = Client([args.rust], native=True)
    try:
        with tempfile.TemporaryDirectory(prefix='ubnf-authoring-', dir=os.environ.get('TMPDIR')) as directory:
            if args.suite == 'jsonrpc':
                from ubnf_authoring_jsonrpc import run_jsonrpc
                evidence = run_jsonrpc([java, native], Path(directory), OMIT_PARAMS)
            elif args.suite == 'configuration':
                from ubnf_authoring_configuration import run_configuration
                evidence = run_configuration([java, native], Path(directory))
            elif args.suite == 'package-json':
                from ubnf_package_json import run_lsp
                initialize(java, native)
                evidence = run_lsp([java, native], Path(directory))
                for client in [java, native]:
                    assert client.result('shutdown') is None
                    client.send('exit')
                    assert client.process.wait(timeout=5) == 0
            elif args.suite == 'package-grammar-paths':
                from ubnf_package_grammar_paths import run_lsp
                initialize(java, native)
                evidence = run_lsp([java, native], Path(directory))
                for client in [java, native]:
                    assert client.result('shutdown') is None
                    client.send('exit')
                    assert client.process.wait(timeout=5) == 0
            elif args.suite in ('package-artifacts', 'package-artifact-utf8'):
                from ubnf_package_artifacts import run_package_artifacts
                initialize(java, native)
                evidence = run_package_artifacts([java, native], Path(directory),
                    java_command + ['org.unlaxer.dsl.CodegenMain'],
                    [str(Path(args.rust).with_name('unlaxer'))], ROOT, args.suite)
                for client in [java, native]:
                    assert client.result('shutdown') is None
                    client.send('exit')
                    assert client.process.wait(timeout=5) == 0
            elif args.suite == 'package-documents':
                from ubnf_package_documents import run_package_documents
                initialize(java, native)
                evidence = run_package_documents([java, native], Path(directory),
                    java_command + ['org.unlaxer.dsl.CodegenMain'],
                    [str(Path(args.rust).with_name('unlaxer'))], ROOT)
                for client in [java, native]:
                    assert client.result('shutdown') is None
                    client.send('exit')
                    assert client.process.wait(timeout=5) == 0
            elif args.suite == 'local-imports':
                from ubnf_local_imports import run_local_imports
                initialize(java, native)
                evidence = run_local_imports([java, native], Path(directory),
                    java_command + ['org.unlaxer.dsl.CodegenMain'],
                    [str(Path(args.rust).with_name('unlaxer'))], ROOT)
            elif args.suite == 'navigation':
                from ubnf_authoring_navigation import run_navigation
                initialize(java, native)
                evidence = run_navigation([java, native], Path(directory))
                for client in [java, native]:
                    assert client.result('shutdown') is None
                    client.send('exit')
                    assert client.process.wait(timeout=5) == 0
            else:
                evidence = run(java, native, Path(directory))
        Path(args.output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
        print(f'Java/Rust authoring stdio: {len(evidence)} shared cases passed')
    finally:
        java.close()
        native.close()


if __name__ == '__main__':
    main()
