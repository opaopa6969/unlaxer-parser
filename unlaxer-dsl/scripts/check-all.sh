#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT_DIR"

./scripts/check-scripts.sh
./scripts/check-golden-snapshots.sh
./scripts/spec/check-doc-sync.sh
mvn -q test
./scripts/spec/check-json-examples.sh

echo "[check-all] OK: all local checks passed."
