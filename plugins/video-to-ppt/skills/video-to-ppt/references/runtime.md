# 运行、稳定性与去重参考

## 环境

使用Python 3.12+和已有正常环境，不修改全局Python。基础依赖为NumPy、Pillow、ReportLab、pypdf；默认SSIM阶段需要scikit-image（脚本声明固定0.26.0及其SciPy依赖）；PPTX另需python-pptx。新视频采样需要ffmpeg、ffprobe，复用完整采样不需要重新解码。

稳定性和最后去重均需要SSIM，缺依赖会在创建输出前报错；只有同时显式--skip-stability --skip-ssim才完全不需要该依赖。脚本不会静默跳过。依赖安装使用正常来源与独立环境。PEP 723依赖列在auto_pdf.py开头。

## 参数

| 参数 | 默认 | 含义 |
|---|---|---|
| --interval | 5 | 完整固定采样网格，单位秒，正数 |
| --stability-threshold | 0.995 | 当前帧与原始前、后邻帧SSIM均≥此值才合格 |
| --skip-stability | 不跳过 | 显式跳过稳定性，仍记录所有样本的旁路状态 |
| --hash-mode | phash-dhash | 两种hash的AND条件；显式phash保留旧单哈希模式 |
| --hash-distance | 1 | 64位pHash汉明距离≤阈值 |
| --dhash-distance | 2 | 同一个已保留代表的64位dHash距离≤阈值 |
| --ssim-mode | dedup | 默认实际去重；显式audit仅保留矩阵和报告 |
| --ssim-threshold | 0.995 | SSIM≥阈值才合并到第一个已保留代表；越低越激进 |
| --ssim-width / --ssim-height | 960 / 540 | 稳定性及后置SSIM共用的灰度尺寸，每边至少11像素 |
| --ssim-review-threshold | 0.995 | 审计报告突出显示的相似对门槛，不参与删图 |
| --skip-ssim | 不跳过 | 只跳过后置SSIM阶段；不跳过稳定性；旧--skip-ssim-audit保留为别名 |
| --format | pdf | pdf、pptx或both |
| --samples-manifest | 无 | 必须含匹配的source_video_sha256；拒绝缺哈希、候选子集、缺样、乱序或来源不一致 |
| --title / --source-url | 视频名 / 空 | 文档元数据及公开来源，不添加新封面 |

pHash：灰度LANCZOS缩为32×32，DCT-II左上8×8系数，与全部64个系数的中位数比较二值化，按行记录64位。dHash：灰度LANCZOS缩为9×8，逐行判断右像素是否大于左像素，记录64位。距离越大越激进；0也只表示感知hash相同，不是像素或内容相同。

新采样依据视频流时长，不用可能被更长音轨拉长的容器时长。流元数据缺时长时仅读取视频包时间戳范围，不额外解码帧。先将视频PTS归零，再按固定网格采样；时间戳相对视频首帧。实际图数必须等于ceil(视频流时长/采样间隔)，否则保留诊断并停止，不写成完整采样。duration_basis记录来源，自生成清单可按同一网格复用。

首次与复用均按实际采样图尺寸确定PDF/PPT页面几何；FFmpeg自动应用显示旋转后，图像尺寸可能与视频编码尺寸相反。

## 原始邻域稳定性

先沿完整原始网格计算N−1个相邻SSIM分数，每图预处理一次并用两个数组滚动缓存，不算全矩阵。样本i只有在SSIM(i−1,i)和SSIM(i,i+1)均≥稳定阈值时合格；两个比较都使用原始直接邻居。第一和最后一帧缺双侧邻居，默认拒绝。少于3张、或没有任何合格样本时保存stability.json诊断后停止，不隐式回退。

度量与后置SSIM共用下述尺寸及参数。每对仅比较一次，两个端点记录共享分数；缺邻居分数为null。稳定性、双哈希、SSIM分别计时。随后仅对合格样本计算hash，并按原时间顺序比较已保留代表；后置SSIM仍只比较hash代表。导出的“First qualified frame”是首次合格帧时间，不是页面开始出现时刻。

5秒间隔的三个相似采样点不证明中间连续10秒不变。短于约两格、只落在两个采样点、持续动画或变化较大的真实页可能被排除；首尾内容若没有内侧合格代表也可能消失。这是选择稳定性规则的取舍，不通过后期AI补页。参数变化可能改变代表，须用实际映射核对替代内容。

## SSIM第二阶段

只在过滤后的N张代表上计算N×N对称矩阵，实际比较N(N−1)/2对；对角线设1，另一半镜像写入。不会对全部原始采样图做矩阵。按输入首次顺序扫描，SSIM≥阈值则归入第一个已保留代表，后者始终保留首次原图。被丢弃图不能成为后续代表，不用传递闭包，也不选最高分的更晚代表。原图预处理一次后缓存，串行比较；报告实测预处理及比较耗时。

参数：Pillow L灰度、LANCZOS缩放、float32值0..255；data_range=255，channel_axis=None，gaussian_weights=True，sigma=1.5，win_size=11，use_sample_covariance=False，K1=0.01，K2=0.03。这是全图平均分数，公共模板、白底和缩图会影响分数。

## 输出与验证

- slides.pdf / slides.pptx：按保留顺序逐页放置首次源图，页脚写时间和采样编号。
- samples/：新采样时的全部原图；复用时直接引用既有图，不复制或改写。
- stability.json：全部原始帧的前后ID/SSIM、资格、拒绝原因、合格索引、N−1对比较数和计时。
- hash-samples.json / hash-pages.json：原始序列中合格帧的双哈希归属与代表；清单仍含全部原始帧，被稳定性排除项没有hash及代表。
- samples.json：全部采样的最终代表、页号及两个阶段归属；hash_representative_sample_id为第一阶段代表，ssim_input_sample_id为送入SSIM的hash代表。ssim_score_hash_rep_to_final_rep是hash代表与最终代表的分数，不能当作每张原始图都计算过SSIM。最终代表的hash距离可能超过第一阶段阈值，原hash距离另存phash_distance_to_hash_representative等字段。
- pages.json：代表列表、源图SHA256及每次出现的映射。
- ssim-decisions.json：每个hash代表的最终归属、分数、首次时间及实际合并对。
- ssim-matrix.csv：按hash阶段页号/采样ID标明行列的完整矩阵，顺序对应hash-pages.json。
- ssim-audit.json：矩阵、参数、版本、行列来源顺序、突出显示的相似对、第二阶段选图及计时；显式跳过时记录原因。
- ssim-audit.html：可离线查看的矩阵和相似对链接，不做内容判断。
- run.json：实际脚本路径/SHA256、插件版本、输入来源、参数、实测耗时、导出验证与审计状态。
- extraction.log：仅新视频解码时生成。

自动PDF/PPTX验证检查实际导出文件中的页数、图片关联和顺序源像素一致；这不等于语义无重复或无漏页。交付时离线渲染最终文件查看实际显示，不使用易阻塞的GUI完成批处理。

第二阶段仅改变导出清单，不删除原始图片。矩阵相似并不等于语义相同，尤其小字、局部数字与转场仍需核查；不为得到某个页数调阈值。

所有清单采样ID与时间都指原始网格，不重新编号。samples.json中status=stability_rejected的样本page/representative为null，stability保留两侧分数和原因，不虚构去重归属。automatic_merges只统计合格样本的去重，stability_rejected_samples单独统计；两者相加再加retained_pages应等于原始样本总数。
