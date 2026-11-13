# 家庭记账改造方案与工作量估算

> 范围变更：本文保留最初完整需求的评审证据和参考设计。用户已确认家庭自用简化方案，后续实现及预算以 [simplified-scope.md](simplified-scope.md) 为准；本文冲突的权限、迁移、多币种、三库及估算条款不再适用。

评审基线：`82920c62fd774b95eaed7fe78938675b56b73cf2`。本文为设计草案，DDL 没有在生产执行，也未经过三数据库实测；仅在内存SQLite与简化旧表桩上通过语法验证，不代表真实schema迁移通过。源码证据以该提交为准；稳定版本核实见 [上游核验](upstream-verification.md)。

## 1. 产品口径与必须先明确的边界

用户已确认每人 1 个家庭、每家庭 10 人、个人账本始终保留、家庭默认只看汇总、明细/余额/附件本人另行授权、退出撤销可见性；个人模式始终存在；只读角色限制家庭写入，不影响其个人记账；家庭能看成员收支金额。已确认口径属于目标要求，不是已经实现的行为；只读角色看金额等未明确事项仍是设计建议。

R15 下钻与 R23 默认不披露明细存在条件关系：默认只返回汇总，只有目标成员显式授予本家庭明细权限后才开放其账户名称、交易；附件单独授权。拥有者不能替成员授权。R9“私有分类”默认只保护名称和标签，不自动隐藏金额，归为“个人分类”；如果金额也要隐藏，需新增逐交易分享规则并重新估算。

推荐成员加入后仅统计加入以来且当前仍在家庭的成员交易，退出立即撤销可见性。入会前历史仅由本人显式回溯授权。该方案历史报表会因退出而变化，需在报表显示“当前成员口径”。若要求家庭保留离开成员历史账务，则必须选择经同意的独立汇总快照与删除政策，不能简单持久化 member uid 列表。

家庭默认时区 Asia/Shanghai；家庭本位币首次发布报表后禁止直接修改，重建报表另设版本。多家庭会产生“同笔交易计入哪个家庭、字典归哪个家庭”的歧义，首版 N=1，N=2 不能只提高上限。

## 2. 源码对方案的限制

- 交易所有权保留 `Uid`，金额 int64，转账两条通过 `RelatedId` 关联：`pkg/models/transaction.go:129`、`:136`、`:137`。不能全面将个人查询 `uid=?` 替换为 `IN`。
- 分类有父子层级、个人图标；标签另有分组：`pkg/models/transaction_category.go:24`、`:28`；`pkg/models/transaction_tag.go:8`。家庭共享不能只给两表加字段；标签组、分类父链和图标解析必须同域。
- 账户双方归属检查 `pkg/services/transactions.go:3338`、`:3363` 保持本人；分类/标签检查 `:3507`、`:3530`、`:3549` 改为引用权限检查。
- Xorm `Sync2` 不是完整带版本迁移器：`pkg/datastore/database.go:92`；当前路由全部走第一个数据源 `pkg/datastore/datastore.go:25`。首版单数据库部署即可，不引入跨分片统计。
- 时间字段包含每秒内序号，不能当普通 Unix 毫秒随意运算：`pkg/utils/datetimes.go:520`、`:525`、`:530`。使用这些转换函数处理边界。
- 63 位 ID 生成器只有 4 位类型：`pkg/uuid/internal_generator.go:68`；已有类型 0–12：`pkg/uuid/uuid_type.go:8`。新实体共享 DEFAULT 类型的全局生成序列，JSON ID 仍用字符串；需并发/重启唯一性验证，不为每张新表分配一个新枚举。

## 3. 数据模型与 DDL

以下是三种数据库共有的逻辑 DDL。PostgreSQL/SQLite 按原文（旧表标识符用双引号）；MySQL 将标识符双引号替换为反引号并使用 InnoDB/utf8mb4（对大小写敏感令牌摘要字段使用 ascii_bin）；SQLite 使用相同声明类型，应用严格校验类型与长度。所有 ID 由应用生成，不用自增或 SERIAL。所有时间为 UTC Unix 秒 BIGINT，local_day 为 YYYY-MM-DD 字符串，角色/状态由应用枚举校验。新表 smallint 标志不复用旧表 bool 的原始 SQL。迁移器须先读取数据库 catalog 确认旧表实际名；本次读取的初始化源码未见额外表前缀映射，旧表不是需求中假设的 ebk_transaction_categories。

