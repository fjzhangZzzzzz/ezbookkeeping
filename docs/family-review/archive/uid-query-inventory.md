# UID 查询点清单

基线：`82920c62fd774b95eaed7fe78938675b56b73cf2`。此附录列出 `pkg/`、`cmd/` 非测试 Go 文件中的显式 SQL uid 谓词及 builder uid 等值键。CSV 保留完整代码行。

## 覆盖与限制

以 Python 逐文件扫描显式字符串 `uid = / IN / <> / !=` 与 `"uid":`，按 Go 函数边界定位。路由从 `cmd/webserver.go` 注册提取，按函数名调用关系反向求可达入口。**映射是保守候选，未做 Go 类型解析**：重名方法可能过度映射，函数值、接口调用、动态 SQL、ORM bean 隐式条件可能漏映射；不能把这份检索全集等同于语义上的完整授权证明。无路由映射项明确保留，实施前必须用类型化调用图与集成测试逐项收口。业务聚合标记按函数用途识别，基础函数可能被统计间接复用。

“可以不动”指旧个人接口继续严格 self；家庭统计新建受控读仓储，不批量放宽这些条件。字典函数“必须改”也不表示将所有 uid 替换成家庭成员 uid：必须区分私人字典与 family_id 范围、服务端写权限和旧清空/注销路径。原 `GetTotal...ByUid` 个人计数仍保留。

本次命中 **216** 处、**15** 个文件；解析 **142** 个路由注册。

## 路由索引

已读取 `cmd/webserver.go` 的 Group 链：根 `router` 无路径前缀，`/avatar`（190）、`/pictures`（198）、`/icons`（206）、`/proxy`（215）、`/_AMapService`（229）、`/qrcode`（236）、根 `/oauth2`（265）；`/api`（274）下含 `/2fa`（284）、`/oauth2`（293）、`/verify_email`（307）、`/forget_password/reset`（317）及 `/v1`（326）。下表为应用完整路由路径；反向代理外部挂载前缀不属于此路由定义。方法、路径、注册行均可回查。CSV 新增 `route_paths` 保存对应完整接口路径；原 `routes` 编号保持不变。

