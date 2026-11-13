# 实施状态

## 阶段 0：基线与本地验证（早期记录，最新结论见后文）

- 实施基线冻结为 `82920c62fd774b95eaed7fe78938675b56b73cf2`；后续工作树修改是该基线上的实施中变更。稳定版 `3dcc316e1fb6a190d6a0dd13fbe4052d17b9315c` 是其祖先；二者相差 277 个文件。选择当前提交以保留外部账号绑定修复（`8fecff18`）、撤销令牌后前端跳转修复（`abe6bd68`）、命令行撤销全部令牌（`d628f1c1`）、派生令牌约束（`38c60dbe`）和令牌签名（`d54b7326`）。这不是安全审计通过或阶段验收通过的证明。
- 两个版本外部身份模型均以用户名/邮箱保存，尚未实现稳定的 OIDC issuer/sub 身份，Pocket ID 绑定仍须设计和实测。
- 当前提交声明 Go 1.27.3；Dockerfile 使用 Go 1.27.3、Node 26.13.0 和 Alpine 3.24.3，镜像尚未固定摘要或构建。本机 Go 1.27.3、Node 26.12.1、npm 11.20.0、Git 和 Bash 可用；Docker、Podman、GCC、Zig 不在 PATH，WSL 无已安装发行版。Clang 存在，沙箱内 CGO 测试因权限失败，沙箱外 `CGO_ENABLED=1 CC=clang go test github.com/mattn/go-sqlite3` 退出 0；应用 SQLite 场景仍未验收。
- 在 `.work/baseline` 使用 HEAD 归档及项目内 `.cache` 执行：联网下载 Go 模块成功；`npm ci --ignore-scripts --no-audit --no-fund` 退出 0；`npm test` 退出 0，14 个文件、38,685 个测试通过；`vue-tsc --noEmit` 退出 0。`go test ./...` 退出 1，汇率包两项实时外部 API 请求失败，其他包完成；运行时 CGO 未启用。`npm run build` 沙箱内及沙箱外均退出 1，Vite 提示无法加载 `@vue/tsconfig/tsconfig.dom.json`，尽管该文件存在，原因待核查。离线 Go 全量测试和 npm 安装因缓存缺失退出 1。
- `npm audit` 退出 1，报告 22 项：1 critical、18 moderate、3 low；critical 为间接依赖 DOMPurify 的 GHSA-cg2g-xh64-v4q7，实际调用路径与修复策略待评估。告警数量不等于已验证可利用性。
- `govulncheck` 未安装；Go 依赖漏洞扫描、SQLite 登录/记账/导出、Docker 镜像构建、Pocket ID 和真实设备验证均尚未完成。不得将未执行项目记为通过。

## 阶段 1：共享字典（早期记录，最新结论见后文）

- 已在服务端建立实例目录 UID 0，分类、标签与分组的读写使用共享目录，交易及标签索引仍归本人。清理个人数据不再删除共享目录；分类、标签删除检查所有用户的交易及未删除模板，包括停用或过期的定时模板。
- `database update` 初始化稳定标记的内部转入/转出分类，重复执行幂等，系统分类及父分类受保护；分类服务端拒绝自定义图标。分组增加隐藏 API，交易和模板禁止新用隐藏分组内标签，前端入口与选择器适配尚待完成。
- CGO SQLite 回归覆盖两成员共享读取、新增改名、实际交易及手动模板、禁用定时模板引用保护、个人交易清理后字典仍可读、系统分类保护及重复初始化、分组隐藏和恢复；`go test ./pkg/services ./pkg/api ./pkg/models ./cmd` 退出 0。尚需完整 API/导入导出和真实移动端验收，不能宣称阶段 1 已完成。

## 当前候选：开发及本地验收检查点

