# 逐版修改历史（事后重建）

本次于 2026-11-14 根据本地留存材料重建。Git 提交使用实际重建时间，没有伪造过去的作者日期。既有发布历史和 `v1.5.0` Release 均保留。

历史分支：[history/reconstructed-20261114](https://github.com/NayGnehz/ios-voice-engine/commits/history/reconstructed-20261114)。历史提交通过合并接入 main，但不将旧代码覆盖到当前版本。每个阶段有独立提交和 `reconstructed/` 标签，可以查看差异或检出。

| 序号 | 阶段 | 材料状态 | 提交 | 标签 |
|---|---|---|---|---|
| 00 | 社区原包基线记录 | 仅留存 APK 身份，缺完整源码 | [dc7666a](https://github.com/NayGnehz/ios-voice-engine/commit/dc7666a3fc2ed2637d2ab499ce8126f6e253f6fb) | [reconstructed/00-community-baseline](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/00-community-baseline) |
| 01 | ARM64 运行库替换试验 | 留存源码/脚本 | [edcdf72](https://github.com/NayGnehz/ios-voice-engine/commit/edcdf72f21dbfa502a64420d711c23797fd77e05) | [reconstructed/01-arm64-trial](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/01-arm64-trial) |
| 02 | 单音色无图标与重复 close 修复 | 留存源码/脚本 | [5fd4596](https://github.com/NayGnehz/ios-voice-engine/commit/5fd45961191780cdd931796e4deabbf09745d326) | [reconstructed/02-fd-close-fix1](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/02-fd-close-fix1) |
| 03 | 修复二：匹配定点 BET3 模块 | 留存源码/脚本 | [45d0d75](https://github.com/NayGnehz/ios-voice-engine/commit/45d0d7539c0c112b6e850f049f7d5e2fc5ab8315) | [reconstructed/03-bet3-fix2](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/03-bet3-fix2) |
| 04 | 压缩安装包且保留资源 FD 访问 | 留存源码/脚本 | [fe37e8e](https://github.com/NayGnehz/ios-voice-engine/commit/fe37e8e4d7e12fc9e8a67efaa214cc0c6d47130d) | [reconstructed/04-compressed](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/04-compressed) |
| 05 | 启动就绪修复 1.1 / 诊断 1.3 | 仅留存 APK 身份，缺完整源码 | [8c77b82](https://github.com/NayGnehz/ios-voice-engine/commit/8c77b8234fd9149a1342ca63369fe9c7f80859e9) | [reconstructed/05-ready-initial](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/05-ready-initial) |
| 06 | 启动就绪 1.2 / 诊断 1.4 | 留存源码/脚本 | [2d5521d](https://github.com/NayGnehz/ios-voice-engine/commit/2d5521d2d2b2b39816dde5cc130013dbc768f168) | [reconstructed/06-ready-v2](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/06-ready-v2) |
| 07 | 逐词位置回调 1.3 / 诊断 1.5 | 按已记录变更重建 | [8e1993a](https://github.com/NayGnehz/ios-voice-engine/commit/8e1993a588acb3eeef360aa05f01d47685e42818) | [reconstructed/07-word-highlighting](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/07-word-highlighting) |
| 08 | 连续倍速 1.4 / 诊断 1.6 | 留存源码/脚本 | [120b6ce](https://github.com/NayGnehz/ios-voice-engine/commit/120b6ce93082f7e439fb59a35a50dd92fd8c8f33) | [reconstructed/08-rate-fix](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/08-rate-fix) |
| 09 | 五音色独立包 1.5.0 已发布源码 | 已发布源码快照 | [35944e2](https://github.com/NayGnehz/ios-voice-engine/commit/35944e2ed9492d76e9cb40e975dbf63bfe724017) | [reconstructed/09-five-voices-1.5.0](https://github.com/NayGnehz/ios-voice-engine/tree/reconstructed/09-five-voices-1.5.0) |

## 恢复边界

- 00：社区原包没有可验证的开放源码，仅记录输入版本、大小和 SHA-256。
- 02：现存脚本已将单音色、无图标与重复 close 修复合在一起，不凭猜测拆分。历史签名密码参数改为环境变量引用，原文与归档 SHA-256 分别记录。
- 05：1.1-ready / 诊断 1.3 的 APK 仍在，但完整源码已被下一版覆盖。此提交只保存真实的版本身份，不伪造一套源码。
- 07：从保留的后续源码和已记录的变更逆向还原倍速调用、版本标签、诊断说明和打包文件名；每项变换均有记录。这不是逐字原件，也未声称能重新产出同一签名 APK。旧版 verify.py 未单独恢复。
- 09：src、tools、tests、音色配置和构建入口与已发布的 1.5.0 对应提交一致；历史分支不引入 GitHub 发布工作流。

每个阶段的 `RECONSTRUCTION.json` 记录材料来源、文件校验和、变换和留存 APK 身份。[完整索引](history-index.json) 汇总全部阶段。APK、SDK DEX、原生库、音色数据、私钥和私人诊断日志均未放入历史。旧脚本沿用当时的路径假设，只作历史追溯，不是当前推荐构建入口。

本轮只验证 Git 图、归档内容、APK 身份和来源校验和，没有重新执行旧构建、测试旧 APK 或改变既有版本的实机验证结论。之后的开发应直接按修改独立提交，不再靠事后重建。
