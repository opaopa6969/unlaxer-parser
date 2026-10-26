#!/usr/bin/env python3
"""Java 公開 parser 宣言と人が監査した Rust 対応表を照合する（JDK 21+、外部通信なし）。"""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DATA = "docs/java-api-rust-parity.json"
DOCUMENT = "docs/java-api-rust-parity.md"
JAVA = "unlaxer-common/src/main/java/org/unlaxer"
# parser 全階層に加え、構築・探索・tag・解析結果・context の公開境界を含める。
RELATED = ("Name", "Parsed", "Token", "TokenKind", "TokenList", "TokenPredicators",
           "TransactionElement", "CapturedValue")
CONTEXT = ("ParseContext", "TransactionalState", "MutationAwareTransactionalState")
STATUSES = {"verified": "対応済み（記載した共通契約）", "uncorroborated": "対応あり・全API共通検証なし",
            "missing": "未対応（記載した動作）", "language-specific": "言語固有（移植境界あり）"}


def source_paths(root):
    base = root / JAVA
    paths = set((base / "parser").rglob("*.java"))
    paths.update(base.glob("Parser*.java"))
    paths.update(base.glob("*Tag*.java"))
    paths.update(base / (name + ".java") for name in RELATED)
    paths.update(base / "context" / (name + ".java") for name in CONTEXT)
    paths.add(base / "reducer/TagBasedReducer.java")
    return sorted(paths)


