import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / 'profile-tiny-classic-benchmark.py'
SPEC = importlib.util.spec_from_file_location('tiny_profile', SCRIPT)
profile = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(profile)


def row():
    return dict(entry='Formula', memo_effective='SAFE_FAILURES', diagnostics_effective='DETAILED',
                accepted=True, parser_succeeded=True, consumed_cp=1, matched_cp=1, parse_map_ns=7,
                ast=dict(type='FormulaExpr', span=[0, 1], fields={'value': '1'}),
                syntax=dict(offset=1, expected=[]), semantic=[], failure_hits=2, success_hits=3,
                phase='cold', iteration=0, cache=dict(failure_retained_bytes=12))


class TinyClassicProfileTest(unittest.TestCase):
    def test_observation_is_inside_the_active_grammar_before_cleanup(self):
        source = (profile.ROOT / 'rust/unlaxer-runtime/src/lib.rs').read_text()
        changed = profile.instrument_runtime(source)
        self.assertEqual(changed.count('PROFILE_MEMO '), 1)
        self.assertLess(changed.index('PROFILE_MEMO '), changed.index('self.failure_memo = previous_failure_memo;'))
        for invalid in ['', source + source]:
            with self.assertRaisesRegex(ValueError, 'boundary changed'):
                profile.instrument_runtime(invalid)

    def test_missing_or_unexpected_cache_observations_are_not_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'probe.log'
            log.write_text(json.dumps(row()) + '\n')
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                profile.read_rows(log, 1, True)
            log.write_text('PROFILE_MEMO {"failure_entries": 1}\n' + json.dumps(row()) + '\n')
            self.assertEqual(profile.read_rows(log, 1, True)[0]['cache']['failure_entries'], 1)
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                profile.read_rows(log, 1, False)
            log.write_text('unexpected JVM log\n' + json.dumps(row()) + '\n')
            with self.assertRaises(ValueError):
                profile.read_rows(log, 1)

    def test_changed_native_hint_budget_or_operation_phase_fails(self):
        case = next(c for c in json.loads((profile.harness.FIXTURES / 'manifest.json').read_text())['cases']
                    if c['id'] == 'oracle-one')
        reference = row()
        profile.validate_rows(case, [row()], reference, 16, 0)
        for changed in [dict(syntax=dict(offset=9, expected=['changed'])),
                        dict(cache=dict(failure_retained_bytes=17)), dict(phase='warm'),
                        dict(phase_breakdown=dict(parse_ns=8))]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                profile.validate_rows(case, [dict(row(), **changed)], reference, 16, 0)

    def test_jfr_uses_only_the_relevant_event_whitelist(self):
        events = {e.attrib['name'] for e in ET.parse(profile.PROFILES / 'classic-profile.jfc').getroot()}
        self.assertEqual(events, {'jdk.ExecutionSample', 'jdk.ObjectAllocationSample',
                                 'jdk.GarbageCollection', 'jdk.GCHeapSummary',
                                 'jdk.Compilation', 'jdk.ThreadCPULoad'})


if __name__ == '__main__':
    unittest.main()