```sql
CREATE TABLE ebk_family_schema_migrations (
 version BIGINT PRIMARY KEY, applied_at BIGINT NOT NULL, checksum VARCHAR(64) NOT NULL
);
CREATE TABLE ebk_families (
 family_id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL,
 base_currency VARCHAR(3) NOT NULL, timezone VARCHAR(64) NOT NULL,
 owner_uid BIGINT NOT NULL, status SMALLINT NOT NULL DEFAULT 1,
 version BIGINT NOT NULL DEFAULT 1, created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL, deleted_at BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE ebk_family_members (
 family_id BIGINT NOT NULL, uid BIGINT NOT NULL, role SMALLINT NOT NULL,
 joined_at BIGINT NOT NULL, share_from BIGINT NOT NULL,
 detail_grant SMALLINT NOT NULL DEFAULT 0, balance_grant SMALLINT NOT NULL DEFAULT 0,
 attachment_grant SMALLINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 1, updated_at BIGINT NOT NULL,
 PRIMARY KEY(family_id, uid), UNIQUE(uid)
);
CREATE TABLE ebk_family_invitations (
 invitation_id BIGINT PRIMARY KEY, family_id BIGINT NOT NULL,
 code_hash VARCHAR(64) NOT NULL UNIQUE, invited_role SMALLINT NOT NULL,
 expires_at BIGINT NOT NULL, remaining_uses INTEGER NOT NULL,
 created_by BIGINT NOT NULL, status SMALLINT NOT NULL DEFAULT 1,
 version BIGINT NOT NULL DEFAULT 1, created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE INDEX idx_finvit_family_status ON ebk_family_invitations(family_id,status,expires_at);
CREATE TABLE ebk_family_audit_logs (
 audit_id BIGINT PRIMARY KEY, family_id BIGINT NOT NULL, actor_uid BIGINT NOT NULL,
 action VARCHAR(40) NOT NULL, resource_type VARCHAR(24) NOT NULL,
 resource_id BIGINT NOT NULL, before_value TEXT NOT NULL, after_value TEXT NOT NULL,
 request_id VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL
);
CREATE INDEX idx_faudit_family_time ON ebk_family_audit_logs(family_id,created_at,audit_id);
CREATE TABLE ebk_family_fx_snapshots (
 snapshot_id BIGINT PRIMARY KEY, family_id BIGINT NOT NULL,
 local_day VARCHAR(10) NOT NULL, currency VARCHAR(3) NOT NULL,
 base_currency VARCHAR(3) NOT NULL, rate_numerator BIGINT NOT NULL,
 rate_denominator BIGINT NOT NULL, source VARCHAR(128) NOT NULL,
 observed_at BIGINT NOT NULL, created_by BIGINT NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(family_id,local_day,currency)
);
CREATE TABLE ebk_family_dictionary_aliases (
 family_id BIGINT NOT NULL, kind SMALLINT NOT NULL,
 source_id BIGINT NOT NULL, target_id BIGINT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(family_id,kind,source_id)
);
CREATE TABLE ebk_family_detach_jobs (
 job_id BIGINT PRIMARY KEY, family_id BIGINT NOT NULL, uid BIGINT NOT NULL,
 state SMALLINT NOT NULL, checkpoint BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 1, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(family_id,uid)
);
ALTER TABLE "transaction_category" ADD COLUMN family_id BIGINT NULL;
ALTER TABLE "transaction_category" ADD COLUMN family_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE "transaction_tag" ADD COLUMN family_id BIGINT NULL;
ALTER TABLE "transaction_tag" ADD COLUMN family_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE "transaction_tag_group" ADD COLUMN family_id BIGINT NULL;
ALTER TABLE "transaction_tag_group" ADD COLUMN family_version BIGINT NOT NULL DEFAULT 1;
CREATE INDEX idx_fcat_scope ON "transaction_category"(family_id,deleted,type,parent_category_id,display_order);
CREATE INDEX idx_ftag_scope ON "transaction_tag"(family_id,deleted,tag_group_id,display_order);
CREATE INDEX idx_ftgroup_scope ON "transaction_tag_group"(family_id,deleted,display_order);
```

