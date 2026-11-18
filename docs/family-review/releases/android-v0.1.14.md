# android-v0.1.14

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionName：`0.1.14`
- versionCode：`16`
- APK 协议文件名：`ezbookkeeping-android-0.1.14.apk`

## Release 说明

- 在 Emulator 启动步骤重新设置 `ANDROID_AVD_HOME`，修复 GitHub Actions steps 之间环境变量不继承导致的 AVD 不可见。
- 保持 AVD 创建/列表校验、模拟器启动加速、独立超时保护、稳定 Android 更新协议、既有包名和既有签名证书不变。
- `android-v0.1.9` 至 `android-v0.1.13` 是没有 Release 工件的失败 CI Tag，不复用、不覆盖。

## 验收门禁

必须通过源码验证、签名构建、真实 `0.1.8 → 0.1.14` 模拟器升级回归，以及 Release 资产和 `update.json`/`SHA256SUMS` 一致性校验后，才视为发布完成。
