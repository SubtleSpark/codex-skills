# codex-skills

`codex-skills` 是一个最小的 Codex repo marketplace。

当前内容：

- marketplace：`codex-skills`
- plugin：`Go Call Graph Analyzer`（`go-call-graph-analyzer`）
- plugin：`Java Call Graph Analyzer`（`java-call-graph-analyzer`）
- plugin：`Knowledge PR`（`knowledge-pr`）

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
    ├── java-call-graph-analyzer/
    │   ├── .codex-plugin/plugin.json
    │   └── skills/java-callgraph-analyzer/
    │       ├── SKILL.md
    │       └── ...
    └── knowledge-pr/
        ├── .codex-plugin/plugin.json
        └── skills/knowledge-pr/
            └── SKILL.md
```

## 当前对象

- marketplace：`codex-skills`
- plugin：`go-call-graph-analyzer`
- plugin：`java-call-graph-analyzer`
- skill：`callgraph-analyzer`
- skill：`java-callgraph-analyzer`
- plugin：`knowledge-pr`
- skill：`knowledge-pr`

## 知识回流

`Knowledge PR` 将任务成果整理为目标知识库的 PR，支持从长期工作树选择性贡献知识。

安装该 plugin 后，可请求：

```text
使用 $knowledge-pr，将本次任务中值得共享的知识整理并提交到目标知识库。
```

Skill 根据当前任务识别目标仓库，读取其规则、最新目标分支和相关 PR，在贡献分支上整合知识，并检查文本冲突和知识冲突。也可以只要求整理候选变更。

源工作树可以继续使用；提交 PR 不自动合并 PR、同步或清理源工作树。Skill 不绑定固定机器路径，也不自带凭据或工具安装步骤。
