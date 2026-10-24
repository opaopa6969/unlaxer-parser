#!/usr/bin/env python3
"""host 別 corpus・テスト内の除外の台帳漏れと、JSON/Markdown の不一致を検査する。外部通信は行わない。"""

import argparse
from collections import Counter
import json
import os
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


# JSON の `host` / `hosts` の値で host を選ぶ形式（行ごとの host 別期待値）も拾う。
HOST_VALUE_KEYS = {"host", "hosts"}
HOST_VALUE = re.compile(r"(?i)(?:java|rust|native)(?:[-_][a-z0-9]+)*")
# YAML/TOML は依存を増やさず、行頭のキーだけを見る。
TEXT_KEY = re.compile(r"""^\s*(?:-\s*)?["']?([A-Za-z][\w -]*?)["']?\s*[:=]""")
HEADER_CELL = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:[ -][A-Za-z0-9_]+)*")
PRUNE = {".git", "target", "node_modules"}
# テストの無条件 skip と、テスト内に書いた差分表・除外集合。環境 opt-in の Assume は #719 が扱う。
TEST_MARKERS = (
    ("disabled", ".java", re.compile(r"^[ \t]*@(?:Disabled|Ignore)\b", re.M), None),
    ("disabled", ".rs", re.compile(r"^[ \t]*#\[ignore\b", re.M), None),
    ("documentedDeviation", ".java", re.compile(r"\bnew Deviation\("), None),
    ("documentedDeviation", ".rs", re.compile(r"\bDOCUMENTED_DEVIATIONS\s*:\s*\[[^;\]]*;\s*(\d+)\s*\]"), "size"),
    ("runtimeScopeExclusion", ".java", re.compile(r"\bRUNTIME_SCOPE_IDS\s*=\s*Set\.of\(([^)]*)\)"), "strings"),
    ("runtimeScopeExclusion", ".rs", re.compile(r"\bRUNTIME_SCOPE_IDS\s*:\s*\[&str;\s*(\d+)\s*\]"), "size"),
    ("countedSkip", ".rs", re.compile(r"\bskipped_\w+\s*\+=\s*1\b"), None),
)


def json_fields(value):
    fields = Counter()
    if isinstance(value, dict):
        for key, child in value.items():
            if HOST_FIELD.match(key):
                fields[key] += 1
            if key in HOST_VALUE_KEYS:
                for item in child if isinstance(child, list) else [child]:
                    if isinstance(item, str) and HOST_VALUE.fullmatch(item):
                        fields[f"{key}={item}"] += 1
            fields.update(json_fields(child))
    elif isinstance(value, list):
        for child in value:
            fields.update(json_fields(child))
    return fields


def table_fields(path, relative, delimiter):
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    fields = Counter()
    # この既存形式は header がなく、JDK と固定 Unicode の比較列を持つ。
    if re.fullmatch(r"jdk\d+-legacy-delta\.tsv", path.name):
        rows = [line.split("\t") for line in lines if not line.startswith("#")]
        if not rows or any(len(row) != 4 for row in rows):
            raise ValueError(f"legacy JDK corpus の4列が不正: {relative}")
        fields["javaLegacyFold"] = len(rows)
        return fields
    comments = []
    for line in lines:
        if not line.startswith("#"):
            break
        comments.append(line)
    data = [line.split(delimiter) for line in lines[len(comments):] if not line.startswith("#")]
    # 先頭コメント群の header。<TAB> 表記も既存 corpus が使用する。
    headers = []
    for line in comments:
        columns = [c.strip() for c in line.lstrip("# ").replace("<TAB>", "\t").split(delimiter)]
        if len(columns) > 1:
            headers.append(columns)
    if not headers and comments and data:
        # 空白区切りの header（例: `# id scenario ...`）は、列数がデータと一致するときだけ採る。
        columns = comments[-1].lstrip("# ").split()
        if len(columns) > 1 and len(columns) == len(data[0]):
            headers.append(columns)
    if not headers and data and all(HEADER_CELL.fullmatch(c.strip()) for c in data[0]):
        # コメントでない先頭行を header とする形式（例: `host<TAB>case<TAB>...`）。
        # 数値・hex 等を含む行はデータとみなし、header のない表を誤検出しない。
        headers.append([c.strip() for c in data.pop(0)])
    for columns in headers:
        fields.update(c for c in columns if HOST_FIELD.match(c))
    for columns in headers[-1:]:
        # 行ごとに host を持つ形式は、host 値ごとの行数を記録する。
        for index, column in enumerate(columns):
            if column.lower() not in HOST_VALUE_KEYS:
                continue
            for row in data:
                value = row[index].strip() if index < len(row) else ""
                if HOST_VALUE.fullmatch(value):
                    fields[f"{column}={value}"] += 1
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
            try:
                if path.suffix == ".json":
                    fields = json_fields(json.loads(path.read_text(encoding="utf-8")))
                elif path.suffix in (".jsonl", ".ndjson"):
                    for line in path.read_text(encoding="utf-8").splitlines():
                        if line.strip():
                            fields.update(json_fields(json.loads(line)))
                elif path.suffix in (".tsv", ".csv"):
                    fields = table_fields(path, relative, "\t" if path.suffix == ".tsv" else ",")
                elif path.suffix in (".yaml", ".yml", ".toml"):
                    for line in path.read_text(encoding="utf-8").splitlines():
                        match = TEXT_KEY.match(line)
                        if match and HOST_FIELD.match(match.group(1)):
                            fields[match.group(1)] += 1
            except ValueError as error:
                raise ValueError(f"{relative}: {error}") from error
            if fields:
                result[relative] = dict(sorted(fields.items()))
    return result


