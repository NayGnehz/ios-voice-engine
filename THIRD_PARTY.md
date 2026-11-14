# 来源与第三方组件

## 社区方案基础

本项目以“苹果中文语音库”独立 APK 民间方案为基础，**不是从 Apple 官方引擎源码移植**。

- 原始分享页：[天坦社区：推荐苹果中文语音库普通版加共存版](https://bbs.tatans.cn/topic/25344)，发布者：落天。
- 原帖的[下载入口](https://bbs.tatans.cn/topic/25344)仍以原帖为准。本项目没有找到可验证的原始封装源码仓库，不能把分享者认定为全部组件的原作者。
- 实际分析输入：`苹果中文语音库_clone.apk`；SHA-256：`3768e8f7f1c7cefc169cc5fbb5354dbd8a7cd8efdddfe4b45f8486e4d767946f`。
- 五种中文音色来自该输入 APK。界面名称采用原包：莉莉（Li-Li）、李木（Li-Mu）、甜甜（Tian-Tian）、婷婷（Ting-Ting）、舒宇（Yu-Shu）。原帖的中文写法略有不同，以 SDK 音色标识为准。

## ARM64 运行库

- 来源项目：[Igor B. Poretsky / SmartVoice](https://poretsky.github.io/android/smartvoice/)。
- 发布仓库：[poretsky/poretsky.github.io](https://github.com/poretsky/poretsky.github.io)。
- 实际使用：[SmartVoice 4.1.16 APK](https://github.com/poretsky/poretsky.github.io/releases/download/android/SmartVoice-4.1.16.apk)。
- 输入 APK SHA-256：`8660625f39035c668a55c37e4c5c6086d499d1c3b2c8ca3c29465c54b537a4ed`。
- 使用其中的 `lib/arm64-v8a/libNuanceVocalizer.so`，并修复已定位的重复关闭文件描述符问题。没有禁用 Android FDSAN，也没有修改授权检查。
- 4.1.16 是本构建的固定输入；SmartVoice 5.x 改为聚合已安装引擎，不能用最新版代替本输入。

## 开源范围

仓库 MIT 许可只覆盖本项目新增的 Android TTS 兼容层、补丁脚本、构建脚本、测试和文档。原 Java SDK 字节码、Nuance 原生运行库和语音模型并无因此转为 MIT 或完整开源。

第三方组件的权利归各自权利人；本项目未找到这些二进制和语音数据的明确开源许可，社区下载可用不代表获得了开源授权。仓库不包含原始 APK、SDK DEX、运行库、语音数据及签名私钥；构建者须自行提供可合法使用的固定输入。Release 安装包含这些第三方组件，不能称为全栈开源合成引擎。

“iOS 声音引擎”是社区项目名称，不表示 Apple、Nuance 或 SmartVoice 作者为本项目背书，也不保证音色与任一特定 iOS 版本相同。
