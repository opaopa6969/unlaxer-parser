#!/usr/bin/env python3
"""Java 公開 parser 宣言と人が監査した Rust 対応表を照合する（JDK 21+、外部通信なし）。"""
import argparse
from collections import Counter
import copy
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
           "TransactionElement", "CapturedValue", "Committed", "TypedToken", "TokenEffecterWithMatcher")
# ParseContext の実装 interface・options・失敗診断・capture snapshot・reducer・listener は
# 個別ファイル名で選ばず package 全体を走査し、新しい公開型の追加も検出する。
PACKAGES = ("parser", "context", "capture", "reducer", "listener")
BLANK_LINES = {"", "{", "}", "},", ")", ");", "*/", "/**"}
EVIDENCE = ("code", "tests", "fixtures")
IDENTIFIER = re.compile(r"\w")
STATUSES = {"verified": "対応済み（記載した共通契約）", "uncorroborated": "対応あり・全API共通検証なし",
            "missing": "未対応（記載した動作）", "language-specific": "言語固有（移植境界あり）"}


def source_paths(root):
    base = root / JAVA
    paths = set()
    for package in PACKAGES:
        paths.update((base / package).rglob("*.java"))
    paths.update(base.glob("Parser*.java"))
    paths.update(base.glob("*Tag*.java"))
    paths.update(base / (name + ".java") for name in RELATED)
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


def normalized(text):
    return " ".join(text.split())


def matches(text, anchor):
    # 行頭からの一致に限り、識別子の途中で切れる一致（宣言名の延長・改名）は通さない。
    text = normalized(text)
    if not text.startswith(anchor):
        return False
    return len(text) == len(anchor) or not (IDENTIFIER.match(anchor[-1]) and IDENTIFIER.match(text[len(anchor)]))


def indentation(text):
    return len(text) - len(text.lstrip())


def resolve(lines, anchor, scope=None):
    """anchor が一意に一致する行番号を返す。scope があれば、その宣言の字下げブロック内だけを探す。"""
    start, end = 0, len(lines)
    if scope is not None:
        heads = [index for index, text in enumerate(lines) if matches(text, scope)]
        if len(heads) != 1:
            raise ValueError(("scope が見つからない" if not heads else "scope が一意でない") + ": " + scope)
        start = heads[0] + 1
        depth = indentation(lines[heads[0]])
        end = next((index for index in range(start, len(lines))
                    if lines[index].strip() and indentation(lines[index]) <= depth), len(lines))
    hits = [index + 1 for index in range(start, end) if matches(lines[index], anchor)]
    if not hits:
        raise ValueError("anchor が見つからない（宣言の改名・削除は再監査する）")
    if len(hits) > 1:
        # 先頭の一致へ黙って割り当てない。anchor を長くするか scope で宣言を絞る。
        raise ValueError("anchor が一意でない: " + ", ".join(f"L{number}" for number in hits))
    return hits[0]


def locate(root, entry):
    """行根拠 {location, anchor[, scope]} の現在の行番号を返す。"""
    path = entry["location"].partition("#L")[0]
    return resolve((root / path).read_text().splitlines(), entry["anchor"], entry.get("scope"))


def well_formed(entry):
    anchor, scope = entry.get("anchor"), entry.get("scope")
    return (not set(entry) - {"location", "anchor", "scope"} and type(entry.get("location")) is str
            and entry["location"].partition("#L")[2].isdigit()
            and type(anchor) is str and normalized(anchor) == anchor and anchor not in BLANK_LINES
            and (scope is None or type(scope) is str and normalized(scope) == scope and scope not in BLANK_LINES))


def remap(root, data):
    """anchor から行番号を引き直す。確認できない根拠が一つでもあれば、どの行番号も変えない。"""
    data = copy.deepcopy(data)
    changes, errors = [], []
    for assessment in data["assessments"].values():
        for key in EVIDENCE:
            for entry in assessment.get(key, []):
                if type(entry) is not dict or not well_formed(entry):
                    continue
                path, _, line = entry["location"].partition("#L")
                try:
                    found = locate(root, entry)
                except (ValueError, OSError) as error:
                    errors.append(f"根拠 anchor を確認できない: {entry['location']}（{error}）")
                    continue
                if found != int(line):
                    changes.append(f"{entry['location']} → L{found}")
                    entry["location"] = f"{path}#L{found}"
    return data, changes, errors


