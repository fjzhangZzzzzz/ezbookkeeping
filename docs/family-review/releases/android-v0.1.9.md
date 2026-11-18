# android-v0.1.9

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionCode：`11`
- 基于：`android-v0.1.8`

## 更新内容

- 恢复 Android OTA 的稳定 APK 文件名协议：`ezbookkeeping-android-<version>.apk`，确保现有 `0.1.8` 客户端可以发现并下载本版本。
- 增加客户端与 CI 共享约束的清单校验，拒绝 `family` 别名、任意下载 URL、错误摘要和不一致的 `SHA256SUMS`。
- 区分更新网络失败、清单格式不兼容、版本无效、APK 摘要失败和 APK 签名失败，避免将协议故障误报为网络问题。
- 增加 `0.1.8 / versionCode 10 → 0.1.9 / versionCode 11` 的真实签名 APK 模拟器升级回归，发布前覆盖清单发现、下载、摘要、签名和安装。

## 验证范围

- 本地 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 已通过。
- CI release workflow 会生成旧稳定文件名，验证 `update.json`、APK 和 `SHA256SUMS`，并在发布前运行隔离模拟器升级回归。
- 正式 APK、`update.json` 和 `SHA256SUMS` 由 CI 使用既有专用签名材料生成；不修改 `android-v0.1.8` 的不可变 Release，不手工改写线上清单。
