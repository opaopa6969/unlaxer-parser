"""#932/#934: independent grammar I/O oracles through Java and JVM-free native CLIs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OPERATIONS = ('inspect', 'check', 'generate', 'playground')
MODULE_REPORT = ('{"schemaVersion":1,"target":"rust","portable":false,"structure":"blocked",'
                 '"diagnostics":[{"code":"P-MODULE","severity":"error","span":null,'
                 '"subject":"UBNF module resolution"}]}\n')
PASSED_REPORT = ('{"schemaVersion":1,"target":"rust","portable":true,'
                 '"structure":"passed","diagnostics":[]}\n')
PLAYGROUND_MODULE = ('Playground requires a Rust-portable grammar: '
                     '[Diagnostic[code=P-MODULE, span=null, subject=UBNF module resolution]]')


def snapshot(folder):
    result = {}
    for path in sorted(folder.rglob('*')):
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode):
            value = os.readlink(path)
        elif stat.S_ISREG(mode):
            try:
                value = hashlib.sha256(path.read_bytes()).hexdigest()
            except PermissionError:
                value = ('unreadable', path.stat().st_size)
        else:
            value = 'directory'
        result[str(path.relative_to(folder))] = (mode, value)
    return result


def permissions_apply(folder):
    probe = folder / 'permission-probe'
    probe.write_bytes(b'probe')
    probe.chmod(0)
    try:
        try:
            probe.read_bytes()
            return False
        except PermissionError:
            return True
    finally:
        probe.chmod(0o600)
        probe.unlink()


def setup(work, case, separator):
    work.mkdir()
    for name in case.get('directories', []):
        (work / name).mkdir(parents=True, exist_ok=True)
    for name, text in case.get('files', {}).items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.replace('\n', separator).encode())
    for name, target in case.get('symlinks', {}).items():
        (work / name).symlink_to(target)
    if 'denied' in case:
        (work / case['denied']).chmod(0)


def run(command, options, work, native, **popen):
    env = dict(os.environ)
    # Non-ASCII paths must not depend on the runner locale (Java's sun.jnu/stderr encodings).
    env.update(LC_ALL='C.UTF-8')
    if native:
        env.update(PATH='', JAVA_HOME='/nonexistent-java')
    result = subprocess.run(command + options, cwd=work, env=env, capture_output=True, timeout=30, **popen)
    stderr = result.stderr.decode('utf-8')
    return {'exit': result.returncode, 'stdout': result.stdout.decode('utf-8'), 'stderr': stderr,
            'lastError': (stderr.splitlines() or [''])[-1]}


def expected(case, operation, work, ending):
    if case['scope'] == 'import' and operation in ('check', 'playground'):
        if operation == 'check' and 'checkDiagnostics' in case:
            report = dict(schemaVersion=1, target='rust', portable=False, structure='blocked',
                          diagnostics=case['checkDiagnostics'][ending])
            return (3, json.dumps(report, separators=(',', ':')) + '\n', '')
        return (3, MODULE_REPORT, '') if operation == 'check' else (3, '', PLAYGROUND_MODULE)
    error = case.get('inspectError', case['error']) if operation == 'inspect' else case['error']
    context = case.get('context', {}).get(ending, '') if operation == 'generate' else ''
    error = context + error.replace('${WORK}', str(work))
    if operation == 'check':
        error = 'I/O error: ' + error
    return (3 if operation == 'inspect' else 4, '', error)


def exercise(cases, directory, commands, evidence):
    denied_works = permissions_apply(directory)
    for case in cases:
        if 'denied' in case and not denied_works:
            # chmod(000) is not an I/O failure for root/CAP_DAC_OVERRIDE. Do not claim it was tested.
            evidence['excluded'].append({'case': case['id'], 'reason':
                'The execution user can read chmod(000); this permission combination is outside this run.'})
            continue
        for ending, separator in [('LF', '\n'), ('CRLF', '\r\n')]:
            work = directory / (case['id'] + '-' + ending)
            setup(work, case, separator)
            try:
                before = snapshot(work)
                grammar = case['grammar'].replace('${WORK}', str(work))
                output = work / 'out'
                for operation in case.get('operations', OPERATIONS):
                    options = {
                        'inspect': ['deps', 'inspect'],
                        'check': ['check', '--target', 'rust', '--format', 'json'],
                        'generate': ['generate', '--target', 'rust', '--output', str(output)],
                        'playground': ['playground', '--output', str(output)],
                    }[operation] + ['--grammar', grammar]
                    pairs, artifacts = [], []
                    for host, command in commands:
                        actual = run(command, options, work, host == 'rust')
                        entry = {'case': case['id'], 'ending': ending, 'operation': operation,
                                 'host': host, **actual}
                        evidence['observations'].append(entry)
                        print(json.dumps(entry, ensure_ascii=False), flush=True)
                        observed = (actual['exit'], actual['stdout'], actual['lastError'])
                        if case['scope'] != 'success':
                            assert observed == expected(case, operation, work, ending), entry
                            assert not output.exists(), entry
                        else:
                            assert actual['exit'] == 0 and actual['lastError'] == '', entry
                            if operation == 'check':
                                assert actual['stdout'] == PASSED_REPORT, entry
                            elif operation == 'inspect':
                                doc = json.loads(actual['stdout'])
                                assert doc['schemaVersion'] == 1, entry
                                assert doc['whitespace'] == [
                                    {'grammar': 'Main', 'policy': 'none', 'rule': None},
                                    {'grammar': 'Main', 'policy': 'none', 'rule': 'Start'}], entry
                                assert len(doc['modules']) == (1 if case['id'] == 'valid-import' else 0), entry
                                if doc['modules']:
                                    assert doc['modules'][0]['text'] == case['files']['lib.ubnf'].replace('\n', separator), entry
                            else:
                                files = {str(p.relative_to(output)): p.read_bytes()
                                         for p in output.rglob('*') if p.is_file()}
                                assert files, entry
                                if operation == 'generate':
                                    assert set(files) == {'mod.rs', 'parser.rs', 'ast.rs', 'mapper.rs', 'evaluator.rs'}, entry
                                else:
                                    assert files['public/grammar.ubnf'] == case['files']['main.ubnf'].replace('\n', separator).encode(), entry
                                entry['filesSha256'] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
                                artifacts.append(files)
                                checked = run(command, options + ['--check'], work, host == 'rust')
                                assert checked['exit'] == 0 and checked['lastError'] == '', checked
                                assert checked['stdout'] == actual['stdout'].replace('Generated ', 'Verified ', 1), checked
                                shutil.rmtree(output)
                        pairs.append(observed)
                        assert snapshot(work) == before, (entry, 'input/cache/output snapshot changed')
                    assert pairs[0] == pairs[1], (case, operation, pairs)
                    if artifacts:
                        assert artifacts[0] == artifacts[1], (case, operation, 'generated bytes differ')
            finally:
                if 'denied' in case:
                    (work / case['denied']).chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust', required=True)
    parser.add_argument('--suite', choices=['entry', 'import', 'success'], required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    cases = json.loads((ROOT / 'spec-corpus/cli-input-errors.json').read_text(encoding='utf-8'))
    assert len(cases) == 26, 'I/O fixtures must not silently disappear'
    cases = [case for case in cases if case['scope'] == args.suite]
    assert len(cases) == {'entry': 10, 'import': 14, 'success': 2}[args.suite]
    evidence = {'excluded': [], 'observations': []}
    try:
        with tempfile.TemporaryDirectory(prefix='cli-input-', dir=os.environ.get('TMPDIR')) as directory:
            exercise(cases, Path(directory), [
                ('java', [args.java, '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']),
                ('rust', [args.rust])], evidence)
        assert len(evidence['observations']) == sum(len(c.get('operations', OPERATIONS)) * 4 for c in cases) - len(evidence['excluded']) * 16
    finally:
        Path(args.output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
