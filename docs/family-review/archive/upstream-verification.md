# 上游版本、安全公告与发布链核验

核验日期：2026-11-13。资料：实际本地 Git 对象、工作流、Dockerfile；GitHub 上游 Release / Security Advisories / Issue；CVE Program 与 NVD。这里区分公告原文、源码观察和未确认事项；没有把搜索摘要当成修复证明。

## 1. 基线：当前 fork 是开发版

| 项目 | 核验结果 |
|---|---|
| 最新公开稳定 Release | **v2.1.1**，2026-10-23，GitHub 页面标为 Latest |
| 稳定版精确提交 | **3dcc316e1fb6a190d6a0dd13fbe4052d17b9315c** |
| 当前工作树 HEAD | **82920c62fd774b95eaed7fe78938675b56b73cf2** |
| 两者关系 | `git merge-base --is-ancestor 3dcc316 HEAD` 返回 0；`git log 3dcc316..HEAD --oneline` 有 96 条 |
| 当前版本声明 | `package.json:3`：`"version": "2.2.0"`，尚不能视为 2.2.0 稳定发布 |
| Git 完整性范围 | `git rev-parse --is-shallow-repository` 返回 false；本地没有 v2.1.1 tag，但其 commit 对象存在 |
| 远程 | 当前只配置用户 fork 的 origin；没有在本次检查中修改 remote / tag / 分支 |

