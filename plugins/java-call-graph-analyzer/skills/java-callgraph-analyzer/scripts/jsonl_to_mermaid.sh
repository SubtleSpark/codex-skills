#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INPUT_FILE=".tmp/callgraph-java.jsonl"
OUTPUT_FILE=".tmp/callgraph-java.mmd"

EXTRA_ARGS=("$@")
JAVA_ARGS=()
for ((i = 0; i < ${#EXTRA_ARGS[@]}; i++)); do
  arg="${EXTRA_ARGS[$i]}"
  case "$arg" in
    --input=*)
      INPUT_FILE="${arg#*=}"
      ;;
    --output=*)
      OUTPUT_FILE="${arg#*=}"
      ;;
    --input)
      if [[ $((i + 1)) -lt ${#EXTRA_ARGS[@]} ]]; then
        INPUT_FILE="${EXTRA_ARGS[$((i + 1))]}"
        i=$((i + 1))
      fi
      ;;
    --output)
      if [[ $((i + 1)) -lt ${#EXTRA_ARGS[@]} ]]; then
        OUTPUT_FILE="${EXTRA_ARGS[$((i + 1))]}"
        i=$((i + 1))
      fi
      ;;
    *)
      JAVA_ARGS+=("$arg")
      ;;
  esac
done

mkdir -p "$(dirname "$OUTPUT_FILE")"

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/java-callgraph-analyzer.XXXXXX")"
cleanup() {
  rm -rf "$BUILD_DIR"
}
trap cleanup EXIT

javac -d "$BUILD_DIR" "$SCRIPT_DIR/JsonlToMermaid.java"
CMD=(java -cp "$BUILD_DIR" JsonlToMermaid --input "$INPUT_FILE" --output "$OUTPUT_FILE")
if [[ ${#JAVA_ARGS[@]} -gt 0 ]]; then
  CMD+=("${JAVA_ARGS[@]}")
fi
"${CMD[@]}"

echo "Mermaid file written to: $OUTPUT_FILE"
