#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$TEST_DIR/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/java-callgraph-mermaid-both-test.XXXXXX")"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

INPUT_FILE="$TMP_DIR/callgraph.jsonl"
OUTPUT_FILE="$TMP_DIR/callgraph.mmd"

cat > "$INPUT_FILE" <<'JSONL'
{"from":"com.example.order.OrderController#create()","to":"com.example.order.OrderService#create(java.lang.String)","kind":"direct"}
{"from":"com.example.order.OrderService#create(java.lang.String)","to":"com.example.order.OrderServiceImpl#create(java.lang.String)","kind":"hierarchy"}
{"from":"com.example.order.OrderServiceImpl#create(java.lang.String)","to":"com.example.order.OrderServiceImpl#audit(java.lang.String)","kind":"direct"}
JSONL

"$SKILL_DIR/scripts/jsonl_to_mermaid.sh" \
  --input "$INPUT_FILE" \
  --output "$OUTPUT_FILE" \
  --mode both \
  --func "com.example.order.OrderServiceImpl#create" \
  --include-prefix "com.example" \
  --max-depth 5

assert_contains() {
  local expected="$1"
  if ! grep -Fq "$expected" "$OUTPUT_FILE"; then
    printf 'Expected Mermaid fragment not found:\n%s\n\nActual output:\n' "$expected" >&2
    sed -n '1,160p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_not_contains() {
  local unexpected="$1"
  if grep -Fq "$unexpected" "$OUTPUT_FILE"; then
    printf 'Unexpected Mermaid fragment found:\n%s\n\nActual output:\n' "$unexpected" >&2
    sed -n '1,160p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_line_count() {
  local expected="$1"
  local pattern="$2"
  local actual
  actual="$(grep -Fc "$pattern" "$OUTPUT_FILE")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Expected %s occurrences of %s, got %s\n\nActual output:\n' "$expected" "$pattern" "$actual" >&2
    sed -n '1,160p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_line_count 1 'flowchart LR'
assert_contains 'com.example.order.OrderController#create()'
assert_contains 'com.example.order.OrderService#create(<br/>java.lang.String)'
assert_contains 'com.example.order.OrderServiceImpl#create(<br/>java.lang.String)'
assert_contains 'com.example.order.OrderServiceImpl#audit(<br/>java.lang.String)'
assert_not_contains '<br/>)'
assert_contains 'N2 --> N1'
assert_contains 'N1 --> N0'
assert_contains 'N0 --> N3'
assert_contains 'classDef entry fill:#dbeafe,stroke:#2563eb,stroke-width:3px,color:#1e3a8a'
assert_contains 'classDef top fill:#f3f4f6,stroke:#6b7280,stroke-width:2px,color:#374151'

printf 'Mermaid both mode test passed.\n'
