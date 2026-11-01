#!/usr/bin/env python3
"""#719: real Surefire 3.2.5 reports and workflow obligation regression checks."""
import importlib.util
import copy
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


def execution_contracts():
    expanded = VERIFIER.expand_contracts(json.loads(VERIFIER.CONTRACTS.read_text()))
    return {name: contract for name, contract in expanded.items() if 'shards' not in contract}


def matrix_jobs(job):
    """Inspect each fixed shard's actual commands with its matrix values expanded."""
    for row in job.get('strategy', {}).get('matrix', {}).get('include', [{}]):
        document = json.dumps(job)
        for key, value in row.items():
            document = document.replace('${{ matrix.' + key + ' }}', str(value))
        expanded = json.loads(document)
        steps = []
        for step in expanded.get('steps', []):
            condition = step.get('if', '')
            match = re.search(r"matrix.shard == '([^']+)'", condition)
            if match:
                if match[1] != row['shard']:
                    continue
                condition = condition.replace(match[0] + ' && ', '').replace(' && ' + match[0], '')
                step['if'] = condition
            steps.append(step)
        expanded['steps'] = steps
        yield expanded


class ShardContractTest(unittest.TestCase):
    def setUp(self):
        self.contracts = json.loads(VERIFIER.CONTRACTS.read_text())
        self.parent = self.contracts['cross-language']

    def test_union_preserves_every_original_class_method_property_and_report(self):
        expanded = VERIFIER.expand_contracts(self.contracts)
        combined = {}
        for name in self.parent['shards']:
            shard = expanded[name]
            self.assertFalse(set(combined) & set(shard['required']))
            combined.update(shard['required'])
            self.assertEqual(shard['properties'], self.parent['properties'])
            self.assertEqual(shard['reports'], self.parent['reports'])
            self.assertEqual(shard['properties']['rustConformance'], 'true')
        self.assertEqual(combined, self.parent['required'])
        # Expanding never changes the canonical, unsplit obligation.
        self.assertEqual(expanded['cross-language'], self.parent)

    def test_missing_duplicate_unknown_empty_and_colliding_shards_fail_closed(self):
        names = list(self.parent['shards'])
        for mutation in ['missing', 'duplicate', 'unknown', 'empty', 'collision']:
            with self.subTest(mutation=mutation):
                contracts = copy.deepcopy(self.contracts)
                shards = contracts['cross-language']['shards']
                if mutation == 'missing':
                    shards[names[0]].pop()
                elif mutation == 'duplicate':
                    shards[names[1]].append(shards[names[0]][0])
                elif mutation == 'unknown':
                    shards[names[0]].append('unknown.Test')
                elif mutation == 'empty':
                    shards[names[0]] = []
                else:
                    shards['tiny-catalog'] = shards.pop(names[0])
                with self.assertRaises(ValueError):
                    VERIFIER.expand_contracts(contracts)


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

    def test_all_reports_contract_checks_unlisted_xml_too(self):
        self.report()
        self.contract['all_reports'] = True
        for fixture, ok in [('success', True), ('skipped', False), ('failure', False), ('error', False)]:
            path = self.report(fixture, name='other.Test')
            def rename(suite):
                suite.set('name', 'other.Test')
                suite.find('testcase').set('classname', 'other.Test')
            self.rewrite(path, rename)
            result = self.verify()
            self.assertEqual(result['ok'], ok, result)
            self.assertEqual(len(result['suites']), 2)
            self.assertTrue((self.output / path.name).exists())
        path.write_text('<testsuite')
        self.assertFalse(self.verify()['ok'])
        VERIFIER.prepare(self.root, self.contract, self.output)
        self.assertEqual(list(self.reports.glob('TEST-*.xml')), [])

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
        contracts = execution_contracts()
        verified, prepared, artifacts = set(), set(), []
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            jobs = yaml.safe_load(path.read_text())['jobs'].values()
            for job in (expanded for job in jobs for expanded in matrix_jobs(job)):
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
                            self.assertEqual(step.get('if'), 'success()', (path, name))
                            # Matrix workers share a step ID, with distinct contract/artifact names.
                            expected_id = 'verify-cross-language' if name.startswith('cross-language-') else 'verify-' + name
                            self.assertEqual(step.get('id'), expected_id)
                    if 'upload-artifact@' in step.get('uses', '') and 'optin-evidence/' in step.get('with', {}).get('path', ''):
                        artifacts.append(step['with']['path'])
                        self.assertIn(".outcome != 'skipped'", step.get('if', ''))
                        self.assertIn('!cancelled()', step['if'])
        self.assertEqual(verified, set(contracts))
        # tiny-catalog uses the same freshly prepared cross-language invocation.
        self.assertEqual(prepared, set(contracts) - {'tiny-catalog'})
        for name in contracts:
            self.assertTrue(any('optin-evidence/' + name + '/' in a for a in artifacts), name)

    def test_real_tiny_catalog_uses_the_downstream_pin_and_grammar(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/maven.yml').read_text())['jobs']
        jobs['rust'] = next(matrix_jobs(jobs['rust-conformance']))
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
        portability = 'org.unlaxer.dsl.RustPortabilityConformanceTest'
        real = 'realTinyGrammarPassesAndItsExplicitlyInjectedGapsAreAllReported'
        self.assertNotIn(real, required[portability])
        self.assertIn(real, contracts['downstream-portability']['required'][portability])

    def test_every_selected_class_and_method_has_a_contract_and_vice_versa(self):
        contracts = execution_contracts()
        checked = set()
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            jobs = yaml.safe_load(path.read_text())['jobs'].values()
            for job in (expanded for job in jobs for expanded in matrix_jobs(job)):
                steps = job.get('steps', [])
                for index, step in enumerate(steps):
                    prepare = re.search(r'verify-optin-tests.py ([\w-]+) --prepare', step.get('run', ''))
                    if not prepare:
                        continue
                    name = prepare[1]
                    execution = steps[index + 1]
                    run = execution.get('run', '')
                    for key, value in execution.get('env', {}).items():
                        run = run.replace('${' + key + '}', str(value))
                    match = re.search(r'-Dtest=([^\s]+)', run)
                    if not match:
                        # These two fixed consumer profiles choose tests in their pinned pom.xml.
                        self.assertIn(name, ['downstream-p4-smoke', 'downstream-ide-smoke'])
                        self.assertIn('-P', run)
                        continue
                    selected = {}
                    for selector in match[1].split(','):
                        cls, sep, methods = selector.partition('#')
                        self.assertNotIn(cls, selected)
                        selected[cls] = set(methods.split('+')) if sep else None
                    required = {fq.rsplit('.', 1)[1]: set(methods)
                                for fq, methods in contracts[name]['required'].items()}
                    for key, value in contracts[name].get('properties', {}).items():
                        self.assertIn(f'-D{key}=' + (value or ''), run, (path, name))
                    self.assertEqual(set(selected), set(required), (path, name))
                    for cls, methods in selected.items():
                        if methods is not None:
                            self.assertEqual(methods, required[cls], (path, name, cls))
                    checked.add(name)
        self.assertEqual(checked, set(contracts) - {'tiny-catalog', 'downstream-p4-smoke', 'downstream-ide-smoke'})
        self.assertTrue(contracts['assertion-contracts']['all_reports'])

    def test_original_check_requires_both_shards_and_preserves_failure_status(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/maven.yml').read_text())['jobs']
        worker, aggregate = jobs['rust-conformance'], jobs['rust']
        self.assertEqual(worker['strategy']['max-parallel'], 2)
        self.assertIs(worker['strategy']['fail-fast'], False)
        self.assertEqual({row['shard'] for row in worker['strategy']['matrix']['include']}, {'core', 'cli'})
        # #1035: both workers reached the former 50-minute limit under load.
        self.assertEqual(worker['timeout-minutes'], 75)
        self.assertEqual(aggregate['name'], 'Rust backend and Java conformance')
        self.assertEqual(aggregate['needs'], 'rust-conformance')
        self.assertEqual(aggregate['if'], 'always()')
        setup, step = aggregate['steps']
        self.assertEqual(setup['name'], 'Select job temporary directory (#1045)')
        self.assertEqual(step['env']['RESULT'], '${{ needs.rust-conformance.result }}')
        for status in ['success', 'failure', 'cancelled', 'skipped', '']:
            result = subprocess.run(['bash', '-eu', '-c', step['run']], env={'RESULT': status})
            self.assertEqual(result.returncode == 0, status == 'success', status)

    def test_learning_browser_builds_its_site_in_the_same_job(self):
        # DeclarativeTokenConformanceTest's CLI-side build cannot supply core's files.
        checked = 0
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            jobs = yaml.safe_load(path.read_text())['jobs'].values()
            for job in (expanded for job in jobs for expanded in matrix_jobs(job)):
                steps = job.get('steps', [])
                for index, step in enumerate(steps):
                    if 'node scripts/learning-browser.mjs' in step.get('run', ''):
                        builders = [s for s in steps[:index]
                                    if s.get('run', '').strip() == 'node scripts/build-learning.mjs']
                        self.assertEqual(len(builders), 1, path)
                        self.assertNotIn('working-directory', builders[0])
                        checked += 1
        self.assertEqual(checked, 2)  # core and the standalone learning-pages workflow


if __name__ == '__main__':
    unittest.main()
