# ezBookkeeping Family 0.1.4

本版本增加自部署 Android 更新工件的静态分发入口。APK 独立签名和发布，由部署环境按版本选择并只读挂载；后端镜像不包含 APK，不新增服务、端口或下载代理。

## 变更

- 匿名 `GET` / `HEAD /android/update.json` 提供部署环境所选择的更新元数据，显式要求缓存重新验证。
- `GET` / `HEAD /android/releases/<versionName>/<apkAssetName>` 提供不可变版本化 APK，支持标准条件请求及 Range，并明确 MIME 与缓存策略。
- 静态根目录为 `static_root_path` 下的 `android`，默认 `/ezbookkeeping/public/android`。目录为空或工件未挂载时返回 404；不列举目录，不开放点文件、其他任意文件、路径越界或逃出根目录的软链接。
- `update.json` 可使用根目录内的相对软链接选择版本。发布目录须只读挂载并由 Ansible 管理，版本化 APK 不覆盖；不修改共享 Caddy 配置。
- 不修改数据库 schema、业务 API、原生 OAuth bridge、本地密码或 Pocket ID 登录机制。既有 Web/PWA 随正常完整 Linux 流水线构建。

## 发布与升级边界

本说明随待发布的 `family-v0.1.4` 源码提交保存。准确源码提交、镜像 digest 与 config ID 由成功构建后的 Release `build-info.json` 记录，不预填或复用 0.1.3 标识。

Android 客户端独立版本 `0.1.1` / versionCode `3` 使用同一签名身份，从自部署 HTTPS 地址检查与下载更新；旧 Android `0.1.0` / code `2` 的不可变标签和工件不改写。本后端 Release 不冒称 APK 已发布、已挂载或真实手机已验收。

升级沿用 homelab 的版本化配置、升级前 Backrest 一致性备份与隔离恢复流程。备份恢复须包含匹配后端镜像、数据、配置及所选 Android APK/manifest 工件。切回旧更新元数据不会降级已安装的高 versionCode 客户端；后端与客户端兼容性需分别核验。完整 Linux Go/Web CI 与正式部署、HTTPS 静态分发、真实手机和自更新安装验收仍由发布与部署阶段记录。