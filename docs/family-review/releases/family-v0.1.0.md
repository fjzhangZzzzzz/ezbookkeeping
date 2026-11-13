# 家庭 fork 0.1.0

本发行将单家庭记账二次开发源码交付到 `fjzhangZzzzzz/ezbookkeeping`。发行标签为 `family-v0.1.0`，应用内保留的上游基础版本号仍为 `2.2.0`，不代表上游正式发行。正式部署、Pocket ID 客户端配置及备份恢复验收交由 homelab 工作空间实施。

## 主要变化

- 单家庭、最多 10 名有效成员；独立账号、自行维护个人交易，家庭成员共享查看汇总、交易、账户余额和附件。
- 全局分类、标签及标签组，使用内置图标；家庭人民币统计、成员比较、12 个月趋势和家庭汇总 CSV。
- 交易写入持久化幂等保护；禁用成员后撤销会话并从家庭视图排除其数据。
- 保留账密与 Pocket ID 原生 OIDC 登录，关闭公开注册、OAuth 自动注册和自动周期记账；收敛日志敏感数据并更新安全依赖。

## 源码与构建来源

发行标签包含交付文档和部署示例的最终修订。已验运行候选的冻结源码是 `462ff2b3f66659daa92ca2d19255924d1e2a3b70`；后续提交仅更新 `docs/` 和 `deploy/`，没有重建运行程序。

发行附件中的 `ezbookkeeping-family-v0.1.0-frontend.tar` 是该冻结源码在 Windows 上构建的跨平台浏览器静态资源，包含 `dist/` 下 109 个文件，**不能单独部署，也不是 Docker 镜像或后端安装包**。随附逐文件校验清单、构建来源证明和 `SHA256SUMS`。前端归档 SHA256 为 `48f14690df948d5b7466a4a38e2c3e3dd86b00255c5918dd24080ff345bd4ba5`。

Linux 后端与 Windows 静态资源曾组合成隔离验收镜像；该镜像没有推送公共 registry，也不作为本发行的可下载附件。homelab 后续须明确采用现有已验离线工件，或自行构建并验收新镜像。

## 验证及边界

冻结源码已完成 Linux Go 测试、前端类型检查和 lint、17 个 Vitest 文件共 38689 项测试，以及真实 SQLite、CSV、交易重试和桌面/移动视口浏览器验收。Windows 前端生产打包通过。Linux 前端 Vite 打包因容器内存不足失败，不能将本发行描述为完整 Linux 构建流水线通过；本次源码发布不重新运行多平台 CI。

尚未完成公网 HTTPS、真实 Pocket ID 首次绑定及真实 Backrest 快照恢复验收；这些属于 homelab 的后续部署验收。详细证据见 [实施状态](../implementation-status.md)、[构建记录](../linux-build-validation.md)和 [homelab 交接](../homelab-handoff.md)。
