#!/usr/bin/env python3
"""仕様 corpus の委譲先と実行記録を検査する。宣言だけでは実行の証拠にならない。"""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "spec-corpus/execution/manifest.json"
CORPUS = "unlaxer-dsl/src/test/resources/spec-corpus/ubnf"
HOSTS = ("java", "java-host", "native")
SCOPES = ("validate", "runtime")
FRONTENDS = {
    "rust/unlaxer-ubnf/tests/spec_corpus.rs": ['delegated_ids("validate")', 'delegated_ids("runtime")'],
    "unlaxer-dsl/src/test/java/org/unlaxer/dsl/bootstrap/UbnfSpecCorpusTest.java": ["SpecCorpusExecutions.runtimeIds()"],
}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def validate(root, receipts=None):
    data = read(root / MANIFEST)
    errors = []
    if data.get("schema_version") != 1:
        errors.append("schema_version は 1 が必要")
    corpus = {}
    for path in sorted((root / CORPUS).glob("*.json")):
        for row in read(path)["cases"]:
            if row["id"] in corpus:
                errors.append(f"corpus ID 重複: {row['id']}")
            corpus[row["id"]] = (path.name, row)
    actual = {key for key, (_, row) in corpus.items() if row["scope"] in SCOPES}
    rows = {row["id"]: row for row in data["cases"]}
    if len(rows) != len(data["cases"]):
        errors.append("実行先 ID 重複")
    if actual != rows.keys():
        errors.append(f"未宣言/過剰な除外 corpus: {sorted(actual ^ rows.keys())}")
    expected_drivers = {f"{host}-{scope}" for host in HOSTS for scope in SCOPES}
    if data["drivers"].keys() != expected_drivers:
        errors.append("driver 集合が不足/過剰")
    for key, driver in data["drivers"].items():
        path = Path(driver["path"])
        if path.is_absolute() or ".." in path.parts or not (root / path).is_file():
            errors.append(f"実行先ファイルが無い/不正: {key}")
            continue
        text = (root / path).read_text(encoding="utf-8")
        method = re.escape(driver["test"])
        pattern = (r"#\[test\]\s*fn\s+" + method + r"\s*\(" if path.suffix == ".rs" else
                   r"@Test\s+public\s+void\s+" + method + r"\s*\(")
        if not re.search(pattern, text):
            errors.append(f"実行先テストが無い: {key} {driver['test']}")
        if key != f"{driver['host']}-{driver['scope']}":
            errors.append(f"driver host/scope が不一致: {key}")
    ledger = read(root / "spec-corpus/parity-ledger.json")
    differences = {entry["id"]: entry for entry in ledger["entries"]}
    expected_receipts = {}
    for key, row in rows.items():
        if key not in corpus:
            continue
        filename, original = corpus[key]
        scope = original["scope"]
        if row["file"] != filename or row["scope"] != scope:
            errors.append(f"fixture の参照が不一致: {key}")
        excluded = ["rust-frontend"] if scope == "validate" else ["java-frontend", "rust-frontend"]
        if row["excluded_from"] != excluded:
            errors.append(f"frontend 除外が不一致: {key}")
        if row["drivers"] != [f"{host}-{scope}" for host in HOSTS]:
            errors.append(f"実行先が不足/過剰: {key}")
        if not row.get("oracle"):
            errors.append(f"独立 oracle が無い: {key}")
        if scope == "validate" and not original["expectation"].get("code", "").startswith("E-"):
            errors.append(f"独立診断コードが無い: {key}")
        if scope == "runtime" and (not row.get("samples") or not row.get("entry")):
            errors.append(f"runtime 入力/期待値/entry が無い: {key}")
        if difference := row.get("difference"):
            entry = differences.get(difference["ledger"], {})
            if (entry.get("category") != "unresolved" or difference["issue"] not in entry.get("issues", [])
                    or ledger["issues"].get(str(difference["issue"]), {}).get("state") != "OPEN"
                    or MANIFEST not in entry.get("evidence", [])):
                errors.append(f"既知差の台帳/OPEN issue が無い: {key}")
            for host in ("native", "java-host"):
                observation = difference.get(host)
                if not observation or observation.startswith(original["expectation"].get("code", "")):
                    errors.append(f"既知差の実測が無い/解消済み: {key} {host}")
        for driver in row["drivers"]:
            checks = len(row.get("samples", [])) if scope == "runtime" else (1 if driver == "java-validate" else 2)
            expected_receipts[(driver, key)] = (original["input"], checks)
    for path, calls in FRONTENDS.items():
        source = (root / path).read_text(encoding="utf-8")
        if any(call not in source for call in calls):
            errors.append(f"frontend が共通の実行先宣言を読んでいない: {path}")
    if receipts is not None:
        seen = set()
        for row in receipts:
            key = (row["driver"], row["id"])
            if key in seen:
                errors.append(f"実行記録重複: {key}")
            seen.add(key)
            if key not in expected_receipts or (row["input"], row["checks"]) != expected_receipts[key]:
                errors.append(f"実行記録の原文/照合数が不一致: {key}")
        if seen != expected_receipts.keys():
            errors.append(f"未実行/過剰な corpus driver: {sorted(seen ^ expected_receipts.keys())}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--receipts", type=Path, nargs="+", help="Java conformance が出力する validate/runtime の JSON 記録")
    args = parser.parse_args()
    try:
        receipts = None if args.receipts is None else [row for path in args.receipts for row in read(path)]
        errors = validate(args.root, receipts)
        if errors:
            raise ValueError("\n".join(errors))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"spec corpus 実行検査失敗: {error}", file=sys.stderr)
        return 1
    print("spec corpus の全委譲先を確認" + (f"、実行記録 {len(receipts)} 件を照合" if receipts is not None else "（実行記録は未検査）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
