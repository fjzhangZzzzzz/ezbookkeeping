# 交接给 homelab 工作空间

2026-11-14 更新。用户明确：本项目负责应用开发、测试及向 GitHub 发布修改版本；Pocket ID 接入、公网准入、部署、备份和恢复验收均由 `homelab-infra` 工作空间办理。本会话不再执行运维操作。

## 应用交付

- 当前交付为 [GitHub 预发行 `family-v0.1.2`](https://github.com/fjzhangZzzzzz/ezbookkeeping/releases/tag/family-v0.1.2)，非草稿；源码分支 `family/initial`，标签及运行源码均为 `e531c40dfa68478d5a5068f6a5d4bbe80d7f829a`。后续交接文档提交不改变镜像的源码标识。
- 主部署产物为 `linux/amd64` 完整应用镜像，包含 Go 后端、桌面 Web、移动 Web 和 PWA；不包含 Android APK 或独立管理员 Web 后台，创建和启停账号仍使用 CLI。镜像版本为 `ghcr.io/fjzhangzzzzzz/ezbookkeeping-family:0.1.2`，另提供 `sha-e531c40dfa68478d5a5068f6a5d4bbe80d7f829a` 标签。
- homelab 应锁定 registry manifest digest：`ghcr.io/fjzhangzzzzzz/ezbookkeeping-family@sha256:3ed43b67a25ae98e5d3dd8976fbe124be2a3e448d96e93ff97cdde142f0766b0`。发布任务已验证匿名读取该 manifest；运行 image ID 为 `sha256:de52b7b2771196fdab5bcfa3758b7ac55d9bd061d4586ffab37588016bca4b3c`，与 registry digest 区分记录。
- Release 的两份附件为 `ezbookkeeping-family-0.1.2-build-info.json` 和 `SHA256SUMS`，前者记录源码、镜像 digest、上游来源、Actions run 及 smoke 结果；其 SHA256 为 `f0a629bfdc603fc6fcb096d9f875a41afd73279f1c77483eed01f96156735300`。校验和用于完整性检查，不是数字签名。源码压缩包用于维护源码，前端 tar 不再作为主要部署产物。
- [GitHub Actions 43551697609](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43551697609) 的 build 与 publish 均成功。此次由同一源码完整执行 Linux Dockerfile：Go vet/测试及后端构建、前端类型检查/lint、17 个文件 38,689 项测试和 Vite 生产打包，未复用旧 Windows 前端。仅关闭第三方汇率在线探测，未跳过本地业务测试。
- 本次实际镜像 smoke 验证 UID/GID 1000、只读根、前后端版本/完整提交、22 项引用静态资源、PWA 文件、stdin 开通合成用户、密码登录、SQLite 账户与支出写入、家庭汇总、幂等重试、重启后的会话/数据/重试记录及 SQLite 完整性。该检查没有运行真实浏览器；真实 Pocket ID 绑定、公网 HTTPS、实体手机/PWA 和实际 Backrest 快照恢复仍待 homelab 验收。

`family-v0.1.0` 的源码预发行、冻结源码 `462ff2b3` 与旧离线 image ID `e7601cbf` 属于此前隔离验收记录，来源细节保留在下列历史证据中。`family-v0.1.1` 保留源码标签，未产生 Actions 运行或应用发行；当前接手使用 `0.1.2`，不覆盖旧标签或将旧离线镜像当作本版产物。

详细证据：[实施状态](implementation-status.md)、[独立 QA](independent-qa.md)、[构建来源与限制](linux-build-validation.md)。产品范围以[简化需求](simplified-scope.md)为准；应用侧部署输入见 [deploy/homelab](../../deploy/homelab/README.md)，由 homelab 工作空间按当前规范采纳。

## 已确认的接入要求

| 项目 | 约定 |
|---|---|
| 公开地址 | `https://books.fjzhang233.top/` |
| DNS | 用户已通知配置完成：A → `139.196.45.16`，不新增 AAAA；此次交接未独立复核解析 |
| 身份服务 | 复用现有 `https://auth.fjzhang233.top` Pocket ID、原账号与已有 Passkey，无需部署新服务或创建新 Passkey |
| 应用登记 | 在现有 Pocket ID 为记账应用配置专用 OIDC 客户端，客户端 ID/密钥通过受限文件交接；尚未确认已配置完成 |
| 回调 | `https://books.fjzhang233.top/oauth2/callback`，启用 PKCE S256 |
| 登录 | 原生 OIDC 与账密均可登录同一本地 UID；保留账密入口，不用前置认证阻断它 |
| 注册/绑定 | 关闭公开注册和 OAuth 自动注册；管理员先建本地账号，首次绑定需确认本地密码 |
| 会话 | 30 天会话，使用/启动时刷新；退出仅退出记账应用，保留设备会话及全部会话撤销能力 |

首批真实账号**尚未创建**，按本机 `C:\Users\fjzha\Projects\ezbookkeeping\output\homelab-private-handoff.md` 中的私有开通信息办理；该文件被 Git 忽略，不随公开源码或 Release 分发。记账本位币 CNY。昵称和本地邮箱可在个人设置中修改；用户名保持固定。若更改 Pocket ID 邮箱，须同时处理本地邮箱与外部绑定，不把改本地邮箱视为已改身份绑定。

本轮历史交接已登记以下 control-01 受限路径，homelab 应在自己的工作空间核验存在性、权限和完整性；本文件不读取或保存其内容：

- `/etc/homelab/ezbookkeeping/credentials.json`：应用及 OIDC 凭据，客户端字段仍待配置确认。
- 初始密码受限文件的精确路径见本机私有交接；开通用户时由 CLI `--password-stdin` 安全输入，不放聊天、Git 或命令行参数。
- `/srv/homelab/recovery/ezbookkeeping/source/`：冻结源码归档、完整 Git bundle、前端工件及校验材料。
- `/srv/homelab/recovery/platform-images/`：已验镜像的 age 加密离线归档及索引。

这些工件落盘**不等于已进入 Backrest 快照或已完成恢复验收**。

## homelab 接手后的工作

1. 在 `C:\Users\fjzha\Projects\homelab-infra` 重新读取当前 `AGENTS.md`、HEAD、工作树及发布约定。历史本地集成提交为 `564cc4f737aed1248c5d006f99ec31ae409f8162`（`2.4.149`），当时未推送、未发布、未激活；其后状态由 homelab 核实，保护并发用户修改，不直接套用旧发布号或覆盖已有标签。
2. 复核 DNS、专用 OIDC 客户端、受限凭据和精确可信代理范围；按当前基础设施流程审阅及发布运维配置。应用 GitHub Release 不代表基础设施已发布或服务已上线。
3. 核对本页与 Release 清单的源码、`linux/amd64` 平台及完整 registry digest，按当前基础设施流程引入匹配的镜像及恢复材料；在批准的范围内完成 edge → control `18882` 准入、Caddy HTTPS、单实例 SQLite 容器和只读根文件系统部署。不要以示例端口或配置语法检查代替实际连通与业务验收。
4. 安全开通首批本地用户，验收账密兜底、Pocket ID/已有 Passkey 首次绑定和后续登录确为同一 UID；验证会话保持、退出/撤销，以及实体手机使用。
5. 沿用现有 Backrest 作为唯一备份调度/执行入口，不增加第二套 cron/timer。将数据、附件、必要配置/密钥、匹配的源码与镜像恢复材料纳入实际计划；仅在实施窗口按当前规范暂停/恢复计划，不在等待人工配置期间暂停备份。
6. 完成真实快照和隔离业务恢复，从快照恢复匹配的源码、镜像、配置/密钥、数据库和附件；不能借用宿主现存工件冒充快照完整。重新开放恢复实例前撤销恢复出的旧会话并复核停用用户。升级失败跨 schema 时按匹配备份恢复，不把切换旧镜像当作数据回滚。
7. 记录最终应用版本、镜像来源、基础设施发布、部署和验收结果。当前状态为应用隔离验收完成，公网生产服务与真实恢复尚未验收。

可在 homelab 工作空间直接使用本文件作为接手入口；应用功能缺陷仍回到本项目修复并发布新版本。
