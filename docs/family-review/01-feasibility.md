# 家庭记账可行性评估

> 范围变更：本文保留最初完整需求的评审证据和参考设计。用户已确认家庭自用简化方案，后续实现及预算以 [simplified-scope.md](simplified-scope.md) 为准；本文冲突的权限、迁移、多币种、三库及估算条款不再适用。

评估基线：`82920c62fd774b95eaed7fe78938675b56b73cf2`。需求输入为《ezBookkeeping家庭记账二次开发需求说明》v1.0（2026-11-13）。本文评估实际 fork 源码，未执行数据库迁移、未修改业务代码；公开稳定版本与漏洞修复范围见 [上游核验](upstream-verification.md)。

## 结论

**可以做，整体为中高侵入改造。** 家庭和成员可以增量建设，难点是共享字典贯穿记账校验、历史解析、模板、导入导出、清空数据和注销；家庭聚合还需独立处理隐私、固定汇率与跨成员转账。不能把原查询里的 `uid=?` 全部替换为 `uid IN (...)`。个人账户、交易、附件和模板继续以本人为所有者；家庭新建聚合服务，字典则增加显式资源域。

早期评审曾假设第一期单用户最多一个家庭、家庭最多十人，个人功能永久保留，家庭默认仅汇总，明细/余额/附件需要另行授权；这些假设已由后续 [简化范围](simplified-scope.md) 取代。本文其余内容是早期可行性分析，不是当前产品规范。

R15 的他人账户下钻与 R23 默认不暴露明细冲突：默认仅聚合，下钻必须另有资源所有者授予、可撤销的授权，家庭拥有者身份不自动授予看他人交易和余额的权利。只读角色只限制家庭资源写入，不禁用其本人个人记账。

## 源码架构核实

|层面|实际读取证据|结论与改造含义|
|---|---|---|
|HTTP → API → service → datastore|`cmd/webserver.go:401` 注册 `Transactions.TransactionStatisticsHandler`；`pkg/api/transactions.go:616` 为处理器；`pkg/services/transactions.go:2303` 统计服务；`pkg/datastore/database.go:34` 创建数据库会话|有清晰服务目录，但服务内混有授权前提、验证与聚合；新增家庭 service 可复用纯计算辅助逻辑，不能原样复用个人返回模型。|
|ORM 与方言|`go.mod:16` Gin；`go.mod:39` Xorm；`pkg/datastore/datastore_container.go:69` 按数据库类型构造连接；`pkg/datastore/database.go:73` PostgreSQL savepoint；`pkg/datastore/database.go:92` Sync2|支持三库，存在原生 SQL；不能以“使用 ORM”推导三库 DDL/迁移自动正确。当前未跑三库兼容测试。|
|实际表名|`cmd/database.go:88`、`:96`、`:112` 分别 SyncStructs Transaction / TransactionCategory / TransactionTag；`pkg/services/transactions.go:3201` 明确 `From("transaction_picture_info")`|模型无 TableName 覆盖，初始化未见设置前缀 mapper；据源码默认映射应为 `transaction`、`transaction_category`、`transaction_tag`，而非需求中的 `ebk_transaction_categories`。运行数据库目录未取证，执行前必须核对真实 schema。|
|数据仓储|`pkg/services/base.go:53` UserDataDB(uid)；`pkg/datastore/datastore.go:24` Choose 恒返回 databases[0]|接口预留分片概念，当前没有依 uid 真正分库；该调用不是权限条件，不能省略 WHERE。家庭服务应明确限定当前单库部署，不承诺未来跨分片事务。|
|认证上下文|`pkg/core/context_web.go:22` TOKEN_CLAIMS；`:110` SetTokenClaims；`:169` GetCurrentUid 从 claims 读取；`pkg/middlewares/authorization.go:149` tokenAuthorization|uid 是请求级 Gin context 中令牌声明，无家庭范围。`pkg/datastore/datastore_container.go:30` 存在全局 Container 单例，但不能把家庭成员 uid 列表放入此类可共享全局变量。|
|前端复用|`src/stores/statistics.ts:2` Pinia；`src/views/base/transactions/TransactionEditPageBase.ts:1` shared composable；`src/views/mobile/transactions/EditPage.vue:534` 引用 shared base；`src/stores/index.ts:64` resetAllStates|Vue 业务逻辑与 store 可复用，桌面/移动的 UI 模板仍需分别实现。家庭状态独立 store，缓存必须带上下文，不以当前用户唯一键缓存家庭响应。|
|统计聚合|`pkg/services/transactions.go:2188` 总收支按账户 Go big.Int 聚合；`:2361` 选取记录，`:2370` append 全量交易；`:2382` Go 遍历；`:2455` 月度统计|需求“服务端聚合”部分成立，“数据库聚合集中单处”不成立。可提取相同时间/金额逻辑，但现实现内存累积随交易量增加。家庭需要限制范围和数据库聚合/流式计算，不能简单外层加成员循环后缓存永不过期。|
|汇率|`src/stores/statistics.ts:1173` getExchangedAmount；`:572` 账户资产使用当前默认货币；`pkg/models/transaction.go:136` Amount；`pkg/models/account.go` Currency 字段|原交易只存整型原币金额，币种取账户。固定历史汇率、家庭本位币和汇率版本是新增能力，不能直接拿现有前端统计视作满足 R16。|
|转账|`pkg/models/transaction.go:64` 两种转账 DB 类型；`pkg/services/transactions.go:2162` 生成关联记录；`:2164` 关联记录 Uid=原Uid；`:3338`、`:3363` 双方账户按同一个 uid 校验|仅本人账户间转账，RelatedId 不是跨成员关联。家庭收入/支出可直接排除转入、转出和余额调整；只有资金流图需要按配对键去重。|
|分类与标签|`pkg/models/transaction_category.go:20` category 主键、uid、父分类、deleted；`pkg/models/transaction_tag.go:8` TagGroupId；`pkg/models/transaction_tag_index.go` 交易标签索引|共享不仅新增 family_id：分类父链、标签分组、自定义图标、显示顺序和软删除都需同域。标签索引 uid 仍是交易所有者。|
|定时/导入/导出/MCP|`cmd/webserver.go:408` 批量新增；`:420` 解析导入；`:379` CSV 导出；`pkg/services/transactions.go:3312` 模板生成交易 uid；`pkg/mcp/query_transactions_tool_handler.go` 本人查询工具|这些入口同样依赖个人服务与字典；共享引用需适配，聚合权限不可从 HTTP 独有上下文悄悄绕过。完整动态授权未确认，见 UID 附录覆盖限制。|

