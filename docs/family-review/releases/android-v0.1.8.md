# android-v0.1.8

- 产品：`ezbookkeeping-family`
- 发布单元：Android 原生客户端
- 包名：`top.fjzhang233.books`
- versionCode：`10`
- 基于：`android-v0.1.7` 之后的 Android 变更

## 更新内容

- 对齐家庭总览与个人统计的数据契约，支持家庭收支概览、成员、账户、每日、分类和家庭流水展示，并支持成员筛选。
- 优化账单搜索的数据加载和页面复用，避免重复汇总全量账单；搜索默认范围为全部历史账单。
- 优化秒开启动、记账保存和流水更新路径，采用本地关键数据缓存及乐观更新，减少等待和阻塞式反馈。
- 重构设置页为与主界面一致的分组列表，统一浅色/深色主题、图标、间距和返回行为。
- 合并设置、关于与更新入口，保留手动检查更新，并将高级设置默认折叠。
- 增加本地性能诊断摘要、导出和清理能力，明确不上传账本内容、金额、关键词或登录凭据。
- 完善 Pocket ID 第三方登录详情页，支持本地密码确认后解除关联，失败时保留对话框反馈。
- 保持秒开模式、账户、分类与标签、登录设备等入口在统一设置层级中可用。

## 验证范围

- Android 模拟器已验收浅色/深色主题、设置分组、主题选择、高级设置、性能诊断、关于与更新、第三方登录和秒开开关。
- `testDebugUnitTest`、`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- 正式发布 APK、`update.json` 和 `SHA256SUMS` 由 Android release workflow 使用专用签名材料生成并校验；本文件不替代 CI 产物校验。

Webapp 本次另有家庭总览 API 变更，将以独立的 `webapp-v0.1.10` 发布；本 Android Release 不替代 Webapp Release。
