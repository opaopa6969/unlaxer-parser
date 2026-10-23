#!/usr/bin/env python3
"""Require actual, successful Surefire execution for a named CI contract (#719).

Only Python's standard library is needed. Unrelated suites (including intentional
opt-in skips in plain Java builds) are outside the selected contract.
"""
import argparse
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET


CONTRACTS = Path(__file__).resolve().parents[1] / '.github/optin-test-contracts.json'
BAD_RESULTS = ('skipped', 'failure', 'error', 'flakyFailure', 'flakyError',
               'rerunFailure', 'rerunError')


def report_paths(root, contract, name):
    return [root / directory / f'TEST-{name}.xml' for directory in contract['reports']]


def prepare(root, contract, output):
    """Remove only this contract's generated XML; an older pass cannot mask omission."""
    for name in contract['required']:
        for path in report_paths(root, contract, name):
            path.unlink(missing_ok=True)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)


def check_suite(suite, name, methods, properties):
    errors = []
    cases = suite.findall('testcase')
    if not cases:
        errors.append('testcase がない（ゼロ実行）')
    for counter, tag in [('tests', None), ('skipped', 'skipped'),
                         ('failures', 'failure'), ('errors', 'error')]:
        try:
            value = int(suite.attrib[counter])
        except (KeyError, ValueError):
            errors.append(f'{counter} カウンターがない、または整数でない')
            continue
        actual = len(cases) if tag is None else sum(c.find(tag) is not None for c in cases)
        if value != actual:
            errors.append(f'{counter}={value} と testcase の件数 {actual} が不一致')
        if counter != 'tests' and value != 0:
            errors.append(f'{counter}={value}（必須テストは 0 が必要）')
    for case in cases:
        method = case.get('name', '')
        if case.get('classname') != name or not method:
            errors.append(f'testcase の class/name が不正: {case.attrib}')
        for tag in BAD_RESULTS:
            if case.find(tag) is not None:
                errors.append(f'{method}: {tag}')
    # Surefire parameterized invocations use names such as method[0] or method[case].
    for method in methods:
        if not any(c.get('name') == method or c.get('name', '').startswith(method + '[') for c in cases):
            errors.append(f'必須メソッドが未実行: {method}')
    observed = {p.get('name'): p.get('value') for p in suite.findall('properties/property')}
    for key, expected in properties.items():
        actual = observed.get(key)
        if (expected is None and not (actual or '').strip()) or (expected is not None and actual != expected):
            errors.append(f'必須 property {key}: expected={expected!r}, actual={actual!r}')
    return errors, len(cases)


def verify(root, contract, output):
    if not contract['required']:
        raise ValueError('必須テスト一覧が空です')
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)
    errors, observations = [], []
    for name, methods in contract['required'].items():
        paths = [p for p in report_paths(root, contract, name) if p.is_file()]
        if len(paths) != 1:
            errors.append(f'{name}: Surefire XML は 1 個必要（実際: {len(paths)}）')
            continue
        path = paths[0]
        shutil.copyfile(path, output / path.name)
        try:
            document = ET.parse(path).getroot()
            suites = [document] if document.tag == 'testsuite' else document.findall('testsuite')
            suites = [s for s in suites if s.get('name') == name]
            if len(suites) != 1:
                errors.append(f'{name}: 対象 testsuite がない、または重複している')
                continue
            problems, tests = check_suite(suites[0], name, methods, contract.get('properties', {}))
            errors.extend(f'{name}: {problem}' for problem in problems)
            observations.append({'class': name, 'tests': tests, 'report': str(path), 'ok': not problems})
        except (ET.ParseError, OSError) as error:
            errors.append(f'{name}: XML を読めない: {error}')
    result = {'ok': not errors, 'suites': observations, 'errors': errors}
    (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('contract', help='optin-test-contracts.json 内の契約名')
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--contracts', type=Path, default=CONTRACTS)
    parser.add_argument('--prepare', action='store_true', help='実行前に対象の古い XML を消す')
    args = parser.parse_args()
    try:
        contract = json.loads(args.contracts.read_text())[args.contract]
        output = args.root / 'target/optin-evidence' / args.contract
        if args.prepare:
            prepare(args.root, contract, output)
            print(f'{args.contract}: 対象 XML を初期化しました')
            return 0
        result = verify(args.root, contract, output)
        for error in result['errors']:
            print(error, file=sys.stderr)
        print(f"{args.contract}: {'PASS' if result['ok'] else 'FAIL'} "
              f"({len(result['suites'])} suites, {sum(s['tests'] for s in result['suites'])} tests)")
        return 0 if result['ok'] else 1
    except (KeyError, ValueError, OSError) as error:
        print(f'検査設定エラー: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
