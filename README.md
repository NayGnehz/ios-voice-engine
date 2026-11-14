# iOS 声音引擎 · 历史重建 06

启动就绪 1.2 / 诊断 1.4

本提交于 2026-11-14 事后重建，提交时间为实际重建时间，不是原开发时间。

材料状态：`retained-source`。保留的 readiness 源码含就绪等待和进程独立日志；不导入用户诊断日志、设备身份或运行采样。

保留的源码/脚本位于 `research/`；APK 仅记录身份与校验和，不上传第三方二进制、音色数据、私钥或私人日志。历史脚本沿用当时的目录和工具假设，仅供追溯，未在本轮重新构建或验证旧 APK。查阅 `RECONSTRUCTION.json` 了解材料清单、变换及实际留存 APK 版本。

社区基础：https://bbs.tatans.cn/topic/25344
ARM64 运行库发布来源：https://github.com/poretsky/poretsky.github.io
