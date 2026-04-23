#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DEPENDENCIES_FILE="${1:-.tmp/class-dependencies-java.jsonl}"
LAYERS_FILE="${2:-.tmp/class-layers-java.jsonl}"
OUTPUT_FILE="${3:-.tmp/package-dependencies-java.mmd}"

cleanup() {
  find "$SCRIPT_DIR" -name 'PackageDependenciesToMermaid*.class' -type f -delete
}
trap cleanup EXIT

javac "$SCRIPT_DIR/PackageDependenciesToMermaid.java"
java -cp "$SCRIPT_DIR" PackageDependenciesToMermaid \
  --dependencies "$DEPENDENCIES_FILE" \
  --layers "$LAYERS_FILE" \
  --output "$OUTPUT_FILE"

echo "Package dependency Mermaid written to: $OUTPUT_FILE"
