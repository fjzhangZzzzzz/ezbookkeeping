# 上游源码安全审计（有范围限制）

审计对象：本地 `82920c62fd774b95eaed7fe78938675b56b73cf2`，2026-11-13。此 checkout 的 `package.json:3` 标为 2.2.0；它不是已经核实的最新稳定版 2.1.1。公开公告、稳定版 commit 和修复版本见 [上游核验](upstream-verification.md)。下文源码行号均属于本地 commit，不能当成稳定版同名文件的行号。

## 1. 结论与检查边界

**在本次实际阅读的认证、上传、存储、网络客户端、导入导出、MCP 和前端脚本路径中，没有找到足以认定“后门或恶意代码”的证据。不能因此保证项目及依赖没有后门。** 未完成需求 S1 所要求的 `pkg/`、`cmd/`、`src/` 全量逐文件逐行通读，也未完成全部依赖包实现人工审计、发布镜像检查和动态出站抓包，因此该项结论为“有限静态审查未发现恶意证据，完整检查未完成”。

方法：对上述三个目录搜索 `exec/unsafe/syscall/reflect/eval/Function/base64/hex/http.NewRequest/Do/https/v-html/innerHTML`，读取命中上下文并沿路由→中间件→服务追踪；读取 `go.mod`、`package.json`、Dockerfile、默认配置；实际运行依赖检查。搜索能生成候选清单，不能证明没有混淆、动态 URL、依赖初始化行为或 IDOR。未运行真实服务、攻击流量、生产数据库迁移或镜像内容检查。

优先处理的结果：

| 编号 | 类别 | 结果 | 严重性及限制 |
|---|---|---|---|
| F1 | 上游 | 默认密钥可回退到公开常量，启动仅警告 | 高风险部署前置项；不是默认管理员或通用登录后门 |
| F2 | 上游 | CSV/TSV 文本字段未过滤公式前缀 | 中；已通过字符串转换推导，尚未在 Excel 交互复现 |
| F3 | 上游 | 请求日志含原始 query，TOTP 重放日志含验证码，LLM debug 可含响应正文 | 中；需结合日志开关和保留权限评估 |
| F4 | 上游 | 图片上传先解析 multipart，再检查首文件大小；类型依赖扩展名 | 中；未证明可执行代码，需上传总量和真实格式验证 |
| F5 | 上游 | PBKDF2-SHA256 固定 10000 次 | 离线数据库泄漏抗猜测成本偏低；没有明文密码导出证据 |
| F6 | 上游/部署 | 可配置服务端 URL 未见统一内网 IP/重定向限制；自定义脚本 sandbox 未限制网络 | 配置权限与不可信脚本相关；没有证明普通请求者能任意 SSRF |
| F7 | 供应链 | npm audit 告警 27 个包节点，其中 critical 1 | 依赖告警，不等于应用可直接利用；需补可达性验证 |

## 2. 证据与可复核路径

### E1 默认密钥与认证模型

- `pkg/settings/setting.go:196`：`defaultSecretKey string = "ezbookkeeping"`；`:1094` 将其作为配置默认值。
- `cmd/initializer.go:62`：`if config.SecretKeyNoSet { log.BootWarnf(...) }`，继续初始化，不是拒绝启动。
- `pkg/services/tokens.go:92` 区分三段 `ebk` token；`:446` 解析加密 token；`:464` `hmac.Equal(signature, s.generateTokenSignature(record))`；`:917` `hmac.New(sha1.New, []byte(record.GetSecret()))`。`:572` 使用 SecretKey 解密 token ID，`:735` 为 token 记录生成随机 secret。`pkg/utils/numbers.go:49` 使用 `rand.Int(rand.Reader, ...)`，不是可预测伪随机。
- `pkg/services/tokens.go:623` 另有 `jwt.ParseWithClaims` 兼容旧 token，签名密钥取数据库 record.Secret，`:648` 使用 `jwt.WithIssuedAt()`。该调用未显式 `WithValidMethods`，建议针对遗留 JWT 增加算法白名单回归，不据此断言可用 none 算法绕过。
- `cmd/webserver.go:336`：刷新使用 `bindApiWithTokenUpdate(..., core.USER_TOKEN_TYPE_NORMAL)`；`pkg/middlewares/token_restriction.go:12` 按类型白名单拒绝。**不要仅看到刷新 handler 内没有检查就判断 API token 可提权**；实际检查在绑定层。
- `pkg/services/tokens.go:280` 起删除 token 记录；`:640` 等核对数据库记录有效期。令牌不是仅靠客户端过期时间的无状态 JWT。