- 以82920c62为上游起点，已实现的家庭修改尚不能冒称该上游SHA；最终交付以独立family分支的实际提交/源码manifest记录。
- 已实现实例共享分类/标签/分组、受保护内部往来类别和普通默认种子、分组隐藏、个人清理保留目录、十个启用成员上限及停用原子撤销令牌。
- 已实现正常会话专用family读取API、上海时区人民币四类汇总、当前成员明细/账户/附件、复合分页、负数退款和外币排除提示、汇总CSV，以及移动/桌面基本入口。
- 保存使用SQLite持久request-id，和账目/余额/标签/图片绑定同事务；独立QA已验证同请求重试、不同payload冲突、跨成员key隔离、八并发、跨进程重启、迟到重试不复活已删账目。SQLite固定单连接，避免家庭轻并发中的锁失败。
- 内置图标、自动定时记账关闭、公开注册/OAuth自动注册关闭；普通模板保留。新增CLI密码stdin输入，失败日志不输出argv；请求日志不记query、2FA日志不记验证码/恢复码，启动要求显式至少32字符secret_key。
- Go安全限定更新到1.27.4、Excelize2.11.1、compress1.20.2、xcrypto0.59及必要间接依赖；项目缓存go mod verify退出0，govulncheck1.9.0使用Go1.27.4符号级扫描退出0/无finding。Dockerfile同步Go版本并固定三个官方amd64基础镜像摘要。
- Monaco内嵌DOMPurify需运行时替换，已定向锁3.4.17并由Vite resolver替换其vendored导入；正式dist包含3.4.17且不含3.4.15。npm生产依赖audit退出0、0告警；完整audit仍有18moderate/2low，均构建/测试依赖，未作为应用可利用漏洞冒报或隐藏。
- 最新完整前端测试：17文件38,689用例通过，类型检查和19个修改前端文件定向ESLint通过；正式build退出0。最初隔离快照的OXC解析问题在实际根安装依赖后消失；Vitest限定正式src，避免收集.work快照副本，不排除任何正式源码用例。
- Go1.27.4/CGO_ENABLED=1/CC=clang完整go test -p2 ./...退出0；沿用上游BUILD_PIPELINE=1、CHECK_3RD_API=0，仅跳过第三方在线汇率探测。未将外部服务在线失败归为家庭回归。
- 非作者独立QA见[independent-qa.md](independent-qa.md)：真实Chrome桌面及移动视口、保存成功后丢响应再重试、第二笔新request-id、共享字典导出/解析/导入、停用/删除、stdin开通/改密码、附件/转账/self边界均有实际证据；当前检查范围无未关闭P1/P2。

## Linux与生产交付边界

- 已通过批准环境严格SSH核验control-01身份，Docker29.9.2、8CPU、约6.2GiB可用内存、473GiB空闲磁盘；候选18882无监听。最初沙箱TCP假阴性已经纠正，不需用户排网络。
- control直接DockerHub超时；独立Linux代理使用已验证官方摘要的镜像离线传输/既有代理路线，正在构建和隔离运行。Compose候选语法校验通过，不代表生产已部署。精确候选生产范围在deploy/homelab/integration-review.md，由根另派基础设施代理准备实际infra变更，不激活。
- books.fjzhang233.top在有网络环境下A/AAAA为NXDOMAIN，对照root/auth A均139.196.45.16。DNS/域名、edge→control18882准入、Pocket ID专用客户端与Backrest接入须按具体材料实施，不能以本地业务测试代替。
- 未验：最终冻结源码的Linux镜像/恢复、公网TLS、真实Pocket ID/Passkey、实体手机及PWA安装。模拟移动视口不冒称实体手机验收。未改生产数据、身份、证书或网络；完整任务仍在推进。

## 冻结来源与认证日志定向验证

