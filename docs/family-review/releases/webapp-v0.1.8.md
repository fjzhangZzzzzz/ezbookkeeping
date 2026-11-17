
# webapp-v0.1.8

这是 Family 产品 Webapp 发布线启用 `webapp-vX.Y.Z` 命名后的首个版本。

- 产品：`ezbookkeeping-family`
- 发布单元：Webapp（Go 后端、桌面 Web、移动 Web 和 PWA）
- Android 客户端继续使用独立的 `android-vX.Y.Z` 发布线，不随本 Release 重新发布。
- 正式部署应使用 Release 元数据中的不可变镜像 digest，不使用可变版本 tag 作为部署锁定依据。

本版本的发布元数据、构建来源、镜像 digest、测试结果和 Android 关联状态由附件 `ezbookkeeping-family-webapp-0.1.8-release.json` 记录；`SHA256SUMS` 仅用于完整性校验，不是数字签名。

## 命名迁移

这是从旧 `family-vX.Y.Z` 发布线迁移到 `webapp-vX.Y.Z` 的一次性版本。旧 Family 公共 Release、tag 和 GHCR 镜像版本已在本次迁移完成后退役；这不是未来每次发布都删除旧版本的规则。后续 Webapp 发布继续使用不可变的 `webapp-vX.Y.Z` tag，Android 继续使用独立的 `android-vX.Y.Z` tag。
