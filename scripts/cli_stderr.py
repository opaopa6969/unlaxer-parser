"""#937/#952/#953/#974/#971: fixed stderr and output-path oracles for real CLIs, with LF/CRLF."""
import argparse
from contextlib import contextmanager
import json
import hashlib
import stat
import shutil
import os
from pathlib import Path
import re
import resource
import queue
import subprocess
import threading
import signal
import tempfile

from cli_input_errors import permissions_apply, run, setup, snapshot

ROOT = Path(__file__).resolve().parents[1]


def normalize(value, work):
    # Only the unpredictable staging basename is normalized; retain its parent path.
    value = value.replace(str(work), '${WORK}')
    return re.sub(r'(?<=/)\.ubnf-playground-[0-9]+(?:-[0-9]+)?(?=[:\s/]|$)',
                  '.ubnf-playground-${TEMP}', value)


def write_permissions_apply(directory):
    probe = directory / 'permission-probe'
    probe.mkdir()
    probe.chmod(0o555)
    try:
        try:
            (probe / 'child').write_bytes(b'probe')
            return False
        except PermissionError:
            return True
    finally:
        probe.chmod(0o755)
        for child in probe.iterdir():
            child.unlink()
        probe.rmdir()


def file_write_permissions_apply(directory):
    probe = directory / 'file-permission-probe'
    probe.write_bytes(b'probe')
    probe.chmod(0o444)
    try:
        try:
            with probe.open('r+b'):
                return False
        except PermissionError:
            return True
    finally:
        probe.chmod(0o644)
        probe.unlink()


def prepare(work, case, separator):
    setup(work, case, separator)
    for name, encoded in case.get('bytes', {}).items():
        (work / name).write_bytes(case.get('prefix', '').replace('\n', separator).encode()
                                  + bytes.fromhex(encoded))
    for name in case.get('readOnly', []):
        (work / name).chmod(0o555)
    for name in case.get('readOnlyFiles', []):
        (work / name).chmod(0o444)


def options(case, operation):
    if 'args' in case:
        return case['args']
    output = case.get('output', 'out')
    return {
        'inspect': ['deps', 'inspect'],
        'check': ['check', '--target', 'rust', '--format', 'json'],
        'generate': ['generate', '--target', 'rust', '--output', output] + case.get('flags', []),
        'playground': ['playground', '--output', output] + case.get('flags', []),
    }[operation] + (['--profile', case['profile']] if 'profile' in case
                    else ['--grammar', 'main.ubnf'])


def limited(case):
    if 'fileSizeLimit' not in case:
        return {}
    limit = case['fileSizeLimit']

    def apply():
        # A failed write must be reported as EFBIG (the ENOSPC class), not a SIGXFSZ kill.
        signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
        resource.setrlimit(resource.RLIMIT_FSIZE, (limit, limit))
    return dict(preexec_fn=apply, restore_signals=False)


def created(case, operation, host):
    expected = case.get('created', {}).get(operation, [])
    return expected[host] if isinstance(expected, dict) else expected


def expected_tree(case, operation, host, ending, trees):
    spec = case.get('tree', {}).get(operation, {})
    if 'java' in spec:
        spec = spec[host]
    result = {name: (0o40755, 'directory') for name in spec.get('directories', [])}
    for artifact in spec.get('artifacts', []):
        for name, value in trees[artifact['template']][ending].items():
            if 'include' in artifact and name not in artifact['include']:
                continue
            if name in artifact.get('exclude', []):
                continue
            path = artifact['root'] + ('/' + name if name else '')
            mode, content = value
            result[path] = (mode[host] if isinstance(mode, dict) else mode, content)
    result.update({name: tuple(value) for name, value in spec.get('files', {}).items()})
    return result


def remove_created(work, case, before):
    readonly = [work / name for name in case.get('readOnly', [])]
    for path in readonly:
        path.chmod(0o755)
    for name in sorted(set(snapshot(work)) - set(before), reverse=True):
        path = work / name
        path.rmdir() if path.is_dir() and not path.is_symlink() else path.unlink()
    for path in readonly:
        path.chmod(0o555)


