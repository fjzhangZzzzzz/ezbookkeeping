# 交接给 homelab 工作空间

应用开发及 GitHub 发行由本仓库维护；正式部署、准入、身份服务、Backrest 备份恢复及现场验收由 `homelab-infra` 的现行清单和 Runbook 管理。以下区分最新交付与正在运行的版本；源码发布、镜像发布、部署和验收不能相互代替。

## 当前应用交付：0.1.4

- [公开预发行 `family-v0.1.4`](https://github.com/fjzhangZzzzzz/ezbookkeeping/releases/tag/family-v0.1.4) 已由正式标签流水线完整发布，源码为 `f8da00a8fcf23ff4412db8bf792c6f0f60a32353`。[Actions 43621154413](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43621154413) 的完整 Linux Go/Web 检查、构建、准确镜像 smoke 和 publish 均成功；没有把第三方实时汇率探测记为本地业务测试。
- 固定完整 `linux/amd64` 镜像为 `ghcr.io/fjzhangzzzzzz/ezbookkeeping-family@sha256:6794136afc1738b806890f93596763749ac2ad96a51eb1e5ac69ab99afc36fa4`；config/image ID 为 `sha256:0746be1306aeb3a24494430013bbf40b59cafe76abd187c5aa2eaced7238f658`。镜像仍包含 Go 后端、桌面 Web、移动 Web 和 PWA，不内嵌独立 Android APK。
- Release `ezbookkeeping-family-0.1.4-build-info.json` 的 SHA256 为 `9689b7dcbae0677b2576278f55b38ad7ec0df6b56d4bb85a0e40c8519a6e536f`，另有 `SHA256SUMS`。独立匿名核验公开 Release、GHCR manifest/config 的实际摘要、版本、源码标签和 amd64；正式 CI 恢复归档的 config 字节及全部 13 层与公开镜像一致。校验和不是数字签名，恢复原包由 homelab 受限保管。
- 0.1.4 新增 `/android/update.json` 与版本化 APK 的匿名静态 GET/HEAD 分发、缓存与路径边界保护；目录为空时 404，不新增服务或端口。APK 独立签名发布，原始 CI manifest 与校验清单一并交付并只读挂载；清晰路由不要求激活共享 Caddy。未改数据库 schema 或 0.1.3 [原生 OAuth 交接](native-oauth2.md)。详见[0.1.4 发行说明](releases/family-v0.1.4.md)与 [Android 发行](android-release.md)。
- `android-v0.1.0` / code `2` 为已公开的旧 GitHub 更新客户端，其标签和附件不改写；`0.1.1` / code `3` 使用同签名自托管更新。正式 [Android tag CI 43621801593](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43621801593) 的 verify/build/publish 已全绿，[android-v0.1.1](https://github.com/fjzhangZzzzzz/ezbookkeeping/releases/tag/android-v0.1.1) 已公开。实际包为 `top.fjzhang233.books` / `0.1.1` / code `3` / minSdk `26`，源码同为 `f8da00a8fcf23ff4412db8bf792c6f0f60a32353`。APK SHA256 `133b73c664de942842dbff5e6f105d8a16bd9bdfdccdc1e8b13d01735af2fc29`，原 `update.json` SHA256 `cd89dfb5da817e66fad6147e86a2b2eccb543b79dbfaa41f6dcbcecaa7b16f43`，`SHA256SUMS` 自身 SHA256 `6291edb49dca1b29551d884a4d6777e373f2ed27dd76e10c46ea17e800ecddd1`。独立 apksigner/aapt 验证唯一固定签名、非 debuggable 及版本，匿名 Release 三件套与正式 CI 原件逐字节相同；不将“已发布”写成“已挂载/已安装/已验收”。旧公开包需要一次从自家 HTTPS 地址手工安装同签名新版本；后续客户端不访问 GitHub API/asset，不作该渠道回退。

## 当前生产：0.1.3；0.1.4 尚未部署

生产实测为 `0.1.3`，源码 `7d8ce0e16f0b5912a954518cea6e6c991c61f649`，固定镜像 `ghcr.io/fjzhangzzzzzz/ezbookkeeping-family@sha256:73283d3aee2a7c25504a83fca475bde7b2b6c641a2274e58f063d260a4eafdeb`，config ID `sha256:f6ab5d4344f7812a6b67eed74f71cd8420e736fd56332f50323428fe7694f786`。当前正式运维包为 homelab `2.4.160`；实际部署与恢复依据仍以 homelab 当前清单为权威。

真实 Backrest 快照 `680cd66cf86ad909833135fcae2dca0ec1a434f626fcf1bb09020c848181d186` 已完成标准同点恢复与 0.1.3 隔离业务验证：同快照源码/镜像、原本地密码/UID、账户余额、交易和附件 SHA 匹配，隔离副本跨用户只读/禁用/会话撤销已核。该验证没有完成真实 Pocket ID + TLS 的登录恢复，不将其冒称已通过；真实手机及真实原生 Passkey、自托管公开更新安装也仍待验。已有唯一真实账号和业务数据不得重复创建或改密。

0.1.4 已发布但尚未部署；其公网静态分发、APK 选择和匹配的备份恢复须在后续窗口验收。历史 0.1.3 原 CI 最后发布失败、维护者复用成功构建工件补建 Release 的透明事实保留在[原发行说明](releases/family-v0.1.3.md)，不套用到本次全绿的 0.1.4。0.1.2 及更早构建/部署记录只代表当时对应版本，不作为本次新镜像来源。
## 身份、配置与升级交接

公开地址继续为 `https://books.fjzhang233.top/`，复用现有 `https://auth.fjzhang233.top` Pocket ID 账号和 Passkey。专用客户端和 PKCE S256 已配置；服务端 HTTPS 回调保持 `https://books.fjzhang233.top/oauth2/callback`，Android 不携带客户端密钥，原生回跳固定 `top.fjzhang233.books://oauth2/callback`。关闭公开注册和 OAuth 自动注册，保留账密入口，不以前置强制认证阻断它。 会话继续为 30 天并按使用刷新，保留单设备退出及其他/全部会话撤销。单实例 SQLite、最多 10 个活跃家庭成员、CNY/Asia/Shanghai 汇总及默认关闭 AI/MCP/地图等范围见[简化需求](simplified-scope.md)。

当前应用仍按配置的外部邮箱/用户名识别绑定，未迁移到稳定 issuer/sub 模型。首次 direct 登录需本地邮箱与 IdP 选用邮箱精确匹配；已登录用户也可通过原生“关联”入口携带当前 UID，再确认本地密码。改变 IdP 邮箱前须评估既有绑定及其他服务准入，必要时用官方取消关联/重新绑定流程，不直接写数据库。生产已完成邮箱对齐和用户实际重绑，后续不要重放该迁移。

首批私有交接位于维护电脑 `output/homelab-private-handoff.md`，被 Git 忽略，不随源码/Release 分发。现有 `/etc/homelab/ezbookkeeping/credentials.json`、独立运行凭据及初始密码受限文件按 homelab 约定核验，不覆盖已有内容、不回显值。账号创建/启停仍使用 CLI；`--conf-path` 指定全局配置，密码仅经 `--password-stdin` 输入。

升级接手时先读 `C:/Users/fjzha/Projects/homelab-infra` 当前 AGENTS、HEAD、工作树、清单和 Runbook，保护未提交工作。按正式归档发布、prepare/激活及精确网络准入流程采纳 [deploy/homelab](../../deploy/homelab/README.md) 输入，不直接执行旧 `2.4.149` 草案或把示例 `18882` 当实时事实。本次 0.1.4 及 Android 接入不要求新增公网端口、Pocket ID 实例或 Passkey。

沿用 Backrest 唯一调度/执行入口，升级前取得一致性备份并准备匹配的数据、附件、配置/密钥、源码与镜像恢复资料；真实快照的隔离恢复必须使用同快照实际内容。恢复实例对外开放前撤销恢复会话并复核禁用用户。本次 schema 未变不意味着今后跨 schema 升级失败时可只切旧镜像；应按匹配备份恢复。正式升级和原生 APK 发放由后续授权窗口推进。
