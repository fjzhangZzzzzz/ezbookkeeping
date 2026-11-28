package top.fjzhang233.books

import android.view.KeyEvent
import android.view.MotionEvent
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/** Dedicated 18096 synthetic backend + in-process 18098 fault proxy. No production input or implicit replay. */
class EntityWriteProcessTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val run get()=InstrumentationRegistry.getArguments().getString("qaEntityRun","entity-v1").also{require(Regex("[a-z0-9-]{1,64}").matches(it))}
    private var scenario:ActivityScenario<MainActivity>?=null
    private var stage="initial"
    private class Proxy:java.io.Closeable {
        val server=MockWebServer();val writes=AtomicInteger();val committed=AtomicInteger();val bodies=java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var path:String?=null;@Volatile var disconnect=true;var prepared=CountDownLatch(1);var release=CountDownLatch(1)
        private val upstream=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).retryOnConnectionFailure(false).build()
        init{
            Logger.getLogger(MockWebServer::class.java.name).level=Level.WARNING
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
                val endpoint=checkNotNull(request.path);require(endpoint.startsWith("/api/"));val bytes=request.body.readByteArray()
                val builder=Request.Builder().url("http://127.0.0.1:18096"+endpoint)
                for(header in listOf("Authorization","User-Agent","X-Timezone-Name","X-Timezone-Offset"))request.getHeader(header)?.let{builder.header(header,it)}
                builder.method(request.method!!,if(request.method=="GET")null else bytes.toRequestBody("application/json".toMediaType()))
                upstream.newCall(builder.build()).execute().use{response->
                    val body=response.body!!.bytes();val result=MockResponse().setResponseCode(response.code).setHeader("Content-Type","application/json").setBody(okio.Buffer().write(body))
                    if(isEntityWritePath(endpoint)&&request.method=="POST"){
                        writes.incrementAndGet();bodies+=bytes.toString(Charsets.UTF_8)
                        if(response.isSuccessful){committed.incrementAndGet();if(endpoint==path){prepared.countDown();check(release.await(20,TimeUnit.SECONDS));if(disconnect)result.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}}
                    };return result
                }
            }};server.start(java.net.InetAddress.getByName("127.0.0.1"),18098)
        }
        override fun close(){release.countDown();server.shutdown()}
    }
    private fun fixture():JSONObject {
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL=="http://127.0.0.1:18098")
        val f=File(context.filesDir,"qa-entity-config.json");assumeTrue(f.isFile);val input=JSONObject(f.readText());check(input.optBoolean("complete"));return input
    }
    private fun hash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun pendingStore():TokenStore {val scope=hash(BuildConfig.API_BASE_URL.trimEnd('/'));return TokenStore(context,"pending-entity-$scope","pending-entity-$scope")}
    private fun marker()=File(context.filesDir,"qa-entity-$run-private.json")
    private fun result(number:Int,data:JSONObject){context.openFileOutput("qa-entity-$run-stage$number-result.json",android.content.Context.MODE_PRIVATE).use{it.write(data.toString().toByteArray());it.fd.sync()}}
    private fun model(action:(BooksModel)->Unit){scenario!!.onActivity{action(ViewModelProvider(it)[BooksModel::class.java])}}
    private fun ready(){compose.waitUntil(45000){var idle=false;model{idle=!it.busy};idle}}
    private fun tag(value:String){compose.waitUntil(45000){compose.onAllNodesWithTag(value,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
    private fun appWindows():List<android.view.accessibility.AccessibilityWindowInfo>{val ui=instrumentation.uiAutomation;val info=ui.serviceInfo;val flag=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;if(info.flags and flag==0){info.flags=info.flags or flag;ui.serviceInfo=info};return ui.windows}
    private fun hideIme(){compose.waitUntil(5000){appWindows().any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION}};if(appWindows().any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD})instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitUntil(5000){appWindows().none{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}}
    private fun outside(){val now=android.os.SystemClock.uptimeMillis();val y=context.resources.displayMetrics.heightPixels/2f;val ui=instrumentation.uiAutomation;ui.injectInputEvent(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,4f,y,0),true);ui.injectInputEvent(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,4f,y,0),true);compose.waitForIdle()}
    private fun capture(name:String){
        val ui=instrumentation.uiAutomation;compose.waitUntil(5000){ui.rootInActiveWindow?.packageName?.toString()==context.packageName}
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val before=ui.rootInActiveWindow;check(before?.packageName?.toString()==context.packageName)
        val bitmap=checkNotNull(ui.takeScreenshot());File(context.cacheDir,"qa-entity-$run-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        File(context.cacheDir,"qa-entity-$run-$name.xml").outputStream().use{out->
            val xml=android.util.Xml.newSerializer();xml.setOutput(out,"UTF-8");xml.startDocument("UTF-8",true);xml.startTag(null,"hierarchy")
            var count=0
            fun node(item:android.view.accessibility.AccessibilityNodeInfo,depth:Int){
                check(depth<40&&count++<2000);xml.startTag(null,"node");val bounds=android.graphics.Rect();item.getBoundsInScreen(bounds)
                for((key,value) in mapOf("package" to item.packageName?.toString().orEmpty(),"class" to item.className?.toString().orEmpty(),"text" to if(item.isPassword)"[redacted]" else item.text?.toString().orEmpty(),"content-desc" to item.contentDescription?.toString().orEmpty(),"bounds" to bounds.toShortString(),"enabled" to item.isEnabled.toString(),"clickable" to item.isClickable.toString()))xml.attribute(null,key,value)
                for(i in 0 until item.childCount)item.getChild(i)?.let{node(it,depth+1)};xml.endTag(null,"node")
            };node(checkNotNull(ui.rootInActiveWindow),0);xml.endTag(null,"hierarchy");xml.endDocument()
        };check(ui.rootInActiveWindow?.packageName?.toString()==context.packageName)
    }
    private fun nativeClick(label:String,description:Boolean=false){
        val ui=instrumentation.uiAutomation;compose.waitUntil(10000){
            val root=ui.rootInActiveWindow;if(root==null||root.packageName?.toString()!=context.packageName)false else{
                val matches=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun visit(node:android.view.accessibility.AccessibilityNodeInfo){if((if(description)node.contentDescription?.toString()else node.text?.toString())==label)matches+=node;for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)};visit(root)
                if(matches.size!=1)false else{var node:android.view.accessibility.AccessibilityNodeInfo?=matches.single();repeat(6){if(node?.isClickable==false)node=node?.parent};node?.isClickable==true&&node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)}
            }
        };compose.waitForIdle()
    }
    private fun accounts(){nativeClick("打开菜单",true);nativeClick("账户");tag("accounts-list")}
    private fun catalog(){nativeClick("打开菜单",true);nativeClick("分类与标签");compose.onNodeWithText("标签组",substring=false).performClick()}
    private suspend fun loginFirst(input:JSONObject):BooksApi {val user=input.getJSONArray("users").getJSONObject(0);val api=BooksApi("http://127.0.0.1:18096"){TokenStore(context).read()};TokenStore(context).write(api.login(user.getString("loginName"),user.getString("password"),"").getString("token"));return api}
    private fun launch(){scenario=ActivityScenario.launch(MainActivity::class.java);tag("ledger-list");ready()}
    private fun protectedView():EntityPendingView?{var view:EntityPendingView?=null;model{view=it.entityPendingView};return view}
    @Test fun stage1UnknownAccountIsEncryptedAndBusyCannotEditOrDismiss()=runBlocking<Unit>{
        val input=fixture();check(!marker().exists());check(pendingStore().readChecked()==null)
        try{val direct=loginFirst(input);Proxy().use{proxy->
            launch();accounts();compose.onNodeWithContentDescription("新建账户").performClick()
            val name="实体重启账户-"+UUID.randomUUID();compose.onNodeWithText("账户名称").performTextInput(name);compose.onNodeWithText("初始余额（CNY）").performTextReplacement("0.01");hideIme()
            proxy.path="/api/v1/accounts/add.json";stage="account-busy-commit";compose.onNodeWithText("保存账户").performClick();check(proxy.prepared.await(15,TimeUnit.SECONDS))
            compose.onNodeWithText("账户名称").assertIsNotEnabled();compose.onNodeWithText("币种：CNY").assertExists();compose.onNodeWithText("初始余额（CNY）").assertIsNotEnabled();compose.onNodeWithText("账户类型：现金").assertIsNotEnabled();compose.onNodeWithText("备注",substring=false).assertIsNotEnabled();compose.onNodeWithText("保存账户").assertIsNotEnabled();compose.onNodeWithText("取消",substring=false).assertIsNotEnabled()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);outside();compose.onNodeWithText("账户名称").assertIsDisplayed();capture("account-busy")
            proxy.release.countDown();ready();tag("entity-pending-original");stage="account-unknown-original"
            compose.onNodeWithText("原名称：$name").assertIsDisplayed();compose.onNodeWithText("初始余额：0.01 CNY").assertIsDisplayed();compose.onAllNodes(hasSetTextAction()).assertCountEquals(0);capture("account-unknown-readonly")
            compose.onNodeWithTag("entity-recheck").performClick();ready();assertEquals(1,proxy.writes.get())
            val saved=checkNotNull(pendingStore().readChecked());val scope=hash(BuildConfig.API_BASE_URL.trimEnd('/'));val cipher=context.getSharedPreferences("pending-entity-$scope",0).getString("encrypted",null);check(!cipher.isNullOrBlank()&&!cipher.contains(name))
            compose.onNodeWithTag("entity-view-list").performClick();compose.onNodeWithContentDescription("新建账户").assertIsNotEnabled();assertEquals(saved,pendingStore().readChecked())
            val actual=direct.accounts().filter{it.optString("name")==name};assertEquals(1,actual.size);assertEquals("1",actual.single().optString("balance"))
            marker().writeText(JSONObject().put("producerPid",android.os.Process.myPid()).put("pendingSha256",hash(saved)).toString())
            result(1,JSONObject().put("phase1ReadyForForceStop",true).put("realBackendCommittedBeforeUnknownResponse",true).put("busyNameSaveCancelBackAndOutsideLocked",true).put("unknownOriginalNameAmountReadonly",true).put("encryptedBeforeRestart",true).put("onePostAndOneOneFenAccount",true).put("newEntityBlockedAfterViewingList",true))
        }}catch(error:Throwable){throw AssertionError("entity stage=$stage class=${error.javaClass.simpleName}")}
    }
    @Test fun stage2FreshProcessUidSwitchNeverReplaysAndExplicitReviewOnlyClearsLocalIntent()=runBlocking<Unit>{
        val input=fixture();check(marker().isFile);val mark=JSONObject(marker().readText());check(mark.getInt("producerPid")!=android.os.Process.myPid())
        try{Proxy().use{proxy->
            stage="restore-original";launch();tag("entity-pending-original");ready();val original=checkNotNull(protectedView());val saved=checkNotNull(pendingStore().readChecked());assertEquals(mark.getString("pendingSha256"),hash(saved));compose.onNodeWithText("原名称："+original.body.getString("name")).assertIsDisplayed()
            repeat(2){compose.onNodeWithTag("entity-recheck").performClick();ready()};assertEquals(0,proxy.writes.get())
            stage="switch-uid";model{it.logout()};ready();val other=input.getJSONArray("users").getJSONObject(1);model{it.login(other.getString("loginName"),other.getString("password"),"")};ready();model{it.reviewEntityWrite()};ready();tag("entity-pending-original")
            assertNull(protectedView());capture("other-uid-hidden");compose.onNodeWithText("原名称："+original.body.getString("name")).assertDoesNotExist();compose.onNodeWithTag("entity-abandon-intent").assertDoesNotExist()
            model{it.saveEntity(original.path,JSONObject().put("name","不得新建"),{})};ready();assertEquals(saved,pendingStore().readChecked());assertEquals(0,proxy.writes.get())
            model{it.abandonEntityAfterReview()};ready();assertEquals(saved,pendingStore().readChecked())
            stage="return-original-uid";model{it.logout()};ready();val first=input.getJSONArray("users").getJSONObject(0);model{it.login(first.getString("loginName"),first.getString("password"),"")};ready();model{it.reviewEntityWrite()};ready();compose.onNodeWithText("原名称："+original.body.getString("name")).assertIsDisplayed()
            val direct=BooksApi("http://127.0.0.1:18096"){TokenStore(context).read()};val actual=direct.accounts().filter{it.optString("name")==original.body.getString("name")};assertEquals(1,actual.size);assertEquals("1",actual.single().optString("balance"))
            compose.onNodeWithTag("entity-abandon-intent").performClick();tag("entity-confirm-abandon");compose.onNodeWithTag("entity-confirm-abandon").performClick();ready();assertNull(pendingStore().readChecked());assertEquals(0,proxy.writes.get());model{assertFalse(it.entityBlocked)}
            result(2,JSONObject().put("freshProcessRestoresEncryptedOriginal",true).put("twoRealUidsNeverExposeOrResolveOthersPending",true).put("readChecksAndBlockedNewWritesMakeZeroPosts",true).put("originalOneFenAccountStillExactlyOne",true).put("explicitUserReviewClearsOnlyLocalIntentWithoutReplay",true))
            model{it.logout()};ready();scenario!!.close()
        }}catch(error:Throwable){throw AssertionError("entity stage=$stage class=${error.javaClass.simpleName}")}
    }
    @Test fun stage3NonIdempotentGroupBusyDoubleSaveAndUnknownRemainSingleWrites()=runBlocking<Unit>{
        val input=fixture();check(pendingStore().readChecked()==null)
        try{val direct=loginFirst(input);Proxy().use{proxy->
            launch();catalog();compose.onNodeWithContentDescription("新建标签组").performClick();val ackName="实体组ACK-"+UUID.randomUUID();compose.onNodeWithText("标签组名称").performTextInput(ackName);hideIme()
            proxy.path="/api/v1/transaction/tags/groups/add.json";proxy.disconnect=false;stage="group-busy-no-second-queue";compose.onNodeWithText("保存标签组").performClick();check(proxy.prepared.await(15,TimeUnit.SECONDS))
            compose.onNodeWithText("标签组名称").assertIsNotEnabled();compose.onNodeWithText("取消",substring=false).assertIsNotEnabled();model{it.saveEntity(proxy.path!!,JSONObject().put("name",ackName),{})};instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);outside();compose.onNodeWithText("标签组名称").assertIsDisplayed()
            proxy.release.countDown();ready();assertEquals(1,proxy.writes.get());assertEquals(1,direct.tagGroups().count{it.optString("name")==ackName});assertNull(pendingStore().readChecked())
            proxy.prepared=CountDownLatch(1);proxy.release=CountDownLatch(1);proxy.disconnect=true;compose.onNodeWithContentDescription("新建标签组").performClick();val unknownName="实体组未知-"+UUID.randomUUID();compose.onNodeWithText("标签组名称").performTextInput(unknownName);hideIme();stage="group-unknown-no-replay";compose.onNodeWithText("保存标签组").performClick();check(proxy.prepared.await(15,TimeUnit.SECONDS));proxy.release.countDown();ready();tag("entity-pending-original")
            compose.onNodeWithText("原名称：$unknownName").assertIsDisplayed();capture("group-unknown-readonly");repeat(2){compose.onNodeWithTag("entity-recheck").performClick();ready()};assertEquals(2,proxy.writes.get());assertEquals(1,direct.tagGroups().count{it.optString("name")==unknownName})
            compose.onNodeWithTag("entity-abandon-intent").performClick();compose.onNodeWithTag("entity-confirm-abandon").performClick();ready();assertEquals(2,proxy.writes.get());assertNull(pendingStore().readChecked())
            result(3,JSONObject().put("busyGroupCannotEditDismissOrQueueSecondWriteAfterAck",true).put("actualAckGroupExactlyOne",true).put("nonIdempotentUnknownGroupExactlyOne",true).put("unknownChecksAndExplicitFinishNeverPostAgain",true))
            model{it.logout()};ready();scenario!!.close()
        }}catch(error:Throwable){throw AssertionError("entity stage=$stage class=${error.javaClass.simpleName}")}
    }
}
