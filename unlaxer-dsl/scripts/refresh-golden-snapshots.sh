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

CP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/unlaxer-dsl-test-cp.XXXXXX")
trap 'rm -rf "$CP_DIR"' EXIT
CP_FILE="$CP_DIR/classpath.txt"

mvn -q -DskipTests test-compile
mvn -q -DincludeScope=test -Dmdep.outputFile="$CP_FILE" dependency:build-classpath >/dev/null

CP="target/classes:target/test-classes:$(cat "$CP_FILE")"
java_launcher="$ROOT_DIR/../scripts/ci/java-tmpdir/bin/java"

"$java_launcher" --enable-preview -cp "$CP" org.unlaxer.dsl.codegen.SnapshotFixtureWriter --output-dir "$OUT_DIR"
"$java_launcher" --enable-preview -cp "$CP" org.unlaxer.dsl.CliFixtureWriter --output-dir "$OUT_DIR"

echo "Golden snapshots refreshed under $OUT_DIR"
