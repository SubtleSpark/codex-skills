#!/bin/bash
# 将 mmd 文件转换为 svg
# 用法: ./mmd2svg.sh [input.mmd]
# 默认: .tmp/flowchart.mmd

INPUT="${1:-.tmp/flowchart.mmd}"
OUTPUT="${INPUT%.mmd}.svg"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
npx -y -p @mermaid-js/mermaid-cli mmdc -i "$INPUT" -o "$OUTPUT" -c "$SCRIPT_DIR/mermaid.config.json"

echo "SVG 已生成: $OUTPUT"
