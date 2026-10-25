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


HELP_JAR = 'org/apache/maven/plugins/maven-help-plugin/3.5.1/maven-help-plugin-3.5.1.jar'


def make_repository(root, *relatives):
    """A local repository with one file below each relative directory."""
    for relative in relatives:
        (root / relative).mkdir(parents=True, exist_ok=True)
        (root / relative / 'marker').write_text(relative)
    root.mkdir(parents=True, exist_ok=True)
    return root


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
                        'printf "%s\\n" "$*" >> "$STUB_LOG"\n'
                        'if [ -n "$STUB_FAILS" ]; then echo "[ERROR] $STUB_FAILS"; exit 1; fi\n'
                        'case " $* " in *" -o "*) if [ -n "$STUB_OFFLINE_FAILS" ]; then echo "[ERROR] offline"; exit 1; fi;; esac\n'
                        'if [ -n "$STUB_RESOLVES" ]; then printf %s "$STUB_RESOLVES"; exit 0; fi\n'
                        'head="$HOME/.m2/repository"\n'
                        'for argument in $MAVEN_ARGS; do case "$argument" in\n'
                        '  -Dmaven.repo.local=*) head="${argument#-Dmaven.repo.local=}";;\n'
                        'esac; done\n'
                        'if [ -n "$STUB_DOWNLOADS" ]; then mkdir -p "$head/${STUB_DOWNLOADS%/*}"; : > "$head/$STUB_DOWNLOADS"; fi\n'
                        'printf %s "$head"\n')
        stub.chmod(0o755)
        # A HOME without ~/.m2: tests that want the shared repository create it.
        self.home = self.temp / 'home'
        self.home.mkdir()
        self.env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}', RUNNER_TEMP=str(self.temp / 'runner-temp'),
                        GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='3', GITHUB_JOB='downstream',
                        GITHUB_ENV=str(self.temp / 'env'), GITHUB_OUTPUT=str(self.temp / 'output'),
                        GITHUB_STATE=str(self.temp / 'state'), HOME=str(self.home), STUB_LOG=str(self.temp / 'mvn.log'))
        for variable in ['MAVEN_ARGS', 'STUB_RESOLVES', 'STUB_FAILS', 'STUB_OFFLINE_FAILS', 'STUB_DOWNLOADS',
                         'UNLAXER_MAVEN_REPOSITORY']:
            self.env.pop(variable, None)
        self.repository = Path(self.env['RUNNER_TEMP']) / 'maven-repository/42-3-downstream'
        self.view = Path(self.env['RUNNER_TEMP']) / 'maven-repository/42-3-downstream.tail'

    def exported_maven_args(self):
        return (self.temp / 'env').read_text().splitlines()[0]

    def run_action(self, **overrides):
        env = dict(self.env, **overrides)
        return subprocess.run(['bash', '-c', self.script], env=env, text=True, capture_output=True)

    def test_creates_a_fresh_repository_and_exports_it(self):
        # No runner repository and no ~/.m2: no tail, as before the tail existed.
        result = self.run_action(MAVEN_ARGS='-e')
        self.assertEqual(result.returncode, 0, result.stderr)
        repository = self.repository
        self.assertTrue(repository.is_dir())
        self.assertEqual(list(repository.iterdir()), [])
        self.assertEqual((self.temp / 'env').read_text().splitlines(),
                         [f'MAVEN_ARGS=-e -Dmaven.repo.local={repository}', f'UNLAXER_MAVEN_REPOSITORY={repository}'])
        self.assertEqual((self.temp / 'output').read_text(), f'path={repository}\n')
        self.assertEqual((self.temp / 'state').read_text(), f'repository={repository}\n')
        self.assertFalse(self.view.exists())
        self.assertIn('without a tail', result.stdout)

    def assert_view(self, view, source):
        """The view links every entry of the source except org/unlaxer."""
        self.assertTrue(view.is_dir() and not view.is_symlink(), view)
        expected = {entry.name for entry in source.iterdir()}
        self.assertEqual({entry.name for entry in view.iterdir()}, expected)
        for entry in view.iterdir():
            if entry.name != 'org':
                self.assertEqual(os.readlink(entry), str(source / entry.name))
        org = view / 'org'
        self.assertFalse(org.is_symlink())
        self.assertEqual({entry.name for entry in org.iterdir()},
                         {entry.name for entry in (source / 'org').iterdir()} - {'unlaxer'})
        for entry in org.iterdir():
            self.assertEqual(os.readlink(entry), str(source / 'org' / entry.name))

    def test_shared_repository_becomes_a_tail_without_org_unlaxer(self):
        shared = make_repository(self.home / '.m2/repository', 'org/unlaxer/unlaxer-common/3.3.0',
                                 'org/apache/maven', 'com/example', 'junit')
        result = self.run_action(MAVEN_ARGS='-e')
        self.assertEqual(result.returncode, 0, result.stderr)
        view = self.view / '1'
        self.assertEqual(self.exported_maven_args(),
                         f'MAVEN_ARGS=-e -Dmaven.repo.local={self.repository} -Dmaven.repo.local.tail={view}')
        self.assert_view(view, shared)
        self.assertEqual(list(self.view.iterdir()), [view])
        self.assertEqual((self.temp / 'state').read_text().splitlines(),
                         [f'repository={self.repository}', f'tail={self.view}'])
        # The head still starts empty, and the sources are untouched.
        self.assertEqual(list(self.repository.iterdir()), [])
        self.assertEqual((shared / 'org/unlaxer/unlaxer-common/3.3.0/marker').read_text(), 'org/unlaxer/unlaxer-common/3.3.0')

    def test_runner_repository_from_maven_args_is_the_first_tail(self):
        # Runners 2 and 3: the service selects its own repository; it is read, never written.
        runner = make_repository(self.temp / 'runner-3/m2/repository', 'org/unlaxer/tinyExpression', 'org/ow2')
        shared = make_repository(self.home / '.m2/repository', 'org/unlaxer/unlaxer-dsl', 'org/codehaus')
        result = self.run_action(MAVEN_ARGS=f'-e -Dmaven.repo.local=/elsewhere -Dmaven.repo.local={runner} -U')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"the runner's Maven repository {runner} is read-only", result.stdout)
        first, second = self.view / '1', self.view / '2'
        self.assertEqual(self.exported_maven_args(),
                         f'MAVEN_ARGS=-e -U -Dmaven.repo.local={self.repository} -Dmaven.repo.local.tail={first},{second}')
        self.assert_view(first, runner)
        self.assert_view(second, shared)

    def test_a_missing_runner_repository_falls_back_to_the_shared_one(self):
        shared = make_repository(self.home / '.m2/repository', 'org/apache')
        result = self.run_action(MAVEN_ARGS=f'-Dmaven.repo.local={self.temp / "missing"}')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.exported_maven_args(),
                         f'MAVEN_ARGS=-Dmaven.repo.local={self.repository} -Dmaven.repo.local.tail={self.view / "1"}')
        self.assert_view(self.view / '1', shared)

    def test_the_same_repository_is_one_tail(self):
        shared = make_repository(self.home / '.m2/repository', 'org/apache')
        result = self.run_action(MAVEN_ARGS=f'-Dmaven.repo.local={self.home}/.m2/../.m2/repository')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(self.view.iterdir()), [self.view / '1'])
        self.assert_view(self.view / '1', shared)

    def test_replaces_a_tail_the_runner_selected(self):
        make_repository(self.home / '.m2/repository', 'org/apache')
        result = self.run_action(MAVEN_ARGS='-Dmaven.repo.local.tail=/runner/tail -Dmaven.repo.local.tail.ignoreAvailability=false')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.exported_maven_args(),
                         f'MAVEN_ARGS=-Dmaven.repo.local={self.repository} -Dmaven.repo.local.tail={self.view / "1"}')

    def test_evaluates_offline_first_and_then_online(self):
        make_repository(self.home / '.m2/repository', 'org/apache')
        self.assertEqual(self.run_action().returncode, 0)
        self.assertEqual(len((self.temp / 'mvn.log').read_text().splitlines()), 1)
        self.assertIn(' -o ', (self.temp / 'mvn.log').read_text())
        shutil.rmtree(self.env['RUNNER_TEMP'])
        (self.temp / 'mvn.log').unlink()
        result = self.run_action(STUB_OFFLINE_FAILS='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = (self.temp / 'mvn.log').read_text().splitlines()
        self.assertEqual(len(calls), 2)
        self.assertIn(' -o ', calls[0])
        self.assertNotIn(' -o ', calls[1])

    def test_rejects_a_tail_that_maven_does_not_use(self):
        # The tail has the help plugin, yet Maven downloaded it into the head.
        make_repository(self.home / '.m2/repository', HELP_JAR.rsplit('/', 1)[0])
        (self.home / '.m2/repository' / HELP_JAR).write_text('jar')
        result = self.run_action(STUB_DOWNLOADS=HELP_JAR)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('the tail is not in effect', result.stderr)
        self.assertFalse((self.temp / 'env').exists())
        # Without the download, the same tail passes.
        shutil.rmtree(self.env['RUNNER_TEMP'])
        for name in ['state', 'output']:
            (self.temp / name).unlink(missing_ok=True)
        self.assertEqual(self.run_action().returncode, 0)

    def test_failure_with_a_tail_names_it(self):
        make_repository(self.home / '.m2/repository', 'org/apache')
        result = self.run_action(STUB_FAILS='Could not transfer artifact ... status code: 429')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('[ERROR] Could not transfer artifact', result.stderr)
        self.assertIn('missing from the read-only tail', result.stderr)
        self.assertIn('repo.maven.apache.org', result.stderr)
        self.assertNotIn('empty', result.stderr)
        # The post step still removes both directories.
        self.assertEqual((self.temp / 'state').read_text().splitlines(),
                         [f'repository={self.repository}', f'tail={self.view}'])

    def test_reports_why_maven_cannot_fill_the_empty_repository(self):
        # Maven -q prints [ERROR] on stdout; it must reach the log instead of a silent exit 1.
        result = self.run_action(STUB_FAILS='Cannot access central in offline mode')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('[ERROR] Cannot access central in offline mode', result.stderr)
        self.assertIn('::error::Maven could not resolve', result.stderr)
        self.assertIn('there is no tail', result.stderr)
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
        self.assertIn("the runner's Maven repository /home/runner-3/m2/repository is read-only", result.stdout)
        # It does not exist here, and neither does ~/.m2: no tail.
        self.assertEqual(self.exported_maven_args(), f'MAVEN_ARGS=-e -U -Dmaven.repo.local={self.repository}')

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
        # Maven splits the tail at commas.
        self.assertIn('comma', self.run_action(RUNNER_TEMP=str(self.temp / 'has,comma')).stderr)


@unittest.skipUnless(shutil.which('node'), 'node is required for the post step')
class PostStepTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, True)
        self.parent = self.temp / 'runner-temp/maven-repository'
        self.env = dict(os.environ, RUNNER_TEMP=str(self.temp / 'runner-temp'), GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='3')

    def post(self, repository, tail=None):
        env = dict(self.env, STATE_repository=str(repository))
        if tail is not None:
            env['STATE_tail'] = str(tail)
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

    def test_removes_the_tail_view_without_following_its_links(self):
        shared = make_repository(self.temp / 'home/.m2/repository', 'org/unlaxer/unlaxer-common', 'org/apache', 'junit')
        mine = self.parent / '42-3-build'
        mine.mkdir(parents=True)
        view = self.parent / '42-3-build.tail'
        (view / '1/org').mkdir(parents=True)
        (view / '1/junit').symlink_to(shared / 'junit')
        (view / '1/org/apache').symlink_to(shared / 'org/apache')
        result = self.post(mine, view)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(view.exists())
        self.assertIn('Removed job-local Maven tail view', result.stdout)
        for relative in ['org/unlaxer/unlaxer-common', 'org/apache', 'junit']:
            self.assertEqual((shared / relative / 'marker').read_text(), relative)

    def test_never_removes_a_tail_it_did_not_create(self):
        mine = self.parent / '42-3-build'
        mine.mkdir(parents=True)
        for foreign in [self.temp / 'home/.m2/repository', self.parent / '41-1-build.tail']:
            foreign.mkdir(parents=True, exist_ok=True)
            result = self.post(mine, foreign)
            self.assertIn('not removing', result.stdout, foreign)
            self.assertTrue(foreign.is_dir(), foreign)
        # A link in place of the view is not followed either.
        link = self.parent / '42-3-build.tail'
        link.symlink_to(self.temp / 'home/.m2/repository')
        self.assertIn('not a directory', self.post(self.parent / '42-3-other', link).stdout)
        self.assertTrue((self.temp / 'home/.m2/repository').is_dir())

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
        # Offline when the tail (~/.m2) has the plugin: no request to Central, which may answer 429.
        offline = ['-o'] if (Path.home() / '.m2/repository/org/apache/maven/plugins/maven-help-plugin/3.5.1').is_dir() else []
        command = ['mvn', '-B', '-q', '-N', *offline, *arguments, 'org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate',
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


SHARED = Path.home() / '.m2/repository'
TAIL_PLUGINS = ['org/apache/maven/plugins/maven-dependency-plugin/3.8.1', 'org/apache/maven/plugins/maven-install-plugin/3.1.2',
                'org/apache/maven/plugins/maven-help-plugin/3.5.1']


@unittest.skipUnless(shutil.which('mvn') and all((SHARED / plugin).is_dir() for plugin in TAIL_PLUGINS),
                     'Maven and the plugins in ~/.m2 are required (resolved offline from the tail)')
class TailResolutionTest(unittest.TestCase):
    """Real Maven, offline: the head is written and preferred, the tail is only read, org/unlaxer is hidden."""

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, True)
        # As on runners 2 and 3: the service's own repository, here not writable at all.
        self.runner = self.temp / 'runner-repository'
        for group, content in [('example', 'tail'), ('unlaxer', 'tail-unlaxer')]:
            directory = self.runner / 'org' / group / 'demo/1.0'
            directory.mkdir(parents=True)
            (directory / 'demo-1.0.jar').write_text(content)
            (directory / 'demo-1.0.pom').write_text(
                f'<project><modelVersion>4.0.0</modelVersion><groupId>org.{group}</groupId>'
                '<artifactId>demo</artifactId><version>1.0</version></project>\n')
            (directory / '_remote.repositories').write_text('demo-1.0.jar>central=\ndemo-1.0.pom>central=\n')
        self.before = self.snapshot(self.runner)
        self.set_writable(False)
        self.addCleanup(self.set_writable, True)
        self.project = self.temp / 'project'
        self.project.mkdir()
        self.env = dict(os.environ, RUNNER_TEMP=str(self.temp / 'runner-temp'), GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1',
                        GITHUB_JOB='build', GITHUB_ENV=str(self.temp / 'env'), GITHUB_OUTPUT=str(self.temp / 'output'),
                        GITHUB_STATE=str(self.temp / 'state'), MAVEN_ARGS=f'-Dmaven.repo.local={self.runner}')
        self.env.pop('UNLAXER_MAVEN_REPOSITORY', None)
        selected = subprocess.run(['bash', str(ACTION / 'select.sh')], cwd=self.project, env=self.env, text=True,
                                  capture_output=True)
        self.assertEqual(selected.returncode, 0, selected.stdout + selected.stderr)
        exported = dict(line.split('=', 1) for line in (self.temp / 'env').read_text().splitlines())
        self.env.update(exported)
        self.head = Path(exported['UNLAXER_MAVEN_REPOSITORY'])
        # The runner's repository first, the shared ~/.m2 (plugins) second.
        self.assertIn('-Dmaven.repo.local.tail=', self.env['MAVEN_ARGS'])

    def set_writable(self, writable):
        for directory, _, files in os.walk(self.runner):
            for path in [directory] + [os.path.join(directory, name) for name in files]:
                mode = os.stat(path).st_mode
                os.chmod(path, mode | 0o200 if writable else mode & ~0o222)

    @staticmethod
    def snapshot(root):
        return {str(path.relative_to(root)): path.read_bytes() if path.is_file() else None for path in root.rglob('*')}

    def mvn(self, *arguments):
        return subprocess.run(['mvn', '-B', '-q', '-o', *arguments], cwd=self.project, env=self.env, text=True,
                              capture_output=True)

    def resolve(self, group):
        output = self.temp / 'copied'
        shutil.rmtree(output, True)
        result = self.mvn('org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy', f'-Dartifact=org.{group}:demo:1.0',
                          f'-DoutputDirectory={output}')
        return (output / 'demo-1.0.jar').read_text() if result.returncode == 0 else None

    def test_the_head_is_written_and_wins_over_the_tail(self):
        self.assertEqual(self.resolve('example'), 'tail')
        built = self.temp / 'built.jar'
        built.write_text('head')
        installed = self.mvn('org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file', f'-Dfile={built}',
                             '-DgroupId=org.example', '-DartifactId=demo', '-Dversion=1.0', '-Dpackaging=jar')
        self.assertEqual(installed.returncode, 0, installed.stdout + installed.stderr)
        self.assertEqual((self.head / 'org/example/demo/1.0/demo-1.0.jar').read_text(), 'head')
        self.assertEqual(self.resolve('example'), 'head')
        # Nothing was written to the tail (it is not writable, and its bytes are unchanged).
        self.assertEqual(self.snapshot(self.runner), self.before)

    def test_org_unlaxer_of_the_tail_is_not_visible(self):
        # Development installs of the release version live there; only the head or Central may supply them.
        self.assertIsNone(self.resolve('unlaxer'))
        self.assertTrue((self.runner / 'org/unlaxer/demo/1.0/demo-1.0.jar').is_file())

    def test_plugins_come_from_the_tail_without_downloads(self):
        self.assertEqual(self.resolve('example'), 'tail')
        self.assertFalse((self.head / 'org/apache/maven/plugins/maven-dependency-plugin').exists())


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
        # STUB_SEQUENCE: one "<status> [<exit>]" line per call, taken in order.
        curl = bin_dir / 'curl'
        curl.write_text('#!/bin/sh\n'
                        'printf "%s\\n" "$*" >> "$STUB_LOG"\n'
                        'if [ -n "$STUB_SEQUENCE" ]; then\n'
                        '  line=$(head -n 1 "$STUB_SEQUENCE"); sed -i 1d "$STUB_SEQUENCE"\n'
                        '  set -- $line; printf %s "$1"; exit "${2:-0}"\n'
                        'fi\n'
                        'printf %s "$STUB_STATUS"\n'
                        'exit "${STUB_EXIT:-0}"\n')
        curl.chmod(0o755)
        # MVN_OUTPUT: what a failing Maven prints; without it Maven must not run.
        mvn = bin_dir / 'mvn'
        mvn.write_text('#!/bin/sh\n'
                       'echo run >> "$MVN_LOG"\n'
                       'if [ -z "$MVN_OUTPUT" ]; then echo "mvn must not run" >&2; exit 99; fi\n'
                       'printf "%s\\n" "$MVN_OUTPUT"; exit 1\n')
        mvn.chmod(0o755)
        sleep = bin_dir / 'sleep'
        sleep.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> "$SLEEP_LOG"\n')
        sleep.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}', STUB_LOG=str(self.temp / 'curl.log'),
                        MVN_LOG=str(self.temp / 'mvn.log'), SLEEP_LOG=str(self.temp / 'sleep.log'),
                        UNLAXER_MAVEN_REPOSITORY=str(self.temp / 'job'), RUNNER_TEMP=str(self.temp))

    def sequence(self, *lines):
        path = self.temp / 'sequence'
        path.write_text(''.join(f'{line}\n' for line in lines))
        return str(path)

    def calls(self, name):
        path = self.temp / name
        return path.read_text().splitlines() if path.exists() else []

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

    def test_retries_a_rate_limit_with_growing_delays_until_central_answers(self):
        result = self.check(STUB_SEQUENCE=self.sequence('429', '000 28', '404'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('is not on Central', result.stdout)
        self.assertEqual(self.calls('sleep.log'), ['20', '40'])
        self.assertEqual(len(self.calls('curl.log')), 3)

    def test_gives_up_on_an_unreachable_central_after_bounded_attempts(self):
        result = self.check(STUB_STATUS='429')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('cannot tell whether', result.stderr)
        self.assertEqual(self.calls('sleep.log'), ['20', '40', '80', '160'])
        self.assertEqual(len(self.calls('curl.log')), 5)

    def test_an_answer_from_central_is_not_retried(self):
        for status in ['403', '404', '401']:
            (self.temp / 'curl.log').unlink(missing_ok=True)
            self.check(STUB_STATUS=status)
            self.assertEqual(len(self.calls('curl.log')), 1, status)
        self.assertEqual(self.calls('sleep.log'), [])

    def test_retries_maven_only_when_it_did_not_reach_central(self):
        unreachable = ('[ERROR] Failed to read artifact descriptor for org.unlaxer:unlaxer-common:jar:9.9.9: '
                       'status code: 429, reason phrase: Too Many Requests (429)')
        result = self.check(STUB_STATUS='200', MVN_OUTPUT=unreachable)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.calls('mvn.log')), 5)
        self.assertEqual(self.calls('sleep.log'), ['20', '40', '80', '160'])
        for answer in ['[ERROR] Could not find artifact org.unlaxer:unlaxer-common:jar:9.9.9 in central',
                       '[ERROR] Could not transfer artifact org.unlaxer:unlaxer-common:jar:9.9.9 from/to central: '
                       'Checksum validation failed, expected abc but is def; status code: 503']:
            for name in ['mvn.log', 'sleep.log']:
                (self.temp / name).unlink(missing_ok=True)
            result = self.check(STUB_STATUS='200', MVN_OUTPUT=answer)
            self.assertNotEqual(result.returncode, 0, answer)
            self.assertEqual(len(self.calls('mvn.log')), 1, answer)
            self.assertEqual(self.calls('sleep.log'), [], answer)


if __name__ == '__main__':
    unittest.main()
