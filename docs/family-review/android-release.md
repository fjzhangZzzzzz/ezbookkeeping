# Android 签名、发布与更新

Android 原生客户端与服务端分别发行。正式包名固定为 `top.fjzhang233.books`，首个公开版为 `0.1.0` / versionCode `2`；`0.1.1` / code `3` 开始从自部署环境检查与下载更新，`minSdk=26`。版本唯一来源为 Android Gradle 配置，升级必须增加 versionCode 并保持包名及同一 release 签名身份。各自的不可变 tag、Release 和附件不因另一产品发布而覆盖或重打。

本地早期签名验证包 `0.1.0` / code `1` 从未公开发布，仅保留于 ignored `output/android-upgrade-fixture/` 作为合成旧版本输入。旧 GitHub OTA 完整演练未完成，不能称已验证发现、下载及系统安装闭环，也不能转用于现行自托管渠道。已公开的 `android-v0.1.0` / code `2` 工件不改写；其内置检查地址不会随服务端变更，应由用户从自家 HTTPS 地址手动安装同签名 `0.1.1` / code `3` 一次，之后使用自托管更新。保留登录会话和真实用户数据须另行实际验证，不从同签名推导已验收。

## 签名材料

专用 RSA-3072 PKCS12 release 身份只生成一次，不使用 debug key。证书 DER 的 SHA256：

`6fe857a17e79298dee70334a4900df8935312bf312ef9f9ad6a23897a9f3735d`

私有原件位于维护电脑应用仓库的 `output/android-signing/`，整个目录受当前 Windows 用户和 SYSTEM 专有 ACL 保护，已被 Git 忽略。离线备份需要整个目录（release.p12、密码文件和别名文件），不能只备份公开证书。不要提交、上传 Release 或贴出任何私钥、密码和完整环境变量。密钥丢失会阻断后续覆盖升级；不要重新生成同名 key 作为补救。

