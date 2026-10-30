"""#966: OS argv bytes, JVM filename encoding, and locale-independent UTF-8 file I/O."""
import argparse
import base64
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from ubnf_credentials_home import batch_cli, compile_passwd_shim, isolated_env, protect_real_credentials
from ubnf_package_credentials import TOKEN, local_tls
from ubnf_package_json import encode

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / 'spec-corpus/cli-locale-paths.json'
LOCALES = (None, 'C', 'C.UTF-8')


def fixtures():
    document = json.loads(CORPUS.read_text(encoding='utf-8'))
    cases = document['cases']
    wanted = {(scope, kind) for scope in ('manifest', 'grammar', 'output', 'import', 'credentials', 'home')
              for kind in ('unicode', 'bytes')}
    # Paths read from UTF-8 file bodies (local source, CA) cannot spell raw bytes.
    wanted |= {(scope, 'unicode') for scope in ('local-source', 'ca-certificate', 'grammar-missing')}
    assert {(c['scope'], c['kind']) for c in cases if c['kind'] in ('unicode', 'bytes')} == wanted
    assert len(cases) == len({c['id'] for c in cases}) == 27, 'locale fixture missing'
    return cases, document['trees']


def utf8_json(value):
    """Raw UTF-8 JSON bytes: JDK17's US-ASCII file.encoding must not reach these readers."""
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


ARTIFACT = utf8_json(dict(schemaVersion=1, id='std/layout', version='1.0.0', entry='layout.ubnf',
                          files={'layout.ubnf': "grammar Layout { @ubnf: v2 token T ::= 'a'; } // é😀\n"},
                          dependencies={}))


def tree(folder):
    """Keep raw filenames lossless in evidence; never follow fixture symlinks."""
    result = {}
    for path in folder.rglob('*'):
        name = os.fsencode(str(path.relative_to(folder))).hex()
        if path.is_symlink():
            result[name] = 'link:' + os.fsencode(os.readlink(path)).hex()
        elif path.is_file():
            result[name] = hashlib.sha256(path.read_bytes()).hexdigest()
        else:
            result[name] = 'directory'
    return result


def diagnostic(stdout, stderr, code):
    assert TOKEN.encode() not in stdout + stderr, 'fixture credential leaked'
    return dict(exit=code, stdout=stdout.decode('utf-8'),
                lastError=(stderr.decode('utf-8').rstrip('\n').rsplit('\n', 1))[-1])


def invoke(command, options, env, cwd):
    if callable(command):
        return command(options)
    result = subprocess.run(command + options, env=env, cwd=cwd, capture_output=True, timeout=30)
    return diagnostic(result.stdout, result.stderr, result.returncode)


def locale_env(shim, directory, lc_all, lang):
    env = isolated_env(shim, directory / 'unused-home', directory / 'home')
    for key in list(env):
        if key.startswith('LC_') or key in ('LANG', 'LANGUAGE'):
            env.pop(key)
    if lc_all is not None:
        env['LC_ALL'] = lc_all
    if lang is not None:
        env['LANG'] = lang
    return env