短片段说明两个关键边界：

```go
// pkg/services/transactions.go:3507 —— 共享分类改造必须覆盖的校验
has, err := sess.ID(transaction.CategoryId).Where("uid=? AND deleted=?", transaction.Uid, false).Get(category)
// pkg/services/transactions.go:3363 —— 仍必须保持个人所有权
has, err = sess.ID(transaction.RelatedAccountId).Where("uid=? AND deleted=?", transaction.Uid, false).Get(destinationAccount)
```

## R1–R25 逐条评估

“侵入”指相对现有项目的修改范围，“代价高”是安全与兼容成本而非技术不可实现。

|编号|结论|实现路径|侵入|风险与验收|
|---|---|---|---|---|
|R1|可行|家庭、成员、创建事务；先 N=1，活跃家庭占位/唯一约束防并发加入；保留未来扩展列|中|N=2 会产生同一交易属于哪一家及字典混用问题，延后；并发创建/加入只能成功一次。|
|R2|可行|邀请令牌哈希、有效期、剩余次数；原子扣减并加入；移除/退出撤销成员权限|中|退出必须重绑本人共享引用；最后拥有者退出前转让/解散，不能留无主家庭。|
|R3|可行|家庭角色在服务端每次操作判定；只读只禁家庭写；本人的个人账户/交易仍可写|中|前端隐藏按钮不算授权；拥有者也不能改别人的账户/交易。|
|R4|可行|家庭名称、本位币、时区、创建及版本字段；本位币变更新统计版本|低|已有汇率快照不能被改币种后错误复用。|
|R5|可行但代价高|退出前为本人成套克隆所用分类父链、标签/分组，并事务重绑本人交易、索引和模板；共享字典保留|高|直接留共享 ID 会使退出者不能解析自己历史，或需永久授权而泄露后续字典变化；批量迁移要幂等、恢复测试。|
|R6|可行|分类/标签增加 nullable family_id、域约束、索引；uid 表示创建人但不得继续单独决定共享归属；标签分组亦共享|中高|NULL 私有与非空共享必须互斥校验；旧版清空用户不能删除全家字典。|
|R7|可行|新增可用字典读取服务；校验 self 私有或当前家庭共享，父子/组同域；原个人交易端点允许合法共享引用|高|只改列表不改 transactions.go:3507/3530/3549 则仍无法记账；改得过宽则可猜 ID 越权。|
|R8|可行|第一期 owner-only 家庭字典写；P1 独立提议待审记录；如允许成员新增，也必须仅新增且留审计|中|不能复用私人 modify/delete 接口绕过家庭写权限。|
|R9|可行|私人分类保持 uid/self；家庭响应只提供“私人分类”桶或不计入，按产品同意规则确定|中|共享分类与私人分类同名不能自动合并；占比/总额差异会透露私人金额，需要清晰隐私口径。|
|R10|可行但代价高|重命名保留 ID 并留前后审计；合并记录映射与版本，禁止硬删被引用字典；历史展示名采用当前名称或快照需明确|高|原删除引用检查只查操作者 uid（transaction_categories.go:390、398），共享域须查全引用；改名影响所有历史统计呈现。|
|R11|可行|独立家庭统计仓储，认证当前 active 成员范围+shareFrom 时间下限；只计 income/expense；返回汇总 DTO|中高|不能放宽原 list/get；“当前成员全部历史”会导致加入即泄露旧账，需时间/授权边界。|
|R12|可行|按共享 category_id 聚合，支持一级父分类；私人分类使用匿名桶或排除|中|分母必须解释是否包含私人项；标签多值聚合不得 JOIN 后把一笔支出重复求和。|
|R13|可行|以成员ID+昵称聚合，金额按统一快照折算|中|昵称不是匿名化；退出后历史展示策略明确，不能继续读取已撤销授权的数据。|
|R14|可行|复用上游时间辅助函数，家庭固定时区，日/周/月/年桶；UTC 查询边界本地构造|中|周起始日/财年/交易时区选项和 DST 需测试，不能在浏览器各自时区聚合。|
|R15|可行但代价高|保持账户本人所有；默认禁他人明细；另建显式授权与受控下钻返回模型|高|与 R23 冲突已指出，默认拒绝；余额、凭证、备注不因家庭成员身份公开。|
|R16|可行但代价高|新增家庭汇率快照与版本；每个日期/币种的固定汇率不可覆盖，缺率报明确错误|高|不可使用 src/stores/statistics.ts:1173 当前汇率；整型分币、币种精度、舍入与跨币退款须统一。|
|R17|可行但需修正理解|本人转账两条在家庭收支中全排除；资金流用 min/max transaction_id 配对；跨成员需显式双边关联|中高|上游不能跨成员转账；A 支出+B 收入无法靠 RelatedId 自动配对。金额/日期相同仅能建议，不能自动中和。|
|R18|可行|新增家庭聚合 CSV/XLSX 导出，与查询使用同一 DataScope、快照版本和时间规则|中|不复用全量个人账单导出返回明细；XLSX 字符串显式文本，CSV 防公式注入。|
|R19|可行|个人路由、DTO 与默认查询语义保留，家庭独立前缀；无家庭字典逻辑走旧分支|中|回归测试无家庭用户；不可把 family_id 参数默认解释为任意家庭。|
|R20|可行但代价高|只增表/nullable列，旧行 NULL=个人；版本化迁移，备份恢复，旧数据导出前后对账|高|不能宣称 Sync2 等于安全回滚；旧版程序不理解共享引用，数据重绑后不能直接换旧二进制降级。|
|R21|可行但代价高|原导入继续创建本人数据；导出把有权使用的共享字典映射成上游可理解的个人字典；不接受外部文件直接指定家庭资源|高|导入同名匹配必须带域；跨家庭ID、恶意模板引用、退出副本都要测；上游格式不携带家庭成员/审计备份。|
|R22|可行|统一构造不可由客户端扩宽的范围对象，家庭每次授权；私人ID、家庭ID、成员关系均校验|高|完整安全证明需越权矩阵与所有入口集成测试，本次静态审查不能证明已满足。|
|R23|可行|聚合专用 DTO，不返回账户名/交易ID/备注/图片/地理位置；明细另有所有者授权|中高|极小时间桶/仅一笔交易仍可推断金额；是否需要最小桶阈值应按家庭隐私预期决定。|
|R24|可行|同业务事务追加字典变更审计，记录操作者/版本/前后值，不存账目摘要|中|原服务没有这套家庭审计；审计只 owner 可读、保留期受控，避免新敏感数据源。|
|R25|可行|高熵随机令牌只存哈希，固定到期/次数/作废状态，限流和并发原子扣减|中|不得用可预测类型化主键作邀请码；短数字码需更严限速。|

