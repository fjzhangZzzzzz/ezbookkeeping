# 家庭版独立 QA 记录

日期：2026-11-13。范围以 `simplified-scope.md` 为准。本记录来自独立 QA 的实际执行，不代表生产、Pocket ID、容器或实体手机已经验收。

## 被测环境

- Windows、本地 Chrome/Playwright CLI；Go SQLite 使用 `CGO_ENABLED=1`、`CC=clang`。
- `GOCACHE=$PWD/.cache/go-build`，`GOMODCACHE=$PWD/.cache/go-mod`。沙箱内 clang 首次因 permission denied 未能执行；授权本地测试升级后可运行。
- 基线 HEAD：`82920c62fd774b95eaed7fe78938675b56b73cf2`，加正在开发的工作树修改。不是已提交发布制品。
- 隔离 HTTP：`127.0.0.1:18089`；数据库、随机密钥、测试账号及日志位于被忽略的 `.work/family-browser-qa/`，未使用生产数据。
- 最终被测版本：Go 1.27.4 构建的 `.work/ezbookkeeping-qa-v4.exe`，SHA256 `6CDC93EA36A8B822264283AB5B0C9885BA8ADAA4EA1B50EBEB87B9B49FB71FE9`，测试进程 PID 26536（17:12:55 启动）。前端为负责人最终生产构建 `dist`。持久幂等跨重启和并发先在 v2/v3 验证；最终版本再次通过独立 Go/HTTP/浏览器检查。

## 实际执行及结果

| 命令/路径 | 结果 | 范围 |
|---|---|---|
| `go test ./pkg/services ./pkg/api -run 'TestFamily\|TestSharedDictionarySQLite' -count=1` | exit 0 | 独立家庭 SQLite、共享字典、CSV、匿名/API/MCP 拒绝 |
| `go build -o .work/ezbookkeeping-qa-v4.exe .` | exit 0 | Go 1.27.4、实际用于最后 HTTP 测试的 CGO 可执行文件 |
| `go test ./pkg/services ./pkg/api ./pkg/datastore ./cmd -count=1` | 最终 exit 0 | services/API/datastore 全部通过；cmd 无测试。曾因新增 SQLite 空闲连接导致 Windows TempDir 清理失败，已补测试关闭连接后复跑通过 |
| `go test ./pkg/services -run TestTransactionRequestDurableConcurrentSQLiteQA -count=1` | exit 0 | 永久回归测试：8 并发、余额一次、跨 UID 同键、内容冲突、数据库关闭重开、删除后迟到重试 |
| `.work/family-browser-qa/seed.ps1` | exit 0 | 两个 CLI 预建账号通过真实登录及写入 API 建账户、共享分类和收入/支出/退款/外币交易 |
| `.work/family-browser-qa/retry-qa.ps1` | 最终 exit 0 | 附件标签重试、不同 payload 冲突、跨 UID 同键、8 个并发同请求，明细仅一条 |
| `.work/family-browser-qa/contracts-qa.ps1` | exit 0 | 转账双条及余额、附件标签恢复；手动模板成功、自动定时模板拒绝、自定义账户图标拒绝 |
| `.work/family-browser-qa/roundtrip-qa.ps1` + `roundtrip-finish-qa.ps1` | 前段测试脚本断言错误后续验 exit 0 | 导入已成功，原脚本错误使用 user.uid 查询；改用实际 id 后确认 3 条账目，另验 stdin 改密码/失败日志/删除用户 |
| Playwright Chrome 桌面和 390×844 视口 | 操作通过 | 真实登录、会话恢复、家庭页、成员筛选、附件请求、CSV 下载、网络响应丢失后重试 |

最终 Go 输出保存在 `.work/family-browser-qa/final-go-test.log`。测试脚本使用只存在本地的随机测试凭据，原始 `.playwright-cli` 和 `.work` 不应提交。

## 已验证行为

### SQLite 及 API 边界

独立新增 `pkg/services/family_integration_qa_test.go`：2 个启用成员、1 个停用成员和 1 个删除成员；家庭读取只包括启用成员。固定上海月初包含、下月月初排除；前月末进入正确趋势月。收入、负支出退款、内部往来、余额调整、转账、CNY/USD 的统计与排除规则通过。两个成员相同 transaction_time 分页不丢不重，成员过滤正确，只有自己的条目 editable。

测试真实 SQLite 拒绝跨成员删除交易和隐藏账户，随后原交易及账户仍不变。拒绝未关联图片、图片 owner 与交易 owner 不一致、停用 owner 图片、关联不存在交易。停用成员原子删除其令牌、从家庭历史和账户范围移除。`family_auth_qa_test.go` 验证四个家庭 handler 均拒绝匿名、API 和 MCP 令牌。