def setup(folder, case, origin, ca, env):
    folder.mkdir()
    name = 'é😀' if case['kind'] == 'unicode' else os.fsdecode(b'raw-\xff')
    scope = case['scope']
    grammar = folder / (name + '.ubnf' if scope == 'grammar' else 'main.ubnf')
    manifest = folder / (name + '.json' if scope == 'manifest' else 'ubnf.json')
    output = folder / (name if scope == 'output' else 'out')
    source = "grammar Main { @ubnf: v2 @root @mapping(Value) Start ::= 'a'; }\n"
    (folder / (name + '.json' if scope == 'local-source' else 'artifact.json')).write_bytes(ARTIFACT)
    dependency = {'std/layout': dict(version='1.0.0', source='local:' + (
        name + '.json' if scope == 'local-source' else 'artifact.json'))}
    if scope == 'grammar-missing':
        grammar = folder / (name + '.ubnf')
    if scope in ('credentials', 'home', 'credentials-body', 'credentials-invalid', 'ca-certificate'):
        dependency['std/layout']['source'] = origin + '/artifact'
        credentials = folder / (name + '.json' if scope == 'credentials' else 'credentials.json')
        if scope == 'home':
            home = folder / name
            credentials = home / '.config/unlaxer/credentials.json'
            env['UBNF_TEST_PASSWD_HOME'] = str(home)
        else:
            env['UBNF_CREDENTIALS_FILE'] = str(credentials)
        credentials.parent.mkdir(parents=True, exist_ok=True)
        authority = str(ca)
        if scope == 'ca-certificate':
            authority = str(folder / (name + '.pem'))
            shutil.copyfile(ca, authority)
        body = {origin: dict(caCertificate=authority, bearerToken=TOKEN, note='é😀')}
        credentials.write_bytes(b'\xff' if scope == 'credentials-invalid' else
                                utf8_json(body) if scope == 'credentials-body' else encode(body))
    if scope in ('import', 'import-parent', 'import-invalid'):
        lib = folder / (name if scope == 'import' else 'lib')
        lib.mkdir()
        (lib / 'lib.ubnf').write_bytes(b'\xff' if scope == 'import-invalid' else
                                     b"grammar Lib { @ubnf: v2 token T ::= 'a'; }\n")
        reference = lib.name
        if scope == 'import' and case['kind'] == 'bytes':
            # UTF-8 grammar has no spelling for a non-UTF-8 filename. An ASCII
            # symlink tests kernel traversal of that raw target without inventing one.
            (folder / 'link').symlink_to(name)
            reference = 'link'
        source = "grammar Main { @import lib from '" + reference + "/lib.ubnf' @ubnf: v2 @root @mapping(Value) Start ::= lib.T; }\n"
        if scope == 'import-parent':
            raw = folder / os.fsdecode(b'raw-\xff')
            raw.mkdir()
            shutil.move(str(lib), str(raw / 'lib'))
            grammar = raw / 'main.ubnf'
    if scope in ('grammar-body', 'playground-body', 'profile-body'):
        source = source.replace("'a'", "'é😀'")
    if scope == 'stderr-body':
        # Both CLIs reject it with exit 3; only the console encoding of the echoed body differs.
        source = "grammar Main { é😀 }\n"
    if scope != 'grammar-missing':
        grammar.write_bytes(b'\xff' if scope == 'grammar-invalid' else source.encode('utf-8'))
    document = dict(schemaVersion=1, dependencies=dependency if scope in (
        'credentials', 'home', 'manifest-body', 'credentials-body', 'credentials-invalid',
        'local-source', 'ca-certificate') else {}, note='é😀')
    manifest.write_bytes(b'\xff' if scope == 'manifest-invalid' else
                         utf8_json(document) if scope in ('manifest-body', 'local-source') else encode(document))
    raw_bodies = [ARTIFACT] + [path.read_bytes() for path, wanted in (
        (manifest, ('manifest-body', 'local-source')),
        (Path(env.get('UBNF_CREDENTIALS_FILE', '')), ('credentials-body',))) if scope in wanted]
    assert all(max(body) >= 0x80 for body in raw_bodies), 'body control lost its raw UTF-8 bytes'
    if scope == 'output-failure':
        output.mkdir()
        (output / 'mod.rs').write_bytes('caller-owned é\n'.encode())
    if scope == 'profile-body':
        # Same published profile format as the existing profile I/O suite.
        template = (ROOT / 'spec-corpus/cli-stderr.json').read_text(encoding='utf-8')
        profile = next(c for c in json.loads(template)['cases'] if c['id'] == 'profile-readable')
        (folder / 'profile.tsv').write_bytes(profile['fixedFiles']['p.tsv'].encode())
    if scope in ('manifest', 'credentials', 'home', 'manifest-body', 'manifest-invalid',
                 'credentials-body', 'credentials-invalid', 'local-source', 'ca-certificate'):
        options = ['deps', 'resolve', '--manifest', str(manifest)]
    elif scope in ('playground-body', 'profile-body'):
        options = ['playground', '--output', str(output)] + (
            ['--profile', str(folder / 'profile.tsv')] if scope == 'profile-body' else ['--grammar', str(grammar)])
    else:
        options = ['generate', '--target', 'rust', '--grammar', str(grammar), '--output', str(output)]
    return options, output


def batch(stack, java, env, cwd, unbatched):
    if unbatched:
        return java
    call = stack.enter_context(batch_cli(java[:-1] + ['org.unlaxer.dsl.CodegenBatchHost', '--locale-paths'],
                                        env, cwd, diagnostic))
    return lambda options: call([base64.b64encode(os.fsencode(arg)).decode('ascii') for arg in options])


