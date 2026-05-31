#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${1:-.}"
OUTPUT_FILE="${2:-.tmp/callgraph-java.jsonl}"
CLASSPATH_ARG="${3:-}"
INCLUDE_PREFIX_ARG="${4:-}"

mkdir -p "$(dirname "$OUTPUT_FILE")"

find_tools_jar() {
  if [[ -n "${JAVA_HOME:-}" && -f "$JAVA_HOME/lib/tools.jar" ]]; then
    printf '%s\n' "$JAVA_HOME/lib/tools.jar"
    return
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local java_home
    java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$java_home" && -f "$java_home/lib/tools.jar" ]]; then
      printf '%s\n' "$java_home/lib/tools.jar"
      return
    fi
  fi

  return 0
}

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/java-callgraph-analyzer.XXXXXX")"
cleanup() {
  rm -rf "$BUILD_DIR"
}
trap cleanup EXIT

JAVA_CP="$BUILD_DIR"
JAVAC_ARGS=(-d "$BUILD_DIR")
TOOLS_JAR="$(find_tools_jar)"
if [[ -n "$TOOLS_JAR" ]]; then
  JAVA_CP="$JAVA_CP:$TOOLS_JAR"
  JAVAC_ARGS+=(-cp "$TOOLS_JAR")
fi

javac "${JAVAC_ARGS[@]}" "$SCRIPT_DIR/JavaSourceCallGraphExporter.java"

CMD=(java -cp "$JAVA_CP" JavaSourceCallGraphExporter --project "$PROJECT_DIR" --output "$OUTPUT_FILE")
if [[ -n "$CLASSPATH_ARG" ]]; then
  CMD+=(--classpath "$CLASSPATH_ARG")
fi
if [[ -n "$INCLUDE_PREFIX_ARG" ]]; then
  CMD+=(--include-prefix "$INCLUDE_PREFIX_ARG")
fi

"${CMD[@]}"

echo "Callgraph JSONL written to: $OUTPUT_FILE"
