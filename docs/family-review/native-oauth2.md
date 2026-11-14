# Android 原生认证交接

服务端 0.1.3 新增原生 PKCE 交接接口；Android 使用 Kotlin/Compose、系统浏览器和现有 Go 业务 API。该源码版本准备不代表镜像发布、部署或真实 Pocket ID/Android 验收。现行 Web 登录及 `/oauth2/callback` 保留，不把 Pocket ID client secret 放入 APK。公开注册和 OAuth 自动注册继续关闭。

## 接口

所有 JSON 接口沿用 `success/result/error` 包装。原生接口要求已有 OIDC 配置并启用上游 PKCE。

1. `POST /api/native/oauth2/start.json`：`codeChallenge`、`codeChallengeMethod=S256`、`redirectUri=top.fjzhang233.books://oauth2/callback`、`state`。Android 生成独立 verifier 和强随机 state，保存到短期加密状态。返回 `flowId`、`authorizationUrl`、`expiresAt`。authorizationUrl 为当前服务 origin 的精确 `/oauth2/native/start?flow_id=...`，应用核对 origin/path/flowId 后用系统浏览器打开；后端继续跳转 Pocket ID。
2. 已登录关联使用 `POST /api/v1/users/external_auth/oauth2/native_link.json`，同样请求体与响应，Bearer 普通会话决定 UID，客户端不能指定 UID。
3. 浏览器完成后仅回跳固定 scheme 的 `code` 和原始 `state`。Android 先常量时间比对保存的 state，再 `POST /api/native/oauth2/exchange.json`：`flowId`、`code`、`codeVerifier`。只在响应 body 返回 `callbackToken`、`requiresPassword`，不得记录或放入 URI、Intent 日志。
4. `POST /api/oauth2/authorize.json` 使用 callbackToken 作为 Bearer。首次关联提交 `password`，启用本地两步验证时同时提交 `passcode`；已绑定且不需确认时允许 `{}`。仍使用现有本地密码校验、2FA、防失败重试与同 UID 关联逻辑。成功返回现有 AuthResponse，普通 token 按既有会话策略管理。错误密码可在固定到期前重试，不延长 scoped token。
5. 浏览器阶段取消使用 `POST /api/native/oauth2/cancel.json`：`flowId`、`codeVerifier`；交换后取消密码页使用 `POST /api/oauth2/cancel.json`：callbackToken Bearer、body `{}`。两者均不能撤销普通会话。Android 完成、失败或取消时清理 verifier/state/code/callbackToken。

## 限额与安全边界

回跳 URI 精确固定，禁止任意 redirect。challenge 必须规范 43 字符 S256，verifier 必须 43–128 RFC 7636 字符；state 必须 43–128 URL 安全字符且由 Android 生成验证。flow 仅存内存，5 分钟过期；最多 128 个、每来源 8 个，访问时清理过期项，重启后全部失效。浏览器结果票据 60 秒、原子一次消费；错误 verifier 不消费合法票据，累计五次失败后作废。跨 flow 的 code 不可交换。callbackToken 为现有受限类型，固定 2 分钟，仅可进入密码/2FA 确认或取消接口，不能访问普通业务 API；普通 token 只能在临时 token 原子撤销成功后创建。首次绑定竞争仍受数据库唯一约束保护。

start/exchange/cancel 完整读取最多 4 KiB JSON，拒绝超长 chunked 尾部和第二对象。新增响应使用 no-store、no-cache 和 no-referrer。已识别 native flow 的 IdP 错误经一次性票据返回归一化错误；未知 state 不反射任意回跳。native 上游 token/UserInfo 错误及 HTTP debug body 不记录原响应，不把 IdP error_description 传给客户端。原生 scheme 存在其他应用注册同 scheme 的可能，PKCE 使拦截 code 的应用无法交换；将来 HTTPS App Link 需要正式域名关联声明后单独设计。

## 验证和恢复

单元测试覆盖固定 redirect、S256/状态格式、过期、清理、限额、重启失效、并发交换单次消费、错误 verifier/跨 flow/重放、取消、超过 4 KiB、provider start 失败清理、错误回跳归一化、旧 Web redirect 和原生日志隔离。manager 的 race 检查已在 Windows clang 工具链运行通过。

仅回环的独立 SQLite + 合成 OIDC 签名 issuer 回路验证首次本地密码绑定原 UID、错误密码后同 token 重试、120 秒固定 TTL、取消后拒绝、已绑定空 body 登录、16 并发 authorize 恰一成功、上游 token/UserInfo 敏感 sentinel 不落日志；用户数保持 1。合成 issuer 不等于真实 Pocket ID/Passkey，接口回路不等于手机安装或真实系统浏览器验收。逐次结果及合成输入只在忽略且受限的 output 中保留。

没有新增数据库模型、迁移或依赖；新增 flow 为内存态，scoped token 使用现有 token_record 类型与字段。仍须升级前取得一致性备份；不得把本次未改 schema 泛化为未来跨 schema 只切旧镜像的回退方案。重启使未完成 native flow 失效，客户端提示重新开始；已完成普通会话沿用现行数据库恢复及撤销要求。
