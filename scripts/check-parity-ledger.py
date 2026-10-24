#!/usr/bin/env python3
"""host 別 corpus の台帳漏れと、JSON/Markdown の不一致を検査する。外部通信は行わない。"""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CORPUS_ROOTS = ("spec-corpus", "conformance", "unlaxer-dsl/src/test/resources", "docs/fixtures")
LEDGER = "spec-corpus/parity-ledger.json"
DOCUMENT = "docs/parity-ledger.md"
CATEGORIES = {
    "contract": "意図した差（契約）",
    "unresolved": "未修正の差",
    "unsupported": "両方とも未対応",
}
# 型表現・consumer コード・計測 metadata も保守的に拾い、台帳側で理由を記す。
# 小文字の javaStyle 等を本文から拾わず、JSON のキー / TSV の列名だけを見る。
HOST_FIELD = re.compile(r"^(?:(?:java|rust|native)(?:$|[A-Z_ -])|(?:Java|Rust|Native)(?:$|[A-Z_ -]))")
ISSUE_URL = "https://github.com/opaopa6969/unlaxer-parser/issues/"


def json_fields(value):
    fields = Counter()
    if isinstance(value, dict):
        for key, child in value.items():
            if HOST_FIELD.match(key):
                fields[key] += 1
            fields.update(json_fields(child))
    elif isinstance(value, list):
        for child in value:
            fields.update(json_fields(child))
    return fields


def inventory(root):
    result = {}
    for directory in CORPUS_ROOTS:
        base = root / directory
        if not base.is_dir():
            raise ValueError(f"corpus のディレクトリがない: {directory}")
        for path in sorted(base.rglob("*")):
            relative = path.relative_to(root).as_posix()
            if relative == LEDGER or not path.is_file():
                continue
            fields = Counter()
            if path.suffix == ".json":
                try:
                    fields = json_fields(json.loads(path.read_text(encoding="utf-8")))
                except ValueError as error:
                    raise ValueError(f"{relative}: {error}") from error
            elif path.suffix == ".tsv":
                lines = path.read_text(encoding="utf-8").splitlines()
                # この既存形式は header がなく、JDK と固定 Unicode の比較列を持つ。
                if re.fullmatch(r"jdk\d+-legacy-delta\.tsv", path.name):
                    rows = [line.split("\t") for line in lines if line and not line.startswith("#")]
                    if not rows or any(len(row) != 4 for row in rows):
                        raise ValueError(f"legacy JDK corpus の4列が不正: {relative}")
                    fields["javaLegacyFold"] = len(rows)
                # 先頭コメント群の TSV header。<TAB> 表記も既存 corpus が使用する。
                for line in lines:
                    if not line.startswith("#"):
                        break
                    columns = line.lstrip("# ").replace("<TAB>", "\t").split("\t")
                    if len(columns) > 1:
                        fields.update(c.strip() for c in columns if HOST_FIELD.match(c.strip()))
            if fields:
                result[relative] = dict(sorted(fields.items()))
    return result


def render(data):
    lines = [
        "# Java / Rust の既知差台帳", "",
        "この文書は `spec-corpus/parity-ledger.json` から生成する。更新は JSON を編集し、",
        "`python3 scripts/check-parity-ledger.py --write-doc` を実行する。", "",
        f"監査日: {data['audited_on']}。基準 commit: `{data['audited_revision']}`。",
        "issue の OPEN/CLOSED は監査時に `gh issue view -R opaopa6969/unlaxer-parser` で確認したスナップショット。",
        "CI はネットワークに依存せず、この記録を検査する。状態を再確認した際は JSON も更新する。", "",
        "## 範囲と読み方", "",
    ]
    lines.extend(f"- {note}" for note in data["scope_notes"])
    for category, label in CATEGORIES.items():
        lines += ["", f"## {label}", "", "| ID / 対象 | 観測・理由 | issue（監査時）/ 根拠 |", "|---|---|---|"]
        for entry in data["entries"]:
            if entry["category"] != category:
                continue
            issues = ", ".join(
                f"[#{n}]({ISSUE_URL}{n}) {data['issues'][str(n)]['state']}"
                for n in entry["issues"]
            )
            evidence = "<br>".join(f"[{p}](../{p})" for p in entry["evidence"])
            cells = [f"`{entry['id']}`<br>{entry['title']}<br>{entry['hosts']}", entry["observation"], f"{issues}<br>{evidence}"]
            lines.append("| " + " | ".join(c.replace("|", "\\|").replace("\n", "<br>") for c in cells) + " |")
    lines += ["", "## host 別フィールドの網羅表", "",
              "値が等しい列、型・consumer コード・計測 metadata も除外せず記録する。",
              "検査は JSON の全階層と TSV の先頭コメント列を走査し、ファイル・キー・出現数の完全一致を要求する。",
              "header のない `jdk*-legacy-delta.tsv` は4列を検査し、JDK比較列を `javaLegacyFold`、行数を出現数として記録する。",
              "新しい host 名や別形式の corpus を導入する場合は検出規則とテストも更新する。", "",
              "| corpus | host 別キー（出現数） | 台帳 ID |", "|---|---|---|"]
    for item in data["corpora"]:
        fields = ", ".join(f"`{key}` ({count})" for key, count in item["fields"].items())
        lines.append(f"| [{item['path']}](../{item['path']}) | {fields} | {', '.join(item['entries'])} |")
    lines += ["", "## 検査と更新", "", "```sh",
              "python3 scripts/check-parity-ledger.py",
              "python3 scripts/tests/test_parity_ledger.py", "```", "",
              "既存 Maven CI の build job で両コマンドを実行する。#719 の opt-in 実行証跡検査とは役割を分ける。",
              "JSON の分類・issue 参照・根拠ファイル・corpus の完全一致・この Markdown の生成差分を検査する。",
              "未修正/両方未対応の項目には OPEN の追跡 issue が必須。CLOSED の実装 issue だけで残課題を完了扱いにしない。",
              "corpus の値そのものの正しさ、skip の実行時発生、全 API の意味的網羅性は既存の適合性テストと #719/#716 が担う。",
              "未知の差が存在しないという証明ではない。", ""]
    return "\n".join(lines)


