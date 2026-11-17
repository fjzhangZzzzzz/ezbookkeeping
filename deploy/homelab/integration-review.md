# homelab 接入与激活边界

本目录是应用侧参考，不是另一份正式 Stack。部署、准入、配置发布和恢复以 `homelab-infra` 的 AGENTS、唯一 inventory/artifact 锁及 `docs/runbooks/ezbookkeeping.md` 为准；应用最新发行与生产状态见[交接](../../docs/family-review/homelab-handoff.md)。旧 2.4.149/本地离线镜像/Windows 前端草案已退出当前步骤，不再照旧申请 DNS 或重建身份。

## 已有接入

`books.fjzhang233.top` 的 DNS/HTTPS、edge 到 control-01 的现有 18882 回源和专用 Pocket ID 客户端已接入。issuer、回调和 PKCE S256 沿用现有配置，本地密码与原生 OIDC 同时保留，双注册关闭；不新增 Pocket ID、Passkey 或全站前置强制认证。准确主机身份、IP、可信代理及防火墙/中央 ACL 范围仍以当前 inventory 和实时核验为准，本文不复制正式 Caddy/ACL 规则或授权额外来源。

## 历史接入检查点：0.1.4 与 Android 0.1.1

- 后端以正式 `registry@sha256` 锁定；来源提交、manifest digest、config/image ID 与离线归档 SHA 分别记录，不能把旧离线恢复资料当新镜像。原生 OAuth bridge、SQLite schema 与既有业务 API 未改。
- 独立签名 APK 和 CI 原始 `update.json` / `SHA256SUMS` 由 Ansible 校验，不重写元数据、不覆盖同名不同内容工件。公开目录固定为 `/srv/homelab/ezbookkeeping/android`，只读挂载到同一 Go 容器 `/ezbookkeeping/public/android`；相对选择和不可变版本目录由独立组件管理。
- 自部署 HTTPS 的 `/android/update.json` 提供待安装版本，APK 只从 `/android/releases/<versionName>/<apkAssetName>` 下载。首次部署挂载按后端组件 prepare/激活流程；之后工件选版不重启后端，不激活共享 Caddy 或改动 Glance 路由，不扩大现有网络准入。
- 元数据缓存必须重验，版本 APK 为 immutable；匿名 GET/HEAD、真实 SHA/MIME/缓存头、无目录索引/越界、同版本幂等和失败回退分别验证。Android 签名、较高 versionCode 和用户确认安装不因服务端发布而跳过。旧公开 0.1.0/code2 保持不可变，手工一次升级到同签名 0.1.1/code3 后使用自托管检查。

## 备份、回退与现场边界

沿用 Backrest 唯一计划/执行和 Ansible 一致性流程。匹配数据、附件、配置密钥、源码、镜像恢复原包以及 Android 公共版本/相对选择必须在同一真实恢复点；缺项失败，不向生产 `current` 借材料。恢复副本隔离网络，开放前清会话、复核停用成员。切换旧更新清单不会降级已安装高 versionCode 的 APK；后端回退与客户端兼容性分别核验，跨 schema 失败使用匹配备份恢复。

当前应用为 Family 0.1.7、Android 0.1.6；应用源码、镜像与 APK 仍分别发行和锁定。生产运维包、Backrest、快照、恢复及现场状态不在本文件重复，以 `homelab-infra` 现行清单、状态页和 Runbook 为准。用户已确认实体手机自托管更新、密码和原 Pocket ID 同 UID、记账附件、重开保持登录、退出重登及桌面撤销手机会话；不涉及新登录用户或新账密。真实 OIDC+TLS 隔离恢复仍按运维仓库边界处理。不要根据旧草案重放建账号、改密、建客户端或网络变更。