真实 HTTP 另验：乙读取甲已关联附件 200；乙删除甲交易、隐藏甲账户均 400。CLI 停用乙后，旧 token 访问家庭返回 401，账密重新登录返回 400，甲看到的家庭只剩一人。恢复启用未恢复旧令牌。Pocket ID 的实际登录未执行。

### 保存防重

- 首次含附件和标签的写入完成后，完全相同请求重发返回同一 ID、附件和标签。变更同请求键的金额返回 409；不同 UID 使用同键可分别创建本人交易。
- 实际停止并重启隔离进程，再发送原请求，恢复同一 ID 和附件；所有账户余额与重放前相同。这验证了跨进程持久性，未只依赖内存缓存。
- SQLite 单连接修复后，8 个同时到达的同内容请求全部 200、同一 ID，家庭明细仅一条。
- 转账含附件标签重试：原 ID、附件、标签完整，数据库查询结果恰好转出和转入两条，目标余额只增加 1234 分，重放前后余额不变；家庭收入支出不受转账影响。
- 移动真实页面：先由 Playwright `route.fetch()` 让服务器成功保存，再 `route.abort('connectionreset')` 丢弃响应。编辑页保留原表单并显示“无法添加交易”；再次点击保存后成功回主页。两个 POST 的 payload 和 clientSessionId 一致；实际 HTTP 明细只有一笔 1230 分。网络记录为首次 `ERR_CONNECTION_RESET`、第二次 200。
- 最终构建再次新建一笔：默认分类为“日常 / 日常”，保存 100 分成功（POST 57、200）。新 clientSessionId 为 `4155a59e-a6d4-8df4-8af4-e20b182633e5`，与前次响应丢失重试的 `10d2d859-7b55-861c-bf93-fbb485962159` 不同，没有复用已完成请求。

### 页面、金额和导出

Chrome 中登录后进入桌面家庭页，切换至移动入口仍保持登录。390px 视口没有文档横向溢出。家庭成员选择乙后仅显示乙的 4 条明细。附件按钮发出成功图片请求；CSV 按钮实际下载文件。

截图对应时点总额为收入 `1000000` 分、支出 `38702` 分、结余 `961298` 分；页面分别显示 `10000.00`、`387.02`、`9612.98`。成员金额、分类金额与 CSV 勾稽。退款分类 `-150.00` 保持负值，不显示错误占比；外币 1 笔有显式警告。

金额精度静态核验：后端汇总 `big.Int`，JSON 与 CSV 使用十进制字符串；前端金额展示仅字符串补零/切片，没有 `Number` 转换；类别比例用 Decimal。大额汇总单元测试通过。本轮没有用真实浏览器注入超 JS 安全整数的家庭累计数据。

个人 CSV 最小闭环：真实 HTTP 导出普通支出 321 分（含共享标签）、退款 -123 分、内部转出 456 分；使用 CLI `--password-stdin` 新建测试用户，再建其本人同名账户。解析正确识别共享分类/标签及稳定内部分类 ID；导入返回 finished=true、importedCount=3，家庭按该成员查询恰好三条且金额一致、他人不可编辑。删除导入用户后共享内部类别仍在。该样例不覆盖所有导入文件格式或图片迁移。

CLI stdin 修改密码后，使用新密码真实登录成功；故意对不存在用户修改密码得到非零退出码，捕获日志不包含密码或完整 argv。

证据（均不含凭据）：

- [桌面家庭页](../../output/playwright/family-desktop.png)
- [移动视口家庭页](../../output/playwright/family-mobile.png)
- [响应丢失后的保留表单](../../output/playwright/mobile-save-response-lost.png)
- [失败提示摘录](../../output/playwright/mobile-save-failure.txt)
- [实际网络请求摘录](../../output/playwright/mobile-save-network.txt)
- [浏览器下载的汇总 CSV](../../output/playwright/family-2026-11.csv)

## 独立发现及修复状态

