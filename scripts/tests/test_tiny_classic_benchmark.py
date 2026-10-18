import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'run-tiny-classic-benchmark.py'
spec = importlib.util.spec_from_file_location('tiny_classic_benchmark', SCRIPT)
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


def valid_row():
    return dict(entry='Formula', memo_effective='OFF', diagnostics_effective='DETAILED',
                accepted=True, parser_succeeded=True, consumed_cp=1, matched_cp=1,
                parse_map_ns=5, ast=dict(type='FormulaExpr', span=[0, 1], fields={'value': '1'}),
                syntax=dict(offset=1, expected=[]), semantic=[], failure_hits=0, success_hits=0,
                phase='cold', iteration=0)


class TinyClassicBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.case = next(case for case in json.loads((harness.FIXTURES / 'manifest.json').read_text())['cases']
                         if case['id'] == 'oracle-one')

    def test_fast_failure_null_ast_short_parse_and_wrong_value_are_not_success(self):
        for changes in [dict(accepted=False), dict(ast=None), dict(consumed_cp=0),
                        dict(matched_cp=0), dict(entry='OtherEntry'), dict(error='callback failed'), dict(success_hits=1),
                        dict(memo_effective='SAFE_FAILURES'), dict(diagnostics_effective='DETAILED_ON_FAILURE'),
                        dict(ast=dict(type='FormulaExpr', span=[0, 0], fields={'value': '1'})),
                        dict(ast=dict(type='FormulaExpr', span=[0, 1], fields={'value': 'wrong'}))]:
            with self.subTest(changes=changes):
                row = valid_row()
                row.update(changes)
                with self.assertRaises(ValueError):
                    harness.validate_result(self.case, row, 'OFF', 'DETAILED')
        harness.validate_result(self.case, valid_row(), 'OFF', 'DETAILED')

    def test_rejection_requires_real_diagnostics_in_detailed_mode(self):
        case = dict(self.case, accept=False, failure_cp=3)
        row = dict(valid_row(), accepted=False, parser_succeeded=False, consumed_cp=0,
                   matched_cp=0, ast=None, syntax=dict(offset=3, expected=['expression']))
        harness.validate_result(case, row, 'OFF', 'DETAILED')
        for syntax in [dict(offset=3, expected=[]), dict(offset=2, expected=['expression'])]:
            with self.assertRaises(ValueError):
                harness.validate_result(case, dict(row, syntax=syntax), 'OFF', 'DETAILED')
        harness.validate_result(case, dict(row, syntax=dict(offset=0, expected=[]), diagnostics_effective='DETAILED_ON_FAILURE'),
                                'OFF', 'DETAILED_ON_FAILURE')

    def test_complete_ast_oracle_detects_a_wrong_nested_span(self):
        case = next(case for case in json.loads((harness.FIXTURES / 'manifest.json').read_text())['cases']
                    if case['id'] == 'oracle-unicode')
        row = dict(valid_row(), consumed_cp=3, matched_cp=3,
                   ast=json.loads(json.dumps(case['expected_ast'])))
        harness.validate_result(case, row, 'OFF', 'DETAILED')
        row['ast']['fields']['expression']['span'] = [0, 2]
        with self.assertRaisesRegex(ValueError, 'complete AST/span mismatch'):
            harness.validate_result(case, row, 'OFF', 'DETAILED')

    def test_tracked_flattened_metadata_is_restored_even_after_a_failed_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / 'module/.flattened-pom.xml'
            path.parent.mkdir()
            original = b'<project>original release metadata</project>\r\n'
            path.write_bytes(original)
            with patch.object(harness, 'ROOT', root), patch.object(harness, 'git', return_value='module/.flattened-pom.xml'):
                with self.assertRaisesRegex(ValueError, 'build failed'):
                    with harness.preserve_flattened_poms():
                        path.write_bytes(b'changed snapshot metadata')
                        raise ValueError('build failed')
            self.assertEqual(path.read_bytes(), original)

    def run_fake(self, directory, produce):
        (directory / 'build.json').write_text(json.dumps(dict(
            parser_product_hash='test-product', tiny_commit='pin', classpath='test-classpath',
            consumer_hashes=harness.consumer_hashes())))
        arguments = SimpleNamespace(mode='verify', exclusive_host=False, prerequisite=[],
                                    corpus='oracle', memo='both', diagnostics='DETAILED',
                                    timeout=1, warmups=0, iterations=0, entry_override=None)
        manifest = dict(tiny_pin='pin', cases=[self.case])
        with patch.object(harness, 'product_hash', return_value='test-product'), \
                patch.object(harness, 'git', return_value='test-commit'), \
                patch.object(harness, 'command', side_effect=produce):
            harness.execute(arguments, manifest, directory)

    def test_verified_rows_are_not_mislabeled_as_measurement(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def produce(arguments, cwd, log, timeout):
                log.write_text(json.dumps(dict(valid_row(), memo_effective=arguments[-5])) + '\n')
                return 100
            self.run_fake(directory, produce)
            result = next(directory.glob('run-*/result.json'))
            self.assertEqual(json.loads(result.read_text()), dict(
                errors=[], semantic_configurations=1, measurement_eligible=False))
            rows = next(directory.glob('run-*/samples.jsonl')).read_text().splitlines()
            self.assertEqual(len(rows), 6)
            self.assertTrue(all(not json.loads(row)['measurement_eligible'] for row in rows))

    def test_changed_native_diagnostic_is_a_failed_run_even_when_ast_matches(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def produce(arguments, cwd, log, timeout):
                row = valid_row()
                row['memo_effective'] = arguments[-5]
                if 'SAFE_FAILURES' in arguments:
                    row['syntax'] = dict(offset=9, expected=['corrupted memo hint'])
                log.write_text(json.dumps(row) + '\n')
                return 100
            with self.assertRaisesRegex(ValueError, 'explicit verification failures'):
                self.run_fake(directory, produce)
            result = json.loads(next(directory.glob('run-*/result.json')).read_text())
            self.assertEqual(len(result['errors']), 3)
            self.assertTrue(all('native diagnostic changed' in item['error'] for item in result['errors']))

    def test_child_exception_is_not_a_fast_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaisesRegex(ValueError, 'explicit verification failures'):
                self.run_fake(directory, lambda *args: (_ for _ in ()).throw(RuntimeError('child failed')))
            result = json.loads(next(directory.glob('run-*/result.json')).read_text())
            self.assertEqual(len(result['errors']), 6)
            self.assertEqual(result['semantic_configurations'], 0)

    def test_owned_process_timeout_is_reported_as_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(RuntimeError, 'timeout; no successful sample'):
                harness.command([sys.executable, '-c', 'import time; time.sleep(30)'],
                                Path(temporary), Path(temporary) / 'child.log', timeout=0.05)

    def test_manifest_detects_changed_crlf_and_unicode_bytes(self):
        manifest = json.loads((harness.FIXTURES / 'manifest.json').read_text())
        for case in manifest['cases']:
            path = harness.FIXTURES / case['file']
            self.assertEqual(case['sha256'], harness.digest(path))
            self.assertEqual(case['code_points'], len(path.read_bytes().decode('utf-8')))
        self.assertEqual(sum(case['group'] == 'fraud' for case in manifest['cases']), 5)


if __name__ == '__main__':
    unittest.main()