判断：默认密钥削弱加密配置，须部署时生成高熵密钥并备份，不能推导“知道默认密钥即可登录任意用户”，因为另有每条 token 记录校验。主流程改用新 token，需求文档“JWT 两类令牌”的理解已过时；还有 MCP、2FA、邮箱验证、重置密码及派生作用域。

### E2 密码与 2FA

`pkg/utils/strings.go:240`：

```go
encodedPassword := pbkdf2.Key([]byte(password), []byte(salt), 10000, 48, sha256.New)
```

`pkg/services/twofactor_authorizations.go:123` 使用 SecretKey 加密 TOTP secret；`:66` 解密。`:101` 验证 TOTP，`:20` 周期 30 秒，`:24` skew 0。`pkg/api/authorizations.go:220`、`:490` 调用已使用码检查，`:639` 使用原子 GetOrSet 保存 uid+码哈希，TTL 为 30 秒。`pkg/duplicatechecker/in_memory_duplicate_checker.go:54` 起使用 mutex 保护读取再写入。内存状态随重启丢失，不应声称跨进程、跨重启永久防重放；多实例需要共享原子存储验证。

### E3 CSV 公式注入

`pkg/converters/default/default_transaction_plain_text_data_table.go:118`：

```go
func (b *defaultTransactionPlainTextDataTableBuilder) ReplaceDelimiters(text string) string {
    text = strings.Replace(text, "\r\n", " ", -1)
    text = strings.Replace(text, "\r", " ", -1)
    text = strings.Replace(text, "\n", " ", -1)
    text = strings.Replace(text, b.columnSeparator, " ", -1)
    text = strings.Replace(text, b.lineSeparator, " ", -1)
    return text
}
```

`:114` 按 `%s` 写入行。`pkg/converters/default/default_transaction_data_plain_text_converter.go:64` 起复用该 builder 导出；`pkg/converters/converter/data_table_transaction_data_exporter.go:72` 将 transaction.Comment 直接交给 ReplaceDelimiters。示例备注 `=1+1` 不含逗号或换行，经这段函数仍是 `=1+1`，表格软件可能按公式解释。需要覆盖 `= + - @`、前导制表符/空白及不同 Excel 打开模式；新家庭导出必须按文本单元格写 XLSX、对 CSV 文本字段做公式防护，并提供保真机器导出策略。

### E4 日志泄露

- `pkg/middlewares/request_log.go:15`：`query := c.Request.URL.RawQuery`，`:40` 将其拼回 path，`:45` 输出。`pkg/middlewares/authorization.go:28` 支持 query token 鉴权，`pkg/core/context_web.go:191` 读取 query token。因此 URL 中派生 token 可能进入日志；还需检查反代日志。
- `pkg/api/authorizations.go:642`：`log.Warnf(... "passcode ...", passcode, uid, remark)`；重用验证码触发时记录真实验证码。应仅记录重放事件、uid 和时间，不记录码值。
- `pkg/llm/provider/common/common_http_large_language_model_provider.go:60` 起注册响应日志回调：`log.Debugf(... "response is %s", data)`。`pkg/httpclient/http_client.go:33` 仅 enableHttpResponseLog 时调用；开启响应日志并保留 debug 输出可能保存识别的账目。请求侧也有独立日志：`pkg/llm/provider/openai/openai_common_compatible_large_language_model_adapter.go:236`、`openai_common_responses_api_large_language_model_adapter.go:249` 直接记录 requestBodyBytes，关闭响应日志不能阻止 debug 级请求正文落盘。
- `pkg/middlewares/recovery.go:32` 记录服务端堆栈，`:35` 向客户端返回统一系统错误；读取范围内未见将完整 stack 原样返回。