| 问题 | 发现时位置/原因 | 复测 |
|---|---|---|
| P1 生产构建启动整页空白 | 原 `src/components/FamilyContent.vue` 被分到独立 chunk，vendor/common/FamilyContent 环依赖使 crypto-js 初始化 `v is not a function` | 已移至 `components/common`；真实浏览器重新加载、登录和两端页面通过 |
| P2 附件保存成功但响应丢失，重试被当作图片不存在 | 原 `pkg/api/transactions.go:1346–1365` 在幂等回放前检查“尚未绑定图片” | 新 `pkg/api/transaction_requests.go` 先回放；真实 HTTP 含附件/标签通过 |
| P2 内存防重过期或进程重启可能重复记账 | 原事务外 300 秒内存 remark | 新 `models.TransactionRequest` 与账目同事务持久化；跨进程、冲突、并发及余额复测通过 |
| P2 同时请求出现 SQLite locked，错误变成“用户不存在” | `pkg/datastore/datastore_container.go` 原多连接共享 SQLite | 单连接版本 8 并发全部成功且仅一笔 |
| P2 新交易默认选内部转出，普通支出易被家庭统计排除 | `src/lib/category.ts:309` 原首个可见类别；初始化内部分类排在最前 | 已修；最终构建真实移动浏览器默认日常类别，实际保存成功 |
| CLI 失败日志包含完整 argv（含密码参数） | 原 `ezbookkeeping.go` fatal 输出 `os.Args` | 已移除 argv；真实 stdin 开通/改密码及失败日志无泄漏检查通过 |
| P2 修改内部分类颜色后响应丢失系统标记 | `pkg/api/transaction_categories.go:298` 返回新对象，但未带原 `FamilyInternalTransfer`，前端替换后错误视为普通类别 | 已修；最终二进制真实修改颜色返回 familyInternalTransfer=true |
| P2 原 Docker 构建入口未转发源码版本参数 | 新 `.dockerignore` 排除 `.git`，但 `build.sh:278` 的 Docker 调用没有传新增 `BUILD_COMMIT`；容器内 Vite 会调用 `git.short()` 失败 | build.sh、GitHub action、bake 参数传递静态复核已修；最终 Linux 镜像由另一代理验收，本记录不代替该实测 |

## 追加实际 diff 独立评审

使用 `git diff --ignore-space-at-eol` 核对跟踪文件，并直接阅读新增家庭服务/API/model、共享目录初始化和幂等文件。QA 仅编写测试，没有参与上述业务实现。

- actor 与 owner 分离：API 取当前 actor UID；目录 SQL 统一 owner 0，创建时只复制 owner 字段；个人交易、账户及标签索引仍保留本人 UID。`ClearAllDataHandler` 不再调用三类共享目录删除；遗留 `DeleteAllCategories/Tags/TagGroups` 仍限制正 UID，不会清理 owner 0；CLI 删除用户只删用户状态和 token。未发现新增跨本人写入路径。
- 系统分类数据库标记不在普通更新 `Cols` 内；内部子类改名、隐藏、移父以及删除受保护，隐藏父类也检查受保护子类。新增前端标记透传及最终修改响应保留标记已复核。
- 分组 Hidden 在 Go 响应、TypeScript `of/clone`、store 更新中保留；改名 handler 返回原有 group 对象，仅替换 Name，不丢 Hidden。选择器保留已选隐藏标签供旧记录显示；服务验证阻止新使用隐藏分组标签。
- 幂等 `(uid, request_id)` 为复合主键；账目、余额更新和 request 记录均在同一个 `DoTransaction` 中。回放使用原交易 ID 的本人读取，不提供修改别人的路径；删除后仍保留 request 键，迟到重试不能重建。数据库重开和并发已由独立测试覆盖。
- 新增用户启停、目录引用校验、幂等事务均复用传入 session；没有在这些新事务回调中发现再取独立连接的嵌套查询。该判断不等于对所有上游旧服务逐行证明不会死锁；实际个人写入、转账、模板和停用闭环在单连接下已通过。
- 家庭附件先验证启用 owner，再验证未删除交易同 owner 关联，最后读取原存储；没有签发他人的普通会话。家庭 API 的外层 header 中间件仍验证 token，handler 又限定 normal 类型。
- 请求日志移除 query，2FA 日志去除恢复码/验证码，CLI fatal 去除 argv；新 stdin 通道不回显输入。安全功能依旧需要真实部署配置核验。
- DOMPurify resolver 精确匹配 Monaco 的 vendored import，转到锁定 npm 包；未关闭 minify 或类型检查。生产 bundle 已实际浏览器运行。Docker 版本参数的遗漏属于新增构建差异，见上表。

## 未覆盖及限制

独立评审已确认的问题均完成上述修复复测，在本次检查范围内没有仍未关闭的 P1/P2。此结论受以下覆盖范围限制：

- 未执行 Pocket ID 实例的真实 claim/绑定、Passkey、OIDC 故障回退或停用两入口端到端；只验证账密和应用 token。
- 390px Chrome 是真实浏览器视口模拟，不是实体手机、iOS Safari 或添加到主屏幕安装/恢复验收。
- 个人 CSV 只执行上述三行最小样例，未对全部文件格式、历史升级数据和图片导出导入做完整矩阵。
- 未执行 Linux 容器镜像构建、远端部署、公网 TLS、备份/隔离恢复演练，不能据本记录宣称上线完成。
- 未将原版外部汇率 API 测试失败归因于本次家庭修改；本记录未重复全量外部网络测试，也不是全源码/全依赖安全审计。

证据文件位于本地output/playwright（保留给本轮验收），不随应用源码提交；原始浏览器会话位于忽略目录，不包含在发布源归档。
