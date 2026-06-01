---
name: java-callgraph-analyzer
description: 手动触发时使用：零入侵分析 Java 源码调用链，生成 edge-only JSONL、全量或局部 Mermaid 调用链图和 SVG。
---

# Java Call Graph Analyzer

零入侵方式分析 Java 项目调用链：

1. 用 JDK 内置编译器 API 扫描源码并导出 edge-only `callgraph.jsonl`
2. 从 JSONL 生成全量或局部 Mermaid 调用链图
3. 将 Mermaid 转成 SVG

## 前置依赖

- JDK 8+（需要 `javac`；JDK 8 会自动使用 `tools.jar`）
- NPM（可选，仅用于 Mermaid 转 SVG）

## JDK 选择

生成 JSONL 时，脚本调用的是当前环境里的 `javac` 和 `java`，不会自动切换 JDK。运行脚本的 JDK 版本应大于等于目标项目源码版本：Java 17 项目用 JDK 17+，Java 21 项目用 JDK 21+。低版本 `javac` 无法解析高版本语法。

如需临时指定 JDK：

```bash
JAVA_HOME=/path/to/jdk-17 PATH="/path/to/jdk-17/bin:$PATH" \
  <skill-dir>/scripts/generate_callgraph_jsonl.sh ...
```

## 使用步骤

### 1) 生成 callgraph JSONL（关键步骤）

```bash
<skill-dir>/scripts/generate_callgraph_jsonl.sh \
  <项目目录> \
  [输出文件] \
  [classpath] \
  [include-prefix]
```

参数：
- `<项目目录>`：要分析的 Java 源码根目录
- `[输出文件]`：默认 `.tmp/callgraph-java.jsonl`
- `[classpath]`：可选，编译解析依赖路径（`:` 分隔），通常包含 Lombok、Spring、Vavr 等项目依赖 jar；Maven/Gradle 项目建议传入 compile classpath，缺依赖时仍可能输出 JSONL，但边会更少且诊断更多
- `[include-prefix]`：可选，按方法全名做前缀过滤，多个用逗号

> 说明：该步骤不要求改动被分析项目，不要求在目标项目里嵌入 POM。

### 2) JSONL 转 Mermaid

```bash
<skill-dir>/scripts/jsonl_to_mermaid.sh \
  [jsonl文件] \
  [mmd输出文件] \
  [--mode down|up] \
  [--func 方法名] \
  [--include-prefix 包前缀] \
  [--max-depth 深度] \
  [--cut 模式] \
  [--mark 模式]
```

默认：`.tmp/callgraph-java.jsonl -> .tmp/callgraph-java.mmd`

不传 `--func` 时会把输入 JSONL 全量转成 Mermaid。传入 `--func` 后会生成局部调用链：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--mode` | `down` 表示“这个方法调用了谁”，`up` 表示“谁调用了这个方法” | `down` |
| `--func` | 入口/目标方法，多个用逗号分隔；可传完整方法 ID，也可传 `pkg.Type#method` 前缀来匹配重载签名 | - |
| `--include-prefix` / `--prefix` | 只保留 caller 和 callee 都匹配前缀的调用边，多个用逗号分隔 | 不过滤 |
| `--max-depth` | 从入口/目标开始遍历的最大深度 | `20` |
| `--cut` | `down` 模式剪枝节点，子串匹配；命中后节点标红且不继续遍历子节点 | - |
| `--mark` | `down` 模式标记节点，子串匹配；命中节点及其子节点标橙 | - |

> 说明：当前 Java 版暂不支持 Go 版的 `external` 外部系统保留/着色参数。需要保留外部调用时，先不要用 `--include-prefix` 过滤，或后续增加可配置外部调用分类。

### 3) Mermaid 转 SVG

```bash
<skill-dir>/scripts/mmd2svg.sh \
  <mmd文件> \
  [svg输出文件]
```

内部调用：

```bash
npx -y -p @mermaid-js/mermaid-cli mmdc ...
```

## 示例

```bash
# 1. 生成 JSONL
<skill-dir>/scripts/generate_callgraph_jsonl.sh \
  . .tmp/callgraph-java.jsonl

# 2. JSONL 转 Mermaid
<skill-dir>/scripts/jsonl_to_mermaid.sh \
  .tmp/callgraph-java.jsonl .tmp/callgraph-java.mmd

# 3. 渲染 SVG
<skill-dir>/scripts/mmd2svg.sh \
  .tmp/callgraph-java.mmd .tmp/callgraph-java.svg
```

局部调用链示例：

```bash
# 向下看 Controller 入口调用了哪些业务方法，最多 5 层，遇到 Repository 停止继续展开
<skill-dir>/scripts/jsonl_to_mermaid.sh \
  .tmp/callgraph-java.jsonl .tmp/order-down.mmd \
  --mode down \
  --func "com.example.order.OrderController#createOrder" \
  --include-prefix "com.example" \
  --max-depth 5 \
  --cut "Repository" \
  --mark "OrderService"

# 向上看某个领域服务方法被哪些入口调用
<skill-dir>/scripts/jsonl_to_mermaid.sh \
  .tmp/callgraph-java.jsonl .tmp/order-up.mmd \
  --mode up \
  --func "com.example.order.OrderService#createOrder" \
  --include-prefix "com.example" \
  --max-depth 5
```

## 输出 JSONL 结构

每一行是一条调用边：

```jsonl
{"from":"pkg.A#a()","to":"pkg.B#b()"}
{"from":"pkg.B#b()","to":"pkg.C#c(java.lang.String)"}
```

## 当前能力边界

- 这是静态源码分析，不执行业务代码
- 对反射、动态代理、运行时字节码增强等场景覆盖有限
- 若需要更高精度（CHA/RTA/points-to），可在后续切到 SootUp 版导出器
