#!/usr/bin/env python3
"""Fail when maintained user-facing docs drift from the Maven revision."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


pom = read("pom.xml")
match = re.search(r"<revision>([^<]+)</revision>", pom)
if match is None:
    raise SystemExit("pom.xml has no <revision>")
version = match.group(1)

required = {
    "README.md": [
        f"version-{version}-blue",
        f"Latest release — {version}",
        f"<version>{version}</version>",
    ],
    "README-ja.md": [
        f"version-{version}-blue",
        f"最新リリース — {version}",
        f"<version>{version}</version>",
    ],
    "CHANGELOG.md": [f"## [{version}]"],
    "docs/architecture.md": [f"**Version**: {version}"],
    "docs/architecture-ja.md": [f"**バージョン**: {version}"],
    "docs/getting-started.md": [
        f"**Version**: {version}",
        f"<version>{version}</version>",
    ],
    "docs/getting-started-ja.md": [
        f"**バージョン**: {version}",
        f"<version>{version}</version>",
    ],
    "docs/ubnf-guide.md": [f"**Version**: {version}"],
    "docs/ubnf-guide-ja.md": [f"**バージョン**: {version}"],
}

errors = []
for path, markers in required.items():
    content = read(path)
    for marker in markers:
        if marker not in content:
            errors.append(f"{path}: missing {marker!r}")

if (ROOT / "README.ja.md").exists():
    errors.append("README.ja.md: obsolete duplicate must not be restored")

legacy_spec = read("spec/SPEC.md")
if "no longer a source of current" not in legacy_spec:
    errors.append("spec/SPEC.md: legacy status and canonical-doc redirect are missing")

if errors:
    print("Documentation version sync failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print(f"Documentation version sync OK: {version}")
