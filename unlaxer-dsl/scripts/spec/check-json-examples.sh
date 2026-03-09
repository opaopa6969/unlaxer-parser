#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SPEC_FILE="$ROOT_DIR/SPEC.md"
BEFORE_HASH="$(sha256sum "$SPEC_FILE" | awk '{print $1}')"

if [[ -d "$ROOT_DIR/target/classes" ]]; then
  "$ROOT_DIR/scripts/spec/refresh-json-examples.sh" --skip-build
else
  "$ROOT_DIR/scripts/spec/refresh-json-examples.sh"
fi

AFTER_HASH="$(sha256sum "$SPEC_FILE" | awk '{print $1}')"
if [[ "$BEFORE_HASH" == "$AFTER_HASH" ]]; then
  echo "[spec/check-json-examples] OK: SPEC.md JSON examples are up to date."
  exit 0
fi

echo "[spec/check-json-examples] OUTDATED: SPEC.md JSON examples differ from generated output." >&2
echo "[spec/check-json-examples] Fix: run './scripts/spec/refresh-json-examples.sh' and commit changes." >&2
git -C "$ROOT_DIR" --no-pager diff -- "$SPEC_FILE" >&2
exit 1