def observe(command, case, operation, work, native):
    args = [arg.replace('${WORK}', str(work)) for arg in options(case, operation)]
    if callable(command) and case['scope'] == 'regex':
        # One batch JVM for this suite; resolve each case's inputs/output explicitly.
        for option in ('--grammar', '--output'):
            if option in args:
                index = args.index(option) + 1
                args[index] = str(work / args[index])
    actual = command(args) if callable(command) else run(command, args, work, native, **limited(case))
    if 'args' in case or callable(command):
        # Unicode separators in a nonblank path are part of the diagnostic, not LF.
        actual['lastError'] = actual['stderr'].removesuffix('\n').rsplit('\n', 1)[-1]
    keys = ('exit', 'stdout', 'lastError', 'stderr') if case['scope'] == 'regex' else ('exit', 'stdout', 'lastError')
    return {key: normalize(actual[key], work) if isinstance(actual[key], str) else actual[key]
            for key in keys}, actual


def exercise(cases, directory, commands, evidence, trees=None):
    read_permissions = permissions_apply(directory)
    permissions = write_permissions_apply(directory)
    file_permissions = file_write_permissions_apply(directory)
    for case in cases:
        if ((case.get('denied') and not read_permissions)
                or (case.get('readOnly') and not permissions)
                or (case.get('readOnlyFiles') and not file_permissions)):
            evidence['excluded'].append({'case': case['id'], 'observations': len(case['operations']) * 4,
                'reason': 'Execution user can access chmod(000/555/444); permission denial is not exercised.'})
            continue
        endings = [('argv', '\n')] if case['scope'] == 'blank' else [('LF', '\n'), ('CRLF', '\r\n')]
        for ending, separator in endings:
            # Blank argv cases have no inputs and require an empty tree after every call.
            # Sharing this cwd lets CodegenBatchHost use one JVM for the whole table.
            work = directory / ('blank-argv' if case['scope'] == 'blank' else case['id'] + '-' + ending)
            if case['scope'] != 'blank' or not work.exists():
                prepare(work, case, separator)
            try:
                before = snapshot(work)
                for operation in case['operations']:
                    for host, command in commands:
                        observed, raw = observe(command, case, operation, work, host == 'rust')
                        entry = dict(case=case['id'], ending=ending, operation=operation, host=host,
                                     observed=observed, raw=raw)
                        evidence['observations'].append(entry)
                        print(json.dumps(entry, ensure_ascii=False), flush=True)
                        expected = case['expected'][ending][operation]
                        if host == 'rust' and operation in case.get('nativeExpected', {}):
                            expected = case['nativeExpected'][operation]
                        if host == 'rust' and operation in case.get('nativeByEnding', {}).get(ending, {}):
                            expected = case['nativeByEnding'][ending][operation]
                        if host == 'rust' and 'nativeSyntax' in case:
                            expected = {**expected, 'lastError': case['nativeSyntax'][ending]}
                        assert observed == expected, (entry, expected)
                        # Includes absence of output, partial files, and playground staging.
                        # Existing caller-owned output/file cases must remain byte-for-byte intact;
                        # only the fixture's listed paths (Java's per-file writes) may appear.
                        after = snapshot(work)
                        if case['scope'] in ('paths', 'blank') or 'tree' in case:
                            entry['before'] = before
                            entry['after'] = after
                            assert after == {**before, **expected_tree(case, operation, host, ending, trees)}, (entry, 'tree')
                            if observed['exit'] == 0:
                                checked, _ = observe(command, {**case, 'flags': ['--check']},
                                                     operation, work, host == 'rust')
                                evidence['checks'].append(dict(case=case['id'], ending=ending,
                                                              operation=operation, host=host, observed=checked))
                                assert checked == {**expected, 'stdout': expected['stdout'].replace('Generated ', 'Verified ', 1)}, checked
                                assert snapshot(work) == after, (entry, '--check wrote files')
                        else:
                            assert {name: after.get(name) for name in before} == before, (entry, 'changed')
                            new = sorted(normalize('/' + name, work)[1:] for name in set(after) - set(before))
                            assert new == created(case, operation, host), (entry, new)
                        remove_created(work, case, before)
            finally:
                if 'denied' in case:
                    (work / case['denied']).chmod(0o600)
                for name in case.get('readOnly', []):
                    (work / name).chmod(0o755)
                for name in case.get('readOnlyFiles', []):
                    (work / name).chmod(0o644)
    if os.environ.get('GITHUB_ACTIONS'):
        assert not evidence['excluded'], evidence['excluded']


