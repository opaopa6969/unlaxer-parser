"""#928: shared lexical grammar/manifest paths through real CLI and stdio hosts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "grammar Main { @import layout from 'pkg:std/layout' @ubnf: v2 @whitespace: layout.SPACES @root @mapping(Value) Start ::= 'a'; }\n"
MISSING = 'E-PACKAGE: missing lock; run unlaxer deps resolve --manifest ubnf.json'


def fixtures():
    cases = json.loads((ROOT / 'spec-corpus/package-grammar-paths.json').read_text())
    assert len(cases) == 14, 'path fixtures must not silently disappear'
    return cases


def setup(folder, packaged=False, alias_only=False):
    work = folder / 'work'
    (work / 'a').mkdir(parents=True)
    (folder / 'physical/deep').mkdir(parents=True)
    (work / 'a/link').symlink_to('../../physical/deep', target_is_directory=True)
    (work / 'alias').mkdir()
    # File symlinks must keep the lexical parent as the lock/cache base.
    (work / 'alias/g.ubnf').symlink_to('../g.ubnf')
    (work / 'alias/ubnf.json').symlink_to('../ubnf.json')
    (work / 'g.ubnf').write_text(SOURCE)
    # A raw OS read through link/../.. sees different source before package loading.
    (folder / 'g.ubnf').write_text('grammar Physical { token HOST = com.example.NonportableParser; @root Start ::= HOST; }')
    package, version = ('lang/java', '0.1.0') if packaged else ('std/layout', '1.0.0')
    name = 'lang-java-0.1.0.json' if packaged else 'std-layout-1.0.0.json'
    blob = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages' / name).read_bytes()
    digest = hashlib.sha256(blob).hexdigest()
    manifest = {'schemaVersion': 1, 'dependencies': {package: {'version': version, 'source': 'local:unavailable.json'}}}
    lock = {'schemaVersion': 1, 'roots': {package: version}, 'packages': {
        package: {'id': package, 'version': version, 'sha256': digest, 'dependencies': {}}}}
    for base in [work, work / 'alias']:
        if base == work:
            (base / 'ubnf.json').write_text(json.dumps(manifest))
        (base / 'ubnf.lock.json').write_text(json.dumps(lock))
        cache = base / '.ubnf-cache/packages'
        cache.mkdir(parents=True)
        (cache / (digest + '.json')).write_bytes(blob)
    if alias_only:
        (work / 'ubnf.lock.json').unlink()
    return work


def run(command, options, work, native):
    env = dict(os.environ)
    if native:
        env.update(PATH='', JAVA_HOME='/nonexistent-java')
    result = subprocess.run(command + options, cwd=work, env=env, capture_output=True, timeout=30)
    return {'exit': result.returncode, 'stdout': result.stdout.decode(),
            'stderr': result.stderr.decode(),
            'lastError': (result.stderr.decode().strip().splitlines() or [''])[-1]}


def snapshot(folder):
    return {str(p.relative_to(folder)): p.read_bytes()
            for p in folder.rglob('*') if p.is_file()}


def run_cli(suite, directory, java, native, evidence):
    operations = {'inspect': ['inspect'], 'grammar': ['generate', 'check', 'playground-grammar'],
                  'package': ['playground-package']}[suite]
    expected_files = {}
    for case in fixtures():
        for missing in [False, True]:
            folder = directory / (case['id'] + ('-missing' if missing else '-valid'))
            work = setup(folder, suite == 'package', case['id'] == 'symlink-file')
            if missing:
                for base in [work, work / 'alias']:
                    (base / 'ubnf.lock.json').unlink(missing_ok=True)
            before = snapshot(work)
            for operation in operations:
                if missing and operation == 'playground-grammar':
                    continue  # Host-specific readiness rendering predates the path contract.
                output = work / 'output'
                grammar = case['grammar'].replace('${WORK}', str(work))
                manifest = case['manifest'].replace('${WORK}', str(work))
                if operation == 'inspect':
                    options = ['deps', 'inspect', '--grammar', grammar]
                elif operation == 'check':
                    options = ['check', '--grammar', grammar, '--target', 'rust', '--format', 'json']
                elif operation.startswith('playground'):
                    options = ['playground', '--output', str(output)] + (
                        ['--package', 'lang/java', '--manifest', manifest] if suite == 'package'
                        else ['--grammar', grammar])
                else:
                    options = ['generate', '--target', 'rust', '--grammar', grammar, '--output', str(output)]
                pair, files = [], []
                for host, command in [('java', java), ('rust', native)]:
                    actual = run(command, options, work, host == 'rust')
                    entry = {'case': case['id'], 'missing': missing, 'operation': operation, 'host': host, **actual}
                    evidence.append(entry)
                    print(json.dumps(entry, ensure_ascii=False), flush=True)
                    # Independent oracle: a healthy pinned package succeeds even when local source is absent.
                    assert actual['exit'] == (3 if missing else 0), entry
                    if missing:
                        if operation == 'check':
                            report = json.loads(actual['stdout'])
                            assert report['portable'] is False and 'P-MODULE' in actual['stdout'], entry
                        else:
                            assert actual['lastError'] == MISSING, entry
                        assert not output.exists(), entry
                    else:
                        assert actual['lastError'] == '', entry
                        if operation == 'inspect':
                            doc = json.loads(actual['stdout'])
                            assert doc['modules'][0]['grammar'] == 'Main', entry
                            assert doc['modules'][0]['identity']['id'] == 'std/layout', entry
                            assert doc['modules'][0]['identity']['sha256'] == '2701f6884ea6c712d28c76ec3d340c3b27fca4575846239bd0854933205f4b67', entry
                        elif operation == 'check':
                            assert json.loads(actual['stdout'])['portable'] is True, entry
                        else:
                            generated = snapshot(output)
                            assert generated, entry
                            entry['filesSha256'] = {name: hashlib.sha256(data).hexdigest() for name, data in generated.items()}
                            files.append(generated)
                            if case['id'] == 'plain':
                                expected_files[operation] = generated
                            assert generated == expected_files[operation], (case, operation, 'lexical source oracle')
                            if operation == 'playground-grammar':
                                assert (output / 'public/grammar.ubnf').read_text() == SOURCE
                            shutil.rmtree(output)
                    pair.append({key: actual[key] for key in ['exit', 'stdout', 'lastError']})
                assert pair[0] == pair[1], (case, operation, pair)
                if files:
                    assert len(files) == 2 and files[0] == files[1], (case, operation, files)
                assert snapshot(work) == before, (case, operation, 'read-only package snapshot changed')


def run_lsp(clients, directory):
    # The URI keeps the original dots and slash. Path.as_uri() would strip some of them.
    from urllib.parse import quote
    evidence = []
    for case in fixtures():
        work = setup(directory / case['id'], alias_only=case['id'] == 'symlink-file')
        grammar = case['grammar'].replace('${WORK}', str(work))
        path = grammar if grammar.startswith('/') else str(work) + '/' + grammar
        uri = 'file://' + quote(path, safe='/')
        before = snapshot(work)
        pair = []
        for client in clients:
            client.send('textDocument/didOpen', {'textDocument': {
                'uri': uri, 'languageId': 'ubnf', 'version': 1, 'text': SOURCE}})
            diagnostics = client.diagnostics(uri)
            assert diagnostics == [], (case, diagnostics)
            offset = SOURCE.index('layout.SPACES') + len('layout.')
            params = {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': offset}}
            hover = client.result('textDocument/hover', params)
            assert 'std/layout' in json.dumps(hover), (case, hover)
            pair.append(hover)
            client.send('textDocument/didClose', {'textDocument': {'uri': uri}})
            assert client.result('textDocument/hover', params) is None
        assert pair[0] == pair[1], (case, pair)
        assert snapshot(work) == before
        evidence.append({'case': case['id'], 'diagnostics': [], 'hover': pair[0]})
    return evidence


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust', required=True)
    parser.add_argument('--suite', choices=['inspect', 'grammar', 'package'], required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    evidence = []
    try:
        with tempfile.TemporaryDirectory(prefix='package-grammar-', dir=os.environ.get('TMPDIR')) as temporary:
            run_cli(args.suite, Path(temporary),
                    [args.java, '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain'], [args.rust], evidence)
    finally:
        Path(args.output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
