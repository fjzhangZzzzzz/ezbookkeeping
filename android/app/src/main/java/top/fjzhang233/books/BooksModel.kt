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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
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
    /** Server-synchronised account preference; never inferred from account list order alone. */
    var defaultAccountId by mutableStateOf(""); private set
    var accounts by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var categories by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var categoryRevision by mutableStateOf<String?>(null); private set
    var categoryReviewRequired by mutableStateOf(false); private set
    var categoryReviewReady by mutableStateOf(false); private set
    fun acknowledgeCategoryReview(){if(categoryReviewReady){categoryReviewRequired=false;categoryReviewReady=false}}
    fun retryCategoryReview()=act { loadCategories(); categoryReviewReady=true }
    private suspend fun categoryConflict(error:ApiFailure):Nothing {
        if(error.code==206013 && error.httpStatus==409) {
            categoryReviewRequired=true;categoryReviewReady=false
            try { loadCategories();categoryReviewReady=true } catch(e:CancellationException){throw e} catch(_:Exception){}
        }
        throw error
    }
    private suspend fun loadCategories() {
        val (rows,revision)=api.categorySnapshot(); categories=rows; categoryRevision=revision
    }
    fun refreshCategories() { if(token!=null && !busy) act { loadCategories() } }
    internal suspend fun refreshCategoriesNow() { loadCategories() }
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
    var newTransactionDay by mutableStateOf<String?>(null); private set
    var draftPreviews by mutableStateOf<Map<String,ByteArray>>(emptyMap())
    var draftPictures by mutableStateOf<List<String>>(emptyList())
    var draftAttachmentProblem by mutableStateOf(false)
    var copiedTransaction by mutableStateOf<JSONObject?>(null); private set
    private data class LocalCopyPicture(val file:File,val mime:String)
    private var localCopyPictures=emptyMap<String,LocalCopyPicture>()
    private var copyCacheDirectory:File?=null
    private var newlyUploadedPictures=emptySet<String>()
    private val transactionSubmission=TransactionSubmission(
        BuildConfig.API_BASE_URL.trimEnd('/'),
        identify={submissionIdentity(api.accounts(),api.family(BooksApi.month()).array("accounts"))},
        storage={uid->
            val scope=MessageDigest.getInstance("SHA-256").digest((BuildConfig.API_BASE_URL.trimEnd('/')+"\n"+uid).toByteArray()).joinToString(""){"%02x".format(it)}
            val encrypted=TokenStore(app,"pending-transaction-$scope","pending-transaction-$scope")
            object:TransactionRequestStorage{override fun read()=encrypted.readChecked();override fun write(value:String)=encrypted.write(value);override fun clear()=encrypted.clearChecked()}
        },
        send={path,body->api.call(path,body)}
    )
    private val entityWrites=run {
        val scope=MessageDigest.getInstance("SHA-256").digest(BuildConfig.API_BASE_URL.trimEnd('/').toByteArray()).joinToString(""){"%02x".format(it)}
        val encrypted=TokenStore(app,"pending-entity-$scope","pending-entity-$scope")
        EntityWriteState(BuildConfig.API_BASE_URL.trimEnd('/'),object:TransactionRequestStorage{
            override fun read()=encrypted.readChecked();override fun write(value:String)=encrypted.write(value);override fun clear()=encrypted.clearChecked()
        },identify={
            val records=(api.call("/api/v1/tokens/list.json") as JSONArray).objects()
            val own=api.accounts();entityAuthenticatedUid(records,own,if(own.isEmpty())emptyList()else api.family(BooksApi.month()).array("accounts"))
        },sendOnce={path,body->api.writeEntityOnce(path,body)})
    }
    val entityBlocked:Boolean get()=entityWrites.blocked
    val entityAwaitingReview:Boolean get()=entityWrites.blocked&&!entityWrites.inFlight
    internal val entityPendingView:EntityPendingView? get()=entityWrites.view()
    var entityReviewOpen by mutableStateOf(entityWrites.blocked&&token!=null);private set
    fun hideEntityReview(){if(!busy)entityReviewOpen=false}
    fun reviewEntityWrite()=act{entityReviewOpen=true;entityWrites.invalidateIdentity();loadAll()}
    fun abandonEntityAfterReview()=act{
        entityWrites.abandonAfterExplicitReview();entityReviewOpen=false;notice="已结束本次保存意图；这不会撤销服务器更改，请以实际列表为准"
        try{loadAll()}catch(error:CancellationException){throw error}catch(error:Exception){if(error is ApiFailure&&error.sessionExpired)throw error;notice="保存意图已结束，实际列表刷新失败，请稍后核对"}
    }
    var restoredTransaction by mutableStateOf<JSONObject?>(null);private set
    private var pendingCheckReady=false
    val transactionConfirmation:JSONObject? get()=transactionSubmission.confirmationView()
    val transactionNeedsConfirmation:Boolean get()=transactionSubmission.needsConfirmation

    private fun clearCopyCache(){
        val directory=copyCacheDirectory
        localCopyPictures.values.forEach{item->if(directory!=null && item.file.parentFile==directory)item.file.delete()}
        directory?.delete() // Only this exact, empty directory. Never recurse into cache or original storage.
        localCopyPictures=emptyMap();copyCacheDirectory=null
    }
    fun registerDraftPicture(id:String,preview:ByteArray){
        transactionSubmission.requireEditable()
        require(Regex("[1-9][0-9]*").matches(id))
        newlyUploadedPictures=newlyUploadedPictures+id;draftPreviews=draftPreviews+(id to preview);draftPictures=draftPictures+id
    }
    private fun clearTransactionDraft(){
        clearCopyCache();copiedTransaction=null;restoredTransaction=null;newlyUploadedPictures=emptySet();draftPictures=emptyList();draftPreviews=emptyMap();draftAttachmentProblem=false
    }
    private fun discardNewPictures(ids:Set<String>){
        if(ids.isEmpty() || token==null)return
        act { for(id in ids)try{api.removeUnusedPicture(id)}catch(e:ApiFailure){if(e.sessionExpired)throw e;notice="未用附件清理未完成，可稍后重试"} }
    }
    fun copyTransaction(id:String)=act {
        require(pendingCheckReady){"请先刷新账本，核对未完成的保存"}
        transactionSubmission.requireEditable()
        val source=api.transaction(id)
        val copied=transactionCopyDraft(source,java.time.Instant.now().epochSecond)
        val items=source.array("pictures");require(items.size<=10) { "每笔最多10张图片" }
        val cache=File(getApplication<Application>().cacheDir,"transaction-copy-"+UUID.randomUUID()).apply{check(mkdir())}
        val pending=linkedMapOf<String,LocalCopyPicture>();val previews=linkedMapOf<String,ByteArray>()
        try {
            for((index,picture) in items.withIndex()){
                val extension=Uri.parse(picture.getString("originalUrl")).path?.substringAfterLast('.')?.lowercase()
                val mime=when(extension){"jpg","jpeg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";else->throw IllegalArgumentException("无法复制此附件格式")}
                val pictureId=picture.id("pictureId");require(Regex("[1-9][0-9]*").matches(pictureId))
                val bytes=api.picture("/api/v1/family/pictures/$pictureId.$extension")
                require(bytes.size<=10*1024*1024) { "复制图片不能超过10MB" }
                val thumbnail=withContext(Dispatchers.IO){draftThumbnail(bytes)}
                val file=File(cache,"$index.$extension")
                pending["copy-local-$index"]=LocalCopyPicture(file,mime)
                withContext(Dispatchers.IO){file.outputStream().use{it.write(bytes)}}
                previews["copy-local-$index"]=thumbnail
            }
        }catch(e:Exception){pending.values.forEach{it.file.delete()};cache.delete();throw e}
        clearTransactionDraft();newTransactionDay=null;editTransaction=null;copiedTransaction=copied
        localCopyPictures=pending;copyCacheDirectory=cache;draftPictures=pending.keys.toList();draftPreviews=previews;showEditor=true
    }
    private suspend fun uploadLocalCopyPictures(){
        for(key in draftPictures.toList()){
            val local=localCopyPictures[key]?:continue
            val bytes=withContext(Dispatchers.IO){require(local.file.length() in 1..(10L*1024*1024));local.file.readBytes()}
            val result=api.upload(bytes,local.mime);val uploaded=result.id("pictureId")
            require(Regex("[1-9][0-9]*").matches(uploaded)) { "服务器未返回有效图片" }
            newlyUploadedPictures=newlyUploadedPictures+uploaded
            draftPictures=draftPictures.map{if(it==key)uploaded else it}
            draftPreviews[key]?.let{draftPreviews=(draftPreviews-key)+(uploaded to it)}
            localCopyPictures=localCopyPictures-key;local.file.delete()
        }
        if(localCopyPictures.isEmpty()){copyCacheDirectory?.delete();copyCacheDirectory=null}
    }
    fun deleteTransaction(id:String,done:()->Unit={})=act {
        var alreadyAbsent=false
        val refreshed=commitThenRefresh({alreadyAbsent=deleteTransactionOrAlreadyAbsent(api,id)},{done();notice=if(alreadyAbsent)"这笔记录已不存在" else "已删除"},{loadAll()})
        if(!refreshed)notice=if(alreadyAbsent)"这笔记录已不存在，数据刷新失败，可重试刷新" else "已删除，数据刷新失败，可重试刷新"
    }
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
        pendingCheckReady=false;token = value
        result.optJSONObject("user")?.let { user ->
            nickname = user.optString("nickname").ifBlank { "家人" }
            defaultAccountId = user.optString("defaultAccountId")
        }
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
        data.optJSONObject("user")?.let { user ->
            nickname = user.optString("nickname", "家人")
            defaultAccountId = user.optString("defaultAccountId", defaultAccountId)
        }
        refreshAt = System.currentTimeMillis()
    }
    private suspend fun loadAll() {
        if(entityWrites.blocked)entityWrites.invalidateIdentity()
        refreshSession()
        accounts = api.accounts()
        try {
            api.userProfile().let { profile ->
                defaultAccountId = profile.optString("defaultAccountId", defaultAccountId)
                nickname = profile.optString("nickname", nickname)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The account list remains usable when an optional profile refresh fails.
        }
        loadCategories(); tags = api.tags(); tagGroups = api.tagGroups()
        externalLinked = api.externalAuthLinked()
        page = 1; val listed=api.ledgerPage(page,ledgerMonth,ledgerDay);transactions=listed.items;hasMoreTransactions=listed.hasMore;dailyAmounts=api.dailyAmounts(ledgerMonth)
        val today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));weeklyAmounts=api.dailyRange(BooksApi.dayBounds(today.minusDays(6).toString()).first,BooksApi.dayBounds(today.toString()).second)
        family = api.family(month)
        val result = api.familyTransactions(month); familyItems = result.array("items"); familyCursor = result.optString("nextCursor")
        if(accounts.isNotEmpty() && !showEditor && !transactionNeedsConfirmation){
            transactionSubmission.restore()?.let{verified->restoredTransaction=verified;draftPictures=verified.optJSONArray("pictureIds")?.let{ids->(0 until ids.length()).map{ids.getString(it)}}.orEmpty();showEditor=true}
        }
        pendingCheckReady=accounts.isNotEmpty()
        if(entityWrites.blocked)entityWrites.refresh()
    }
    fun dismissFeedback(value:String){if(error==value)error="";if(notice==value)notice=""}
    fun reload() = act { loadAll() }
    fun resume() { if (token != null && !busy) act { refreshSession(); loadCategories() } }
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
        require(pendingCheckReady){"请先刷新账本，核对未完成的保存"}
        transactionSubmission.requireEditable()
        val data=api.transaction(id)
        if(data.optInt("type")==1) { notice="余额调整记录请通过账户或网页核对，不能按普通收支编辑"; return@act }
        clearTransactionDraft();newTransactionDay=null;editTransaction=data;draftPictures=data.array("pictures").map { it.id("pictureId") };draftAttachmentProblem=false;showEditor=true
    }
    fun newTransaction(day:String?=null) { if(!pendingCheckReady || transactionNeedsConfirmation){error="请先刷新账本，核对未完成的保存";return};if(day!=null)java.time.LocalDate.parse(day);newTransactionDay=day;clearTransactionDraft();editTransaction=null;showEditor=true }
    fun closeTransaction(){if(transactionNeedsConfirmation){error="请先确认刚才那笔保存的结果";return};val unused=newlyUploadedPictures;showEditor=false;editTransaction=null;clearTransactionDraft();discardNewPictures(unused)}
    fun removeDraftPicture(id:String){
        transactionSubmission.requireEditable()
        localCopyPictures[id]?.file?.delete();localCopyPictures=localCopyPictures-id
        val unused=if(id in newlyUploadedPictures)setOf(id) else emptySet()
        newlyUploadedPictures=newlyUploadedPictures-id;draftPictures=draftPictures-id;draftPreviews=draftPreviews-id
        discardNewPictures(unused)
    }
    private suspend fun finishTransactionSave(keepOpen:Boolean,onSaved:()->Unit){
        showEditor=keepOpen;editTransaction=null;val copied=copiedTransaction;clearTransactionDraft();if(keepOpen)copiedTransaction=copied
        notice="已保存";onSaved()
        try{loadAll()}catch(error:CancellationException){throw error}catch(error:Exception){if(error is ApiFailure && error.sessionExpired)throw error;notice="已保存，数据刷新失败，可重试刷新"}
    }
    fun saveTransaction(payload: JSONObject, existing: Boolean, keepOpen:Boolean=false, onSaved:()->Unit={}) = act {
        transactionSubmission.requireEditable()
        transactionSubmission.prepare(payload)
        uploadLocalCopyPictures()
        payload.put("pictureIds",JSONArray(draftPictures))
        val acknowledged=try { transactionSubmission.submit("/api/v1/transactions/${if(existing)"modify" else "add"}.json",payload,keepOpen) } catch(error:ApiFailure) { categoryConflict(error) }
        finishTransactionSave(acknowledged,onSaved)
    }

    fun preferredAccountId(): String = choosePreferredAccountId(accounts, defaultAccountId)

    fun setDefaultAccount(accountId: String) = act {
        val result = entityWrites.submit("/api/v1/users/profile/update.json", JSONObject().put("defaultAccountId", accountId)) as JSONObject
        defaultAccountId = result.optJSONObject("user")?.optString("defaultAccountId", accountId) ?: accountId
        refreshAfterAccountWrite("默认账户已更新")
    }

    fun hideAccount(accountId: String, hidden: Boolean) = act {
        require(entityWrites.submit("/api/v1/accounts/hide.json", JSONObject().put("id", accountId).put("hidden", hidden)) == true) { "账户状态更新未完成，请刷新后重试" }
        refreshAfterAccountWrite(if (hidden) "账户已归档" else "账户已恢复")
    }

    fun moveAccounts(order: List<Pair<String, Int>>) = act {
        require(order.isNotEmpty()) { "账户顺序不能为空" }
        require(entityWrites.submit("/api/v1/accounts/move.json", accountOrderPayload(order)) == true) { "账户顺序更新未完成，请刷新后重试" }
        refreshAfterAccountWrite("账户顺序已更新")
    }

    fun moveAccount(accountId: String, direction: Int) = act {
        val selected = accounts.firstOrNull { it.id() == accountId } ?: error("账户不存在")
        val category = selected.optInt("category", -1)
        val parentId = selected.id("parentId")
        require(!selected.optBoolean("hidden")) { "归档账户不能调整顺序" }
        val group = accounts.filter { it.optInt("category", -1) == category && it.id("parentId") == parentId }
            .sortedBy { it.optInt("displayOrder", Int.MAX_VALUE) }.toMutableList()
        val ordered = group.filter { !it.optBoolean("hidden") }.toMutableList()
        val index = ordered.indexOfFirst { it.id() == accountId }
        val target = index + direction
        require(index >= 0 && target in ordered.indices) { "账户已经在该方向的边界" }
        val accountToMove = ordered.removeAt(index); ordered.add(target, accountToMove)
        val visibleIterator = ordered.iterator()
        val newOrder = group.mapIndexed { position, account ->
            (if (account.optBoolean("hidden")) account else visibleIterator.next()).id() to position + 1
        }
        require(entityWrites.submit("/api/v1/accounts/move.json", accountOrderPayload(newOrder)) == true) { "账户顺序更新未完成，请刷新后重试" }
        refreshAfterAccountWrite("账户顺序已更新")
    }

    private fun accountOrderPayload(order: List<Pair<String, Int>>): JSONObject {
        val rows = JSONArray()
        order.forEach { (id, displayOrder) -> rows.put(JSONObject().put("id", id).put("displayOrder", displayOrder)) }
        return JSONObject().put("newDisplayOrders", rows)
    }

    private suspend fun refreshAfterAccountWrite(successNotice: String) {
        try {
            loadAll()
            notice = successNotice
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is ApiFailure && error.sessionExpired) throw error
            notice = "$successNotice，数据刷新失败，可重试刷新"
        }
    }

    fun deleteOrArchiveAccount(accountId: String, onResult: (String) -> Unit = {}) = act {
        if (entityWrites.blocked) {
            entityReviewOpen = true
            throw IllegalArgumentException("请先核对上一笔资料保存；不会自动重发")
        }
        val account = accounts.firstOrNull { it.id() == accountId }
        val subAccount = account?.id("parentId")?.let { it != "0" } == true
        val path = if (subAccount) "/api/v1/accounts/sub_account/delete.json" else "/api/v1/accounts/delete.json"
        val result = entityWrites.submit(path, JSONObject().put("id", accountId))
        val action = accountDeleteAction(result)
        var refreshFailed = false
        try {
            loadAll()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is ApiFailure && error.sessionExpired) throw error
            refreshFailed = true
        }
        notice = if (action == "archived") {
            if (refreshFailed) "账户已归档，数据刷新失败，可重试刷新" else "账户已归档"
        } else {
            if (refreshFailed) "账户已删除，数据刷新失败，可重试刷新" else "账户已删除"
        }
        onResult(action)
    }
    fun refreshTransactionConfirmation()=act {
        transactionSubmission.restore()?.let{verified->restoredTransaction=verified;draftPictures=verified.optJSONArray("pictureIds")?.let{ids->(0 until ids.length()).map{ids.getString(it)}}.orEmpty();showEditor=true}
    }
    fun confirmTransactionSave(onSaved:()->Unit={})=act {
        val keepOpen=try { transactionSubmission.retry() } catch(error:ApiFailure) { categoryConflict(error) }
        finishTransactionSave(keepOpen,onSaved)
    }
    fun saveEntity(path: String, payload: JSONObject, done: () -> Unit) {
        // Reject synchronously before queueing behind an ACK that could otherwise unlock a second write.
        if(busy){error="正在处理请求，请勿重复保存";return}
        if(entityWrites.blocked){entityReviewOpen=true;error="请先核对上一笔资料保存；不会自动重发";return}
        act{
            try{
                val details=JSONObject()
                if(path.contains("/categories/"))details.put("parentName",if(payload.id("parentId")=="0")"作为大类" else categories.firstOrNull{it.id()==payload.id("parentId")}?.optString("name")?:"原大类当前不可见")
                if(path.contains("/tags/")&&!path.contains("/tags/groups/"))details.put("groupName",if(payload.id("groupId")=="0")"不分组" else tagGroups.firstOrNull{it.id()==payload.id("groupId")}?.optString("name")?:"原标签组当前不可见")
                entityWrites.submit(path,payload,details)
                done();notice="已保存"
                try{loadAll()}catch(error:CancellationException){throw error}catch(error:Exception){if(error is ApiFailure&&error.sessionExpired)throw error;notice="已保存，数据刷新失败，可重试刷新"}
            }catch(error:CancellationException){throw error}catch(error:Exception){
                if(error is ApiFailure&&error.sessionExpired)throw error
                if(entityWrites.blocked)throw IllegalArgumentException("资料保存结果待核实；客户端不会重发，请先核对实际列表")
                throw error
            }finally{if(entityWrites.blocked)entityReviewOpen=true}
        }
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
        pendingCheckReady=false;categoryReviewRequired=false;categoryReviewReady=false
        entityWrites.detachForLogout();entityReviewOpen=false
        transactionSubmission.detachForLogout()
        clearTransactionDraft()
        store.clear(); token = null; refreshAt = 0; defaultAccountId = ""; accounts = emptyList(); categories = emptyList(); categoryRevision = null; tags = emptyList()
        tagGroups = emptyList(); transactions = emptyList(); familyItems = emptyList(); family = JSONObject(); sessions = emptyList()
        hasMoreTransactions=false;dailyAmounts=emptyList();weeklyAmounts=emptyList();ledgerDay=null;newTransactionDay=null;editTransaction = null; showEditor = false; oidcCallbackToken = null; twoFactorToken = null;draftPictures=emptyList();draftPreviews=emptyMap();draftAttachmentProblem=false
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

internal fun choosePreferredAccountId(accounts: List<JSONObject>, defaultId: String): String {
    val visible = accounts.filter { !it.optBoolean("hidden") }.sortedBy { it.optInt("displayOrder", Int.MAX_VALUE) }
    return visible.firstOrNull { it.id() == defaultId }?.id()
        ?: visible.firstOrNull()?.id().orEmpty()
}

internal fun accountDeleteAction(result: Any): String {
    if (result == true) return "deleted"
    if (result is JSONObject) {
        val action = result.optString("action")
        require(action == "deleted" || action == "archived") { "服务端返回了未知的账户处理结果" }
        return action
    }
    throw IllegalArgumentException("服务端未确认账户处理结果")
}
