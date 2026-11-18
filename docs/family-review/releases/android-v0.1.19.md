# android-v0.1.19

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionName：`0.1.19`
- versionCode：`21`
- APK 协议文件名：`ezbookkeeping-android-0.1.19.apk`

## Release 说明

- 使用 `adb install --no-streaming`，避免无 KVM x86 软件模拟器长时间 streamed install 导致 ADB Broken pipe。
- 保持 API 30 x86 软件模拟器、APK 安装超时、AVD license 接受、AVD 路径统一、稳定 Android 更新协议、既有包名和既有签名证书不变。
- `android-v0.1.9` 至 `android-v0.1.18` 是没有 Release 工件的失败 CI Tag，不复用、不覆盖。

## 验收门禁

必须通过源码验证、签名构建、真实 `0.1.8 → 0.1.19` 模拟器升级回归，以及 Release 资产和 `update.json`/`SHA256SUMS` 一致性校验后，才视为发布完成。
