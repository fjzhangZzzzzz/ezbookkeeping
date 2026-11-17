# android-v0.1.7

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionCode：`9`
- 基于：`android-v0.1.6` 之后的 Android 变更

## 更新内容

- 搜索账单入口、默认范围、筛选、分页、金额汇总和详情操作继续对齐钱迹使用习惯。
- 修复搜索页重复加载和长列表检索体验，保留搜索缓存与分页边界保护。
- 统一设置、关于与更新、账户、分类与标签、登录设备的层级和返回行为。
- 完善家庭总览、月报表、日历、分类统计和共享详情的导航与数据展示。
- 完善秒开模式、主题切换、附件详情、账户余额、分类隐藏及跨路由验收覆盖。

## 验证范围

- Android 模拟器端到端验收通过，包含搜索、报表、家庭共享详情、账户、分类、主题、活动重建和冷启动。
- `testDebugUnitTest`、`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- 正式发布 APK、`update.json` 和 `SHA256SUMS` 由 Android release workflow 使用专用签名材料生成并校验；本文件不替代 CI 产物校验。

Webapp 本次另有搜索相关 API 和版本变更，将以独立的 `webapp-v0.1.9` 发布；本 Android Release 不替代 Webapp Release。
