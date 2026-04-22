---
name: visual-ddd-skill
description: 手动触发时使用：通过静态分析 Java 源码生成 class dependency JSONL，作为可视化 DDD 依赖关系的第一步。
---

# Visual DDD Skill

第一阶段只生成 Java 类与类之间的静态依赖关系，不做 DDD 语义分类。

## 前置依赖

- JDK（需要 `javac`）

## 使用步骤

### 1) 生成 class dependency JSONL

```bash
<skill-dir>/scripts/generate_class_dependencies_jsonl.sh \
  <项目目录> \
  [输出文件] \
  [classpath] \
  [include-prefix]
```

参数：
- `<项目目录>`：要分析的 Java 源码根目录
- `[输出文件]`：默认 `.tmp/class-dependencies-java.jsonl`
- `[classpath]`：可选，辅助 `javac` 做类型解析（`:` 分隔）
- `[include-prefix]`：可选，只输出匹配该类名前缀的项目内依赖；多个用逗号分隔

## 输出 JSONL 结构

每一行是一条类依赖边：

```jsonl
{"from":"pkg.A","to":"pkg.B","kind":"field"}
{"from":"pkg.A","to":"pkg.C","kind":"method-param"}
```

`kind` 表示依赖来源：

```text
extends, implements, field, method-return, method-param, throws,
local-var, new, annotation, static-import, class-literal, cast, instanceof
```

如需解释 JSONL schema、`kind` 枚举含义和 import 处理规则，读取 `references/class-dependency-jsonl.md`。

## 当前能力边界

- 使用 `javac` 解析符号，`import *` 只会在实际使用具体类时输出具体类依赖
- 不输出 unused import
- 默认只输出项目源码内的类依赖，不输出 JDK、Spring、Lombok、Vavr 等外部依赖
- 这是源码静态分析，不执行业务代码
