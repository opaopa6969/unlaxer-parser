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
        data['assessments']['owned-capture']['code'] = [f'rust/unlaxer-runtime/src/lib.rs#L{blank}']
        self.assertIn('根拠行が空行または括弧だけ', self.errors(data))

    def test_line_start_parsers_cite_the_declarative_bol_bof_route(self):
        # 名前の不一致で未対応と断定しない: UBNF/Rust の BOL・BOF は両 host の共通 corpus を持つ。
        for name, op in (('start-of-line', 'Op::BOL'), ('start-of-source', 'Op::BOF')):
            entry = self.data['assessments'][name]
            self.assertEqual('verified', entry['status'])
            self.assertIn('spec-corpus/declarative-tokens/runtime.json', entry['fixtures'])
            self.assertIn('conformance/line-boundary/cases.tsv', entry['fixtures'])
            self.assertIn('unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java', entry['tests'])
            location = next(c for c in entry['code'] if c.startswith('rust/unlaxer-runtime/src/lexical.rs#L'))
            line = int(location.rpartition('#L')[2])
            self.assertIn(op, (api.ROOT / 'rust/unlaxer-runtime/src/lexical.rs').read_text().splitlines()[line - 1])

    def test_line_composition_does_not_claim_terminated_repeat_completion(self):
        rows = {row['id']: row for row in self.data['apis']}
        for name in ('EmptyLineParser', 'WildCardLineParser'):
            entry = self.data['assessments'][rows['org.unlaxer.parser.elementary.' + name]['assessment']]
            self.assertEqual('uncorroborated', entry['status'])
            self.assertTrue({846, 848}.issubset(entry['issues']))
            self.assertIn('conformance/line-boundary/repeat-choice.tsv', entry['fixtures'])
            self.assertIn('conformance/line-boundary/char-class.tsv', entry['fixtures'])
        for name in ('LineTerminatorParser', 'EndOfLineParser'):
            entry = self.data['assessments'][rows['org.unlaxer.parser.elementary.' + name]['assessment']]
            self.assertEqual('verified', entry['status'])
            self.assertIn('conformance/line-boundary/cases.tsv', entry['fixtures'])
            # Java の DETAILED_ON_FAILURE 限定の FIRST 除外欠陥は別 issue で追跡する。
            self.assertIn(861, entry['issues'])

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