def replacement_snapshot(work):
    """Include link counts for every entry, including independent directory counts."""
    return {name: (mode, (work / name).lstat().st_nlink, content)
            for name, (mode, content) in snapshot(work).items()}


def replacement_expected(work, case, ending, trees, before, host):
    # Fixed generated hashes come from the shared corpus, never a CLI observation.
    operation = case['operations'][0]
    spec = expected_tree(case, operation, host, ending, trees)
    result = dict(before)
    for name, (mode, content) in spec.items():
        if stat.S_ISREG(mode):
            # A non-root write clears set-user-ID, and set-group-ID with group execute.
            mode = stat.S_IFREG | case.get('writtenModes', {}).get(name, case.get('modes', {}).get(
                name, 0o666 & ~case.get('umask', 0o022)))
        elif name in case.get('readOnly', []):
            mode = stat.S_IFDIR | 0o555
        elif stat.S_ISDIR(mode) and not (operation == 'playground' and name == 'out' and host == 'java'):
            mode = stat.S_IFDIR | (0o777 & ~case.get('umask', 0o022))
        result[name] = (mode, 1, content)
    for alias, target in case.get('hardlinks', {}).items():
        result[alias] = result[target]
    for name, (mode, _, content) in list(result.items()):
        if stat.S_ISDIR(mode):
            links = 2 + sum(stat.S_ISDIR(v[0]) and str(Path(child).parent) == name
                            for child, v in result.items())
        else:
            links = 1 + sum(target == name for target in case.get('hardlinks', {}).values())
            if name in case.get('hardlinks', {}):
                links = result[case['hardlinks'][name]][1]
        result[name] = (mode, links, content)
    # An alias can sort before its target.
    for alias, target in case.get('hardlinks', {}).items():
        result[alias] = result[target]
    return result