### E5 上传、附件与文件权限

- `pkg/api/transaction_pictures.go:44` 先 `c.MultipartForm()`，`:63` 检查首文件大小，`:68` 按文件名后缀，`:70` 用 GetImageContentType 判断支持，未在此路径解码真实图像。不能把 MIME 白名单等同内容校验。
- `pkg/services/transaction_pictures.go:160`：`ID(pictureId).Where("uid=? AND deleted=?", uid, false)`；扩展名另需与记录相符。现有个人图片下载有 uid 条件，不是裸 ID 访问。
- `pkg/services/transaction_pictures.go:205` 分配系统生成 PictureId，不直接拿上传文件名构造 storage key。`pkg/storage/local_filesystem_storage.go:83` 使用 `filepath.Join(rootPath,path)`，封装本身没有根目录逃逸验证；必须同时审计所有 key 生产者，不能仅凭 Join 定性漏洞。
- `pkg/storage/local_filesystem_storage.go:24`、`:55` 使用 `os.ModePerm` 创建目录，`:59` 用 `os.Create`，最终权限受 umask 影响，并非显式 0600。数据库 SQLite 文件权限未完成运行时验证。
- `pkg/api/transactions.go:2640`、`:2836` 有导入文件大小检查，`:2653`、`:2867` `io.ReadAll`；需请求级总 body 上限和解压后上限，防止先解析、多文件、XLSX 压缩比绕过。`pkg/converters/excel/excel_ooxml_file_basic_data_table.go:186`、`:256` 调用 excelize.OpenReader 未显式传解压大小选项，依赖默认值未在本次核实。

### E6 自定义脚本、SSRF 与代理

`src/components/desktop/CustomChart.vue:203` 和 `src/views/desktop/transactions/import/tabs/ImportTransactionExecuteCustomScriptTab.vue:150` 存在 `eval(data.code)`；分别位于 iframe 注入脚本。前者 `:69`、后者 `:37` 都使用 `sandbox="allow-scripts"`，未开启 allow-same-origin。这是显式自定义脚本功能，不能据 eval 判断后门；隔离阻止同源直接读取父窗口凭据，但不会自动禁用网络或消除死循环风险。导入不可信脚本、AI 生成代码仍需独立权限提示、CSP 出站限制、执行超时及消息 source 检查。

`pkg/httpclient/http_client.go:49` 克隆默认 Transport；`:56` 根据配置允许 InsecureSkipVerify；`:73` 返回 http.Client，未设置私网地址过滤和重定向目的地策略。LLM、WebDAV、OAuth、自定义地图 URL 属配置输入，应区分管理员配置的内网合法服务和普通用户可控 SSRF；当前未证明普通用户可改这些 URL。

`pkg/api/map_image_proxies.go:115` 校验 provider 等于当前配置，`:145` 起从既定模板构造目标；不接受任意 query URL。`:153` 重写 req.URL/Host，但未在该 director 看到清除 Authorization/Cookie 的逻辑，建议补一条代理请求抓包确认凭据不会转发。`pkg/api/amap_api_proxies.go:52` 起显式移除 `ebk_` Cookie；依然应按允许头列表转发。

### E7 MCP 与权限边界

`conf/ezbookkeeping.ini:42` 默认 `enable_mcp = false`；`cmd/webserver.go:244` 起注册路由并依次挂载请求日志、MCP IP 限制、MCP token 认证。`pkg/middlewares/authorization.go:113` 要求 MCP 类型。`pkg/api/model_context_protocols.go:186` 起读取 enabledTools；`pkg/mcp/query_transactions_tool_handler.go:112` 使用加载用户的 Uid，`pkg/mcp/add_transaction_tool_handler.go:114` 同样。本次检查不是“所有 MCP 工具都已证明无越权”的完整覆盖。

提示词注入无法凭文本自动改变服务器 uid，但拥有写工具的外部代理可能按恶意备注或脚本诱导记账、把合法查询结果发给其模型服务商。家庭接口默认拒绝 API/MCP token，后续独立引入 family_id+动作 scope，不能直接沿用个人 token 的“可查个人全部数据”。