|编号|接口|处理器|注册位置|
|---|---|---|---|
|P001|`GET /server_settings.js`|`ServerSettingsJavascriptHandler`|`cmd/webserver.go:148`|
|P002|`GET /mobile/server_settings.js`|`ServerSettingsJavascriptHandler`|`cmd/webserver.go:166`|
|P003|`GET /desktop/server_settings.js`|`ServerSettingsJavascriptHandler`|`cmd/webserver.go:183`|
|P004|`GET /avatar/:fileName`|`UserGetAvatarHandler`|`cmd/webserver.go:193`|
|P005|`GET /pictures/:fileName`|`TransactionPictureGetHandler`|`cmd/webserver.go:201`|
|P006|`GET /icons/:fileName`|`CustomIconGetHandler`|`cmd/webserver.go:209`|
|P007|`GET /healthz.json`|`HealthStatusHandler`|`cmd/webserver.go:213`|
|P008|`GET /proxy/map/tile/:zoomLevel/:coordinateX/:fileName`|`MapTileImageProxyHandler`|`cmd/webserver.go:219`|
|P009|`GET /proxy/map/annotation/:zoomLevel/:coordinateX/:fileName`|`MapAnnotationImageProxyHandler`|`cmd/webserver.go:223`|
|P010|`GET /_AMapService/*action`|`AmapApiProxyHandler`|`cmd/webserver.go:232`|
|P011|`GET /qrcode/mobile_url.png`|`MobileUrlQrCodeHandler`|`cmd/webserver.go:240`|
|P012|`GET /oauth2/login`|`LoginHandler`|`cmd/webserver.go:269`|
|P013|`GET /oauth2/callback`|`CallbackHandler`|`cmd/webserver.go:270`|
|P014|`POST /api/authorize.json`|`AuthorizeHandler`|`cmd/webserver.go:280`|
|P015|`POST /api/2fa/authorize.json`|`TwoFactorAuthorizeHandler`|`cmd/webserver.go:287`|
|P016|`POST /api/2fa/recovery.json`|`TwoFactorAuthorizeByRecoveryCodeHandler`|`cmd/webserver.go:288`|
|P017|`POST /api/oauth2/authorize.json`|`OAuth2CallbackAuthorizeHandler`|`cmd/webserver.go:296`|
|P018|`POST /api/register.json`|`UserRegisterHandler`|`cmd/webserver.go:301`|
|P019|`POST /api/verify_email/resend.json`|`UserSendVerifyEmailByUnloginUserHandler`|`cmd/webserver.go:305`|
|P020|`POST /api/verify_email/by_token.json`|`UserEmailVerifyHandler`|`cmd/webserver.go:310`|
|P021|`POST /api/forget_password/request.json`|`UserForgetPasswordRequestHandler`|`cmd/webserver.go:315`|
|P022|`POST /api/forget_password/reset/by_token.json`|`UserResetPasswordHandler`|`cmd/webserver.go:320`|
|P023|`GET /api/logout.json`|`TokenRevokeCurrentHandler`|`cmd/webserver.go:324`|
|P024|`GET /api/v1/tokens/list.json`|`TokenListHandler`|`cmd/webserver.go:331`|
|P025|`POST /api/v1/tokens/generate/api.json`|`TokenGenerateAPIHandler`|`cmd/webserver.go:332`|
|P026|`POST /api/v1/tokens/generate/mcp.json`|`TokenGenerateMCPHandler`|`cmd/webserver.go:333`|
|P027|`POST /api/v1/tokens/revoke.json`|`TokenRevokeHandler`|`cmd/webserver.go:334`|
|P028|`POST /api/v1/tokens/revoke_all.json`|`TokenRevokeAllHandler`|`cmd/webserver.go:335`|
|P029|`POST /api/v1/tokens/refresh.json`|`TokenRefreshHandler`|`cmd/webserver.go:336`|
|P030|`GET /api/v1/users/profile/get.json`|`UserProfileHandler`|`cmd/webserver.go:339`|
|P031|`POST /api/v1/users/profile/update.json`|`UserUpdateProfileHandler`|`cmd/webserver.go:340`|
|P032|`POST /api/v1/users/avatar/update.json`|`UserUpdateAvatarHandler`|`cmd/webserver.go:343`|
|P033|`POST /api/v1/users/avatar/remove.json`|`UserRemoveAvatarHandler`|`cmd/webserver.go:344`|
|P034|`POST /api/v1/users/verify_email/resend.json`|`UserSendVerifyEmailByLoginedUserHandler`|`cmd/webserver.go:348`|
|P035|`GET /api/v1/users/external_auth/list.json`|`ExternalAuthListHandler`|`cmd/webserver.go:353`|
|P036|`POST /api/v1/users/external_auth/unlink.json`|`UnlinkExternalAuthHandler`|`cmd/webserver.go:354`|
|P037|`POST /api/v1/users/external_auth/oauth2/link.json`|`LinkHandler`|`cmd/webserver.go:355`|
|P038|`GET /api/v1/users/settings/cloud/get.json`|`ApplicationSettingsGetHandler`|`cmd/webserver.go:359`|
|P039|`POST /api/v1/users/settings/cloud/update.json`|`ApplicationSettingsUpdateHandler`|`cmd/webserver.go:360`|
|P040|`POST /api/v1/users/settings/cloud/disable.json`|`ApplicationSettingsDisableHandler`|`cmd/webserver.go:361`|
|P041|`GET /api/v1/users/2fa/status.json`|`TwoFactorStatusHandler`|`cmd/webserver.go:365`|
|P042|`POST /api/v1/users/2fa/enable/request.json`|`TwoFactorEnableRequestHandler`|`cmd/webserver.go:366`|
|P043|`POST /api/v1/users/2fa/enable/confirm.json`|`TwoFactorEnableConfirmHandler`|`cmd/webserver.go:367`|
|P044|`POST /api/v1/users/2fa/disable.json`|`TwoFactorDisableHandler`|`cmd/webserver.go:368`|
|P045|`POST /api/v1/users/2fa/recovery/regenerate.json`|`TwoFactorRecoveryCodeRegenerateHandler`|`cmd/webserver.go:369`|
|P046|`GET /api/v1/data/statistics.json`|`DataStatisticsHandler`|`cmd/webserver.go:373`|
|P047|`POST /api/v1/data/clear/all.json`|`ClearAllDataHandler`|`cmd/webserver.go:374`|
|P048|`POST /api/v1/data/clear/transactions.json`|`ClearAllTransactionsHandler`|`cmd/webserver.go:375`|
|P049|`POST /api/v1/data/clear/transactions/by_account.json`|`ClearAllTransactionsByAccountHandler`|`cmd/webserver.go:376`|
|P050|`GET /api/v1/data/export.csv`|`ExportDataToEzbookkeepingCSVHandler`|`cmd/webserver.go:379`|
|P051|`GET /api/v1/data/export.tsv`|`ExportDataToEzbookkeepingTSVHandler`|`cmd/webserver.go:380`|
|P052|`GET /api/v1/accounts/list.json`|`AccountListHandler`|`cmd/webserver.go:384`|
|P053|`GET /api/v1/accounts/get.json`|`AccountGetHandler`|`cmd/webserver.go:385`|
|P054|`POST /api/v1/accounts/add.json`|`AccountCreateHandler`|`cmd/webserver.go:386`|
|P055|`POST /api/v1/accounts/modify.json`|`AccountModifyHandler`|`cmd/webserver.go:387`|
|P056|`POST /api/v1/accounts/update/last_reconciled_time.json`|`AccountUpdateLastReconciledTimeHandler`|`cmd/webserver.go:388`|
|P057|`POST /api/v1/accounts/hide.json`|`AccountHideHandler`|`cmd/webserver.go:389`|
|P058|`POST /api/v1/accounts/move.json`|`AccountMoveHandler`|`cmd/webserver.go:390`|
|P059|`POST /api/v1/accounts/delete.json`|`AccountDeleteHandler`|`cmd/webserver.go:391`|
|P060|`POST /api/v1/accounts/sub_account/delete.json`|`SubAccountDeleteHandler`|`cmd/webserver.go:392`|
|P061|`GET /api/v1/transactions/count.json`|`TransactionCountHandler`|`cmd/webserver.go:395`|
|P062|`GET /api/v1/transactions/list.json`|`TransactionListHandler`|`cmd/webserver.go:396`|
|P063|`GET /api/v1/transactions/list/by_month.json`|`TransactionMonthListHandler`|`cmd/webserver.go:397`|
|P064|`GET /api/v1/transactions/list/all.json`|`TransactionListAllHandler`|`cmd/webserver.go:398`|
|P065|`GET /api/v1/transactions/reconciliation_statements.json`|`TransactionReconciliationStatementHandler`|`cmd/webserver.go:399`|
|P066|`GET /api/v1/transactions/unreconciled_transaction_counts.json`|`TransactionUnreconciledTransactionCountsHandler`|`cmd/webserver.go:400`|
|P067|`GET /api/v1/transactions/statistics.json`|`TransactionStatisticsHandler`|`cmd/webserver.go:401`|
|P068|`GET /api/v1/transactions/statistics/trends.json`|`TransactionStatisticsTrendsHandler`|`cmd/webserver.go:402`|
|P069|`GET /api/v1/transactions/statistics/asset_trends.json`|`TransactionStatisticsAssetTrendsHandler`|`cmd/webserver.go:403`|
|P070|`GET /api/v1/transactions/amounts.json`|`TransactionAmountsHandler`|`cmd/webserver.go:404`|
|P071|`GET /api/v1/transactions/amounts/daily.json`|`TransactionDailyAmountsHandler`|`cmd/webserver.go:405`|
|P072|`GET /api/v1/transactions/get.json`|`TransactionGetHandler`|`cmd/webserver.go:406`|
|P073|`POST /api/v1/transactions/add.json`|`TransactionCreateHandler`|`cmd/webserver.go:407`|
|P074|`POST /api/v1/transactions/add_batch.json`|`TransactionCreateBatchHandler`|`cmd/webserver.go:408`|
|P075|`POST /api/v1/transactions/modify.json`|`TransactionModifyHandler`|`cmd/webserver.go:409`|
|P076|`POST /api/v1/transactions/batch_update/category.json`|`TransactionBatchUpdateCategoriesHandler`|`cmd/webserver.go:410`|
|P077|`POST /api/v1/transactions/batch_update/account.json`|`TransactionBatchUpdateAccountsHandler`|`cmd/webserver.go:411`|
|P078|`POST /api/v1/transactions/batch_update/tag/add.json`|`TransactionBatchAddTagsHandler`|`cmd/webserver.go:412`|
|P079|`POST /api/v1/transactions/batch_update/tag/remove.json`|`TransactionBatchRemoveTagsHandler`|`cmd/webserver.go:413`|
|P080|`POST /api/v1/transactions/batch_update/tag/clear.json`|`TransactionBatchClearTagsHandler`|`cmd/webserver.go:414`|
|P081|`POST /api/v1/transactions/move/all.json`|`TransactionMoveAllBetweenAccountsHandler`|`cmd/webserver.go:415`|
|P082|`POST /api/v1/transactions/delete.json`|`TransactionDeleteHandler`|`cmd/webserver.go:416`|
|P083|`POST /api/v1/transactions/batch_delete.json`|`TransactionBatchDeleteHandler`|`cmd/webserver.go:417`|
|P084|`POST /api/v1/transactions/parse_custom_file.json`|`TransactionParseImportCustomFileDataHandler`|`cmd/webserver.go:420`|
|P085|`POST /api/v1/transactions/parse_import.json`|`TransactionParseImportFileHandler`|`cmd/webserver.go:421`|
|P086|`POST /api/v1/transactions/import.json`|`TransactionImportHandler`|`cmd/webserver.go:422`|
|P087|`GET /api/v1/transactions/import/process.json`|`TransactionImportProcessHandler`|`cmd/webserver.go:423`|
|P088|`POST /api/v1/transaction/pictures/upload.json`|`TransactionPictureUploadHandler`|`cmd/webserver.go:428`|
|P089|`POST /api/v1/transaction/pictures/remove_unused.json`|`TransactionPictureRemoveUnusedHandler`|`cmd/webserver.go:429`|
|P090|`GET /api/v1/transaction/categories/list.json`|`CategoryListHandler`|`cmd/webserver.go:433`|
|P091|`GET /api/v1/transaction/categories/get.json`|`CategoryGetHandler`|`cmd/webserver.go:434`|
|P092|`POST /api/v1/transaction/categories/add.json`|`CategoryCreateHandler`|`cmd/webserver.go:435`|
|P093|`POST /api/v1/transaction/categories/add_batch.json`|`CategoryCreateBatchHandler`|`cmd/webserver.go:436`|
|P094|`POST /api/v1/transaction/categories/modify.json`|`CategoryModifyHandler`|`cmd/webserver.go:437`|
|P095|`POST /api/v1/transaction/categories/hide.json`|`CategoryHideHandler`|`cmd/webserver.go:438`|
|P096|`POST /api/v1/transaction/categories/move.json`|`CategoryMoveHandler`|`cmd/webserver.go:439`|
|P097|`POST /api/v1/transaction/categories/delete.json`|`CategoryDeleteHandler`|`cmd/webserver.go:440`|
|P098|`GET /api/v1/transaction/tags/groups/list.json`|`TagGroupListHandler`|`cmd/webserver.go:443`|
|P099|`GET /api/v1/transaction/tags/groups/get.json`|`TagGroupGetHandler`|`cmd/webserver.go:444`|
|P100|`POST /api/v1/transaction/tags/groups/add.json`|`TagGroupCreateHandler`|`cmd/webserver.go:445`|
|P101|`POST /api/v1/transaction/tags/groups/modify.json`|`TagGroupModifyHandler`|`cmd/webserver.go:446`|
|P102|`POST /api/v1/transaction/tags/groups/move.json`|`TagGroupMoveHandler`|`cmd/webserver.go:447`|
|P103|`POST /api/v1/transaction/tags/groups/delete.json`|`TagGroupDeleteHandler`|`cmd/webserver.go:448`|
|P104|`GET /api/v1/transaction/tags/list.json`|`TagListHandler`|`cmd/webserver.go:451`|
|P105|`GET /api/v1/transaction/tags/get.json`|`TagGetHandler`|`cmd/webserver.go:452`|
|P106|`POST /api/v1/transaction/tags/add.json`|`TagCreateHandler`|`cmd/webserver.go:453`|
|P107|`POST /api/v1/transaction/tags/add_batch.json`|`TagCreateBatchHandler`|`cmd/webserver.go:454`|
|P108|`POST /api/v1/transaction/tags/modify.json`|`TagModifyHandler`|`cmd/webserver.go:455`|
|P109|`POST /api/v1/transaction/tags/hide.json`|`TagHideHandler`|`cmd/webserver.go:456`|
|P110|`POST /api/v1/transaction/tags/move.json`|`TagMoveHandler`|`cmd/webserver.go:457`|
|P111|`POST /api/v1/transaction/tags/delete.json`|`TagDeleteHandler`|`cmd/webserver.go:458`|
|P112|`GET /api/v1/transaction/templates/list.json`|`TemplateListHandler`|`cmd/webserver.go:461`|
|P113|`GET /api/v1/transaction/templates/get.json`|`TemplateGetHandler`|`cmd/webserver.go:462`|
|P114|`POST /api/v1/transaction/templates/add.json`|`TemplateCreateHandler`|`cmd/webserver.go:463`|
|P115|`POST /api/v1/transaction/templates/modify.json`|`TemplateModifyHandler`|`cmd/webserver.go:464`|
|P116|`POST /api/v1/transaction/templates/hide.json`|`TemplateHideHandler`|`cmd/webserver.go:465`|
|P117|`POST /api/v1/transaction/templates/move.json`|`TemplateMoveHandler`|`cmd/webserver.go:466`|
|P118|`POST /api/v1/transaction/templates/delete.json`|`TemplateDeleteHandler`|`cmd/webserver.go:467`|
|P119|`GET /api/v1/insights/explorers/list.json`|`InsightsExplorerListHandler`|`cmd/webserver.go:470`|
|P120|`GET /api/v1/insights/explorers/get.json`|`InsightsExplorerGetHandler`|`cmd/webserver.go:471`|
|P121|`POST /api/v1/insights/explorers/add.json`|`InsightsExplorerCreateHandler`|`cmd/webserver.go:472`|
|P122|`POST /api/v1/insights/explorers/modify.json`|`InsightsExplorerModifyHandler`|`cmd/webserver.go:473`|
|P123|`POST /api/v1/insights/explorers/hide.json`|`InsightsExplorerHideHandler`|`cmd/webserver.go:474`|
|P124|`POST /api/v1/insights/explorers/move.json`|`InsightsExplorerMoveHandler`|`cmd/webserver.go:475`|
|P125|`POST /api/v1/insights/explorers/delete.json`|`InsightsExplorerDeleteHandler`|`cmd/webserver.go:476`|
|P126|`POST /api/v1/llm/transactions/recognize_text.json`|`RecognizeTransactionTextHandler`|`cmd/webserver.go:481`|
|P127|`POST /api/v1/llm/transactions/recognize_receipt_image.json`|`RecognizeReceiptImageHandler`|`cmd/webserver.go:487`|
|P128|`POST /api/v1/llm/code/generate.json`|`GenerateCodeHandler`|`cmd/webserver.go:493`|
|P129|`GET /api/v1/custom_icons/list.json`|`CustomIconListHandler`|`cmd/webserver.go:499`|
|P130|`POST /api/v1/custom_icons/upload.json`|`CustomIconUploadHandler`|`cmd/webserver.go:500`|
|P131|`POST /api/v1/custom_icons/move.json`|`CustomIconMoveHandler`|`cmd/webserver.go:501`|
|P132|`POST /api/v1/custom_icons/delete.json`|`CustomIconDeleteHandler`|`cmd/webserver.go:502`|
|P133|`GET /api/v1/import_presets/list.json`|`ImportPresetListHandler`|`cmd/webserver.go:506`|
|P134|`GET /api/v1/import_presets/get.json`|`ImportPresetGetHandler`|`cmd/webserver.go:507`|
|P135|`POST /api/v1/import_presets/add.json`|`ImportPresetCreateHandler`|`cmd/webserver.go:508`|
|P136|`POST /api/v1/import_presets/modify.json`|`ImportPresetModifyHandler`|`cmd/webserver.go:509`|
|P137|`POST /api/v1/import_presets/move.json`|`ImportPresetMoveHandler`|`cmd/webserver.go:510`|
|P138|`POST /api/v1/import_presets/delete.json`|`ImportPresetDeleteHandler`|`cmd/webserver.go:511`|
|P139|`GET /api/v1/exchange_rates/latest.json`|`LatestExchangeRateHandler`|`cmd/webserver.go:514`|
|P140|`POST /api/v1/exchange_rates/user_custom/update.json`|`UserCustomExchangeRateUpdateHandler`|`cmd/webserver.go:515`|
|P141|`POST /api/v1/exchange_rates/user_custom/delete.json`|`UserCustomExchangeRateDeleteHandler`|`cmd/webserver.go:516`|
|P142|`GET /api/v1/systems/version.json`|`VersionHandler`|`cmd/webserver.go:519`|