def replacement_cases(cases, directory, commands, evidence, trees):
    """Reset the same cwd before *each* host; one JVM serves successful writes."""
    work = directory / 'replacement'
    work.mkdir(exist_ok=True)
    permissions = write_permissions_apply(directory)
    file_permissions = file_write_permissions_apply(directory)
    for case in cases:
        if ((case.get('readOnly') and not permissions)
                or (case.get('readOnlyFiles') and not file_permissions)):
            evidence['excluded'].append(dict(case=case['id'], observations=4,
                reason='Execution user bypasses chmod(555/444); non-root permission behavior cannot be exercised.'))
            continue
        if case.get('writtenModes') and os.geteuid() == 0:
            evidence['excluded'].append(dict(case=case['id'], observations=4,
                reason='CAP_FSETID keeps set-ID bits on write; the non-root clearing cannot be exercised.'))
            continue
        for ending, separator in [('LF', '\n'), ('CRLF', '\r\n')]:
            for host, command in commands:
                os.umask(case.get('umask', 0o022))
                # Keep cwd itself for the batch host, recreate all caller-owned inputs.
                seed = work / 'fixture-seed'
                prepare(seed, {**case, 'readOnly': [], 'readOnlyFiles': []}, separator)
                for child in seed.iterdir():
                    child.rename(work / child.name)
                seed.rmdir()
                for name in case.get('readOnly', []):
                    (work / name).chmod(0o555)
                for name in case.get('readOnlyFiles', []):
                    (work / name).chmod(0o444)
                for name, mode in case.get('modes', {}).items():
                    (work / name).chmod(mode)
                for alias, target in case.get('hardlinks', {}).items():
                    os.link(work / target, work / alias)
                before = replacement_snapshot(work)
                initial = {}
                mask = case.get('umask', 0o022)
                for name, text in case['files'].items():
                    initial[name] = (stat.S_IFREG | case.get('modes', {}).get(name,
                        0o444 if name in case.get('readOnlyFiles', []) else 0o666 & ~mask),
                        hashlib.sha256(text.replace('\n', separator).encode()).hexdigest())
                    for parent in Path(name).parents:
                        if str(parent) != '.':
                            initial[str(parent)] = (stat.S_IFDIR | (
                                0o555 if str(parent) in case.get('readOnly', []) else 0o777 & ~mask), 'directory')
                for alias, target in case.get('hardlinks', {}).items():
                    initial[alias] = initial[target]
                # Reuse only the metadata-count expansion, with no generated artifacts.
                initial_case = {**case, 'tree': {}}
                wanted_before = replacement_expected(work, initial_case, ending, trees,
                    {name: (mode, 1, content) for name, (mode, content) in initial.items()}, host)
                assert before == wanted_before, (case['id'], 'fixture preparation', before, wanted_before)
                try:
                    actual, raw = observe(command, case, case['operations'][0], work, host == 'rust')
                    expected = case['expected'][ending][case['operations'][0]]
                    after = replacement_snapshot(work)
                    entry = dict(case=case['id'], ending=ending, host=host,
                                 observed=actual, raw=raw, before=before, after=after)
                    evidence['observations'].append(entry)
                    print(json.dumps(entry, ensure_ascii=False), flush=True)
                    assert actual == expected, (entry, expected)
                    if 'failureBytes' in case:
                        wanted = dict(before)
                        digest = (before['out/mod.rs'][2] if host == 'rust' and case.get('preserveOnFailure')
                                  else hashlib.sha256(case['failureBytes'].encode()).hexdigest())
                        wanted['out/mod.rs'] = (*before['out/mod.rs'][:2], digest)
                        for alias in case.get('hardlinks', {}):
                            wanted[alias] = wanted['out/mod.rs']
                    else:
                        # Playground uses its own fixed tree and preexisting host-specific root mode.
                        wanted = replacement_expected(work, case, ending, trees, before, host)
                    assert after == wanted, (entry, wanted)
                    if actual['exit'] == 0:
                        checked, _ = observe(command, {**case, 'flags': ['--check']},
                                             case['operations'][0], work, host == 'rust')
                        evidence['checks'].append(dict(case=case['id'], ending=ending, host=host, observed=checked))
                        assert checked == {**expected, 'stdout': expected['stdout'].replace('Generated ', 'Verified ', 1)}, checked
                        assert replacement_snapshot(work) == after, '--check changed metadata/content'
                finally:
                    for name in case.get('readOnly', []):
                        (work / name).chmod(0o755)
                    for name in case.get('readOnlyFiles', []):
                        (work / name).chmod(0o644)
                    for child in work.iterdir():
                        if child.is_dir():
                            shutil.rmtree(child)
                        else:
                            child.unlink()
    os.umask(0o022)
    if os.environ.get('GITHUB_ACTIONS'):
        assert not evidence['excluded'], evidence['excluded']


def blank_option_cases(table):
    """Expand fixed argv/expected tables; never derive an oracle from either CLI."""
    for value in table['values']:
        for option in table['options']:
            expected = (dict(exit=2, stdout='', lastError=table['usage']['java']) if value['blank']
                        else {key: text.replace('${VALUE}', value['value']) if isinstance(text, str) else text
                              for key, text in option['nonblank'].items()})
            case = dict(id='blank-' + option['option'] + '-' + value['id'], scope='blank',
                        operations=['playground'], args=[arg.replace('${VALUE}', value['value'])
                                                       for arg in option['args']],
                        expected={'argv': {'playground': expected}})
            if value['blank']:
                case['nativeExpected'] = {'playground': dict(exit=2, stdout='', lastError=table['usage']['rust'])}
            yield case


