# webapp-v0.1.9

- 产品：`ezbookkeeping-family`
- 发布单元：Webapp（Go 后端、桌面 Web、移动 Web 和 PWA）
- 前端/包版本：`0.1.9`
- Android 客户端独立发布为 `android-v0.1.7`，不随 Webapp Release 重打或覆盖。

## 更新内容

- 新增账单搜索摘要 API，与分页搜索使用同一筛选条件，避免客户端为汇总加载全部账单。
- 搜索服务支持关键词、类型、日期、账户、分类、标签、金额和图片条件的汇总计算，并保持 CNY/外币口径。
- 家庭总览和共享统计继续使用服务端完整范围聚合，Android 通过新 API 提升搜索首次打开与再次进入的响应速度。
- 包含对应的 SQLite 查询性能基线、Android 模拟器验收和跨端搜索回归测试。

## 验证范围

- Go/前端检查、Android 客户端单测、lint、Debug 构建和 Android 模拟器核心验收通过。
- 正式 Webapp 镜像、smoke 结果、不可变 digest 和 `SHA256SUMS` 由 webapp release workflow 生成并校验；本文件不替代 CI 产物校验。

本次 Webapp 与 Android 均有更新，必须分别使用 `webapp-v0.1.9` 和 `android-v0.1.7` 发布标签。
