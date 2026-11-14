# iOS 声音引擎 1.5.0（预发布）

五个中文音色分别成包：婷婷、莉莉、李木、甜甜、舒宇。均为 ARM64 离线系统 TTS，无桌面图标，安装后可在系统 TTS 设置中按名称直接选择。按需安装，五包可以共存。

- 婷婷保留 `com.android.apple64ttsx` 和既有签名，versionCode 从 8 提升为 9；其他四个使用独立包名。
- 保留音色就绪等待、文件描述符修复、原生词位置回调，以及连续倍速映射。
- 每包仅包含对应音色的资源，模型数据不变。四种新增音色也使用匹配的定点 BET3 模块。
- 新增公开兼容层源码、输入校验、五包构建脚本和 GitHub Actions 回归检查。

**来源基础**：[天坦社区 · 落天“苹果中文语音库普通版加共存版”](https://bbs.tatans.cn/topic/25344)。这是最初推荐采用的第一种民间方法；原帖是分享页，未找到可验证的封装源码仓库。

**ARM64 运行库来源**：[SmartVoice / Igor B. Poretsky](https://poretsky.github.io/android/smartvoice/)，[发布仓库](https://github.com/poretsky/poretsky.github.io)，固定使用其中的 [4.1.16 APK](https://github.com/poretsky/poretsky.github.io/releases/download/android/SmartVoice-4.1.16.apk)。本项目不是 Apple / Nuance 官方产品。

本仓库 MIT 许可仅覆盖新增兼容层、脚本、测试和文档；安装包中的原 SDK、运行库和音色数据属于第三方组件，不因本次发布变为开源。详情见随附 `THIRD_PARTY.md`。

已验证：Java/D8 构建、APK 签名、ZIP/ELF 对齐、固定输入校验、单音色资源和模型字节不变、倍速/词范围/就绪回归。

未验证：本版本在手机上的实际播放、覆盖安装、逐词回调与时间同步。四种新增音色为实验包，不将之前婷婷可用的结果外推为其他音色已实测。遇到问题请注明音色、版本、系统版本及具体操作。

附件 `SHA256SUMS.txt` 可用于核对下载完整性；`build-evidence.json` 记录各包版本、包名、输入来源校验和及验证边界。五个安装包的共同签名证书 SHA-256：

`3c50a4146e6b022c907fbb11a99919c31169857505b830ccd20df44d6ca1d569`
