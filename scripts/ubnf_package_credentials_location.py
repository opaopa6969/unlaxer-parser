"""Java-oracle credentials locations through two resolvers and three generation CLIs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from ubnf_credentials_home import compile_passwd_shim, isolated_env, protect_real_credentials
from ubnf_package_credentials import ROOT, TLS_ERROR, local_tls, receipt
from ubnf_package_json import digest, encode, lock_bytes, reset, snapshot

COUNT = 26
# Only dummy values; never include these in errors, receipts or logs.
TOKENS = {label: 'location-fixture-only-' + label for label in
          ['passwd', 'environment', 'property', 'explicit', 'spaces', 'tilde', 'cwd', 'fallback', 'short']}


def fixtures():
    cases = json.loads((ROOT / 'spec-corpus/package-credentials-location.json').read_text())
    assert len(cases) == len({case['id'] for case in cases}) == COUNT, 'location fixture missing'
    return cases


def invoke(command, options, env, cwd):
    try:
        result = subprocess.run(command + options, env=env, cwd=cwd, capture_output=True, timeout=30)
    except subprocess.TimeoutExpired:
        raise AssertionError('credentials location process timed out') from None
    assert all(token.encode() not in result.stdout + result.stderr for token in TOKENS.values()), \
        'credential leaked into process output'
    return {'exit': result.returncode,
            'lastError': (result.stderr.decode('utf-8').strip().splitlines() or [''])[-1]}


def check(directory, args, records):
    library = compile_passwd_shim(directory)
    # A separate Java program measures the property without calling the fetcher.
    probe = directory / 'HomeProbe.java'
    probe.write_text('public class HomeProbe { public static void main(String[] a) { '
                     'System.out.println(System.getProperty("java.specification.version")); '
                     'System.out.print(System.getProperty("user.home")); } }\n')
    result = subprocess.run([str(Path(args.java).with_name('javac')), str(probe)],
                            capture_output=True, timeout=30)
    assert result.returncode == 0, 'home oracle compilation failed'
    artifact = (ROOT / 'unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json').read_bytes()
    baseline = {'ubnf.lock.json': lock_bytes({'std/layout': artifact}, {'std/layout': '1.0.0'}).hex(),
                '.ubnf-cache/': 'directory', '.ubnf-cache/packages/': 'directory',
                '.ubnf-cache/packages/' + digest(artifact) + '.json': artifact.hex()}
    started = time.monotonic()
    with local_tls(directory, artifact, {token: label for label, token in TOKENS.items()}) as (origin, ca, received):
        for case in fixtures():
            if args.case and case['id'] not in args.case:
                continue
            tick = time.monotonic()
            print('credentials location:', case['id'], flush=True)
            folder = directory / case['id']
            folder.mkdir()
            project = folder / 'project'
            project.mkdir()
            homes = {label: folder / label for label in ['passwd', 'environment', 'property']}
            paths = {label: path / '.config/unlaxer/credentials.json' for label, path in homes.items()}
            paths.update(explicit=folder / 'explicit.json', spaces=folder / '   ',
                         tilde=folder / '~/credentials.json', cwd=folder / '.config/unlaxer/credentials.json',
                         fallback=folder / '?/.config/unlaxer/credentials.json',
                         short=folder / 's/.config/unlaxer/credentials.json')
            for label, path in paths.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(encode({origin: {'caCertificate': str(ca), 'bearerToken': TOKENS[label]}}))
            (folder / 'directory').mkdir()
            (folder / 'parent-file').write_text('not a directory')
            (folder / 'link').symlink_to('explicit.json')
            (folder / 'broken-link').symlink_to('missing.json')
            (folder / 'directory-link').symlink_to('directory', target_is_directory=True)
            if case.get('missingDefault'):
                paths['passwd'].unlink()
            if case.get('missingSpaces'):
                paths['spaces'].unlink()
            home = {'same': homes['passwd'], 'other': homes['environment'], 'unset': None, 'empty': ''}[case['home']]
            # 'short' is a relative one-byte home, like '/' (systemd DynamicUser) but local.
            passwd = {'normal': str(homes['passwd']), 'empty': '', 'short': 's', 'missing': '!missing',
                      'error': '!error'}[case.get('passwd', 'normal')]
            env = isolated_env(library, home, passwd)
            if case.get('retry'):
                env['UBNF_TEST_PASSWD_RETRY'] = '1'
            override = case.get('override')
            if override is not None:
                env['UBNF_CREDENTIALS_FILE'] = override.replace('$DIR', str(folder))
            property_value = case.get('property')
            property_path = '' if property_value == 'empty' else str(homes['property'])
            properties = [] if property_value is None else ['-Duser.home=' + property_path]
            probe_result = subprocess.run([args.java] + properties + ['-cp', str(directory), 'HomeProbe'],
                                          env=env, cwd=folder, capture_output=True, timeout=15)
            assert probe_result.returncode == 0, 'Java home oracle failed'
            jdk, actual_home = probe_result.stdout.decode().split('\n', 1)
            # JDK17 keeps any found pw_dir; JDK21 replaces one shorter than two bytes.
            found = passwd not in ['!missing', '!error']
            expected_home = property_path if property_value is not None else (
                passwd if found and (jdk == '17' or len(passwd) >= 2) else
                '?' if jdk == '17' or home in [None, ''] else str(home))
            assert actual_home == expected_home, (case['id'], 'unexpected Java home oracle')
            manifest = project / 'ubnf.json'
            manifest.write_bytes(encode({'schemaVersion': 1, 'dependencies': {
                'std/layout': {'version': '1.0.0', 'source': origin + '/artifact'}}}))
            grammar = project / 'main.ubnf'
            grammar.write_text("grammar Main { @import layout from 'pkg:std/layout' @ubnf: v2 "
                               "@root @mapping(Value) Start ::= 'a'; }\n")
            java = [args.java, '--enable-preview'] + properties + [
                '-Djava.io.tmpdir=' + str(directory), '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']
            commands = [('java', java, env)]
            if args.rust:
                native_env = dict(env, PATH='', JAVA_HOME='/nonexistent-java')
                # A JVM property has no native CLI counterpart. Use the documented
                # cross-host equivalent (explicit file), never add a product test knob.
                if property_value is not None:
                    native_env['UBNF_CREDENTIALS_FILE'] = str(paths['cwd'] if property_path == '' else paths['property'])
                commands.append(('rust', [args.rust], native_env))
            record = {'case': case['id'], 'jdk': jdk, 'homeOracle': 'pass',
                      'nativePropertyEquivalent': property_value is not None, 'runs': []}
            records.append(record)
            for existing in [False, True]:
                initial = baseline if existing else {}
                for host, command, process_env in commands:
                    reset(project, initial)
                    before = len(received)
                    actual = invoke(command, ['deps', 'resolve', '--manifest', str(manifest)], process_env, folder)
                    error = case.get('error')
                    if error == 'tls':
                        error = TLS_ERROR[host]  # #956 is intentionally unchanged.
                    expected = {'exit': 3 if error else 0, 'lastError': 'E-PACKAGE: ' + error if error else ''}
                    assert actual == expected, (case['id'], host, 'resolve diagnostic/exit differs')
                    after = snapshot(project)
                    assert after == (initial if error else baseline), (case['id'], host, 'resolve snapshot differs')
                    selected = case.get('selected')
                    if host == 'java' and jdk == '17':
                        selected = case.get('javaSelected17', selected)
                    wanted = [] if error else [{'authorization': selected, 'extraHeader': False, 'path': '/artifact'}]
                    assert received[before:] == wanted, (case['id'], host, 'selected credential file differs')
                    record['runs'].append(dict(host=host, operation='resolve', existingSnapshot=existing,
                                               **actual, selectedFile=selected, snapshot=receipt(after)))
                # All location shapes also reach every generation CLI. No credentials
                # lookup, request or lock/cache mutation is permitted, even on failures.
                reset(project, initial)
                generated = []
                for host, command, process_env in commands:
                    for flags in ([False, True] if host == 'java' else [False]):
                        output = folder / (host + ('-flags' if flags else '-rust') + str(existing))
                        options = ['--grammar', str(grammar), '--output', str(output), '--generators', 'Parser'] if flags else [
                            'generate', '--target', 'rust', '--grammar', str(grammar), '--output', str(output)]
                        before = len(received)
                        actual = invoke(command, options, process_env, folder)
                        missing = 'E-PACKAGE: missing lock; run unlaxer deps resolve --manifest ubnf.json'
                        expected = {'exit': 0 if existing else 4 if flags else 3,
                                    'lastError': '' if existing else ('Generation failed: ' if flags else '') + missing}
                        assert actual == expected, (case['id'], host, 'generation diagnostic/exit differs')
                        assert snapshot(project) == initial, 'generation changed snapshot'
                        assert len(received) == before, 'generation used network'
                        if existing:
                            files = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob('*') if p.is_file()}
                            assert files and all(token.encode() not in data for data in files.values()
                                                 for token in TOKENS.values()), 'credential leaked into generation'
                            if not flags:
                                generated.append(files)
                        else:
                            assert not output.exists(), 'failed generation published files'
                        record['runs'].append(dict(host=host, operation='flags' if flags else 'generate',
                                                   existingSnapshot=existing, **actual))
                if len(generated) == 2:
                    assert generated[0] == generated[1], 'generated bytes differ'
            record['seconds'] = round(time.monotonic() - tick, 3)
    return time.monotonic() - started


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--rust')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', action='append')
    args = parser.parse_args()
    records = []
    elapsed = None
    try:
        with protect_real_credentials(), tempfile.TemporaryDirectory(
                prefix='credentials-location-', dir=os.environ.get('TMPDIR')) as temporary:
            elapsed = check(Path(temporary), args, records)
    finally:
        args.output.write_text(json.dumps({'seconds': elapsed, 'cases': records}, indent=2) + '\n')


if __name__ == '__main__':
    main()
