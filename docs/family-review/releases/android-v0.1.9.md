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
- 本地必要验证和新 CI 流程通过后创建 tag；GitHub Actions 只负责可靠的签名构建、协议门禁和 Release 发布，不在托管 runner 上运行 Android 模拟器。

## 验证范围

- 本地 Windows SDK/ADB/AVD 验证已通过：`testDebugUnitTest`、`lintDebug`、`assembleDebug`、干净 AVD debug smoke，以及真实签名 `0.1.8/code10 → 0.1.9/code11` 升级回归。
- 本地正式签名 `lintRelease`、`assembleRelease` 和真实 APK 生成的 `update.json` / `SHA256SUMS` 协议门禁已通过；产物仅写入 ignored `output/`。
- CI release workflow 会生成稳定文件名，验证真实 APK、`update.json` 和 `SHA256SUMS`；发布前不再执行不可靠的托管模拟器回归。
- 正式 APK、`update.json` 和 `SHA256SUMS` 由 CI 使用既有专用签名材料生成；不修改 `android-v0.1.8` 的不可变 Release，不手工改写线上清单。