索引中 deleted 是旧模型实际 bool 字段，迁移由方言适配器处理。SQLite ALTER ADD 支持此处常量默认值；回滚去列不依赖旧 SQLite DROP COLUMN 能力，而重建表或恢复完整备份。MySQL DDL 隐式提交，迁移按步骤记账、校验、可重入，不能宣称整个迁移事务原子。PostgreSQL/SQLite 用事务保护兼容的 DDL。MySQL TEXT 不设默认值；插入必须显式提供空 JSON 对象。三库对 BIGINT、VARCHAR、复合索引与排序行为逐库验证。以上未启用数据库 CHECK/FK，跨表归属、正汇率和次数下限由应用事务及测试兜底。

`ebk_family_members` 是“当前成员”表，退出即删除其行，历史加入/退出写审计。这让 UNIQUE(uid) 在三库都保证 N=1，不依赖只在部分数据库可用的部分唯一索引。重新加入生成新 joined_at，不自动恢复旧授权。拥有者转移事务同时更新 family.owner_uid 和两人 role；最后拥有者不得退出，必须先转移或解散。家庭和字典软删，成员关系不软删。共享字典 uid 仅作为创建人，不作为家庭数据范围；个人路径需要显式 `family_id IS NULL`。

共享分类树必须整树同域；家庭只允许内置图标，避免个人自定义图标通过删除/权限导致全家损坏。同名允许，但 UI 标明来源；合并只允许同家庭、同类型、无环映射，旧 ID 保留墓碑并解析到 canonical ID。重命名按当前名称重述报表，审计保存旧名；若要求按交易发生时显示旧名，另需名称版本表。历史 tombstone 可显示，不可新选。

不加交易 family_id：交易始终个人所有，家庭是可撤销阅读范围。已有 `(uid,deleted,...transaction_time)` 索引先通过 EXPLAIN 验证，再决定额外索引。加 family_id 会冻结历史归属、增加每个交易入口一致性负担，与退出撤销口径冲突。若未来保留历史快照，用独立不可变报表实体，而非在交易上冗余当前家庭。

退出处理：先在事务中撤销成员关系/权限、失效邀请与缓存，保留任务；独立幂等任务复制该用户仍引用的共享分类父链、标签与分组到其个人空间，并重绑其交易、tag index、模板、定时模板、导入预设中引用。任务期间禁止相关字典/模板写入，旧引用仅通过“本人交易关联所需字段”解析，不开放旧家庭字典列表。失败可重试、展示处理中，不把失败当成功退出。仍在家庭成员的引用与共享字典不变。共享别名也要展开；复制需保留图标/类型/顺序，禁止跨类型自动合并。

老数据迁移：停写备份→校验数据库及附件→运行原版受支持升级链→记录前后 schema/行数/余额/分类引用→添加新表/可空字段→所有旧字典 family_id=NULL→家庭功能开关默认关闭→个人回归→启用。绝不自动把已有私有分类转共享；由用户显式复制或映射。乐观锁使用独立递增 version，不能仅以秒级 updated_at 判并发；写语句同时约束 family_id、version、状态，affected rows=0 返回冲突，字典写与审计同事务。

回滚：尚未有家庭写入时可关闭功能保留兼容列；发生共享引用后不能直接启动上游旧版。必须先完整分离所有引用，验证后做三库专用回迁，或停写恢复同一备份中的旧镜像+数据库+附件+密钥（明确会丢弃备份后的写入）。恢复演练通过之前不承诺 RTO。

## 4. DataScope 和中间件

JWT 校验→加载当前用户→令牌类型准入→解析家庭 ID→从数据库加载当前成员/角色和家庭状态→服务端建立不可变 Scope→业务。Scope 包含 actorUid、familyId、role、authorizedMemberUids、各成员 shareFrom、detail/balance/attachment grants、membershipVersion。客户端只能选择家庭 ID，不可提交 uid 列表或角色。空 scope 直接拒绝，禁止省略 WHERE；客户端 IP 只辅助风控，不参与家庭授权。