仓库 Actions Secrets：`ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。上传使用 GitHub CLI stdin；本机 keytool 使用 `-storepass:env` / `-keypass:env`。工作流只在构建步骤提供 Gradle 四个环境变量（路径名为 `ANDROID_KEYSTORE_PATH`），密钥解码到 runner 临时目录并在 always 步骤删除。OIDC client secret 和生产应用登录凭据不得进入 APK、Gradle 或这些 Secrets。

## 构建及发布

入口 `.github/workflows/android-release.yml` 使用固定提交的官方 Actions、GitHub 公开仓库 runner 和最小权限。`family/initial` 或 `android/**` 分支 push，以及涉及 Android/此工作流的 PR，先运行不读取 Secrets 的 debug 单测、lint 和组装；fork PR 不接触签名作业。手动 workflow_dispatch 只构建签名制品供审阅；只有新的 `android-vX.Y.Z` tag 才进入发布作业。首次 push/tag 前需主会话审阅和授权。不得覆盖已有 tag、Release 或附件，工作流不使用 clobber。

Gradle Wrapper 固定 8.13，执行前核对官方 wrapper JAR SHA256，properties 固定官方 distributionSha256Sum；这两个摘要与 workflow 一起审阅。SDK platform 固定 android-36、build-tools 固定 36.0.0，通过 sdkmanager 幂等安装。协议单测在 debug verify 作业完成（允许仅本机模拟 HTTP）；release 作业运行 lintRelease、assembleRelease，不为通过测试放宽 release 网络安全策略；随后用 Android 官方 apksigner 验证实际 APK 签名并与上面的固定证书指纹比较，用 aapt 验证包名、版本、minSdk 和非 debuggable。metadata 来自实际验证的 APK。发布作业只下载这份构建制品并检查 SHA256SUMS，不重新构建。只发布独立 Android Release，不更换运行中的服务端镜像。

新 Release 包含 `ezbookkeeping-android-X.Y.Z.apk`、`update.json`、`SHA256SUMS`。GitHub 自动源码归档不属于可安装 APK。工作流只使用免费公开 runner，不启用付费 runner、缓存或额外服务。

正式 `android-v0.1.1` / code `3` 已由 [tag CI 43621801593](https://github.com/fjzhangZzzzzz/ezbookkeeping/actions/runs/43621801593) 完成 verify/build/publish；源码 `f8da00a8fcf23ff4412db8bf792c6f0f60a32353`，实际 APK SHA256 `133b73c664de942842dbff5e6f105d8a16bd9bdfdccdc1e8b13d01735af2fc29`。独立签名/包版本和匿名公开三件套与正式 CI 原件核验通过；目前已在自部署 books HTTPS 激活；用户已确认实体手机安装正式包、显示自托管更新源并检查最新版；这仍不能替代真实已登录跨版本升级验收。

## update.json 契约与信任边界

schemaVersion=1，channel 固定 stable；字段 packageId、versionName、整数 versionCode、整数 minSdk、apkAssetName、apkSha256、signingCertificateSha256、sourceCommit。tag 必须精确等于 `android-v` + versionName，APK asset 名必须精确匹配 `ezbookkeeping-android-` + versionName + `.apk`。sourceCommit 是构建源码 commit，不能替代 APK SHA 或签名。

客户端只匿名访问固定自部署地址 `https://books.fjzhang233.top/android/update.json`，不发送应用 Bearer Token；APK 路径由已验证的 versionName 与 apkAssetName 固定导出为 `/android/releases/<versionName>/<apkAssetName>`。不接受任意下载 URL、跨源跳转或 GitHub API/asset 回退。检查元数据时要求重新验证缓存并限制响应大小，忽略相同及更低 versionCode；更新提示后由用户确认下载，再交系统安装器确认安装及所需的安装来源权限。

安装前校验协议格式、包名、minSdk、更高 versionCode、下载 SHA256、实际 APK 包名和版本，以及实际 signer 与应用内固定公开 pin/当前安装 signer 一致。GitHub 仅保留不可变 CI/发行来源和部署取件渠道，不再是客户端检查或下载地址。

APK 和原始 CI `update.json` / `SHA256SUMS` 为独立工件，不嵌入后端镜像，也不在 homelab 重写 metadata。Ansible 验证正式来源后选择版本；稳定宿主父目录 `/srv/homelab/ezbookkeeping/android` 只读挂载到 Go 容器 `/ezbookkeeping/public/android`，`current` 使用根内相对链接指向 `releases/<versionName>`，根 `update.json` 指向 `current/update.json`。服务端 `0.1.4` 提供清晰 `/android` 静态路由，未挂工件时返回 404；manifest 要求缓存重验，版本化 APK 使用不可变缓存，不列举目录或暴露其它文件。独立 APK 切换不重建后端镜像、不新增服务端口、不激活共享 Caddy；首次增加挂载按 homelab 标准组件流程完成。备份恢复包括匹配后端、公共工件与选择依据；切回旧 manifest 不会降级已安装的高 versionCode 客户端。

update.json 和 SHA256SUMS 是来源描述及完整性清单，**不是签名信任根**；不能用其中自报的证书替代应用内固定 pin、当前安装证书和 Android 安装器的签名校验。不能降级或通过换包名绕开版本/签名保护。签名轮换另行设计和批准，当前不支持静默轮换。

## 验收范围

自托管实际系统安装已在专用模拟器验证：内部 `0.1.1-internal-selfhost` / code2 → 官方 `0.1.1` / code3，正常界面检测、用户确认下载、客户端校验、系统来源许可、PackageInstaller Update 与安装成功；实际升级不使用 adb install。读回已安装 APK 完整 SHA256 与正式 CI 原件一致，唯一 signer 匹配固定 pin，重开应用并再次检查显示当前兼容最新版。初始内部旧包为独立获准准备，不是旧公开 0.1.0 客户端；该测试未输入凭据，不证明真实用户登录跨升级保持。

发布前检查本地构建、正式签名/指纹、metadata 与 APK 一致、更新筛选和拒绝错误包；Actions 构建和 Release 发布分别记录结果。用户已明确确认实体手机正式包/更新源/查最新版、本地密码及原 Pocket ID Passkey 同原数据、桌面撤销手机会话生效。仍待补验的是现有登录用户下的 CNY资金账户记账、附图、关闭重开保持登录及退出重登；“资金账户”没有新登录账密，不创建额外 CLI 用户。真实已登录跨版本保持和真实 Pocket ID + TLS 恢复仍未验，不能由这些结果推导通过。服务端 0.1.4/Android 公共工件的真实同点隔离业务恢复已完成，准确范围见 [homelab 交接](homelab-handoff.md)。

参考：[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub CLI stdin](https://cli.github.com/manual/gh_secret_set)、[keytool 密码环境变量](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html)。