## 3. S1–S22 对照表

编号按需求保留，S8/S9 原文未定义。

| 检查 | 方法、观察与结论 | 状态/归因 |
|---|---|---|
| S1 后门/恶意代码 | 全目录危险 API/URL 搜索并读取命中业务路径；E1–E7 未形成恶意证据链；没有全量通读 | 未完成；上游 |
| S2 混淆/动态执行 | E6 两处 eval 属 sandbox 自定义脚本；utils/object.go:33 反射取字段，sgml_decoder.go:91 反射解析，MCP reflect.Type 构造 schema；普通 base64/AES 编码有业务上下文；未发现应用 Go 中 os/exec/unsafe/syscall 命中 | 有限核查；上游 |
| S3 外联 | 见第4节清单；没有从命中中发现隐蔽遥测/license 心跳；未做运行抓包和依赖 init 审计 | 有限核查；上游/部署 |
| S4 依赖恶意/仿冒 | 无 replace；锁文件已入库；工具结果见第5节；没有逐个依赖实现和注册者来源审计，不可证明无恶意包 | 未完成；供应链 |
| S5 构建可信 | Dockerfile 三阶段、最终 USER1000；tag 无 digest；源码工作流不证明远端镜像内容等同；公开发布核验见上游报告 | 部分；供应链/部署 |
| S6 隐藏账户/硬编码密钥 | 搜索 admin/password/token，读取初始化路径未见自动创建隐藏管理员；E1 默认 SecretKey 明确存在 | 有风险，未发现后门证据；上游 |
| S7 历史与维护者 | 本地历史和公开签名/公告检查见上游核验；不能以提交者名字证明身份可信或仓库从未转移 | 未能全面确认 |
| S10 认证会话 | E1 类型、数据库撤销/过期；旧 JWT 算法白名单待加强；context_web.go:208 Cookie 为 HttpOnly=true/Secure=false，调用未显式 SameSite；主会话储存在浏览器 storage | 有风险；上游/部署 |
| S11 授权 | 图片与交易账户/字典检查存在 uid；transactions.go:3338/3507/3549/3578；无全接口 IDOR 动态测试 | 有限核查；改造是新增重点风险 |
| S12 密码敏感数据 | E2 密码哈希和 TOTP 加密，token record 有 Secret；账目字段不是全库透明加密；管理员持 DB+密钥可解密 | 有风险；上游/运维 |
| S13 注入解析 | E3 CSV；transactions.go:2893 fmt.Sprintf 拼接 int64 delta，:3183 插入 len(tagIds)，不是原样用户 SQL；default JSON importer:42 json.Unmarshal，OFX reader:163 xml.NewDecoder；未见自定义 Entity 外联，但未做 XXE/深度攻击复现 | 部分；上游 |
| S14 上传 | E5；大小上限存在，真实内容/请求总量/解压后上限未闭环 | 有风险；上游/反代 |
| S15 SSRF | E6 配置 URL 与默认 HTTP transport；未证明普通用户任意 URL SSRF；DNS/redirect/内网策略未统一 | 待动态验证；上游/部署 |
| S16 AI/MCP | E7 默认关闭 MCP、独立 token/工具 mask；AI 请求正文发往所选服务商，debug 可能落日志 | 有风险，可关闭；上游/配置 |
| S17 限流 | api/base.go:157 按 IP/uid，authorizations.go:74/193/304/422 及 forget_passwords.go:52 有调用；搜索未见 users 注册接口调用同一计数器；邀请为新增待实现；内存计数重启丢失 | 部分覆盖；上游/改造 |
| S18 前端 | E6 sandbox、userstate.ts:16/21 local/session storage token；搜索未见 v-html 命中，不等于 XSS 已排除；未见应用 CSP 设置；Cookie scope 和 bearer API 区别需保留 | 有风险；上游/反代 |
| S19 依赖供应链 | 第5节，go.mod/go.sum、package-lock 已跟踪；govulncheck 未运行成功 | 未完成；供应链 |
| S20 部署 | conf:10 0.0.0.0、:13 8080，:617 允许注册，:620 不强制验证；Dockerfile:56 USER1000，非 root；healths.go:22/23 泄露版本/commit，未见秘密；E5 文件权限 | 需要加固；部署 |
| S21 日志隐私 | E4 已确认敏感 query/验证码/可选 AI 响应；堆栈服务端记录 | 有风险；上游/运维 |
| S22 数据合规 | users.go:649 DeleteUser 仅设 deleted/deleted_unix_time，不是物理擦除；数据清理接口存在但不等于所有备份擦除；未确认国内个人信息处理说明。传输需 TLS，静态需卷/备份加密 | 未完成；上游/运营 |

