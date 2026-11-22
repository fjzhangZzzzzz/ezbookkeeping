# Android 签名、发布与更新

Android 原生客户端与服务端分别发行。正式包名固定为 `top.fjzhang233.books`，最低 API 为 `26`。首个公开版为 `0.1.0` / versionCode `2`，从 `0.1.1` / code `3` 起支持自部署环境更新。源码构建版本以 Android Gradle 配置为准。升级保持包名、release 签名身份，并递增 versionCode。已发布的 Git tag 和附件内容保持不变，旧 Release 按发布规范退役。

Android 正式版本和工件查询见[发布规范](release-policy.md#查询正式发布)。

本地早期签名验证包 `0.1.0` / code `1` 从未公开发布，仅保留于 ignored `output/android-upgrade-fixture/` 作为合成旧版本输入。旧 GitHub OTA 完整演练未完成，不能称已验证发现、下载及系统安装闭环，也不能转用于现行自托管渠道。已公开的 `android-v0.1.0` / code `2` 工件不改写；其内置检查地址不会随服务端变更，应由用户从自家 HTTPS 地址手动安装同签名 `0.1.1` / code `3` 一次，之后使用自托管更新。保留登录会话和真实用户数据须另行实际验证，不从同签名推导已验收。

## 签名材料

专用 RSA-3072 PKCS12 release 身份只生成一次，不使用 debug key。证书 DER 的 SHA256：

`6fe857a17e79298dee70334a4900df8935312bf312ef9f9ad6a23897a9f3735d`

私有原件位于维护电脑应用仓库的 `output/android-signing/`，整个目录受当前 Windows 用户和 SYSTEM 专有 ACL 保护，已被 Git 忽略。离线备份需要整个目录（release.p12、密码文件和别名文件），不能只备份公开证书。不要提交、上传 Release 或贴出任何私钥、密码和完整环境变量。密钥丢失会阻断后续覆盖升级；不要重新生成同名 key 作为补救。

仓库 Actions Secrets：`ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。上传使用 GitHub CLI stdin；本机 keytool 使用 `-storepass:env` / `-keypass:env`。工作流只在构建步骤提供 Gradle 四个环境变量（路径名为 `ANDROID_KEYSTORE_PATH`），密钥解码到 runner 临时目录并在 always 步骤删除。OIDC client secret 和生产应用登录凭据不得进入 APK、Gradle 或这些 Secrets。

## 构建及发布

Android CI 与 Release 分离。Android Markdown 变更执行文档差异检查。源码、资源和 Gradle 变更执行 debug 单测、Lint 和组装。Android 发布脚本变更执行语法和合成工件协议检查。混合变更执行对应的各项检查。新的 `android-vX.Y.Z` tag 触发签名构建、实际 APK 校验与独立 Release 发布。设备和安装升级回归使用本地固定 AVD 脚本。

本地脚本 `scripts/release/android_local_preflight.ps1` 在 SDK、ADB 和选定 AVD 可用时执行必要的 debug smoke；涉及更新器或安装升级的变更，再使用本地真实签名工件执行完整升级回归。缺少必要依赖时必须先确认，不自动安装或静默跳过。

执行升级回归时，从上一条正式 Android Release 下载 APK、`update.json` 和 `SHA256SUMS`，使用 `validate_android_update.py` 对照 tag 和源码提交校验工件。在干净的验收 AVD 上运行 `android_local_preflight.ps1 -Mode update -AvdName <AVD> -ArtifactDir <候选工件目录> -BaselineApk <上一正式版APK>`。基线 APK 旁保留原 `update.json`。脚本核对两个 APK 的签名、摘要、包名和 `versionCode`，用本地 HTTP 验证候选下载路径，再用 ADB 依次安装基线和候选。

Android tag、Release 和附件均不可覆盖；发布命令使用 `--verify-tag`，遇到已存在的 Release 直接失败，不通过新版本号反复试错。

Gradle Wrapper 固定 8.13，执行前核对官方 wrapper JAR SHA256，properties 固定官方 distributionSha256Sum；这两个摘要与 workflow 一起审阅。SDK platform 固定 android-36、build-tools 固定 36.0.0，通过 sdkmanager 幂等安装。协议单测在 Android CI 完成；release 作业运行 lintRelease、assembleRelease，不为通过测试放宽 release 网络安全策略；随后用 Android 官方 apksigner 验证实际 APK 签名并与固定证书指纹比较，用 aapt 验证包名、版本、minSdk 和非 debuggable。metadata 来自实际 APK。发布作业只下载这份构建制品并检查 SHA256SUMS，不重新构建、不构建后端或其他平台。模拟器回归不放在 GitHub Actions 中。只发布独立 Android Release，不更换运行中的服务端镜像。

新 Release 包含 `ezbookkeeping-android-X.Y.Z.apk`、`update.json`、`SHA256SUMS`。GitHub 自动源码归档不属于可安装 APK。工作流在生成真实清单后使用 `scripts/release/validate_android_update.py` 按客户端同一文件名、路径、摘要和清单规则复核，并在发布 job 再次复核；工作流只使用免费公开 runner，不启用付费 runner、托管模拟器或额外服务。

### 本地 QA 清理

- 人工验收期间保持本轮专用模拟器和隔离测试服务可用。收到清理指令后，按设备序列号和 AVD 名称、进程号和监听端口、文件的实际路径核对资源归属。
- 关闭本轮模拟器、镜像窗口和测试服务后，删除本轮专用的临时 AVD、测试数据库、临时凭据文件、调试 APK 和下载残片。确认相关进程已退出、端口已释放，待清理路径已移除。
- 保留可复核的脱敏截图、测试结果和评审记录，以及共用 SDK、可复用 AVD、正式发布工件和其他轮次的测试环境。

## 与 Webapp 发布协调

Webapp 与 Android 仍是独立发布通道，不共用版本号、签名或镜像。Webapp Release 的 `release.json` 增加 `companion_artifacts.android`：工作流比较当前 Webapp 提交与最近 `android-v*` 标签之间的 `android/` 目录；无差异记为 `unchanged`，有差异但尚无新的 Android Release 记为 `pending`。该字段只描述源码相对关系，不代替 APK 的签名、摘要或自托管部署状态。

本地创建 Webapp 或 Android 标签前，必须先检查工作树和相对最近 Android 标签的 Android 差异；未提交的 Android 改动不会出现在 GitHub Actions 中，不能称为已发布。`pending` 不阻止 Webapp 独立发布，但不得将 Webapp 与 Android 的整体验收写成通过；产品发布完整性只在对应 Release、工件和真实设备范围内分别判断。

历史 `android-v0.1.1` / code `3` 已由 [tag CI 43621801593](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43621801593) 完成 verify/build/publish；源码 `f8da00a8fcf23ff4412db8bf792c6f0f60a32353`，实际 APK SHA256 `133b73c664de942842dbff5e6f105d8a16bd9bdfdccdc1e8b13d017af2fc29`。独立签名/包版本和匿名公开三件套与正式 CI 原件核验通过；当时已在自部署 books HTTPS 激活；用户已确认实体手机安装正式包、显示自托管更新源并检查最新版；这仍不能替代真实已登录跨版本升级验收。

## update.json 契约与信任边界

schemaVersion=1，channel 固定 stable；字段 packageId、versionName、整数 versionCode、整数 minSdk、apkAssetName、apkSha256、signingCertificateSha256、sourceCommit。tag 必须精确等于 `android-v` + versionName，APK asset 名必须精确匹配 `ezbookkeeping-android-` + versionName + `.apk`。sourceCommit 是构建源码 commit，不能替代 APK SHA 或签名。CI 生成的 `update.json`、APK 和 `SHA256SUMS` 必须互相指向同一文件名和摘要，不能通过手工改清单修复协议冲突。

客户端只匿名访问固定自部署地址 `https://books.fjzhang233.top/android/update.json`，不发送应用 Bearer Token；APK 路径由已验证的 versionName 与 apkAssetName 固定导出为 `/android/releases/<versionName>/<apkAssetName>`。不接受任意下载 URL、跨源跳转或 GitHub API/asset 回退。检查元数据时要求重新验证缓存并限制响应大小，忽略相同及更低 versionCode；更新提示后由用户确认下载，再交系统安装器确认安装及所需的安装来源权限。

安装前校验协议格式、包名、minSdk、更高 versionCode、下载 SHA256、实际 APK 包名和版本，以及实际 signer 与应用内固定公开 pin/当前安装 signer 一致。GitHub 仅保留不可变 CI/发行来源和部署取件渠道，不再是客户端检查或下载地址。

APK 和原始 CI `update.json` / `SHA256SUMS` 为独立工件，不嵌入后端镜像，也不在本仓库重写 metadata。部署侧应验证正式来源后选择版本；签名密钥、密码、INI、凭据及下载 staging 不进入公开工件。切回旧 manifest 不会降级已安装的高 versionCode 客户端。

update.json 和 SHA256SUMS 是来源描述及完整性清单，**不是签名信任根**；不能用其中自报的证书替代应用内固定 pin、当前安装证书和 Android 安装器的签名校验。不能降级或通过换包名绕开版本/签名保护。签名轮换另行设计和批准，当前不支持静默轮换。

## 验收范围

自托管实际系统安装已在专用模拟器验证：内部 `0.1.1-internal-selfhost` / code2 → 官方 `0.1.1` / code3，正常界面检测、用户确认下载、客户端校验、系统来源许可、PackageInstaller Update 与安装成功；实际升级不使用 adb install。读回已安装 APK 完整 SHA256 与正式 CI 原件一致，唯一 signer 匹配固定 pin，重开应用并再次检查显示当前兼容最新版。初始内部旧包为独立获准准备，不是旧公开 0.1.0 客户端；该测试未输入凭据，不证明真实用户登录跨升级保持。

发布前检查本地构建、正式签名和指纹、metadata 与 APK 一致性、更新筛选及错误包拒绝。Actions 构建和 Release 发布分别记录结果。

历史验收使用 Android `0.1.6`。用户在实体手机上确认了自托管更新、密码及原 Pocket ID 同 UID、CNY 资金账户记账和附图、关闭重开保持登录、退出重登及桌面撤销手机会话。尚需独立验证真实已登录跨版本保持和部署侧恢复。

参考：[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub CLI stdin](https://cli.github.com/manual/gh_secret_set)、[keytool 密码环境变量](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html)。
