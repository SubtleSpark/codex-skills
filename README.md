# codex-skills

`codex-skills` 是一个最小的 Codex repo marketplace。

当前内容：

- marketplace：`codex-skills`
- plugin：`Go Call Graph Analyzer`（`go-call-graph-analyzer`）
- plugin：`Java Call Graph Analyzer`（`java-call-graph-analyzer`）

## 安装

把这个仓库作为 Codex plugin marketplace 添加：

```bash
codex plugin marketplace add SubtleSpark/codex-skills
```

添加后重启 Codex，在 Plugins 里选择 `Codex Skills` marketplace，然后安装需要的 plugin。

后续更新 marketplace：

```bash
codex plugin marketplace upgrade codex-skills
```

## 核心概念

- `skill`：内容单元。真正写 workflow 指令的地方。
- `plugin`：安装单元。把一个或多个 skill 打包给 Codex 安装。
- `marketplace`：导入入口。告诉 Codex 这个仓库里有哪些 plugin 可以装。

## 当前目录结构

```text
codex-skills/
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

- marketplace：`codex-skills`
- plugin：`go-call-graph-analyzer`
- plugin：`java-call-graph-analyzer`
- skill：`callgraph-analyzer`
- skill：`java-callgraph-analyzer`
