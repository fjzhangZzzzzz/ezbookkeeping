# homelab 接入候选差异（供评审，未实施）

本文件是待审阅的目标差异，不是可直接应用的补丁；基于只读检查 `homelab-infra`。最终以当前基线重新生成正式 diff 并验证。镜像来源、镜像 digest、实际代理地址和证书均未确定，不得激活占位配置。

## DNS、网络和身份：本次拟申请的精确范围

- DNS：增加 `books.fjzhang233.top. A 139.196.45.16`；不新增 AAAA。实查该名称 A/AAAA 均为 NXDOMAIN。DNS 提供方及写入权限尚未确定；现有 Caddy 采用 HTTP-01，无已确认的 DNS API 写入口。
- EasyTier 中央 ACL：在现有 `vps_to_control` Stateful TCP 范围中，仅新增 edge-01 `10.144.144.1` → control-01 `10.144.144.2:18882/TCP`；保持其他 ACL、成员和凭据。按实时规则取得精确旧值、变更候选及回退，不从仓库生成整份覆盖。
- 宿主防火墙：控制节点 `inventory/hosts.yml` 的 control-01 `management_access` 中 source `10.144.144.1` 的 ports 加入 `18882`，同节点 `protected_tcp_ports` 加入 `18882`；`components` 加入 `ezbookkeeping`。根据现行模板生成 IPv4/IPv6 与 Docker 守卫候选并验证实际映射路径及其他来源拒绝，生成精确规则摘要供审批。不得只改 UFW 或假定 Docker 发布端口受 INPUT 保护。
- Pocket ID：用正式 UI/API 创建一个专用机密 OIDC 客户端，issuer `https://auth.fjzhang233.top`，回调 `https://books.fjzhang233.top/oauth2/callback`，PKCE S256；不改现有用户、客户端、签名身份或数据库。使用受限 SOPS 输入，不在 diff、日志、命令行或聊天中放入 client secret。源码路由为 `/oauth2/callback`；实际 Pocket ID claims、身份绑定、两种登录和 TLS 仍需验证。

## 仓库声明目标

`inventory/services.yml`：新增 `services.ezbookkeeping`，`configuration_owner: git`、`execution_owner: ansible`，control-01 Docker runtime 为 `homelab-ezbookkeeping-ezbookkeeping-1`、compose_service 和 artifact 均为 `ezbookkeeping`；依赖 `pocket-id`、`easytier`；路径为 `/srv/homelab/ezbookkeeping/current`、`/etc/homelab/ezbookkeeping`、`/var/lib/homelab/ezbookkeeping`；日志采用 Compose local 驱动轮转。健康检查 URL 先留待镜像实际验证后确定；Caddy dependencies 增加 `ezbookkeeping`，Pocket ID clients 加上述 launch/callback/PKCE。`backups.platform_services` 列表追加 `ezbookkeeping`，保留其他条目。

`inventory/artifacts.lock.json`：新增由正式干净源码构建的版本、来源 commit、linux/amd64 image reference 和不可变 digest；当前为 `PENDING_FINAL_IMAGE_DIGEST`，并锁定同一精确镜像的可恢复归档及 SHA256，不能用探索镜像 ID 或标签冒充 registry digest。

`stacks/caddy/Caddyfile`：仅新增以下独立站点，沿用既有脱敏日志和转发头，经过现行 Caddyfile 验证及证书/现有站点回归后激活；导入大小限制需结合产品实际限制另测。

```caddyfile
books.fjzhang233.top {
    import public_log
    reverse_proxy http://10.144.144.2:18882 {
        import proxy_headers
        transport http {
            dial_timeout 5s
            read_timeout 90s
            write_timeout 90s
        }
    }
}
```

应用自身鉴权，不设置全站 forward-auth；准确可信代理 IP 必须在实际链路捕获后再配置，不能推断为整个私网或 Docker 网段。Caddy 的 `public_log` 已删除 URI、Cookie、Authorization、Proxy-Authorization 和 Referer；还需核验应用日志对认证参数的处理。

`stacks/ezbookkeeping` 和 Ansible：基于本目录 Compose/INI 候选实现现有 services 角色、component 选择、prepare_only、配置敏感权限、部署/回退与检查；新建 `homelab-ezbookkeeping.service` 满足现行 Backrest 停写约定。生产不直接复制候选 Compose 文件。应用使用独立 SQLite 和附件目录，服务 UID/GID 1000；受限配置的 secret_key 和 OIDC secret 单独加密管理。

## Backrest 与恢复精确范围

保留 Backrest 已有计划时间、排除项、钩子及其他服务；先在 UI/API 读取现行计划并审阅最小差异，将 `/etc/homelab/ezbookkeeping`、`/var/lib/homelab/ezbookkeeping`（含 data、storage）、`/srv/homelab/ezbookkeeping/current` 所需真实发布工件和精确镜像加密归档/元数据纳入同一快照，另含匹配版本的运维发布依据。`backup-prepare.yml` 现按 `backups.platform_services` 生成单元和 `/etc`、`/var/lib` 必需路径，并按 artifact image_id 生成加密镜像归档要求；须验证新增服务与 artifacts 结构兼容，缺项明确失败。prepare 保存原运行状态、停写；finish 依原状态恢复，异常亦验证恢复；扩展恢复必需路径断言，禁止从现场 current 借缺失镜像或凭据。

正式数据前执行合成 SQLite、附件、登录和家庭范围的真实快照与隔离恢复；恢复旧快照后清理应用会话并核对停用成员。最终审批材料应包含生成的防火墙规则摘要、Caddy 实际 diff、精确来源/镜像摘要、Pocket ID 客户端属性及 Backrest UI 计划差异和回退步骤；本文不代表上述任何资源已创建、已批准或已验收。
