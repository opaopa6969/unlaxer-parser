#!/usr/bin/env python3
"""#789: validate failure / success memo budget conformance evidence TSVs.

Each evidence file is written by one test as a whole file (Java report, or the Rust oracle via
UNLAXER_TEST_*_BUDGET_EVIDENCE), never parsed out of libtest stdout. A file passes only when:

* the first line is exactly HEADER and every row has len(COLUMNS) non-empty fields;
* mode/reset/memo/diagnostics belong to one fixed Java or Rust profile and
  entries/charge are non-negative integers;
* every id is a case of --cases and every case appears;
* the key (id, mode, reset, memo, diagnostics) is unique;
* the keys are the full cartesian product of the required values of each key column
  (the evidence cannot redefine the required coverage);
* the number of rows is exactly --rows.

Every problem is printed with the file name before exiting with status 1.
"""
import argparse
from itertools import product
from pathlib import Path
import re
import sys

COLUMNS = ('id', 'mode', 'reset', 'memo', 'diagnostics', 'entries', 'charge')
HEADER = '\t'.join(COLUMNS)
KEY = len(COLUMNS) - 2
INTEGER = re.compile(r'(0|[1-9][0-9]*)\Z')
MAX_REPORTED = 10
# Keep these independent of the evidence: 10 cases x 5 modes x 2 resets x 2 memo
# policies x 3 diagnostics. Java and Rust spell their enums differently.
PROFILES = {
    'Java': (
        ('normal', 'consumed', 'matchOnly', 'virtualTokenConsumed', 'virtualTokenMatchOnly'),
        ('false', 'true'), ('OFF', 'SAFE_FAILURES'),
        ('AUTO', 'DETAILED', 'DETAILED_ON_FAILURE'),
    ),
    'Rust': (
        ('None', *(f'Some(ParseMode {{ kind: {kind}, invert: false }})'
                   for kind in ('Consumed', 'MatchOnly', 'VirtualConsumed', 'VirtualMatchOnly'))),
        ('false', 'true'), ('Off', 'SafeFailures'),
        ('Auto', 'Detailed', 'DetailedOnFailure'),
    ),
}


def case_ids(cases):
    ids = []
    for line in cases.read_text(encoding='utf-8').splitlines():
        if line.startswith('#') or not line.strip():
            continue
        ids.append(line.split('\t', 1)[0])
    if len(set(ids)) != len(ids):
        raise ValueError(f'{cases}: duplicate case ids')
    return ids


def check(path, cases, rows):
    """Returns the list of problems found in the evidence file (empty when it passes)."""
    if not path.is_file():
        return [f'{path}: missing']
    data = path.read_bytes()
    if not data:
        return [f'{path}: empty']
    if b'\r' in data:
        return [f'{path}: contains CR (expected LF line endings)']
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError as error:
        return [f'{path}: not UTF-8 ({error})']
    if not text.endswith('\n'):
        return [f'{path}: last line is not terminated']
    lines = text[:-1].split('\n')
    problems = []
    if lines[0] != HEADER:
        problems.append(f'{path}:1: header {lines[0]!r}, expected {HEADER!r}')
    expected_ids = case_ids(cases)
    known = set(expected_ids)
    seen = {}
    profile = None
    for number, line in enumerate(lines[1:], start=2):
        fields = line.split('\t')
        if len(fields) != len(COLUMNS) or any(not field for field in fields):
            problems.append(f'{path}:{number}: {len(fields)} fields, expected {len(COLUMNS)} non-empty: {line!r}')
            continue
        if fields[0] not in known:
            problems.append(f'{path}:{number}: unknown case id {fields[0]!r}')
        if profile is None:
            profile = next((values for values in PROFILES.values() if fields[1] in values[0]), None)
        if profile is None:
            problems.append(f'{path}:{number}: unknown mode {fields[1]!r}')
        else:
            for column, allowed in enumerate(profile, start=1):
                if fields[column] not in allowed:
                    problems.append(f'{path}:{number}: invalid {COLUMNS[column]} {fields[column]!r}; expected one of {allowed}')
        for column in (5, 6):
            if not INTEGER.match(fields[column]):
                problems.append(f'{path}:{number}: {COLUMNS[column]} {fields[column]!r} is not a non-negative integer')
        key = tuple(fields[:KEY])
        if key in seen:
            problems.append(f'{path}:{number}: duplicate key {key} (first at line {seen[key]})')
        else:
            seen[key] = number
    missing_ids = [case for case in expected_ids if case not in {key[0] for key in seen}]
    if missing_ids:
        problems.append(f'{path}: missing case ids {missing_ids}')
    if profile is not None:
        expected = set(product(expected_ids, *profile))
        missing = expected - seen.keys()
        extra = seen.keys() - expected
        if missing or extra:
            problems.append(f'{path}: combinations: {len(missing)} missing, {len(extra)} unexpected '
                            f'({len(seen)} distinct keys, expected {len(expected)})')
    count = len(lines) - 1
    if count != rows:
        problems.append(f'{path}: {count} rows, expected {rows}')
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--cases', type=Path, required=True, help='conformance/*-memo-budget/cases.tsv')
    parser.add_argument('--rows', type=int, required=True, help='expected number of data rows')
    parser.add_argument('evidence', type=Path, nargs='+')
    args = parser.parse_args(argv)
    failed = False
    for path in args.evidence:
        problems = check(path, args.cases, args.rows)
        if problems:
            failed = True
            for problem in problems[:MAX_REPORTED]:
                print(problem)
            if len(problems) > MAX_REPORTED:
                print(f'{path}: ... {len(problems) - MAX_REPORTED} more problems')
            print(f'FAIL {path}')
        else:
            print(f'OK {path}: {args.rows} rows, {len(case_ids(args.cases))} cases, unique complete keys')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
