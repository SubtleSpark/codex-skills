#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$TEST_DIR/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/java-callgraph-hierarchy-test.XXXXXX")"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

PROJECT_DIR="$TMP_DIR/project"
SRC_DIR="$PROJECT_DIR/src/main/java/com/example/order"
OUTPUT_FILE="$TMP_DIR/callgraph.jsonl"

mkdir -p "$SRC_DIR"

cat > "$SRC_DIR/OrderService.java" <<'JAVA'
package com.example.order;

public interface OrderService {
    void create(String id);
}
JAVA

cat > "$SRC_DIR/OrderServiceImpl.java" <<'JAVA'
package com.example.order;

public class OrderServiceImpl implements OrderService {
    @Override
    public void create(String id) {
        audit(id);
    }

    void audit(String id) {
    }
}
JAVA

cat > "$SRC_DIR/BackupOrderService.java" <<'JAVA'
package com.example.order;

public class BackupOrderService implements OrderService {
    @Override
    public void create(String id) {
    }
}
JAVA

cat > "$SRC_DIR/OrderController.java" <<'JAVA'
package com.example.order;

public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public void create() {
        orderService.create("42");
    }
}
JAVA

cat > "$SRC_DIR/AbstractProcessor.java" <<'JAVA'
package com.example.order;

public abstract class AbstractProcessor {
    public void process() {
        common();
    }

    protected void common() {
    }
}
JAVA

cat > "$SRC_DIR/ConcreteProcessor.java" <<'JAVA'
package com.example.order;

public class ConcreteProcessor extends AbstractProcessor {
    @Override
    public void process() {
        step();
    }

    void step() {
    }
}
JAVA

cat > "$SRC_DIR/ProcessorClient.java" <<'JAVA'
package com.example.order;

public class ProcessorClient {
    public void run(AbstractProcessor processor) {
        processor.process();
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
    printf 'Expected JSONL line fragment not found:\n%s\n\nActual output:\n' "$expected" >&2
    sed -n '1,200p' "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_contains '{"from":"com.example.order.OrderController#create()","to":"com.example.order.OrderService#create(java.lang.String)","kind":"direct"}'
assert_contains '{"from":"com.example.order.OrderService#create(java.lang.String)","to":"com.example.order.OrderServiceImpl#create(java.lang.String)","kind":"hierarchy"}'
assert_contains '{"from":"com.example.order.OrderService#create(java.lang.String)","to":"com.example.order.BackupOrderService#create(java.lang.String)","kind":"hierarchy"}'
assert_contains '{"from":"com.example.order.ProcessorClient#run(com.example.order.AbstractProcessor)","to":"com.example.order.AbstractProcessor#process()","kind":"direct"}'
assert_contains '{"from":"com.example.order.AbstractProcessor#process()","to":"com.example.order.ConcreteProcessor#process()","kind":"hierarchy"}'
assert_contains '{"from":"com.example.order.OrderServiceImpl#create(java.lang.String)","to":"com.example.order.OrderServiceImpl#audit(java.lang.String)","kind":"direct"}'

printf 'Hierarchy JSONL test passed: %s\n' "$OUTPUT_FILE"
