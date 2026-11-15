package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/** Real isolated Go backend. Faults are injected on the wire only after the upstream has committed. */
class TransactionUnknownResultIntegrationTest {
    private val base="http://127.0.0.1:18090"
    private class MemoryRequestStorage:TransactionRequestStorage{var value:String?=null;override fun read()=value;override fun write(value:String){this.value=value};override fun clear(){value=null}}
    private class FaultProxy(val backend:String):java.io.Closeable {
        val server=MockWebServer()
        val forwardedBodies=mutableListOf<String>()
        var truncateAdd=false;var truncateDelete=false
        var committedAdds=0;var committedDeletes=0;var cleanupCalls=0
        private val upstream=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        init {
            Logger.getLogger(MockWebServer::class.java.name).level=Level.WARNING
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val path=checkNotNull(request.path)
                check(path.startsWith("/api/v1/transactions/") || path.startsWith("/api/v1/transaction/pictures/"))
                val bytes=request.body.readByteArray()
                val builder=Request.Builder().url(backend+path)
                for(header in listOf("Authorization","X-Timezone-Name","X-Timezone-Offset","User-Agent"))request.getHeader(header)?.let{builder.header(header,it)}
                builder.method(request.method!!,if(request.method=="GET")null else bytes.toRequestBody("application/json".toMediaType()))
                upstream.newCall(builder.build()).execute().use{response->
                    val body=response.body!!.string()
                    val answer=MockResponse().setResponseCode(response.code).setHeader("Content-Type","application/json").setBody(body)
                    if(path=="/api/v1/transaction/pictures/remove_unused.json")cleanupCalls++
                    if(path=="/api/v1/transactions/add.json"){
                        forwardedBodies+=bytes.toString(Charsets.UTF_8)
                        if(response.isSuccessful){committedAdds++;if(truncateAdd){truncateAdd=false;answer.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)}}
                    }
                    if(path=="/api/v1/transactions/delete.json" && response.isSuccessful){committedDeletes++;if(truncateDelete){truncateDelete=false;answer.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)}}
                    return answer
                }
            }}
            server.start()
        }
        val url:String get()=server.url("/").toString()
        override fun close(){server.shutdown()}
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private suspend fun session():Pair<BooksApi,()->String>{
        val inputPath=System.getenv("BOOKS_SYNTHETIC_TEST_INPUT")
        assumeTrue("Requires explicit local synthetic handoff",!inputPath.isNullOrBlank())
        check(BuildConfig.DEBUG)
        val input=JSONObject(File(inputPath!!).readText())
        var token:String?=null
        val api=BooksApi(base){token}
        val auth=api.login(input.getString("loginName"),input.getString("password"),"")
        token=auth.getString("token");check(!token.isNullOrBlank())
        return api to {token!!}
    }
    private suspend fun account(api:BooksApi):String {
        val body=JSONObject().put("name","合成未知结果-"+UUID.randomUUID()).put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())
        return (api.call("/api/v1/accounts/add.json",body) as JSONObject).id()
    }
    private suspend fun expense(api:BooksApi,account:String,comment:String,pictures:List<String> = emptyList()):JSONObject {
        val category=api.categories().first{it.optInt("type")==2 && it.id("parentId")!="0" && !it.optBoolean("hidden")}
        return JSONObject().put("type",3).put("categoryId",category.id()).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("tagIds",JSONArray()).put("pictureIds",JSONArray(pictures)).put("comment",comment).put("clientSessionId",UUID.randomUUID().toString())
    }
    // Fixed 16x16 RGB PNG; every chunk CRC was independently verified.
    private fun png():ByteArray=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAF0lEQVR4nGMwn/SLJMQwqmFUw69hqwEAAH7DEDfWY2cAAAAASUVORK5CYII=")
    @Test fun committedAddTruncatedResponseFreezesOriginalRequestAndReplaysExactlyOnce()=runBlocking {
        val(api,token)=session();val account=account(api);val comment="合成原样重试-"+UUID.randomUUID();var created:String?=null
        try {
            val image=png();val picture=api.upload(image,"image/png").id("pictureId")
            FaultProxy(base).use{proxy->
                val wire=BooksApi(proxy.url){token()};val persisted=MemoryRequestStorage()
                val owner=api.accounts().map{it.id()}.toSet()
                val identity=submissionIdentity(api.accounts(),api.family(BooksApi.month()).array("accounts"))
                fun createSubmission()=TransactionSubmission(base,identify={submissionIdentity(api.accounts(),api.family(BooksApi.month()).array("accounts"))},storage={uid->check(uid==identity.uid);persisted},send={path,request->wire.call(path,request)})
                var submission=createSubmission()
                val payload=expense(api,account,comment,listOf(picture));proxy.truncateAdd=true
                var uncertain=false
                try{submission.submit("/api/v1/transactions/add.json",payload,false)}catch(_:Exception){uncertain=true}
                assertTrue(uncertain);assertTrue(submission.needsConfirmation);assertEquals(1,proxy.committedAdds)
                payload.put("comment","不应提交的更改").put("pictureIds",JSONArray())
                var editBlocked=false;try{submission.requireEditable()}catch(_:IllegalArgumentException){editBlocked=true};assertTrue(editBlocked)
                var newWriteBlocked=false;try{submission.submit("/api/v1/transactions/add.json",payload,false)}catch(_:IllegalArgumentException){newWriteBlocked=true};assertTrue(newWriteBlocked)
                assertEquals(1,proxy.forwardedBodies.size);assertEquals(0,proxy.cleanupCalls)
                // Reconstruct the actual state component with the same durable backing, as after process loss.
                submission=createSubmission();check(submission.restore()!=null);assertTrue(submission.needsConfirmation)
                submission.retry();assertFalse(submission.needsConfirmation);assertNull(persisted.value)
                assertEquals(2,proxy.forwardedBodies.size);assertEquals(sha(proxy.forwardedBodies[0].toByteArray()),sha(proxy.forwardedBodies[1].toByteArray()))
                val result=api.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1&with_pictures=true&with_count=true") as JSONObject
                val matching=result.array("items").filter{it.optInt("type")==3 && it.optString("comment")==comment}
                assertEquals(1,matching.size);created=matching.single().id()
                assertEquals(1L,matching.single().getLong("sourceAmount"));assertEquals(picture,matching.single().array("pictures").single().id("pictureId"))
                assertEquals("-1",api.accounts().single{it.id()==account}.optString("balance"))
                assertEquals(sha(image),sha(api.picture("/api/v1/family/pictures/$picture.png")))
                assertEquals(0,proxy.cleanupCalls)
            }
        }finally{created?.let{deleteTransactionOrAlreadyAbsent(api,it)};api.call("/api/logout.json")}
    }
    @Test fun committedDeleteTruncatedResponseConvergesOnlyThroughExactBackendNotFound()=runBlocking {
        val(api,token)=session();val account=account(api);val body=expense(api,account,"合成删除结果-"+UUID.randomUUID())
        val created=(api.call("/api/v1/transactions/add.json",body) as JSONObject).id()
        try {
            FaultProxy(base).use{proxy->
                val wire=BooksApi(proxy.url){token()};proxy.truncateDelete=true
                var uncertain=false;try{deleteTransactionOrAlreadyAbsent(wire,created)}catch(_:Exception){uncertain=true};assertTrue(uncertain);assertEquals(1,proxy.committedDeletes)
                assertTrue(deleteTransactionOrAlreadyAbsent(wire,created))
                assertEquals(1,proxy.committedDeletes)
                assertEquals("0",api.accounts().single{it.id()==account}.optString("balance"))
                var exactMissing=false;try{api.transaction(created)}catch(error:ApiFailure){exactMissing=error.code==205001};assertTrue(exactMissing)
            }
        }finally{deleteTransactionOrAlreadyAbsent(api,created);api.call("/api/logout.json")}
    }
}
