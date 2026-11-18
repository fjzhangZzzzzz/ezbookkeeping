# android-v0.1.16

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionName：`0.1.16`
- versionCode：`18`
- APK 协议文件名：`ezbookkeeping-android-0.1.16.apk`

## Release 说明

- 在 GitHub runner 上安装 ARM64 Android 系统镜像前显式接受 SDK licenses，修复镜像安装被未接受 license 拒绝。
- 保持 ARM64 软件模拟器、AVD 路径统一、AVD 列表校验、独立超时保护、稳定 Android 更新协议、既有包名和既有签名证书不变。
- `android-v0.1.9` 至 `android-v0.1.15` 是没有 Release 工件的失败 CI Tag，不复用、不覆盖。

## 验收门禁

必须通过源码验证、签名构建、真实 `0.1.8 → 0.1.16` 模拟器升级回归，以及 Release 资产和 `update.json`/`SHA256SUMS` 一致性校验后，才视为发布完成。