@contextmanager
def blank_java_host(java, classpath, work):
    """Test-only batch host delegates every vector to the real CodegenMain.run."""
    env = dict(os.environ, LC_ALL='C.UTF-8')
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen([java, '-Djava.io.tmpdir=' + str(work), '-cp', classpath,
                                    'org.unlaxer.dsl.CodegenBatchHost'], cwd=work, env=env,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors)
        lines = queue.Queue()
        reader = threading.Thread(target=lambda: [lines.put(line) for line in process.stdout]
                                  + [lines.put(None)], daemon=True)
        reader.start()

        def call(args):
            process.stdin.write((json.dumps(args) + '\n').encode())
            process.stdin.flush()
            try:
                line = lines.get(timeout=30)
            except queue.Empty:
                raise AssertionError('Java blank-option fixture timed out') from None
            assert line is not None, 'Java blank-option host stopped'
            actual = json.loads(line)
            return actual

        try:
            yield call
        finally:
            process.stdin.close()
            try:
                assert process.wait(timeout=5) == 0, 'Java blank-option host failed'
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                process.stdout.close()
                reader.join(timeout=5)


def main():
    # Fixed permission oracle, independent of the invoking shell's umask.
    os.umask(0o022)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust', required=True)
    parser.add_argument('--suite', choices=['output', 'paths', 'utf8', 'readiness', 'syntax', 'profile', 'blank', 'regex', 'replacement'], required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    corpus = json.loads((ROOT / 'spec-corpus/cli-stderr.json').read_text())
    cases = (list(blank_option_cases(corpus['blankOptions'])) if args.suite == 'blank'
             else [c for c in corpus['cases'] if c['scope'] == args.suite])
    assert len(cases) == corpus['counts'][args.suite], 'fixtures must not silently disappear'
    evidence = dict(excluded=[], observations=[], checks=[])
    try:
        with tempfile.TemporaryDirectory(prefix='cli-stderr-', dir=os.environ.get('TMPDIR')) as directory:
            if args.suite == 'replacement':
                work = Path(directory) / 'replacement'
                work.mkdir()
                # Permission/size failure vectors use real processes with rlimits; successful
                # vectors use the existing real CodegenMain.run batch protocol.
                batch_cases = [c for c in cases if 'fileSizeLimit' not in c and c.get('umask', 0o022) == 0o022]
                direct_cases = [c for c in cases if c not in batch_cases]
                with blank_java_host(args.java, args.classpath, work) as host:
                    replacement_cases(batch_cases, Path(directory),
                                      [('java', host), ('rust', [args.rust])], evidence, corpus['trees'])
                work.rmdir()
                replacement_cases(direct_cases, Path(directory), [
                    ('java', [args.java, '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']),
                    ('rust', [args.rust])], evidence, corpus['trees'])
            elif args.suite == 'blank':
                work = Path(directory) / 'blank-argv'
                work.mkdir()
                with blank_java_host(args.java, args.classpath, work) as host:
                    exercise(cases, Path(directory), [('java', host), ('rust', [args.rust])], evidence)
            elif args.suite == 'regex':
                with blank_java_host(args.java, args.classpath, Path(directory)) as host:
                    exercise(cases, Path(directory), [('java', host), ('rust', [args.rust])], evidence)
            else:
                exercise(cases, Path(directory), [
                    ('java', [args.java, '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']),
                    ('rust', [args.rust])], evidence, corpus.get('trees'))
        excluded = {entry['case'] for entry in evidence['excluded']}
        assert len(evidence['observations']) == sum(len(c['operations']) * (2 if args.suite == 'blank' else 4) for c in cases
                                                   if c['id'] not in excluded)
    finally:
        Path(args.output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
