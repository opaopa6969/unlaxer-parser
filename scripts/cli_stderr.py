"""#937/#952: fixed stderr and output-path oracles for real CLIs, with LF/CRLF."""
import argparse
import json
import os
from pathlib import Path
import re
import resource
import signal
import tempfile

from cli_input_errors import run, setup, snapshot

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
    output = case.get('output', 'out')
    return {
        'inspect': ['deps', 'inspect'],
        'check': ['check', '--target', 'rust', '--format', 'json'],
        'generate': ['generate', '--target', 'rust', '--output', output] + case.get('flags', []),
        'playground': ['playground', '--output', output] + case.get('flags', []),
    }[operation] + ['--grammar', 'main.ubnf']


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
            path = artifact['root'] + ('/' + name if name else '')
            mode, content = value
            result[path] = (mode[host] if isinstance(mode, dict) else mode, content)
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
    actual = run(command, args, work, native, **limited(case))
    return {key: normalize(actual[key], work) if isinstance(actual[key], str) else actual[key]
            for key in ('exit', 'stdout', 'lastError')}, actual


def exercise(cases, directory, commands, evidence, trees=None):
    permissions = write_permissions_apply(directory)
    file_permissions = file_write_permissions_apply(directory)
    for case in cases:
        if ((case.get('readOnly') and not permissions)
                or (case.get('readOnlyFiles') and not file_permissions)):
            evidence['excluded'].append({'case': case['id'], 'observations': len(case['operations']) * 4,
                'reason': 'Execution user can write chmod(555/444); permission denial is not exercised.'})
            continue
        for ending, separator in [('LF', '\n'), ('CRLF', '\r\n')]:
            work = directory / (case['id'] + '-' + ending)
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
                        if host == 'rust' and 'nativeSyntax' in case:
                            expected = {**expected, 'lastError': case['nativeSyntax'][ending]}
                        assert observed == expected, (entry, expected)
                        # Includes absence of output, partial files, and playground staging.
                        # Existing caller-owned output/file cases must remain byte-for-byte intact;
                        # only the fixture's listed paths (Java's per-file writes) may appear.
                        after = snapshot(work)
                        if case['scope'] == 'paths':
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
                for name in case.get('readOnly', []):
                    (work / name).chmod(0o755)
                for name in case.get('readOnlyFiles', []):
                    (work / name).chmod(0o644)
    if os.environ.get('GITHUB_ACTIONS'):
        assert not evidence['excluded'], evidence['excluded']


def main():
    # Fixed permission oracle, independent of the invoking shell's umask.
    os.umask(0o022)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust', required=True)
    parser.add_argument('--suite', choices=['output', 'paths', 'utf8', 'readiness', 'syntax'], required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    corpus = json.loads((ROOT / 'spec-corpus/cli-stderr.json').read_text())
    cases = [c for c in corpus['cases'] if c['scope'] == args.suite]
    assert len(cases) == corpus['counts'][args.suite], 'fixtures must not silently disappear'
    evidence = dict(excluded=[], observations=[], checks=[])
    try:
        with tempfile.TemporaryDirectory(prefix='cli-stderr-', dir=os.environ.get('TMPDIR')) as directory:
            exercise(cases, Path(directory), [
                ('java', [args.java, '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']),
                ('rust', [args.rust])], evidence, corpus.get('trees'))
        excluded = {entry['case'] for entry in evidence['excluded']}
        assert len(evidence['observations']) == sum(len(c['operations']) * 4 for c in cases
                                                   if c['id'] not in excluded)
    finally:
        Path(args.output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
