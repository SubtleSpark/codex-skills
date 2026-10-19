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
            ├── SKILL.md
            └── agents/openai.yaml
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

`Knowledge PR` 仅手动触发，从当前知识库及其 Worktree 的本次任务成果中选择性提炼知识，向同一知识库提交 PR。

安装该 plugin 后，可手动调用：

```text
使用 $knowledge-pr，将本次任务相关知识整理为当前知识库的 PR。
```

流程：整理当前任务相关知识 → 对照最新主库与相关 PR → 检查文本与知识冲突 → 提交 PR → 结束。没有合适增量时直接结束。

Skill 不扫描整个工作树的历史积累，不在任务结束时自动运行，不自动合并或跟踪 PR，也不自动同步或清理源工作树。扩大材料范围、合并及同步需要另外明确要求。机器路径、远端主仓库与目标分支在运行时核对。

Skill 的 `agents/openai.yaml` 设置 `allow_implicit_invocation: false`，禁用隐式调用。

## 视频课件转 PPT

`video-to-ppt` 下载视频及可用字幕，提取视频中已有课件画面，经看图核对后按播放顺序整理成图片型 `.pptx`。没有字幕时跳过字幕，继续视频和 PPT；不自动转写，也不把课件重写成总结。

```text
使用 $video-to-ppt 下载这个视频和可用字幕，把原有课件画面还原成图片 PPT，保存到指定目录。
```

第三方下载、抽帧、PPT 制作或渲染能力按需检查。缺失时提示安装项并停止，不自动安装、升级或拉取运行时。附带的 Python 标准库脚本生成候选 PNG、时间戳清单和看图页面；自动抽帧之后仍需检查漏页、动画及细微变化。
