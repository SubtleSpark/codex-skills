#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INPUT_FILE="${1:-.tmp/callgraph-java.json}"
OUTPUT_FILE="${2:-.tmp/callgraph-java.mmd}"

mkdir -p "$(dirname "$OUTPUT_FILE")"

javac "$SCRIPT_DIR/JsonToMermaid.java"
java -cp "$SCRIPT_DIR" JsonToMermaid --input "$INPUT_FILE" --output "$OUTPUT_FILE"

echo "Mermaid file written to: $OUTPUT_FILE"
