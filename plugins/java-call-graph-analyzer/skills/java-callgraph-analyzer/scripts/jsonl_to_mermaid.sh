#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INPUT_FILE="${1:-.tmp/callgraph-java.jsonl}"
OUTPUT_FILE="${2:-.tmp/callgraph-java.mmd}"

mkdir -p "$(dirname "$OUTPUT_FILE")"

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/java-callgraph-analyzer.XXXXXX")"
cleanup() {
  rm -rf "$BUILD_DIR"
}
trap cleanup EXIT

javac -d "$BUILD_DIR" "$SCRIPT_DIR/JsonlToMermaid.java"
java -cp "$BUILD_DIR" JsonlToMermaid --input "$INPUT_FILE" --output "$OUTPUT_FILE"

echo "Mermaid file written to: $OUTPUT_FILE"