def source_files(root):
    for directory, names, files in os.walk(root):
        # build 出力と、入れ子の checkout / worktree（`.git` を持つ別 repo）は走査しない。
        names[:] = sorted(n for n in names if n not in PRUNE and not os.path.exists(os.path.join(directory, n, ".git")))
        for name in sorted(files):
            if name.endswith((".java", ".rs")):
                yield Path(directory) / name


def test_markers(root):
    result = {}
    for path in source_files(root):
        text = None
        markers = Counter()
        for name, suffix, pattern, mode in TEST_MARKERS:
            if path.suffix != suffix:
                continue
            if text is None:
                text = path.read_text(encoding="utf-8", errors="replace")
            for match in pattern.finditer(text):
                if mode == "size":
                    markers[name] += int(match.group(1))
                elif mode == "strings":
                    markers[name] += len(re.findall(r'"(?:[^"\\]|\\.)*"', match.group(1)))
                else:
                    markers[name] += 1
        markers = +markers
        if markers:
            result[path.relative_to(root).as_posix()] = dict(sorted(markers.items()))
    return result


def compare(errors, label, actual, recorded):
    for path in sorted(actual.keys() | recorded.keys()):
        if path not in recorded:
            errors.append(f"台帳にない {label}: {path}: {actual[path]}")
        elif path not in actual:
            errors.append(f"実在しない {label} の記録: {path}")
        elif actual[path] != recorded[path]:
            errors.append(f"{label} のキー/出現数が不一致: {path}: 実際={actual[path]}, 台帳={recorded[path]}")


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
              "検査は JSON / JSON Lines の全階層、TSV / CSV の header（先頭コメント、空白区切りのコメント、コメントでない先頭行）、"
              "YAML / TOML の行頭キーを走査し、ファイル・キー・出現数の完全一致を要求する。",
              "`host` / `hosts` のキーや列で host を選ぶ形式は、値ごとの件数を `host=java` のように記録する。",
              "header のない `jdk*-legacy-delta.tsv` は4列を検査し、JDK比較列を `javaLegacyFold`、行数を出現数として記録する。",
              "新しい host 名や別形式の corpus を導入する場合は検出規則とテストも更新する。", "",
              "| corpus | host 別キー（出現数） | 台帳 ID |", "|---|---|---|"]
    for item in data["corpora"]:
        fields = ", ".join(f"`{key}` ({count})" for key, count in item["fields"].items())
        lines.append(f"| [{item['path']}](../{item['path']}) | {fields} | {', '.join(item['entries'])} |")
    lines += ["", "## テスト内の除外の網羅表", "",
              "無条件 skip（Java `@Disabled` / `@Ignore`、Rust `#[ignore]`）、テスト内の差分表"
              "（`DOCUMENTED_DEVIATIONS`）、spec corpus の除外集合（`RUNTIME_SCOPE_IDS`）、"
              "Rust の数えた skip（`skipped_* += 1`）を全 `.java` / `.rs` から数え、ファイルと件数の完全一致を要求する。",
              "環境 opt-in の Assume や `if/return` は #719 の実行証跡検査が担う。", "",
              "| テスト | 種類（件数） | 台帳 ID |", "|---|---|---|"]
    for item in data["test_markers"]:
        markers = ", ".join(f"`{key}` ({count})" for key, count in item["markers"].items())
        lines.append(f"| [{item['path']}](../{item['path']}) | {markers} | {', '.join(item['entries'])} |")
    lines += ["", "## 検査と更新", "", "```sh",
              "python3 scripts/check-parity-ledger.py",
              "python3 scripts/tests/test_parity_ledger.py", "```", "",
              "既存 Maven CI の build job で両コマンドを実行する。#719 の opt-in 実行証跡検査とは役割を分ける。",
              "JSON の分類・issue 参照・根拠ファイル・corpus とテスト内の除外の完全一致・この Markdown の生成差分を検査する。",
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
    for key, label, values in (("corpora", "host 別 corpus", "fields"), ("test_markers", "テスト内の除外", "markers")):
        recorded = {}
        for item in data[key]:
            path = item["path"]
            if path in recorded:
                errors.append(f"{label}が重複: {path}")
            recorded[path] = item[values]
            if not item["entries"] or set(item["entries"]) - ids:
                errors.append(f"{path}: 台帳 ID の参照がない/不正: {item['entries']}")
            if not item[values] or any(type(n) is not int or n < 1 for n in item[values].values()):
                errors.append(f"{path}: キーの出現数が不正")
        compare(errors, label, inventory(root) if key == "corpora" else test_markers(root), recorded)
    if errors:
        errors.append(f"差を確認して {LEDGER} の該当項目（分類・issue・出現数）を更新し、"
                      "`python3 scripts/check-parity-ledger.py --write-doc` で文書を再生成する。")
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
    print(f"parity 台帳: {len(data['entries'])} 項目、{len(data['corpora'])} corpus、"
          f"{len(data['test_markers'])} テストの除外を確認")
    return 0


if __name__ == "__main__":
    sys.exit(main())
