#!/usr/bin/env python3
"""Checked, opt-in Classic Tiny parse+map harness. Verification is never a timing pass."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'benchmarks/tiny-classic'
VERSION = '3.3.1-benchmark-SNAPSHOT'
MEMOS = ['OFF', 'SAFE_FAILURES']
DIAGNOSTICS = ['DETAILED', 'DETAILED_ON_FAILURE', 'AUTO']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(arguments, cwd, log, timeout=600):
    """Only terminate the owned child process group, never a shared build/runner."""
    started = time.monotonic_ns()
    environment = dict(os.environ, RUSTUP_TOOLCHAIN='1.85.0')
    with log.open('w') as output:
        process = subprocess.Popen(list(map(str, arguments)), cwd=cwd, env=environment,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            status = process.wait(timeout=timeout)
        except BaseException as failure:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
            if isinstance(failure, subprocess.TimeoutExpired):
                raise RuntimeError('owned child timeout; no successful sample: ' + str(log)) from failure
            raise
    if status:
        raise RuntimeError('child failed (' + str(status) + '): ' + str(log))
    return time.monotonic_ns() - started


def git(tree, *args):
    return subprocess.check_output(['git', *args], cwd=tree, text=True).strip()


def product_hash():
    files = git(ROOT, 'ls-files', 'unlaxer-common/src/main', 'unlaxer-dsl/src/main', 'rust').splitlines()
    value = hashlib.sha256()
    for name in files:
        path = ROOT / name
        if path.is_file():
            value.update(name.encode() + b'\0' + path.read_bytes() + b'\0')
    return value.hexdigest()


def consumer_hashes():
    return {path.name: digest(path) for path in sorted((FIXTURES / 'consumers').iterdir()) if path.is_file()}


def validate_inputs(tiny):
    manifest = json.loads((FIXTURES / 'manifest.json').read_text())
    if git(tiny, 'rev-parse', 'HEAD') != manifest['tiny_pin'] or git(tiny, 'status', '--porcelain'):
        raise ValueError('use a clean isolated Tiny worktree at the manifest pin')
    grammar = tiny / manifest['grammar']
    if digest(grammar) != manifest['grammar_sha256']:
        raise ValueError('pinned grammar hash mismatch')
    for case in manifest['cases']:
        path = FIXTURES / case['file']
        if digest(path) != case['sha256'] or len(path.read_bytes().decode()) != case['code_points']:
            raise ValueError('corpus hash/code-point mismatch: ' + case['id'])
    return manifest, grammar


@contextmanager
def preserve_flattened_poms():
    # The local snapshot version is isolated from the tracked release metadata.
    originals = {ROOT / name: (ROOT / name).read_bytes()
                 for name in git(ROOT, 'ls-files', '.flattened-pom.xml', '*/.flattened-pom.xml').splitlines()}
    previous = signal.getsignal(signal.SIGTERM)
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    try:
        yield
    finally:
        for path, before in originals.items():
            if not path.exists() or path.read_bytes() != before:
                path.write_bytes(before)
        signal.signal(signal.SIGTERM, previous)


def prepare(args, manifest, grammar, output):
    logs = output / 'build-logs'
    logs.mkdir(parents=True, exist_ok=True)
    maven = ['mvn', '-q', '-Dmaven.repo.local=' + str(args.maven_repo), '-Dgpg.skip=true']
    with preserve_flattened_poms():
        command(maven + ['-pl', '.,unlaxer-common,unlaxer-dsl', '-am', '-DskipTests',
                         '-Drevision=' + VERSION, 'install'], ROOT, logs / 'parser-install.log')
    tiny_args = ['-DskipTests', '-Dtinyexpression.skipRailroad=true', '-Dunlaxer.version=' + VERSION]
    command(maven + tiny_args + ['compile', 'dependency:build-classpath', '-Dmdep.includeScope=test',
                                '-Dmdep.outputFile=' + str(output / 'tiny-classpath')],
            args.tiny, logs / 'tiny-compile.log')
    dependency = output / 'resolved-dependencies.txt'
    command(maven + tiny_args + ['dependency:tree', '-Dincludes=org.unlaxer:*',
                                '-DoutputFile=' + str(dependency)], args.tiny, logs / 'dependency-tree.log')
    import re
    resolved = dict(re.findall(r'org\.unlaxer:(unlaxer-(?:common|dsl)):jar:([^:\s]+):', dependency.read_text()))
    if resolved != {'unlaxer-common': VERSION, 'unlaxer-dsl': VERSION}:
        raise ValueError('stale Tiny dependency resolution: ' + str(resolved))
    classpath = os.pathsep.join(map(str, [ROOT / 'unlaxer-common/target/classes',
                                       ROOT / 'unlaxer-dsl/target/classes', args.tiny / 'target/classes']))
    classpath += os.pathsep + (output / 'tiny-classpath').read_text().strip()
    helper = output / 'helper'
    helper.mkdir(exist_ok=True)
    command(['javac', '--release', '21', '-cp', classpath, '-d', helper,
             FIXTURES / 'consumers/Prepare.java'], ROOT, logs / 'helper-compile.log')
    command(['java', '-cp', str(helper) + os.pathsep + classpath, 'Prepare', grammar, output],
            ROOT, logs / 'java-generate.log')
    command(['cargo', 'build', '--locked', '--release', '--manifest-path', ROOT / 'rust/Cargo.toml',
             '-p', 'unlaxer-generator'], ROOT, logs / 'generator-build.log')
    command([ROOT / 'rust/target/release/unlaxer', 'generate', '--grammar', grammar,
             '--output', output / 'rust-native/generated'], ROOT, logs / 'native-generate.log')
    generated_hashes = {}
    for source in sorted((output / 'rust-java/generated').glob('*.rs')):
        native = output / 'rust-native/generated' / source.name
        if source.read_bytes() != native.read_bytes():
            raise ValueError('Java/native Rust generated source mismatch: ' + source.name)
        generated_hashes[source.name] = digest(source)
    if len(generated_hashes) != 5:
        raise ValueError('expected all five generated Rust modules')
    java = output / 'java'
    java.mkdir(exist_ok=True)
    command(['javac', '--release', '21', '-cp', classpath, '-d', java,
             *sorted((output / 'java-src').rglob('*.java')), FIXTURES / 'consumers/JavaConsumer.java'],
            ROOT, logs / 'java-consumer-compile.log')
    runtime = output / 'libunlaxer_runtime.rlib'
    command(['rustc', '--edition=2021', '-C', 'opt-level=3', '-C', 'debuginfo=1', '--crate-name',
             'unlaxer_runtime', '--crate-type=rlib', ROOT / 'rust/unlaxer-runtime/src/lib.rs', '-o', runtime],
            ROOT, logs / 'runtime-compile.log')
    for host in ['java', 'native']:
        directory = output / ('rust-' + host)
        (directory / 'main.rs').write_bytes((FIXTURES / 'consumers/rust-consumer.rs').read_bytes())
        command(['rustc', '--edition=2021', '-C', 'opt-level=3', '-C', 'debuginfo=1', directory / 'main.rs',
                 '--extern', 'unlaxer_runtime=' + str(runtime), '-o', directory / 'consumer'],
                ROOT, logs / ('rust-' + host + '-compile.log'))
    versions = {}
    for tool, arguments in [('java', ['java', '-version']), ('javac', ['javac', '-version']),
                            ('rustc', ['rustc', '-vV'])]:
        path = logs / (tool + '-version.log')
        command(arguments, ROOT, path)
        versions[tool] = path.read_text()
    build = dict(parser_commit=git(ROOT, 'rev-parse', 'HEAD'), parser_product_hash=product_hash(),
                 tiny_commit=manifest['tiny_pin'], grammar_sha256=digest(grammar),
                 classpath=classpath, rust_sources=generated_hashes, resolved_dependencies=resolved,
                 consumer_hashes=consumer_hashes(),
                 toolchains=versions, rust_flags=['opt-level=3', 'debuginfo=1'], java_release=21)
    (output / 'build.json').write_text(json.dumps(build, indent=2) + '\n')


def scalars(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return sum((scalars(item) for item in value), [])
    if isinstance(value, dict):
        return scalars(value.get('fields', list(value.values())))
    return []


def validate_result(case, row, memo, diagnostics, entry=None):
    if 'error' in row:
        raise ValueError('consumer exception: ' + str(row))
    required = {'entry', 'memo_effective', 'diagnostics_effective',
                'accepted', 'parser_succeeded', 'consumed_cp', 'matched_cp', 'parse_map_ns',
                'ast', 'syntax', 'semantic', 'failure_hits', 'success_hits', 'phase', 'iteration'}
    if not required.issubset(row) or not isinstance(row['accepted'], bool):
        raise ValueError('incomplete or malformed consumer result')
    if row['entry'] != (entry or case['entry']):
        raise ValueError('consumer called a different explicit entry')
    resolved_diagnostics = 'DETAILED' if diagnostics == 'AUTO' else diagnostics
    if row['memo_effective'] != memo or row['diagnostics_effective'] != resolved_diagnostics:
        raise ValueError('consumer used different resolved memo/diagnostic options')
    if row['accepted'] != case['accept']:
        raise ValueError('unexpected acceptance: ' + case['id'])
    if memo == 'OFF' and (row['failure_hits'] or row['success_hits']):
        raise ValueError('memo OFF reported a cache hit')
    if case['accept']:
        if not row['parser_succeeded'] or row['consumed_cp'] != case['code_points'] or row['matched_cp'] != case['code_points'] or row['ast'] is None:
            raise ValueError('short parse/null AST is not a successful sample')
        if row['ast']['type'] != case['root'] or row['ast']['span'] != case['root_span']:
            raise ValueError('independent root/span mismatch: ' + case['id'])
        if 'expected_ast' in case and row['ast'] != case['expected_ast']:
            raise ValueError('independent complete AST/span mismatch: ' + case['id'])
        for expected in case.get('required_scalars', []):
            if expected not in scalars(row['ast']):
                raise ValueError('independent scalar mismatch: ' + case['id'] + ': ' + expected)
    elif row['ast'] is not None:
        raise ValueError('rejected input published an AST')
    if not case['accept']:
        if diagnostics == 'DETAILED_ON_FAILURE':
            if row['syntax'] != {'offset': 0, 'expected': []}:
                raise ValueError('deferred low-level diagnostics must remain empty')
        else:
            if not row['syntax']['expected']:
                raise ValueError('missing syntax failure evidence')
            if 'failure_cp' in case and row['syntax']['offset'] != case['failure_cp']:
                raise ValueError('independent failure CP mismatch')
    if row['parse_map_ns'] < 0:
        raise ValueError('negative timer')


def semantic(row):
    """Reached offsets without a syntax error are not cross-language diagnostics."""
    return {key: row[key] for key in ['accepted', 'parser_succeeded', 'consumed_cp', 'matched_cp', 'ast', 'semantic']} | {
        'failure': None if row['accepted'] else {
            'offset': row['syntax']['offset'], 'has_expected': bool(row['syntax']['expected'])}}


def execute(args, manifest, output):
    build = json.loads((output / 'build.json').read_text())
    if (build['parser_product_hash'] != product_hash() or build['tiny_commit'] != manifest['tiny_pin']
            or build.get('consumer_hashes') != consumer_hashes()):
        raise ValueError('stale build identity; run prepare again')
    if args.mode == 'measure':
        if git(ROOT, 'status', '--porcelain'):
            raise ValueError('measurement requires a clean fixed parser source')
        if not args.exclusive_host:
            raise ValueError('measurement requires an explicit exclusive-host attestation')
        prerequisites = dict(value.split(':', 1) for value in args.prerequisite)
        if set(prerequisites) != {'652', '653'}:
            raise ValueError('final measurement requires integrated #652 and #653 commit identities')
        for sha in prerequisites.values():
            subprocess.run(['git', 'merge-base', '--is-ancestor', sha, 'HEAD'], cwd=ROOT, check=True)
    else:
        prerequisites = {}
    samples = output / ('run-' + str(time.time_ns()))
    samples.mkdir()
    metadata = dict(build=build, manifest_sha256=digest(FIXTURES / 'manifest.json'), mode=args.mode,
                    measurement_eligible=args.mode == 'measure', prerequisites=prerequisites,
                    exclusive_host_attested=args.exclusive_host, parser_commit=git(ROOT, 'rev-parse', 'HEAD'),
                    source_changes=git(ROOT, 'status', '--porcelain'), platform=platform.platform(),
                    cpu=next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                              if line.startswith('model name')), platform.processor()),
                    cpu_count=os.cpu_count(), load_average=os.getloadavg(),
                    command=sys.argv, warmups=args.warmups, iterations=args.iterations,
                    entry_override=args.entry_override,
                    note='verification timing is never a deadline pass; concurrent builds may be active')
    (samples / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    comparisons, native_diagnostics, errors = {}, {}, []
    memos = MEMOS if args.memo == 'both' else [args.memo]
    diagnostics = DIAGNOSTICS if args.diagnostics == 'all' else [args.diagnostics]
    with (samples / 'samples.jsonl').open('w') as raw:
        for case in manifest['cases']:
            if args.corpus != 'all' and case['group'] != args.corpus:
                continue
            for memo in memos:
                for diagnostic in diagnostics:
                    for language in ['java', 'rust-java', 'rust-native']:
                        stem = '-'.join([case['id'], memo, diagnostic, language])
                        cmd = ['java', '-cp', str(output / 'java') + os.pathsep + build['classpath'], 'JavaConsumer'] if language == 'java' else [output / language / 'consumer']
                        entry = args.entry_override or case['entry']
                        cmd += [FIXTURES / case['file'], memo, diagnostic, metadata['warmups'], metadata['iterations'], entry]
                        log = samples / (stem + '.jsonl')
                        try:
                            wall = command(cmd, ROOT, log, args.timeout)
                            rows = [json.loads(line) for line in log.read_text().splitlines()]
                            expected_count = 1 + metadata['warmups'] + metadata['iterations']
                            if len(rows) != expected_count:
                                raise ValueError('missing operation samples')
                            for index, row in enumerate(rows):
                                phase = 'cold' if index == 0 else 'warmup' if index <= metadata['warmups'] else 'warm'
                                if row.get('phase') != phase or row.get('iteration') != index:
                                    raise ValueError('consumer mixed cold/warmup/warm operation boundaries')
                                validate_result(case, row, memo, diagnostic, entry)
                                syntax_key = (case['id'], diagnostic, 'java' if language == 'java' else 'rust')
                                if syntax_key in native_diagnostics and native_diagnostics[syntax_key] != row['syntax']:
                                    raise ValueError('native diagnostic changed across OFF/ON/host/trial: ' + repr(syntax_key))
                                native_diagnostics[syntax_key] = row['syntax']
                                if args.mode == 'measure' and memo == 'SAFE_FAILURES' and row['phase'] != 'warmup':
                                    if row['parse_map_ns'] >= case.get('deadline_ms', 10000) * 1_000_000:
                                        raise ValueError('explicit deadline exceeded: ' + row['phase'])
                                comparison = semantic(row)
                                key = (case['id'], diagnostic)
                                if key in comparisons and comparisons[key] != comparison:
                                    raise ValueError('semantic mismatch across OFF/ON/host/trial: ' + repr(key))
                                comparisons[key] = comparison
                                row.update(fixture=case['id'], input_sha256=case['sha256'], language=language,
                                           memo=memo, diagnostics=diagnostic, entry=entry, process_wall_ns=wall,
                                           measurement_eligible=args.mode == 'measure')
                                raw.write(json.dumps(row, ensure_ascii=False) + '\n')
                        except (RuntimeError, ValueError, KeyError, TypeError) as error:
                            errors.append(dict(sample=stem, error=str(error)))
    (samples / 'result.json').write_text(json.dumps(dict(errors=errors, semantic_configurations=len(comparisons),
        measurement_eligible=args.mode == 'measure'), indent=2) + '\n')
    print(samples)
    if errors:
        raise ValueError('explicit verification failures: ' + json.dumps(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tiny', type=Path, required=True)
    parser.add_argument('--maven-repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/tiny-classic-benchmark')
    parser.add_argument('--mode', choices=['prepare', 'verify', 'measure'], default='verify')
    parser.add_argument('--corpus', choices=['oracle', 'fraud', 'production', 'all'], default='oracle')
    parser.add_argument('--memo', choices=['both', *MEMOS], default='both')
    parser.add_argument('--diagnostics', choices=['all', *DIAGNOSTICS], default='all')
    parser.add_argument('--entry-override', help='audit exactly one public entry; never retry others')
    parser.add_argument('--timeout', type=float, default=30)
    parser.add_argument('--warmups', type=int)
    parser.add_argument('--iterations', type=int)
    parser.add_argument('--prerequisite', action='append', default=[], metavar='ISSUE:COMMIT')
    parser.add_argument('--exclusive-host', action='store_true')
    args = parser.parse_args()
    args.tiny, args.maven_repo, args.output = args.tiny.resolve(), args.maven_repo.resolve(), args.output.resolve()
    if args.warmups is None:
        args.warmups = 5 if args.mode == 'measure' else 0
    if args.iterations is None:
        args.iterations = 10 if args.mode == 'measure' else 0
    if args.warmups < 0 or args.iterations < 0 or args.timeout <= 0:
        raise ValueError('nonnegative iteration counts and positive timeout required')
    args.output.mkdir(parents=True, exist_ok=True)
    manifest, grammar = validate_inputs(args.tiny)
    if args.mode == 'prepare':
        prepare(args, manifest, grammar, args.output)
    else:
        execute(args, manifest, args.output)


if __name__ == '__main__':
    main()
