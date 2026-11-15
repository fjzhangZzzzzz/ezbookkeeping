# 交接给 homelab 工作空间

应用开发及 GitHub 发行由本仓库维护；正式部署、准入、身份服务、Backrest 备份恢复及现场验收由 `homelab-infra` 的现行清单和 Runbook 管理。以下区分最新交付与正在运行的版本；源码发布、镜像发布、部署和验收不能相互代替。

## 当前应用交付：0.1.4

- [公开预发行 `family-v0.1.4`](https://github.com/fjzhangZzzzzz/ezbookkeeping/releases/tag/family-v0.1.4) 已由正式标签流水线完整发布，源码为 `f8da00a8fcf23ff4412db8bf792c6f0f60a32353`。[Actions 43621154413](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43621154413) 的完整 Linux Go/Web 检查、构建、准确镜像 smoke 和 publish 均成功；没有把第三方实时汇率探测记为本地业务测试。
- 固定完整 `linux/amd64` 镜像为 `ghcr.io/fjzhangzzzzzz/ezbookkeeping-family@sha256:6794136afc1738b806890f93596763749ac2ad96a51eb1e5ac69ab99afc36fa4`；config/image ID 为 `sha256:0746be1306aeb3a24494430013bbf40b59cafe76abd187c5aa2eaced7238f658`。镜像仍包含 Go 后端、桌面 Web、移动 Web 和 PWA，不内嵌独立 Android APK。
- Release `ezbookkeeping-family-0.1.4-build-info.json` 的 SHA256 为 `9689b7dcbae0677b2576278f55b38ad7ec0df6b56d4bb85a0e40c8519a6e536f`，另有 `SHA256SUMS`。独立匿名核验公开 Release、GHCR manifest/config 的实际摘要、版本、源码标签和 amd64；正式 CI 恢复归档的 config 字节及全部 13 层与公开镜像一致。校验和不是数字签名，恢复原包由 homelab 受限保管。
- 0.1.4 新增 `/android/update.json` 与版本化 APK 的匿名静态 GET/HEAD 分发、缓存与路径边界保护；目录为空时 404，不新增服务或端口。APK 独立签名发布，原始 CI manifest 与校验清单一并交付并只读挂载；新增分发路径沿用现有 Caddy 回源。未改数据库 schema 或 0.1.3 [原生 OAuth 交接](native-oauth2.md)。详见[0.1.4 发行说明](releases/family-v0.1.4.md)与 [Android 发行](android-release.md)。
- `android-v0.1.0` / code `2` 为已公开的旧 GitHub 更新客户端，其标签和附件不改写；`0.1.1` / code `3` 使用同签名自托管更新。正式 [Android tag CI 43621801593](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43621801593) 的 verify/build/publish 已全绿，[android-v0.1.1](https://github.com/fjzhangZzzzzz/ezbookkeeping/releases/tag/android-v0.1.1) 已公开。实际包为 `top.fjzhang233.books` / `0.1.1` / code `3` / minSdk `26`，源码同为 `f8da00a8fcf23ff4412db8bf792c6f0f60a32353`。APK SHA256 `133b73c664de942842dbff5e6f105d8a16bd9bdfdccdc1e8b13d01735af2fc29`，原 `update.json` SHA256 `cd89dfb5da817e66fad6147e86a2b2eccb543b79dbfaa41f6dcbcecaa7b16f43`，`SHA256SUMS` 自身 SHA256 `6291edb49dca1b29551d884a4d6777e373f2ed27dd76e10c46ea17e800ecddd1`。独立 apksigner/aapt 验证唯一固定签名、非 debuggable 及版本，匿名 Release 三件套与正式 CI 原件逐字节相同；不将“已发布”写成“已挂载/已安装/已验收”。旧公开包需要一次从自家 HTTPS 地址手工安装同签名新版本；后续客户端不访问 GitHub API/asset，不作该渠道回退。

## 当前生产：0.1.4 与 Android 0.1.1 已部署

本轮集成以 homelab `2.4.162` 正式激活并验收，运维源码 `455885e795d7260ee5afb9e8652a264c1a648b26`，前一版为 `2.4.161`。应用使用上节已发布的 `0.1.4` / 运行源码 `f8da00a8fcf23ff4412db8bf792c6f0f60a32353` / digest `6794136a…` / config `0746be13…`；本页及后续交接文档提交与镜像运行源码是不同标识。Android `0.1.1` / code `3` 原始 CI 三件套已由独立组件激活至 books HTTPS，固定父目录只读挂载，未重写 metadata、未将 APK 嵌入镜像，也未激活共享 Caddy/Glance。

正式双机检查及公网 HTTPS 静态分发协议验收通过，覆盖实际原件 SHA、GET/HEAD、条件 304、Range、MIME/缓存和拒绝越界。专用模拟器完成从受限内部低码包 `0.1.1-internal-selfhost` / code `2` 到官方 code `3` 的真实自托管更新：正常界面发现/确认下载、客户端校验、系统来源许可及 PackageInstaller Update、安装成功、读回完整 APK SHA 与固定签名、重开后再次检查为最新版。升级没有用 adb install；初始内部输入的单独安装准备不冒充升级动作，也不冒充旧公开 0.1.0 GitHub OTA 演练。全程未输入真实用户凭据，因此不证明真实登录跨版本保持。

升级前真实快照 `d3db122461c8394d6fedb7752e6c3c7ced7c554f6b02ee47c01a9c534b7b50ae` 与升级后真实快照 `8d6ab8968394538e07e0323475c2e5d9c4c05c48f0c0cdf0d5d767d102fb3961` 均完整成功，日常 Backrest 计划已经恢复。升级后点已由 162 标准恢复至 `/var/lib/homelab/ops/validation/restore-836c4wds`，完成真实隔离业务恢复：先从 161 标准恢复的预点生成最新合法现态 checkpoint，再核升级后点的业务表、账户现余额、用户状态和全部存储摘要一致；实际同点 0.1.4 镜像启动后，原密码/UID/交易/PNG、Android 原 manifest/APK 与 GET/HEAD/304/Range/MIME/cache/越界检查通过。随后只在副本补测跨用户只读、禁用及会话撤销，恢复出的会话已清空，原 disabled/deleted 状态保持；容器为 network none、无端口、挂载仅同点副本，已停止，cleanW 原 data/config/storage/Android 指纹不变。原 0.1.2 “部署验收” baseline 保留为 UID/原交易与 PNG 锚，不把旧余额/数量强套到用户最新合法数据。

正式 0.1.4 另完成一次真实 HTTPS 本地密码烟测：仅原登录用户读取现态资金账户、原交易及 PNG，核原 UID，只退出本次临时会话并验证该 token 拒绝，不创建业务数据、不清其他设备会话。用户也已明确确认实体手机安装正式 0.1.1、显示自托管来源并检查最新版，本地密码及原 Pocket ID Passkey 登录可见原数据，桌面撤销手机会话生效。用户已补充确认手机第3项通过：在现有登录用户下创建 CNY、初始余额 0 的“资金账户”（不是新增登录用户，没有新用户名/密码），记账及附图、关闭重开保持登录、退出重登。真实已登录跨版本升级与真实 Pocket ID + TLS 恢复实验仍未完成。已有真实登录用户和业务数据不得重复开户或改密，不能为手机验收创建新 CLI 用户。历史 0.1.3 原 CI 最后发布失败、复用成功工件补建 Release 的透明事实保留在[原发行说明](releases/family-v0.1.3.md)，不套用到本次全绿 0.1.4。
## 身份、配置与升级交接

公开地址继续为 `https://books.fjzhang233.top/`，复用现有 `https://auth.fjzhang233.top` Pocket ID 账号和 Passkey。专用客户端和 PKCE S256 已配置；服务端 HTTPS 回调保持 `https://books.fjzhang233.top/oauth2/callback`，Android 不携带客户端密钥，原生回跳固定 `top.fjzhang233.books://oauth2/callback`。关闭公开注册和 OAuth 自动注册，保留账密入口，不以前置强制认证阻断它。 会话继续为 30 天并按使用刷新，保留单设备退出及其他/全部会话撤销。单实例 SQLite、最多 10 个活跃家庭成员、CNY/Asia/Shanghai 汇总及默认关闭 AI/MCP/地图等范围见[简化需求](simplified-scope.md)。

当前应用仍按配置的外部邮箱/用户名识别绑定，未迁移到稳定 issuer/sub 模型。首次 direct 登录需本地邮箱与 IdP 选用邮箱精确匹配；已登录用户也可通过原生“关联”入口携带当前 UID，再确认本地密码。改变 IdP 邮箱前须评估既有绑定及其他服务准入，必要时用官方取消关联/重新绑定流程，不直接写数据库。生产已完成邮箱对齐和用户实际重绑，后续不要重放该迁移。

首批私有交接位于维护电脑 `output/homelab-private-handoff.md`，被 Git 忽略，不随源码/Release 分发。现有 `/etc/homelab/ezbookkeeping/credentials.json`、独立运行凭据及初始密码受限文件按 homelab 约定核验，不覆盖已有内容、不回显值。账号创建/启停仍使用 CLI；`--conf-path` 指定全局配置，密码仅经 `--password-stdin` 输入。

升级接手时先读 `C:/Users/fjzha/Projects/homelab-infra` 当前 AGENTS、HEAD、工作树、清单和 Runbook，保护未提交工作。按正式归档发布、prepare/激活及精确网络准入流程采纳 [deploy/homelab](../../deploy/homelab/README.md) 输入，不直接执行旧 `2.4.149` 草案或把示例 `18882` 当实时事实。本次 0.1.4 及 Android 接入不要求新增公网端口、Pocket ID 实例或 Passkey。

沿用 Backrest 唯一调度/执行入口，升级前取得一致性备份并准备匹配的数据、附件、配置/密钥、源码与镜像恢复资料；真实快照的隔离恢复必须使用同快照实际内容。恢复实例对外开放前撤销恢复会话并复核禁用用户。本次 schema 未变不意味着今后跨 schema 升级失败时可只切旧镜像；应按匹配备份恢复。后续正式升级与 Android 选版仍沿既定授权窗口推进；本次已部署及已验范围以上节为准。