Scope 构造器集中；handler 无法直接构造/扩大范围，service 仍校验调用意图。写操作在同一事务重新验证成员/角色与资源域，避免检查后被踢仍提交；读查询带当前成员关系约束，避免长期缓存的 uid 列表继续读离开成员。统计可短缓存，但每次命中先验证成员版本，退出/踢人主动失效。授权不可只放路由中间件，否则定时任务、CLI、导入、MCP 会绕过。

| 接口种类 | 读取范围 | 写入范围 |
|---|---|---|
| 原个人账户/交易/导出/附件 | 永远 self | 永远 self；账户 ID 必须本人 |
| 个人交易使用共享字典 | self 字典或当前家庭可用字典，历史 tombstone 只解析 | 引用权限扩展，交易所有权不变 |
| 家庭统计/汇总导出 | active members + shareFrom；不返回原始交易对象 | 无写入 |
| 家庭下钻 | 目标成员显式 detail grant；余额需额外 balance grant | 禁止修改别人交易 |
| 共享字典列表 | 所选家庭 | owner；首版不做成员提议 |
| 家庭审计 | owner，游标分页 | 业务事务附带追加，客户端不能修改 |
| 家庭成员/邀请 | 当前家庭，隐藏邀请摘要 | owner；本人可退出、撤销自己的授权 |
| 家庭附件 | 必须先验证交易归属、目标当前成员、detail+attachment grants | 无写入 |

附件必须 attachment→transaction→uid 核实真实关联，尚未绑定的临时图片仅本人可取；不允许因知道 pictureId 或 familyId 下载。预签名 URL 如启用必须短有效期，但离开后仍可能在到期前有效，首版家庭附件通过鉴权代理交付。

API 令牌和 MCP 首版禁止家庭路由，继续原 self 行为；会话令牌才能使用家庭 API。以后引入独立 family:aggregate:read 授权和 familyId 绑定、短有效期、吊销与每次成员校验，不能把 API 令牌刷新成会话来实现。MCP 不接收调用方给出的 uid/family scope，AI 提示词不具有授权能力。

## 5. API 草案

所有新增路径前缀 `/api/v1/family`；大整数 JSON 字符串，金额以本位币最小单位十进制字符串返回；时间 ISO8601/Unix 秒明确分字段，旧接口响应不改。路由动词和 envelope 最终沿用已有 API conventions；下表为新增资源契约，不是已注册端点。

| 方法与路径 | 输入要点 | 输出/权限 |
|---|---|---|
| POST /create | name,baseCurrency,timezone | family；未加入任何家庭用户 |
| GET /list | 无 | 本人家庭列表 |
| GET /:id/info | id | name,currency,timezone,version |
| PATCH /:id/info | name,expectedVersion | owner；币种冻结规则 |
| DELETE /:id | expectedVersion | owner；异步分离所有成员 |
| GET /:id/members | cursor,limit | 昵称/角色/加入时间，成员可读 |
| PATCH /:id/members/:uid | role,expectedVersion | owner，禁止隐式转移所有权 |
| POST /:id/transfer-owner | targetUid,expectedVersion | owner 原子转移 |
| DELETE /:id/members/:uid | expectedVersion | owner，踢人并撤权 |
| POST /:id/leave | expectedVersion | 本人，返回 detach job id |
| PATCH /:id/my-sharing | detail,balances,attachments,shareFrom,expectedVersion | 本人授权，不能授权他人 |
| POST /:id/invitations | role,expiresAt,maxUses | owner，码仅返回一次 |
| GET /:id/invitations | cursor,limit | owner，仅元数据 |
| DELETE /:id/invitations/:inviteId | version | owner，立即作废 |
| POST /join | code | 登录后消费，加入成功后返回 familyId |
| GET/POST /:id/categories, /tags, /tag-groups | 字典字段、父/组ID、version | 读全部成员，写 owner |
| PATCH/DELETE /:id/categories/:resourceId（标签/组同） | expectedVersion | owner；软删除/并发校验 |
| POST /:id/categories/:resourceId/merge（标签同） | targetId,expectedVersion | owner；生成映射并审计 |
| GET /:id/statistics/overview,/categories,/members,/trend | from,to,bucket,fxPolicy | 仅白名单 DTO |
| GET /:id/statistics/drilldown | targetUid,accountId,cursor | detail grant，账户不得猜测泄露 |
| GET /:id/transactions/:tid/pictures/:pid | 无 | 双重 grant+真实关联 |
| GET /:id/export | 同统计参数,format=csv/xlsx | 首版汇总导出，不默认明细 |
| GET /:id/audit-logs | cursor,limit | owner，脱敏字典变更 |
| PUT /:id/fx-snapshots | day,currency,ratio,source | owner；只允许创建未固定快照 |
| GET /:id/jobs/:jobId | 无 | 本人分离任务或 owner 解散任务 |

