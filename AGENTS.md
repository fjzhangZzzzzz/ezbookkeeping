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

## Push 前验证与 CI 边界

- 代码变更推送前，按变更范围完成必要的本地验证，目标是尽量减少 CI 失败和重复发布；这不是把所有变更都强制绑定到同一套重型回归。
- Android 业务、UI、API 或数据逻辑变更，至少执行受影响的单元测试、lint 和 debug 构建；涉及启动、记账、更新安装、数据库迁移等行为时，再使用本地模拟器做对应 smoke/regression。
- Android 更新器、发布脚本、签名校验或 workflow 变更，必须执行协议/脚本静态验证；只有实际影响安装升级行为时，才要求本地模拟器升级回归。
- 仅文档、发布说明或 CI 配置变更，执行对应的格式、脚本和 workflow 静态检查，不做没有价值的 Android 全量回归。
- 缺少 Android SDK、ADB 或选定 AVD 等必要依赖时，先向用户说明并请求确认；不得自动安装依赖，也不得把未执行的必要验证静默记为通过。
- 稳定、可复用的本地验证步骤应优先封装为仓库脚本；GitHub Actions 只执行可靠且与变更相关的轻量门禁，不在托管 runner 上运行不稳定或长时间的 Android 模拟器回归。
- 创建新的 Android 发布 tag 前，必须确认本地必要验证已完成、工作树干净且版本基于最近正式 Android tag 递增；失败 tag 不通过递增版本反复试错。

## 文档权威顺序

1. CI/workflow 的机器约束；
2. [发布规范](docs/family-review/release-policy.md)；
3. [当前实施状态](docs/family-review/implementation-status.md)；
4. 具体 Release 说明；
5. 明确标记为历史的文档。

应用仓库的现行规范不引用 homelab 的部署、备份、生产指针或内部路径；这些内容属于独立运维仓库。历史评审材料中的旧环境描述不具有当前规范效力。
