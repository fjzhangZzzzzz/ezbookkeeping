# android-v0.1.10

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionCode：`12`
- 基于：`android-v0.1.8`；`android-v0.1.9` 仅保留失败的 CI tag，没有生成 Release 工件

## 更新内容

- 保持 `ezbookkeeping-android-<version>.apk` OTA 稳定文件名，修复 `0.1.8` 客户端发现下一版本的问题。
- 增强 Android 发布 workflow 的模拟器回归：SDK 安装、模拟器启动和 boot 等待均有界，并在失败时输出模拟器日志。
- 继续执行清单、APK、摘要、签名和 `0.1.8 / code10 → 0.1.10 / code12` 安装升级回归。

## 验证范围

- 本地 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- 正式 Release 只有在 CI 协议门禁和模拟器升级回归通过后才会发布。
- 不修改 `android-v0.1.8`，不复用 `android-v0.1.9`，不手工改写线上 `update.json`。
