package top.fjzhang233.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.UUID

/** Backend 0.1.4 TokenInfoResponse: only the protected unique ordinary current record is accepted. */
internal fun entityAuthenticatedUid(tokens:List<JSONObject>,own:List<JSONObject>,family:List<JSONObject>):String {
    val current=tokens.filter{it.opt("isCurrent")==true}
    require(current.size==1 && current.single().opt("tokenType") is Number && current.single().opt("tokenType").toString()=="1"){"无法安全核对当前账号"}
    val publicRecord=current.single().opt("tokenId");require(publicRecord is String){"服务端会话格式无法安全核对，请升级客户端"}
    val fields=Regex("([1-9][0-9]*):([1-9][0-9]*):([1-9][0-9]*)").matchEntire(publicRecord)?.groupValues?.drop(1)
    require(fields!=null && fields.all{it.toLongOrNull()?.let{value->value>0}==true}){"服务端会话格式无法安全核对，请升级客户端"}
    val uid=fields[0]
    if(own.isNotEmpty())require(submissionIdentity(own,family).uid==uid){"当前账号核对结果不一致"}
    return uid
}

internal data class EntityPendingView(val path:String,val body:JSONObject,val details:JSONObject=JSONObject())

/** Never replays an entity mutation: these upstream endpoints do not offer durable idempotency. */
internal class EntityWriteState(
    private val origin:String,
    private val storage:TransactionRequestStorage,
    private val identify:suspend ()->String,
    private val sendOnce:suspend (String,JSONObject)->Any
){
    private data class Pending(val uid:String,val path:String,val body:String,val intentId:String,val details:String)
    private var pending:Pending?=null
    private var verifiedUid by mutableStateOf<String?>(null)
    var blocked by mutableStateOf(false);private set
    var inFlight by mutableStateOf(false);private set
    init { try{readPending()}catch(_:Exception){blocked=true} }
    private fun encode(value:Pending)=JSONObject().put("schema",1).put("origin",origin).put("uid",value.uid).put("path",value.path).put("body",value.body).put("intentId",value.intentId).put("details",value.details).toString()
    private fun decode(raw:String):Pending {
        require(raw.toByteArray().size<=96*1024){"本地待核实资料无法读取"}
        val value=JSONObject(raw)
        require(value.getInt("schema")==1 && value.getString("origin")==origin){"本地待核实资料来源不匹配"}
        val result=Pending(value.getString("uid"),value.getString("path"),value.getString("body"),value.getString("intentId"),value.optString("details","{}"))
        require(Regex("[1-9][0-9]*").matches(result.uid) && result.uid.toLongOrNull()!=null && isEntityWritePath(result.path) && result.intentId.isNotBlank()){"本地待核实资料无法安全核对"}
        JSONObject(result.body);JSONObject(result.details)
        return result
    }
    private fun readPending():Pending? {
        // Any read/decrypt/decode error keeps the lock; it must not look like an empty store.
        val raw=storage.read()
        if(raw==null){require(pending==null){"本地待核实资料状态已改变"};blocked=false;return null}
        blocked=true
        val restored=decode(raw);pending=restored;return restored
    }
    fun invalidateIdentity(){verifiedUid=null}
    fun detachForLogout(){invalidateIdentity()} // Origin-level encrypted marker still blocks future writes.
    fun view():EntityPendingView? {
        val value=pending?:return null
        if(!blocked || verifiedUid!=value.uid)return null
        return EntityPendingView(value.path,JSONObject(value.body),JSONObject(value.details))
    }
    fun requireNewWrite(){require(!blocked&&!inFlight){"有一笔资料保存结果待核实，不能重新提交或新建"}}
    suspend fun refresh():EntityPendingView? {
        require(!inFlight){"资料正在保存"};verifiedUid=null
        val value=readPending()?:return null
        val uid=identify();require(uid==value.uid){"此设备有其他账号的资料保存待核实，请用原账号核对"}
        verifiedUid=uid;return view()
    }
    suspend fun submit(path:String,body:JSONObject,details:JSONObject=JSONObject()):Any{
        requireNewWrite();inFlight=true
        try{
            if(readPending()!=null){verifiedUid=null;throw IllegalArgumentException("请先核对上一笔资料保存；不会自动重发")}
            require(isEntityWritePath(path)){"不支持的资料保存路径"}
            val frozen=body.toString();val frozenDetails=details.toString();require(frozen.toByteArray().size<=64*1024&&frozenDetails.toByteArray().size<=16*1024){"资料内容过大"}
            val uid=identify();require(Regex("[1-9][0-9]*").matches(uid)&&uid.toLongOrNull()!=null){"无法安全核对当前账号"}
            val value=Pending(uid,path,frozen,UUID.randomUUID().toString(),frozenDetails)
            pending=value;verifiedUid=uid;blocked=true
            // A crash after this point is unknown, even if no HTTP bytes had been sent yet.
            storage.write(encode(value))
            val result=try { sendOnce(path,JSONObject(frozen)) } catch (error:ApiFailure) {
                // This exact response guarantees the category mutation was rejected before writing.
                // Network failures and all other responses retain the pending intent.
                if ((path.contains("/categories/") && error.httpStatus==409 && error.code==206013) ||
                    (path=="/api/v1/transaction/categories/delete.json" && error.httpStatus==400 && error.code==206006)) {
                    storage.clear();pending=null;verifiedUid=null;blocked=false
                }
                throw error
            }
            val accountRemovalResult = path.endsWith("/accounts/delete.json") || path.endsWith("/accounts/sub_account/delete.json")
            val objectResultIsValid = result is JSONObject && Regex("[1-9][0-9]*").matches(result.id()) && (!accountRemovalResult || result.optString("action") in setOf("deleted", "archived"))
            require(result==true || objectResultIsValid){"服务响应未能确认，需先核对实际结果"}
            storage.clear();pending=null;verifiedUid=null;blocked=false
            return result
        }finally{inFlight=false}
    }
    suspend fun abandonAfterExplicitReview(){
        require(!inFlight){"资料正在保存"};verifiedUid=null
        val value=readPending()?:return
        val uid=identify();require(uid==value.uid){"仅原账号可结束这笔待核实意图"}
        // Never claims success and never calls the mutation again. This does not undo server changes.
        storage.clear();pending=null;blocked=false
    }
}

internal fun isEntityWritePath(path:String):Boolean = path in setOf(
    "/api/v1/accounts/add.json","/api/v1/accounts/modify.json","/api/v1/accounts/delete.json","/api/v1/accounts/sub_account/delete.json",
    "/api/v1/transaction/categories/add.json","/api/v1/transaction/categories/modify.json",
    "/api/v1/transaction/categories/hide.json","/api/v1/transaction/categories/delete.json",
    "/api/v1/transaction/tags/add.json","/api/v1/transaction/tags/modify.json",
    "/api/v1/transaction/tags/groups/add.json","/api/v1/transaction/tags/groups/modify.json"
)
