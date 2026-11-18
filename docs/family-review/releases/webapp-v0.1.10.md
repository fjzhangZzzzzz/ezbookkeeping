# webapp-v0.1.10

- 产品：`ezbookkeeping-family`
- 发布单元：Webapp（Go 后端、桌面 Web、移动 Web 和 PWA）
- 前端/包版本：`0.1.10`
- Android 客户端独立发布为 `android-v0.1.8`，不随 Webapp Release 重打或覆盖。

## 更新内容

- 新增家庭总览服务端 API，聚合家庭成员的收支概览、成员统计、账户余额、每日统计、分类报表和家庭流水。
- 统一家庭统计的 CNY 与 Asia/Shanghai 口径，并保留成员筛选和共享数据只读边界。
- 增加家庭报表架构说明和服务层集成测试，供 Android 家庭页与个人统计页使用同一数据契约。

## 验证范围

- 家庭服务、API 和集成测试已纳入源码验证；发布 workflow 继续执行完整 Go/前端构建及镜像 smoke 检查。
- 正式 Webapp 镜像、smoke 结果、不可变 digest 和 `SHA256SUMS` 由 webapp release workflow 生成并校验；本文件不替代 CI 产物校验。

本次 Webapp 与 Android 均有更新，必须分别使用 `webapp-v0.1.10` 和 `android-v0.1.8` 发布标签。
