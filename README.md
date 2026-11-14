# iOS 声音引擎 · 历史重建 07

逐词位置回调 1.3 / 诊断 1.5

本提交于 2026-11-14 事后重建，提交时间为实际重建时间，不是原开发时间。

材料状态：`documented-reconstruction`。词回调代码保留；倍速调用、版本标签、诊断说明和打包文件名按已记录的后续变更逆向还原。不是完整原始文件逐字备份，不声称重现同一个签名 APK；旧版 verify.py 未单独恢复。

保留的源码/脚本位于 `research/`；APK 仅记录身份与校验和，不上传第三方二进制、音色数据、私钥或私人日志。历史脚本沿用当时的目录和工具假设，仅供追溯，未在本轮重新构建或验证旧 APK。查阅 `RECONSTRUCTION.json` 了解材料清单、变换及实际留存 APK 版本。

社区基础：https://bbs.tatans.cn/topic/25344
ARM64 运行库发布来源：https://github.com/poretsky/poretsky.github.io
