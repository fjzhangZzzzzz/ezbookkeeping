# 实施状态

## 当前发布

- 产品：`ezbookkeeping-family`。
- Webapp：`webapp-v0.1.10`，源码提交、镜像 digest 和构建结果以对应 Release metadata 为准。
- Android：`android-v0.1.21`，versionCode `23`，与 Webapp 独立发布、独立签名和独立版本递增。
- Webapp 镜像不内嵌 Android APK；Android 使用独立 APK、`update.json` 和 `SHA256SUMS` 工件。
- Webapp Release 的准确来源、镜像 digest、构建结果和 Android 关联状态以对应 Release metadata 为准。

## 当前功能状态

- 家庭共享分类、内部往来保护、分类改名保留引用和 Android 父类/子类交互已实现。
- 账户支持安全删除或自动归档；桌面 Web、移动 Web 和 Android 保持相同语义。
- 家庭读取、人民币统计、上海时区、退款负值处理、外币未计入提示和汇总 CSV 已实现。
- 自动定时记账关闭，普通手动模板保留；公开注册和 OAuth 自动注册关闭。
- 应用使用内置图标，不支持个人自定义图标。

## 已验证范围

- Go 测试、前端测试、类型检查、构建和发布镜像 smoke 已按对应 CI 记录执行。
- Android release workflow 校验 Gradle、签名、包名、versionCode、APK metadata 和完整性清单。
- 独立 QA 记录了真实浏览器视口、重试幂等、共享字典、成员停用、附件、登录边界和 Android 关键流程；详细证据见 [independent-qa.md](independent-qa.md)。

## 未完成或不应推导的范围

- 真实用户的长期跨版本登录保持不能由短时测试推导。
- 真实设备和长期账号体验不能由短时自动化测试推导。
- 本项目不承诺完整源码安全审计、逐依赖人工审计、离线同步、多家庭权限平台或旧库通用迁移。

## 历史资料

历史需求、有限安全审计、上游核验、依赖清单、UID 清单和 ADR 仅用于理解产品决策与技术依据，不是当前发布入口。旧 `family-v*` Release 说明已随退役发布线删除；当前发布只使用 `webapp-v*` 和 `android-v*`。
