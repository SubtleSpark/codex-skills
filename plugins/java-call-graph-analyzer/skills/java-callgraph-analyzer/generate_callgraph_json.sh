#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${1:-.}"
OUTPUT_FILE="${2:-.tmp/callgraph-java.json}"
CLASSPATH_ARG="${3:-}"
INCLUDE_PREFIX_ARG="${4:-}"

mkdir -p "$(dirname "$OUTPUT_FILE")"

javac "$SCRIPT_DIR/JavaSourceCallGraphExporter.java"

CMD=(java -cp "$SCRIPT_DIR" JavaSourceCallGraphExporter --project "$PROJECT_DIR" --output "$OUTPUT_FILE")
if [[ -n "$CLASSPATH_ARG" ]]; then
  CMD+=(--classpath "$CLASSPATH_ARG")
fi
if [[ -n "$INCLUDE_PREFIX_ARG" ]]; then
  CMD+=(--include-prefix "$INCLUDE_PREFIX_ARG")
fi

"${CMD[@]}"

echo "Callgraph JSON written to: $OUTPUT_FILE"