def dump(data):
    """apis の各行と行根拠は1行に保ち、差分を宣言・根拠の単位で読めるようにする。"""
    inline = []

    def hold(value):
        inline.append(json.dumps(value, ensure_ascii=False))
        return f"\0{len(inline) - 1}\0"

    data = copy.deepcopy(data)
    data["apis"] = [hold(row) for row in data["apis"]]
    for assessment in data["assessments"].values():
        for key in EVIDENCE:
            if key in assessment:
                assessment[key] = [hold(entry) if type(entry) is dict else entry for entry in assessment[key]]
    text = json.dumps(data, ensure_ascii=False, indent=2)
    return re.sub(r'"\\u0000(\d+)\\u0000"', lambda match: inline[int(match[1])], text) + "\n"


def validate(root, data, actual):
    errors = []
    if data.get("schema_version") != 2:
        errors.append("schema_version は 2 が必要")
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
        for key in EVIDENCE:
            for entry in assessment.get(key, []):
                location = entry.get("location", "") if type(entry) is dict else str(entry)
                path = location.partition("#")[0]
                target = root / path
                if Path(path).is_absolute() or ".." in Path(path).parts or not target.is_file():
                    errors.append("根拠ファイルがない: " + location)
                elif type(entry) is not dict:
                    if "#" in location:
                        # 行番号だけの根拠は、参照先の行挿入・削除で別の宣言へ黙ってずれる（#859）。
                        errors.append("行の根拠に anchor がない: " + location)
                elif any(type(entry.get(key)) is str and entry[key] in BLANK_LINES for key in ("anchor", "scope")):
                    # 空行・閉じ括弧はどの宣言にも一致し、宣言を確かめない。
                    errors.append("根拠 anchor が空行または括弧だけ: " + location)
                elif not well_formed(entry):
                    errors.append("根拠 anchor の形式が不正: " + location)
                else:
                    try:
                        found = locate(root, entry)
                    except ValueError as error:
                        errors.append(f"根拠 anchor を確認できない: {location}（{error}）")
                        continue
                    if found != int(location.partition("#L")[2]):
                        errors.append(f"根拠行が anchor とずれた: {location} → L{found}（--remap で付け替える）")
    if not re.fullmatch(r"[0-9a-f]{40}", data.get("revision", "")):
        errors.append("基準 revision が不正")
    return errors


def link(entry):
    location = entry["location"] if type(entry) is dict else entry
    return f"[{location}](../{location})"


def render(data, actual):
    locations = {row["id"]: row for row in actual}
    counts = Counter(data["assessments"][row["assessment"]]["status"] for row in data["apis"])
    lines = ["# Java 公開 parser API と Rust の対応表", "",
             f"基準: `origin/master` の `{data['revision']}`（{data['audited_on']}）。親 [#716](https://github.com/opaopa6969/unlaxer-parser/issues/716) / [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)。", "",
             "第1段は棚卸し・対応表・子 issue 分割。runtime の変更や全API parity の完了宣言ではない。", "",
             "## 列挙範囲と更新", "", *data["scope_notes"], "",
             "```sh", "python3 scripts/check-java-api-parity.py --list", "python3 scripts/check-java-api-parity.py",
             "python3 scripts/check-java-api-parity.py --write-doc", "python3 scripts/check-java-api-parity.py --remap --write-doc",
             "python3 -m pytest -q scripts/tests", "```", "",
             "JSON の apis は監査済み宣言の固定一覧。--write-doc は未登録宣言を自動承認せず失敗する。",
             "新しい公開型・static overload、削除、signature 変更は実ソースとの集合比較で失敗する。",
             "line は現行ソースから表示し、宣言の移動だけでは API 変更としない。根拠は監査時のコード・既存テストであり、今回それらを再実行したという意味ではない。",
             "行を指す根拠は location と anchor（その行の先頭の宣言文字列。識別子の途中では切らない）を持ち、anchor がファイル内、scope があればその宣言の字下げブロック内で一意に一致する行を指していなければ失敗する。",
             "参照先の行の挿入・削除でずれたら --remap が anchor から行番号だけを付け替える。anchor が見つからない・複数行に一致する根拠が一つでもあれば何も書き換えず失敗し、宣言の改名・削除は人が再監査する。", "",
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
    parser.add_argument("--remap", action="store_true",
                        help="anchor が一意に指す行へ根拠の行番号を付け替えて JSON を書き直す（判定・anchor は変えない）")
    args = parser.parse_args()
    try:
        actual = inventory(args.root.resolve())
        if args.list:
            print(json.dumps(actual, ensure_ascii=False, indent=2))
            return 0
        data = json.loads((args.root / DATA).read_text())
        if args.remap:
            data, changes, errors = remap(args.root, data)
            if errors:
                for error in errors:
                    print(error, file=sys.stderr)
                return 1
            for change in changes:
                print("根拠行を付け替え: " + change, file=sys.stderr)
            if changes:
                (args.root / DATA).write_text(dump(data), encoding="utf-8")
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
