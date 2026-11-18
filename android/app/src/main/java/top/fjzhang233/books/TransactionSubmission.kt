package top.fjzhang233.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

internal data class SubmissionAccount(val name:String,val currency:String)
internal data class SubmissionIdentity(val uid:String,val accountIds:Set<String>,val accounts:Map<String,SubmissionAccount> = emptyMap())
internal data class AcknowledgedTransaction(val id:String,val row:JSONObject,val keepOpen:Boolean)
internal interface TransactionRequestStorage {fun read():String?;fun write(value:String);fun clear()}
internal interface PendingTransactionIndex {
    fun hasAny():Boolean
    fun contains(uid:String):Boolean
    fun add(uid:String)
    fun remove(uid:String)
}

/** Current-user accounts are joined by global ID only; disabled members are excluded by the server. */
internal fun submissionIdentity(own:List<JSONObject>,family:List<JSONObject>):SubmissionIdentity {
    val ids=own.map{it.id()};require(ids.isNotEmpty() && ids.distinct().size==ids.size && ids.all{Regex("[1-9][0-9]*").matches(it)}){"无法安全核对当前账号"}
    val owners=ids.map{id->val matches=family.filter{it.id()==id};require(matches.size==1){"无法安全核对当前账号"};matches.single().id("ownerId")}
    require(owners.distinct().size==1 && Regex("[1-9][0-9]*").matches(owners.distinct().single())){"无法安全核对当前账号"}
    return SubmissionIdentity(owners.distinct().single(),ids.toSet(),own.associate{it.id() to SubmissionAccount(it.optString("name"),it.optString("currency"))})
}

