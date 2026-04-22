---
name: callgraph-analyzer
description: 手动触发时使用：使用 Go 官方 callgraph 工具分析代码调用链，生成 Mermaid 和 SVG。
---

# Callgraph Analyzer

分析 Go 项目的函数调用链，生成可视化的 Mermaid 流程图。支持两种分析模式：

| 模式 | 方向 | 问题 | 用途 |
|------|------|------|------|
| **down** | 向下 | "A 调用了谁？" | 分析依赖、理解实现 |
| **up** | 向上 | "谁调用了 A？" | 影响分析、重构评估 |

## 前置依赖

需要先安装 Go 官方 `callgraph` 工具：

```bash
go install golang.org/x/tools/cmd/callgraph@latest
```

## 使用步骤

### 1. 生成 callgraph JSON

> **注意**：需在项目根目录执行

```bash
<skill-dir>/scripts/generate_callgraph_json.sh <项目目录> [输出文件]
```

**参数说明：**
- `<项目目录>`: 绝对路径或相对路径均可（如 `.` 表示当前目录）
- `[输出文件]`: 相对于项目目录的路径（默认：`.tmp/callgraph.json`）

### 2. 生成 Mermaid 流程图

> **注意**：需在项目根目录执行

```bash
go run <skill-dir>/scripts/main.go \
  -mode=<down|up> \
  -func="包路径.函数名" \
  -prefix="包前缀" \
  -output=.tmp/flowchart.mmd
```

**参数说明：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| -mode | 分析模式：`down`(向下) / `up`(向上) | down |
| -func | 入口/目标函数（多个用逗号分隔） | 必填 |
| -input | JSON 输入文件 | .tmp/callgraph.json |
| -output | 输出文件 | .tmp/flowchart.mmd |
| -prefix | 包名前缀过滤 | 不过滤 |
| -max-depth | 最大遍历深度 | 20 |
| -cut | [down] 剪枝节点（红色） | - |
| -mark | [down] 标记节点（橙色） | - |
| -external | [down] 是否标记外部调用 | true |

**`-func` 格式：**
- 普通函数：`adxserver/controllers/http/ad.AdReq`
- 指针方法：`(*adxserver/service/srvcache.defaultSrv).GetAd`
- 值方法：`(adxserver/pkg.Type).Method`
- 多个函数：`pkg.FuncA,pkg.FuncB`（逗号分隔，合并到一张图）

**`-prefix` 作用：**
- 只保留 caller 和 callee 都匹配前缀的调用边
- 多个前缀用逗号分隔：`-prefix="adxserver/,adxlib/"`
- 不填则不过滤，保留所有调用边

**`-cut` / `-mark` 匹配规则（仅 down 模式）：**
- 子串匹配：`-cut="DoBidding"` 匹配所有包含 "DoBidding" 的函数
- 多个模式用逗号分隔：`-cut="DoBidding,srvadapi"`

### 3. 转换为 SVG（可选）

```bash
<skill-dir>/scripts/mmd2svg.sh <mmd文件>
```

## 样式说明

### 共用样式
- **entry**: 紫色粗边框 - 入口/目标函数（自动标识）

### Down 模式专用
- **cut**: 红色 - 剪枝节点，不遍历子节点
- **mark**: 橙色 - 标记节点，子节点也标橙
- **external_db**: 蓝色 - 数据库调用
- **external_redis**: 粉色 - Redis 调用
- **external_http**: 绿色 - HTTP 调用
- **external_mq**: 橙色 - 消息队列调用

### Up 模式专用
- **top**: 紫色粗边框 - 顶层调用者（没有更上层调用的节点）

## 图形布局

生成的 Mermaid 图使用 `flowchart LR`（从左到右）布局：

### Down 模式
```
┌──────────┐    ┌──────────┐    ┌──────────┐
│ 入口函数  │ ──▶│ 中间层    │ ──▶│ 底层依赖  │
└──────────┘    └──────────┘    └──────────┘
     左                              右
   紫色标记
```

### Up 模式
```
┌──────────┐    ┌──────────┐    ┌──────────┐
│ 顶层入口  │ ──▶│ 中间层    │ ──▶│ 目标函数 │
└──────────┘    └──────────┘    └──────────┘
     左                              右
   紫色标记                        紫色标记
```

## 示例

### 示例 1：向下分析 AdReq 调用链

```bash
# 1. 生成 JSON（首次或代码变更后执行）
<skill-dir>/scripts/generate_callgraph_json.sh .

# 2. 向下分析，剪枝 DoBidding，标记 Check
go run <skill-dir>/scripts/main.go \
  -mode=down \
  -func="adxserver/controllers/http/ad.AdReq" \
  -prefix="adxserver/" \
  -cut="DoBidding" \
  -mark="(*adxserver/service/srvcheck.defaultSrv).Check" \
  -output=.tmp/adreq_down.mmd

# 3. 转换为 SVG
<skill-dir>/scripts/mmd2svg.sh .tmp/adreq_down.mmd
```

### 示例 2：向上分析 GetAd 被谁调用

```bash
go run <skill-dir>/scripts/main.go \
  -mode=up \
  -func="(*adxserver/service/srvcache.defaultSrv).GetAd" \
  -prefix="adxserver/" \
  -output=.tmp/getad_up.mmd

<skill-dir>/scripts/mmd2svg.sh .tmp/getad_up.mmd
```

### 示例 3：重构前影响分析

想要重构某个底层函数？先看看有多少地方调用了它：

```bash
go run <skill-dir>/scripts/main.go \
  -mode=up \
  -func="adxserver/helpers.FormatPrice" \
  -prefix="adxserver/" \
  -max-depth=5 \
  -output=.tmp/formatprice_callers.mmd
```

### 示例 4：多函数分析

分析多个 API 共享了哪些底层依赖：

```bash
# 向下：看多个入口的共同依赖
go run <skill-dir>/scripts/main.go \
  -mode=down \
  -func="adxserver/controllers/http/ad.AdReq,adxserver/controllers/http/ad.Bid" \
  -prefix="adxserver/" \
  -output=.tmp/multi_down.mmd

# 向上：看多个函数的共同调用者
go run <skill-dir>/scripts/main.go \
  -mode=up \
  -func="pkg.FuncA,pkg.FuncB,pkg.FuncC" \
  -prefix="adxserver/" \
  -output=.tmp/multi_up.mmd
```
