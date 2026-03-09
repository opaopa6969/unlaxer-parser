#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SPEC_FILE="$ROOT_DIR/SPEC.md"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
SKIP_BUILD=false

if [[ "${1:-}" == "--skip-build" ]]; then
  SKIP_BUILD=true
fi

VALID_GRAMMAR="$WORK_DIR/valid.ubnf"
INVALID_GRAMMAR="$WORK_DIR/invalid.ubnf"
OUT_DIR="$WORK_DIR/out"
RUNTIME_CP_FILE="$ROOT_DIR/target/runtime.classpath"

cat > "$VALID_GRAMMAR" <<'GRAMMAR'
grammar Valid {
  @package: org.example.valid
  @root
  @mapping(RootNode, params=[value])
  Valid ::= 'ok' @value ;
}
GRAMMAR

cat > "$INVALID_GRAMMAR" <<'GRAMMAR'
grammar Invalid {
  @package: org.example.invalid
  @root
  @mapping(RootNode, params=[value, missing])
  Invalid ::= 'x' @value ;
}
GRAMMAR

if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "[spec/refresh-json-examples] Skipping build; using existing target/classes."
else
  echo "[spec/refresh-json-examples] Compiling project..."
  (
    cd "$ROOT_DIR"
    mvn -q -DskipTests compile
  )
fi
if [[ ! -f "$RUNTIME_CP_FILE" ]]; then
  echo "[spec/refresh-json-examples] Building runtime classpath..."
  (
    cd "$ROOT_DIR"
    mvn -q -DincludeScope=runtime dependency:build-classpath -Dmdep.outputFile="$RUNTIME_CP_FILE"
  )
else
  echo "[spec/refresh-json-examples] Reusing runtime classpath: $RUNTIME_CP_FILE"
fi

MAIN_CP="$ROOT_DIR/target/classes"
if [[ ! -d "$MAIN_CP" ]]; then
  echo "[spec/refresh-json-examples] ERROR: $MAIN_CP not found. Run mvn compile first." >&2
  echo "[spec/refresh-json-examples] Fix: run 'mvn -q -DskipTests compile'." >&2
  exit 1
fi
echo "[spec/refresh-json-examples] Using classes: $MAIN_CP"
RUNTIME_CP="$(cat "$RUNTIME_CP_FILE")"
JAVA_CP="$MAIN_CP:$RUNTIME_CP"

java -cp "$JAVA_CP" org.unlaxer.dsl.CodegenMain \
  --grammar "$VALID_GRAMMAR" \
  --validate-only \
  --report-format json \
  --report-file "$WORK_DIR/validate-success.json" \
  >/dev/null

set +e
java -cp "$JAVA_CP" org.unlaxer.dsl.CodegenMain \
  --grammar "$INVALID_GRAMMAR" \
  --validate-only \
  --report-format json \
  --report-file "$WORK_DIR/validate-failure.json" \
  >/dev/null 2>/dev/null
STATUS=$?
set -e
if [[ $STATUS -eq 0 ]]; then
  echo "[spec/refresh-json-examples] ERROR: expected validate failure command to fail." >&2
  exit 1
fi

java -cp "$JAVA_CP" org.unlaxer.dsl.CodegenMain \
  --grammar "$VALID_GRAMMAR" \
  --output "$OUT_DIR" \
  --generators AST \
  --report-format json \
  --report-file "$WORK_DIR/generate-success.json" \
  >/dev/null

sed "s#${OUT_DIR//\//\\/}#/path/to/out#g" "$WORK_DIR/generate-success.json" > "$WORK_DIR/generate-success.norm.json"
sed -E 's/"generatedAt":"[^"]+"/"generatedAt":"<generatedAt>"/g; s/"toolVersion":"[^"]+"/"toolVersion":"<toolVersion>"/g; s/"argsHash":"[^"]+"/"argsHash":"<argsHash>"/g' \
  "$WORK_DIR/validate-success.json" > "$WORK_DIR/validate-success.norm.json"
sed -E 's/"generatedAt":"[^"]+"/"generatedAt":"<generatedAt>"/g; s/"toolVersion":"[^"]+"/"toolVersion":"<toolVersion>"/g; s/"argsHash":"[^"]+"/"argsHash":"<argsHash>"/g' \
  "$WORK_DIR/validate-failure.json" > "$WORK_DIR/validate-failure.norm.json"
sed -E 's/"generatedAt":"[^"]+"/"generatedAt":"<generatedAt>"/g; s/"toolVersion":"[^"]+"/"toolVersion":"<toolVersion>"/g; s/"argsHash":"[^"]+"/"argsHash":"<argsHash>"/g' \
  "$WORK_DIR/generate-success.norm.json" > "$WORK_DIR/generate-success.final.json"

VALIDATE_SUCCESS_JSON="$(cat "$WORK_DIR/validate-success.norm.json")"
VALIDATE_FAILURE_JSON="$(cat "$WORK_DIR/validate-failure.norm.json")"
GENERATE_SUCCESS_JSON="$(cat "$WORK_DIR/generate-success.final.json")"

SECTION_CONTENT=$(cat <<SECTION
Validate success:

\`\`\`json
$VALIDATE_SUCCESS_JSON
\`\`\`

Validate failure:

\`\`\`json
$VALIDATE_FAILURE_JSON
\`\`\`

Generate success:

\`\`\`json
$GENERATE_SUCCESS_JSON
\`\`\`
SECTION
)

START_MARKER='<!-- JSON_REPORT_EXAMPLES_START -->'
END_MARKER='<!-- JSON_REPORT_EXAMPLES_END -->'

if ! grep -q "^${START_MARKER}$" "$SPEC_FILE" || ! grep -q "^${END_MARKER}$" "$SPEC_FILE"; then
  echo "[spec/refresh-json-examples] ERROR: marker block not found in SPEC.md" >&2
  echo "[spec/refresh-json-examples] Fix: ensure SPEC.md includes JSON_REPORT_EXAMPLES_START/END markers." >&2
  exit 1
fi

TMP_FILE="$WORK_DIR/SPEC.md.tmp"
awk -v start="$START_MARKER" -v end="$END_MARKER" -v content="$SECTION_CONTENT" '
$0 == start {
  print;
  print content;
  in_block = 1;
  next;
}
$0 == end {
  in_block = 0;
  print;
  next;
}
in_block {
  next;
}
{
  print;
}
' "$SPEC_FILE" > "$TMP_FILE"

mv "$TMP_FILE" "$SPEC_FILE"

echo "[spec/refresh-json-examples] Updated SPEC.md JSON examples."
