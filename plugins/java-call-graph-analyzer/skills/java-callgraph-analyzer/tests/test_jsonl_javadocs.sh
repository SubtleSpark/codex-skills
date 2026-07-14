#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$TEST_DIR/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/java-callgraph-javadocs-test.XXXXXX")"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

PROJECT_DIR="$TMP_DIR/project"
SRC_DIR="$PROJECT_DIR/src/main/java/com/example/docs"
OUTPUT_FILE="$TMP_DIR/callgraph.jsonl"
MMD_FILE="$TMP_DIR/callgraph.mmd"
MMD_WITH_DOCS_FILE="$TMP_DIR/callgraph-with-docs.mmd"

mkdir -p "$SRC_DIR"

cat > "$SRC_DIR/DocController.java" <<'JAVA'
package com.example.docs;

public class DocController {
    private final DocService service = new DocService();

    /**
     * Starts the documented flow.
     */
    public String start(String id) {
        return service.documented(id);
    }
}
JAVA

cat > "$SRC_DIR/DocService.java" <<'JAVA'
package com.example.docs;

public class DocService {
    /**
     * Places a documented call.
     *
     * @param id business id
     * @return normalized id
     */
    public String documented(String id) {
        return id.trim();
    }

    public void undocumented() {
    }
}
JAVA

"$SKILL_DIR/scripts/generate_callgraph_jsonl.sh" \
  "$PROJECT_DIR" \
  "$OUTPUT_FILE" \
  "" \
  "com.example"

assert_contains() {
  local expected="$1"
  if ! grep -Fq "$expected" "$OUTPUT_FILE"; then
    printf 'Expected JSONL fragment not found:\n%s\n\nActual output:\n' "$expected" >&2
    sed -n '1,200p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_not_contains() {
  local unexpected="$1"
  if grep -Fq "$unexpected" "$OUTPUT_FILE"; then
    printf 'Unexpected JSONL fragment found:\n%s\n\nActual output:\n' "$unexpected" >&2
    sed -n '1,200p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_contains '{"type":"method","id":"com.example.docs.DocController#start(java.lang.String)","javadoc":"Starts the documented flow.'
assert_contains '{"type":"method","id":"com.example.docs.DocService#documented(java.lang.String)","javadoc":"Places a documented call.'
assert_contains '@param id business id\n@return normalized id'
assert_contains '{"from":"com.example.docs.DocController#start(java.lang.String)","to":"com.example.docs.DocService#documented(java.lang.String)","kind":"direct"}'
assert_not_contains '{"type":"method","id":"com.example.docs.DocService#undocumented()","javadoc":'

"$SKILL_DIR/scripts/jsonl_to_mermaid.sh" \
  --input "$OUTPUT_FILE" \
  --output "$MMD_FILE" \
  --func "com.example.docs.DocController#start" \
  --include-prefix "com.example" \
  --max-depth 1

if ! grep -Fq 'com.example.docs.DocService#documented(' "$MMD_FILE"; then
  printf 'Expected Mermaid output to include documented callee. Actual output:\n' >&2
  sed -n '1,120p' "$MMD_FILE" >&2
  exit 1
fi

if grep -Fq 'Starts the documented flow.' "$MMD_FILE"; then
  printf 'Expected Mermaid output without --include-docs to omit Javadoc. Actual output:\n' >&2
  sed -n '1,120p' "$MMD_FILE" >&2
  exit 1
fi

"$SKILL_DIR/scripts/jsonl_to_mermaid.sh" \
  --input "$OUTPUT_FILE" \
  --output "$MMD_WITH_DOCS_FILE" \
  --func "com.example.docs.DocController#start" \
  --include-prefix "com.example" \
  --max-depth 1 \
  --include-docs true

if ! grep -Fq 'Starts the documented flow.<br/><br/>com.example.docs.DocController#start(' "$MMD_WITH_DOCS_FILE"; then
  printf 'Expected Mermaid output to place controller Javadoc above the method label. Actual output:\n' >&2
  sed -n '1,140p' "$MMD_WITH_DOCS_FILE" >&2
  exit 1
fi

if ! grep -Fq 'Places a documented call.<br/><br/>@param id business id<br/>@return normalized id<br/><br/>com.example.docs.DocService#documented(' "$MMD_WITH_DOCS_FILE"; then
  printf 'Expected Mermaid output to place service Javadoc above the method label. Actual output:\n' >&2
  sed -n '1,140p' "$MMD_WITH_DOCS_FILE" >&2
  exit 1
fi

printf 'Javadoc JSONL test passed: %s\n' "$OUTPUT_FILE"
