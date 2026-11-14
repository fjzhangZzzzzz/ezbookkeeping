# ezBookkeeping Family 0.1.2

家庭版使用 GitHub Actions 单架构完整应用构建。镜像包含 Go 后端、桌面 Web、移动 Web 和 PWA；不包含独立管理员 Web 后台或 Android APK。此前 `0.1.1` 仅保留源码标签，未产生 Actions 运行或应用发行；本版使用新的不可变标签执行发布。

## 部署产物

- 镜像：`ghcr.io/fjzhangzzzzzz/ezbookkeeping-family:0.1.2`（`linux/amd64`）。
- 以附件 `ezbookkeeping-family-0.1.2-build-info.json` 中的完整 registry digest 锁定部署；同文件记录源码提交、Actions run、上游来源及镜像验证结果。
- `SHA256SUMS` 用于验证发布清单完整性，不是数字签名。
- GitHub 自动生成的源码压缩包用于源码分发，不是安装包；不再将前端静态 tar 作为主要部署产物。

## 构建与验证

本发行仅在同一源码提交的 Linux Dockerfile 构建、Go vet/单元测试、前端类型检查/lint/单元测试/生产打包，以及实际镜像 smoke 全部成功后由 Actions 发布。只关闭依赖第三方汇率服务的在线探测；不跳过本地业务测试。镜像 smoke 验证运行版本/完整源码提交、桌面/移动静态页面、合成用户登录、SQLite 真实记账及重启持久性。发布前检查镜像允许匿名拉取。

这是新的完整 Linux 构建，不复用 `family-v0.1.0` 中的 Windows 前端资产或此前离线镜像。应用显示版本与发行号统一为 `0.1.2`，上游来源单独记录。

## 运维交接

此版本继续标记预发行。公网 HTTPS、Pocket ID 首次绑定与真实移动端登录、正式备份及隔离恢复验收由 homelab 工作空间完成，本项目不宣称已上线。详见 [homelab 交接](https://github.com/fjzhangZzzzzz/ezbookkeeping/blob/family-v0.1.2/docs/family-review/homelab-handoff.md)。