邀请：crypto/rand 至少 128 bit 随机值，只存 SHA256 摘要；默认24小时/1次，最大7天/10次，不能邀请为 owner。放入 URL fragment，由前端清除并 POST body，禁止在 access log/query/referrer 传播。事务条件更新剩余次数并插入成员，失败全部回滚；family 行锁串行化容量检查，另有 UNIQUE(uid) 防并发跨家庭加入。默认用户维度与可信来源 IP 双限流（例如 10次/10分钟），无效/过期/耗尽统一错误，不提供匿名试码接口。

示例：`GET /family/123/statistics/overview?from=2026-11-01&to=2026-12-01` 返回 `{familyId:"123",baseCurrency:"CNY",timezone:"Asia/Shanghai",incomeMinor:"120000",expenseMinor:"45000",netMinor:"75000",fxPolicy:"daily-fixed-v1",membershipVersion:"17",population:"current-members",complete:true}`，禁止包含 comment、经纬度、账户余额、附件 URL。实际外层 envelope 沿用上游。

新增错误码分配独立命名空间：FAMILY_FORBIDDEN（403，域外资源统一404避免枚举）、FAMILY_NOT_FOUND（404）、FAMILY_VERSION_CONFLICT（409）、FAMILY_LIMIT（409）、INVITATION_INVALID（400）、FX_SNAPSHOT_MISSING（422）、RANGE_TOO_LARGE（400）、FAMILY_RATE_LIMITED（429）；映射到上游数值码注册表，不改变旧码。分页 limit 默认50、最大200，稳定 `(createdAt,id)` 游标，返回 nextCursor；游标绑定 familyId、过滤参数并重新授权，不是权限凭证。

## 6. 统计正确性

后端专用聚合 service 返回纯统计 DTO，不能把多用户 TransactionInfoResponse 返回浏览器再做汇总。共享分类按 canonical ID，私有分类合成不带原 ID/名称的“个人分类”，标签不出现在默认家庭 DTO；成员昵称由家庭上下文给出。

金额整数/有理数运算：把账户币种最小单位按币种精度换算，乘固定日汇率 numerator/denominator，再按本位币精度做 round-half-away-from-zero，汇总逐笔已舍入值保证分类、成员和总计勾稽。使用 big.Int/big.Rat 或等价 Decimal，禁止 float64 汇总；负支出视为退款抵扣，负收入抵扣收入，余额调整排除收支。快照 base=base 时1/1，正率且分母非0。

汇率按交易发生日在家庭时区定位。无外部历史汇率时由 owner 导入/录入可追溯历史值，不能拿今天的报价冒充历史值；缺失返回422并列缺失币种/日期，不默认为1、0或悄悄跳过。固定后快照不可覆盖，重算需要显式新 policy/version（首版仅v1，后续增加版本字段及唯一键），UI/CSV 标明汇率日期与来源；同一交易编辑日期/币种后使用对应快照，历史报表随源交易编辑而重述。

收入/支出只取 DB type 2/3，排除调整1和转出4/转入5，因此转账不会污染收支。若展示转账流量，以同 uid 的 min(transactionId,relatedId) 作为 pair key 校验成对，仅计一次；不得按金额/时间猜测配对。上游两账户要求属于同 uid，跨成员 A支出/B收入没有可可靠去重的关联 ID。跨成员往来通过第9节独立关联表由双方确认后排除；未建立关联时不能宣称支持“自动跨成员中性”，报表明确会计入口口径。若业务要求首版自动识别跨成员往来，属于不可满足的输入条件，而不是 RelatedId 算法可解决。

