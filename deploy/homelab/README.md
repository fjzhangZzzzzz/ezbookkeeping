# homelab 部署参考输入

本目录只提供应用侧配置契约和 Compose 参考，**不得直接覆盖正式配置或绕过 Ansible 执行**。主机与准入、服务及备份范围、版本与摘要的唯一权威分别是 `homelab-infra/inventory/hosts.yml`、`inventory/services.yml`、`inventory/artifacts.lock.json`；现行操作见该仓库 `docs/runbooks/ezbookkeeping.md` 及 Backrest Runbook。先读其 AGENTS、当前 HEAD/工作树和清单，再按正式配置包 prepare/激活流程操作。

当前源码、镜像、Android 发行与生产版本的区别见[应用交接](../../docs/family-review/homelab-handoff.md)；不从历史离线镜像或旧本地草案重新初始化。0.1.4 与 Android 0.1.1/code3 已经 homelab 2.4.162 正式部署，公网静态分发及专用模拟器自托管系统升级已通过；新真实快照的同点隔离业务恢复与正式 HTTPS 密码仅读烟测也已通过。用户已确认手机正式包/更新检查、密码及 Passkey 登录和桌面撤销手机会话；资金账户记账/附图/保持登录等仍待补验，进度以交接及 homelab 记录为准。

## 现有路径与配置

- 应用运行在 control-01，现有 edge Caddy 为 `https://books.fjzhang233.top` 提供 HTTPS，经既有私网到 `10.144.144.2:18882`（容器 8080）。域名、回源路径和专用 Pocket ID 客户端已经接入；当前身份、监听及准确准入仍以 inventory 和现场核验为准。
- Pocket ID 为原生登录选项：issuer `https://auth.fjzhang233.top`，HTTPS 回调 `/oauth2/callback`，PKCE S256。不增加全站强制 forward-auth，保留本地密码；关闭公开注册和 OAuth 自动注册。首次绑定及邮箱匹配规则见交接文档。
- `compose.yml` 要求 `HOMELAB_EZBOOKKEEPING_IMAGE` 为完整 `ghcr.io/...@sha256:<registry digest>`，由正式工件锁提供，使用 `pull_policy: missing`。config/image ID、registry manifest digest 和离线 docker-save 归档 SHA 是不同标识；离线恢复须由同快照固定工件校验并加载，不能混用旧 image ID。
- `ezbookkeeping.ini.template` 是配置键参考，不是应覆盖生产的渲染成品。现行 Ansible 管理 `/etc/homelab/ezbookkeeping/ezbookkeeping.ini`，配置与 secret 按受限输入交付，不提交或回显值。服务 UID/GID 1000，SQLite `/ezbookkeeping/data/ezbookkeeping.db` 与附件 `/ezbookkeeping/storage` 独立持久化。
- 会话为 30 天并按使用刷新；默认关闭自动定时记账、AI、MCP、地图及自定义图标，家庭统计按 CNY/Asia/Shanghai 排除并提示外币。准确可信代理只能来自实测地址，不扩大为整个私网。

## 独立 Android 工件

后端 0.1.4 提供匿名 `/android/update.json` 和 `/android/releases/<versionName>/<apkAssetName>`。独立签名 APK、原始 CI manifest 和 SHA256SUMS 由 homelab 的 Android 组件验证、选版；GitHub 仅为不可变构建/发行取件来源，客户端只从自部署 HTTPS 检测与下载。

固定宿主父目录 `/srv/homelab/ezbookkeeping/android` 只读挂载到 `/ezbookkeeping/public/android`。`current` 相对指向 `releases/<versionName>`，根 `update.json` 相对指向 `current/update.json`；版本化 APK 不覆盖、未验证 staging 不进入公开树。后端镜像只建空挂载目标，不内嵌 APK；未挂工件时返回 404。后续只切已验证工件选择，无须后端重建或共享 Caddy/Glance 激活，不新增服务、端口和网络准入。首次增加挂载仍按正式组件部署流程执行。

## CLI、备份与验收

应用 CLI 的全局 `--conf-path` 必须显式指向运行配置；entrypoint 自定义命令不会自动补齐。账号创建与改密用 `userdata user-add --password-stdin` / `user-modify-password --password-stdin`，密码只经受限 stdin，不进入 argv、历史、日志或聊天。已有真实用户不得重复开户或改密。禁用用户与应用会话撤销一起核验，不能只停用 Pocket ID；单设备、其他设备及全部会话按现行 Runbook 操作，最多 10 个活跃家庭成员。

Backrest 是唯一备份调度和执行入口，Restic 为后端，Ansible 提供停写一致性和恢复校验；不另加 cron/timer，不覆盖产品中的既有计划。升级前和日常真实快照应包含匹配数据/附件、配置密钥、源码/镜像及 Android 公共工件和选择依据。隔离恢复必须用同快照内容，开放前撤销恢复会话并复核停用成员；不能借现场数据补缺，也不能跨 schema 只切回旧镜像。

健康检查不代替业务。正式验收分别记录源码、镜像、部署和验收状态，核对 HTTPS/密码/OIDC 同 UID、交易附件、会话、真实快照恢复及实体手机/自更新。手机验收中的“资金账户”是现有登录用户的记账对象，不是新登录用户，不能为此创建新用户名/密码。已确认的手机登录不代表真实已登录跨版本升级或真实 OIDC+TLS 恢复，这两项仍须现场补验。