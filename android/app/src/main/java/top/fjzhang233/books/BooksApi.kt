package top.fjzhang233.books

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LedgerPage(val items:List<JSONObject>,val hasMore:Boolean)
class ApiFailure(val code: Int, message: String, val sessionExpired: Boolean = false, val httpStatus:Int?=null) : IOException(message)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.array(key: String): List<JSONObject> = optJSONArray(key)?.objects().orEmpty()
fun JSONObject.id(key: String = "id"): String = optString(key, "0")
fun money(minor: String): String = runCatching { BigDecimal(minor).movePointLeft(2).setScale(2).toPlainString() }.getOrDefault("—")
fun parseMinor(input: String): Long {
    require(Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(input.trim())) { "金额最多两位小数" }
    return BigDecimal(input.trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact().also { require(it > 0) { "金额必须大于零" } }
}

class BooksApi(private val base: String, private val onTiming:(String,Long)->Unit={_,_->}, private val tokenProvider: () -> String?) {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val entityClient=client.newBuilder().retryOnConnectionFailure(false).build()
    init {
        val origin=base.toHttpUrl()
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.encodedPath=="/" && origin.query==null && origin.fragment==null)
        require((origin.isHttps && origin.host=="books.fjzhang233.top" && origin.port==443) || (BuildConfig.DEBUG && origin.scheme=="http" && origin.host in setOf("10.0.2.2","127.0.0.1")))
    }
    suspend fun call(path: String, payload: JSONObject? = null, auth: Boolean = true, overrideToken: String? = null): Any = requestCancellable(path, payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()), auth, overrideToken)
    internal suspend fun writeEntityOnce(path:String,payload:JSONObject):Any {
        require(isEntityWritePath(path)){"不支持的资料保存路径"}
        return requestCancellable(path,payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),true,null,entityClient)
    }
    private fun buildRequest(path:String,body:RequestBody?,auth:Boolean,overrideToken:String?):Request {
        require(path.startsWith("/api/"))
        val req = Request.Builder().url(base.trimEnd('/') + path).header("X-Timezone-Name", "Asia/Shanghai")
            .header("X-Timezone-Offset", "480").header("User-Agent", "FamilyBooks/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE.orEmpty().filter { it in ' '..'~' && it != '(' && it != ')' }.take(40).ifEmpty { '?'.toString() }})")
        if (auth) (overrideToken ?: tokenProvider())?.let { req.header("Authorization", "Bearer $it") }
        if (body != null) req.post(body)
        return req.build()
    }
    private fun parseResponse(path:String,response:okhttp3.Response,auth:Boolean,overrideToken:String?):Any {
            if (response.code == 404 && path.contains("native")) throw ApiFailure(404, "服务端尚未支持原生 Pocket ID 登录，请先使用本地密码或升级服务端")
            val obj = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { throw ApiFailure(response.code, "服务响应异常，请稍后重试",httpStatus=response.code) }
            if (!response.isSuccessful || !obj.optBoolean("success")) {
                val code = obj.optInt("errorCode", response.code)
                val expired = response.code == 401 && auth && overrideToken == null && tokenProvider() != null
                val message = if (expired) "登录已过期或被撤销，请重新登录" else when (code) {
                    206006 -> "该分类仍被交易或模板使用，不能删除；可选择隐藏"
                    206013 -> "分类已在其他设备变更，请刷新并重新评审后再保存"
                    206012 -> "分类管理需要升级客户端"
                    204013, 204019 -> "账户仍有历史引用，不能永久删除；可选择归档"
                    217001 -> "尚未关联此 Pocket ID，请先用本地密码登录后关联"
                    216001 -> "已有其他关联，请在网页安全设置中核对"
                    201001, 201002, 201003 -> "用户名或密码不正确"
                    201011 -> "本地密码不正确，请重新输入"
                    else -> if (response.code == 401) "认证未通过，请核对密码或二步验证码" else "操作未完成（$code），请检查输入或稍后重试"
                }
                throw ApiFailure(code, message, expired,response.code)
            }
            return obj.opt("result") ?: JSONObject.NULL
    }
    private suspend fun requestCancellable(path:String,body:RequestBody?,auth:Boolean,overrideToken:String?=null,callClient:OkHttpClient=client):Any=suspendCancellableCoroutine{continuation->
        val started=SystemClock.elapsedRealtime();val label=path.substringBefore('?').removePrefix("/").replace('/','.')
        val call=callClient.newCall(buildRequest(path,body,auth,overrideToken))
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:okhttp3.Callback{
            override fun onFailure(call:okhttp3.Call,error:IOException){onTiming(label,SystemClock.elapsedRealtime()-started);if(continuation.isActive)continuation.resumeWithException(error)}
            override fun onResponse(call:okhttp3.Call,response:okhttp3.Response){
                try{response.use{result->val parsed=parseResponse(path,result,auth,overrideToken);if(continuation.isActive)continuation.resume(parsed)}}catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}finally{onTiming(label,SystemClock.elapsedRealtime()-started)}
            }
        })
    }
    private fun request(path: String, body: RequestBody?, auth: Boolean, overrideToken: String? = null, callClient:OkHttpClient=client): Any {
        callClient.newCall(buildRequest(path,body,auth,overrideToken)).execute().use { response ->
            return parseResponse(path,response,auth,overrideToken)
        }
    }
    suspend fun upload(bytes: ByteArray, mime: String): JSONObject {
        require(bytes.size <= 10 * 1024 * 1024) { "图片不能超过 10 MB" }
        require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "请选择 JPG、PNG 或 WebP 图片" }
        val ext = when(mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("picture", "attachment.$ext", bytes.toRequestBody(mime.toMediaType())).build()
        return requestCancellable("/api/v1/transaction/pictures/upload.json", multipart, true) as JSONObject
    }
    suspend fun picture(path: String): ByteArray = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/family/pictures/"))
        val req = Request.Builder().url(base.trimEnd('/') + path).header("Authorization", "Bearer ${tokenProvider().orEmpty()}").build()
        client.newCall(req).execute().use { response ->
            if (response.code == 401) throw ApiFailure(401,"登录已过期或被撤销，请重新登录",true)
            if (!response.isSuccessful) throw ApiFailure(response.code, "图片加载失败")
            val body = response.body ?: throw IOException("图片为空")
            val max = 12 * 1024 * 1024; require(body.contentLength() <= max)
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { stream -> val buffer = ByteArray(8192); while(true) { val n = stream.read(buffer); if(n < 0) break; require(out.size() + n <= max); out.write(buffer, 0, n) } }
            out.toByteArray()
        }
    }
    suspend fun login(user: String, password: String, passcode: String): JSONObject = call("/api/authorize.json", JSONObject().put("loginName", user).put("password", password).put("passcode", passcode), false) as JSONObject
    suspend fun refresh(): JSONObject = call("/api/v1/tokens/refresh.json", JSONObject()) as JSONObject
    internal suspend fun externalAuthLinked():Boolean = try {
        (call("/api/v1/users/external_auth/list.json") as JSONArray).objects().any{it.optString("externalAuthType")=="oidc"&&it.optBoolean("linked")}
    }catch(error:ApiFailure){
        if(error.httpStatus==404&&error.code in setOf(404,100001)&&!error.sessionExpired)false else throw error
    }
    suspend fun accounts(): List<JSONObject> {
        val top = (call("/api/v1/accounts/list.json?visible_only=false") as JSONArray).objects()
        return top.flatMap { if (it.array("subAccounts").isEmpty()) listOf(it) else it.array("subAccounts") }
    }
    suspend fun userProfile(): JSONObject = call("/api/v1/users/profile/get.json") as JSONObject
    suspend fun categories(): List<JSONObject> {
        val result = call("/api/v1/transaction/categories/list.json") as JSONObject
        return result.keys().asSequence().flatMap { key -> result.array(key).asSequence() }.flatMap { parent ->
            sequenceOf(parent) + parent.array("subCategories").asSequence()
        }.toList()
    }
    /** A category tree is usable for management only when surrounded by the same revision. */
    suspend fun categorySnapshot(): Pair<List<JSONObject>,String?> {
        suspend fun revision():String? = try {
            val value=(call("/api/v1/transaction/categories/revision.json") as JSONObject).getString("revision")
            require(Regex("[0-9]+").matches(value)) { "分类版本无效，请更新服务" }; value
        } catch(e:ApiFailure) {
            if(e.httpStatus==404 && e.code in setOf(404,100001)) null else throw e
        }
        repeat(3) {
            val before=revision() ?: return categories() to null
            val rows=categories()
            if(revision()==before) return rows to before
        }
        throw IllegalArgumentException("分类正在更新，请稍后刷新")
    }
    suspend fun categoryImpact(id:String):JSONObject {
        require(Regex("[1-9][0-9]*").matches(id))
        return call("/api/v1/transaction/categories/impact.json?id=$id") as JSONObject
    }
    suspend fun tags() = (call("/api/v1/transaction/tags/list.json") as JSONArray).objects()
    suspend fun tagGroups() = (call("/api/v1/transaction/tags/groups/list.json") as JSONArray).objects()
    suspend fun transactions(page:Int,month:String?=null,day:String?=null):List<JSONObject> = ledgerPage(page,month,day).items
    suspend fun ledgerPage(page: Int, month:String?=null, day:String?=null): LedgerPage {
        val bounds=day?.let{dayBounds(it)}?:month?.let { monthBounds(it) }
        val range=if(bounds==null) "" else "&min_time=${Math.multiplyExact(bounds.first,1000)}&max_time=${Math.addExact(Math.multiplyExact(bounds.second,1000),999)}"
        val result=call("/api/v1/transactions/list.json?count=30&page=$page&with_pictures=true&with_count=true$range") as JSONObject
        val items=result.array("items");val total=result.optLong("totalCount",-1)
        return LedgerPage(items,if(total>=0)page.toLong()*30<total else items.size==30)
    }
    suspend fun dailyAmounts(month:String):List<JSONObject> {
        val bounds=monthBounds(month)
        return dailyRange(bounds.first,bounds.second)
    }
    suspend fun dailyRange(start:Long,end:Long):List<JSONObject> = (call("/api/v1/transactions/amounts/daily.json?start_time=$start&end_time=$end&use_transaction_timezone=false") as JSONArray).objects()

    suspend fun transaction(id: String) = call("/api/v1/transactions/get.json?id=$id&with_pictures=true") as JSONObject
    suspend fun deleteTransaction(id:String){
        require(Regex("[1-9][0-9]*").matches(id))
        require(call("/api/v1/transactions/delete.json",JSONObject().put("id",id))==true) { "删除未完成，请刷新核对后重试" }
    }
    suspend fun removeUnusedPicture(id:String){
        require(Regex("[1-9][0-9]*").matches(id))
        require(call("/api/v1/transaction/pictures/remove_unused.json",JSONObject().put("id",id))==true) { "未用图片清理未完成" }
    }
    suspend fun family(month: String) = call("/api/v1/family/overview.json?month=$month") as JSONObject
    suspend fun familyTransactions(month: String, cursor: String = "", memberId: String = "0") = call("/api/v1/family/transactions/list.json?month=$month&member_id=$memberId&cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}") as JSONObject
    internal suspend fun familyReport(period: ReportPeriod, memberId: String = "0"): JSONObject {
        val value = if (period.yearly && !period.custom) period.month.year.toString() else period.month.toString()
        return call("/api/v1/family/report.json?period=$value&yearly=${period.yearly && !period.custom}&member_id=${java.net.URLEncoder.encode(memberId, "UTF-8")}") as JSONObject
    }
    internal suspend fun familyReportTransactions(period: ReportPeriod, memberId: String = "0", date: String = "", categoryId: String = "", accountId: String = "", cursor: String = ""): JSONObject {
        val value = if (period.yearly && !period.custom) period.month.year.toString() else period.month.toString()
        val params = listOf(
            "period=${java.net.URLEncoder.encode(value, "UTF-8")}",
            "yearly=${period.yearly && !period.custom}",
            "member_id=${java.net.URLEncoder.encode(memberId, "UTF-8")}",
            "date=${java.net.URLEncoder.encode(date, "UTF-8")}",
            "category_id=${java.net.URLEncoder.encode(categoryId, "UTF-8")}",
            "account_id=${java.net.URLEncoder.encode(accountId, "UTF-8")}",
            "cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}",
        ).joinToString("&")
        return call("/api/v1/family/report/transactions.json?$params") as JSONObject
    }
    companion object {
        fun dayBounds(day:String):Pair<Long,Long>{val value=java.time.LocalDate.parse(day);val zone=ZoneId.of("Asia/Shanghai");return value.atStartOfDay(zone).toEpochSecond() to value.plusDays(1).atStartOfDay(zone).toEpochSecond()-1}
        fun monthBounds(month:String):Pair<Long,Long> {
            val value=YearMonth.parse(month);val zone=ZoneId.of("Asia/Shanghai")
            return value.atDay(1).atStartOfDay(zone).toEpochSecond() to value.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()-1
        }
        fun month(): String = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString() }
}
