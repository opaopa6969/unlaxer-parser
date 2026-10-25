#!/usr/bin/env python3
"""CI の skip 禁止と通常のローカル unittest の互換性。"""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / 'scripts/run-required-unittests.py'


class RequiredUnittestTest(unittest.TestCase):
    def run_fixture(self, body, strict=True, discover=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'test_fixture.py'
            path.write_text('import unittest\n' + body + '\nif __name__ == "__main__": unittest.main()\n')
            if strict:
                args = [str(RUNNER), 'discover', '-s', directory] if discover else [str(RUNNER), path.name]
            else:
                args = [str(path)]
            return subprocess.run([sys.executable, *args], cwd=directory, text=True, capture_output=True)

    def test_success_failure_error_and_zero_execution(self):
        for body, status in [('pass', 0), ('self.fail("failure")', 1), ('raise RuntimeError("error")', 1)]:
            result = self.run_fixture('class Example(unittest.TestCase):\n    def test_case(self): ' + body)
            self.assertEqual(result.returncode, status, result.stderr)
        result = self.run_fixture('', discover=True)
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertIn('Ran 0 tests', result.stderr)

    def test_method_class_setup_and_subtest_skips_fail_only_in_ci(self):
        for body in [
            'class Example(unittest.TestCase):\n    @unittest.skip("optional")\n    def test_case(self): pass',
            '@unittest.skipUnless(False, "missing tool")\nclass Example(unittest.TestCase):\n    def test_case(self): pass',
            'class Example(unittest.TestCase):\n    @classmethod\n    def setUpClass(cls): raise unittest.SkipTest("setup")\n    def test_case(self): pass',
            'class Example(unittest.TestCase):\n    def test_case(self):\n        with self.subTest(value=1): self.skipTest("subtest")',
        ]:
            with self.subTest(body=body):
                self.assertEqual(self.run_fixture(body, strict=False).returncode, 0)
                result = self.run_fixture(body)
                self.assertNotEqual(result.returncode, 0, result.stderr)
                self.assertIn('skipped=1', result.stderr)
                self.assertNotEqual(self.run_fixture(body, discover=True).returncode, 0)

    def test_real_repository_classes_cannot_silently_skip_in_ci(self):
        # Import the real tests with absent tools/plugins. No Maven command is executed.
        for case, missing_tool in [('PostStepTest', True), ('MavenResolutionTest', True),
                                   ('TailResolutionTest', True), ('TailResolutionTest', False)]:
            for strict in [False, True]:
                with self.subTest(case=case, missing_tool=missing_tool, strict=strict), tempfile.TemporaryDirectory() as home:
                    script = RUNNER if strict else ROOT / 'scripts/tests/test_maven_job_repository.py'
                    target = 'scripts.tests.test_maven_job_repository.' + case if strict else case
                    driver = f'''
import runpy, sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, {str(ROOT)!r})
sys.argv = [{str(script)!r}, {target!r}]
with patch('shutil.which', return_value={None if missing_tool else '/unused/mvn'!r}), patch('pathlib.Path.home', return_value=Path({home!r})):
    runpy.run_path({str(script)!r}, run_name='__main__')
'''
                    result = subprocess.run([sys.executable, '-c', driver], text=True, capture_output=True)
                    self.assertEqual(result.returncode, 1 if strict else 0, result.stderr)
                    self.assertIn('skipped=', result.stderr)

    def test_all_workflow_unittest_invocations_use_the_required_runner(self):
        invocations = 0
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            for job in yaml.safe_load(path.read_text())['jobs'].values():
                for step in job.get('steps', []):
                    for line in step.get('run', '').splitlines():
                        if 'python' in line and ('test_' in line or 'unittest' in line):
                            if line.strip() == 'python3 scripts/tests/test_version_sync.py':
                                # 独自の終了コードを持つ文書検査。unittest ではなくゼロ件と誤判定しない。
                                source = (ROOT / 'scripts/tests/test_version_sync.py').read_text()
                                self.assertNotRegex(source, r'\b(import|from)\s+unittest\b')
                                continue
                            self.assertIn('python3 scripts/run-required-unittests.py ', line, (path, line))
                            invocations += 1
        self.assertGreaterEqual(invocations, 5)


if __name__ == '__main__':
    unittest.main()
