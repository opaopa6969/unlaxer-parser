#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

README_EN="$ROOT_DIR/README.md"
README_JA="$ROOT_DIR/README.ja.md"
SPEC_DOC="$ROOT_DIR/SPEC.md"

EXPECTED_FLAGS=(
  '--grammar'
  '--output'
  '--generators'
  '--validate-parser-ir'
  '--export-parser-ir'
  '--validate-only'
  '--dry-run'
  '--clean-output'
  '--overwrite'
  '--fail-on'
  '--strict'
  '--help'
  '--version'
  '--report-format'
  '--report-file'
  '--output-manifest'
  '--manifest-format'
  '--report-version'
  '--report-schema-check'
  '--warnings-as-json'
)

extract_table_flags() {
  local doc="$1"
  awk '
    BEGIN { in_table = 0 }
    /^\| (Option|オプション) \|/ { in_table = 1; next }
    in_table && /^\|/ { print; next }
    in_table && !/^\|/ { exit }
  ' "$doc" | grep -oE -- '--[a-z][a-z-]*' | sort -u
}

expected_sorted="$(printf '%s\n' "${EXPECTED_FLAGS[@]}" | sort -u)"

for doc in "$README_EN" "$README_JA" "$SPEC_DOC"; do
  if [[ ! -f "$doc" ]]; then
    echo "[spec/check-doc-sync] ERROR: missing doc file: $doc" >&2
    echo "[spec/check-doc-sync] Fix: restore the missing documentation file." >&2
    exit 1
  fi
done

for doc in "$README_EN" "$README_JA"; do
  actual_sorted="$(extract_table_flags "$doc" || true)"
  if [[ -z "$actual_sorted" ]]; then
    echo "[spec/check-doc-sync] ERROR: failed to parse CLI options table in $(basename "$doc")." >&2
    echo "[spec/check-doc-sync] Fix: ensure markdown options table exists with '--*' option entries." >&2
    exit 1
  fi

  if [[ "$actual_sorted" != "$expected_sorted" ]]; then
    echo "[spec/check-doc-sync] ERROR: CLI options table mismatch in $(basename "$doc")." >&2
    echo "[spec/check-doc-sync] expected flags:" >&2
    printf '  %s\n' "$expected_sorted" >&2
    echo "[spec/check-doc-sync] actual flags:" >&2
    printf '  %s\n' "$actual_sorted" >&2
    exit 1
  fi

done

for opt in "${EXPECTED_FLAGS[@]}"; do
  if ! grep -Fq -- "$opt" "$SPEC_DOC"; then
    echo "[spec/check-doc-sync] ERROR: option '$opt' is missing in $(basename "$SPEC_DOC")" >&2
    echo "[spec/check-doc-sync] Fix: add '$opt' to the CLI behavior section in SPEC.md." >&2
    exit 1
  fi
done

echo "[spec/check-doc-sync] OK: CLI option docs are synchronized across README.md, README.ja.md, and SPEC.md."
