#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMMITTED_DIR="src/test/resources/golden"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

./scripts/refresh-golden-snapshots.sh --output-dir "$TMP_DIR" >/dev/null

committed_list="$(cd "$COMMITTED_DIR" && find . -type f | sort)"
generated_list="$(cd "$TMP_DIR" && find . -type f | sort)"

if [[ "$committed_list" != "$generated_list" ]]; then
  echo "[check-golden-snapshots] ERROR: file set mismatch between committed fixtures and regenerated output." >&2
  diff -u <(printf '%s\n' "$committed_list") <(printf '%s\n' "$generated_list") || true
  exit 1
fi

while IFS= read -r rel; do
  [[ -z "$rel" ]] && continue
  if ! diff -u "$COMMITTED_DIR/$rel" "$TMP_DIR/$rel" >/dev/null; then
    echo "[check-golden-snapshots] ERROR: fixture out of date: $rel" >&2
    diff -u "$COMMITTED_DIR/$rel" "$TMP_DIR/$rel" || true
    exit 1
  fi
done <<< "$committed_list"

echo "[check-golden-snapshots] OK: committed golden fixtures match regenerated output."