## 4. 出站网络清单及隐私边界

这是**已定位出站路径清单**，不是依赖、插件、浏览器扩展等所有运行时出站的穷尽证明。静态 URL 中的文档、版权链接和测试 fixture 不计自动外联；用户点击帮助、GitHub 等链接另会发起浏览器导航。

| 功能及目标 | 证据 | 触发/关闭 | 身份与内容 |
|---|---|---|---|
| 汇率：各央行域名，见下表 | common_http_exchange_rates_data_provider.go:48 `httpClient.Do`，各 datasource | 选择相应汇率来源后加载/更新；用 user_custom 避免外联 | 未见账户/交易正文；带出站 IP、User-Agent，第三方可获使用时间 |
| LLM：api.openai.com（responses）、api.anthropic.com、openrouter.ai、generativelanguage.googleapis.com | openai_responses_api_provider.go:18、anthropic_messages_api_provider.go:20、openrouter_chat_completions_api_provider.go:18、google_ai_large_language_model_adapter.go:19；通用 Do 在 common provider:64 | 用户调用文本/图片识别、分类或图表 AI；不配置 provider/禁止 AI | API key、提示内容及输入的账单/图片/字典，具体取决于调用；不能保证“账目明文不出站” |
| LLM：OpenAI/Anthropic compatible、自配 Ollama/LM Studio | 各 compatible provider 的 getFinal...RequestUrl，ollama adapter:80、lmstudio adapter:73 | 同上，可用内网自托管或关闭 | 同上，目的地址由配置决定 |
| OpenRouter HTTP-Referer | openrouter_chat_completions_api_provider.go:29 | 仅所选 OpenRouter provider | 常量 `https://ezbookkeeping.mayswind.net/` 是发送给 OpenRouter 的标头，**不是请求该站的遥测** |
| OAuth GitHub github.com、api.github.com | github_oauth2_provider.go:19–22、:70/103 | 用户启用并走 OAuth 登录/绑定，可关闭 OAuth | OAuth client 凭据/code/token、用户资料/邮箱 |
| OAuth Gitea/Nextcloud/OIDC 自配置服务 | gitea_oauth2_datasource.go:43、nextcloud_oauth2_datasource.go:51；OIDC provider:60 Exchange、:97 UserInfo、:148 NewProvider discovery | 配置并执行外部登录；可关闭 | 登录凭据交换与身份元数据 |
| S3/MinIO/WebDAV 自配置端点；AWS SDK 可能走凭据链 | s3_storage.go:45、minio_storage.go:31、webdav_storage.go:57/83/157/189/220/245 | 使用远端对象存储读写时；local_filesystem 避免 | 存储凭据、头像/凭证图片；SDK 凭据链/元数据访问需运行时核实 |
| SMTP 自配置 host | mail/default_mailer.go:22/34/60 | 邮件注册验证、找回密码；enable_smtp=false | 邮箱、验证/重置链接、SMTP 身份 |
| Gravatar www.gravatar.com | avatars/gravatar_provider.go:12 | 选择 Gravatar 并在浏览器显示；默认 internal | 邮箱派生哈希、浏览器 IP；哈希不等于匿名化 |
| 地图瓦片 OSM/OpenTopoMap/memomaps/cyclosm/cartocdn/tomtom/tianditu 及自配 | api/map_image_proxies.go:17–25；src/consts/map.ts:22 起；lib/map/leaflet.ts:133 | 打开地图/地理位置；可停用地图或自托管瓦片 | 地理瓦片坐标、客户端或代理 IP、部分地图 key；地图非必须国内离线能力 |
| Google/Baidu/AMap 地图脚本与请求 | src/consts/api.ts:17–19，api/amap_api_proxies.go:16–18 | 选择供应商并打开地图；可停用 | 地理位置/浏览器元数据、key；代理应清除认证头 |
| 自定义图表/导入脚本的任意目标 | E6 eval + sandbox allow-scripts | 用户执行脚本；不使用该功能/加网络 CSP | 脚本可将已传入 sandbox 的交易/导入内容发送外部，不可视为禁网隔离 |

