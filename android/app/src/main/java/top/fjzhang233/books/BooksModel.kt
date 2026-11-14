package top.fjzhang233.books

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** A committed write must never look like a failed write merely because the follow-up read failed. */
internal suspend fun commitThenRefresh(commit: suspend () -> Unit, afterCommit: () -> Unit, refresh: suspend () -> Unit): Boolean {
    commit(); afterCommit()
    return try { refresh(); true } catch(e:CancellationException) { throw e } catch(e:Exception) { if(e is ApiFailure && e.sessionExpired) throw e; false }
}

class BooksModel(app: Application) : AndroidViewModel(app) {
    private val store = TokenStore(app)
    var token by mutableStateOf(store.read()); private set
    val api = BooksApi(BuildConfig.API_BASE_URL) { token }
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var notice by mutableStateOf(""); private set
    var nickname by mutableStateOf("家人"); private set
    var accounts by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var categories by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var tags by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var tagGroups by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var transactions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var hasMoreTransactions by mutableStateOf(false); private set
    var family by mutableStateOf(JSONObject()); private set
    var familyItems by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var sessions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var month by mutableStateOf(BooksApi.month()); private set
    var ledgerMonth by mutableStateOf(BooksApi.month()); private set
    var dailyAmounts by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var weeklyAmounts by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var ledgerDay by mutableStateOf<String?>(null); private set
    var editTransaction by mutableStateOf<JSONObject?>(null)
    var showEditor by mutableStateOf(false)
    var draftPreviews by mutableStateOf<Map<String,ByteArray>>(emptyMap())
    var draftPictures by mutableStateOf<List<String>>(emptyList())
    var draftAttachmentProblem by mutableStateOf(false)
    var browserUrl by mutableStateOf<String?>(null)
    var oidcCallbackToken by mutableStateOf<String?>(null); private set
    var oidcNeedsPassword by mutableStateOf(false); private set
    var twoFactorToken by mutableStateOf<String?>(null); private set
    var externalLinked by mutableStateOf(false); private set
    var browserPending by mutableStateOf(false); private set
    private val operations = Mutex()
    private var oidcLink = false
    private var page = 1
    private var familyCursor = ""
    private var refreshAt = 0L
    private val protectedFlow = TokenStore(app, "native-pkce", "native-flow")
    init { browserPending=protectedFlow.read()!=null; if (token != null) reload() }

