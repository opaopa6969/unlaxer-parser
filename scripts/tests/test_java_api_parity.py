"""公開宣言・対応表・根拠リンク・CI配線の欠落を成功扱いしない。"""
import copy
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check-java-api-parity.py"
SPEC = importlib.util.spec_from_file_location("java_api_parity", SCRIPT)
api = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(api)


class JavaApiParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.actual = api.inventory(api.ROOT)
        cls.data = json.loads((api.ROOT / api.DATA).read_text())

    def errors(self, data=None, actual=None):
        return "\n".join(api.validate(api.ROOT, self.data if data is None else data,
                                     self.actual if actual is None else actual))

    def test_repository_inventory_and_generated_document_match(self):
        self.assertEqual("", self.errors())
        self.assertEqual((api.ROOT / api.DOCUMENT).read_text(), api.render(self.data, self.actual))
        paths = {row['path'] for row in self.actual}
        for directory in ('combinator', 'elementary', 'posix', 'referencer'):
            self.assertTrue(any('/parser/' + directory + '/' in path for path in paths))

    def test_ast_handles_visibility_nested_types_overloads_and_fake_declarations(self):
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = Path(temporary)
            path = root / 'Example.java'
            path.write_text('''package org.unlaxer.parser;
public interface Example {
  // public class Fake { public static Fake of() { return null; } }
  String TEXT = "public static Example fake()";
  static <T extends Example> T make(java.util.List<? extends T> values) { return null; }
  static Example make(String... values) { return null; }
  private static Example secret() { return null; }
  class Nested { public static Nested of() { return null; } }
  enum Kind { A }
  record Item(String text) {}
  Example instance();
}
class Hidden { public static class Leaked {} }
''')
            rows = api.inventory(root, [path])
            self.assertEqual({
                'org.unlaxer.parser.Example',
                'org.unlaxer.parser.Example#make(java.util.List<? extends T>)',
                'org.unlaxer.parser.Example#make(String[])',
                'org.unlaxer.parser.Example.Nested',
                'org.unlaxer.parser.Example.Nested#of()',
                'org.unlaxer.parser.Example.Kind',
                'org.unlaxer.parser.Example.Item',
            }, {row['id'] for row in rows})
            self.assertTrue(all(row['line'] > 0 for row in rows))

    def test_added_source_file_and_factory_fail_until_registered(self):
        # 実ファイルを走査する mutation。名前固定の件数比較だけでは通らない。
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = Path(temporary)
            package = root / api.JAVA / 'parser/newpackage'
            package.mkdir(parents=True)
            path = package / 'Added.java'
            path.write_text('package org.unlaxer.parser.newpackage; '
                            'public class Added { public static Added create() { return null; } }')
            self.assertIn(path, api.source_paths(root))
            added = api.inventory(root, [path])
            errors = self.errors(actual=self.actual + added)
            self.assertIn('未登録の公開 API: org.unlaxer.parser.newpackage.Added\n', errors)
            self.assertIn('未登録の公開 API: org.unlaxer.parser.newpackage.Added#create()', errors)

    def test_added_type_in_context_related_packages_fails_until_registered(self):
        # ParseContext の interface・診断・snapshot・reducer・listener は package 全体を走査する（#716 第1段レビュー）。
        for package in ('context', 'capture', 'reducer', 'listener'):
            with self.subTest(package=package), tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
                root = Path(temporary)
                directory = root / api.JAVA / package
                directory.mkdir(parents=True)
                path = directory / 'AddedProbe.java'
                path.write_text(f'package org.unlaxer.{package}; public interface AddedProbe {{}}')
                self.assertIn(path, api.source_paths(root))
                errors = self.errors(actual=self.actual + api.inventory(root, [path]))
                self.assertIn(f'未登録の公開 API: org.unlaxer.{package}.AddedProbe', errors)

    def test_failure_diagnostics_and_parse_context_interfaces_are_inventoried(self):
        ids = {row['id'] for row in self.actual}
        for name in ('context.ParseOptions', 'context.ParseFailureDiagnostics', 'context.RuleStack',
                     'context.RaisedFailure', 'context.Transaction', 'context.ParseContextBase',
                     'context.PortableTokenMetadata', 'capture.CaptureSnapshot', 'reducer.CommittedReducer',
                     'listener.ParserListenerContainer'):
            self.assertIn('org.unlaxer.' + name, ids)

    def test_evidence_line_pointing_at_blank_or_brace_fails(self):
        data = copy.deepcopy(self.data)
        lines = (api.ROOT / 'rust/unlaxer-runtime/src/lib.rs').read_text().splitlines()
        blank = next(number for number, text in enumerate(lines, 1) if text.strip() == '}')
        data['assessments']['owned-capture']['code'] = [
            {'location': f'rust/unlaxer-runtime/src/lib.rs#L{blank}', 'anchor': '}'}]
        self.assertIn('根拠 anchor が空行または括弧だけ', self.errors(data))

    def test_line_location_without_anchor_fails(self):
        # 行番号だけの根拠は内容を確かめられない（#859）。
        data = copy.deepcopy(self.data)
        data['assessments']['owned-capture']['code'] = ['rust/unlaxer-runtime/src/lib.rs#L1']
        self.assertIn('行の根拠に anchor がない: rust/unlaxer-runtime/src/lib.rs#L1', self.errors(data))
        data['assessments']['owned-capture']['code'] = [{'location': 'rust/unlaxer-runtime/src/lib.rs#L1'}]
        self.assertIn('根拠 anchor の形式が不正', self.errors(data))

    def test_every_line_evidence_is_anchored_to_its_line(self):
        for name, entry in self.data['assessments'].items():
            for key in api.EVIDENCE:
                for evidence in entry.get(key, []):
                    with self.subTest(assessment=name, evidence=evidence):
                        if type(evidence) is str:
                            self.assertNotIn('#', evidence)
                            continue
                        path, _, line = evidence['location'].partition('#L')
                        text = (api.ROOT / path).read_text().splitlines()[int(line) - 1]
                        self.assertTrue(api.matches(text, evidence['anchor']), text)

    def test_anchor_matching_stops_at_identifier_boundaries(self):
        self.assertTrue(api.matches('    JavaLookahead {', 'JavaLookahead'))
        self.assertTrue(api.matches('\tpublic  class A\textends B{', 'public class A extends B'))
        self.assertFalse(api.matches('    JavaLookaheadOld {', 'JavaLookahead'))
        self.assertFalse(api.matches('    InputCaptureBoundary(Kind, Box<Expr>),', 'CaptureBoundary('))
        self.assertFalse(api.matches('    // pub fn repeat_java(', 'pub fn repeat_java('))

    def test_line_start_parsers_cite_the_declarative_bol_bof_route(self):
        # 名前の不一致で未対応と断定しない: UBNF/Rust の BOL・BOF は両 host の共通 corpus を持つ。
        for name, op in (('start-of-line', 'Op::BOL'), ('start-of-source', 'Op::BOF')):
            entry = self.data['assessments'][name]
            self.assertEqual('uncorroborated', entry['status'])
            self.assertIn('spec-corpus/declarative-tokens/runtime.json', entry['fixtures'])
            evidence = next(c for c in entry['code'] if type(c) is dict
                            and c['location'].startswith('rust/unlaxer-runtime/src/lexical.rs#L'))
            self.assertTrue(evidence['anchor'].startswith(op))
            line = int(evidence['location'].rpartition('#L')[2])
            self.assertIn(op, (api.ROOT / 'rust/unlaxer-runtime/src/lexical.rs').read_text().splitlines()[line - 1])

    RUNTIME = 'rust/unlaxer-runtime/src/lib.rs'

    def overlay(self, temporary, replacements):
        """ROOT を symlink で写し、replacements のファイルだけ実体へ置き換えた検査用 root。"""
        root = Path(temporary)
        for relative, text in replacements.items():
            target = root
            for part in Path(relative).parts[:-1]:
                target = target / part
                target.mkdir(exist_ok=True)
            (target / Path(relative).name).write_text(text)
        for relative in replacements:
            source, target = api.ROOT, root
            for part in Path(relative).parts:
                for child in source.iterdir():
                    if not (target / child.name).exists() and not (target / child.name).is_symlink():
                        (target / child.name).symlink_to(child, target_is_directory=child.is_dir())
                source, target = source / part, target / part
        return root

    def runtime_evidence(self, data):
        return [evidence for entry in data['assessments'].values() for key in api.EVIDENCE
                for evidence in entry.get(key, [])
                if type(evidence) is dict and evidence['location'].startswith(self.RUNTIME + '#L')]

    def runtime_errors(self, root, data):
        return [error for error in api.validate(root, data, self.actual) if self.RUNTIME in error]

    def test_inserted_or_deleted_lines_fail_until_remapped(self):
        lines = (api.ROOT / self.RUNTIME).read_text().splitlines(keepends=True)
        blank = next(index for index, text in enumerate(lines) if index > 0 and not text.strip())
        for label, mutated, shift in (('insert', lines[:blank] + ['// inserted\n'] + lines[blank:], 1),
                                      ('delete', lines[:blank] + lines[blank + 1:], -1)):
            with self.subTest(mutation=label), tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
                root = self.overlay(temporary, {self.RUNTIME: ''.join(mutated)})
                moved = {evidence['location'] for evidence in self.runtime_evidence(self.data)
                         if int(evidence['location'].rpartition('#L')[2]) > blank}
                self.assertGreater(len(moved), 10)
                # 旧検査（空行・括弧だけを拒否）なら通っていた、別の宣言を指す参照を含む。
                text = ''.join(mutated).splitlines()
                self.assertTrue(any(text[int(location.rpartition('#L')[2]) - 1].strip() not in api.BLANK_LINES
                                    for location in moved))
                errors = self.runtime_errors(root, self.data)
                self.assertEqual(moved, {error.split(': ', 1)[1].split(' → ')[0] for error in errors})
                self.assertTrue(all('根拠行が anchor とずれた' in error for error in errors))
                remapped, changes, failures = api.remap(root, self.data)
                self.assertEqual([], failures)
                self.assertEqual([], self.runtime_errors(root, remapped))
                for before, after in zip(self.runtime_evidence(self.data), self.runtime_evidence(remapped)):
                    old = int(before['location'].rpartition('#L')[2])
                    self.assertEqual(old + (shift if old > blank else 0), int(after['location'].rpartition('#L')[2]))
                    self.assertEqual(before['anchor'], after['anchor'])

    def test_renamed_declaration_fails_and_remap_refuses(self):
        source = (api.ROOT / self.RUNTIME).read_text()
        # 改名後の名前が旧名を前方に含んでも、識別子の途中では一致しない。
        renamed = source.replace('pub fn repeat_java(', 'pub fn repeat_java_classic(', 1)
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = self.overlay(temporary, {self.RUNTIME: renamed})
            errors = '\n'.join(self.runtime_errors(root, self.data))
            self.assertIn('lib.rs#L410（anchor が見つからない', errors)
            _, _, failures = api.remap(root, self.data)
            self.assertTrue(any('lib.rs#L410（anchor が見つからない' in failure for failure in failures))

    def test_ambiguous_anchor_fails_and_remap_does_not_pick_the_first(self):
        source = (api.ROOT / self.RUNTIME).read_text()
        duplicate = source + '\nmod duplicate {\n    pub fn repeat_java(self, min: usize, max: Option<usize>) -> Self {\n    }\n}\n'
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = self.overlay(temporary, {self.RUNTIME: duplicate})
            errors = '\n'.join(self.runtime_errors(root, self.data))
            self.assertRegex(errors, r'lib\.rs#L410（anchor が一意でない: L410, L\d+')
            _, _, failures = api.remap(root, self.data)
            self.assertTrue(any('lib.rs#L410（anchor が一意でない' in failure for failure in failures))

    def test_scoped_anchor_does_not_escape_its_declaration(self):
        # Expr::Number を消しても、後続の Predictor::Number へ付け替えない。
        lines = (api.ROOT / self.RUNTIME).read_text().splitlines(keepends=True)
        self.assertEqual('Number,', lines[107].strip())
        self.assertIn({'location': self.RUNTIME + '#L108', 'anchor': 'Number,', 'scope': 'pub enum Expr'},
                      self.data['assessments']['lexical-composites']['code'])
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = self.overlay(temporary, {self.RUNTIME: ''.join(lines[:107] + lines[108:])})
            self.assertIn('lib.rs#L108（anchor が見つからない', '\n'.join(self.runtime_errors(root, self.data)))
            _, _, failures = api.remap(root, self.data)
            self.assertTrue(any('lib.rs#L108（anchor が見つからない' in failure for failure in failures))

    def test_cli_remap_rewrites_only_line_numbers_and_regenerates_links(self):
        lines = (api.ROOT / self.RUNTIME).read_text().splitlines(keepends=True)
        documents = {api.DATA: (api.ROOT / api.DATA).read_text(), api.DOCUMENT: (api.ROOT / api.DOCUMENT).read_text()}
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = self.overlay(temporary, {self.RUNTIME: ''.join(lines[:1] + ['// inserted\n'] + lines[1:]),
                                            **documents})
            command = [sys.executable, str(SCRIPT), '--root', str(root)]
            result = subprocess.run(command, capture_output=True, text=True, timeout=90)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('根拠行が anchor とずれた: rust/unlaxer-runtime/src/lib.rs#L410 → L411', result.stderr)
            result = subprocess.run(command + ['--remap', '--write-doc'], capture_output=True, text=True, timeout=90)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('根拠行を付け替え: rust/unlaxer-runtime/src/lib.rs#L410 → L411', result.stderr)
            rewritten = json.loads((root / api.DATA).read_text())
            self.assertEqual(api.dump(rewritten), (root / api.DATA).read_text())
            expected, _, _ = api.remap(root, self.data)
            self.assertEqual(expected, rewritten)
            document = (root / api.DOCUMENT).read_text()
            self.assertIn('](../rust/unlaxer-runtime/src/lib.rs#L411)', document)
            self.assertNotIn('](../rust/unlaxer-runtime/src/lib.rs#L410)', document)
            self.assertEqual(0, subprocess.run(command, capture_output=True, timeout=90).returncode)

    def test_cli_remap_refuses_without_writing_when_any_anchor_is_unresolved(self):
        source = (api.ROOT / self.RUNTIME).read_text()
        documents = {api.DATA: (api.ROOT / api.DATA).read_text(), api.DOCUMENT: (api.ROOT / api.DOCUMENT).read_text()}
        # 行挿入（付け替え可能）と改名（再監査が必要）が同時にあっても、一部だけ書き換えない。
        mutated = '// inserted\n' + source.replace('pub fn repeat_java(', 'pub fn repeat_classic(', 1)
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = self.overlay(temporary, {self.RUNTIME: mutated, **documents})
            result = subprocess.run([sys.executable, str(SCRIPT), '--root', str(root), '--remap', '--write-doc'],
                                    capture_output=True, text=True, timeout=90)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('anchor が見つからない', result.stderr)
            self.assertEqual(documents[api.DATA], (root / api.DATA).read_text())
            self.assertEqual(documents[api.DOCUMENT], (root / api.DOCUMENT).read_text())

    def test_remap_reports_unverifiable_evidence_instead_of_skipping_it(self):
        # 読み飛ばすと、残りの根拠だけ付け替えた JSON を書き戻してしまう（#859 査読）。
        for label, evidence, message in (
                ('unanchored', 'rust/unlaxer-runtime/src/lib.rs#L1', '行の根拠に anchor がない'),
                ('malformed', {'location': 'rust/unlaxer-runtime/src/lib.rs#L1'}, '根拠 anchor の形式が不正'),
                ('blank', {'location': 'rust/unlaxer-runtime/src/lib.rs#L1', 'anchor': '}'}, '根拠 anchor の形式が不正'),
                ('outside', {'location': '../outside.rs#L1', 'anchor': 'fn outside'}, '根拠ファイルがない'),
                ('absolute', {'location': str(SCRIPT) + '#L1', 'anchor': '#!/usr/bin/env python3'}, '根拠ファイルがない')):
            with self.subTest(case=label):
                data = copy.deepcopy(self.data)
                data['assessments']['owned-capture']['code'] = [evidence]
                remapped, _, failures = api.remap(api.ROOT, data)
                self.assertTrue(any(message in failure for failure in failures), failures)
                self.assertEqual(data, remapped)

    def test_cli_remap_leaves_both_files_byte_identical_when_any_check_fails(self):
        # 付け替えられる行ずれと、付け替えとは別の検査失敗が同時にあっても、JSON だけを書き換えない。
        source = (api.ROOT / self.RUNTIME).read_text()
        original = json.loads((api.ROOT / api.DATA).read_text())

        def other_evidence(data):
            return next((entry[key], index) for entry in data['assessments'].values() for key in api.EVIDENCE
                        for index, evidence in enumerate(entry.get(key, []))
                        if type(evidence) is dict and self.RUNTIME not in evidence['location'])

        def unanchored(data):
            evidences, index = other_evidence(data)
            evidences[index] = evidences[index]['location']

        def malformed(data):
            evidences, index = other_evidence(data)
            evidences[index] = {'location': evidences[index]['location']}

        def blank(data):
            evidences, index = other_evidence(data)
            evidences[index] = dict(evidences[index], anchor='}')

        def schema(data):
            data['schema_version'] = 1

        def inventory(data):
            data['apis'].pop(0)

        for label, mutate, message, options in (
                ('unanchored', unanchored, '行の根拠に anchor がない', ['--write-doc']),
                ('malformed', malformed, '根拠 anchor の形式が不正', ['--write-doc']),
                ('blank', blank, '根拠 anchor の形式が不正', ['--write-doc']),
                ('schema', schema, 'schema_version は 2 が必要', ['--write-doc']),
                ('inventory', inventory, '未登録の公開 API', ['--write-doc']),
                # --write-doc なしの付け替えは、Markdown のリンクが古いままになるので書かない。
                ('stale-document', None, 'Markdown が JSON/ソースと不一致', [])):
            with self.subTest(case=label), tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
                data = copy.deepcopy(original)
                if mutate:
                    mutate(data)
                files = {api.DATA: api.dump(data).encode(), api.DOCUMENT: (api.ROOT / api.DOCUMENT).read_bytes()}
                root = self.overlay(temporary, {self.RUNTIME: '// inserted\n' + source,
                                                **{path: text.decode() for path, text in files.items()}})
                result = subprocess.run([sys.executable, str(SCRIPT), '--root', str(root), '--remap', *options],
                                        capture_output=True, text=True, timeout=90)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)
                self.assertNotIn('根拠行を付け替え', result.stderr)
                for path, content in files.items():
                    self.assertEqual(content, (root / path).read_bytes(), path)

    def test_every_unverified_row_belongs_to_a_registered_child_issue(self):
        followups = {entry['number'] for entry in self.data['followups'].values()}
        for name, entry in self.data['assessments'].items():
            if entry['status'] != 'verified':
                with self.subTest(assessment=name):
                    self.assertTrue(set(entry['issues']) & followups)

    def test_deleted_registration_and_deleted_declaration_fail(self):
        data = copy.deepcopy(self.data)
        removed = data['apis'].pop()
        self.assertIn('未登録の公開 API: ' + removed['id'], self.errors(data))
        self.assertIn('消えた公開 API: ' + self.actual[-1]['id'], self.errors(actual=self.actual[:-1]))

    def test_duplicate_and_changed_signature_fail(self):
        data = copy.deepcopy(self.data)
        data['apis'].append(copy.deepcopy(data['apis'][0]))
        self.assertIn('API ID が重複', self.errors(data))
        data = copy.deepcopy(self.data)
        next(row for row in data['apis'] if row['kind'] == 'static')['signature'] += ' changed'
        self.assertIn('公開 API の宣言変更', self.errors(data))

    def test_missing_assessment_evidence_and_followup_fail(self):
        data = copy.deepcopy(self.data)
        data['apis'][0]['assessment'] = 'unreviewed'
        self.assertIn('判定がない', self.errors(data))
        data = copy.deepcopy(self.data)
        data['assessments']['owned-capture']['fixtures'] = []
        self.assertIn('共通検証根拠がない', self.errors(data))
        data = copy.deepcopy(self.data)
        data['assessments']['reference-by-name']['issues'] = []
        self.assertIn('後続 issue がない', self.errors(data))
        data = copy.deepcopy(self.data)
        data['assessments']['owned-capture']['code'] = ['rust/not-present.rs#L1']
        self.assertIn('根拠ファイルがない', self.errors(data))

    def test_named_review_targets_have_distinct_assessments(self):
        expected = {
            'parser.combinator.ChildOccursWithTerminator': 'terminated-repeat',
            'parser.referencer.MatchedChoiceParser': 'selected-choice',
            'parser.elementary.StartOfLineParser': 'start-of-line',
            'parser.SuggestableParser': 'suggest', 'parser.Suggest': 'suggest',
            'parser.combinator.TagWrapper': 'tag-wrapper',
            'parser.MetaFunctionParser': 'meta-function',
            'parser.referencer.ReferenceByNameParser': 'reference-by-name',
            'parser.combinator.NotASTNode': 'not-ast-node',
            'parser.combinator.PredicateAnyMatchForParsedParser': 'parsed-predicate',
            'parser.elementary.EscapeInQuotedParser': 'escape-in-quoted',
            'parser.elementary.ParenthesesParser': 'parentheses',
            'parser.referencer.MatchedNonOrderedParser': 'selected-order',
        }
        rows = {row['id']: row for row in self.data['apis']}
        for name, assessment in expected.items():
            self.assertEqual(assessment, rows['org.unlaxer.' + name]['assessment'])
            self.assertTrue(self.data['assessments'][assessment]['code'])

    def test_document_local_links_and_assessment_anchors_exist(self):
        document = (api.ROOT / api.DOCUMENT).read_text()
        for location in re.findall(r'\]\(([^)]+)\)', document):
            if location.startswith('https://'):
                continue
            if location.startswith('#'):
                self.assertIn('### ' + location[1:] + '\n', document)
                continue
            path, _, anchor = location.partition('#')
            target = (api.ROOT / 'docs' / path).resolve()
            self.assertTrue(target.is_file(), location)
            if anchor:
                self.assertRegex(anchor, r'^L\d+$')
                self.assertLessEqual(int(anchor[1:]), len(target.read_text().splitlines()), location)

    def test_required_ci_runner_is_registered_and_wired(self):
        # Surefire 契約に属する補助Python検査。JUnitのrequired一覧には混ぜない。
        contracts = json.loads((api.ROOT / '.github/optin-test-contracts.json').read_text())
        modules = contracts['cross-language']['python_unittests']
        self.assertIn('scripts.tests.test_java_api_parity', modules)
        workflow = (api.ROOT / '.github/workflows/maven.yml').read_text()
        for module in modules:
            self.assertIn('python3 scripts/run-required-unittests.py ' + module, workflow)
            self.assertTrue((api.ROOT / (module.replace('.', '/') + '.py')).is_file())

    def test_cli_refuses_unreviewed_inventory_even_with_write_doc(self):
        with tempfile.TemporaryDirectory(prefix='java-api-') as temporary:
            root = Path(temporary)
            (root / 'docs').mkdir()
            # 実APIから1行除いた対応表に --write-doc をかけても自動承認しない。
            data = copy.deepcopy(self.data)
            data['apis'].pop()
            (root / api.DATA).write_text(json.dumps(data))
            (root / 'unlaxer-common').symlink_to(api.ROOT / 'unlaxer-common', target_is_directory=True)
            (root / 'rust').symlink_to(api.ROOT / 'rust', target_is_directory=True)
            (root / 'unlaxer-dsl').symlink_to(api.ROOT / 'unlaxer-dsl', target_is_directory=True)
            (root / 'spec-corpus').symlink_to(api.ROOT / 'spec-corpus', target_is_directory=True)
            (root / 'conformance').symlink_to(api.ROOT / 'conformance', target_is_directory=True)
            result = subprocess.run([sys.executable, str(SCRIPT), '--root', str(root), '--write-doc'],
                                    capture_output=True, text=True, timeout=90)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('未登録の公開 API', result.stderr)
            self.assertFalse((root / api.DOCUMENT).exists())


if __name__ == '__main__':
    unittest.main()
