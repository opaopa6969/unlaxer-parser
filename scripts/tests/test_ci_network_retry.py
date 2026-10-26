#!/usr/bin/env python3
"""#780: exercise the real Bash wrapper, without external network or sleeps."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / 'scripts/ci/retry-network.sh'
# Diagnostic formats checked against the tools' implementations / reports:
# https://github.com/rust-lang/rustup/issues/2929
# https://github.com/rust-lang/cargo/blob/rust-1.85.0/src/cargo/util/errors.rs
# https://github.com/npm/cli/blob/v10.8.2/node_modules/npm-registry-fetch/lib/errors.js
# https://github.com/microsoft/playwright/blob/v1.56.1/packages/playwright-core/src/server/registry/oopDownloadBrowserMain.ts
# https://github.com/microsoft/playwright/blob/v1.56.1/packages/playwright-core/src/server/utils/network.ts
TRANSIENT = [
    'npm error code ECONNRESET',
    'npm error network read ETIMEDOUT',
    'npm error syscall getaddrinfo EAI_AGAIN registry.npmjs.org',
    'getaddrinfo ENOTFOUND registry.npmjs.org',
    'Could not resolve host: static.rust-lang.org',
    'dns error: Temporary failure in name resolution',
    'Connection reset by peer (os error 104)',
    'Connection timed out (os error 110)',
    'TLS handshake timeout',
    'error sending request for url (https://static.rust-lang.org): operation timed out',
    '[28] Timeout was reached (Operation too slow)',
    'Request to https://cdn.playwright.dev/chromium.zip timed out after 30000ms',
    'HTTP 429 Too Many Requests',
    'HTTP/2 502 Bad Gateway',
    'HTTP 503 Service Unavailable',
    'HTTP 504 Gateway Timeout',
    'http request returned an unsuccessful status code: 503',
    'failed to get successful HTTP response from `https://index.crates.io/config.json`, got 502',
    'npm error code E429',
    'npm error code E503',
    "Download failed: server returned code 503 body 'unavailable'. URL: https://cdn.playwright.dev",
    'HTTP 500 Internal Server Error',
]
PERMANENT = [
    'npm error code EUSAGE: package.json and package-lock.json are not in sync',
    'the lock file needs to be updated but --locked was passed to prevent this',
    'npm error code ETARGET: No matching version found',
    'error: toolchain 1.999.0 is not installable',
    'http request returned an unsuccessful status code: 404',
    'HTTP 401 Unauthorized',
    'npm error code E403',
    'failed to get successful HTTP response from `https://index.crates.io/missing`, got 404',
    'Download failed: server returned code 404',
    'Download failed: certificate verify failed',
    'checksum failed for download',
    'Permission denied (os error 13)',
    'No space left on device',
    'timeout waiting for a file lock',
    'package version 1.503.0 requires 429 files, 502 bytes; checksum E503abc',
    'HTTP 5030 is not a status code',
    '',
]


class NetworkRetryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.state = self.directory / 'attempts'
        self.stub = self.directory / 'download.py'
        self.stub.write_text('''import json, os, pathlib, sys
state = pathlib.Path(os.environ['STUB_STATE'])
attempt = int(state.read_text()) + 1 if state.exists() else 1
state.write_text(str(attempt))
outcomes = json.loads(os.environ['STUB_OUTCOMES'])
code, message = outcomes[min(attempt - 1, len(outcomes) - 1)]
print(f'stdout attempt {attempt}: ' + json.dumps(sys.argv[1:]), flush=True)
stream = sys.stdout if os.environ.get('STUB_MESSAGE_STDOUT') else sys.stderr
print(f'stderr attempt {attempt}: {message}', file=stream, flush=True)
sys.exit(code)
''')
        self.env = dict(os.environ, CI_NETWORK_RETRY_DELAY_SECONDS='0',
                        STUB_STATE=str(self.state), TMPDIR=str(self.directory))

    def run_wrapper(self, outcomes, *args):
        self.state.unlink(missing_ok=True)
        self.env['STUB_OUTCOMES'] = json.dumps(outcomes)
        # Match Actions' strict shell, and ensure a failure prevents later commands.
        return subprocess.run(
            ['bash', '-euo', 'pipefail', '-c', 'bash "$@"; echo caller-continued',
             'test', str(WRAPPER), sys.executable, str(self.stub), *args],
            env=self.env, text=True, capture_output=True, timeout=10)

    def attempts(self):
        return int(self.state.read_text())

    def test_transport_and_http_diagnostics_retry_then_succeed(self):
        for message in TRANSIENT:
            with self.subTest(message=message):
                result = self.run_wrapper([(7, message), (0, 'download complete')])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.attempts(), 2)
                self.assertIn('caller-continued', result.stdout)

    def test_permanent_failures_stop_immediately_with_original_exit_code(self):
        for message in PERMANENT:
            with self.subTest(message=message):
                result = self.run_wrapper([(42, message)])
                self.assertEqual(result.returncode, 42, result.stderr)
                self.assertEqual(self.attempts(), 1)
                self.assertNotIn('caller-continued', result.stdout)

    def test_network_diagnostic_on_stdout_also_retries(self):
        self.env['STUB_MESSAGE_STDOUT'] = '1'
        result = self.run_wrapper([(7, 'ECONNRESET'), (0, 'complete')])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.attempts(), 2)

    def test_three_attempt_limit_preserves_last_exit_code_and_all_output(self):
        result = self.run_wrapper([(7, 'ECONNRESET'), (19, 'ETIMEDOUT'), (37, 'EAI_AGAIN')])
        self.assertEqual(result.returncode, 37)
        self.assertEqual(self.attempts(), 3)
        for number, message in enumerate(['ECONNRESET', 'ETIMEDOUT', 'EAI_AGAIN'], 1):
            self.assertIn(f'stdout attempt {number}:', result.stdout)
            self.assertIn(f'stderr attempt {number}: {message}', result.stdout)
        self.assertEqual(result.stderr.count('retrying in'), 2)
        self.assertNotIn('caller-continued', result.stdout)
        self.assertEqual(list(self.directory.glob('retry-network.*')), [])

    def test_third_attempt_can_succeed(self):
        result = self.run_wrapper([(8, 'ECONNRESET'), (9, 'ETIMEDOUT'), (0, 'complete')])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.attempts(), 3)

    def test_only_current_attempt_is_classified(self):
        result = self.run_wrapper([(7, 'ECONNRESET'), (41, 'lockfile mismatch')])
        self.assertEqual(result.returncode, 41)
        self.assertEqual(self.attempts(), 2)

    def test_success_never_retries_even_if_output_mentions_a_network_error(self):
        result = self.run_wrapper([(0, 'recovered from ECONNRESET')])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.attempts(), 1)

    def test_arguments_are_preserved_without_shell_evaluation(self):
        args = ['two words', '', '$(exit 99)', '*.json', '--flag=a;b']
        result = self.run_wrapper([(0, 'complete')], *args)
        self.assertIn(json.dumps(args), result.stdout)
        self.assertEqual(result.returncode, 0)

    def test_default_backoff_increases_without_sleeping_in_the_test(self):
        self.env.pop('CI_NETWORK_RETRY_DELAY_SECONDS')
        bin_dir = self.directory / 'bin'
        bin_dir.mkdir()
        sleep = bin_dir / 'sleep'
        sleep.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> "$STUB_SLEEPS"\n')
        sleep.chmod(0o755)
        sleeps = self.directory / 'sleeps'
        self.env.update(PATH=f'{bin_dir}:{os.environ["PATH"]}', STUB_SLEEPS=str(sleeps))
        result = self.run_wrapper([(7, 'ECONNRESET')])
        self.assertEqual(result.returncode, 7)
        self.assertEqual(sleeps.read_text().splitlines(), ['5', '10'])

    def test_interrupt_exit_code_is_not_retried(self):
        result = self.run_wrapper([(130, 'ECONNRESET')])
        self.assertEqual(result.returncode, 130)
        self.assertEqual(self.attempts(), 1)

    def test_tee_failure_cannot_be_reported_as_success(self):
        bin_dir = self.directory / 'bin'
        bin_dir.mkdir()
        tee = bin_dir / 'tee'
        tee.write_text('#!/bin/sh\ncat > "$1"\nexit 73\n')
        tee.chmod(0o755)
        self.env['PATH'] = f'{bin_dir}:{os.environ["PATH"]}'
        result = self.run_wrapper([(0, 'complete')])
        self.assertEqual(result.returncode, 73)
        self.assertEqual(self.attempts(), 1)


if __name__ == '__main__':
    unittest.main()
