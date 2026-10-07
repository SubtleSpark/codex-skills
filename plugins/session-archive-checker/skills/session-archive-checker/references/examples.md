# 边界案例

以下场景用于校准判断，不是已执行的 test report。未注明例外时，假设当前 session 的其他要求、文件和 Git 处置均已检查并收尾。

| 场景与证据 | session 整体结论 | 报告要点 |
| --- | --- | --- |
| 本次修改已 commit，约定验证已通过；起始记录证明剩余脏文件属于其他任务。 | 可归档 | 排除无关修改，不要求全 repo 干净。 |
| `git status` 为空，但当前 session 早先约定的 integration test 仍失败；后来的另一个任务已完成。 | 暂不建议归档 | 早先任务不会因最新任务完成而消失。 |
| session 记录明确创建了已无用途的 `debug.log`，它仍存在且处于 ignored 状态。 | 暂不建议归档 | 建议删除；ignored 状态不代表生命周期已结束。 |
| 用户要求生成并保留 `.tmp/report.json`，文件已交付，现有 ignore rules 已覆盖。 | 可归档 | 保留交付物，不把生成文件当垃圾。 |
| 正式 source code 尚未 commit；用户明确要求自行看 diff 后决定 commit，任务未要求 Agent commit。 | 可归档 | 说明有意保留未 commit 状态，不擅自 commit。 |
| 正式 source code 尚未 commit，且没有用户选择或 repo 约定支持继续保留该状态。 | 暂不建议归档 | 建议处理本次有效变更，不能臆测“用户之后会 commit”。 |
| `App.java` 混有多个任务修改，无法确认属于当前 session 的变更是否已收尾。 | 需要确认 | 不建议整体 commit、删除或回滚该文件。 |
| 一个无关目录中的文件只有时间戳接近当前 session；无其他任务关联证据。 | 可归档 | 不仅凭时间戳认领，也不让无关疑问阻塞 archive。 |
| 一个可能是本任务必需的 migration 文件缺乏来源和最终处置记录。 | 需要确认 | 指出具体交付风险及需要确认的问题。 |
| 用户只让检查任务 A 或 Git，检查通过，但同一 session 的任务 B 未检查。 | 需要确认 | 只能说该部分未发现问题，不能据此判定整个 session 可归档。 |
| 已知当前 session 有遗留修改，但现在无法访问对应 worktree，或关键历史缺失。 | 需要确认 | 写明证据缺口，不把“查不到”说成“已干净”。 |
| 用户要求实现并提 PR 后交其 review；PR 已创建、工作已移交，当前 session 无剩余动作。 | 可归档 | 不要求 PR merge；若约定的 PR 尚未创建则暂不建议归档。 |
| 当前是非 Git 目录；请求已完成，交付物明确保留，无临时文件遗留。 | 可归档 | 跳过 Git，不把没有 repo 当成检查失败。 |
| test 明确未完成，同时存在归属不明的本任务候选文件。 | 暂不建议归档 | 确定阻塞项优先，不确定项单列；不执行 test 或清理。 |
| subagent 已完成，结果已接收且必要验证通过；subagent session 尚未 archive，当前环境明确支持连带 archive。 | 可归档 | 不要求先手动 archive subagent session；按当前环境说明给出 archive 建议。 |
| subagent 工作已收尾；当前环境需单独 archive，或无法确认是否支持连带 archive；本次没有额外 archive 约定。 | 可归档 | 如实说明 archive 方式或能力缺口；不将已收尾 subagent session 的 archive 状态当作任务阻塞。 |
| subagent 已停止运行，但其必需结果尚未接收，或约定验证仍未通过。 | 暂不建议归档 | 停止运行不等于子任务已收尾，指出未处理的结果或验证。 |
| 已另开 independent session，用户明确将后续工作移交给它；当前 session 无剩余动作，新 session 仍在运行。 | 可归档 | 说明移交去向；独立任务可继续推进，不能套用 subagent session 的生命周期。 |
| 已另开 independent session，当前 session 仍需等待它的必需结果并完成集成或交付。 | 暂不建议归档 | 创建新 session 没有解除当前 session 的后续责任。 |
| independent session 工作已完成但尚未 archive；用户明确要求本次同时 archive 该 session。 | 暂不建议归档 | 按用户的明确约定列出待办，不执行 archive，也不假定它会连带 archive。 |
| 有当前 session 发起新 session 的记录，但无法判定其任务关系，也无法确认必需结果是否仍需当前 session 处理。 | 需要确认 | 说明具体关系与任务证据缺口，不根据工具名称猜测类型。 |
| session 工作已收尾，但当前环境不支持 rename，或 rename 调用失败。 | 可归档 | 提示手动加标题前缀，能力限制不改变 archive 结论。 |