def inventory(root, paths=None):
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    result = subprocess.run([java, str(ROOT / "scripts/JavaParserApiInventory.java"),
                             *map(str, source_paths(root) if paths is None else paths)],
                            capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise ValueError("公開 API の構文解析に失敗: " + result.stderr)
    rows = []
    for line in result.stdout.splitlines():
        identifier, kind, signature, path, number = line.split("\t")
        rows.append(dict(id=identifier, kind=kind, signature=signature,
                         path=Path(path).relative_to(root).as_posix(), line=int(number)))
    if not rows:
        raise ValueError("公開 API がゼロ件")
    return sorted(rows, key=lambda r: r["id"])


def validate(root, data, actual):
    errors = []
    if data.get("schema_version") != 1:
        errors.append("schema_version は 1 が必要")
    expected = data["apis"]
    ids = [row["id"] for row in expected]
    if len(ids) != len(set(ids)):
        errors.append("対応表の API ID が重複")
    declared = {row["id"]: row for row in actual}
    registered = {row["id"]: row for row in expected}
    for identifier in sorted(declared.keys() - registered.keys()):
        errors.append("未登録の公開 API: " + identifier)
    for identifier in sorted(registered.keys() - declared.keys()):
        errors.append("消えた公開 API: " + identifier)
    for identifier in sorted(declared.keys() & registered.keys()):
        if any(registered[identifier][key] != declared[identifier][key]
               for key in ("kind", "signature", "path")):
            errors.append("公開 API の宣言変更: " + identifier)
    used = set()
    for row in expected:
        profile = row.get("assessment")
        used.add(profile)
        if profile not in data["assessments"]:
            errors.append("判定がない: " + row["id"])
    if used != set(data["assessments"]):
        errors.append("未使用または不明な判定")
    for name, assessment in data["assessments"].items():
        status = assessment.get("status")
        group = assessment.get("followup_group")
        if group and (group not in data.get("followups", {}) or
                      data["followups"][group]["number"] not in assessment.get("issues", [])):
            errors.append("子 issue の対応がない: " + name)
        if status not in STATUSES or not assessment.get("reason") or not assessment.get("route"):
            errors.append("状態/理由/利用経路がない: " + name)
        if status != "verified" and not assessment.get("issues"):
            errors.append("後続 issue がない: " + name)
        if status == "verified" and not all(assessment.get(k) for k in ("code", "tests", "fixtures")):
            errors.append("対応済みの実装/共通検証根拠がない: " + name)
        if any(type(number) is not int or number <= 0 for number in assessment.get("issues", [])):
            errors.append("issue 番号が不正: " + name)
        for key in ("code", "tests", "fixtures"):
            for location in assessment.get(key, []):
                path, _, anchor = location.partition("#L")
                target = root / path
                if Path(path).is_absolute() or ".." in Path(path).parts or not target.is_file():
                    errors.append("根拠ファイルがない: " + location)
                elif anchor and (not anchor.isdigit() or not 1 <= int(anchor) <= len(target.read_text().splitlines())):
                    errors.append("根拠行番号が不正: " + location)
    if not re.fullmatch(r"[0-9a-f]{40}", data.get("revision", "")):
        errors.append("基準 revision が不正")
    return errors


def link(location):
    return f"[{location}](../{location})"


def render(data, actual):
    locations = {row["id"]: row for row in actual}
    counts = Counter(data["assessments"][row["assessment"]]["status"] for row in data["apis"])
    lines = ["# Java 公開 parser API と Rust の対応表", "",
             f"基準: `origin/master` の `{data['revision']}`（{data['audited_on']}）。親 [#716](https://github.com/opaopa6969/unlaxer-parser/issues/716) / [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)。", "",
             "第1段は棚卸し・対応表・子 issue 分割。runtime の変更や全API parity の完了宣言ではない。", "",
             "## 列挙範囲と更新", "", *data["scope_notes"], "",
             "```sh", "python3 scripts/check-java-api-parity.py --list", "python3 scripts/check-java-api-parity.py",
             "python3 scripts/check-java-api-parity.py --write-doc", "python3 -m pytest -q scripts/tests", "```", "",
             "JSON の apis は監査済み宣言の固定一覧。--write-doc は未登録宣言を自動承認せず失敗する。",
             "新しい公開型・static overload、削除、signature 変更は実ソースとの集合比較で失敗する。",
             "line は現行ソースから表示し、宣言の移動だけでは API 変更としない。根拠は監査時のコード・既存テストであり、今回それらを再実行したという意味ではない。", "",
             "## 状態と追跡", "", "| 状態 | API 行数 |", "|---|---:|"]
    for status, label in STATUSES.items():
        lines.append(f"| {label} | {counts[status]} |")
    lines += ["", "対応済みは記載した契約・fixture の範囲を指す。言語固有も免除ではなく、実用上同等な構築・移植境界の検証を子 issue で追跡する。",
              "memo 安全宣言は既存 [#184](https://github.com/opaopa6969/unlaxer-parser/issues/184) に集約し、重複 issue は作らない。", "",
              "## 子 issue", "", "| issue | 意味単位・分割理由 |", "|---|---|"]
    for followup in data.get("followups", {}).values():
        lines.append(f"| [#{followup['number']} {followup['title']}]({followup['url']}) | {followup['reason']} |")
    lines += ["", "## issue #716 の重点12項目", "", "| Java API | 個別判定・根拠 |", "|---|---|"]
    focus = {
        "ChildOccursWithTerminator": "terminated-repeat", "MatchedChoiceParser": "selected-choice",
        "StartOfLineParser": "start-of-line", "SuggestableParser / Suggest": "suggest",
        "TagWrapper": "tag-wrapper", "MetaFunctionParser": "meta-function",
        "ReferenceByNameParser": "reference-by-name", "NotASTNode": "not-ast-node",
        "PredicateAnyMatchForParsedParser": "parsed-predicate", "EscapeInQuotedParser": "escape-in-quoted",
        "ParenthesesParser": "parentheses", "MatchedNonOrderedParser": "selected-order",
    }
    for label, assessment in focus.items():
        status = STATUSES[data["assessments"][assessment]["status"]]
        lines.append(f"| `{label}` | [{status}](#{assessment}) |")
    lines += ["", "## 判定の根拠と利用経路", ""]
    for name, entry in data["assessments"].items():
        lines += [f"### {name}", "", f"**{STATUSES[entry['status']]}**。{entry['reason']}", "", "利用経路: " + entry["route"], ""]
        for key, label in (("code", "実装"), ("tests", "テスト"), ("fixtures", "共通 fixture")):
            lines.append(label + ": " + ("、".join(map(link, entry.get(key, []))) or "全API動作の共通根拠は未整備。"))
            lines.append("")
        if entry.get("issues"):
            lines += ["追跡: " + "、".join(f"[#{n}](https://github.com/opaopa6969/unlaxer-parser/issues/{n})" for n in entry["issues"]), ""]
    lines += ["## 全公開 API（機械列挙との一致を検査）", "", "各行は上の判定へ対応する。static は factory 以外の公開 utility も保守的に含める。", "",
              "| Java API / 宣言位置 | 種別 | 状態 | Rust 経路・根拠 |", "|---|---|---|---|"]
    for row in data["apis"]:
        current = locations.get(row["id"], row)
        assessment = row["assessment"]
        location = row["path"] + "#L" + str(current["line"])
        label = row["id"].removeprefix("org.unlaxer.").replace("|", "&#124;")
        lines.append(f"| [`{label}`](../{location}) | {row['kind']} | {STATUSES[data['assessments'][assessment]['status']]} | [{assessment}](#{assessment}) |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--write-doc", action="store_true")
    args = parser.parse_args()
    try:
        actual = inventory(args.root.resolve())
        if args.list:
            print(json.dumps(actual, ensure_ascii=False, indent=2))
            return 0
        data = json.loads((args.root / DATA).read_text())
        errors = validate(args.root, data, actual)
        if errors:
            for error in errors:
                print(error, file=sys.stderr)
            return 1
        document = render(data, actual)
        if args.write_doc:
            (args.root / DOCUMENT).write_text(document, encoding="utf-8")
        elif not errors and (args.root / DOCUMENT).read_text() != document:
            errors.append("Markdown が JSON/ソースと不一致。--write-doc で再生成する")
        for error in errors:
            print(error, file=sys.stderr)
        if errors:
            return 1
        print(f"Java API 対応表: {len(actual)} 宣言を確認")
        return 0
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
