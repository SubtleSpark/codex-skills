---
name: java-callgraph-analyzer
description: 零入侵分析 Java 源码调用链，生成 JSON、Mermaid 和 SVG。
---

# Java Call Graph Analyzer

零入侵方式分析 Java 项目调用链：

1. 用 JDK 内置编译器 API 扫描源码并导出 `callgraph.json`
2. 将 JSON 转成 Mermaid
3. 将 Mermaid 转成 SVG

## 前置依赖

- JDK（需要 `javac`）
- NPM（用于 Mermaid CLI）

## 使用步骤

### 1) 生成 callgraph JSON（关键步骤）

```bash
plugins/java-call-graph-analyzer/skills/java-callgraph-analyzer/generate_callgraph_json.sh \
  <项目目录> \
  [输出文件] \
  [classpath] \
  [include-prefix]
```

参数：
- `<项目目录>`：要分析的 Java 源码根目录
- `[输出文件]`：默认 `.tmp/callgraph-java.json`
- `[classpath]`：可选，编译解析依赖路径（`:` 分隔）
- `[include-prefix]`：可选，按方法全名做前缀过滤，多个用逗号

> 说明：该步骤不要求改动被分析项目，不要求在目标项目里嵌入 POM。

### 2) JSON 转 Mermaid

```bash
plugins/java-call-graph-analyzer/skills/java-callgraph-analyzer/json_to_mermaid.sh \
  [json文件] \
  [mmd输出文件]
```

默认：`.tmp/callgraph-java.json -> .tmp/callgraph-java.mmd`

### 3) Mermaid 转 SVG

```bash
plugins/java-call-graph-analyzer/skills/java-callgraph-analyzer/mmd2svg.sh \
  <mmd文件> \
  [svg输出文件]
```

内部调用：

```bash
npx -y -p @mermaid-js/mermaid-cli mmdc ...
```

## 示例

```bash
# 1. 生成 JSON
plugins/java-call-graph-analyzer/skills/java-callgraph-analyzer/generate_callgraph_json.sh \
  . .tmp/callgraph-java.json

# 2. 转 Mermaid
plugins/java-call-graph-analyzer/skills/java-callgraph-analyzer/json_to_mermaid.sh \
  .tmp/callgraph-java.json .tmp/callgraph-java.mmd

# 3. 渲染 SVG
plugins/java-call-graph-analyzer/skills/java-callgraph-analyzer/mmd2svg.sh \
  .tmp/callgraph-java.mmd .tmp/callgraph-java.svg
```

## 输出 JSON 结构

```json
{
  "meta": {"tool": "jdk-source-analyzer", "mode": "static-source"},
  "nodes": [{"id": "pkg.Clz#method(java.lang.String)", "class": "pkg.Clz", "method": "method(java.lang.String)"}],
  "edges": [{"from": "pkg.A#a()", "to": "pkg.B#b()"}]
}
```

## 当前能力边界

- 这是静态源码分析，不执行业务代码
- 对反射、动态代理、运行时字节码增强等场景覆盖有限
- 若需要更高精度（CHA/RTA/points-to），可在后续切到 SootUp 版导出器
