#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <input.mmd> [output.svg]"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INPUT="${1}"
OUTPUT="${2:-.tmp/package-dependencies-java.svg}"

npx -y -p @mermaid-js/mermaid-cli mmdc -i "$INPUT" -o "$OUTPUT" -c "$SCRIPT_DIR/mermaid.config.json"

echo "Generated: $OUTPUT"