### 汇率请求 URL（自动提取常量后检查）

所有行共同：仅选定 datasource 的请求，切换 user_custom 避免该类外联，未见账目或用户 token 参数；本表列请求 URL，不将 ReferenceUrl 当成调用。

| 源码（均在 pkg/exchangerates/） | 实际请求目标 |
|---|---|
| bank_of_canada_datasource.go:21 | www.bankofcanada.ca/valet/observations/group/FX_RATES_DAILY/json |
| bank_of_israel_datasource.go:24 | boi.org.il/PublicApi/GetExchangeRates |
| bank_of_italy_datasource.go:21 | tassidicambio.bancaditalia.it/terzevalute-wf-web/rest/v1.0/latestRates |
| bank_of_russia_datasource.go:24 | cbr.ru/scripts/XML_daily_eng.asp |
| central_bank_of_argentina_datasource.go:24 | api.bcra.gob.ar/estadisticascambiarias/v1.0/Cotizaciones |
| central_bank_of_hungary_datasource.go:23 | **http://www.mnb.hu/arfolyamok.asmx**（明文 HTTP，可被篡改汇率） |
| central_bank_of_malaysia_datasource.go:21 | api.bnm.gov.my/public/exchange-rate |
| central_bank_of_myanmar_datasource.go:20 | forex.cbm.gov.mm/api/latest |
| central_bank_of_uzbekistan_datasource.go:20 | cbu.uz/ru/arkhiv-kursov-valyut/json/ |
| czech_national_bank_datasource.go:20–21 | www.cnb.cz/.../daily.txt 与 .../fx_rates.txt |
| danmarks_national_bank_datasource.go:23 | www.nationalbanken.dk/api/currencyratesxml |
| euro_central_bank_datasource.go:22 | www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml |
| national_bank_of_belarus_datasource.go:20 | api.nbrb.by/exrates/rates |
| national_bank_of_georgia_datasource.go:20 | nbg.gov.ge/gw/api/ct/monetarypolicy/currencies/en/json |
| national_bank_of_kazakhstan_datasource.go:20 | www.nationalbank.kz/rss/rates_all.xml |
| national_bank_of_poland_datasource.go:23–24 | api.nbp.pl/api/exchangerates/tables/A 与 /B |
| national_bank_of_romania_datasource.go:23 | curs.bnr.ro/nbrfxrates.xml |
| national_bank_of_ukraine_datasource.go:20 | bank.gov.ua/NBU_Exchange/exchange |
| norges_bank_datasource.go:24 | data.norges-bank.no/api/data/EXR/B..NOK.SP |
| swiss_national_bank_datasource.go:23 | www.snb.ch/public/en/rss/exchangeRates |

## 5. 依赖清单与实际工具检查

根目录 `package.json`，不是需求文档所写 frontend/package.json；直接版本固定，根目录有 package-lock.json。`go.mod` 使用标准 require 两段，无 replace。完整声明见 [依赖附录](dependency-inventory.md)，这份清单只代表包名/版本/来源入口核对，不是第三方源码可信证明。非主流、旧版本或伪版本如 extrame/xls、extrame/ole2、robfig/go-cache 应补用途和源码核验，不能仅据包名判恶意；未见 `boltdb-go/bolt` 条目。