周期采用家庭时区日界 `[from,to)`，通过上游 transaction time 转换函数构造范围；个人统计保持原行为。周一为周首，按天聚合后组合周/月/年，携带 UTC offset/DST 用例。保护：最多10成员、交互最多366天、趋势最多366桶、长跨度导出按年分段且总行上限与超时；统计索引扫描受时间限制，不直接复用无限全量拉取。缓存key包括family、成员/授权版本、字典版本、FX版本、时间/时区；事务成功后失效，不能仅按 uid 缓存。

## 7. 前端改造

桌面导航账户菜单附近与移动主页顶部提供“个人/家庭”切换；独立 Pinia family store 和 family statistics store，复用 API 类型/金额转换/日期/图表 option 构造。业务复用基础见 `src/stores/index.ts:64` 的 resetAllStates、`src/stores/transactionCategory.ts:21`、`src/stores/statistics.ts:181`。ECharts 依赖已存在（`package.json:31`，以实际行校验），不增加图表库。

切换时取消旧请求并增加 context generation，旧响应不得覆盖新状态；清 family统计、成员、授权、字典派生缓存与图表状态，个人账户/交易 store 不承载家庭原始数据。注销清空所有上下文与本地图片缓存；服务工作线程不得缓存家庭敏感 API。共享分类/标签添加家庭标识，私有加“个人”标识；只读家庭隐藏家庭写按钮同时服务端强制拒绝。拥有者维护树/分组/成员邀请/审计页；移动端保留可读图表、时间筛选、切换和邀请交互。两套 UI 分开验收，不能只测试桌面后声称移动端完成。

## 8. 工作量（人天）

| 模块 | 乐观 | 正常 | 悲观 |
|---|---:|---:|---:|
| 产品口径/源码补审/原型契约 | 3 | 5 | 8 |
| 数据模型/三库迁移/退出分离 | 6 | 10 | 16 |
| Scope/角色/授权与邀请 | 5 | 8 | 12 |
| 共享字典/引用入口/导入导出兼容 | 7 | 12 | 20 |
| 家庭统计/固定汇率/汇总导出 | 6 | 10 | 16 |
| 跨成员往来双向确认 | 6 | 10 | 16 |
| 桌面端 | 5 | 8 | 12 |
| 移动端 | 4 | 7 | 11 |
| 自动化越权/统计/迁移/并发/性能 | 7 | 12 | 20 |
| CI供应链/部署/备份恢复/文档 | 4 | 6 | 10 |
| 集成验收与缺陷修复 | 4 | 7 | 12 |
| **合计** | **57** | **95** | **153** |

按熟悉 Go/Vue 的工程师、已有自托管设施、没有银行同步/AA/跨成员自动匹配（含手工双向确认）估算；是工程人天，不是保证工期。正常约19人周，双人协作有串行设计/迁移依赖，不能直接承诺9.5周完成。该估算不含专业全源码人工安全审计/依赖逐包源代码审计，需在补审范围确认后另估。

首阶段只做个人兼容、成员、owner维护共享字典、单币种统计、汇总导出与隔离测试，约35–50人天；固定历史汇率、授权下钻、审计UI与完整移动体验后续交付。P0需要移动基本可用和审计数据落库，不能把安全必要项以P1名义延后。提议审核、多家庭、跨成员自动识别往来、保留离开成员历史快照均不在95人天内。是否首阶段必须包含 R15 授权下钻/R16 多币种，需要产品确认。

## 9. 补充：跨成员往来与接口数据契约

R17 包含“跨成员往来家庭视角中性”，完整实现需要双方明确关联，不能依赖上游仅限同人的 RelatedId。最小扩展设计如下（与前述通用 DDL 同样未执行）：

