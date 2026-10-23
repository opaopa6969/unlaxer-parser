#!/usr/bin/env python3
"""#719: real Surefire 3.2.5 reports and workflow obligation regression checks."""
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import yaml


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/verify-optin-tests.py'
FIXTURES = Path(__file__).parent / 'fixtures/surefire'
SPEC = importlib.util.spec_from_file_location('verifier', SCRIPT)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class SurefireTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.reports = self.root / 'module/target/surefire-reports'
        self.reports.mkdir(parents=True)
        self.output = self.root / 'target/optin-evidence/fixture'
        self.contract = {'reports': ['module/target/surefire-reports'],
                         'properties': {'rustConformance': 'true'},
                         'required': {'fixture.MethodTest': ['success']}}

    def report(self, fixture='success', name='fixture.MethodTest'):
        target = self.reports / f'TEST-{name}.xml'
        shutil.copyfile(FIXTURES / f'{fixture}.xml', target)
        return target

    def rewrite(self, path, change):
        document = ET.parse(path)
        change(document.getroot())
        document.write(path, encoding='utf-8', xml_declaration=True)

    def verify(self):
        result = VERIFIER.verify(self.root, self.contract, self.output)
        self.assertEqual(result, json.loads((self.output / 'result.json').read_text()))
        return result

    def test_success_preserves_xml_and_positive_execution_count(self):
        path = self.report()
        result = self.verify()
        self.assertTrue(result['ok'], result)
        self.assertEqual(result['suites'][0]['tests'], 1)
        self.assertEqual(path.read_bytes(), (self.output / path.name).read_bytes())

    def test_missing_report_fails_even_when_an_unrelated_suite_passes(self):
        self.report(name='other.Test')
        self.assertFalse(self.verify()['ok'])

    def test_missing_required_method_fails_even_when_the_class_passes(self):
        self.report()
        self.contract['required']['fixture.MethodTest'].append('realTinyGrammar')
        self.assertFalse(self.verify()['ok'])

    def test_skip_failure_and_error_fail_using_real_surefire_shapes(self):
        for name in ['skipped', 'failure', 'error']:
            with self.subTest(name=name):
                self.report(name)
                self.contract['required']['fixture.MethodTest'] = [name]
                result = self.verify()
                self.assertFalse(result['ok'])
                self.assertIn(name, '\n'.join(result['errors']))

    def test_before_class_assumption_fails(self):
        self.report('before-class', name='fixture.BeforeClassTest')
        self.contract['required'] = {'fixture.BeforeClassTest': ['success']}
        self.assertFalse(self.verify()['ok'])

    def test_zero_test_suite_fails(self):
        path = self.report()
        def empty(suite):
            suite.remove(suite.find('testcase'))
            suite.set('tests', '0')
        self.rewrite(path, empty)
        self.contract['required']['fixture.MethodTest'] = []
        self.assertFalse(self.verify()['ok'])

    def test_empty_contract_is_not_a_pass(self):
        self.contract['required'] = {}
        with self.assertRaises(ValueError):
            self.verify()

    def test_missing_or_false_optin_property_fails(self):
        for value in [None, 'false']:
            path = self.report()
            def change(suite):
                properties = suite.find('properties')
                prop = properties.find("property[@name='rustConformance']")
                if value is None:
                    properties.remove(prop)
                else:
                    prop.set('value', value)
            self.rewrite(path, change)
            self.assertFalse(self.verify()['ok'])

    def test_required_grammar_property_must_not_be_blank(self):
        self.contract['properties']['tinyexpression.grammar'] = None
        for value in ['', ' ', '/pinned/tinyexpression-p4.ubnf']:
            path = self.report()
            self.rewrite(path, lambda s: ET.SubElement(s.find('properties'), 'property',
                         name='tinyexpression.grammar', value=value))
            self.assertEqual(self.verify()['ok'], bool(value.strip()))

    def test_malformed_xml_fails(self):
        self.report().write_text('<testsuite')
        self.assertFalse(self.verify()['ok'])

    def test_wrong_suite_or_testcase_identity_fails(self):
        for target in ['suite', 'case']:
            path = self.report()
            self.rewrite(path, lambda s: s.set('name', 'wrong.Test') if target == 'suite'
                         else s.find('testcase').set('classname', 'wrong.Test'))
            self.assertFalse(self.verify()['ok'])

    def test_summary_cannot_hide_failure_or_skip(self):
        for fixture, counter in [('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')]:
            path = self.report(fixture)
            self.contract['required']['fixture.MethodTest'] = []
            self.rewrite(path, lambda s: s.set(counter, '0'))
            self.assertFalse(self.verify()['ok'])

    def test_invalid_or_missing_counters_fail(self):
        for counter in ['tests', 'failures', 'errors', 'skipped']:
            for value in [None, '-1', 'invalid']:
                path = self.report()
                self.rewrite(path, lambda s: s.attrib.pop(counter) if value is None else s.set(counter, value))
                self.assertFalse(self.verify()['ok'])

    def test_retries_do_not_hide_a_failed_attempt(self):
        for tag in ['flakyFailure', 'flakyError', 'rerunFailure', 'rerunError']:
            path = self.report()
            self.rewrite(path, lambda s: ET.SubElement(s.find('testcase'), tag))
            self.assertFalse(self.verify()['ok'])

    def test_unrelated_optin_skip_is_allowed(self):
        self.report()
        self.report('skipped', name='unrelated.Test')
        self.assertTrue(self.verify()['ok'])

    def test_parameterized_names_and_testsuites_wrapper(self):
        path = self.report()
        suite = ET.parse(path).getroot()
        suite.find('testcase').set('name', 'success[0: fixed oracle]')
        wrapper = ET.Element('testsuites')
        wrapper.append(suite)
        ET.ElementTree(wrapper).write(path)
        self.assertTrue(self.verify()['ok'])

    def test_duplicate_reports_fail(self):
        path = self.report()
        other = self.root / 'other-reports'
        other.mkdir()
        shutil.copyfile(path, other / path.name)
        self.contract['reports'].append('other-reports')
        self.assertFalse(self.verify()['ok'])

    def test_prepare_removes_old_pass_without_touching_unrelated_reports(self):
        path = self.report()
        other = self.report('skipped', name='unrelated.Test')
        self.assertTrue(self.verify()['ok'])
        VERIFIER.prepare(self.root, self.contract, self.output)
        self.assertFalse(path.exists())
        self.assertTrue(other.exists())
        self.assertFalse((self.output / 'result.json').exists())
        self.assertFalse(self.verify()['ok'])

    def test_cli_exit_status_cannot_be_replaced_with_unconditional_success(self):
        manifest = self.root / 'contracts.json'
        manifest.write_text(json.dumps({'fixture': self.contract}))
        command = [sys.executable, str(SCRIPT), 'fixture', '--root', str(self.root), '--contracts', str(manifest)]
        for present, status in [(False, 1), (True, 0)]:
            if present:
                self.report()
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, status, result.stdout + result.stderr)


