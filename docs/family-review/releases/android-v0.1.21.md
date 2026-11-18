# android-v0.1.21

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionName：`0.1.21`
- versionCode：`23`
- APK 协议文件名：`ezbookkeeping-android-0.1.21.apk`

## Release 说明

- 升级回归使用满足 `minSdk=26` 的 API 26 x86 Google APIs 软件模拟器，避免 API 30 无 KVM 模拟器的系统服务启动不稳定。
- 回归脚本显式 push APK、等待 package service、执行 `pm install -r` 并重试；失败时 workflow 输出 emulator log。
- 保持既有包名、既有签名证书和稳定 Android 更新协议不变。
- `android-v0.1.9` 至 `android-v0.1.20` 是没有 Release 工件的失败 CI Tag，不复用、不覆盖。

## 验收门禁

必须通过源码验证、签名构建、真实 `0.1.8 → 0.1.21` 模拟器升级回归，以及 Release 资产和 `update.json`/`SHA256SUMS` 一致性校验后，才视为发布完成。
