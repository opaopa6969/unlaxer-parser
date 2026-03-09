#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT_DIR="src/test/resources/golden"
if [[ $# -eq 2 && "$1" == "--output-dir" ]]; then
  OUT_DIR="$2"
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--output-dir <path>]" >&2
  exit 2
fi

CP_FILE="/tmp/unlaxer-dsl-test-cp.txt"

mvn -q -DskipTests test-compile
mvn -q -DincludeScope=test -Dmdep.outputFile="$CP_FILE" dependency:build-classpath >/dev/null

CP="target/classes:target/test-classes:$(cat "$CP_FILE")"

java --enable-preview -cp "$CP" org.unlaxer.dsl.codegen.SnapshotFixtureWriter --output-dir "$OUT_DIR"
java --enable-preview -cp "$CP" org.unlaxer.dsl.CliFixtureWriter --output-dir "$OUT_DIR"

echo "Golden snapshots refreshed under $OUT_DIR"