来源：[v2.1.1 Release](https://github.com/mayswind/ezbookkeeping/releases/tag/v2.1.1)、[Release 列表](https://github.com/mayswind/ezbookkeeping/releases)。网页显示稳定提交为 GitHub verified signature；这不是镜像内容证明。

**版本建议：已确认的认证/IP 公告修复下限为 ≥2.1.0；本次二次开发应固定最新稳定 v2.1.1 的上述精确提交。** 但 CVE-2025-65519 的首个修复版未能确认，因此不能给出“版本达到某值就全部漏洞清零”的结论。上线还需完成导入 DoS 检查及依赖扫描。

当前源码证据默认对应 HEAD，不自动适用于稳定版。稳定版到 HEAD 的 96 条提交包含令牌实现的大改动：`d54b7326`（base62 与令牌签名）、`f993fcbc`（map proxy 派生令牌）、`38c60dbe`（限制 parent token 使用）、`7d4c9a34`（验证与找回密码令牌改用请求头）。本附录另通过 `git show 3dcc316:<path>` 复核刷新、TOTP、代理及导入关键路径；没有对 v2.1.1 全仓逐行审计。正式冻结稳定基线后，应重跑主报告全部检查。

## 2. 上游公开 Security Advisories 全清单

访问[上游公告列表](https://github.com/mayswind/ezbookkeeping/security/advisories)，页面显示以下 **4 条**，没有显示下一页链接；逐条打开详情。此为当次匿名可见完整清单，不含私有草稿。REST `per_page=100` 请求失败；显式 `?page=2` 也未能抓取，不能声称通过 API 分页证明全集。GitHub Advisory Database 对 CVE 的镜像记录不是新增上游公告。

| 公告 | 发布时间 | 影响范围（公告元数据） | 修复版本 | 备注 |
|---|---|---|---|---|
| [GHSA-wchh-mrv2-5h4q](https://github.com/mayswind/ezbookkeeping/security/advisories/GHSA-wchh-mrv2-5h4q) | 2026-07-19 | 0.10.0–1.5.1 | 1.6.0 | 伪造 XFF 绕过 MCP / API token 来源 IP 白名单，CVE-2026-86041 |
| [GHSA-p6qr-48g6-97q3](https://github.com/mayswind/ezbookkeeping/security/advisories/GHSA-p6qr-48g6-97q3) | 2026-09-16 | ≤1.6.1 | 2.0.0 | TOTP 使用后未失效；公告页仍显示 No known CVE，CVE Program 已将其关联 CVE-2026-94112 |
| [GHSA-wq25-mpcf-2mfc](https://github.com/mayswind/ezbookkeeping/security/advisories/GHSA-wq25-mpcf-2mfc) | 2026-09-25 | 1.2.0–2.0.0 | 2.0.1 | API 令牌换取普通会话，CVE-2026-105131 的 NVD 引用此公告 |
| [GHSA-537p-h8qj-p2gr](https://github.com/mayswind/ezbookkeeping/security/advisories/GHSA-537p-h8qj-p2gr) | 2026-10-15 | 2.0.0–2.0.1 | 2.1.0 | **需求清单遗漏**：30s 去重 TTL 与 ±1 时间步有效期不一致，且非原子 check/set；未标 CVE |

最后一条公告正文保留了披露时“候选、待动态复现、修复待确认”的旧描述，而顶部元数据已写修复 2.1.0。这里不照抄过时文字作为当前状态：另以本地 Git 的 `c9344464ce0c88f6a1c9ff9c4fb0e18a730abc5c` 和 `060ba380d4638b6bacb36abb1c626686e17f0db8` 核对分别关闭 skew、改为带互斥锁的消费；两者均已在稳定提交内。

## 3. 需求所列五个 CVE 核验

| CVE | 实际确认的受影响/修复信息 | 本地及稳定版判断 |
|---|---|---|
| [CVE-2024-57604](https://nvd.nist.gov/vuln/detail/cve-2024-57604) | NVD 明确提到 0.7.0 token 权限问题；其引用的 [Issue #33](https://github.com/mayswind/ezbookkeeping/issues/33) 实际描述登录和 2FA 恢复码暴力破解。NVD 没给精确修复版本，不应解释成 JWT 任意签发漏洞。Issue 的修复提交 `74844b9a994affdfb7913bd212b94a9f91f66368`、里程碑 0.8.0 支持“0.8.0 引入相关限流”的判断；不是用 NVD 证明所有 ≤0.7.0 的精确范围 | 修复提交是 3dcc316 和 HEAD 的祖先；当前 `pkg/api/base.go:181` 起存在按 IP/uid 失败计数。静态确认限流逻辑，不等于暴力破解压力测试通过 |
| [CVE-2025-65519](https://github.com/advisories/GHSA-f2fg-5m3g-hqwv) | 后续追溯到[原始披露者报告](https://github.com/ictrun/EBK-SA-2025-001)：声称 ≤1.2.0，JSON/GnuCash/OFX/CAMT 导入；展示 v1.2.0 受限 Docker 并发 XML 复现。GitHub 全局公告有此 CVE，标为 Unreviewed，Patched versions Unknown。**修复提交及首个修复版未能确认**；不得把第三方库“建议升级1.3.0”当上游修复证据 | HEAD 和稳定版仍有 `json.Unmarshal` 与 `xml.Decoder.Decode` 路径；没找到应用层统一深度预算。标准库可能自带限制，不能仅凭搜索缺失定漏洞，也不能判定已修复。披露报告对 Go 默认没有深度限制的概括需对对应 Go 版本实测，不直接采信。需限制测试进程资源、针对导入 API 复现并查 Go 对应版本实现 |
| [CVE-2026-94112](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/94xxx/CVE-2026-94112.json) | CVE Program：版本 <2.0.0；原始修复 `3dd6286d7a3ab0f980a6d36339b9c9c4df9467e4`；2.0.0 修复原始问题，但残余窗口/并发问题见后续 GHSA，最终应 ≥2.1.0 | 当前 `pkg/services/twofactor_authorizations.go:24` skew=0、`:100` 自定义校验；`pkg/api/authorizations.go:637` 原子消费入口；`pkg/duplicatechecker/in_memory_duplicate_checker.go:52` 互斥锁。stable 同样含关闭 skew 与原子消费路径。多实例间/重启后的消费一致性未验证 |
| [CVE-2026-105131](https://nvd.nist.gov/vuln/detail/cve-2026-105131) | NVD：≥1.2.0,<2.0.1；修复 `9a92b07be8a7034665abfdb35b036210a1d57bf9`，2.0.1。公告还说明收窄 API 令牌管理权限 | stable TokenRefreshHandler 显式检查 `oldTokenClaims.Type != core.USER_TOKEN_TYPE_NORMAL`；HEAD 已把类型检查移至 `cmd/webserver.go:336` 路由声明 NORMAL，`:589` wrapper、`:594` CheckCurrentTokenType，在 handler 执行前拒绝，不能把旧版 handler 行号生搬到 HEAD |
| [CVE-2026-86041 上游公告](https://github.com/mayswind/ezbookkeeping/security/advisories/GHSA-wchh-mrv2-5h4q) | 0.10.0–1.5.1，1.6.0 修复；补丁 `d79a5beb8037e529dc4dc64a6d22bd2d02b2e986` | 当前 `cmd/webserver.go:107` 调用 SetTrustedProxies，`:548` 等将配置传入 WebContext；`pkg/settings/setting.go:197` 默认仍信任大段私网。stable 同样有 SetTrustedProxies。部署必须缩小到实际代理，阻止用户直连后端；家庭授权不得依赖 IP |

未声称五条以外没有 CVE。为发现遗漏还检索了项目与 CVE 的组合；能看到 CVE-2024-57603 也是 #33 的登录暴力破解相关问题。它与 #33 修复路径合并跟踪，不应把“上游公告 4 条”解释成“世界上仅有 4 个漏洞”。

## 4. 关键源码证据

以下为 HEAD 行号，旧稳定源码证据必须在 GitHub 链接选择固定提交，不能与 HEAD 混用。

```go
// cmd/webserver.go:107
err = router.SetTrustedProxies(config.TrustedProxyTextualIPs)

// pkg/settings/setting.go:197
defaultTrustedProxyIPs string = "10.0.0.0/8,169.254.0.0/16,127.0.0.0/8,172.16.0.0/12,192.168.0.0/16"

// pkg/services/twofactor_authorizations.go:24
twoFactorSkew uint = 0

// pkg/duplicatechecker/in_memory_duplicate_checker.go:52-53
c.mutex.Lock()
defer c.mutex.Unlock()
```

[stable TokenRefreshHandler](https://github.com/mayswind/ezbookkeeping/blob/3dcc316e1fb6a190d6a0dd13fbe4052d17b9315c/pkg/api/tokens.go)、[stable TOTP service](https://github.com/mayswind/ezbookkeeping/blob/3dcc316e1fb6a190d6a0dd13fbe4052d17b9315c/pkg/services/twofactor_authorizations.go)、[stable duplicate checker](https://github.com/mayswind/ezbookkeeping/blob/3dcc316e1fb6a190d6a0dd13fbe4052d17b9315c/pkg/duplicatechecker/in_memory_duplicate_checker.go)。这些文件通过本地 `git show` 实际读取，链接用于复核。

## 5. S5：构建/发布链

读取 Dockerfile、docker-bake.hcl、release/snapshot 工作流及 build/push composite actions，观察如下：

| 实际证据 | 判断 |
|---|---|
| `Dockerfile:2,22,40` 分别为 Go builder、Node builder、Alpine runtime；`:51` 起从 builder COPY 产物 | 多阶段构建；HEAD 为 golang:1.27.3-alpine3.24 / node:26.13.0-alpine3.24 / alpine:3.24.3。通过 git show 查到 stable 则为 Go1.27.1 / Node26.10.0 / Alpine3.24.2。不能混同 |
| `Dockerfile:56`：USER 1000:1000 | 默认运行非 root；入口脚本尚须与主报告共同核实，不能仅看到建用户就推断 |
| `Dockerfile:40`、`.github/workflows/build-release.yml:26,30` | 基础镜像与 Actions 用 tag，无 digest / commit SHA 锁定，供应链存在可漂移输入；没有证据因此定性恶意 |
| `.github/workflows/build-release.yml:5` tag 触发，`:34` 元数据，`:37` latest | release 自动构建，同时发布可漂移 latest；生产应固定自己构建的版本+digest |
| `.github/actions/build-linux-docker-and-package/action.yml:97` push-by-digest，`:103` 读取产物 digest；push action `:58` 合并多架构 manifest | 可追踪各架构构建产物，但不等于密码学证明发布镜像与指定源码一致 |
| `.github/workflows/build-release.yml:270` action-gh-release，`:275` draft=true | 存在自动打包建草稿流程；没有实际核对该 Release 的 run、最终镜像 digest、签名、镜像层文件清单 |
| `build.sh:162` 默认 go test，`:166` 可按 SKIP_TESTS 跳过；`:196` npm install | 有测试入口但不能从工作流文件证明某次发布测试全通过；应改成 CI 固定 npm ci 并检查锁文件不变 |

本次**未下载/运行上游 Release 二进制、未拉取 Docker 镜像、未重建对比哈希**，因此“镜像内无不明文件”“可复现构建”“SBOM 与实际镜像一致”均未确认。建议 fork CI 固定 Action SHA、builder/runtime digest、依赖锁，生成 CycloneDX/SPDX SBOM、漏洞检查及 provenance，把自己制品 digest 纳入 homelab 唯一 artifacts lock。

## 6. S7：提交者/签名/维护信任

实际检查 `git log -30 --format='%h %an %G? %s'`。近期作者包含 MaysWind、Mark（#760 翻译）、Carson Jones（#759 MT940 修复）、Jay Hemnani（#758 QIF 日期处理）、Shanazar Nizamaddinov（#755 翻译）、Albert Brugués（#754 翻译）等。提交主题与变更用途可追溯，但本次没有对每一外部 PR 的讨论、作者账户控制、review 过程逐一验证。

本地这 30 个 commit 的 `%G?` 均为 N（Git 对象未含可检验签名）；GitHub Release 页面却把稳定提交标 Verified。二者是不同取样对象，不能用网页一个 Verified 宣称全历史均已签名。未取得上游组织审计日志或历史转移记录，**仓库是否曾转移、维护者身份真实性/账户是否失陷未能确认**；仓库路径长期一致不足以排除转移。

初步判断：可追溯的公开开发与安全修复存在；不支持“因为作者/项目可信所以无需审计”，也没有据此发现维护者恶意或供应链植入的证据。家庭 fork 应持续监控上游 Security Advisories，安全补丁优先独立合并，记录原 SHA→fork SHA→测试→制品 digest→部署状态。

## 7. 复核命令与未完成边界

可只读重放：

```powershell
git rev-parse HEAD
git rev-parse 3dcc316
git merge-base --is-ancestor 3dcc316 HEAD
git log 3dcc316..HEAD --oneline
git show 3dcc316:pkg/api/tokens.go
git show 3dcc316:pkg/services/twofactor_authorizations.go
git show 3dcc316:pkg/duplicatechecker/in_memory_duplicate_checker.go
git show 3dcc316:cmd/webserver.go
git show 3dcc316:Dockerfile
```

联网限制：PowerShell Invoke-RestMethod 请求 GitHub API 失败（Authentication failed，未请求/输出任何凭据）；web 工具 GitHub API 不可访问，部分 NVD/CVE 正文不可读。公开 HTML 可读的公告、release、原始披露报告与 Issue 已分别交叉核实。未为了抓取数据关闭 TLS 校验。补齐 CVE-2025-65519 修复链、受限资源 DoS 复现、二进制/镜像与源码对应、完整稳定版重新审计，是上线前条件。