- 应用源码位于本地 `family/initial` 分支，业务与本地 QA 冻结提交为 `ea1852fea60776c514a4d5d83a9b2d2cb8419228`；许可证元数据闭环后的镜像来源提交为 `462ff2b3f66659daa92ca2d19255924d1e2a3b70`，未推送。后者仅调整 `third-party-dependencies.json`，未改变业务逻辑或依赖版本。
- 未推送远端不等于丢失维护历史：标准 `git bundle` 保存 `family/initial` 和完整可达历史，bundle SHA256 `33991d60a6929999679c8cfd914d7ae0fa2fd39fff5f2c7f30047ed49467724a`、31,781,092 字节；`git bundle verify`、隔离 `git clone --bare --branch family/initial`、`git fsck --full` 均退出 0，恢复 HEAD 为 `462ff2b3f66659daa92ca2d19255924d1e2a3b70`。源 tar、逐文件清单与 bundle 已以冻结提交命名、root-only、不覆盖方式落位 control 的 `/srv/homelab/recovery/ezbookkeeping/source/` 并核对 SHA；正式路径再次完成 bundle 克隆、fsck 和 1184 个归档文件逐项校验。尚未加入实际 Backrest 产品计划或正式快照，不将持久工件副本冒称备份恢复验收。只读 origin 探测退出 0、远端尚无此 family 分支；未验证或执行推送。
- 本轮变化的 crypto、text、Excelize、compress、mscfb、efp、sys 与 DOMPurify 告知均按实际模块 LICENSE/版权头核对，并经非作者复核；正式前端 build、类型检查均退出 0，生成的 common JS 包已核验全部八个准确版本的许可证链接。
- `pkg/middlewares/request_log.go` 只记录 URL.Path，删除 RawQuery 拼接；`pkg/api/authorizations.go` 的 TOTP 重放和恢复码授权成功日志不再传入 passcode/RecoveryCode。主程序失败日志也不再打印 argv。
- 在启用 request 日志和 info 级别的真实本地 HTTP 进程上，用临时合成用户完成 TOTP 开通、首次成功、同码重放拒绝及恢复码登录成功，实际命中两条认证日志分支；带随机 token/code query 的请求亦确有路径日志。定向断言退出 0：新增日志无 query sentinel、恢复码、密码、TOTP secret，重放日志不含该次 passcode。合成用户随后通过原生 CLI 删除（退出 0），未修改生产或真实成员。
- 日志验证脚本和无密钥布尔结果保留在被忽略的 `.work/log-security-qa.py`、`.work/family-browser-qa/log-security-result.json`，不提交测试凭据。此验证与 CLI stdin/argv 验证分开记录，未以其中一项替代另一项。
- LLM provider 原有 debug request body 日志仍保留，未宣称其内容脱敏；当前三个 AI 功能默认关闭，部署日志级别为 info。未来主动启用 AI 或 debug 时应另行评估正文日志，当前验收不覆盖该未启用能力。

## 最终静态工件的构建平台

- control 的正式冻结来源 Linux 后端 `go vet`、完整 `go test` 和 CGO 静态二进制构建均通过。Linux 前端依赖安装、类型检查、ESLint 和测试通过，但 Vite 在 4 GiB 构建容器上触发内存不足；最后一次低并发尝试亦失败，未将 Linux Vite 打包记为通过，未继续无限增加生产宿主资源。
- 使用同一标准 `git archive` 在 Windows 独立目录构建，source tar SHA256 仍为 `6e98cae257c75d31cab353ba8e2583375cfc6a1b2ae077da752b099cd5d81f1b`；Node `26.12.1`、npm `11.20.0`，显式 `NODE_ENV=production` 和完整 `BUILD_COMMIT=462ff2b3f66659daa92ca2d19255924d1e2a3b70`。`npm ci`、原完整 `npm run lint`（含类型检查）和 `npm run build` 均退出 0。
- lint 后及正式构建后，1184 个冻结文件逐项 SHA256 不变，清单 SHA256 为 `005f683bcf4c025f70701219f794249273d3f6119dbac8a8e84c778bac93c991`，package-lock SHA256 为 `c6d7d545a4f98afdc857d0373843e35fa3ca260ac0a049f395114549e90ae17b`。实际 dist 含完整冻结提交、DOMPurify 3.4.17、全部八项许可证链接，未含 DOMPurify 3.4.15。
- 静态 dist 共 109 文件，归档 28,487,680 字节；tar SHA256 `48f14690df948d5b7466a4a38e2c3e3dd86b00255c5918dd24080ff345bd4ba5`，逐文件清单 SHA256 `691ad9a3349a9c9f87978b386dafc827ebf593bce183cea39348922260a00b8d`。本地证明为 `.work/windows-fe-462ff2-proof.json`。Linux 最终镜像使用该已校验静态输入与已验证 Linux 后端组合，实际运行和浏览器结果见下文。

