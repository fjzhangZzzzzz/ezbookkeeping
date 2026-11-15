package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Logger
import java.util.logging.Level

/** Real synthetic backend commit + actual wire disconnect before any HTTP response, no invented responses. */
class EntityUnknownResultIntegrationTest {
    private val backend="http://127.0.0.1:18096"
    private class Memory:TransactionRequestStorage {var value:String?=null;override fun read()=value;override fun write(value:String){this.value=value};override fun clear(){value=null}}
    private class Proxy:java.io.Closeable {
        val server=MockWebServer();var writes=0;val bodies=mutableListOf<String>()
        private val upstream=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).retryOnConnectionFailure(false).build()
        init{
            Logger.getLogger(MockWebServer::class.java.name).level=Level.WARNING
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
                val path=checkNotNull(request.path);require(path.startsWith("/api/"));val bytes=request.body.readByteArray()
                val req=Request.Builder().url("http://127.0.0.1:18096"+path)
                for(name in listOf("Authorization","X-Timezone-Name","X-Timezone-Offset"))request.getHeader(name)?.let{req.header(name,it)}
                req.method(request.method!!,if(request.method=="GET")null else bytes.toRequestBody("application/json".toMediaType()))
                upstream.newCall(req.build()).execute().use{response->
                    val body=response.body!!.bytes();val result=MockResponse().setResponseCode(response.code).setHeader("Content-Type","application/json").setBody(okio.Buffer().write(body))
                    if(isEntityWritePath(path)&&request.method=="POST"){
                        writes++;bodies+=bytes.toString(Charsets.UTF_8)
                        if(writes==1&&response.isSuccessful)result.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    return result
                }
            }};server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        }
        val origin get()=server.url("/").toString().trimEnd('/')
        override fun close(){server.shutdown()}
    }
    private fun input():JSONObject{val path=System.getenv("BOOKS_ENTITY_TEST_INPUT");assumeTrue(!path.isNullOrBlank()&&File(path).isFile);val doc=JSONObject(File(path!!).readText());check(doc.optBoolean("complete"));return doc.getJSONArray("users").getJSONObject(0)}
    private suspend fun uid(api:BooksApi):String{val tokens=(api.call("/api/v1/tokens/list.json") as JSONArray).objects();val own=api.accounts();return entityAuthenticatedUid(tokens,own,if(own.isEmpty())emptyList()else api.family(BooksApi.month()).array("accounts"))}
    private fun record(key:String){System.getenv("BOOKS_ENTITY_TEST_RESULT")?.takeIf{it.isNotBlank()}?.let{path->val f=File(path);val result=if(f.isFile)JSONObject(f.readText())else JSONObject();result.put(key,true);f.writeText(result.toString())}}
    @Test fun accountCommitUnknownNeverReplaysOrChangesFrozenBody()=runBlocking<Unit>{
        val fixture=input();var token:String?=null;val direct=BooksApi(backend){token};token=direct.login(fixture.getString("loginName"),fixture.getString("password"),"").getString("token")
        try{Proxy().use{proxy->
            val api=BooksApi(proxy.origin){token};api.call("/api/v1/tokens/list.json") // warm the real HTTP connection
            val name="实体断连账户-"+UUID.randomUUID();val body=JSONObject().put("name",name).put("category",1).put("type",1).put("currency","CNY").put("balance","1").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())
            val store=Memory();val state=EntityWriteState(proxy.origin,store,{uid(direct)},{path,value->api.writeEntityOnce(path,value)})
            var failed=false;try{state.submit("/api/v1/accounts/add.json",body)}catch(_:IOException){failed=true};assertTrue(failed);assertTrue(state.blocked);assertEquals(1,proxy.writes)
            val frozen=store.value;body.put("name","绝不能重复发送").put("balance","999")
            assertTrue(runCatching{state.requireNewWrite()}.isFailure);state.refresh();state.refresh()
            assertEquals(name,state.view()!!.body.getString("name"));assertEquals("1",state.view()!!.body.getString("balance"));assertEquals(frozen,store.value);assertEquals(1,proxy.writes)
            val actual=direct.accounts().filter{it.optString("name")==name};assertEquals(1,actual.size);assertEquals("1",actual.single().optString("balance"))
            state.abandonAfterExplicitReview();assertFalse(state.blocked);assertNull(store.value);assertEquals(1,proxy.writes)
            record("realAccountCommitThenResponseLostNoReplayFrozenOriginalAndSingleOneFenAccount")
        }}finally{runCatching{direct.call("/api/logout.json",JSONObject())}}
    }
    @Test fun tagGroupWithoutIdempotencyIsNeverRepostedAfterUnknown()=runBlocking<Unit>{
        val fixture=input();var token:String?=null;val direct=BooksApi(backend){token};token=direct.login(fixture.getString("loginName"),fixture.getString("password"),"").getString("token")
        try{Proxy().use{proxy->
            val api=BooksApi(proxy.origin){token};api.call("/api/v1/tokens/list.json")
            val name="实体断连组-"+UUID.randomUUID();val body=JSONObject().put("name",name);val store=Memory()
            val state=EntityWriteState(proxy.origin,store,{uid(direct)},{path,value->api.writeEntityOnce(path,value)})
            var failed=false;try{state.submit("/api/v1/transaction/tags/groups/add.json",body)}catch(_:IOException){failed=true};assertTrue(failed);assertTrue(state.blocked);assertEquals(1,proxy.writes)
            repeat(2){state.refresh();assertTrue(runCatching{state.requireNewWrite()}.isFailure)}
            assertEquals(1,direct.tagGroups().count{it.optString("name")==name});state.abandonAfterExplicitReview();assertEquals(1,proxy.writes)
            record("realNonIdempotentTagGroupCommitUnknownReadsAndAbandonNeverReplaySingleGroup")
        }}finally{runCatching{direct.call("/api/logout.json",JSONObject())}}
    }
}