class WorkflowTest(unittest.TestCase):
    def test_all_contracts_are_wired_with_prepare_verification_and_evidence(self):
        contracts = json.loads(VERIFIER.CONTRACTS.read_text())
        verified, prepared, artifacts = set(), set(), []
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            for job in yaml.safe_load(path.read_text())['jobs'].values():
                for step in job.get('steps', []):
                    run = step.get('run', '')
                    match = re.search(r'verify-optin-tests.py ([\w-]+)', run)
                    if match:
                        name = match[1]
                        self.assertIn(name, contracts)
                        if '--prepare' in run:
                            prepared.add(name)
                        else:
                            verified.add(name)
                            self.assertEqual(step.get('if'), 'always()', (path, name))
                    if 'upload-artifact@' in step.get('uses', '') and 'optin-evidence/' in step.get('with', {}).get('path', ''):
                        artifacts.append(step['with']['path'])
                        self.assertEqual(step.get('if'), 'always()')
        self.assertEqual(verified, set(contracts))
        # tiny-catalog uses the same freshly prepared cross-language invocation.
        self.assertEqual(prepared, set(contracts) - {'tiny-catalog'})
        for name in contracts:
            self.assertTrue(any('optin-evidence/' + name + '/' in a for a in artifacts), name)

    def test_real_tiny_catalog_uses_the_downstream_pin_and_grammar(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/maven.yml').read_text())['jobs']
        def tiny(job, path):
            return next(s['with'] for s in jobs[job]['steps'] if s.get('with', {}).get('path') == path)
        self.assertEqual(tiny('rust', 'tiny-catalog')['ref'], tiny('downstream', 'tinyexpression')['ref'])
        cross = next(s['run'] for s in jobs['rust']['steps'] if s.get('name', '').startswith('Cross-language'))
        self.assertIn('-Dtinyexpression.grammar=', cross)
        self.assertIn('/tiny-catalog/tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf', cross)
        contracts = json.loads(VERIFIER.CONTRACTS.read_text())
        required = contracts['cross-language']['required']
        self.assertIn('nativeFrontendMatchesJavaSyntax', required['org.unlaxer.dsl.codegen.RustUbnfFrontendConformanceTest'])
        self.assertIn('reportMeasuredWorkAndAllocation', required['org.unlaxer.dsl.codegen.TokenStreamConformanceTest'])
        self.assertIn('realTinyGrammarAdvancesPastCatalogToTheSameGenerationFrontier',
                      required['org.unlaxer.dsl.codegen.RustCatalogConformanceTest'])
        # Other PRs may extend -Dtest without editing this baseline contract in the same line.
        selected = set(re.search(r'-Dtest=([^\s]+)', cross)[1].split(','))
        self.assertTrue({fq.rsplit('.', 1)[1] for fq in required} <= selected)


if __name__ == '__main__':
    unittest.main()