| 命令 | 实际结果 | 能得出的结论 |
|---|---|---|
| `go mod verify` | 输出大量写 `C:\Users\fjzha\go\pkg\mod\cache` Access is denied，末行 `all modules verified` | 有工具输出但缓存解析受限，不能视为完整依赖校验干净通过；需在允许写的独立缓存重跑并保留退出码 |
| `govulncheck ./...` | 可执行文件不存在，PowerShell CommandNotFound | **未执行成功**，无 Go 漏洞扫描结论 |
| `npm.cmd audit --ignore-scripts --json` | 有效报告，exit 1：info0/low3/moderate23/high0/critical1，total27 | 扫描未通过；这些是受影响包节点数量，不是27条独立 CVE |

npm 报告统计 dependencies：prod181/dev690/optional67/total936（按工具统计，各类未必直接相加）。核心入口：DOMPurify critical [GHSA-cg2g-xh64-v4q7](https://github.com/advisories/GHSA-cg2g-xh64-v4q7)，另含 [GHSA-mrv2-3pqr-j5jr](https://github.com/advisories/GHSA-mrv2-3pqr-j5jr)；报告节点通过 monaco-editor 依赖传播。deepmerge [GHSA-gcqx-w48m-w2h4](https://github.com/advisories/GHSA-gcqx-w48m-w2h4)，glob [GHSA-3g3m-2fjr-6pw4](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4)，minimatch [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249)。这些链接是 audit 返回的公告入口；本次未逐页读取正文和验证 sanitizer 模式可达性，不据严重级别宣称已存在可利用的应用级 critical XSS，也不能忽略它。不得自动 npm audit fix 覆盖上游锁文件。

## 6. 改造与部署新增风险（与上游分开）

| 风险 | 关键控制 | 验收 |
|---|---|---|
| N1 多 uid 范围扩大 | 独立 FamilyReadScope，由服务端家庭成员关系和披露授权产生；个人写服务不改为多 uid | 其他家庭、退出成员、伪造 scope、缓存失效测试 |
| N2 邀请枚举 | 高熵随机码，库中仅 hash，TTL/次数、一次原子扣减，IP+用户限流 | 并发最后一次、撤销后使用、过期与暴力请求 |
| N3 只读绕写 | 每个家庭写服务验证动作权限；只读家庭身份不夺走其个人记账权 | 普通/API/MCP token ×所有家庭写路由 |
| N4 字典破坏 | 保留稳定 ID、软删除、引用验证、乐观锁、审计同事务 | 重命名/合并/删除后历史与退出成员仍可回放 |
| N5 聚合 DoS | 成员/时间上限、索引、超时、缓存按权限版本隔离 | 计划和长跨度并发压测，退组即撤销旧缓存 |
| N6 审计敏感数据 | 字典差异最小化，不记交易明细、token/邀请码；保留期与访问角色独立 | 读日志越权与过期删除/备份留存检查 |
| N7 上游修复遗漏 | 固定稳定 tag/commit，安全回归独立维护，升级核对公告映射 | 每次合并重跑 token类型/IP/2FA/隔离回归 |

部署控制：独立高熵 SecretKey、HTTPS、精确可信代理、禁止直连绕过网关、关闭开放注册/API/MCP/外部 AI 的未用功能、日志 query 脱敏、最小出站、非 root、只读 rootfs+明确可写卷、数据库仅内部网络、受限文件权限和加密一致性备份。这些是建议，**本次没有修改或验证生产配置**。

## 7. 发布前尚未闭环

1. 对确定的稳定版重新执行差异审计；完整通读与检查表覆盖清单尚缺，不能颁发“无后门”保证。
2. 重跑完整 go mod verify、govulncheck，并验证 npm 告警的修复/不可达证明。
3. CVE-2025-65519 的深嵌套 JSON/XML/SGML 导入回归与修复版本证据；本地 json.Unmarshal、xml.Decoder 不能单独证明全部自定义递归解析路径已安全。
4. 运行服务的全角色 IDOR/上传/CSV/XXE/SSRF、反代头伪造、跨重启 TOTP、地图代理出站头抓包。
5. 对实际构建镜像生成 SBOM、检查层内文件、签名/摘要和构建来源；本地 Dockerfile 不能替代该项。

本报告可用于确定改造和验收门槛；没有宣称需求中“全量安全审计”已完成。
