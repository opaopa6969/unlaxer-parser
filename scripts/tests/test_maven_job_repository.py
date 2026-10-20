#!/usr/bin/env python3
"""#718: every CI job that runs Maven first selects a fresh job-local repository."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / '.github/actions/maven-job-repository/action.yml'
# Scripts that start Maven themselves; their children inherit MAVEN_ARGS.
MAVEN_SCRIPTS = ('check-tiny-production.py', 'run-tiny-classic-benchmark.py', 'examples/engine-reuse/run.py',
                 'examples/resolver-tutorial/run.py', 'check-job-maven-repository.sh')


def runs_maven(step):
    script = step.get('run', '')
    return bool(re.search(r'(^|[\s;&|(])mvn\s', script)) or any(name in script for name in MAVEN_SCRIPTS)


def selects_repository(step):
    return str(step.get('uses', '')).endswith('.github/actions/maven-job-repository')


class WorkflowTest(unittest.TestCase):
    def test_every_maven_job_selects_the_job_repository_before_maven(self):
        checked = 0
        for workflow in sorted((ROOT / '.github/workflows').glob('*.yml')):
            for name, job in yaml.safe_load(workflow.read_text())['jobs'].items():
                steps = job.get('steps', [])
                maven = [index for index, step in enumerate(steps) if runs_maven(step)]
                selections = [index for index, step in enumerate(steps) if selects_repository(step)]
                where = f'{workflow.name}:{name}'
                if not maven:
                    self.assertEqual(selections, [], where)
                    continue
                checked += 1
                self.assertEqual(len(selections), 1, where)
                java = [index for index, step in enumerate(steps) if 'actions/setup-java' in str(step.get('uses', ''))]
                self.assertTrue(java and java[0] < selections[0] < maven[0], where)
                # The action is read from this repository's own checkout in that job.
                own = [step.get('with', {}).get('path', '') for step in steps
                       if 'actions/checkout' in str(step.get('uses', '')) and 'repository' not in step.get('with', {})]
                self.assertEqual(len(own), 1, where)
                prefix = './' + (own[0] + '/' if own[0] else '')
                self.assertEqual(steps[selections[0]]['uses'], prefix + '.github/actions/maven-job-repository', where)
                for step in steps:
                    self.assertNotIn('.m2', step.get('run', ''), where)
        self.assertGreaterEqual(checked, 20)


class ActionScriptTest(unittest.TestCase):
    """Runs the action's shell with a stub mvn that reports what MAVEN_ARGS selects."""

    def setUp(self):
        self.script = yaml.safe_load(ACTION.read_text())['runs']['steps'][0]['run']
        self.temp = Path(tempfile.mkdtemp())
        bin_dir = self.temp / 'bin'
        bin_dir.mkdir()
        stub = bin_dir / 'mvn'
        stub.write_text('#!/bin/sh\n'
                        'if [ -n "$STUB_RESOLVES" ]; then printf %s "$STUB_RESOLVES"; exit 0; fi\n'
                        'for argument in $MAVEN_ARGS; do case "$argument" in\n'
                        '  -Dmaven.repo.local=*) printf %s "${argument#-Dmaven.repo.local=}"; exit 0;;\n'
                        'esac; done\nprintf %s "$HOME/.m2/repository"\n')
        stub.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}', RUNNER_TEMP=str(self.temp / 'runner-temp'),
                        GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='3', GITHUB_JOB='downstream',
                        GITHUB_ENV=str(self.temp / 'env'), GITHUB_OUTPUT=str(self.temp / 'output'), REPOSITORY_SUFFIX='')
        self.env.pop('MAVEN_ARGS', None)
        self.env.pop('STUB_RESOLVES', None)

    def run_action(self, **overrides):
        env = dict(self.env, **overrides)
        return subprocess.run(['bash', '-c', self.script], env=env, text=True, capture_output=True)

    def test_creates_a_fresh_repository_and_exports_it(self):
        result = self.run_action(MAVEN_ARGS='-e')
        self.assertEqual(result.returncode, 0, result.stderr)
        repository = Path(self.env['RUNNER_TEMP']) / 'maven-repository/42-3-downstream'
        self.assertTrue(repository.is_dir())
        self.assertEqual(list(repository.iterdir()), [])
        self.assertEqual((self.temp / 'env').read_text().splitlines(),
                         [f'MAVEN_ARGS=-e -Dmaven.repo.local={repository}', f'UNLAXER_MAVEN_REPOSITORY={repository}'])
        self.assertEqual((self.temp / 'output').read_text(), f'path={repository}\n')

    def test_suffix_separates_repositories_in_one_job(self):
        self.assertEqual(self.run_action().returncode, 0)
        result = self.run_action(REPOSITORY_SUFFIX='consumer')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((Path(self.env['RUNNER_TEMP']) / 'maven-repository/42-3-downstream-consumer').is_dir())

    def test_rejects_reuse_and_competing_selection(self):
        self.assertEqual(self.run_action().returncode, 0)
        self.assertNotEqual(self.run_action().returncode, 0)
        self.assertIn('already selects', self.run_action(MAVEN_ARGS='-Dmaven.repo.local=/elsewhere').stderr)

    def test_rejects_a_repository_maven_does_not_use(self):
        result = self.run_action(STUB_RESOLVES=str(Path.home() / '.m2/repository'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('expected', result.stderr)
        self.assertFalse((self.temp / 'env').exists())

    def test_requires_runner_context(self):
        for variable in ['RUNNER_TEMP', 'GITHUB_JOB', 'GITHUB_RUN_ATTEMPT']:
            self.assertIn(variable, self.run_action(**{variable: ''}).stderr)

    def test_rejects_whitespace_that_mvn_would_split(self):
        self.assertIn('whitespace', self.run_action(RUNNER_TEMP=str(self.temp / 'has space')).stderr)


if __name__ == '__main__':
    unittest.main()
