---
name: java-callgraph-analyzer
description: 手动触发时使用：零入侵分析 Java 源码调用链，生成带方法 Javadoc 元数据和 direct/hierarchy 边类型的 JSONL，并按入口方法追踪上游、下游或双向 Mermaid 调用链图，可选在节点上方展示 Javadoc。
---

# Java Call Graph Analyzer

零入侵方式分析 Java 方法调用链。主流程是先生成 JSONL 调用边索引，再用一个或多个入口方法做上游、下游或双向局部追踪。

1. 用 JDK 内置编译器 API 扫描源码并导出带方法 Javadoc 元数据和边类型的 `callgraph.jsonl`
2. 默认补充接口、父类、子类 override 的 hierarchy 实现链路
3. 传入 `--func`，从指定方法向下、向上或双向生成 Mermaid 调用链图
4. 按需将 Mermaid 转成 SVG

## 前置依赖

- JDK 8+（需要 `javac`；JDK 8 会自动使用 `tools.jar`）
- NPM（可选，仅用于 Mermaid 转 SVG）

## JDK 选择

生成 JSONL 时，脚本调用的是当前环境里的 `javac` 和 `java`，不会自动切换 JDK。运行脚本的 JDK 版本应大于等于目标项目源码版本：Java 17 项目用 JDK 17+，Java 21 项目用 JDK 21+。低版本 `javac` 无法解析高版本语法。

如需临时指定 JDK：

```bash
JAVA_HOME=/path/to/jdk-17 PATH="/path/to/jdk-17/bin:$PATH" \
  <java-callgraph-analyzer目录>/scripts/generate_callgraph_jsonl.sh ...
```

## 使用步骤

### 1) 生成 callgraph JSONL（关键步骤）

```bash
<java-callgraph-analyzer目录>/scripts/generate_callgraph_jsonl.sh \
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

### 2) 按方法生成 Mermaid 调用链

```bash
<java-callgraph-analyzer目录>/scripts/jsonl_to_mermaid.sh \
  --input <jsonl文件> \
  --output <mmd输出文件> \
  [--mode down|up|both] \
  [--func 方法名] \
  [--include-prefix 包前缀] \
  [--max-depth 深度] \
  [--cut 模式] \
  [--mark 模式] \
  [--include-docs true|false]
```

默认：未指定 `--input` 时读取 `.tmp/callgraph-java.jsonl`，未指定 `--output` 时写入 `.tmp/callgraph-java.mmd`。

主路径是传入 `--func` 生成局部调用链：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--input` | 输入的 callgraph JSONL 文件 | `.tmp/callgraph-java.jsonl` |
| `--output` | 输出 Mermaid `.mmd` 文件 | `.tmp/callgraph-java.mmd` |
| `--mode` | `down` 表示“这个方法调用了谁”，`up` 表示“谁调用了这个方法”，`both` 表示在同一张图里同时展示上游和下游 | `down` |
| `--func` | 入口/目标方法，多个用逗号分隔；可传完整方法 ID，也可传 `pkg.Type#method` 前缀来匹配重载签名 | 主路径必传 |
| `--include-prefix` | 只保留 caller 和 callee 都匹配前缀的调用边，多个用逗号分隔 | 不过滤 |
| `--max-depth` | 从入口/目标开始遍历的最大深度 | `20` |
| `--cut` | `down` / `both` 的下游侧剪枝节点，子串匹配；命中后节点标红且不继续遍历子节点 | - |
| `--mark` | `down` / `both` 的下游侧标记节点，子串匹配；命中节点及其子节点标橙 | - |
| `--include-docs` | 是否把 JSONL 中的本地方法 Javadoc 展示在 Mermaid 节点签名上方；可写 `true` / `false`，也可只传 `--include-docs` 表示开启 | `false` |

不传 `--func` 时会把输入 JSONL 全量转成 Mermaid。大项目通常不建议这样做，除非只是快速检查索引内容。

> 说明：当前 Mermaid 转换不会单独保留或分类业务包前缀之外的外部调用。需要保留外部调用时，先不要用 `--include-prefix` 过滤，或后续增加可配置外部调用分类。
> 当前 Mermaid 转换会读取带 `kind` 的 JSONL，但暂不按 `direct` / `hierarchy` 区分线型。

Mermaid 节点会压缩展示长签名并左对齐 label：第一行显示完整 `owner#method(`，每个参数单独一行，右括号跟最后一个参数在同一行。无参方法保持 `owner#method()`。传 `--include-docs true` 时，若 JSONL 中存在该方法的本地 Javadoc，会把 Javadoc 放在节点签名上方；没有 Javadoc 的节点保持原样。这只影响图上的 label，JSONL、遍历和 `--func` 匹配仍使用完整方法 ID。

### 3) Mermaid 转 SVG

