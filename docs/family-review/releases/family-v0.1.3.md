# ezBookkeeping Family 0.1.3

本版本增加供独立原生 Android 客户端使用的安全 OAuth 登录交接接口。服务端镜像仍包含 Go 后端、桌面 Web、移动 Web 和 PWA，无需另部署前端。

## 变更

- 新增原生 OAuth start / exchange / cancel 与已登录用户的 native link 入口，沿用现有 Pocket ID 和 HTTPS 回调，再交回固定 Android URI。
- 原生交接使用 PKCE S256、精确 state、短时一次性代码及受限临时令牌；返回 URI 不携带应用会话令牌、用户名或邮箱。支持取消、过期、请求大小及并发重放保护。
- 首次关联继续使用原有本地密码及适用的二步验证，保留同一 UID、已有账本和本地密码登录。公开注册和 OAuth 自动注册开关不放宽。
- OAuth 临时令牌消费失败立即拒绝，避免并发重复授权；原生提供方错误使用固定日志类别，避免将提供方响应及敏感内容写入日志。
- 未修改数据库 schema、Go 依赖或既有 Web 业务界面；前端仅随应用版本重新构建。

## 来源及工件

- 源码：`7d8ce0e16f0b5912a954518cea6e6c991c61f649`，标签 `family-v0.1.3`。
- 固定镜像：`ghcr.io/fjzhangzzzzzz/ezbookkeeping-family@sha256:73283d3aee2a7c25504a83fca475bde7b2b6c641a2274e58f063d260a4eafdeb`。
- 平台：`linux/amd64`，镜像 config ID：`sha256:f6ab5d4344f7812a6b67eed74f71cd8420e736fd56332f50323428fe7694f786`。
- [原始 GitHub Actions 流水线](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43593592805)的完整 Linux build、Go/前端检查及准确镜像 smoke 均成功。镜像已由该流水线推送，匿名 manifest/config 校验通过。
- 该流水线最终结论为失败：publish 最后创建 Release 时，冻结标签缺少发行说明文件。此 Release 由维护者复用原始成功构建 artifact 和既有镜像补完，**未重建或替换镜像，未改写标签**。build-info 的 publication 字段保留该事实，不能将整条原流水线称为成功。
- 原始 artifact 的 SHA256SUMS、image.tar.gz、smoke.json 已核验；Docker save 的 config 及全部 12 层与已发布镜像身份匹配。

附件 `ezbookkeeping-family-0.1.3-build-info.json` 记录准确源码、镜像、构建与补完来源，`SHA256SUMS` 用于完整性核对，不能替代数字签名。独立 Kotlin/Compose Android APK 使用后续 `android-v*` Release；本服务端 Release 不包含或冒称已发布 APK。

## 验收边界

此版本已经完成构建、测试与隔离合成协议检查。**发布时，homelab 的 0.1.3 正式 HTTPS、真实 Pocket ID/手机原生客户端及该版本备份恢复验收尚未完成**；原 0.1.2 的部署和验收不能自动代表 0.1.3 通过。

升级须使用 homelab 的不可变配置发布与 Backrest 一致性备份流程，保留匹配的数据、附件、配置密钥、源码和镜像恢复资料。本次新增 Release 和镜像标签不覆盖 `family-v0.1.2`、其附件或 0.1.2 镜像。
