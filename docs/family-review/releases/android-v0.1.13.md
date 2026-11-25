# android-v0.1.13

- 包名 `top.fjzhang233.books`
- versionCode `15`
- 上一正式版本 `android-v0.1.12`

## 更新内容

- 修复 Android 边缘返回手势与左上角返回按钮动画不一致的问题，关闭 Navigation 3 的默认预测返回缩放过渡。
- 个人流水、统计、家庭、日历、搜索、设置及其他共享导航页面统一使用无过渡返回效果。
- 在导航 ADR 中记录普通返回与预测返回必须同步配置的长期约束。

## 验证范围

- `testDebugUnitTest`、`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- API 35 隔离模拟器使用本地合成后端验证统计、家庭、设置、搜索和日历页面的左右边缘返回，均正常回到流水页。
- Android Release workflow 负责正式签名、APK 包名、版本、versionCode、摘要及 `update.json` / `SHA256SUMS` 校验，并发布稳定 OTA 文件名。
