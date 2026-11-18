# android-v0.1.20

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionName：`0.1.20`
- versionCode：`22`
- APK 协议文件名：`ezbookkeeping-android-0.1.20.apk`

## Release 说明

- 升级回归使用显式 APK push 和 `pm install -r`，并在安装前确认 package service 可用；失败时对每个 APK 最多重试 3 次。
- 保持 API 30 x86 软件模拟器、既有包名、既有签名证书和稳定 Android 更新协议不变。
- `android-v0.1.9` 至 `android-v0.1.19` 是没有 Release 工件的失败 CI Tag，不复用、不覆盖。

## 验收门禁

必须通过源码验证、签名构建、真实 `0.1.8 → 0.1.20` 模拟器升级回归，以及 Release 资产和 `update.json`/`SHA256SUMS` 一致性校验后，才视为发布完成。
