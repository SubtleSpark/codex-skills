---
name: session-archive-checker
description: 在用户明确要求“这个会话能归档吗”“归档前检查”或手动调用本 Skill 时使用。检查当前 session 的未完结事项、任务产生的临时文件及相关 Git 变更，只给出 archive 建议和待处理清单，不执行清理或 archive。
---

# Session 归档前检查

判断当前 session 是否已经收尾，而不是审计整个项目。workspace “干净”指当前 session 相关变更已妥善处理，不要求整个 repo 的 `git status` 为空。

本文用 session 表示会话对象，用 subagent 表示受委派的执行角色。subagent session 与 independent session 的定义及检查规则见第 2 节。

使用当前 Agent 环境提供的历史读取、session 查询和 rename 能力；可用能力及其工具名称以当前环境的说明为准。

## 操作边界

除“可归档”时按规则 rename 当前 session 外，只读取可用 session 历史、文件、`git status` 和已有验证结果。不要删除或修改文件、生成报告文件、修改 ignore rules、stage、commit、回滚、切换 branch、stash、fetch、push，也不要 archive session。不要为补齐验证而执行 test、build、format 或其他可能产生写入的命令；将缺失验证列为待处理。需要清理时仅给建议，执行属于另一个任务。

## 1. 确定范围与证据

- 按用户最新明确意图梳理当前 session 的全部任务、验收要求、已完成内容与遗留事项；不要只检查最后一个任务。
- 优先使用用户要求、session 中的工具操作及当前可验证状态。不要仅凭助手说“完成”、session 闲置时间或干净的 `git status` 判定完成。
- 确认任务所在目录及实际 Git worktree；对当前 session 实际操作过的路径识别其所属 Git repo。即使外层 repo 状态干净，或该路径在外层 repo 中属于 ignored 路径，也不能据此认为内部 repo 已收尾；如果该路径属于另一个 Git repo，应单独检查。不要为此递归扫描所有 ignored directories，只检查当前 session 实际触达的路径。
- 只检查有创建记录或关系信息证明由当前 session 发起的相关 session。依据创建操作的实际语义、当前环境提供的关系信息及 session 记录，区分 subagent session 与 independent session，按下一节分别检查。工具名称、共享工作目录或“由当前 session 启动”本身不足以确定 session 类型；不遍历无关历史 session。
- 只使用确实可访问的历史；不要假装能读取全部旧消息，也不要遍历其他 session 记录。历史被截断、workspace 不可访问或检查失败，且缺失证据影响判断时，记录为“需要确认”。
- 用户只要求检查某个子任务或只检查 Git 时，明确标注“局部检查，不能据此判断整个 session 可归档”。未发现明确阻塞项时，整个 session 结论仍为“需要确认”，不能输出“可归档”。

## 2. 检查未完结事项

查找尚未满足的用户要求、约定但未执行或失败的验证、当前范围内的 TODO、阻塞项，以及承诺执行但没有实际完成的动作。已有失败被后续有效验证解决时，不再算遗留项；通过结果必须对应相关版本，不能拿旧版本通过记录替代当前验证。

对当前 session 发起的相关 session，按任务关系判断是否影响当前 session 收尾：

| 类型 | 判定依据 | 收尾检查 |
| --- | --- | --- |
| subagent session | subagent 受委派执行当前任务的一部分时使用的 session；结果由当前任务接收和处理。 | 检查子任务是否完成、结果是否已接收及必要验证是否满足。仍在执行、结果未处理或遗留要求未满足时，当前 session 尚未收尾。 |
| independent session | 另行创建的普通 session，作为独立任务推进，而非当前任务的 subagent 执行单元。 | 检查当前 session 是否仍需等待其必需结果或执行后续动作。已明确移交且当前 session 无剩余责任时，新 session 仍在运行或尚未 archive 不自动阻塞当前 session；用户明确要求本次同时收尾或 archive 该 session 时，按该约定检查。 |

session 类型无法可靠确定，且会影响当前任务是否收尾时，记为“需要确认”，说明缺少的关系或任务证据。

任务完成情况与 archive 状态分开判断。工作已收尾的 subagent session 尚未 archive，不自动阻塞当前 session。是否会连带 archive、是否需要单独 archive，以当前环境的明确说明或实际结果为准；不把某个环境的行为当作通用规则。仅缺少连带 archive 行为或已收尾 subagent session 的 archive 状态信息，不构成任务未完成的证据；有明确 archive 约定未满足，或已知失败需要处理时，列出具体待办。本 Skill 仍只给建议，不执行 archive。

取消、明确放弃、接受限制或已明确移交到其他 issue / PR / session / 人员的工作不算当前 session 未完成，但要说明去向。仅创建新 session 或存在一个 PR 不等于已经移交。暂停、等待条件恢复但仍需当前 session 继续的任务仍未完成。可选优化、未采纳的建议和项目原有 TODO 不自动成为阻塞项。

## 3. 检查文件与 Git 变更

