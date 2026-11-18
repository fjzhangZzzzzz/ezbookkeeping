# android-v0.1.12

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionName：`0.1.12`
- versionCode：`14`
- APK 协议文件名：`ezbookkeeping-android-0.1.12.apk`

## Release 说明

- 为模拟器启动探测、设置命令、升级回归脚本和退出清理增加独立超时，避免 CI 在设备异常时无界等待。
- 保持启用硬件加速、稳定 Android 更新协议、既有包名和既有签名证书不变。
- `android-v0.1.9`、`android-v0.1.10` 与 `android-v0.1.11` 是没有 Release 工件的失败 CI Tag，不复用、不覆盖。

## 验收门禁

必须通过源码验证、签名构建、真实 `0.1.8 → 0.1.12` 模拟器升级回归，以及 Release 资产和 `update.json`/`SHA256SUMS` 一致性校验后，才视为发布完成。
