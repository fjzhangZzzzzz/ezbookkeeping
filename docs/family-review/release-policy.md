# 发布规范

## 术语

| 概念 | 约定 |
|---|---|
| 产品 | `ezbookkeeping-family` |
| 发布单元 | `webapp`、`android` |
| Webapp tag | `webapp-vX.Y.Z` |
| Android tag | `android-vX.Y.Z` |
| Webapp 镜像 | `ghcr.io/fjzhangzzzzzz/ezbookkeeping-family` |
| Android APK | `ezbookkeeping-android-X.Y.Z.apk` |

产品名可以出现在镜像、metadata 和 APK 文件名前缀中；发布单元名负责区分后端/Web 与 Android 生命周期。

## 查询正式发布

正式发布版本和工件以 [GitHub Releases](https://github.com/fjzhangZzzzzz/ezbookkeeping/releases) 为准。按 `webapp-v*` 和 `android-v*` 分别查询两个发布单元。源码构建版本以对应构建配置为准。仓库的发布说明记录每个版本的变更和验收范围。

家庭版维护 GitHub Actions 发布流程。

## Webapp

Webapp Release 包含 Go 后端、桌面 Web、移动 Web 和 PWA。发布前必须满足：

- tag 精确匹配 `webapp-vX.Y.Z`；
- `package.json`、`package-lock.json` 和 tag 版本一致；
- 对应 `docs/family-review/releases/webapp-vX.Y.Z.md` 存在且非空；
- CI 使用完整 Git 历史并记录准确源码 commit；
- 镜像使用产品仓库名，并以不可变 digest 作为部署锁定依据；
- Release metadata 和 `SHA256SUMS` 与实际构建产物一致。

## Android

Android 使用独立 Release，不因 Webapp 发布而重新发布或覆盖：

- tag 精确匹配 `android-vX.Y.Z`；
- `versionCode` 必须递增；
- APK 文件名为 `ezbookkeeping-android-X.Y.Z.apk`；这是客户端已发布版本使用的稳定 OTA 协议，不能改成产品前缀文件名；
- Release 同时包含 APK、`update.json` 和 `SHA256SUMS`；
- 签名身份、包名和 metadata 必须由 CI 对实际 APK 校验；
- Android 源码改动必须进入新的 Android Release，不能由 Webapp Release 代替。

## 独立性与协调

Webapp 与 Android 不共用版本号、签名、镜像或发布 tag。Webapp metadata 可以记录相对最近 Android tag 的源码关系，但该字段不代表 Android 已发布、已部署或已人工验收。

## 历史发布

旧 `family-v*` 发布线已经退役。历史资料不得作为当前 tag、Release、镜像或 APK 的发布入口；未来发布继续使用不可变的 `webapp-v*` 或 `android-v*`。
