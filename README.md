# iOS 声音引擎

Android ARM64 离线中文 TTS 兼容项目。基于社区“苹果中文语音库”独立 APK 方案，使用原有 Vocalizer 音色数据和兼容的 ARM64 运行库，提供五种可共存的单音色安装包。

**不是 Apple / Nuance 官方项目，也不是 Apple 官方语音引擎的源码移植。** 本仓库公开本项目新增的兼容层、构建和补丁代码；第三方运行库和语音模型不因此变为开源。

## 下载与使用

到 [Releases](https://github.com/NayGnehz/ios-voice-engine/releases) 下载需要的音色，不必全部安装。

| 文件中的音色代号 | 音色 | 系统引擎名称 | 包名 |
|---|---|---|---|
| `tingting` | 婷婷 / Ting-Ting | iOS-婷婷 | `com.android.apple64ttsx` |
| `lili` | 莉莉 / Li-Li | iOS-莉莉 | `com.android.apple64ttsx.lili` |
| `limu` | 李木 / Li-Mu | iOS-李木 | `com.android.apple64ttsx.limu` |
| `tiantian` | 甜甜 / Tian-Tian | iOS-甜甜 | `com.android.apple64ttsx.tiantian` |
| `yushu` | 舒宇 / Yu-Shu | iOS-舒宇 | `com.android.apple64ttsx.yushu` |

安装后，在系统“文字转语音 / 文本转语音 / TTS”设置中直接选择对应引擎。各包没有桌面启动图标，也没有额外音色选择页；调用方选择系统引擎即可。TTS 设置路径随手机系统不同。

仅包含 `arm64-v8a`；最低 Android 5.0，当前 targetSdk 为 32。婷婷与本项目之前的同签名版本可覆盖更新；其他四个包可并列安装。与更早社区原包的签名不同，不承诺覆盖社区原包。

## 本项目的修改

- 为旧封装接入 ARM64 运行库，修复重复关闭文件描述符的问题，保留 Android FDSAN 检查。
- 让旧 FIX3 / HMEX 音色数据选择匹配的定点 BET3 模块；不修改语音模型数据。
- 等待初始化和音色加载成功后再合成，避免启动首句无声。
- 恢复 SDK 原生词标记分发，并转发 Android `rangeStart`。高亮需要调用方接收位置回调。
- 倍速直接传递 Android 请求百分比，限制在 50～400；去掉旧公式在 1.0 附近的跳变。
- 输出格式为 22050 Hz、PCM16、单声道；每个 APK 只包含一个中文音色。
- 用标准 TTS 数据检查 Activity 替代原来的音色选择流程。

## 来源基础与原项目地址

1. **最初推荐并采用的民间方案**：[天坦社区 · 落天：推荐苹果中文语音库普通版加共存版](https://bbs.tatans.cn/topic/25344)。实际输入为用户从该路线取得的 `苹果中文语音库_clone.apk`。该链接是社区分享页；没有找到可验证的原封装源码仓库，不能将它冒称为开源上游。
2. **ARM64 运行库来源**：[SmartVoice 项目](https://poretsky.github.io/android/smartvoice/)，作者 Igor B. Poretsky；[发布仓库](https://github.com/poretsky/poretsky.github.io)，固定使用 [4.1.16 安装包](https://github.com/poretsky/poretsky.github.io/releases/download/android/SmartVoice-4.1.16.apk) 中的 Vocalizer ARM64 库。5.x 已改为引擎聚合器，不能替代此构建输入。

完整来源、输入校验和和许可范围见 [THIRD_PARTY.md](THIRD_PARTY.md)。语音名称不能证明它与某个特定 iOS 版本的声音完全相同。

## 构建

不在 Git 中分发原始 APK、SDK DEX、原生库、音色数据或私钥。自行准备有权使用且与文档 SHA-256 一致的两个 APK 输入。需要 Python 3.12、JDK 17、Android SDK Build Tools 36.0.0 与 Android API 37 平台。

```powershell
$env:VOICE_STORE_PASSWORD = '<你的签名库密码>'
python tools/build.py `
  --original-apk '<原社区APK>' --runtime-apk '<SmartVoice-4.1.16.apk>' `
  --sdk '<Android SDK目录>' --jdk '<JDK目录>' `
  --work '<源码目录外的构建缓存>' --output '<源码目录外的产物目录>' `
  --keystore '<现有签名库>' --alias '<密钥别名>'
```

脚本不会下载输入或工具，SHA-256 不匹配会中止。构建者使用自己的签名将无法覆盖本项目发布签名的安装包；**不要把签名密钥提交到 GitHub**。五个包的版本由 `tools/build.py` 统一控制，音色配置位于 `voices.json`。

本地共享便携环境可使用 `eng/build.ps1`；它从祖先目录查找 `_env` 并复用固定工具，缓存放在 `_cache/projects/ios-voice-engine/`。不修改共享环境登记表。

## 验证及限制

```powershell
python tools/test.py --jdk '<JDK目录>' --work '<源码目录外的测试缓存>'
```

GitHub Actions 自动运行倍速、UTF-16 词范围和就绪状态回归；该流程不含私有第三方输入，不会自动生成完整 APK。发布构建另有 APK 签名、ZIP/ELF 对齐、单音色资源和模型字节检查。

1.5.0 为预发布：五个 APK 已完成构建和静态检查，尚未对本版本做手机试听、覆盖安装和逐词时间同步验证。新增莉莉、李木、甜甜、舒宇尤其需要实机确认。之前婷婷的运行记录不能替代新增音色的验证，也不保证未来 Android 版本兼容性。

词标记可能包含标点；阅读器不能直接将任意高亮范围当作暂停恢复锚点。见 [对接说明](docs/INTEGRATION.md)。

## 许可

新增源代码使用 [MIT](LICENSE)；第三方组件和语音数据的权利单独保留，见 [来源及第三方说明](THIRD_PARTY.md)。
