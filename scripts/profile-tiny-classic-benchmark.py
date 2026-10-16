#!/usr/bin/env python3
"""Opt-in, bounded failure-memo experiment; never a performance acceptance gate."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import resource
import shutil
import time

ROOT = Path(__file__).resolve().parents[1]
PROFILES = ROOT / 'benchmarks/tiny-classic/profiles'
SPEC = importlib.util.spec_from_file_location('tiny_harness', ROOT / 'scripts/run-tiny-classic-benchmark.py')
harness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(harness)


def instrument_runtime(source):
    # The public retention getters describe only the active grammar. Observe them
    # immediately before parse_shared_grammar restores/drops that grammar's cache.
    anchor = '        let result = self.parse_expression(&Expr::Rule(root));'
    if source.count(anchor) != 1:
        raise ValueError('runtime observation boundary changed; review the probe')
    observation = '''
        eprintln!("PROFILE_MEMO {{\\\"failure_entries\\\":{},\\\"failure_retained_bytes\\\":{},\\\"failure_budget_misses\\\":{},\\\"success_entries\\\":{},\\\"success_retained_bytes\\\":{},\\\"success_budget_misses\\\":{}}}", self.failure_memo_entries(), self.failure_memo_retained_bytes(), self.failure_memo_budget_misses(), self.success_memo_entries(), self.success_memo_retained_bytes(), self.success_memo_budget_misses());'''
    return source.replace(anchor, anchor + observation)


def read_rows(log, count, observe_cache=False):
    rows, counters = [], []
    for line in log.read_text().splitlines():
        if line.startswith('PROFILE_MEMO '):
            counters.append(json.loads(line.removeprefix('PROFILE_MEMO ')))
        else:
            rows.append(json.loads(line))
    if (len(rows) != count or (observe_cache and len(counters) != count)
            or (not observe_cache and counters)):
        raise ValueError('incomplete operation/cache observations: ' + str(log))
    if observe_cache:
        for row, counter in zip(rows, counters):
            row['cache'] = counter
    return rows


def validate_rows(case, rows, reference, budget, warmups):
    for index, row in enumerate(rows):
        phase = 'cold' if index == 0 else 'warmup' if index <= warmups else 'warm'
        if row.get('phase') != phase or row.get('iteration') != index:
            raise ValueError('mixed cold/warm operation boundaries')
        harness.validate_result(case, row, 'SAFE_FAILURES', 'DETAILED')
        if harness.semantic(row) != harness.semantic(reference) or row['syntax'] != reference['syntax']:
            raise ValueError('profile changed AST/span/cursors/semantic/native syntax')
        if budget is not None and row['cache']['failure_retained_bytes'] > budget:
            raise ValueError('finite failure budget exceeded')
        if 'phase_breakdown' in row and sum(row['phase_breakdown'].values()) != row['parse_map_ns']:
            raise ValueError('phase timings do not cover the operation')


def prepare_probes(build, compiled, output):
    cp = os.pathsep.join([str(compiled / 'java'), build['classpath']])
    harness.command(['javac', '--release', '21', '-cp', cp, '-d', output,
                     PROFILES / 'PhaseConsumer.java', PROFILES / 'SummarizeJfr.java'], ROOT,
                    output / 'java-probes-compile.log')
    runtime = output / 'runtime-probe'
    shutil.copytree(ROOT / 'rust/unlaxer-runtime/src', runtime, dirs_exist_ok=True)
    source = runtime / 'lib.rs'
    source.write_text(instrument_runtime(source.read_text()))
    library = output / 'libunlaxer_runtime_probe.rlib'
    harness.command(['rustc', '--edition=2021', '-C', 'opt-level=3', '-C', 'debuginfo=1',
                     '--crate-name', 'unlaxer_runtime', '--crate-type=rlib', source, '-o', library],
                    ROOT, output / 'rust-runtime-probe-compile.log')
    for host in ['java', 'native']:
        directory = output / ('rust-' + host)
        shutil.copytree(compiled / ('rust-' + host) / 'generated', directory / 'generated', dirs_exist_ok=True)
        for name, digest in build['rust_sources'].items():
            if harness.digest(directory / 'generated' / name) != digest:
                raise ValueError('stale generated Rust source: ' + name)
        shutil.copy2(PROFILES / 'profile-consumer.rs', directory / 'main.rs')
        harness.command(['rustc', '--edition=2021', '-C', 'opt-level=3', '-C', 'debuginfo=1',
                         '--crate-name', 'budget_probe', directory / 'main.rs', '--extern',
                         'unlaxer_runtime=' + str(library), '-o', directory / 'consumer'],
                        ROOT, output / ('rust-' + host + '-probe-compile.log'))
    for tool, arguments in [('java', ['java', '-version']), ('rustc', ['rustc', '-vV'])]:
        harness.command(arguments, ROOT, output / (tool + '-version.log'))
    return cp


def run(args):
    manifest, _ = harness.validate_inputs(args.tiny)
    build = json.loads((args.build / 'build.json').read_text())
    if (build['parser_product_hash'] != harness.product_hash()
            or build['tiny_commit'] != manifest['tiny_pin']
            or build['consumer_hashes'] != harness.consumer_hashes()):
        raise ValueError('prepare a matching fixed Classic build first')
    cases = manifest['cases'] if args.all else [next((c for c in manifest['cases'] if c['id'] == name), None)
                                               for name in args.cases]
    if any(case is None for case in cases):
        raise ValueError('unknown manifest case')
    output = args.output / ('profile-' + str(time.time_ns()))
    output.mkdir(parents=True)
    cp = prepare_probes(build, args.build, output)
    metadata = dict(parser_commit=harness.git(ROOT, 'rev-parse', 'HEAD'), build=build,
                    product_hash=harness.product_hash(), manifest_sha256=harness.digest(harness.FIXTURES / 'manifest.json'),
                    profile_sources={p.name: harness.digest(p) for p in PROFILES.iterdir() if p.is_file()},
                    driver_sha256=harness.digest(Path(__file__)),
                    instrumented_runtime_sha256=harness.digest(output / 'runtime-probe/lib.rs'),
                    toolchains={tool: (output / (tool + '-version.log')).read_text() for tool in ['java', 'rustc']},
                    source_changes=harness.git(ROOT, 'status', '--porcelain'),
                    cpu_count=os.cpu_count(), load=os.getloadavg(),
                    warmups=args.warmups, iterations=args.iterations, operations=[], errors=[],
                    measurement_eligible=False,
                    note='hypothesis profile; shared host; supplementary probes; no production/default changes')
    references = {}
    def capture(case, language, budget):
        normal = budget is None
        stem = case['id'] + ('-baseline' if normal else '-' + str(budget)) + '-' + language
        recording = output / (stem + '.jfr')
        if language == 'java':
            cmd = ['java']
            if not normal:
                cmd += ['-Dunlaxer.memo.failure.maxBytes=' + str(budget), '-Xlog:jfr+startup=off',
                        '-XX:StartFlightRecording=settings=' + str(PROFILES / 'classic-profile.jfc')
                        + ',filename=' + str(recording) + ',dumponexit=true']
            cmd += ['-cp', (str(output) + os.pathsep if not normal else '') + cp,
                    'JavaConsumer' if normal else 'PhaseConsumer']
        else:
            cmd = [args.build / language / 'consumer'] if normal else [
                'env', 'UNLAXER_FAILURE_MEMO_MAX_BYTES=' + str(budget), output / language / 'consumer']
        warmups, iterations = (0, 0) if normal else (args.warmups, args.iterations)
        cmd += [harness.FIXTURES / case['file'], 'SAFE_FAILURES', 'DETAILED', warmups, iterations, case['entry']]
        before = resource.getrusage(resource.RUSAGE_CHILDREN)
        log = output / (stem + '.jsonl')
        wall = harness.command(cmd, ROOT, log, args.timeout)
        after = resource.getrusage(resource.RUSAGE_CHILDREN)
        rows = read_rows(log, 1 + warmups + iterations, not normal and language != 'java')
        reference = rows[0] if normal else references[case['id'], language]
        validate_rows(case, rows, reference, budget, warmups)
        if normal:
            references[case['id'], language] = rows[0]
            java_reference = references.get((case['id'], 'java'))
            if java_reference and harness.semantic(java_reference) != harness.semantic(rows[0]):
                raise ValueError('original Java/Rust consumers disagree')
        metadata['operations'].append(dict(fixture=case['id'], input_sha256=case['sha256'], entry=case['entry'],
            language=language, budget_bytes=budget, command=list(map(str, cmd)), rows=rows, process_wall_ns=wall,
            process_user_cpu_s=after.ru_utime-before.ru_utime, process_system_cpu_s=after.ru_stime-before.ru_stime))
        if not normal and language == 'java':
            harness.command(['java', '-cp', output, 'SummarizeJfr', recording], ROOT,
                            output / (stem + '-summary.tsv'))
        (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print(stem, 'verified', len(rows), flush=True)
    try:
        for case in cases:
            for language in ['java', 'rust-java', 'rust-native']:
                capture(case, language, None)
            for mib in args.budgets_mib:
                for language in ['java', 'rust-java', 'rust-native']:
                    capture(case, language, mib * 1024 * 1024)
    except BaseException as failure:
        metadata['errors'].append(str(failure))
        raise
    finally:
        (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tiny', type=Path, required=True)
    parser.add_argument('--build', type=Path, default=ROOT / 'target/tiny-classic-benchmark')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/tiny-classic-profile')
    parser.add_argument('--cases', nargs='+', default=['fraud-1', 'fraud-4'])
    parser.add_argument('--all', action='store_true', help='profile all manifest cases, retaining explicit entries')
    parser.add_argument('--budgets-mib', nargs='+', type=int, default=[16, 64])
    parser.add_argument('--warmups', type=int, default=1)
    parser.add_argument('--iterations', type=int, default=1)
    parser.add_argument('--timeout', type=float, default=180)
    args = parser.parse_args()
    if (any(value < 1 or value > 1024 for value in args.budgets_mib)
            or args.warmups < 0 or args.iterations < 0 or args.timeout <= 0):
        parser.error('finite budgets 1..1024 MiB, nonnegative counts and positive timeout required')
    for name in ['tiny', 'build', 'output']:
        setattr(args, name, getattr(args, name).resolve())
    run(args)


if __name__ == '__main__':
    main()
