# Android 签名、发布与更新

Android 原生客户端与服务端分别发行。正式包名固定为 `top.fjzhang233.books`，首版 `versionName=0.1.0`、`versionCode=1`、`minSdk=26`；版本唯一来源为 Android Gradle 配置。升级必须增加 versionCode，保持包名及 release 签名身份。服务端 `family-v0.1.2` 不因 Android 发布而覆盖或重打标签。

## 签名材料

专用 RSA-3072 PKCS12 release 身份只生成一次，不使用 debug key。证书 DER 的 SHA256：

`6fe857a17e79298dee70334a4900df8935312bf312ef9f9ad6a23897a9f3735d`

私有原件位于维护电脑应用仓库的 `output/android-signing/`，整个目录受当前 Windows 用户和 SYSTEM 专有 ACL 保护，已被 Git 忽略。离线备份需要整个目录（release.p12、密码文件和别名文件），不能只备份公开证书。不要提交、上传 Release 或贴出任何私钥、密码和完整环境变量。密钥丢失会阻断后续覆盖升级；不要重新生成同名 key 作为补救。

仓库 Actions Secrets：`ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。上传使用 GitHub CLI stdin；本机 keytool 使用 `-storepass:env` / `-keypass:env`。工作流只在构建步骤提供 Gradle 四个环境变量（路径名为 `ANDROID_KEYSTORE_PATH`），密钥解码到 runner 临时目录并在 always 步骤删除。OIDC client secret 和生产应用登录凭据不得进入 APK、Gradle 或这些 Secrets。

## 构建及发布

入口 `.github/workflows/android-release.yml` 使用固定提交的官方 Actions、GitHub 公开仓库 runner 和最小权限。`family/initial` 或 `android/**` 分支 push，以及涉及 Android/此工作流的 PR，先运行不读取 Secrets 的 debug 单测、lint 和组装；fork PR 不接触签名作业。手动 workflow_dispatch 只构建签名制品供审阅；只有新的 `android-vX.Y.Z` tag 才进入发布作业。首次 push/tag 前需主会话审阅和授权。不得覆盖已有 tag、Release 或附件，工作流不使用 clobber。

Gradle Wrapper 固定 8.13，执行前核对官方 wrapper JAR SHA256，properties 固定官方 distributionSha256Sum；这两个摘要与 workflow 一起审阅。SDK platform 固定 android-36、build-tools 固定 36.0.0，通过 sdkmanager 幂等安装。协议单测在 debug verify 作业完成（允许仅本机模拟 HTTP）；release 作业运行 lintRelease、assembleRelease，不为通过测试放宽 release 网络安全策略；随后用 Android 官方 apksigner 验证实际 APK 签名并与上面的固定证书指纹比较，用 aapt 验证包名、版本、minSdk 和非 debuggable。metadata 来自实际验证的 APK。发布作业只下载这份构建制品并检查 SHA256SUMS，不重新构建。只发布独立 Android Release，不更换运行中的服务端镜像。

新 Release 包含 `ezbookkeeping-android-X.Y.Z.apk`、`update.json`、`SHA256SUMS`。GitHub 自动源码归档不属于可安装 APK。工作流只使用免费公开 runner，不启用付费 runner、缓存或额外服务。

## update.json 契约与信任边界

schemaVersion=1，channel 固定 stable；字段 packageId、versionName、整数 versionCode、整数 minSdk、apkAssetName、apkSha256、signingCertificateSha256、sourceCommit。tag 必须精确等于 `android-v` + versionName，APK asset 名必须精确匹配 `ezbookkeeping-android-` + versionName + `.apk`。sourceCommit 是构建源码 commit，不能替代 APK SHA 或签名。

客户端从固定仓库的正式非 draft、非 prerelease Android Release 检查更新，忽略 family-v 标签及低版本。只取同一 Release 上的指定 APK asset，不接受 metadata 提供任意下载地址。校验协议格式、包名、minSdk、更高 versionCode、下载 SHA256、下载 APK 包名和版本，以及实际 APK signer 与固定公开 pin/当前安装 signer 一致后，才交给 Android 系统安装器；用户仍需确认安装及按系统要求允许安装来源。

update.json 和 SHA256SUMS 是来源描述及完整性清单，**不是签名信任根**；不能用其中自报的证书替代应用内固定 pin、当前安装证书和 Android 安装器的签名校验。不能降级或通过换包名绕开版本/签名保护。签名轮换另行设计和批准，当前不支持静默轮换。

## 验收范围

发布前检查本地构建、正式签名/指纹、metadata 与 APK 一致、更新筛选和拒绝错误包；Actions 构建和 Release 发布分别记录结果。真实 Android 安装、Pocket ID 系统浏览器回调、退出/再次登录、附件与下一版本升级仍需设备验收，不能由构建成功推定通过。

参考：[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub CLI stdin](https://cli.github.com/manual/gh_secret_set)、[keytool 密码环境变量](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html)。