    fun act(done: (() -> Unit)? = null, block: suspend () -> Unit) {
        busy = true; error = ""; notice = ""
        viewModelScope.launch {
          operations.withLock {
            busy = true
            try { block(); done?.invoke() }
            catch (e: CancellationException) { throw e }
            catch (e: ApiFailure) {
                if (e.sessionExpired) localLogout(false)
                error = e.message ?: "请求未完成"
            }
            catch (e: IllegalArgumentException) { error = e.message ?: "请检查输入" }
            catch (_: Exception) { error = "网络暂不可用，请检查连接后重试" }
            finally { busy = false }
          }
        }
    }
    private suspend fun acceptAuth(result: JSONObject) {
        if (result.optBoolean("need2FA")) { twoFactorToken = result.getString("token"); return }
        val value = result.optString("token"); require(value.isNotBlank()) { "未取得有效会话" }
        try { store.write(value) } catch(_:Exception) { runCatching { api.call("/api/logout.json",null,true,value) }; throw IllegalArgumentException("无法安全保存会话，请检查设备安全设置后重试") }
        token = value; nickname = result.optJSONObject("user")?.optString("nickname") ?: "家人"
    }
    fun login(name: String, password: String, passcode: String) = act {
        require(name.isNotBlank() && password.isNotBlank()) { "请填写用户名和密码" }
        acceptAuth(api.login(name.trim(), password, ""))
        if (twoFactorToken != null && passcode.isNotBlank()) finishTwoFactorInternal(passcode)
        if (token != null) loadAll()
    }
    private suspend fun refreshSession() {
        if (token == null || System.currentTimeMillis() - refreshAt < 30 * 60_000) return
        val data = api.refresh()
        data.optString("newToken").takeIf { it.isNotBlank() }?.let {
            store.write(it); token = it
            data.optString("oldTokenId").takeIf(String::isNotBlank)?.let { old -> api.call("/api/v1/tokens/revoke.json", JSONObject().put("tokenId", old)) }
        }
        data.optJSONObject("user")?.let { nickname = it.optString("nickname", "家人") }
        refreshAt = System.currentTimeMillis()
    }
    private suspend fun loadAll() {
        refreshSession()
        accounts = api.accounts(); categories = api.categories(); tags = api.tags(); tagGroups = api.tagGroups()
        externalLinked = (api.call("/api/v1/users/external_auth/list.json") as JSONArray).objects().any { it.optString("externalAuthType") == "oidc" && it.optBoolean("linked") }
        page = 1; val listed=api.ledgerPage(page,ledgerMonth,ledgerDay);transactions=listed.items;hasMoreTransactions=listed.hasMore;dailyAmounts=api.dailyAmounts(ledgerMonth)
        val today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));weeklyAmounts=api.dailyRange(BooksApi.dayBounds(today.minusDays(6).toString()).first,BooksApi.dayBounds(today.toString()).second)
        family = api.family(month)
        val result = api.familyTransactions(month); familyItems = result.array("items"); familyCursor = result.optString("nextCursor")
    }
    fun dismissFeedback(value:String){if(error==value)error="";if(notice==value)notice=""}
    fun reload() = act { loadAll() }
    fun resume() { if (token != null && !busy) act { refreshSession() } }
    fun loadMore() = act {if(!hasMoreTransactions)return@act;val result=api.ledgerPage(page+1,ledgerMonth,ledgerDay);hasMoreTransactions=result.hasMore;if(result.items.isNotEmpty()){page++;transactions=(transactions+result.items).distinctBy{it.id()}}}
    fun changeLedgerMonth(value:String)=act {
        java.time.YearMonth.parse(value)
        val listed=api.ledgerPage(1,value);val rows=listed.items;val totals=api.dailyAmounts(value)
        ledgerMonth=value;ledgerDay=null;page=1;transactions=rows;hasMoreTransactions=listed.hasMore;dailyAmounts=totals
    }
    fun changeLedgerDay(value:String?)=act {
        if(value!=null)require(java.time.YearMonth.from(java.time.LocalDate.parse(value)).toString()==ledgerMonth)
        val listed=api.ledgerPage(1,ledgerMonth,value);ledgerDay=value;page=1;transactions=listed.items;hasMoreTransactions=listed.hasMore
    }
    fun changeMonth(value: String) = act {
        require(runCatching { java.time.YearMonth.parse(value) }.isSuccess) { "请输入 YYYY-MM 格式月份" }
        month = value; family = api.family(value)
        val result = api.familyTransactions(value); familyItems = result.array("items"); familyCursor = result.optString("nextCursor")
    }
    fun moreFamily() = act {
        if (familyCursor.isEmpty()) { notice = "已显示全部家庭流水"; return@act }
        val result = api.familyTransactions(month, familyCursor); familyItems = familyItems + result.array("items"); familyCursor = result.optString("nextCursor")
    }
    fun openTransaction(id: String) = act {
        val data=api.transaction(id)
        if(data.optInt("type")==1) { notice="余额调整记录请通过账户或网页核对，不能按普通收支编辑"; return@act }
        draftPreviews=emptyMap();editTransaction=data;draftPictures=data.array("pictures").map { it.id("pictureId") };draftAttachmentProblem=false;showEditor=true
    }
    fun newTransaction() { editTransaction=null;draftPreviews=emptyMap();draftPictures=emptyList();draftAttachmentProblem=false;showEditor=true }
    fun closeTransaction(){showEditor=false;editTransaction=null;draftPictures=emptyList();draftPreviews=emptyMap();draftAttachmentProblem=false}
    fun removeDraftPicture(id:String){draftPictures=draftPictures-id;draftPreviews=draftPreviews-id}
    fun saveTransaction(payload: JSONObject, existing: Boolean, keepOpen:Boolean=false, onSaved:()->Unit={}) = act {
        val refreshed = commitThenRefresh({ api.call("/api/v1/transactions/${if (existing) "modify" else "add"}.json", payload) }, { showEditor = keepOpen; editTransaction = null; draftPictures=emptyList();draftPreviews=emptyMap();draftAttachmentProblem=false;notice = "已保存";onSaved() }, { loadAll() })
        if(!refreshed) notice="已保存，数据刷新失败，可重试刷新"
    }
    fun saveEntity(path: String, payload: JSONObject, done: () -> Unit) = act {
        val refreshed=commitThenRefresh({api.call(path,payload)},{done();notice="已保存"},{loadAll()})
        if(!refreshed) notice="已保存，数据刷新失败，可重试刷新"
    }
    fun getSessions() = act { sessions = (api.call("/api/v1/tokens/list.json") as JSONArray).objects() }
    fun revokeSession(id: String, current: Boolean) = act {
        api.call("/api/v1/tokens/revoke.json", JSONObject().put("tokenId", id))
        if (current) localLogout() else sessions = (api.call("/api/v1/tokens/list.json") as JSONArray).objects()
    }
    fun revokeOthers() = act { api.call("/api/v1/tokens/revoke_all.json", JSONObject()); sessions = (api.call("/api/v1/tokens/list.json") as JSONArray).objects(); notice = "其他会话已撤销" }
    fun logout() = act { api.call("/api/logout.json"); localLogout() }
    fun forgetLocal() { localLogout(); notice = "已清除此设备会话；离线退出不会撤销服务器会话" }
    private fun localLogout(clearFlow: Boolean = true) {
        store.clear(); token = null; refreshAt = 0; accounts = emptyList(); categories = emptyList(); tags = emptyList()
        tagGroups = emptyList(); transactions = emptyList(); familyItems = emptyList(); family = JSONObject(); sessions = emptyList()
        hasMoreTransactions=false;dailyAmounts=emptyList();weeklyAmounts=emptyList();ledgerDay=null;editTransaction = null; showEditor = false; oidcCallbackToken = null; twoFactorToken = null;draftPictures=emptyList();draftPreviews=emptyMap();draftAttachmentProblem=false
        if (clearFlow) protectedFlow.clear()
    }
    private fun random(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun beginOidc(link: Boolean = false) = act {
        protectedFlow.read()?.let(::JSONObject)?.let { old -> try { api.call("/api/native/oauth2/cancel.json", JSONObject().put("flowId",old.getString("flowId")).put("codeVerifier",old.getString("verifier")),false) } catch(e:ApiFailure) { if(e.code != 217017) throw e } finally { protectedFlow.clear(); browserPending=false } }
        require(!link || !externalLinked) { "Pocket ID 已关联。如需取消关联，请在网页账户安全设置操作。" }
        val verifier = random(); val state = random()
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val body = JSONObject().put("codeChallenge", challenge).put("codeChallengeMethod", "S256").put("redirectUri", "top.fjzhang233.books://oauth2/callback").put("state", state)
        val path = if (link) "/api/v1/users/external_auth/oauth2/native_link.json" else "/api/native/oauth2/start.json"
        val result = api.call(path, body, link) as JSONObject
        oidcLink = link; browserPending = true
        protectedFlow.write(JSONObject().put("verifier", verifier).put("state", state).put("flowId", result.getString("flowId")).put("link", link).put("started", System.currentTimeMillis()).toString())
        val uri = Uri.parse(result.getString("authorizationUrl"))
        val origin = Uri.parse(BuildConfig.API_BASE_URL)
        require(uri.scheme == origin.scheme && uri.host == origin.host && uri.port == origin.port && uri.path == "/oauth2/native/start" && uri.getQueryParameter("flow_id") == result.getString("flowId")) { "身份服务返回了非预期地址" }
        browserUrl = uri.toString()
    }
    fun onCallback(uri: Uri) = act {
        require(uri.scheme == "top.fjzhang233.books" && uri.host == "oauth2" && uri.path == "/callback") { "无效的登录回调" }
        val flow = protectedFlow.read()?.let(::JSONObject) ?: throw IllegalArgumentException("登录流程已失效，请重试")
        require(System.currentTimeMillis() - flow.getLong("started") < 300_000 && uri.getQueryParameter("state") == flow.getString("state")) { "登录校验已过期，请重试" }
        oidcLink = flow.optBoolean("link"); protectedFlow.clear(); browserPending = false
        require(uri.getQueryParameter("errorCode").isNullOrBlank()) { "Pocket ID 授权未完成，请重新登录" }
        val result = api.call("/api/native/oauth2/exchange.json", JSONObject().put("flowId", flow.getString("flowId")).put("code", uri.getQueryParameter("code")).put("codeVerifier", flow.getString("verifier")), false) as JSONObject
        oidcCallbackToken = result.getString("callbackToken"); oidcNeedsPassword = result.optBoolean("requiresPassword")
        if (!oidcNeedsPassword) finishOidcInternal("", "")
    }
    private suspend fun finishOidcInternal(password: String, passcode: String) {
        val callback = oidcCallbackToken ?: throw IllegalArgumentException("登录流程已失效，请重试")
        val body = JSONObject().put("password", password).put("passcode", passcode)
        if (oidcLink && token != null) body.put("token", token)
        val result = try { api.call("/api/oauth2/authorize.json", body, true, callback) as JSONObject }
        catch (failure: ApiFailure) {
            if (failure.code in setOf(202002, 202003, 202004, 202011)) {
                oidcCallbackToken = null; oidcNeedsPassword = false; oidcLink = false
                throw ApiFailure(failure.code, "Pocket ID 确认已失效，请重新发起登录或关联")
            }
            throw failure
        }
        acceptAuth(result); oidcCallbackToken = null; oidcNeedsPassword = false
        if (token != null) loadAll()
    }
    fun finishOidc(password: String, passcode: String) = act { finishOidcInternal(password, passcode) }
    fun dismissOidc() = act {
        try { oidcCallbackToken?.let { api.call("/api/oauth2/cancel.json", JSONObject(), true, it) } } finally { oidcCallbackToken = null; oidcNeedsPassword = false }
    }
    fun cancelBrowser() = act {
        try { protectedFlow.read()?.let(::JSONObject)?.let { flow -> api.call("/api/native/oauth2/cancel.json", JSONObject().put("flowId", flow.getString("flowId")).put("codeVerifier", flow.getString("verifier")), false) } } finally { protectedFlow.clear(); browserUrl = null; browserPending = false }; notice = "已取消 Pocket ID 登录"
    }
    fun browserFailed() { browserUrl = null; error = "无法打开系统浏览器，请安装可用浏览器后重试" }
    fun imageFailed(failure: Exception) { if(failure is ApiFailure && failure.sessionExpired) { localLogout(false); error=failure.message.orEmpty() } }
    private suspend fun finishTwoFactorInternal(passcode: String) {
        val temporary = twoFactorToken ?: throw IllegalArgumentException("验证码流程已失效，请重新登录")
        val result = api.call("/api/2fa/authorize.json", JSONObject().put("passcode", passcode), true, temporary) as JSONObject
        acceptAuth(result); twoFactorToken = null
    }
    fun finishTwoFactor(passcode: String) = act { finishTwoFactorInternal(passcode); loadAll() }
    fun cancelTwoFactor() { twoFactorToken = null }
}
