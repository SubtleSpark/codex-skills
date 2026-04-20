# myskills

`myskills` 是一个最小的 Codex repo marketplace。

当前内容：

- marketplace：`myskills`
- plugin：`Go Call Graph Analyzer`（`go-call-graph-analyzer`）
- plugin：`Java Call Graph Analyzer`（`java-call-graph-analyzer`）

## 核心概念

- `skill`：内容单元。真正写 workflow 指令的地方。
- `plugin`：安装单元。把一个或多个 skill 打包给 Codex 安装。
- `marketplace`：导入入口。告诉 Codex 这个仓库里有哪些 plugin 可以装。

## 当前目录结构

```text
myskills/
├── .agents/
│   └── plugins/
│       └── marketplace.json
├── README.md
└── plugins/
    ├── go-call-graph-analyzer/
    │   ├── .codex-plugin/plugin.json
    │   └── skills/callgraph-analyzer/
    │       ├── SKILL.md
    │       └── ...
    └── java-call-graph-analyzer/
        ├── .codex-plugin/plugin.json
        └── skills/java-callgraph-analyzer/
            ├── SKILL.md
            └── ...
```

## 当前对象

- marketplace：`myskills`
- plugin：`go-call-graph-analyzer`
- plugin：`java-call-graph-analyzer`
- skill：`callgraph-analyzer`
- skill：`java-callgraph-analyzer`