def check(directory, args, evidence):
    shim = compile_passwd_shim(directory)
    cases, trees = fixtures()
    java = [args.java, '-Djava.io.tmpdir=' + str(directory), '-cp', args.classpath, 'org.unlaxer.dsl.CodegenMain']
    # The TLS response is raw UTF-8 text too: the raw cache must keep its bytes.
    with local_tls(directory, ARTIFACT) as (origin, ca, received):
        for lc_all in LOCALES:
            for lang in LOCALES:
                label = str(lc_all) + '-' + str(lang)
                env = locale_env(shim, directory, lc_all, lang)
                probe = subprocess.run(java[:-1] + ['org.unlaxer.dsl.CodegenBatchHost', '--locale-info'],
                                       env=env, capture_output=True, timeout=15)
                assert probe.returncode == 0 and not probe.stderr, 'C-locale JVM did not start'
                info = json.loads(probe.stdout)
                utf8 = (lc_all if lc_all is not None else lang) == 'C.UTF-8'
                assert info['filenames'] == ('UTF-8' if utf8 else 'US-ASCII'), info
                assert info['files'] == ('US-ASCII' if info['jdk'] == '17' and not utf8 else 'UTF-8'), info
                evidence['environments'].append(dict(LC_ALL=lc_all, LANG=lang, **info))
                with ExitStack() as common:
                    base_java = batch(common, java, env, directory, args.unbatched)
                    for case in cases:
                        if args.case and case['id'] not in args.case:
                            continue
                        folder = directory / (label + '-' + case['id'])
                        case_env = dict(env)
                        options, output = setup(folder, case, origin, ca, case_env)
                        private = case_env != env
                        with ExitStack() as stack:
                            command = batch(stack, java, case_env, directory, args.unbatched) if private else base_java
                            for existing in (False, True):
                                for host, cmd in [('java', command), ('native', [args.rust])]:
                                    # Recreate the exact preexisting lock/cache and caller output.
                                    lock = folder / 'ubnf.lock.json'
                                    cache = folder / '.ubnf-cache'
                                    if lock.exists():
                                        lock.unlink()
                                    if cache.exists():
                                        shutil.rmtree(cache)
                                    if existing:
                                        lock.write_bytes(b'old-lock\n')
                                        (cache / 'packages').mkdir(parents=True)
                                        (cache / 'packages/old.json').write_bytes(b'old-cache\n')
                                    if existing and options[0] == 'generate':
                                        output.mkdir(exist_ok=True)
                                        (output / 'caller-marker').write_bytes(b'preserve output\n')
                                    before = tree(folder)
                                    request_start = len(received)
                                    process_env = case_env if host == 'java' else dict(case_env, PATH='', JAVA_HOME='/nonexistent-java')
                                    actual = invoke(cmd, options, process_env, directory)
                                    actual['stdout'] = actual['stdout'].replace(str(folder), '${WORK}')
                                    actual['lastError'] = actual['lastError'].replace(str(folder), '${WORK}')
                                    after = tree(folder)
                                    changed = {k: v for k, v in after.items() if before.get(k) != v}
                                    removed = sorted(before.keys() - after.keys())
                                    selected = 'javaUtf8' if utf8 else 'javaAscii'
                                    selected = selected if host == 'java' else 'native'
                                    expected = case.get(selected)
                                    record = dict(case=case['id'], LC_ALL=lc_all, LANG=lang, host=host,
                                                  existingSnapshot=existing, **actual, changed=changed, removed=removed,
                                                  requests=received[request_start:])
                                    evidence['observations'].append(record)
                                    assert actual == {k: expected[k] for k in ('exit', 'stdout', 'lastError')}, (case['id'], label, host, 'diagnostic/exit')
                                    assert removed == [], (case['id'], label, host, 'removed input')
                                    updates = {k: v for k, v in trees.get(expected.get('tree'), {}).items()
                                               if before.get(k) != v}
                                    assert changed == updates, (case['id'], label, host, 'file bytes/locations')
                                    wanted = [dict(authorization='bearer', extraHeader=False, path='/artifact')] if private and actual['exit'] == 0 else []
                                    assert received[request_start:] == wanted, (case['id'], label, host, 'selected credentials/request')
                                    # Clean only additions; verify every preexisting input first.
                                    for key in sorted(set(after) - set(before), key=len, reverse=True):
                                        path = folder / os.fsdecode(bytes.fromhex(key))
                                        path.rmdir() if path.is_dir() and not path.is_symlink() else path.unlink()
                                    # Resolve may replace the fixture-owned old lock. Other inputs must be intact.
                                    remaining = tree(folder)
                                    if lock.exists():
                                        remaining.pop(b'ubnf.lock.json'.hex(), None)
                                    original = {k: v for k, v in before.items() if k != b'ubnf.lock.json'.hex()}
                                    assert remaining == original, (case['id'], host, 'input modified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'classpath', 'rust'):
        parser.add_argument('--' + option, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--unbatched', action='store_true')
    parser.add_argument('--case', action='append')
    args = parser.parse_args()
    runner_locale = {k: v for k, v in os.environ.items() if k.startswith('LC_') or k in ('LANG', 'LANGUAGE')}
    evidence = dict(runnerLocale=runner_locale, environments=[], observations=[], excluded=[])
    started = time.monotonic()
    try:
        with protect_real_credentials(), tempfile.TemporaryDirectory(prefix='locale-paths-', dir=os.environ.get('TMPDIR')) as temp:
            check(Path(temp), args, evidence)
    finally:
        assert runner_locale == {k: v for k, v in os.environ.items() if k.startswith('LC_') or k in ('LANG', 'LANGUAGE')}
        evidence['seconds'] = round(time.monotonic() - started, 3)
        args.output.write_text(json.dumps(evidence, ensure_ascii=True, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
