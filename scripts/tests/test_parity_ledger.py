#!/usr/bin/env python3
"""台帳の欠落・古い分類・corpus追加が成功扱いされないことを検査する。"""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check-parity-ledger.py"
SPEC = importlib.util.spec_from_file_location("parity_ledger", SCRIPT)
ledger = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ledger)


class ParityLedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="parity-ledger-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for directory in ledger.CORPUS_ROOTS:
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        (self.root / "docs/contract.md").write_text("契約\n", encoding="utf-8")
        self.path = "spec-corpus/example/corpus.json"
        (self.root / self.path).parent.mkdir()
        self.write_corpus([{"cases": [{"javaCst": [], "cst": ["Root"]}]}])
        self.data = dict(
            schema_version=1, audited_on="2026-10-24", audited_revision="0" * 40,
            scope_notes=["テスト用"], issues={"767": {"state": "OPEN", "title": "CST"}},
            entries=[dict(id="cst", category="unresolved", title="CST", hosts="Java / Rust",
                          observation="root の有無", issues=[767], evidence=["docs/contract.md"])],
            corpora=[dict(path=self.path, fields={"javaCst": 1}, entries=["cst"])],
        )

    def write_corpus(self, value):
        (self.root / self.path).write_text(json.dumps(value), encoding="utf-8")

    def errors(self):
        return "\n".join(ledger.validate(self.root, self.data))

    def run_check(self, *args):
        (self.root / ledger.LEDGER).write_text(json.dumps(self.data), encoding="utf-8")
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root), *args],
                              capture_output=True, text=True, timeout=30)

    def test_real_repository_is_covered(self):
        data = json.loads((ledger.ROOT / ledger.LEDGER).read_text(encoding="utf-8"))
        self.assertEqual([], ledger.validate(ledger.ROOT, data))
        self.assertEqual((ledger.ROOT / ledger.DOCUMENT).read_text(encoding="utf-8"), ledger.render(data))

    def test_delete_one_corpus_registration_fails(self):
        self.data["corpora"].clear()
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("台帳にない host 別 corpus", result.stderr)

    def test_delete_referenced_entry_fails(self):
        self.data["entries"].clear()
        self.assertIn("台帳 ID の参照がない", self.errors())

    def test_new_nested_host_key_and_occurrence_require_review(self):
        self.write_corpus([{"cases": [{"javaCst": []}, {"javaCst": [], "nativeDiagnostic": 1}]}])
        self.assertIn("host 別キー/出現数が不一致", self.errors())

    def test_removed_host_field_is_stale(self):
        self.write_corpus([{"cst": []}])
        self.assertIn("実在しない host 別 corpus", self.errors())

    def test_new_tsv_host_columns_are_detected(self):
        path = self.root / "conformance/extra.tsv"
        path.write_text("# fixture\n# id<TAB>Java matched<TAB>Rust matched\nx\t1\t0\n", encoding="utf-8")
        self.assertEqual({"Java matched": 1, "Rust matched": 1},
                         ledger.inventory(self.root)["conformance/extra.tsv"])
        self.assertIn("台帳にない host 別 corpus", self.errors())

    def test_host_words_in_values_are_not_fields(self):
        self.write_corpus([{"input": "javaCst", "mode": "javaStyle", "cases": [{"javaCst": []}]}])
        self.assertEqual("", self.errors())

    def test_headerless_jdk_comparison_is_detected(self):
        path = self.root / "spec-corpus/jdk21-legacy-delta.tsv"
        path.write_text("001C89\t001C89\t001C8A\tCYRILLIC CAPITAL LETTER TJE\n", encoding="utf-8")
        self.assertEqual({"javaLegacyFold": 1},
                         ledger.inventory(self.root)["spec-corpus/jdk21-legacy-delta.tsv"])
        self.assertIn("台帳にない host 別 corpus", self.errors())

    def test_closed_issue_cannot_hide_remaining_work(self):
        self.data["issues"]["767"]["state"] = "CLOSED"
        for category in ("unresolved", "unsupported"):
            self.data["entries"][0]["category"] = category
            self.assertIn("OPEN の issue が必要", self.errors())
        self.data["entries"][0]["category"] = "contract"
        self.assertEqual("", self.errors())

    def test_unknown_issue_duplicate_ids_and_missing_evidence_fail(self):
        self.data["entries"].append(dict(self.data["entries"][0]))
        self.data["entries"][0]["issues"] = [999]
        self.data["entries"][0]["evidence"] = ["docs/missing.md"]
        errors = self.errors()
        for message in ("issue の記録がない", "台帳 ID が重複", "根拠ファイルがない"):
            self.assertIn(message, errors)

    def test_invalid_json_corpus_is_not_silently_ignored(self):
        (self.root / self.path).write_text('{"javaCst":', encoding="utf-8")
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("parity 台帳検査失敗", result.stderr)

    def test_document_drift_and_regeneration(self):
        result = self.run_check("--write-doc")
        self.assertEqual(0, result.returncode, result.stderr)
        (self.root / ledger.DOCUMENT).write_text("古い文書\n", encoding="utf-8")
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Markdown と JSON が不一致", result.stderr)
        result = self.run_check("--write-doc")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, self.run_check().returncode)


if __name__ == "__main__":
    unittest.main()