## 查询点逐项索引

路由编号对应上表，CSV 的 `routes` 与本文一致；每处都列出候选接口、聚合性质及改造分类。

|位置|函数|候选接口|聚合|分类|
|---|---|---|---|---|
|`pkg/services/accounts.go:41`|`GetTotalAccountCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:53`|`GetAllAccountsByUid`|P050,P051,P052,P064,P066,P070,P071,P077,P085,P126,P127|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:69`|`GetAccountByAccountId`|P049,P056,P065|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:91`|`GetAccountAndSubAccountsByAccountId`|P053,P054,P055|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:107`|`GetSubAccountsByAccountId`|无静态路由映射；CLI/定时/MCP/内部或待人工核实|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:122`|`GetSubAccountsByAccountIds`|P050,P051,P061,P062,P063,P064|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:165`|`GetAccountsByAccountIds`|P031,P062,P063,P064,P065,P072,P073,P074,P075,P076,P078,P079,P080,P081,P082,P083,P086|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:182`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|可以不动（保持个人域）|
|`pkg/services/accounts.go:206`|`GetMaxSubAccountDisplayOrder`|无静态路由映射；CLI/定时/MCP/内部或待人工核实|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:341`|`CreateAccounts`|P054|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:452`|`ModifyAccounts`|P055|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:503`|`ModifyAccounts`|P055|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:529`|`ModifyAccounts`|P055|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:536`|`ModifyAccounts`|P055|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:564`|`ModifyAccounts`|P055|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:584`|`ModifyAccounts`|P055|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:608`|`UpdateAccountExtend`|P056|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:634`|`HideAccount`|P057|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:659`|`ModifyAccountDisplayOrders`|P058|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:688`|`DeleteAccount`|P059|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:709`|`DeleteAccount`|P059|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:731`|`DeleteAccount`|P059|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:756`|`DeleteAccount`|P059|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:776`|`DeleteAccount`|P059|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:806`|`DeleteSubAccount`|P060|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:814`|`DeleteSubAccount`|P060|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:821`|`DeleteSubAccount`|P060|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:837`|`DeleteSubAccount`|P060|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:845`|`DeleteSubAccount`|P060|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/accounts.go:865`|`DeleteSubAccount`|P060|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/explorer.go:37`|`GetTotalExplorationsCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/explorer.go:49`|`GetAllExplorationNamesByUid`|P119|否/基础查询|可以不动（保持个人域）|
|`pkg/services/explorer.go:65`|`GetExplorationByExplorationId`|P120,P121,P122|否/基础查询|可以不动（保持个人域）|
|`pkg/services/explorer.go:83`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|可以不动（保持个人域）|
|`pkg/services/explorer.go:127`|`ModifyExploration`|P122|否/基础查询|可以不动（保持个人域）|
|`pkg/services/explorer.go:153`|`HideExploration`|P123|否/基础查询|可以不动（保持个人域）|
|`pkg/services/explorer.go:178`|`ModifyExplorationDisplayOrders`|P124|否/基础查询|可以不动（保持个人域）|
|`pkg/services/explorer.go:205`|`DeleteExploration`|P125|否/基础查询|可以不动（保持个人域）|
|`pkg/services/explorer.go:231`|`DeleteAllExplorations`|P047|否/基础查询|可以不动（保持个人域）|
|`pkg/services/import_presets.go:37`|`GetTotalImportPresetCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/import_presets.go:49`|`GetAllPresetNamesByUid`|P133|否/基础查询|可以不动（保持个人域）|
|`pkg/services/import_presets.go:65`|`GetPresetByPresetId`|P134,P135,P136|否/基础查询|可以不动（保持个人域）|
|`pkg/services/import_presets.go:83`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|可以不动（保持个人域）|
|`pkg/services/import_presets.go:127`|`ModifyPreset`|P136|否/基础查询|可以不动（保持个人域）|
|`pkg/services/import_presets.go:152`|`ModifyPresetDisplayOrders`|P137|否/基础查询|可以不动（保持个人域）|
|`pkg/services/import_presets.go:179`|`DeletePreset`|P138|否/基础查询|可以不动（保持个人域）|
|`pkg/services/import_presets.go:205`|`DeleteAllPresets`|P047|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:59`|`GetAllTokensByUid`|P028|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:73`|`GetAllUnexpiredNormalAndMCPTokensByUid`|P024|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:260`|`UpdateTokenLastSeen`|P029|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:283`|`DeleteToken`|P015,P016,P017,P022,P023,P027|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:304`|`DeleteTokens`|P028|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:339`|`DeleteTokensBeforeTime`|P022,P031,P043|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:351`|`DeleteTokensByType`|P020,P031|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:363`|`DeleteAllTokens`|无静态路由映射；CLI/定时/MCP/内部或待人工核实|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:402`|`ExistsValidTokenByType`|P014|否/基础查询|可以不动（保持个人域）|
|`pkg/services/tokens.go:888`|`getTokenRecord`|P017,P023|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:47`|`GetTotalTransactionCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:507`|`GetTransactionByTransactionId`|P072,P073,P075,P082|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:529`|`GetTransactionsByTransactionIds`|P076,P077,P078,P079,P080,P083|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:900`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1001`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1085`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1087`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1099`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1114`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1130`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1160`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1178`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1205`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1220`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1236`|`ModifyTransaction`|P075,P077|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1482`|`BatchUpdateTransactionsCategory`|P076|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1605`|`BatchRemoveTagsFromTransactions`|P079|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1636`|`BatchClearAllTagsFromTransactions`|P080|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1665`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1674`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1696`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1732`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1744`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1768`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1781`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1800`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1819`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1835`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1851`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1883`|`MoveAllTransactionsBetweenAccounts`|P081|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:1923`|`DeleteTransaction`|P049,P082,P083|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1947`|`DeleteTransaction`|P049,P082,P083|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1956`|`DeleteTransaction`|P049,P082,P083|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1966`|`DeleteTransaction`|P049,P082,P083|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:1973`|`DeleteTransaction`|P049,P082,P083|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2079`|`DeleteAllTransactions`|P047,P048|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2086`|`DeleteAllTransactions`|P047,P048|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2093`|`DeleteAllTransactions`|P047,P048|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2100`|`DeleteAllTransactions`|P047,P048|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2322`|`GetAccountsAndCategoriesTotalInflowAndOutflow`|P067|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:2449`|`GetUnreconciledTransactionCounts`|P066|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:2479`|`GetAccountsAndCategoriesMonthlyInflowAndOutflow`|P068|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:2667`|`doCreateTransaction`|P073,P074,P086|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2688`|`doCreateTransaction`|P073,P074,P086|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2690`|`doCreateTransaction`|P073,P074,P086|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2736`|`doCreateTransaction`|P073,P074,P086|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2797`|`doCreateTransaction`|P073,P074,P086|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2883`|`updateAccountBalance`|P049,P073,P074,P075,P077,P081,P082,P083,P086|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:2909`|`getAllTransactionsInSpecifiedDateRange`|P070,P071|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:2987`|`buildTransactionQueryCondition`|P049,P050,P051,P061,P062,P063,P064,P065,P069,P081|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:3148`|`appendFilterTagIdsConditionToQuery`|P049,P050,P051,P061,P062,P063,P064,P065,P067,P068,P069,P070,P071,P081|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:3169`|`appendFilterTagIdsConditionToQuery`|P049,P050,P051,P061,P062,P063,P064,P065,P067,P068,P069,P070,P071,P081|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:3201`|`appendFilterPicturesConditionToQuery`|P049,P050,P051,P061,P062,P063,P064,P065,P069,P081|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transactions.go:3338`|`getAccountModels`|P049,P073,P074,P075,P077,P082,P083,P086|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:3363`|`getAccountModels`|P049,P073,P074,P075,P077,P082,P083,P086|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:3376`|`getAccountModels`|P049,P073,P074,P075,P077,P082,P083,P086|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:3395`|`getAccountModels`|P049,P073,P074,P075,P077,P082,P083,P086|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:3408`|`getAccountModels`|P049,P073,P074,P075,P077,P082,P083,P086|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:3431`|`getOldAccountModels`|P075,P077|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:3443`|`getOldAccountModels`|P075,P077|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transactions.go:3507`|`isCategoryValid`|P073,P074,P075,P077,P086|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transactions.go:3530`|`isCategoryValid`|P073,P074,P075,P077,P086|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transactions.go:3549`|`isTagsValid`|P073,P074,P075,P077,P078,P086|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transactions.go:3578`|`isPicturesValid`|P073,P074,P075,P077,P086|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_categories.go:39`|`GetTotalCategoryCountByUid`|P046|是/可达聚合|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:50`|`GetAllCategoriesByUid`|P050,P051,P064,P085,P090,P126,P127|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:81`|`GetSubCategoriesByCategoryIds`|P050,P051,P061,P062,P063,P064|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:124`|`GetCategoryByCategoryId`|P072,P076,P091,P092,P094|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:146`|`GetCategoriesByCategoryIds`|P062,P063,P064|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:163`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:187`|`GetMaxSubCategoryDisplayOrder`|P092,P094|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:297`|`ModifyCategory`|P094|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:323`|`HideCategory`|P095|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:348`|`ModifyCategoryDisplayOrders`|P096|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:376`|`DeleteCategory`|P097|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:390`|`DeleteCategory`|P097|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:398`|`DeleteCategory`|P097|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:406`|`DeleteCategory`|P097|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:432`|`DeleteAllCategories`|P047|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_categories.go:440`|`DeleteAllCategories`|P047|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_pictures.go:44`|`GetTotalTransactionPicturesCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transaction_pictures.go:60`|`GetPictureInfoByPictureId`|P088|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_pictures.go:82`|`GetNewPictureInfosByPictureIds`|P073,P075|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_pictures.go:102`|`GetPictureInfosByTransactionId`|P072,P075|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_pictures.go:122`|`GetPictureInfosByTransactionIds`|P062,P063,P064|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_pictures.go:139`|`GetAllPictureInfosOfAllTransactions`|P064|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_pictures.go:160`|`GetPictureByPictureId`|P005|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_pictures.go:246`|`RemoveUnusedTransactionPicture`|P089|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_tags.go:41`|`GetTotalTagCountByUid`|P046|是/可达聚合|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:53`|`GetAllTagsByUid`|P050,P051,P064,P085,P104,P126,P127|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:69`|`GetTagByTagId`|P105,P108|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:91`|`GetTagsByTagIds`|P062,P063,P064,P072,P078,P079|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:108`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:127`|`GetAllTagIdsOfAllTransactions`|P050,P051,P064,P126,P127|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:187`|`GetAllTagIdsOfTransactions`|P062,P063,P064,P072,P075,P078|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:237`|`CreateTags`|P107|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:313`|`ModifyTag`|P108|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:339`|`HideTag`|P109|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:364`|`ModifyTagDisplayOrders`|P110|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:391`|`DeleteTag`|P111|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:400`|`DeleteTag`|P111|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:421`|`DeleteTag`|P111|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:447`|`DeleteAllTags`|P047|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:455`|`DeleteAllTags`|P047|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:471`|`ExistsTagName`|P106,P108|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tags.go:487`|`ModifyTagIndexTransactionTime`|无静态路由映射；CLI/定时/MCP/内部或待人工核实|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:38`|`GetAllTagGroupsByUid`|P098|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:54`|`GetTagGroupByTagGroupId`|P099,P101,P106,P107,P108|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:72`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:116`|`ModifyTagGroup`|P101|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:141`|`ModifyTagGroupDisplayOrders`|P102|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:168`|`DeleteTagGroup`|P103|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:176`|`DeleteTagGroup`|P103|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:202`|`DeleteAllTagGroups`|P047|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_tag_groups.go:210`|`DeleteAllTagGroups`|P047|否/基础查询|必须改（字典域/校验）|
|`pkg/services/transaction_templates.go:37`|`GetTotalNormalTemplateCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:48`|`GetTotalScheduledTemplateCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:60`|`GetAllTemplatesByUid`|P112|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:76`|`GetTemplateByTemplateId`|P113,P114,P115,P116,P117,P118|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:94`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:150`|`ModifyTemplate`|P115|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:176`|`HideTemplate`|P116|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:201`|`ModifyTemplateDisplayOrders`|P117|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:228`|`DeleteTemplate`|P118|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:254`|`DeleteAllTemplates`|P047|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:268`|`isTemplateValid`|P114,P115|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:284`|`isTemplateValid`|P114,P115|否/基础查询|可以不动（保持个人域）|
|`pkg/services/transaction_templates.go:304`|`isTemplateValid`|P114,P115|否/基础查询|必须复核（模板共享引用）|
|`pkg/services/transaction_templates.go:329`|`isTemplateValid`|P114,P115|否/基础查询|可以不动（保持个人域）|
|`pkg/services/twofactor_authorizations.go:58`|`GetUserTwoFactorSettingByUid`|P015,P017,P041|否/基础查询|可以不动（保持个人域）|
|`pkg/services/twofactor_authorizations.go:144`|`DeleteTwoFactorSetting`|P044|否/基础查询|可以不动（保持个人域）|
|`pkg/services/twofactor_authorizations.go:162`|`ExistsTwoFactorSetting`|P014,P016,P042,P043,P044,P045|否/基础查询|可以不动（保持个人域）|
|`pkg/services/twofactor_authorizations.go:174`|`UseUserTwoFactorRecoveryCode`|P016|否/基础查询|可以不动（保持个人域）|
|`pkg/services/twofactor_authorizations.go:217`|`CreateTwoFactorRecoveryCodes`|P043,P045|否/基础查询|可以不动（保持个人域）|
|`pkg/services/twofactor_authorizations.go:243`|`DeleteTwoFactorRecoveryCodes`|P044|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_app_cloud_settings.go:61`|`UpdateUserApplicationCloudSettings`|P039|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_app_cloud_settings.go:95`|`ClearUserApplicationCloudSettings`|P040|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_exchange_rates.go:33`|`GetAllCustomExchangeRatesByUid`|P139|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_exchange_rates.go:50`|`UpdateCustomExchangeRate`|P140|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_exchange_rates.go:61`|`UpdateCustomExchangeRate`|P140|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_exchange_rates.go:69`|`UpdateCustomExchangeRate`|P140|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_exchange_rates.go:119`|`DeleteCustomExchangeRate`|P141|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_exchange_rates.go:144`|`DeleteAllCustomExchangeRates`|P047|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:45`|`GetTotalCustomIconsCountByUid`|P046|是/可达聚合|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:57`|`GetAllCustomIconInfosByUid`|P129|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:73`|`GetCustomIconInfoByIconId`|P130|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:95`|`GetCustomIconByIconId`|P006|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:131`|`GetMaxDisplayOrder`|P018,P054,P055,P092,P093,P100,P106,P107,P108,P114,P121,P130,P135|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:187`|`ModifyCustomIconDisplayOrders`|P131|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:214`|`DeleteCustomIcon`|P132|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:224`|`DeleteCustomIcon`|P132|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:234`|`DeleteCustomIcon`|P132|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:260`|`DeleteAllCustomIcons`|P047|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:275`|`ExistsCustomIcon`|无静态路由映射；CLI/定时/MCP/内部或待人工核实|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_custom_icons.go:289`|`ExistsCustomIcons`|P018,P054,P055,P092,P093,P094|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_external_auths.go:33`|`GetUserAllExternalAuthsByUid`|P035|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_external_auths.go:45`|`GetUserExternalAuthByUid`|P017|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_external_auths.go:93`|`CreateUserExternalAuth`|P013,P017|否/基础查询|可以不动（保持个人域）|
|`pkg/services/user_external_auths.go:113`|`DeleteUserExternalAuth`|P036|否/基础查询|可以不动（保持个人域）|

## 需额外人工核查的隐式边界

- `pkg/services/base.go:53` 的 UserDataDB(uid) 只是分库选择，不是数据授权。`pkg/datastore/datastore.go:24` 当前恒选首库；家庭多 uid 查询在此部署无需跨分片，但不能据此取消 uid 谓词。
- `pkg/core/context_web.go:169` 当前 uid 来自已解析令牌；家庭范围必须从成员关系构造，不接受客户端 uid 集合。
- `pkg/services/transactions.go:3507` 与 `3530` 分类父子校验、`3549` 标签校验必须支持家庭字典；`3338` 与 `3363` 账户校验必须保持 self。
- `pkg/services/transaction_categories.go:390` 删除引用检查、`398` 模板引用检查原先仅查操作者 uid；共享删除必须检查所有引用并优先归档，不能认为“本用户没用”即可删。
- CLI（`pkg/cli/user_data.go`）、MCP（`pkg/mcp/*`）、计划任务与导入异步任务不是全部由本次 HTTP 路由解析覆盖；必须保持个人域或显式建立家庭服务端范围，不能复用管理员范围。
- 必须另查新增模型的 `Uid:` 赋值/ORM bean 查询、无 uid 的全局任务和按主键 Get。此表不声称“表外无越权点”。
