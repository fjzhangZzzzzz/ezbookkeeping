# android-v0.1.17

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionName：`0.1.17`
- versionCode：`19`
- APK 协议文件名：`ezbookkeeping-android-0.1.17.apk`

## Release 说明

- 使用 API 30 `x86` Google APIs 镜像运行无 KVM 的纯软件模拟器；应用最低 API 26，覆盖真实升级验证所需的安装和包管理能力。
- 保持 AVD license 接受、AVD 路径统一、AVD 列表校验、独立超时保护、稳定 Android 更新协议、既有包名和既有签名证书不变。
- `android-v0.1.9` 至 `android-v0.1.16` 是没有 Release 工件的失败 CI Tag，不复用、不覆盖。

## 验收门禁

必须通过源码验证、签名构建、真实 `0.1.8 → 0.1.17` 模拟器升级回归，以及 Release 资产和 `update.json`/`SHA256SUMS` 一致性校验后，才视为发布完成。