## 隔离镜像与修正部署输入验收

- 最终候选 image ID 为 `sha256:e7601cbf24091f538cbe147f4664ed02a5fb1b5c6a6315fe3534a6a1f07148f8`；docker-save 归档 SHA256 为 `b7281709f90fc6a4c00ef9b29c2aec919c02d4fe474a2c4f21c9b4fc51da27eb`。此为本地离线镜像，未推 registry；image ID 不称作 registry manifest digest。镜像内后端二进制及 109 个静态文件与各自已验输入逐项相符。
- 同镜像以 UID/GID 1000、只读根文件系统和独立 SQLite/storage 运行，健康返回完整来源 SHA。合成账号密码登录、SQLite 账户读写、家庭 API、重启持久性与 integrity_check 均通过；不挂生产数据。另用合成 OIDC client 在临时专用网络验证真实应用的 TLS/issuer discovery 与 S256 跳转参数，不代表真实 Pocket ID 登录/绑定完成。
- 精简部署示例初次运行暴露必填枚举缺失，已同步修正应用 `deploy/homelab` 示例及实际 infra INI 模板：UUID `internal`、duplicate checker `in_memory`、禁用地图时仍需的 `amap_security_verification_method=internal_proxy`、exchange rates `user_custom`，并显式开启个人 import/export。应用示例与实际模板除四项凭据占位语法外逐字一致；实际模板 raw SHA256 为 `c0249588abb3079d9aaa93a3b11781bad9884c021dd8041683f554dd8280b2af`。
- 修正模板的合成 Windows CLI `database update` 退出 0，SQLite 建表/integrity_check 通过；同最终 Linux 镜像再以修正模板启动，真实 HTTP 分类及交易写入、个人 CSV 导出/解析/实际导入、家庭 CSV 导出均通过。只有 HTTP 健康及模板语法通过不作为此结论的替代。
- 真实 Chrome 经 SSH 回环访问最终镜像，桌面及移动 390×844 家庭页和 CSV 下载通过。最后移动日常流程先访问家庭页基线 24.68 元，再返回 Home、原金额键盘记 1.23 元普通支出、点击保存回 Home、通过正常家庭入口返回；支出立即变 25.91 元、余额 -25.91 元且新明细出现，过程无 reload 或直接 API 代写，控制台 0 errors/0 warnings。证明在 `.work/linux-ui-save-proof.json`，截图在 `output/playwright/linux-final-*.png`。
- 临时 Linux 测试容器、回环转发及构建代理通道已由构建代理清理；独立最终浏览器会话已关闭。原始证据和恢复工件保留。当前本地源码、独立 QA、隔离镜像与日常记账验收已收口，真实 Pocket ID 绑定、公网 HTTPS/准入、实体手机/PWA 及实际 Backrest 快照/隔离产品恢复仍未完成。
- 本次后续提交只收口交付文档及被 `.dockerignore` 排除的 `deploy` 示例；运行镜像来源仍为 `462ff2b3f66659daa92ca2d19255924d1e2a3b70`，交付文档新 HEAD 不冒充已经重建的运行来源。

- 基础设施本地集成已提交 `564cc4f737aed1248c5d006f99ec31ae409f8162`（`2.4.149`，13 个集成文件加唯一版本文件），独立核对提交范围与工件锁通过。尚未推送、发布或激活，原有用户文档修改保持未提交；该状态不等于生产部署。
