#!/usr/bin/env python3
"""#718: every CI job that runs Maven first selects a fresh job-local repository."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / '.github/actions/maven-job-repository'
# Scripts that start Maven themselves; their children inherit MAVEN_ARGS.
MAVEN_SCRIPTS = ('check-tiny-production.py', 'run-tiny-classic-benchmark.py', 'examples/engine-reuse/run.py',
                 'examples/resolver-tutorial/run.py', 'check-job-maven-repository.sh',
                 'check-published-consumer.sh')


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


class ActionDefinitionTest(unittest.TestCase):
    def test_post_step_always_removes_the_repository(self):
        runs = yaml.safe_load((ACTION / 'action.yml').read_text())['runs']
        self.assertEqual((runs['main'], runs['post'], runs['post-if']), ('main.js', 'post.js', 'always()'))
        for name in [runs['main'], runs['post'], 'select.sh']:
            self.assertTrue((ACTION / name).is_file(), name)


class ActionScriptTest(unittest.TestCase):
    """Runs the action's shell with a stub mvn that reports what MAVEN_ARGS selects."""

    def setUp(self):
        self.script = (ACTION / 'select.sh').read_text()
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, True)
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
                        GITHUB_ENV=str(self.temp / 'env'), GITHUB_OUTPUT=str(self.temp / 'output'),
                        GITHUB_STATE=str(self.temp / 'state'), REPOSITORY_SUFFIX='')
        for variable in ['MAVEN_ARGS', 'STUB_RESOLVES', 'UNLAXER_MAVEN_REPOSITORY']:
            self.env.pop(variable, None)

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
        self.assertEqual((self.temp / 'state').read_text(), f'repository={repository}\n')

    def test_suffix_separates_repositories_in_one_job(self):
        self.assertEqual(self.run_action().returncode, 0)
        result = self.run_action(REPOSITORY_SUFFIX='consumer')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((Path(self.env['RUNNER_TEMP']) / 'maven-repository/42-3-downstream-consumer').is_dir())

    def test_rejects_reuse_and_a_second_selection(self):
        self.assertEqual(self.run_action().returncode, 0)
        self.assertNotEqual(self.run_action().returncode, 0)
        self.assertIn('already selected', self.run_action(UNLAXER_MAVEN_REPOSITORY='/elsewhere').stderr)

    def test_replaces_a_persistent_repository_of_the_runner_service(self):
        result = self.run_action(MAVEN_ARGS='-e -Dmaven.repo.local=/home/runner-3/m2/repository -U')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('replacing the runner', result.stdout)
        repository = Path(self.env['RUNNER_TEMP']) / 'maven-repository/42-3-downstream'
        self.assertEqual((self.temp / 'env').read_text().splitlines()[0], f'MAVEN_ARGS=-e -U -Dmaven.repo.local={repository}')

    def test_rejects_a_repository_maven_does_not_use(self):
        result = self.run_action(STUB_RESOLVES=str(Path.home() / '.m2/repository'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('expected', result.stderr)
        self.assertFalse((self.temp / 'env').exists())
        # The post step still removes the directory that was created.
        self.assertIn('repository=', (self.temp / 'state').read_text())

    def test_requires_runner_context(self):
        for variable in ['RUNNER_TEMP', 'GITHUB_JOB', 'GITHUB_RUN_ATTEMPT', 'GITHUB_STATE']:
            self.assertIn(variable, self.run_action(**{variable: ''}).stderr)

    def test_rejects_whitespace_that_mvn_would_split(self):
        self.assertIn('whitespace', self.run_action(RUNNER_TEMP=str(self.temp / 'has space')).stderr)


@unittest.skipUnless(shutil.which('node'), 'node is required for the post step')
class PostStepTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, True)
        self.parent = self.temp / 'runner-temp/maven-repository'
        self.env = dict(os.environ, RUNNER_TEMP=str(self.temp / 'runner-temp'), GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='3')

    def post(self, repository):
        env = dict(self.env, STATE_repository=str(repository))
        return subprocess.run(['node', str(ACTION / 'post.js')], env=env, text=True, capture_output=True)

    def test_removes_this_attempts_repository_and_keeps_others(self):
        mine = self.parent / '42-3-build'
        other = self.parent / '42-3-vsix'
        for directory in [mine / 'org/unlaxer', other]:
            directory.mkdir(parents=True)
        (mine / 'org/unlaxer/unlaxer-common.jar').write_text('development')
        result = self.post(mine)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(mine.exists())
        self.assertTrue(other.is_dir())
        self.assertIn('Removed', result.stdout)

    def test_never_removes_a_directory_it_did_not_create(self):
        for foreign in [self.temp / 'home/.m2/repository', self.parent / '41-1-build', self.parent / '42-3-build/../../x']:
            foreign.mkdir(parents=True, exist_ok=True)
            result = self.post(foreign)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('not removing', result.stdout)
            self.assertTrue(foreign.is_dir(), foreign)

    def test_nothing_to_do_without_state(self):
        result = self.post('')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nothing to remove', result.stdout)


@unittest.skipUnless(shutil.which('mvn'), 'Maven is required')
class MavenResolutionTest(unittest.TestCase):
    """Real Maven: the job repository wins over any settings.xml, and an explicit property wins over it."""

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, True)
        self.env = dict(os.environ, RUNNER_TEMP=str(self.temp / 'runner-temp'), GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1',
                        GITHUB_JOB='build', GITHUB_ENV=str(self.temp / 'env'), GITHUB_OUTPUT=str(self.temp / 'output'),
                        GITHUB_STATE=str(self.temp / 'state'))
        self.env.pop('UNLAXER_MAVEN_REPOSITORY', None)
        # As on runners 2 and 3, whose service selects a persistent repository.
        self.env['MAVEN_ARGS'] = f'-Dmaven.repo.local={self.temp / "runner-service-repository"}'
        selected = subprocess.run(['bash', str(ACTION / 'select.sh')], cwd=self.temp, env=self.env, text=True, capture_output=True)
        self.assertEqual(selected.returncode, 0, selected.stdout + selected.stderr)
        exported = dict(line.split('=', 1) for line in (self.temp / 'env').read_text().splitlines())
        self.env.update(exported)
        self.repository = exported['UNLAXER_MAVEN_REPOSITORY']

    def local_repository(self, *arguments):
        command = ['mvn', '-B', '-q', '-N', *arguments, 'org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate',
                   '-Dexpression=settings.localRepository', '-DforceStdout']
        return subprocess.run(command, cwd=self.temp, env=self.env, text=True, capture_output=True, check=True).stdout

    def test_settings_written_by_another_runner_cannot_move_the_repository(self):
        # setup-java of other repositories' jobs rewrites the shared settings.xml at any time.
        settings = self.temp / 'settings.xml'
        settings.write_text(f'<settings><localRepository>{Path.home() / ".m2/repository"}</localRepository></settings>')
        self.assertEqual(self.local_repository('-s', str(settings)), self.repository)

    def test_an_explicit_property_selects_a_separate_consumer_repository(self):
        consumer = self.temp / 'consumer'
        self.assertEqual(self.local_repository(f'-Dmaven.repo.local={consumer}'), str(consumer))


class TinyProductionGuardTest(unittest.TestCase):
    def run_script(self, *arguments, **environment):
        env = dict(os.environ)
        env.pop('MAVEN_ARGS', None)
        env.update(environment)
        return subprocess.run([sys.executable, str(ROOT / 'scripts/check-tiny-production.py'), '/nonexistent-tiny', *arguments],
                              env=env, text=True, capture_output=True)

    def test_refuses_to_install_without_a_dedicated_repository(self):
        self.assertIn('refusing to install', self.run_script().stderr)
        self.assertIn('refusing to install', self.run_script('--maven-repo', str(Path.home() / '.m2/repository')).stderr)
        self.assertIn('refusing to install',
                      self.run_script(MAVEN_ARGS=f'-Dmaven.repo.local={Path.home() / ".m2/repository"}').stderr)

    def test_a_dedicated_repository_passes_the_guard(self):
        for result in [self.run_script('--maven-repo', '/tmp/dedicated'),
                       self.run_script(MAVEN_ARGS='-Dmaven.repo.local=/tmp/dedicated'),
                       self.run_script('--skip-build')]:
            self.assertNotIn('refusing to install', result.stderr)


if __name__ == '__main__':
    unittest.main()
