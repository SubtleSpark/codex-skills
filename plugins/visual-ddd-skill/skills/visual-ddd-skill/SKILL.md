---
name: visual-ddd-skill
description: 手动触发时使用：通过静态分析 Java 源码生成 class dependency JSONL 和 DDD layer metadata JSONL。
---

# Visual DDD Skill

当前提供两类 JSONL：Java 类与类之间的静态依赖关系，以及基于正则配置的 DDD layer metadata。Layer 标记只按类名规则分类，不推断更深业务语义。

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

### 2) 生成 DDD layer metadata JSONL

```bash
<skill-dir>/scripts/generate_class_layers_jsonl.sh \
  <项目目录> \
  [输出文件] \
  [layer-config] \
  [classpath] \
  [include-prefix]
```

参数：
- `<项目目录>`：要分析的 Java 源码根目录
- `[输出文件]`：默认 `.tmp/class-layers-java.jsonl`
- `[layer-config]`：可选的 JSON 配置文件路径；默认 `references/default-ddd-layers.json`
- `[classpath]`：可选，辅助 `javac` 做类型解析（`:` 分隔）
- `[include-prefix]`：可选，只输出匹配该类名前缀的项目内 class metadata；多个用逗号分隔

## 输出 JSONL 结构

Class dependency JSONL 每一行是一条类依赖边：

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

Class layer JSONL 每一行是一条类元数据：

```jsonl
{"class":"pkg.A","layer":"domain","label":"Domain","color":"#2F855A"}
```

如需解释 layer 配置 schema、Java regex 匹配风格、匹配顺序、默认层规则和自定义配置示例，读取 `references/ddd-layer-config.md`。

## 当前能力边界

- 使用 `javac` 解析符号，`import *` 只会在实际使用具体类时输出具体类依赖
- 不输出 unused import
- 默认只输出项目源码内的类依赖，不输出 JDK、Spring、Lombok、Vavr 等外部依赖
- DDD layer 标记只按完整类名正则匹配，不推断真实业务语义
- 这是源码静态分析，不执行业务代码
