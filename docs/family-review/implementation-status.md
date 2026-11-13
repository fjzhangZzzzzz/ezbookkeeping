# 实施状态

## 阶段 0：基线与本地验证（进行中）

- 实施基线冻结为 `82920c62fd774b95eaed7fe78938675b56b73cf2`；后续工作树修改是该基线上的实施中变更。稳定版 `3dcc316e1fb6a190d6a0dd13fbe4052d17b9315c` 是其祖先；二者相差 277 个文件。选择当前提交以保留外部账号绑定修复（`8fecff18`）、撤销令牌后前端跳转修复（`abe6bd68`）、命令行撤销全部令牌（`d628f1c1`）、派生令牌约束（`38c60dbe`）和令牌签名（`d54b7326`）。这不是安全审计通过或阶段验收通过的证明。
- 两个版本外部身份模型均以用户名/邮箱保存，尚未实现稳定的 OIDC issuer/sub 身份，Pocket ID 绑定仍须设计和实测。
- 当前提交声明 Go 1.27.3；Dockerfile 使用 Go 1.27.3、Node 26.13.0 和 Alpine 3.24.3，镜像尚未固定摘要或构建。本机 Go 1.27.3、Node 26.12.1、npm 11.20.0、Git 和 Bash 可用；Docker、Podman、GCC、Zig 不在 PATH，WSL 无已安装发行版。Clang 存在，沙箱内 CGO 测试因权限失败，沙箱外 `CGO_ENABLED=1 CC=clang go test github.com/mattn/go-sqlite3` 退出 0；应用 SQLite 场景仍未验收。
- 在 `.work/baseline` 使用 HEAD 归档及项目内 `.cache` 执行：联网下载 Go 模块成功；`npm ci --ignore-scripts --no-audit --no-fund` 退出 0；`npm test` 退出 0，14 个文件、38,685 个测试通过；`vue-tsc --noEmit` 退出 0。`go test ./...` 退出 1，汇率包两项实时外部 API 请求失败，其他包完成；运行时 CGO 未启用。`npm run build` 沙箱内及沙箱外均退出 1，Vite 提示无法加载 `@vue/tsconfig/tsconfig.dom.json`，尽管该文件存在，原因待核查。离线 Go 全量测试和 npm 安装因缓存缺失退出 1。
- `npm audit` 退出 1，报告 22 项：1 critical、18 moderate、3 low；critical 为间接依赖 DOMPurify 的 GHSA-cg2g-xh64-v4q7，实际调用路径与修复策略待评估。告警数量不等于已验证可利用性。
- `govulncheck` 未安装；Go 依赖漏洞扫描、SQLite 登录/记账/导出、Docker 镜像构建、Pocket ID 和真实设备验证均尚未完成。不得将未执行项目记为通过。

## 阶段 1：共享字典（进行中）

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
