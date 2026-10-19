# 下载参考

先检查当前 `yt-dlp --help` 和实际站点返回；这些命令是模板。`URL`、`OUT`、`LANG` 是已确认的输入和独立输出目录，Shell 参数须正确引用。涉及登录时只在授权后追加 `--cookies-from-browser edge` 或用户指定浏览器，不默认读取 Edge。

```sh
yt-dlp --ignore-config --no-playlist --list-formats "$URL"
yt-dlp --ignore-config --no-playlist --list-subs "$URL"
```

人工字幕优先，平台 AI 字幕或自动字幕可以使用，但保留类型标签。语言代码从清单选取，B 站某些视频使用 `ai-zh`，不能假定所有站点都用 `zh`。排除弹幕和 `live_chat`。

视频与字幕分开调用；不要把可选字幕失败变成整条流水线失败：

```sh
yt-dlp --ignore-config --no-playlist --no-overwrites \
  -f 'bv*[height<=1080]+ba/b[height<=1080]' \
  -P "$OUT" -o '%(title).160B [%(id)s].%(ext)s' -- "$URL"

yt-dlp --ignore-config --no-playlist --no-overwrites --skip-download \
  --write-subs --write-auto-subs --sub-langs "$LANG" --sub-format 'srt/vtt/best' \
  -P "$OUT" -o '%(title).160B [%(id)s].%(ext)s' -- "$URL"
```

检查合并后的实际文件格式，不仅看扩展名。默认格式选择不存在时，回看可用清单再选择，不悄悄提高画质或把无声轨当完整视频。需要 MP4 时优先兼容编码的流；重封装不能把任意编码变成兼容编码，不默认进行昂贵转码。

字幕没有生成文件时即使退出码为零也要报告缺失。若字幕检测因登录受限而未完成，报告“未取得字幕”，不要断言平台没有字幕。字幕错误跳过后继续；视频的权限、网络或站点错误则如实停止依赖视频的步骤，不沿用某个历史 issue 的特定修复。

来源与参数依据：[yt-dlp 官方 README](https://github.com/yt-dlp/yt-dlp#readme)、[FFmpeg 命令行](https://ffmpeg.org/ffmpeg.html)、[FFmpeg filters](https://ffmpeg.org/ffmpeg-filters.html)。站点行为按执行时实测。
