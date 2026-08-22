#!/usr/bin/env bash
set -euo pipefail

# unlaxer-parser MCP server launcher
# Resolves the classpath for Java CLI tools, then starts the Node.js MCP server.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

export JAVA_HOME="${JAVA_HOME:-/home/opa/.sdkman/candidates/java/current}"
export PATH="$JAVA_HOME/bin:$PATH"

# Build classpath if not present (deps only; classes dirs are added by server.mjs)
CP_FILE="$REPO_DIR/mcp/classpath.txt"
if [ ! -f "$CP_FILE" ]; then
  MVN="${MVN:-/home/opa/.sdkman/candidates/maven/3.9.9/bin/mvn}"
  if [ -x "$MVN" ]; then
    "$MVN" -q -f "$REPO_DIR/pom.xml" -pl unlaxer-dsl dependency:build-classpath \
      -Dmdep.outputFile="$CP_FILE" -DincludeScope=runtime 2>/dev/null || true
  fi
fi

# Ensure unlaxer-dsl is compiled
if [ ! -d "$REPO_DIR/unlaxer-dsl/target/classes/org/unlaxer/dsl" ]; then
  MVN="${MVN:-/home/opa/.sdkman/candidates/maven/3.9.9/bin/mvn}"
  if [ -x "$MVN" ]; then
    "$MVN" -q -f "$REPO_DIR/pom.xml" -pl unlaxer-dsl -am compile -DskipTests 2>/dev/null || true
  fi
fi

export PORT="${PORT:-9228}"
exec node "$REPO_DIR/mcp/server.mjs"
