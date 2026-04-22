---
name: java-callgraph-analyzer
description: 手动触发时使用：零入侵分析 Java 源码调用链，生成 edge-only JSONL、Mermaid 和 SVG。
---

# Java Call Graph Analyzer

零入侵方式分析 Java 项目调用链：

1. 用 JDK 内置编译器 API 扫描源码并导出 edge-only `callgraph.jsonl`
2. 将 JSONL 转成 Mermaid
3. 将 Mermaid 转成 SVG

## 前置依赖

- JDK（需要 `javac`）
- NPM（用于 Mermaid CLI）

## 使用步骤

### 1) 生成 callgraph JSONL（关键步骤）

```bash
<skill-dir>/generate_callgraph_jsonl.sh \
  <项目目录> \
  [输出文件] \
  [classpath] \
  [include-prefix]
```

参数：
- `<项目目录>`：要分析的 Java 源码根目录
- `[输出文件]`：默认 `.tmp/callgraph-java.jsonl`
- `[classpath]`：可选，编译解析依赖路径（`:` 分隔）
- `[include-prefix]`：可选，按方法全名做前缀过滤，多个用逗号

> 说明：该步骤不要求改动被分析项目，不要求在目标项目里嵌入 POM。

### 2) JSONL 转 Mermaid

```bash
<skill-dir>/jsonl_to_mermaid.sh \
  [jsonl文件] \
  [mmd输出文件]
```

默认：`.tmp/callgraph-java.jsonl -> .tmp/callgraph-java.mmd`

### 3) Mermaid 转 SVG

```bash
<skill-dir>/mmd2svg.sh \
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
<skill-dir>/generate_callgraph_jsonl.sh \
  . .tmp/callgraph-java.jsonl

# 2. JSONL 转 Mermaid
<skill-dir>/jsonl_to_mermaid.sh \
  .tmp/callgraph-java.jsonl .tmp/callgraph-java.mmd

# 3. 渲染 SVG
<skill-dir>/mmd2svg.sh \
  .tmp/callgraph-java.mmd .tmp/callgraph-java.svg
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