先根据 session 中的创建/修改操作列出相关路径，再做定向检查。检查交付物、一次性脚本、log、test data 及中间产物，包括历史中明确提到的 ignored files 或 repo 外文件；不要递归扫描整个磁盘或全部 build cache。

工具、包管理器、编译器等在其标准 cache 目录中自动维护的 cache、下载包或可复用 runtime 数据默认不纳入检查，也不建议为了 archive 而清理。只有当前 session 显式改变 cache 位置、将这些内容写入项目/任务目录，或出现敏感、异常产物时才纳入检查。

归属判断优先看 session 操作记录、已知起始状态和具体 diff。仅凭文件名、时间戳或 `git status` 不能认定归属。明显无关的修改排除；可能涉及本任务且影响收尾、但归属不明的变更才需要确认。一个文件混有多任务修改时按变更片段判断，不把整个文件都算成当前 session 产物。

在已确认的 repo 中按需使用以下只读命令。`repo` 是实际 repo 路径，`path` 是要检查的 repo 相对路径；不是 Git repo 时跳过，不把“非 Git 项目”本身当作问题。

```bash
git --no-optional-locks -c core.fsmonitor=false -C "$repo" rev-parse --show-toplevel
git --no-optional-locks -c core.fsmonitor=false -C "$repo" status --short --untracked-files=all
git --no-pager --no-optional-locks --literal-pathspecs -c core.fsmonitor=false -C "$repo" diff --no-ext-diff --no-textconv -- "$path"
git --no-pager --no-optional-locks --literal-pathspecs -c core.fsmonitor=false -C "$repo" diff --cached --no-ext-diff --no-textconv -- "$path"
git --no-optional-locks -c core.fsmonitor=false -C "$repo" check-ignore -v -- "$path"
```

`check-ignore` 返回 1 表示没有匹配规则，不是检查故障。`git diff` 不显示 untracked files 内容，需要时只读取相关文件；避免运行外部 diff、textconv 或 file watcher。状态已被后续操作改变时，以当前证据为准。

逐项给出处置建议及依据：

| 建议 | 判断条件 |
| --- | --- |
| 保留 / 已妥善处理 | 正式交付物已按约定保存，或用户明确要求保留当前状态（包括未 commit 状态）。不能自行假设用户打算稍后处理。 |
| 建议 commit | 当前 session 的有效 source code、test 或文档应受版本控制，但仍未 commit 且没有明确的保留理由。staged 变更不等于 commit。 |
| 建议删除 | 有证据属于当前 session、用途已结束的一次性产物；生成文件和 untracked files 不必然是垃圾。 |
| 建议加入 ignore rules | 应持续保留在本地、按项目约定不应入库的生成物，且没有适用的 ignore rules。tracked files 不能仅靠新增 ignore rules 解决。 |
| 需要确认 | 影响本次收尾，但用途、归属或最终处置无法可靠确定。 |

ignored 状态不等于无需清理：不再需要的一次性 log 即使处于 ignored 状态，也可能是遗留项。相反，有明确保留用途的 ignored 产物不必删除。commit、push 或 PR merge 不是统一要求，按本次约定判断。

## 4. 判定与报告

按以下优先级输出当前 session 的唯一整体结论：

1. **暂不建议归档**：存在明确未完结事项，或明确待处置的当前 session 文件 / Git 变更。
2. **需要确认**：没有明确阻塞项，但存在影响结论的证据缺口、归属/处置疑问，或仅完成局部检查。
3. **可归档**：当前 session 范围已检查，未发现上述阻塞项或重要不确定项。

明确阻塞项与不确定项并存时使用“暂不建议归档”，并分别列出两类事项。遇到混合修改、局部检查或 ignored 产物等容易误判的情况，按需读取 [边界案例](references/examples.md)，不要无条件加载或扩展成全 repo 审计。

结论为“可归档”且当前环境支持 session rename 时，将当前 session 标题改为 `可归档 | <原标题>`；已有该前缀时不要重复添加。只 rename 当前 session，不执行 archive。若环境不支持 rename，或 rename 失败，不改变 archive 结论，在“待处理”中简短提示手动加前缀。

采用以下简短格式；结论始终放在最前，只列影响判断的信息，路径、相关操作或验证结果应能支撑结论。不要输出凭证、令牌或无关文件内容。

```markdown
## 结论
**可归档 / 暂不建议归档 / 需要确认**
依据：<用 1–2 句话说明支撑结论的关键事实，例如任务完成情况、相关文件 / `git status` 或证据缺口；不复述检查过程，不重复待处理清单。>

## 待处理
<没有则写“无”；有则逐项列出：事项或路径 — 观察到的状态 — 建议 / 待确认的问题>

## Git / 文件
<当前 session 相关变更及已确认的保留理由；未检查写“未检查”，不能写成“未发现”>
```

只有有助于解释结论时才补充“不纳入本次判断”，简述明显无关的修改。报告建议，不声称已经执行清理，也不为了填模板制造待办。
