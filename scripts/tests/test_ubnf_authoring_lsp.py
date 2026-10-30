"""実 pipe で server 終了・timeout・stderr 詰まりの診断を検証する (#995)。"""
import importlib.util
from pathlib import Path
import signal
import sys
import time
import traceback
import unittest


SPEC = importlib.util.spec_from_file_location(
    'authoring_lsp', Path(__file__).resolve().parents[1] / 'check-ubnf-authoring-lsp.py')
lsp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lsp)


class AuthoringClientTest(unittest.TestCase):
    def client(self, code, backend='java'):
        client = lsp.Client([sys.executable, '-c', code], backend=backend)
        self.addCleanup(client.close)
        client.fixture = 'exact-limit'
        # Generous for a loaded shared runner; timeout tests set their own bound.
        client.timeout = 10
        return client

    def test_explicit_params_are_preserved_including_falsy_values_and_omission(self):
        # Echo the actual framed message so fixture corruption cannot turn an
        # invalid-input test into a successful default-params test.
        client = self.client("""import sys, json
while True:
    header = sys.stdin.buffer.readline()
    if not header: break
    count = int(header.split(b':')[1])
    assert sys.stdin.buffer.readline() == b'\\r\\n'
    message = json.loads(sys.stdin.buffer.read(count))
    data = json.dumps({'jsonrpc': '2.0', 'id': message['id'], 'result': message}).encode()
    sys.stdout.buffer.write(('Content-Length: %d\\r\\n\\r\\n' % len(data)).encode() + data)
    sys.stdout.buffer.flush()
""")
        for params in (None, [], False, 0, '', {}, [None], lsp.OMIT_PARAMS):
            with self.subTest(params=params):
                actual = client.result('initialize', params)
                expected = {'jsonrpc': '2.0', 'method': 'initialize', 'id': client.serial}
                if params is not lsp.OMIT_PARAMS:
                    expected['params'] = params
                self.assertEqual(expected, actual)

    def test_raw_frames_preserve_order_duplicates_unicode_and_overflow(self):
        client = self.client("""import sys, json
while True:
    header = sys.stdin.buffer.readline()
    if not header: break
    count = int(header.split(b':')[1])
    assert sys.stdin.buffer.readline() == b'\\r\\n'
    raw = sys.stdin.buffer.read(count).decode('utf-8')
    data = json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': raw}).encode()
    sys.stdout.buffer.write(('Content-Length: %d\\r\\n\\r\\n' % len(data)).encode() + data)
    sys.stdout.buffer.flush()
""")
        frame = '{"params":{"text":"😀","line":1e400},"method":"first","method":"last","id":null}'
        client.send_raw(frame)
        self.assertEqual({'jsonrpc': '2.0', 'id': 1, 'result': frame}, client.receive(lambda _: True))

    def test_diagnostic_envelope_rejects_missing_wrong_or_extra_version_fields(self):
        for extra in ({}, {'version': 1}, {'version': 0, 'extra': True}):
            with self.subTest(extra=extra):
                client = self.client('import time; time.sleep(10)')
                client.messages.put({'jsonrpc': '2.0', 'method': 'textDocument/publishDiagnostics',
                                     'params': {'uri': 'file:///test.ubnf', 'diagnostics': [], **extra}})
                with self.assertRaises(AssertionError):
                    client.diagnostics('file:///test.ubnf')

    def test_exit_before_shutdown_reports_backend_fixture_request_status_and_safe_tail(self):
        for backend in ('java', 'rust'):
            with self.subTest(backend=backend):
                client = self.client(
                    "import sys; sys.stderr.write('token=secret-value\\n'"
                    "+ 'grammar Private { secret body }\\n'"
                    "+ 'thread main panicked at secret-value\\n'"
                    "+ 'java.lang.OutOfMemoryError: Java heap space\\n'); sys.exit(7)", backend)
                client.process.wait(timeout=10)
                with self.assertRaises(RuntimeError) as caught:
                    client.result('shutdown')
                message = str(caught.exception)
                for expected in (f'backend={backend}', 'fixture=exact-limit',
                                 'shutdown', 'exit=7', 'panicked at', 'OutOfMemoryError: Java heap space'):
                    self.assertIn(expected, message)
                self.assertNotIn('secret', message)
                self.assertNotIn('grammar Private', message)
                client.close()  # Also safe when called again by cleanup.

    def test_eof_while_waiting_reports_signal_and_last_request(self):
        client = self.client('import os, signal; os.read(0, 4096); os.kill(os.getpid(), signal.SIGTERM)', 'rust')
        with self.assertRaises(RuntimeError) as caught:
            client.result('initialize', {'token': 'secret'})
        message = str(caught.exception)
        self.assertIn(f'exit={-signal.SIGTERM} (SIGTERM)', message)
        self.assertIn('initialize', message)
        self.assertIn('backend=rust fixture=exact-limit', message)
        self.assertIn('invalid protocol frame (EOFError)', message)
        self.assertNotIn('secret', message)

    def test_broken_pipe_diagnostic_survives_cleanup(self):
        client = self.client('import os, time; os.close(0); time.sleep(5)')
        # stdout EOF is deliberately absent: test the write-error path itself.
        with self.assertRaises(RuntimeError) as caught:
            client.send('textDocument/didOpen', {'text': 'private' * 20000})
        self.assertIn('transport write failed', str(caught.exception))
        self.assertIn('textDocument/didOpen', str(caught.exception))
        self.assertNotIn('private', str(caught.exception))
        client.close()

    def test_response_timeout_reports_live_process(self):
        client = self.client('import time; time.sleep(5)')
        client.timeout = 0.05
        started = time.monotonic()
        with self.assertRaises(RuntimeError) as caught:
            client.result('shutdown')
        self.assertLess(time.monotonic() - started, 2)
        self.assertIn('exit=running: response timeout', str(caught.exception))
        self.assertIn('backend=java fixture=exact-limit last_request=shutdown', str(caught.exception))

    def test_stderr_flood_is_drained_and_tail_is_bounded(self):
        client = self.client(
            "import sys; sys.stderr.write('secret-body' * 100000); sys.stderr.flush(); "
            "sys.stderr.write('\\nmemory allocation of 67108864 bytes failed\\n'); sys.exit(9)")
        # A full stderr pipe would prevent exit and hit this finite wait.
        client.process.wait(timeout=10)
        with self.assertRaises(RuntimeError) as caught:
            client.result('shutdown')
        message = str(caught.exception)
        self.assertIn('exit=9', message)
        self.assertIn('memory allocation of 67108864 bytes failed', message)
        self.assertNotIn('secret-body', message)
        self.assertLess(len(client.stderr_tail), 4097)
        self.assertLess(len(message.encode()), 4500)

    def test_unrelated_notifications_do_not_extend_response_deadline(self):
        client = self.client(
            "import sys, time; data=b'{\"method\":\"unrelated\"}'; "
            "frame=b'Content-Length: '+str(len(data)).encode()+b'\\r\\n\\r\\n'+data; "
            "exec('for _ in range(50):\\n sys.stdout.buffer.write(frame); sys.stdout.buffer.flush(); time.sleep(.01)')")
        client.timeout = 0.1
        started = time.monotonic()
        with self.assertRaises(RuntimeError) as caught:
            client.result('initialize')
        self.assertLess(time.monotonic() - started, 2)
        self.assertIn('response timeout', str(caught.exception))

    def test_malformed_frame_reports_context_without_payload(self):
        client = self.client("import sys; sys.stdout.write('Content-Length: ' + 'private-body' * 10000 + '\\r\\n\\r\\n'); sys.stdout.flush()")
        with self.assertRaises(RuntimeError) as caught:
            client.result('initialize')
        self.assertIn('backend=java fixture=exact-limit', str(caught.exception))
        self.assertIn('invalid protocol frame (ValueError)', str(caught.exception))
        rendered = ''.join(traceback.format_exception(caught.exception))
        self.assertNotIn('private-body', rendered)
        self.assertLess(len(rendered.encode()), 5000)


if __name__ == '__main__':
    unittest.main()
