# myskills

`myskills` 是一个最小的 Codex repo marketplace。

它的目标只有两个：

- 通过 Add Market 导入这个仓库
- 在一个 plugin 里管理并分发你的 skills

## 核心概念

- `skill`：内容单元。真正写 workflow 指令的地方。
- `plugin`：安装单元。把一个或多个 skill 打包给 Codex 安装。
- `marketplace`：导入入口。告诉 Codex 这个仓库里有哪些 plugin 可以装。

## 最小目录结构

```text
myskills/
├── .agents/
│   └── plugins/
│       └── marketplace.json          # Add Market 入口；列出当前仓库可安装的 plugin
├── README.md                         # 仓库说明；目录树、文件职责、后续新增 skill 的方法都写在这里
└── plugins/
    └── myskills/
        ├── .codex-plugin/
        │   └── plugin.json           # plugin manifest；定义 plugin 身份并指向 skills 目录
        └── skills/
            └── starter/
                └── SKILL.md          # 最小示例 skill；用于验证 plugin 安装和 skill 识别链路
```

## 这几个文件分别做什么

### `/.agents/plugins/marketplace.json`

这是仓库级 marketplace 清单。

Add Market 导入的是这个文件代表的 marketplace，而不是直接扫你的 `skills/` 目录。

它的职责是：

- 给这个 marketplace 起名字
- 列出当前仓库有哪些 plugin
- 告诉 Codex 每个 plugin 在仓库里的路径

### `/plugins/myskills/.codex-plugin/plugin.json`

这是 plugin 的入口 manifest。

Codex 识别一个 plugin，靠的是这个文件，而不是 `README` 或目录名。

它的职责是：

- 声明 plugin 名字和基本信息
- 声明这个 plugin 的 `skills` 在哪里
- 提供安装界面需要的最小展示信息

### `/plugins/myskills/skills/starter/SKILL.md`

这是一个最小 skill。

它的职责不是提供复杂能力，而是验证一件事：这个 plugin 安装后，Codex 能正确识别其中的 skill。

## 现在这套结构怎么理解

最准确的理解是：

1. 你通过 Add Market 导入的是这个仓库里的 marketplace。
2. marketplace 暴露一个可安装的 plugin：`myskills`。
3. 你安装的是 `myskills` 这个 plugin。
4. `myskills` plugin 里打包的 skills 会一起进入 Codex。

所以，在 Add Market 这条链路里，最小安装单位是 `plugin`，不是裸 `skill`。

## 如果以后要新增一个 skill

直接在这个目录下新增即可：

```text
plugins/myskills/skills/<your-skill-name>/SKILL.md
```

约定：

- skill 目录名使用 kebab-case
- `SKILL.md` 里的 `name` 与目录名保持一致最稳妥
- 一个 skill 目录只做一件事，避免把很多不相关流程塞进同一个 skill

## 本地 authoring 和 Add Market 分发的区别

有两种常见用法：

- 本地 authoring：把 skills 直接放进仓库里的 `.agents/skills/`
- Add Market 分发：把 skills 打进 plugin，然后通过 marketplace 安装

这个仓库当前选择的是第二种，因为目标是 Add Market 导入。

如果你只是想在某个项目里本地试验 skill，而不需要 marketplace，确实可以只有 `.agents/skills/`，不需要 plugin。
