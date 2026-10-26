---
name: video-to-ppt
description: 将课程、讲座或录屏中的原有课件固定间隔采样，以原始前后邻帧筛稳定画面，再经双哈希和SSIM去重，按首次合格顺序导出图片型PDF或PPT并保留完整映射。
---

# 视频课件还原

默认由脚本执行：**每5秒采样全部画面 → 当前图与原始前、后邻图的960×540灰度SSIM均≥0.995才合格 → 64位pHash≤1且64位dHash≤2才合并 → 保留页SSIM≥0.995再次合并 → 按首次合格顺序输出PDF**。首尾缺双侧邻居，默认排除。邻帧必须取原始时间序列，不能跳过已过滤图。去重匹配同一个已保留代表，被丢弃图不能连锁归并。

SSIM按阈值实际去掉后出现的重复页，并保存矩阵和决定。两个阶段各自只匹配当时已保留代表，不对矩阵相似对做传递闭包。AI可核查代码和成品，不能用语义判断、标题过滤、选更清晰的后图等方式改动自动选页清单。

## 执行

优先复用本地视频及完整固定采样。URL输入时才读[下载参考](references/download.md)；浏览器登录态读取须已有授权。使用具备依赖的Python 3.12+；缺依赖会明确失败，不能静默略过SSIM阶段。参数、依赖和输出定义见[运行参考](references/runtime.md)。

以下路径相对本Skill目录，输出必须是新目录：

~~~sh
/absolute/python -B scripts/auto_pdf.py /absolute/video.mp4 /absolute/new-output
~~~

已有完整采样时，追加以下参数避免重新解码；清单必须含匹配的来源视频SHA256，会校验完整采样网格并重新计算两hash：

~~~sh
--samples-manifest /absolute/existing/samples.json
~~~

需要PPT时用--format pptx，或--format both同时导出；默认PDF。页脚时间是首次合格帧时间，并非内容开始时刻。--stability-threshold调整稳定性；--skip-stability显式跳过并记录。--ssim-threshold调整最终去重；--skip-ssim只跳过最终SSIM阶段，--ssim-mode audit仅报告不删页。少于3帧或无合格帧会保存诊断并停止，不隐式放行。

## 验收与限制

核对稳定性及两阶段去重参数、首次合格顺序和实际入口。stability.json保存全部原始邻接得分及资格；hash-samples.json/hash-pages.json保存双哈希阶段；samples.json/pages.json保存最终归属，稳定性拒绝项页号与代表为空；ssim-decisions.json记录最终合并。SSIM矩阵顺序对应hash-pages.json。自动检查导出源像素映射；交付前离线渲染查看实际页序和显示，不能把结构检查当作视觉检查。

5秒网格和双侧稳定性会漏掉短展示或持续变化的真实页；三个相隔5秒的采样点相似不证明中间连续10秒稳定。提高哈希阈值或降低去重SSIM阈值可能误合并不同内容；后置SSIM不能恢复前阶段已拒绝的页面。保留所有原图、原因和旧产物，不按目标页数手工删图。
