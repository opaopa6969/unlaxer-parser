#!/usr/bin/env python3
"""#789: the memo budget evidence gate must not depend on libtest stdout.

`failure-libtest-mixed.log` is real `runtime-tests failure_budget_ --nocapture` output of the
previous runtime (Rust 1.85.0), where libtest wrote `test X ... ` and the oracle's
`BUDGET\t...` row landed on the same line. `failure-rust.tsv` is the evidence file the Rust
oracle now writes itself (UNLAXER_TEST_FAILURE_BUDGET_EVIDENCE).
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / 'scripts/check-memo-budget-evidence.py'
FIXTURES = Path(__file__).resolve().parent / 'fixtures/memo-budget-evidence'
MIXED_LOG = FIXTURES / 'failure-libtest-mixed.log'
EVIDENCE = FIXTURES / 'failure-rust.tsv'
CASES = ROOT / 'conformance/failure-memo-budget/cases.tsv'
# The gate this issue replaces (maven.yml before #789).
OLD_GATE = "/^BUDGET/ {n++} END {exit n != 600}"


def check(path, cases=CASES, rows=600):
    return subprocess.run([sys.executable, str(CHECKER), '--cases', str(cases), '--rows', str(rows), str(path)],
                          capture_output=True, text=True)


class MixedStdoutTest(unittest.TestCase):
    def test_fixture_reproduces_the_interleaving(self):
        text = MIXED_LOG.read_text()
        mixed = [line for line in text.splitlines() if line.startswith('test ') and 'BUDGET\t' in line]
        self.assertEqual(len(mixed), 1, mixed)
        self.assertEqual(text.count('BUDGET\t'), 600)

    def test_old_stdout_gate_rejects_complete_mixed_output(self):
        # All 600 rows are present, yet the line-start count sees 599: the old gate is unstable.
        result = subprocess.run(['awk', OLD_GATE, str(MIXED_LOG)])
        self.assertNotEqual(result.returncode, 0)

    def test_evidence_file_holds_the_same_rows_as_the_old_stdout(self):
        rows_in_stdout = sorted(line[line.index('BUDGET\t') + len('BUDGET\t'):]
                                for line in MIXED_LOG.read_text().splitlines() if 'BUDGET\t' in line)
        self.assertEqual(sorted(EVIDENCE.read_text().splitlines()[1:]), rows_in_stdout)

    def test_stdout_is_not_accepted_as_evidence(self):
        result = check(MIXED_LOG)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('header', result.stdout)
        self.assertIn(f'FAIL {MIXED_LOG}', result.stdout)


class EvidenceCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.lines = EVIDENCE.read_text().splitlines()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, lines=None, raw=None):
        path = Path(self.tmp.name) / 'evidence.tsv'
        path.write_bytes(raw if raw is not None else ('\n'.join(lines) + '\n').encode())
        return path

    def assert_rejected(self, path, message, rows=600):
        result = check(path, rows=rows)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(message, result.stdout)
        self.assertIn(f'FAIL {path}', result.stdout)
        return result.stdout

    def test_recorded_evidence_passes(self):
        result = check(EVIDENCE)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('OK', result.stdout)

    def test_java_and_success_profiles_pass(self):
        # All enum spellings, including the success oracle, must be accepted.
        replacements = {
            'None': 'normal',
            'Some(ParseMode { kind: Consumed, invert: false })': 'consumed',
            'Some(ParseMode { kind: MatchOnly, invert: false })': 'matchOnly',
            'Some(ParseMode { kind: VirtualConsumed, invert: false })': 'virtualTokenConsumed',
            'Some(ParseMode { kind: VirtualMatchOnly, invert: false })': 'virtualTokenMatchOnly',
            'Off': 'OFF', 'SafeFailures': 'SAFE_FAILURES',
            'Auto': 'AUTO', 'Detailed': 'DETAILED', 'DetailedOnFailure': 'DETAILED_ON_FAILURE',
        }
        java = ['\t'.join(replacements.get(f, f) for f in line.split('\t')) for line in self.lines]
        for lines in (self.lines, java):
            for kind in ('failure', 'success'):
                with self.subTest(java=lines is java, kind=kind):
                    result = check(self.write(lines), cases=ROOT / f'conformance/{kind}-memo-budget/cases.tsv')
                    self.assertEqual(result.returncode, 0, result.stdout)

    def test_entire_enum_value_cannot_be_renamed(self):
        # The observed cartesian product still has 600 unique rows after each rename.
        for column, original in ((1, 'None'), (3, 'Off'), (4, 'Auto')):
            with self.subTest(column=column):
                lines = [self.lines[0]]
                for line in self.lines[1:]:
                    fields = line.split('\t')
                    if fields[column] == original:
                        fields[column] = 'unsupported'
                    lines.append('\t'.join(fields))
                self.assert_rejected(self.write(lines), 'unsupported')

    def test_missing_reset_cannot_be_replaced_by_extra_diagnostics(self):
        # 10 x 5 x 1 x 2 x 6 = 600: count and observed-product checks both pass.
        lines = [self.lines[0]]
        for line in self.lines[1:]:
            fields = line.split('\t')
            if fields[2] == 'true':
                fields[2] = 'false'
                fields[4] += 'Extra'
            lines.append('\t'.join(fields))
        self.assert_rejected(self.write(lines), 'invalid diagnostics')

    def test_missing_enum_dimension_is_rejected_even_with_reduced_row_count(self):
        for column, value in ((1, 'None'), (2, 'true'), (3, 'Off'), (4, 'Auto')):
            with self.subTest(column=column):
                lines = [self.lines[0]] + [line for line in self.lines[1:] if line.split('\t')[column] != value]
                self.assert_rejected(self.write(lines), 'combinations', rows=len(lines) - 1)

    def test_java_and_rust_enums_cannot_be_mixed_in_one_file(self):
        self.assert_rejected(self.write([line.replace('\tOff\t', '\tOFF\t') for line in self.lines]),
                             "invalid memo 'OFF'")

    def test_invalid_numeric_encodings_are_rejected(self):
        for column in (5, 6):
            for value in ('-1', '1.0', '+1', '01', 'NaN', '１'):
                with self.subTest(column=column, value=value):
                    lines = self.lines[:]
                    fields = lines[1].split('\t')
                    fields[column] = value
                    lines[1] = '\t'.join(fields)
                    self.assert_rejected(self.write(lines), 'non-negative integer')

    def test_invalid_utf8_is_rejected(self):
        self.assert_rejected(self.write(raw=b'\xff\n'), 'not UTF-8')

    def test_duplicate_row_with_unchanged_count_is_rejected(self):
        # Exactly what a count-only gate would accept.
        lines = self.lines[:]
        lines[2] = lines[1]
        out = self.assert_rejected(self.write(lines), 'duplicate key')
        self.assertIn('combinations', out)

    def test_missing_row_is_rejected(self):
        out = self.assert_rejected(self.write(self.lines[:-1]), '599 rows, expected 600')
        self.assertIn('combinations', out)

    def test_extra_row_is_rejected(self):
        self.assert_rejected(self.write(self.lines + [self.lines[-1]]), '601 rows, expected 600')

    def test_missing_case_is_rejected(self):
        lines = [line for line in self.lines if not line.startswith('single-small\t')]
        self.assert_rejected(self.write(lines), "missing case ids ['single-small']")

    def test_whole_dimension_missing_is_rejected_even_with_the_expected_count(self):
        lines = [line for line in self.lines if line.split('\t')[0] != 'single-small']
        self.assert_rejected(self.write(lines), "missing case ids ['single-small']", rows=540)

    def test_unknown_case_is_rejected(self):
        lines = self.lines[:]
        lines[1] = 'no-such-case' + lines[1][lines[1].index('\t'):]
        self.assert_rejected(self.write(lines), "unknown case id 'no-such-case'")

    def test_row_with_libtest_prefix_is_rejected(self):
        lines = self.lines[:]
        lines[5] = 'test memo_retention_tests::failure_budget_x ... BUDGET\t' + lines[5]
        self.assert_rejected(self.write(lines), ':6: 8 fields')

    def test_libtest_line_inside_evidence_is_rejected(self):
        lines = self.lines[:]
        lines.insert(3, 'test memo_retention_tests::failure_budget_x ... ok')
        self.assert_rejected(self.write(lines), ':4: 1 fields')

    def test_missing_column_is_rejected(self):
        lines = self.lines[:]
        lines[1] = lines[1].rsplit('\t', 1)[0]
        self.assert_rejected(self.write(lines), ':2: 6 fields')

    def test_empty_field_is_rejected(self):
        lines = self.lines[:]
        fields = lines[1].split('\t')
        fields[1] = ''
        lines[1] = '\t'.join(fields)
        self.assert_rejected(self.write(lines), 'non-empty')

    def test_non_integer_charge_is_rejected(self):
        lines = self.lines[:]
        lines[1] = lines[1].rsplit('\t', 1)[0] + '\t-1'
        self.assert_rejected(self.write(lines), "charge '-1'")

    def test_invalid_reset_is_rejected(self):
        lines = self.lines[:]
        fields = lines[1].split('\t')
        fields[2] = 'maybe'
        lines[1] = '\t'.join(fields)
        self.assert_rejected(self.write(lines), "reset 'maybe'")

    def test_wrong_header_is_rejected(self):
        self.assert_rejected(self.write(self.lines[1:]), ':1: header')

    def test_crlf_is_rejected(self):
        raw = ('\r\n'.join(self.lines) + '\r\n').encode()
        self.assert_rejected(self.write(raw=raw), 'CR')

    def test_unterminated_file_is_rejected(self):
        raw = '\n'.join(self.lines).encode()
        self.assert_rejected(self.write(raw=raw), 'not terminated')

    def test_empty_and_missing_files_are_rejected(self):
        self.assert_rejected(self.write(raw=b''), 'empty')
        missing = Path(self.tmp.name) / 'missing.tsv'
        self.assert_rejected(missing, 'missing')

    def test_every_file_is_reported(self):
        missing = Path(self.tmp.name) / 'missing.tsv'
        result = subprocess.run([sys.executable, str(CHECKER), '--cases', str(CASES), '--rows', '600',
                                 str(missing), str(EVIDENCE)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn(f'FAIL {missing}', result.stdout)
        self.assertIn(f'OK {EVIDENCE}', result.stdout)


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/maven.yml').read_text())
        self.steps = workflow['jobs']['rust']['steps']

    def step(self, name):
        found = [index for index, step in enumerate(self.steps) if step.get('name') == name]
        self.assertEqual(len(found), 1, name)
        return found[0]

    def test_gates_check_the_evidence_files_not_stdout(self):
        for kind in ('failure', 'success'):
            run = self.steps[self.step(f'Require bounded {kind} memo conformance evidence')]['run']
            self.assertIn('scripts/check-memo-budget-evidence.py', run)
            self.assertIn(f'conformance/{kind}-memo-budget/cases.tsv --rows 600', run)
            self.assertIn(f'unlaxer-dsl/target/{kind}-memo-budget-java.tsv', run)
            self.assertIn(f'unlaxer-dsl/target/{kind}-memo-budget-rust.tsv', run)
            self.assertNotIn('.log', run)
            self.assertNotIn('awk', run)

    def test_evidence_is_uploaded_even_when_the_gate_fails(self):
        upload = self.step('Upload memo budget evidence')
        self.assertGreater(upload, self.step('Require bounded failure memo conformance evidence'))
        self.assertGreater(upload, self.step('Require bounded success memo conformance evidence'))
        self.assertGreater(upload, self.step('Cross-language fixtures, compile failures, and generated-file drift'))
        step = self.steps[upload]
        self.assertEqual(step['if'], 'always()')
        self.assertNotIn('continue-on-error', step)
        for kind in ('failure', 'success'):
            gate = self.steps[self.step(f'Require bounded {kind} memo conformance evidence')]
            self.assertNotIn('continue-on-error', gate)
        self.assertNotIn('continue-on-error', self.steps[self.step(
            'Cross-language fixtures, compile failures, and generated-file drift')])
        for kind in ('failure', 'success'):
            for name in (f'{kind}-memo-budget-java.tsv', f'{kind}-memo-budget-rust.tsv', f'{kind}-memo-budget-rust.log'):
                self.assertIn(f'unlaxer-dsl/target/{name}', step['with']['path'])

    def test_this_test_runs_before_the_conformance_tests(self):
        index = self.step('Check memo budget evidence gate (#789)')
        self.assertIn('scripts.tests.test_memo_budget_evidence', self.steps[index]['run'])
        self.assertLess(index, self.step('Cross-language fixtures, compile failures, and generated-file drift'))


if __name__ == '__main__':
    unittest.main()