## 关键技术边界与工程约束

1. **家庭新读服务与个人写服务分离。** 共用纯计算函数，不复用个人详情响应装配。字典策略需被事务中的验证函数调用，不能只在 handler 检查一次。异步导入/定时交易也须再次确认成员与字典可用性；退出副本迁移与定时执行并发要序列化。
2. **复合游标。** `pkg/models/transaction.go:129` 的唯一约束是 `(uid, transaction_time)`；`pkg/services/transactions.go:2377` 原代码翻页用最后一条 `transaction_time-1`。家庭多 uid 直接套用会在不同用户相同时间处漏记录；改为 `(transaction_time, uid, transaction_id)` 游标或逐成员独立聚合。
3. **交易时间不是直接 Unix 毫秒。** `pkg/utils/datetimes.go:520` 为 `unixTime*1000`，`:525` 最大值 `unixTime*1000+999`，`:531` `/1000` 转秒；低三位也用作同秒序号。边界查询须复用已有辅助函数。
4. **主键扩展有限。** `pkg/uuid/uuid_type.go:3` 指明类型 0–15，当前用到 12；`pkg/uuid/internal_generator.go:68` 63 位编码含 4 位类型。家庭、成员、邀请、审计、汇率不能各占一个新 type 而越界。可用独立表内键策略或共用 DEFAULT 类型的统一生成器，必须测试重启/并发及与上游未来类型分配冲突。
5. **三个数据库。** PostgreSQL 优先运行，但所有模型与构建继续纳入三库。DDL 的布尔/时间/JSON/部分索引兼容必须显式处理；推荐审计前后值用文本 JSON 而非依赖 PostgreSQL 专属 JSONB 查询。迁移需要真数据库验证，不只编译。
6. **E1/E3/E4。** Compose 单机、备份与上游跟踪在现结构上可行；应用代码在此 fork，基础设施正式声明在 homelab-infra。完整备份须包含 PostgreSQL dump+图片/头像/配置机密恢复依据，不用个人导出冒充备份。
7. **E5/E6/E7。** 锁文件、SBOM、漏洞检查可增量接 CI；离线运行应关 AI/MCP、地图/外部头像等不需能力。汇率固定快照可人工导入；不能把自托管误解为所有功能没有外网请求。依赖审查结果见安全报告。

## 验收入口和不确定项

实施前确定：是否接受默认仅 shareFrom 之后（本人可显式回溯授权）的当前 active 成员交易、退出撤销全部贡献；私人分类是否接受默认匿名计入金额；家庭统计本位币/时区；只读是否看金额；成员可否授权明细；解散和注销保留期。已确认家庭规模、个人模式、默认汇总/授权和退出撤权；其余细节仍为建议。

对需求“完整 uid 过滤清单”的交付见 [UID 索引](uid-query-inventory.md) 与 [完整谓词 CSV](uid-query-inventory.csv)：216 处显式谓词，15 个 Go 文件，142 个路由注册索引。表中明确标记路由候选、聚合与修改范围；它是定义明确的静态检索全集，尚不等于所有隐式 ORM 查询和动态调用的语义全集。实施阶段应以 Go 类型化调用图、全部查询会话人工复核以及跨家庭自动化矩阵补齐。

本轮未运行家庭功能测试，因为尚无改造实现；未用假数据测试结果替代已实现能力。工作量与分阶段验收见改造方案及风险报告。
