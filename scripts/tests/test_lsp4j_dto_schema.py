"""sources 由来の型表と、独立した全 enum 欄 wire fixture の網羅性。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('dto_schema', ROOT / 'scripts/generate-lsp4j-dto-schema.py')
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class DtoSchemaTest(unittest.TestCase):
    def test_all_client_enum_fields_have_independent_wire_fragments(self):
        schema = json.loads((ROOT / 'rust/unlaxer-ubnf-lsp/src/lsp4j-dto-schema.json').read_text())
        fixture = json.loads((ROOT / 'spec-corpus/ubnf-authoring-lsp/jsonrpc.json').read_text())['enumReader']
        actual = {}

        def walk(kind, path):
            if isinstance(kind, list):
                if kind[0] == 'enum':
                    actual[path] = schema['enums'][kind[1]]['reader']
                else:
                    for item in kind[1:]:
                        walk(item, path + ('[]' if kind[0] == 'list' else ''))
            elif kind in schema['types']:
                for name, child in schema['types'][kind].items():
                    walk(child, path + '.' + name)

        walk('ClientCapabilities', '')
        expected = {}

        def leaf(value, path):
            if isinstance(value, dict):
                for key, child in value.items():
                    leaf(child, path + '.' + key)
            elif isinstance(value, list):
                self.assertEqual(1, len(value))
                leaf(value[0], path + '[]')
            else:
                self.assertEqual('$VALUE', value)
                expected[path] = field['reader']

        for field in fixture['fields']:
            leaf(json.loads(field['raw'].replace('$VALUE', '"$VALUE"')), '')
        self.assertEqual(21, len(expected))
        self.assertEqual(expected, actual)
        self.assertEqual((10, 11), (list(actual.values()).count('integer'), list(actual.values()).count('string')))

    def test_source_declarations_drive_enum_types_and_inherited_fields(self):
        # Synthetic source JAR exercises discovery, not server/fixture replay.
        sources = {
            'ClientCapabilities': 'public class ClientCapabilities extends Parent {\n'
                '  /** See {@link OpenKind}. */\n  private List<String> formats;\n'
                '  /** Unrelated {@link OpenKind}. */\n  private Boolean enabled;\n}',
            'Parent': 'public class Parent {\n  private ClosedKind kind;\n}',
            'ClosedKind': 'public enum ClosedKind {\n One(1),\n Two(7);\n private final int value;\n}',
            'OpenKind': 'public final class OpenKind {\n public static final String FIRST = "first";\n}',
            'TextDocumentService': '', 'WorkspaceService': '',
        }
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'sources.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for name, source in sources.items():
                    archive.writestr('org/eclipse/lsp4j/' + name + '.java', source)
            with patch.object(generator, 'SOURCES_SHA256', hashlib.sha256(jar.read_bytes()).hexdigest()), patch.object(generator, 'REQUESTS', {}):
                schema = generator.generate(jar)
            self.assertEqual({'kind': ['enum', 'ClosedKind'], 'formats': ['list', ['enum', 'OpenKind']], 'enabled': 'boolean'}, schema['types']['ClientCapabilities'])
            self.assertEqual({'reader': 'integer', 'unknown': 'null', 'values': {'One': 1, 'Two': 7}}, schema['enums']['ClosedKind'])
            self.assertEqual({'reader': 'string', 'unknown': 'preserve', 'values': {'FIRST': 'first'}}, schema['enums']['OpenKind'])
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                generator.generate(jar)