```sql
CREATE TABLE ebk_family_transfer_links (
 link_id BIGINT PRIMARY KEY, family_id BIGINT NOT NULL,
 expense_transaction_id BIGINT NOT NULL UNIQUE,
 income_transaction_id BIGINT NULL UNIQUE,
 expense_uid BIGINT NOT NULL, income_uid BIGINT NOT NULL,
 expense_confirmed SMALLINT NOT NULL DEFAULT 0,
 income_confirmed SMALLINT NOT NULL DEFAULT 0,
 status SMALLINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 1,
 expense_fingerprint VARCHAR(64) NOT NULL,
 income_fingerprint VARCHAR(64) NULL,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX idx_ftransfer_family_status ON ebk_family_transfer_links(family_id,status);
```

新增 POST `/:id/transfer-links/propose`（本人支出ID、目标成员、对方交易由对方确认时提交），POST `/:id/transfer-links/:linkId/confirm`、DELETE撤回；待确认提议记录使用 income_transaction_id=NULL，正式确认事务填入，三库唯一约束均允许多个NULL；不得使用0占位，否则唯一索引阻止多个待确认提议。入库提议不暴露他人候选交易。双方各自对自己的单据确认，owner无替代授权；不需要共享单据明细，提议只展示双方同意披露的金额/日期/昵称。

fingerprint为规范化后的交易uid/id/金额/币种/日期/分类/删除状态的SHA256摘要，确认及统计时核对，避免沿用秒级updated_at作为可靠并发版本。未确认收入端可为空。修改本人交易的所有入口都需失效关联；计算统计时再核对摘要作兜底。

只有双方确认、两人当前均为成员、两条交易在各自shareFrom后、未删除、方向为expense/income、同一家庭且固定汇率折算金额一致，才在家庭收入/支出中排除这两条。手续费另记普通支出；金额不等时拒绝关联而非自动补差。任意一方撤回、退出、修改交易金额/币种/日期/分类或删除，关联立即失效并使统计缓存失效。不可通过关联修改他人交易。若两条时间落在不同周期，各自周期均排除已确认往来，报表可显示“已排除家庭内部往来”汇总；如果需要现金流到账前暂挂属另一套会计规则，本次不做。

此为手工双向确认方案，不支持自动识别，也不做AA/债务结算。完整 R17 手工双向确认模块计 **6/10/16 人天**，已计入第8节 **57/95/153 人天** 总计。首阶段可不实现该功能，但不得声称已验收完整R17。

新增接口统一数据类型建议：

- Family：`{id,name,baseCurrency,timezone,role,version,createdAt}`；Member：`{uid,nickname,role,joinedAt,shareFrom,detailGrant,balanceGrant,attachmentGrant,version}`，不含邮箱/登录身份/账户余额。
- 创建请求：`{name:"家庭",baseCurrency:"CNY",timezone:"Asia/Shanghai"}`；成功返回Family。邀请请求：`{role:"member",expiresAt:1794739200,maxUses:1}`；返回`{id,code,expiresAt,remainingUses}`，仅创建响应包含code，后续列表不含code/hash。
- Dictionary：`{id,name,type,parentId,groupId,icon,color,scope:"family",familyId,hidden,deleted,version}`，按分类/标签/组取字段；写入不接受uid，familyId取路由，expectedVersion必填；读取墓碑只返回历史解析所需字段。
- 统计分类/成员输出：`{items:[{dimensionId,label,incomeMinor,expenseMinor,netMinor}],baseCurrency,fxPolicy,complete}`；趋势加`bucketStart,bucketEnd`，不返回原始交易ID。下钻仅在授权后返回最少明细DTO，默认无备注/地理位置/附件；账户余额必须额外balanceGrant，进一步字段披露需单独产品确认。
- 分页：`{items:[],nextCursor:null}`；冲突：`{code:"FAMILY_VERSION_CONFLICT",message:"资源已更新，请刷新",requestId:"..."}`（外层再用上游envelope）；错误详情不回显邀请码/其他家庭名/他人单据内容。

审计保留策略建议默认180天、owner可导出字典审计，成员变动/撤权元数据保留至家庭删除后30天；备份副本沿用Restic保留策略且需告知不能立即物理擦除。确需永久追溯时由产品明确，不把审计日志无期限作为默认。解散先撤权再分离引用，字典墓碑只有在没有任何交易/模板/任务引用后才可由专门清理任务处理。
