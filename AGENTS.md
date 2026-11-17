# ezBookkeeping Agent 操作规范

本仓库只负责 ezBookkeeping 产品源码、功能、测试和公开发布；不负责具体部署、备份、生产状态或基础设施配置。

## 开始工作

1. 阅读 [发布规范](docs/family-review/release-policy.md)。
2. 阅读 [当前实施状态](docs/family-review/implementation-status.md)。
3. 按任务阅读相关 ADR、功能文档和 workflow。
4. 检查当前分支、工作树和远端状态，不覆盖未提交修改。

## 产品与发布约定

- 产品名：`ezbookkeeping-family`。
- 发布单元：`webapp`、`android`。
- Webapp 使用 `webapp-vX.Y.Z`，Android 使用 `android-vX.Y.Z`。
- Webapp 包含 Go 后端、桌面 Web、移动 Web 和 PWA；不包含 Android APK。
- Android 使用独立签名、独立版本递增和独立 Release。
- 未提交的 Android 改动不能视为已发布或已验收。
- 不把聊天中的临时授权当作永久仓库规范；需要长期有效的决定必须写入仓库文档或 workflow。

## 文档权威顺序

1. CI/workflow 的机器约束；
2. [发布规范](docs/family-review/release-policy.md)；
3. [当前实施状态](docs/family-review/implementation-status.md)；
4. 具体 Release 说明；
5. 明确标记为历史的文档。

应用仓库的现行规范不引用 homelab 的部署、备份、生产指针或内部路径；这些内容属于独立运维仓库。历史评审材料中的旧环境描述不具有当前规范效力。
