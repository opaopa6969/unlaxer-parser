#!/usr/bin/env python3
"""CI workflows: self-hosted runners only, superseded PR runs cancelled, master and tags never."""
from pathlib import Path
import re
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / '.github/workflows'
RUNNER = ['unlaxer-parser-ci', 'Linux', 'X64']
# A pull request run is replaced by the next push to it. Any other run has a group of its own
# (run_id): GitHub cancels a pending run when a newer one joins its group, even without
# cancel-in-progress, so a shared group would drop queued master runs.
PR_GROUP = '${{ github.workflow }}-${{ github.event.pull_request.number || github.run_id }}'
PR_CANCEL = "${{ github.event_name == 'pull_request' }}"
# Network steps that may fail once; the step after each of them repeats it and decides the job.
RETRIED_ACTIONS = ('actions/configure-pages@', 'actions/deploy-pages@')
# Also the spellings not used yet (npm install, rustup component add, cargo fetch without
# --locked, playwright install --with-deps), so a new download step cannot skip the wrapper.
NETWORK_COMMAND = re.compile(
    r'\b(?:rustup\s+(?:toolchain\s+install|install|target\s+add|component\s+add)|'
    r'cargo\s+(?:\+\S+\s+)?fetch|npm\s+(?:ci|clean-install|install|i)|'
    r'npx\s+playwright\s+install)(?![\w-])')


def workflows():
    for path in sorted(WORKFLOWS.glob('*.yml')):
        document = yaml.safe_load(path.read_text())
        # YAML 1.1 reads the key "on" as True.
        yield path.name, document, document.get('on', document.get(True))


class WorkflowConcurrencyTest(unittest.TestCase):

    def test_every_workflow_declares_concurrency(self):
        for name, document, _ in workflows():
            concurrency = document.get('concurrency')
            self.assertIsInstance(concurrency, dict, name)
            self.assertIn('group', concurrency, name)
            self.assertIn('cancel-in-progress', concurrency, name)

    def test_pull_request_runs_are_cancelled_by_a_newer_push_and_others_never(self):
        for name, document, triggers in workflows():
            if 'pull_request' not in triggers:
                continue
            concurrency = document['concurrency']
            self.assertEqual(concurrency['group'], PR_GROUP, name)
            self.assertEqual(concurrency['cancel-in-progress'], PR_CANCEL, name)

    def test_release_and_pages_runs_are_never_cancelled(self):
        for name, document, triggers in workflows():
            if 'pull_request' in triggers:
                continue
            self.assertIs(document['concurrency']['cancel-in-progress'], False, name)

    def test_a_tag_release_waits_for_an_earlier_release_of_the_same_tag(self):
        documents = {name: document for name, document, _ in workflows()}
        self.assertEqual(documents['release-vsix.yml']['concurrency']['group'],
                         '${{ github.workflow }}-${{ github.ref }}')
        self.assertIn('inputs.version', documents['release-central.yml']['concurrency']['group'])


class WorkflowRunnerTest(unittest.TestCase):

    def test_every_job_runs_on_the_self_hosted_runners(self):
        for name, document, _ in workflows():
            for job_name, job in document['jobs'].items():
                self.assertEqual(job.get('runs-on'), RUNNER, f'{name}:{job_name}')


class NetworkRetryTest(unittest.TestCase):

    def test_every_dependency_download_uses_the_wrapper_from_its_own_checkout(self):
        checked = 0
        for name, document, _ in workflows():
            for job_name, job in document['jobs'].items():
                own = [step.get('with', {}).get('path', '') for step in job.get('steps', [])
                       if str(step.get('uses', '')).startswith('actions/checkout@')
                       and 'repository' not in step.get('with', {})]
                checked_out = False
                for step in job.get('steps', []):
                    if (str(step.get('uses', '')).startswith('actions/checkout@')
                            and 'repository' not in step.get('with', {})):
                        checked_out = True
                    script = step.get('run', '')
                    for command in NETWORK_COMMAND.finditer(script):
                        where = f'{name}:{job_name}: {step}'
                        self.assertEqual(len(own), 1, where)
                        self.assertTrue(checked_out, f'wrapper used before checkout: {where}')
                        prefix = own[0] + '/' if own[0] else ''
                        wrapper = f'bash "$GITHUB_WORKSPACE/{prefix}scripts/ci/retry-network.sh" '
                        self.assertTrue(script[:command.start()].endswith(wrapper), where)
                        checked += 1
        self.assertGreaterEqual(checked, 30)

    def test_wrapper_only_runs_download_commands_never_tests_or_shell_blocks(self):
        checked = 0
        for name, document, _ in workflows():
            for job_name, job in document['jobs'].items():
                for step in job.get('steps', []):
                    script = step.get('run', '')
                    # An allowlist also rejects bash -c, mvn, cargo test, node --test,
                    # npm test/run and test scripts, including multi-command blocks.
                    for call in re.finditer(r'retry-network\.sh[\"\']?\s+', script):
                        self.assertIsNotNone(NETWORK_COMMAND.match(script, call.end()),
                                             f'{name}:{job_name}: {step}')
                        checked += 1
        self.assertGreaterEqual(checked, 30)

    def test_retry_regressions_run_through_the_required_unittest_runner(self):
        command = 'python3 scripts/run-required-unittests.py scripts.tests.test_ci_network_retry'
        build = dict((name, doc) for name, doc, _ in workflows())['maven.yml']['jobs']['build']
        self.assertTrue(any(step.get('run') == command for step in build['steps']))

    def test_only_retried_network_steps_may_fail(self):
        for name, document, _ in workflows():
            for job_name, job in document['jobs'].items():
                self.assertNotIn('continue-on-error', job, f'{name}:{job_name}')
                for step in job.get('steps', []):
                    if 'continue-on-error' in step:
                        self.assertTrue(str(step.get('uses', '')).startswith(RETRIED_ACTIONS),
                                        f'{name}:{job_name}: {step}')

    def test_a_failed_network_step_is_repeated_once_and_the_repeat_decides(self):
        for name, document, _ in workflows():
            for job_name, job in document['jobs'].items():
                steps = job.get('steps', [])
                for index, step in enumerate(steps):
                    if 'continue-on-error' not in step:
                        continue
                    where = f'{name}:{job_name}:{step["uses"]}'
                    self.assertIs(step['continue-on-error'], True, where)
                    condition = f"steps.{step['id']}.outcome == 'failure'"
                    wait, repeat = steps[index + 1], steps[index + 2]
                    self.assertEqual(wait.get('if'), condition, where)
                    self.assertRegex(wait.get('run', ''), r'^sleep [1-9][0-9]*$', where)
                    self.assertEqual(repeat.get('uses'), step['uses'], where)
                    self.assertEqual(repeat.get('if'), condition, where)
                    self.assertNotIn('continue-on-error', repeat, where)
                    self.assertEqual(repeat.get('with'), step.get('with'), where)

    def test_pages_are_deployed_from_master_only_with_both_actions_retried(self):
        documents = {name: document for name, document, _ in workflows()}
        deploy = documents['learning-pages.yml']['jobs']['deploy']
        self.assertEqual(deploy['if'], "github.ref == 'refs/heads/master'")
        uses = [step.get('uses') for step in deploy['steps'] if 'uses' in step]
        self.assertEqual(uses, ['actions/configure-pages@v5'] * 2 + ['actions/deploy-pages@v4'] * 2)
        self.assertIn('timeout:', deploy['env']['RES_OPTIONS'])
        self.assertIn('deployment-retry.outputs.page_url', deploy['environment']['url'])


if __name__ == '__main__':
    unittest.main()
