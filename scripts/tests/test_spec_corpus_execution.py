"""新規除外・driver 削除・実行漏れを mutation で検出する (#784)。"""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("execution", Path(__file__).resolve().parents[1] / "check-spec-corpus-execution.py")
execution = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(execution)


class SpecCorpusExecutionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="spec-execution-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.data = execution.read(execution.ROOT / execution.MANIFEST)
        paths = {execution.MANIFEST, "spec-corpus/parity-ledger.json", *execution.FRONTENDS}
        paths.update(driver["path"] for driver in self.data["drivers"].values())
        paths.update(str(path.relative_to(execution.ROOT)) for path in (execution.ROOT / execution.CORPUS).glob("*.json"))
        for path in paths:
            destination = self.root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(execution.ROOT / path, destination)
        self.receipts = []
        for row in self.data["cases"]:
            original = next(c for c in execution.read(self.root / execution.CORPUS / row["file"])["cases"] if c["id"] == row["id"])
            for driver in row["drivers"]:
                checks = len(row["samples"]) if row["scope"] == "runtime" else (1 if driver == "java-validate" else 2)
                self.receipts.append(dict(driver=driver, id=row["id"], input=original["input"], checks=checks))

    def save(self, path, value):
        (self.root / path).write_text(json.dumps(value), encoding="utf-8")

    def errors(self, receipts=None):
        self.save(execution.MANIFEST, self.data)
        return "\n".join(execution.validate(self.root, receipts))

    def test_repository_routes_are_complete(self):
        self.assertEqual([], execution.validate(execution.ROOT))
        self.assertEqual("", self.errors(self.receipts))

    def test_new_corpus_without_destination_fails_for_both_scopes(self):
        for scope in execution.SCOPES:
            with self.subTest(scope=scope):
                row = copy.deepcopy(next(r for r in self.data["cases"] if r["scope"] == scope))
                original = execution.read(self.root / execution.CORPUS / row["file"])["cases"][0]
                original["id"], original["scope"] = f"new/{scope}", scope
                self.save(execution.CORPUS + "/new.json", {"cases": [original]})
                self.assertIn("未宣言/過剰な除外 corpus", self.errors())

    def test_removed_destination_fails(self):
        self.data["cases"][0]["drivers"].pop()
        self.assertIn("実行先が不足/過剰", self.errors())

    def test_added_frontend_exclusion_fails(self):
        self.data["cases"][0]["excluded_from"].append("java-frontend")
        self.assertIn("frontend 除外が不一致", self.errors())

    def test_deleted_driver_file_fails(self):
        (self.root / self.data["drivers"]["native-validate"]["path"]).unlink()
        self.assertIn("実行先ファイルが無い", self.errors())

    def test_removed_test_annotation_fails(self):
        driver = self.data["drivers"]["native-validate"]
        path = self.root / driver["path"]
        path.write_text(path.read_text().replace("#[test]\nfn " + driver["test"], "fn " + driver["test"]))
        self.assertIn("実行先テストが無い", self.errors())

    def test_driver_no_longer_reads_one_corpus_fails(self):
        self.assertIn("未実行/過剰な corpus driver", self.errors(self.receipts[1:]))

    def test_empty_driver_success_without_receipts_fails(self):
        receipts = [r for r in self.receipts if r["driver"] != "native-runtime"]
        self.assertIn("未実行/過剰な corpus driver", self.errors(receipts))

    def test_receipt_of_copied_or_wrong_input_fails(self):
        self.receipts[0]["input"] += " "
        self.assertIn("実行記録の原文/照合数が不一致", self.errors(self.receipts))

    def test_runtime_must_execute_all_samples(self):
        next(r for r in self.receipts if r["driver"] == "native-runtime")["checks"] -= 1
        self.assertIn("実行記録の原文/照合数が不一致", self.errors(self.receipts))

    def test_duplicate_execution_cannot_replace_missing_case(self):
        self.receipts[0] = self.receipts[1]
        errors = self.errors(self.receipts)
        self.assertIn("実行記録重複", errors)
        self.assertIn("未実行/過剰な corpus driver", errors)

    def test_known_difference_requires_open_issue_and_ledger(self):
        self.data["cases"][0]["difference"]["issue"] = 999999
        self.assertIn("既知差の台帳/OPEN issue が無い", self.errors())

    def test_frontend_cannot_restore_its_private_exclusion_list(self):
        path = self.root / next(iter(execution.FRONTENDS))
        path.write_text(path.read_text().replace('delegated_ids("validate")', 'Default::default()'))
        self.assertIn("frontend が共通の実行先宣言を読んでいない", self.errors())