/** A write is persisted before sending, bound to origin + immutable UID, and replayed unchanged only. */
internal class TransactionSubmission(
    private val origin:String,
    private val identify:suspend ()->SubmissionIdentity,
    private val storage:(String)->TransactionRequestStorage,
    private val send:suspend (String,JSONObject)->Any,
    private val pendingIndex:PendingTransactionIndex? = null,
    private val onTiming:(String,Long)->Unit = {_,_->}
){
    private data class Request(val uid:String,val path:String,val json:String,val keepOpen:Boolean)
    private var request:Request?=null
    private var activeIdentity by mutableStateOf<SubmissionIdentity?>(null)
    private var preparedIdentity:SubmissionIdentity?=null
    private var preparedStore:TransactionRequestStorage?=null
    private var acknowledgedTransaction:AcknowledgedTransaction?=null
    var needsConfirmation by mutableStateOf(false);private set
    fun requireEditable(){require(!needsConfirmation){"请先确认刚才那笔保存的结果"}}
    fun detachForLogout(){request=null;activeIdentity=null;preparedIdentity=null;preparedStore=null;needsConfirmation=false} // Encrypted per-UID request is retained.
    fun takeAcknowledgedTransaction():AcknowledgedTransaction?=acknowledgedTransaction.also{acknowledgedTransaction=null}
    fun hasPendingCandidate():Boolean = pendingIndex?.hasAny() ?: true
    private fun verify(request:Request,identity:SubmissionIdentity){
        require(request.uid==identity.uid){"当前账号不能确认这笔保存"}
        val body=JSONObject(request.json)
        require(body.id("sourceAccountId") in identity.accountIds){"无法安全核对原账户"}
        if(body.optInt("type")==4)require(body.id("destinationAccountId") in identity.accountIds){"无法安全核对原转入账户"}
        require(request.path in setOf("/api/v1/transactions/add.json","/api/v1/transactions/modify.json"))
        if(request.path.endsWith("add.json"))require(body.optString("clientSessionId").isNotBlank())
    }
    private fun encoded(request:Request)=JSONObject().put("schema",1).put("origin",origin).put("uid",request.uid).put("path",request.path).put("body",request.json).put("keepOpen",request.keepOpen).toString()
    private fun decoded(value:String):Request {
        val obj=JSONObject(value);require(obj.getInt("schema")==1 && obj.getString("origin")==origin){"无法安全核对保存来源"}
        return Request(obj.getString("uid"),obj.getString("path"),obj.getString("body"),obj.getBoolean("keepOpen"))
    }
    fun confirmationView():JSONObject? {
        val captured=request?:return null;val identity=activeIdentity?:return null
        if(!needsConfirmation || identity.uid!=captured.uid)return null
        val body=JSONObject(captured.json)
        val source=identity.accounts[body.id("sourceAccountId")]?:return null
        if(source.name.isBlank() || !Regex("[A-Z]{3}").matches(source.currency))return null
        body.put("sourceAccount",JSONObject().put("name",source.name).put("currency",source.currency))
        if(body.optInt("type")==4){
            val destination=identity.accounts[body.id("destinationAccountId")]?:return null
            if(destination.name.isBlank() || !Regex("[A-Z]{3}").matches(destination.currency))return null
            body.put("destinationAccount",JSONObject().put("name",destination.name).put("currency",destination.currency))
        }
        return body
    }
    suspend fun restore():JSONObject? {
        // Hide stale identity immediately, but retain a known pending lock across every failed recheck.
        activeIdentity=null
        if(pendingIndex!=null&&!pendingIndex.hasAny()){detachForLogout();return null}
        val identity=identify();val store=storage(identity.uid);val value=store.read()
        if(pendingIndex!=null&&!pendingIndex.contains(identity.uid)){detachForLogout();return null}
        if(value==null){pendingIndex?.remove(identity.uid);detachForLogout();return null} // Only fresh UID + checked storage can release the old lock.
        val restored=decoded(value);verify(restored,identity)
        request=restored;activeIdentity=identity;needsConfirmation=true
        return JSONObject(restored.json)
    }
    suspend fun prepare(payload:JSONObject){
        val started=System.nanoTime()
        try{
        requireEditable();val identity=identify();val store=storage(identity.uid)
        val hasPending=pendingIndex?.hasAny()
        val stored=if(hasPending==false)null else store.read()
        if(pendingIndex?.contains(identity.uid)==true){
            stored?.let{value->val restored=decoded(value);verify(restored,identity);request=restored;activeIdentity=identity;needsConfirmation=true;throw IllegalArgumentException("请先确认刚才那笔保存的结果")}
            pendingIndex.remove(identity.uid)
        }
        if(hasPending!=false)stored?.let{value->val restored=decoded(value);verify(restored,identity);request=restored;activeIdentity=identity;needsConfirmation=true;throw IllegalArgumentException("请先确认刚才那笔保存的结果")}
        preparedIdentity=identity
        preparedStore=store
        verify(Request(identity.uid,if(payload.has("id"))"/api/v1/transactions/modify.json" else "/api/v1/transactions/add.json",payload.toString(),false),identity)
        }finally{onTiming("prepare",(System.nanoTime()-started)/1_000_000)}
    }
    private suspend fun post(captured:Request):JSONObject{
        val result=send(captured.path,JSONObject(captured.json))
        if(result !is JSONObject || !Regex("[1-9][0-9]*").matches(result.id()))throw ApiFailure(200,"服务响应异常，保存结果仍需确认")
        return result
    }
    suspend fun submit(path:String,payload:JSONObject,keepOpen:Boolean):Boolean {
        val started=System.nanoTime()
        try{
            requireEditable();val identity=preparedIdentity?:identify();preparedIdentity=null;val prepared=preparedStore;val store=prepared?:storage(identity.uid);preparedStore=null
            if(prepared==null)require(pendingIndex?.hasAny()!=true && store.read()==null){"请先确认刚才那笔保存的结果"}
            val captured=Request(identity.uid,path,payload.toString(),keepOpen);verify(captured,identity)
            val durableStarted=System.nanoTime();pendingIndex?.add(identity.uid)
            try { store.write(encoded(captured)) } catch(error:Exception) { pendingIndex?.remove(identity.uid);throw error } // Checked durable encryption completes before any HTTP mutation.
            finally{onTiming("durable_write",(System.nanoTime()-durableStarted)/1_000_000)}
            request=captured;activeIdentity=identity;needsConfirmation=true
            val result=try{post(captured)}catch(error:Exception){
                // Only the version conflict is guaranteed to precede mutation. A 500,
                // including attachment-token generation failure, may follow a commit.
                if(error is ApiFailure && error.code==206013 && error.httpStatus==409){store.clear();pendingIndex?.remove(identity.uid);detachForLogout()}
                throw error
            }
            acknowledgedTransaction=AcknowledgedTransaction(result.id(),JSONObject(result.toString()),captured.keepOpen)
            val cleanupStarted=System.nanoTime();store.clear();pendingIndex?.remove(identity.uid);onTiming("cleanup",(System.nanoTime()-cleanupStarted)/1_000_000);detachForLogout();return captured.keepOpen
        }finally{onTiming("submit",(System.nanoTime()-started)/1_000_000)}
    }
    suspend fun retry():Boolean {
        val started=System.nanoTime()
        try{
            activeIdentity=null
            val identity=identify();val store=storage(identity.uid)
            require(pendingIndex?.contains(identity.uid) != false){"没有待确认的保存"}
            val captured=decoded(checkNotNull(store.read()){"没有待确认的保存"});verify(captured,identity)
            require(request==null || request==captured){"待确认内容发生变化"}
            request=captured;activeIdentity=identity;needsConfirmation=true
            val result=try { post(captured) } catch(error:ApiFailure) {
                // Server confirms this request did not commit; ambiguous failures stay durable.
                if(error.code==206013 && error.httpStatus==409) { store.clear();pendingIndex?.remove(identity.uid);detachForLogout() }
                throw error
            }
            acknowledgedTransaction=AcknowledgedTransaction(result.id(),JSONObject(result.toString()),captured.keepOpen)
            store.clear();pendingIndex?.remove(identity.uid);detachForLogout();return captured.keepOpen
        }finally{onTiming("retry",(System.nanoTime()-started)/1_000_000)}
    }
}

internal suspend fun deleteTransactionOrAlreadyAbsent(api:BooksApi,id:String):Boolean {
    val current=try{api.transaction(id)}catch(error:ApiFailure){if(error.code==205001 && !error.sessionExpired)return true else throw error}
    require(current.optBoolean("editable",false)){"此记录当前不可删除"}
    return try{api.deleteTransaction(current.id());false}catch(error:ApiFailure){if(error.code==205001 && !error.sessionExpired)true else throw error}
}
