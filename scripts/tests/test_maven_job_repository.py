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


def workflow_jobs():
    for workflow in sorted((ROOT / '.github/workflows').glob('*.yml')):
        for name, job in yaml.safe_load(workflow.read_text())['jobs'].items():
            yield f'{workflow.name}:{name}', job.get('steps', [])


def sets_up_java(step):
    return 'actions/setup-java' in str(step.get('uses', ''))


class WorkflowTest(unittest.TestCase):
    def test_every_java_job_selects_the_job_repository(self):
        # Independent of how Maven is started (names of scripts change): a job with a JDK
        # can run Maven, so it selects the job repository right after its first setup-java.
        checked = 0
        for where, steps in workflow_jobs():
            java = [index for index, step in enumerate(steps) if sets_up_java(step)]
            selections = [index for index, step in enumerate(steps) if selects_repository(step)]
            if not java:
                self.assertEqual(selections, [], where)
                continue
            checked += 1
            self.assertEqual(len(selections), 1, where)
            self.assertLess(java[0], selections[0], where)
            self.assertFalse(any(sets_up_java(step) or 'run' in step for step in steps[java[0] + 1:selections[0]]), where)
        self.assertEqual(checked, 21)

    def test_every_maven_job_selects_the_job_repository_before_maven(self):
        checked = 0
        for where, steps in workflow_jobs():
            maven = [index for index, step in enumerate(steps) if runs_maven(step)]
            selections = [index for index, step in enumerate(steps) if selects_repository(step)]
            if not maven:
                continue
            checked += 1
            self.assertEqual(len(selections), 1, where)
            java = [index for index, step in enumerate(steps) if sets_up_java(step)]
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
                        'if [ -n "$STUB_FAILS" ]; then echo "[ERROR] $STUB_FAILS"; exit 1; fi\n'
                        'if [ -n "$STUB_RESOLVES" ]; then printf %s "$STUB_RESOLVES"; exit 0; fi\n'
                        'for argument in $MAVEN_ARGS; do case "$argument" in\n'
                        '  -Dmaven.repo.local=*) printf %s "${argument#-Dmaven.repo.local=}"; exit 0;;\n'
                        'esac; done\nprintf %s "$HOME/.m2/repository"\n')
        stub.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}', RUNNER_TEMP=str(self.temp / 'runner-temp'),
                        GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='3', GITHUB_JOB='downstream',
                        GITHUB_ENV=str(self.temp / 'env'), GITHUB_OUTPUT=str(self.temp / 'output'),
                        GITHUB_STATE=str(self.temp / 'state'))
        for variable in ['MAVEN_ARGS', 'STUB_RESOLVES', 'STUB_FAILS', 'UNLAXER_MAVEN_REPOSITORY']:
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

    def test_reports_why_maven_cannot_fill_the_empty_repository(self):
        # Maven -q prints [ERROR] on stdout; it must reach the log instead of a silent exit 1.
        result = self.run_action(STUB_FAILS='Cannot access central in offline mode')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('[ERROR] Cannot access central in offline mode', result.stderr)
        self.assertIn('::error::Maven could not resolve', result.stderr)
        self.assertIn('repo.maven.apache.org', result.stderr)
        self.assertFalse((self.temp / 'env').exists())

    def test_check_job_repository_reports_maven_failures(self):
        env = dict(self.env, STUB_FAILS='Could not transfer artifact', UNLAXER_MAVEN_REPOSITORY=str(self.temp / 'job'))
        result = subprocess.run(['bash', str(ROOT / 'scripts/check-job-maven-repository.sh'), str(ROOT), '3.3.0'],
                                env=env, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('[ERROR] Could not transfer artifact', result.stderr)
        self.assertIn('could not evaluate', result.stderr)

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
        # cwd: a relative path must not be resolved against the working directory.
        return subprocess.run(['node', str(ACTION / 'post.js')], cwd=self.temp, env=env, text=True, capture_output=True)

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
        foreign = [self.temp / 'home/.m2/repository', self.parent / '41-1-build', self.parent / '42-3-build/../../x',
                   # This attempt's name, but not directly below $RUNNER_TEMP/maven-repository.
                   self.temp / 'elsewhere/42-3-build', self.parent / 'nested/42-3-build',
                   Path('runner-temp/maven-repository/42-3-build')]
        for path in foreign:
            (self.temp / path).mkdir(parents=True, exist_ok=True)
            result = self.post(path)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('not removing', result.stdout, path)
            self.assertTrue((self.temp / path).is_dir(), path)

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

    def test_refuses_the_shared_repository(self):
        shared = Path.home() / '.m2/repository'
        for result in [self.run_script(), self.run_script('--skip-build'),
                       self.run_script('--maven-repo', str(shared)),
                       self.run_script(MAVEN_ARGS=f'-Dmaven.repo.local={shared}'),
                       # Maven uses the last -Dmaven.repo.local.
                       self.run_script(MAVEN_ARGS=f'-Dmaven.repo.local=/tmp/dedicated -Dmaven.repo.local={shared}')]:
            self.assertIn('refusing to use the shared Maven repository', result.stderr)

    def test_a_dedicated_repository_passes_the_guard(self):
        shared = Path.home() / '.m2/repository'
        for result in [self.run_script('--maven-repo', '/tmp/dedicated'),
                       self.run_script('--skip-build', '--maven-repo', '/tmp/dedicated'),
                       self.run_script(MAVEN_ARGS='-Dmaven.repo.local=/tmp/dedicated'),
                       self.run_script(MAVEN_ARGS=f'-Dmaven.repo.local={shared} -Dmaven.repo.local=/tmp/dedicated')]:
            self.assertNotIn('refusing to use', result.stderr)


class PublishedConsumerTest(unittest.TestCase):
    """Only a 404 from Central skips the consumer check; a network failure must not."""

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, True)
        bin_dir = self.temp / 'bin'
        bin_dir.mkdir()
        curl = bin_dir / 'curl'
        curl.write_text('#!/bin/sh\n'
                        'printf "%s\\n" "$*" >> "$STUB_LOG"\n'
                        'printf %s "$STUB_STATUS"\n'
                        'exit "${STUB_EXIT:-0}"\n')
        curl.chmod(0o755)
        mvn = bin_dir / 'mvn'
        mvn.write_text('#!/bin/sh\necho "mvn must not run" >&2; exit 99\n')
        mvn.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}', STUB_LOG=str(self.temp / 'curl.log'),
                        UNLAXER_MAVEN_REPOSITORY=str(self.temp / 'job'), RUNNER_TEMP=str(self.temp))

    def check(self, **stub):
        env = dict(self.env, **stub)
        return subprocess.run(['bash', str(ROOT / 'scripts/check-published-consumer.sh'), str(ROOT), '9.9.9'],
                              env=env, text=True, capture_output=True)

    def test_skips_only_an_unpublished_version(self):
        result = self.check(STUB_STATUS='404')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('is not on Central', result.stdout)
        self.assertIn('https://repo.maven.apache.org/maven2/org/unlaxer/', (self.temp / 'curl.log').read_text())

    def test_fails_when_central_cannot_be_asked(self):
        for stub in [{'STUB_STATUS': '503'}, {'STUB_STATUS': '000', 'STUB_EXIT': '6'}, {'STUB_STATUS': '403'}]:
            result = self.check(**stub)
            self.assertNotEqual(result.returncode, 0, stub)
            self.assertIn('cannot tell whether', result.stderr, stub)
            self.assertNotIn('is not on Central', result.stdout, stub)


if __name__ == '__main__':
    unittest.main()
