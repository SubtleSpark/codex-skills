#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="${1:-.}"
OUTPUT_FILE="${2:-.tmp/class-layers-java.jsonl}"
LAYER_CONFIG_ARG="${3:-$SKILL_DIR/references/default-ddd-layers.json}"
CLASSPATH_ARG="${4:-}"
INCLUDE_PREFIX_ARG="${5:-}"

if [[ -z "$LAYER_CONFIG_ARG" ]]; then
  LAYER_CONFIG_ARG="$SKILL_DIR/references/default-ddd-layers.json"
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"

javac "$SCRIPT_DIR/JavaClassLayerExporter.java"

CMD=(java -cp "$SCRIPT_DIR" JavaClassLayerExporter --project "$PROJECT_DIR" --output "$OUTPUT_FILE" --layer-config "$LAYER_CONFIG_ARG")
if [[ -n "$CLASSPATH_ARG" ]]; then
  CMD+=(--classpath "$CLASSPATH_ARG")
fi
if [[ -n "$INCLUDE_PREFIX_ARG" ]]; then
  CMD+=(--include-prefix "$INCLUDE_PREFIX_ARG")
fi

"${CMD[@]}"

echo "Class layer JSONL written to: $OUTPUT_FILE"
