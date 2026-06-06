#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$TEST_DIR/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/java-callgraph-mermaid-labels-test.XXXXXX")"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

INPUT_FILE="$TMP_DIR/callgraph.jsonl"
OUTPUT_FILE="$TMP_DIR/callgraph.mmd"

cat > "$INPUT_FILE" <<'JSONL'
{"from":"com.github.acme.order.application.OrderService#create(com.github.acme.order.model.OrderCommand,java.util.Map<java.lang.String,com.github.acme.order.model.Item>)","to":"com.github.acme.order.domain.Order#validate()","kind":"direct"}
JSONL

"$SKILL_DIR/scripts/jsonl_to_mermaid.sh" \
  --input "$INPUT_FILE" \
  --output "$OUTPUT_FILE" \
  --mode down \
  --func "com.github.acme.order.application.OrderService#create" \
  --include-prefix "com.github.acme" \
  --max-depth 1

assert_contains() {
  local expected="$1"
  if ! grep -Fq "$expected" "$OUTPUT_FILE"; then
    printf 'Expected Mermaid fragment not found:\n%s\n\nActual output:\n' "$expected" >&2
    sed -n '1,120p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_not_contains() {
  local unexpected="$1"
  if grep -Fq "$unexpected" "$OUTPUT_FILE"; then
    printf 'Unexpected Mermaid fragment found:\n%s\n\nActual output:\n' "$unexpected" >&2
    sed -n '1,120p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_contains 'com.github.acme.order.application.OrderService#create(<br/>com.github.acme.order.model.OrderCommand,<br/>java.util.Map&lt;java.lang.String,com.github.acme.order.model.Item&gt;<br/>)'
assert_contains 'com.github.acme.order.domain.Order#validate()'
assert_not_contains 'java.util.Map&lt;java.lang.String,<br/>com.github.acme.order.model.Item&gt;'

printf 'Mermaid label test passed.\n'
