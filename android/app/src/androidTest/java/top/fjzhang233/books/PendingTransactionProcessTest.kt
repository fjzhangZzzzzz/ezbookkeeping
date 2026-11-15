package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Logger
import java.util.logging.Level

/** Two explicit stages; operator must force-stop and prove PID disappearance between them. Synthetic only. */
class PendingTransactionProcessTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val backend="http://127.0.0.1:18090"
    private val run get()=InstrumentationRegistry.getArguments().getString("qaPendingRun","pending-v2").also{require(Regex("[a-z0-9-]{1,64}").matches(it))}
    private val marker get()=File(context.filesDir,"qa-pending-$run-private.json")
    private class FaultProxy(val backend:String):java.io.Closeable {
        val server=MockWebServer()
        val forwardedBodies=mutableListOf<String>()
        var truncateAdd=false;var truncateDelete=false;var failFamilyOnce=false
        var committedAdds=0;var committedDeletes=0;var cleanupCalls=0;var transactionLookups=0
        private val upstream=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        init {
            Logger.getLogger(MockWebServer::class.java.name).level=Level.WARNING
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val path=checkNotNull(request.path)
                check(path.startsWith("/api/"))
                if(failFamilyOnce && path.startsWith("/api/v1/family/overview.json")){failFamilyOnce=false;return MockResponse().setResponseCode(500).setBody("""{"success":false,"errorCode":100001}""")}
                val bytes=request.body.readByteArray()
                val builder=Request.Builder().url(backend+path)
                for(header in listOf("Authorization","X-Timezone-Name","X-Timezone-Offset","User-Agent"))request.getHeader(header)?.let{builder.header(header,it)}
                builder.method(request.method!!,if(request.method=="GET")null else bytes.toRequestBody("application/json".toMediaType()))
                upstream.newCall(builder.build()).execute().use{response->
                    val body=response.body!!.bytes()
                    val answer=MockResponse().setResponseCode(response.code).setHeader("Content-Type",response.header("Content-Type")?:"application/json").setBody(okio.Buffer().write(body))
                    if(path=="/api/v1/transaction/pictures/remove_unused.json")cleanupCalls++
                    if(path.startsWith("/api/v1/transactions/get.json"))transactionLookups++
                    if(path=="/api/v1/transactions/add.json"){
                        forwardedBodies+=bytes.toString(Charsets.UTF_8)
                        if(response.isSuccessful){committedAdds++;if(truncateAdd){truncateAdd=false;answer.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)}}
                    }
                    if(path=="/api/v1/transactions/delete.json" && response.isSuccessful){committedDeletes++;if(truncateDelete){truncateDelete=false;answer.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)}}
                    return answer
                }
            }}
            server.start(java.net.InetAddress.getByName("127.0.0.1"),18094)
        }
        val url:String get()=server.url("/").toString()
        override fun close(){server.shutdown()}
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fixture():JSONObject {
        assumeTrue("Requires isolated pending-process debug build",BuildConfig.DEBUG && BuildConfig.API_BASE_URL=="http://127.0.0.1:18094")
        val file=File(context.filesDir,"qa-config.json");assumeTrue("Requires private synthetic handoff",file.isFile)
        return JSONObject(file.readText())
    }
    private fun persist(name:String,value:JSONObject){context.openFileOutput(name,android.content.Context.MODE_PRIVATE).use{it.write(value.toString().toByteArray());it.fd.sync()}}
    private fun writeResult(stage:Int,value:JSONObject){persist("qa-pending-$run-stage$stage-result.json",value)}
    private fun vm(scenario:ActivityScenario<MainActivity>,action:(BooksModel)->Unit){scenario.onActivity{action(ViewModelProvider(it)[BooksModel::class.java])}}
    private fun ready(scenario:ActivityScenario<MainActivity>){compose.waitUntil(45000){var result=false;vm(scenario){result=!it.busy};result}}
    private fun waitForTag(tag:String){compose.waitUntil(45000){compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
    private fun store(uid:String):TokenStore {
        val scope=sha((BuildConfig.API_BASE_URL.trimEnd('/')+"\n"+uid).toByteArray())
        return TokenStore(context,"pending-transaction-$scope","pending-transaction-$scope")
    }
    @Test fun stage1CommittedDisconnectPersistsAndLocksDraft()=runBlocking {
        val input=fixture();check(!marker.exists()){"Pending process scenario already exists"}
        val direct=BooksApi(backend){TokenStore(context).read()}
        val token=direct.login(input.getString("username"),input.getString("password"),"").getString("token")
        TokenStore(context).write(token!!)
        val account=(direct.call("/api/v1/accounts/add.json",JSONObject().put("name","合成持久确认-"+UUID.randomUUID()).put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
        val category=direct.categories().first{it.optInt("type")==2 && it.id("parentId")!="0" && !it.optBoolean("hidden")}
        val image=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAF0lEQVR4nGMwn/SLJMQwqmFUw69hqwEAAH7DEDfWY2cAAAAASUVORK5CYII=")
        val uploaded=direct.upload(image,"image/png").id("pictureId")
        val comment="合成进程确认-"+UUID.randomUUID()
        val body=JSONObject().put("type",3).put("categoryId",category.id()).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("tagIds",JSONArray()).put("pictureIds",JSONArray(listOf(uploaded))).put("comment",comment).put("clientSessionId",UUID.randomUUID().toString())
        FaultProxy(backend).use{proxy->
            val scenario=ActivityScenario.launch(MainActivity::class.java);waitForTag("ledger-list");ready(scenario)
            proxy.truncateAdd=true
            vm(scenario){it.newTransaction();it.registerDraftPicture(uploaded,draftThumbnail(image));it.saveTransaction(body,false)}
            waitForTag("confirm-pending-transaction");ready(scenario)
            var locked=false
            vm(scenario){model->check(model.transactionNeedsConfirmation);locked=runCatching{model.removeDraftPicture(uploaded)}.isFailure;model.closeTransaction();check(model.showEditor)}
            assertTrue(locked);assertEquals(1,proxy.forwardedBodies.size);assertEquals(0,proxy.cleanupCalls)
            val identity=submissionIdentity(direct.accounts(),direct.family(BooksApi.month()).array("accounts"))
            val pending=store(identity.uid).readChecked();check(!pending.isNullOrBlank())
            val frozen=JSONObject(pending!!).getString("body")
            assertEquals(sha(proxy.forwardedBodies.single().toByteArray()),sha(frozen.toByteArray()))
            val scope=sha((BuildConfig.API_BASE_URL.trimEnd('/')+"\n"+identity.uid).toByteArray())
            val cipher=context.getSharedPreferences("pending-transaction-$scope",android.content.Context.MODE_PRIVATE).getString("encrypted",null)
            assertFalse(cipher.isNullOrBlank());assertFalse(cipher!!.contains(comment));assertFalse(cipher.contains("sourceAccountId"))
            val records=(direct.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1&with_count=true") as JSONObject).array("items").filter{it.optString("comment")==comment}
            assertEquals(1,records.size);assertEquals("-1",direct.accounts().single{it.id()==account}.optString("balance"))
            persist(marker.name,JSONObject().put("pid",android.os.Process.myPid()).put("uid",identity.uid).put("account",account).put("comment",comment).put("transaction",records.single().id()).put("bodySha256",sha(frozen.toByteArray())).put("picture",uploaded).put("imageSha256",sha(image)))
            writeResult(1,JSONObject().put("phase1ReadyForForceStop",true).put("realGoCommittedBeforeWireDisconnect",true).put("pendingEncryptedBeforeRestart",true).put("draftMutationAndCleanupLocked",true).put("oneTransactionAndMinusOneFen",true).put("pid",android.os.Process.myPid()))
        }
    }
    @Test fun stage2FreshProcessRestoresOriginalUidAndConfirmsOnce()=runBlocking {
        fixture();check(marker.isFile){"Stage1 must pass before force-stop and stage2"}
        val before=JSONObject(marker.readText());check(before.getInt("pid")!=android.os.Process.myPid()){"A fresh process is required"}
        val direct=BooksApi(backend){TokenStore(context).read()}
        val identity=submissionIdentity(direct.accounts(),direct.family(BooksApi.month()).array("accounts"));assertEquals(before.getString("uid"),identity.uid)
        check(before.getString("account") in identity.accountIds)
        FaultProxy(backend).use{proxy->
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            waitForTag("confirm-pending-transaction");ready(scenario)
            vm(scenario){check(it.transactionNeedsConfirmation && it.restoredTransaction!=null)}
            compose.onNodeWithTag("confirm-pending-transaction").performClick();waitForTag("ledger-list");ready(scenario)
            assertEquals(1,proxy.forwardedBodies.size);assertEquals(before.getString("bodySha256"),sha(proxy.forwardedBodies.single().toByteArray()));assertEquals(0,proxy.cleanupCalls)
            assertNull(store(identity.uid).readChecked())
            val account=before.getString("account")
            val matching=(direct.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1&with_pictures=true&with_count=true") as JSONObject).array("items").filter{it.optString("comment")==before.getString("comment")}
            assertEquals(1,matching.size);assertEquals(before.getString("transaction"),matching.single().id());assertEquals("-1",direct.accounts().single{it.id()==account}.optString("balance"))
            assertEquals(before.getString("imageSha256"),sha(direct.picture("/api/v1/family/pictures/${before.getString("picture")}.png")))
            writeResult(2,JSONObject().put("freshProcessVerified",true).put("originalUidVerifiedFromFreshProtectedAccountJoin",true).put("actualModelRestoredEncryptedPending",true).put("uiConfirmReplayedExactOriginalBody",true).put("singleTransactionBalanceAndPictureShaVerified",true).put("encryptedPendingClearedOnlyAfterAcknowledgment",true).put("pid",android.os.Process.myPid()))
            deleteTransactionOrAlreadyAbsent(direct,before.getString("transaction"))
            vm(scenario){it.logout()};ready(scenario);scenario.close()
        }
    }
    @Test fun stage3FailedStartupBlocksNewDraftAndFallbackShowsOriginalContent()=runBlocking {
        val input=fixture();val direct=BooksApi(backend){TokenStore(context).read()}
        TokenStore(context).write(direct.login(input.getString("username"),input.getString("password"),"").getString("token"))
        val name="合成恢复展示-"+UUID.randomUUID()
        val account=(direct.call("/api/v1/accounts/add.json",JSONObject().put("name",name).put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
        val category=direct.categories().first{it.optInt("type")==2 && it.id("parentId")!="0" && !it.optBoolean("hidden")}
        val oldComment="原保存-"+UUID.randomUUID();val newComment="新草稿不得提交-"+UUID.randomUUID()
        val body=JSONObject().put("type",3).put("categoryId",category.id()).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("tagIds",JSONArray()).put("pictureIds",JSONArray()).put("comment",oldComment).put("clientSessionId",UUID.randomUUID().toString())
        val identity=submissionIdentity(direct.accounts(),direct.family(BooksApi.month()).array("accounts"));check(store(identity.uid).readChecked()==null)
        store(identity.uid).write(JSONObject().put("schema",1).put("origin",BuildConfig.API_BASE_URL.trimEnd('/')).put("uid",identity.uid).put("path","/api/v1/transactions/add.json").put("body",body.toString()).put("keepOpen",false).toString())
        FaultProxy(backend).use{proxy->
            proxy.failFamilyOnce=true
            val scenario=ActivityScenario.launch(MainActivity::class.java);waitForTag("ledger-list");ready(scenario)
            var blocked=false
            vm(scenario){it.newTransaction();blocked=!it.showEditor};assertTrue(blocked)
            // Exercise the defensive prepare fallback even if an older/reused UI bypassed the new-entry guard.
            val newBody=JSONObject(body.toString()).put("sourceAmount",999).put("comment",newComment).put("clientSessionId",UUID.randomUUID().toString())
            vm(scenario){it.showEditor=true;it.saveTransaction(newBody,false)}
            waitForTag("confirm-pending-transaction");ready(scenario)
            compose.onNodeWithText("金额：0.01 CNY").assertIsDisplayed();compose.onNodeWithText("账户：$name").assertIsDisplayed();compose.onNodeWithText("备注：$oldComment").assertIsDisplayed()
            assertEquals(0,proxy.forwardedBodies.size)
            compose.onNodeWithTag("confirm-pending-transaction").performClick();waitForTag("ledger-list");ready(scenario)
            val actual=JSONObject(proxy.forwardedBodies.single());assertEquals(oldComment,actual.getString("comment"));assertEquals(1L,actual.getLong("sourceAmount"))
            val matching=(direct.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1") as JSONObject).array("items").filter{it.optInt("type")==3}
            assertEquals(1,matching.size);assertEquals(oldComment,matching.single().optString("comment"));assertEquals("-1",direct.accounts().single{it.id()==account}.optString("balance"))
            writeResult(3,JSONObject().put("startupRestoreFailureBlocksNewDraft",true).put("prepareFallbackShowsFrozenOriginalAmountAccountMemo",true).put("newEditorBodyNeverPosted",true).put("explicitConfirmPostsOnlyOldBody",true).put("singleOneFenTransactionVerified",true))
            deleteTransactionOrAlreadyAbsent(direct,matching.single().id());vm(scenario){it.logout()};ready(scenario);scenario.close()
        }
    }

    @Test fun stage4RepeatedIdentityFailuresKeepImagesLockedAndOriginalReplayable()=runBlocking {
        val input=fixture()
        val direct=BooksApi(backend){TokenStore(context).read()}
        val token=direct.login(input.getString("username"),input.getString("password"),"").getString("token")
        TokenStore(context).write(token!!)
        val account=(direct.call("/api/v1/accounts/add.json",JSONObject().put("name","合成持久确认-"+UUID.randomUUID()).put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
        val category=direct.categories().first{it.optInt("type")==2 && it.id("parentId")!="0" && !it.optBoolean("hidden")}
        val image=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAF0lEQVR4nGMwn/SLJMQwqmFUw69hqwEAAH7DEDfWY2cAAAAASUVORK5CYII=")
        val uploaded=direct.upload(image,"image/png").id("pictureId")
        val comment="合成进程确认-"+UUID.randomUUID()
        val body=JSONObject().put("type",3).put("categoryId",category.id()).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("tagIds",JSONArray()).put("pictureIds",JSONArray(listOf(uploaded))).put("comment",comment).put("clientSessionId",UUID.randomUUID().toString())
        FaultProxy(backend).use{proxy->
            val scenario=ActivityScenario.launch(MainActivity::class.java);waitForTag("ledger-list");ready(scenario)
            proxy.truncateAdd=true
            vm(scenario){it.newTransaction();it.registerDraftPicture(uploaded,draftThumbnail(image));it.saveTransaction(body,false)}
            waitForTag("confirm-pending-transaction");ready(scenario)
            val identity=submissionIdentity(direct.accounts(),direct.family(BooksApi.month()).array("accounts"))
            val originalCipher=store(identity.uid).readChecked();check(!originalCipher.isNullOrBlank())
            proxy.failFamilyOnce=true
            vm(scenario){it.confirmTransactionSave()};ready(scenario);waitForTag("refresh-pending-identity")
            repeat(2){
                proxy.failFamilyOnce=true
                compose.onNodeWithTag("refresh-pending-identity").performClick();ready(scenario)
                compose.onNodeWithTag("refresh-pending-identity").assertIsDisplayed()
                compose.onNodeWithTag("confirm-pending-transaction").assertDoesNotExist()
                vm(scenario){model->
                    check(model.transactionNeedsConfirmation && model.transactionConfirmation==null)
                    check(runCatching{model.removeDraftPicture(uploaded)}.isFailure)
                    check(runCatching{model.registerDraftPicture(uploaded,draftThumbnail(image))}.isFailure)
                    model.closeTransaction();check(model.showEditor)
                    model.newTransaction();check(model.draftPictures==listOf(uploaded))
                }
                vm(scenario){it.openTransaction("1")};ready(scenario)
                vm(scenario){it.copyTransaction("1")};ready(scenario)
                vm(scenario){check(it.transactionNeedsConfirmation && it.transactionConfirmation==null && it.showEditor && it.draftPictures==listOf(uploaded))}
                assertEquals(0,proxy.transactionLookups)
                assertEquals(originalCipher,store(identity.uid).readChecked());assertEquals(0,proxy.cleanupCalls);assertEquals(1,proxy.forwardedBodies.size)
            }
            compose.onNodeWithTag("refresh-pending-identity").performClick();ready(scenario);waitForTag("confirm-pending-transaction")
            compose.onNodeWithText("金额：0.01 CNY").assertIsDisplayed()
            compose.onNodeWithTag("confirm-pending-transaction").performClick();ready(scenario);waitForTag("ledger-list")
            assertEquals(2,proxy.forwardedBodies.size);assertEquals(proxy.forwardedBodies[0],proxy.forwardedBodies[1]);assertEquals(0,proxy.cleanupCalls)
            assertNull(store(identity.uid).readChecked())
            val rows=(direct.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1&with_pictures=true") as JSONObject).array("items").filter{it.optString("comment")==comment}
            assertEquals(1,rows.size);assertEquals("-1",direct.accounts().single{it.id()==account}.optString("balance"))
            assertEquals(sha(image),sha(direct.picture("/api/v1/family/pictures/$uploaded.png")))
            writeResult(4,JSONObject().put("realGoCommitThenUnknownResponse",true).put("retryThenTwoIdentityRefreshFailuresKeepLock",true).put("unverifiedOriginalContentHidden",true).put("removeRegisterCloseNewEditCopyCannotCleanupPendingImage",true).put("zeroCleanupCallsAndEncryptedRequestUnchanged",true).put("successfulRecheckReplaysExactOriginalBody",true).put("singleOneFenAndOriginalPictureShaVerified",true))
            deleteTransactionOrAlreadyAbsent(direct,rows.single().id());vm(scenario){it.logout()};ready(scenario);scenario.close()
        }
    }

}