```bash
<java-callgraph-analyzer目录>/scripts/mmd2svg.sh \
  <mmd文件> \
  [svg输出文件]
```

内部调用：

```bash
npx -y -p @mermaid-js/mermaid-cli mmdc ...
```

## 示例

```bash
# 1. 生成 JSONL 索引
<java-callgraph-analyzer目录>/scripts/generate_callgraph_jsonl.sh \
  . .tmp/callgraph-java.jsonl "" "com.example"

# 2. 从 Controller 方法向下追业务调用链
<java-callgraph-analyzer目录>/scripts/jsonl_to_mermaid.sh \
  --input .tmp/callgraph-java.jsonl \
  --output .tmp/order-down.mmd \
  --mode down \
  --func "com.example.order.OrderController#createOrder" \
  --include-prefix "com.example" \
  --max-depth 5

# 3. 渲染 SVG
<java-callgraph-analyzer目录>/scripts/mmd2svg.sh \
  .tmp/order-down.mmd .tmp/order-down.svg
```

更多调用链示例：

```bash
# 向下看 Controller 入口调用了哪些业务方法，最多 5 层，遇到 Repository 停止继续展开
<java-callgraph-analyzer目录>/scripts/jsonl_to_mermaid.sh \
  --input .tmp/callgraph-java.jsonl \
  --output .tmp/order-down.mmd \
  --mode down \
  --func "com.example.order.OrderController#createOrder" \
  --include-prefix "com.example" \
  --max-depth 5 \
  --cut "Repository" \
  --mark "OrderService"

# 向上看某个领域服务方法被哪些入口调用
<java-callgraph-analyzer目录>/scripts/jsonl_to_mermaid.sh \
  --input .tmp/callgraph-java.jsonl \
  --output .tmp/order-up.mmd \
  --mode up \
  --func "com.example.order.OrderService#createOrder" \
  --include-prefix "com.example" \
  --max-depth 5

# 在同一张图里同时看某个方法的上游调用方和下游被调方法
<java-callgraph-analyzer目录>/scripts/jsonl_to_mermaid.sh \
  --input .tmp/callgraph-java.jsonl \
  --output .tmp/order-both.mmd \
  --mode both \
  --func "com.example.order.OrderService#createOrder" \
  --include-prefix "com.example" \
  --max-depth 5

# 展示节点对应方法的本地 Javadoc
<java-callgraph-analyzer目录>/scripts/jsonl_to_mermaid.sh \
  --input .tmp/callgraph-java.jsonl \
  --output .tmp/order-with-docs.mmd \
  --mode down \
  --func "com.example.order.OrderController#createOrder" \
  --include-prefix "com.example" \
  --max-depth 5 \
  --include-docs true

# 多个入口一起向下追踪
<java-callgraph-analyzer目录>/scripts/jsonl_to_mermaid.sh \
  --input .tmp/callgraph-java.jsonl \
  --output .tmp/multi-down.mmd \
  --mode down \
  --func "com.example.order.OrderController#createOrder,com.example.payment.PaymentController#pay" \
  --include-prefix "com.example" \
  --max-depth 5
```

## 输出 JSONL 结构

JSONL 会包含两类记录：

- `type=method`：源码方法的本地 Javadoc 元数据；只导出当前源码里方法声明上直接写的 Javadoc，不追接口继承注释，也不读取依赖 jar 文档
- 调用边：`from` / `to` / `kind`，用于后续 Mermaid 调用链追踪

示例：

```jsonl
{"type":"method","id":"pkg.Service#create(java.lang.String)","javadoc":"Create an order.\n\n@param id order id"}
{"from":"pkg.A#a()","to":"pkg.B#b()","kind":"direct"}
{"from":"pkg.B#b()","to":"pkg.C#c(java.lang.String)","kind":"direct"}
{"from":"pkg.Service#create(java.lang.String)","to":"pkg.ServiceImpl#create(java.lang.String)","kind":"hierarchy"}
```

- `type=method` 记录只在方法有 Javadoc 时输出；没有写 Javadoc 的方法不会额外输出空注释记录
- `kind=direct`：源码调用点直接解析出的调用边
- `kind=hierarchy`：根据接口、父类、子类 override/implements 关系补充的实现链路边
- 多实现接口会展开到多个候选实现；这是静态候选链路，不表示运行时一定只走某一个实现

## 当前能力边界

- 这是静态源码分析，不执行业务代码
- hierarchy 边只展开源码内可见的实现/override 方法，不会主动展开依赖 jar 里的框架实现类
- 当前不做 Spring Bean 选择、`@Qualifier`、`@Primary`、profile 或 condition 判断
- 对反射、动态代理、运行时字节码增强等场景覆盖有限
- 若需要更高精度，可后续扩展 RTA/points-to 等更强的静态分析策略