def validate(root, data):
    errors = []
    if data.get("schema_version") != 1:
        errors.append("schema_version は 1 が必要")
    ids = set()
    for entry in data["entries"]:
        name = entry["id"]
        if name in ids:
            errors.append(f"台帳 ID が重複: {name}")
        ids.add(name)
        if entry["category"] not in CATEGORIES:
            errors.append(f"分類が不正: {name}")
        for field in ("id", "title", "hosts", "observation", "issues", "evidence"):
            if not entry.get(field):
                errors.append(f"{name}: {field} が空")
        states = []
        for number in entry["issues"]:
            issue = data["issues"].get(str(number))
            if type(number) is not int or number <= 0 or not issue:
                errors.append(f"{name}: issue の記録がない/不正: {number}")
            else:
                states.append(issue["state"])
                if issue["state"] not in ("OPEN", "CLOSED") or not issue.get("title"):
                    errors.append(f"{name}: issue 状態/題名が不正: {number}")
        if entry["category"] in ("unresolved", "unsupported") and "OPEN" not in states:
            errors.append(f"{name}: 残課題には OPEN の issue が必要")
        for path in entry["evidence"]:
            if Path(path).is_absolute() or ".." in Path(path).parts or not (root / path).is_file():
                errors.append(f"{name}: 根拠ファイルがない/不正: {path}")
    recorded = {}
    for item in data["corpora"]:
        path = item["path"]
        if path in recorded:
            errors.append(f"corpus が重複: {path}")
        recorded[path] = item["fields"]
        if not item["entries"] or set(item["entries"]) - ids:
            errors.append(f"{path}: 台帳 ID の参照がない/不正: {item['entries']}")
        if not item["fields"] or any(type(n) is not int or n < 1 for n in item["fields"].values()):
            errors.append(f"{path}: キーの出現数が不正")
    actual = inventory(root)
    for path in sorted(actual.keys() | recorded.keys()):
        if path not in recorded:
            errors.append(f"台帳にない host 別 corpus: {path}: {actual[path]}")
        elif path not in actual:
            errors.append(f"実在しない host 別 corpus 記録: {path}")
        elif actual[path] != recorded[path]:
            errors.append(f"host 別キー/出現数が不一致: {path}: 実際={actual[path]}, 台帳={recorded[path]}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--write-doc", action="store_true", help="検査成功後に Markdown を更新")
    args = parser.parse_args()
    try:
        data = json.loads((args.root / LEDGER).read_text(encoding="utf-8"))
        errors = validate(args.root, data)
        if errors:
            raise ValueError("\n".join(errors))
        expected = render(data)
        document = args.root / DOCUMENT
        if args.write_doc:
            document.write_text(expected, encoding="utf-8")
        elif document.read_text(encoding="utf-8") != expected:
            raise ValueError("Markdown と JSON が不一致: --write-doc で更新してください")
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"parity 台帳検査失敗: {error}", file=sys.stderr)
        return 1
    print(f"parity 台帳: {len(data['entries'])} 項目、{len(data['corpora'])} corpus を確認")
    return 0


if __name__ == "__main__":
    sys.exit(main())
