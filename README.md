# codex-skills

`codex-skills` 是一个最小的 Codex repo marketplace。

当前内容：

- marketplace：`codex-skills`
- plugin：`Go Call Graph Analyzer`（`go-call-graph-analyzer`）
- plugin：`Java Call Graph Analyzer`（`java-call-graph-analyzer`）
- plugin：`Knowledge PR`（`knowledge-pr`）
- plugin：`Session Archive Checker`（`session-archive-checker`，含检查和尝试归档两个 Skill）

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
    ├── session-archive-checker/
    │   ├── .codex-plugin/plugin.json
    │   └── skills/
    │       ├── session-archive-checker/SKILL.md
    │       └── try-archive/
    │           ├── SKILL.md
    │           ├── agents/openai.yaml
    │           └── references/examples.md
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
- plugin：`session-archive-checker`
- skill：`session-archive-checker`
- skill：`try-archive`

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

`video-to-ppt` 默认每5秒完整采样，要求当前帧与原始前、后邻帧的960×540灰度SSIM均≥0.995筛出稳定图，再按64位pHash≤1且dHash≤2、后置SSIM≥0.995依次去重，保留首次合格帧输出PDF或PPT。首尾默认排除，全部原图、邻接得分、拒绝原因及阶段归属均保存；不加载AI决定。短展示或持续变化的真实页可能被稳定性排除，三个采样点相似不证明中间连续10秒稳定。

```text
使用 $video-to-ppt 每5秒采样，按原始前后邻帧SSIM筛稳定页，再经双哈希和SSIM去重，导出首次合格顺序PDF。
```

默认入口是 `plugins/video-to-ppt/skills/video-to-ppt/scripts/auto_pdf.py`，需要现有 ffmpeg/ffprobe、Python 3.12+、NumPy、Pillow、ReportLab、pypdf及scikit-image。缺失时提示并停止，不自动安装或拉取运行时。提高哈希阈值或降低去重SSIM阈值会更激进；提高稳定性阈值会排除更多帧。固定采样和感知哈希都可能漏掉不同内容，自动结果不宣称恢复全部原课件。

回归验证：使用已有上述依赖的Python执行 `python -B -m unittest discover -s plugins/video-to-ppt/skills/video-to-ppt/tests -v`；不需要真实视频或AI决策。

## 会话检查与尝试归档

安装 `Session Archive Checker` 即包含两个 Skill，无需分别安装：

- `$session-archive-checker`：只检查并给建议，不清理、不执行归档；原有标题标记规则不变。
- `$try-archive`：先读取同插件的检查 Skill，完整检查通过后，清理明确安全的本次临时项，复查后直接归档当前会话。

```text
使用 $try-archive，处理安全的临时文件，检查通过后直接归档当前会话。
```

`try-archive` 设置 `allow_implicit_invocation: false`，仅明确调用，不会因问“能归档吗”就执行。未提交代码、有用途的文件及不能安全判断的可选项会保留，不自动 commit、push 或合并 PR。存在未完成事项或关键疑问时停止；环境不支持正式归档能力时不清理、不伪装成功。检查规则通过相对路径引用原 Skill，不维护第二份标准。
