# 交接给 homelab 工作空间

应用开发及 GitHub 发行由本仓库维护；正式部署、准入、身份服务、Backrest 备份恢复及现场验收由 `homelab-infra` 的现行清单和 Runbook 管理。以下区分最新交付与正在运行的版本；源码发布、镜像发布、部署和验收不能相互代替。

## 当前应用交付：0.1.3

- [公开预发行 `family-v0.1.3`](https://github.com/fjzhangZzzzzz/ezbookkeeping/releases/tag/family-v0.1.3) 已补建，源码及不可变标签为 `7d8ce0e16f0b5912a954518cea6e6c991c61f649`。本页、[发行说明](releases/family-v0.1.3.md)和前置门禁属于标签后的文档/CI 维护，不声称已包含在该源码标签或镜像中。
- `linux/amd64` 完整应用镜像包含 Go 后端、桌面 Web、移动 Web 和 PWA，无需独立部署前端。部署锁定 `ghcr.io/fjzhangzzzzzz/ezbookkeeping-family@sha256:73283d3aee2a7c25504a83fca475bde7b2b6c641a2274e58f063d260a4eafdeb`；版本标签为 `0.1.3`，config/image ID 为 `sha256:f6ab5d4344f7812a6b67eed74f71cd8420e736fd56332f50323428fe7694f786`，二者不是同一摘要。
- Release 附件为 `ezbookkeeping-family-0.1.3-build-info.json`（SHA256 `866c2c59a442ce8495b7014ed7618afd5543f650d53cfa57a35907c24089a095`）及 `SHA256SUMS`。清单记录源码、上游来源、准确镜像、smoke 和补发依据；校验和不是数字签名。
- [Actions 43593592805](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43593592805) 的完整 Linux build、Go vet/测试、前端类型/lint/测试/生产打包及准确镜像 smoke 成功，镜像已由原流水线推送且匿名核验。整条流水线结论仍为失败：冻结标签缺少发行说明，最后 `gh release create` 失败。维护者复用原成功 build artifact 和既有镜像补建 Release，未重跑构建、替换镜像或改写标签；build-info 的 `publication` 字段透明记录该过程。
- 0.1.3 新增 Android 系统浏览器认证的固定回跳、PKCE S256、短时一次性 code、受限 callbackToken 及取消能力，首次本地密码和适用的 2FA 确认保留；同 UID、本地密码登录、关闭双注册的约束不变。Go 全量测试/vet、manager race、合成 OIDC 首绑/重试/取消/并发/日志隔离及旧 Web 回调已验证；没有修改数据库 schema 或依赖。详见[原生认证交接](native-oauth2.md)。第三方实时汇率探测按既有 CI 条件关闭，不代表跳过本地业务测试。
- 独立 Kotlin/Compose Android 首版（工程 `versionName=0.1.0`）目前仅本地开发/构建及模拟器验收推进中，尚未发布、未完成真实手机验收；本服务端 Release 不含 APK。签名与 `android-v*` 独立发布及用户确认下载、系统安装的更新流程见 [Android 发行](android-release.md)，不把应用壳当作原生界面，也不把模拟器当实体设备。

## 当前生产：仍为 0.1.2

homelab 已部署并保留 `0.1.2`，运行源码 `e531c40dfa68478d5a5068f6a5d4bbe80d7f829a`，锁定镜像 `ghcr.io/fjzhangzzzzzz/ezbookkeeping-family@sha256:3ed43b67a25ae98e5d3dd8976fbe124be2a3e448d96e93ff97cdde142f0766b0`。该版本的公网 HTTPS、本地密码、Pocket ID 同 UID 首绑和退出后再次登录、真实记账附件样本，以及 Backrest 真实快照/隔离业务恢复已在 homelab 验证；移动实体设备/PWA 安装及原生 APK 仍没有完成验收。唯一首批真实用户已经开通，不应因早期交接写“未创建”而重复开户或改密。

0.1.3 已发布但尚未升级到生产；其正式 HTTPS、真实 Pocket ID/Android 和匹配版本的备份恢复仍须另行验收，不能继承 0.1.2 通过结论。历史 `0.1.0` 离线镜像及 `0.1.1` 源码标签不作为本次来源。旧版本构建和隔离记录见[实施状态](implementation-status.md)，基础设施实际发布、恢复工件及受限验收索引以 homelab 为准。

## 身份、配置与升级交接

公开地址继续为 `https://books.fjzhang233.top/`，复用现有 `https://auth.fjzhang233.top` Pocket ID 账号和 Passkey。专用客户端和 PKCE S256 已配置；服务端 HTTPS 回调保持 `https://books.fjzhang233.top/oauth2/callback`，Android 不携带客户端密钥，原生回跳固定 `top.fjzhang233.books://oauth2/callback`。关闭公开注册和 OAuth 自动注册，保留账密入口，不以前置强制认证阻断它。 会话继续为 30 天并按使用刷新，保留单设备退出及其他/全部会话撤销。单实例 SQLite、最多 10 个活跃家庭成员、CNY/Asia/Shanghai 汇总及默认关闭 AI/MCP/地图等范围见[简化需求](simplified-scope.md)。

当前应用仍按配置的外部邮箱/用户名识别绑定，未迁移到稳定 issuer/sub 模型。首次 direct 登录需本地邮箱与 IdP 选用邮箱精确匹配；已登录用户也可通过原生“关联”入口携带当前 UID，再确认本地密码。改变 IdP 邮箱前须评估既有绑定及其他服务准入，必要时用官方取消关联/重新绑定流程，不直接写数据库。生产已完成邮箱对齐和用户实际重绑，后续不要重放该迁移。

首批私有交接位于维护电脑 `output/homelab-private-handoff.md`，被 Git 忽略，不随源码/Release 分发。现有 `/etc/homelab/ezbookkeeping/credentials.json`、独立运行凭据及初始密码受限文件按 homelab 约定核验，不覆盖已有内容、不回显值。账号创建/启停仍使用 CLI；`--conf-path` 指定全局配置，密码仅经 `--password-stdin` 输入。

升级接手时先读 `C:/Users/fjzha/Projects/homelab-infra` 当前 AGENTS、HEAD、工作树、清单和 Runbook，保护未提交工作。按正式归档发布、prepare/激活及精确网络准入流程采纳 [deploy/homelab](../../deploy/homelab/README.md) 输入，不直接执行旧 `2.4.149` 草案或把示例 `18882` 当实时事实。0.1.3 不要求新增公网端口、Pocket ID 实例或 Passkey。

沿用 Backrest 唯一调度/执行入口，升级前取得一致性备份并准备匹配的数据、附件、配置/密钥、源码与镜像恢复资料；真实快照的隔离恢复必须使用同快照实际内容。恢复实例对外开放前撤销恢复会话并复核禁用用户。本次 schema 未变不意味着今后跨 schema 升级失败时可只切旧镜像；应按匹配备份恢复。正式升级和原生 APK 发放由后续授权窗口推进。
