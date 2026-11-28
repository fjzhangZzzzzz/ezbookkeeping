package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real UI tests use only the explicit isolated backend and private, synthetic handoff. */
class NativeAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private var scenario:ActivityScenario<MainActivity>?=null
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():JSONObject {
        assumeTrue("Requires isolated debug backend",BuildConfig.DEBUG&&BuildConfig.API_BASE_URL in setOf("http://10.0.2.2:18090","http://127.0.0.1:18090"))
        val input=File(context.filesDir,"qa-config.json");assumeTrue("Requires private synthetic input",input.isFile)
        return JSONObject(input.readText())
    }
    private fun exists(text:String)=compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun await(text:String){compose.waitUntil(20_000){exists(text)}}
    private fun ledgerVisible()=compose.onAllNodesWithTag("ledger-list").fetchSemanticsNodes().isNotEmpty()
    private fun awaitLedger(){compose.waitUntil(45_000){ledgerVisible()}}
    private fun launch(){scenario=ActivityScenario.launch(MainActivity::class.java)}
    private fun model(block:(BooksModel)->Unit){scenario?.onActivity{block(ViewModelProvider(it)[BooksModel::class.java])}}
    private fun ready(){compose.waitUntil(45_000){var idle=false;model{idle=!it.busy};idle}}
    private fun navigate(title:String){compose.onNodeWithContentDescription("打开菜单").performClick();compose.waitForIdle();compose.onNodeWithTag("nav-$title",useUnmergedTree=true).performClick()}
    private fun refresh(){model{it.reload()};ready()}
    private fun choose(text:String){compose.waitUntil(10_000){compose.onAllNodesWithTag("choice-list",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("choice-list",useUnmergedTree=true).performScrollToNode(hasText(text));compose.onNode(hasText(text) and hasClickAction()).performClick();compose.waitUntil(10_000){compose.onAllNodesWithTag("choice-list",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}}
    private fun enterCent(){listOf("0",".","0","1").forEach{compose.onNodeWithTag("entry-key-$it").performClick()}}
    private fun hideIme(){val ui=InstrumentationRegistry.getInstrumentation().uiAutomation;val info=ui.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.serviceInfo=info;compose.waitUntil(5000){val windows=ui.windows;windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION}&&windows.none{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}}
    private fun note(text:String){compose.onNodeWithTag("entry-comment").performTextInput(text);compose.onNodeWithTag("entry-comment").performImeAction();hideIme()}
    private fun login(input:JSONObject){compose.onNodeWithText("用户名或邮箱").performTextInput(input.getString("username"));compose.onNodeWithText("密码",substring=false).performTextInput(input.getString("password"));compose.onNodeWithText("登录",substring=false).performScrollTo().performClick();awaitLedger();ready()}
    private fun reject(token:String){runBlocking{val failure=runCatching{BooksApi(BuildConfig.API_BASE_URL){token}.accounts()}.exceptionOrNull();check(failure is ApiFailure&&failure.sessionExpired)}}
    private fun png():ByteArray=java.io.ByteArrayOutputStream().use{out->val bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.rgb(55,146,250));check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));bitmap.recycle();out.toByteArray()}
    private data class Photo(val uri:android.net.Uri,val bytes:ByteArray,val caption:String)
    private fun preparePhoto():Photo{
        check(android.os.Build.VERSION.SDK_INT>=29)
        val taken=(System.currentTimeMillis()/1000)*1000
        val values=android.content.ContentValues().apply{put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"books-qa-${java.util.UUID.randomUUID()}.png");put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.DATE_TAKEN,taken);put(android.provider.MediaStore.Images.Media.IS_PENDING,1)}
        val uri=context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("synthetic_media_insert_failed")
        val bytes=png();context.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}
        context.contentResolver.update(uri,android.content.ContentValues().apply{put(android.provider.MediaStore.Images.Media.IS_PENDING,0)},null,null)
        val stamp=java.text.SimpleDateFormat("MMM d, yyyy, h:mm:ss a",java.util.Locale.US).format(java.util.Date(taken))
        return Photo(uri,bytes,"Photo taken on $stamp")
    }
    private fun pick(photo:Photo){
        compose.onNodeWithTag("entry-pictures").performClick()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        var selected=false
        fun normalize(value:String)=value.replace('\u202f',' ').replace('\u00a0',' ')
        compose.waitUntil(15_000){
            val root=automation.rootInActiveWindow
            if(root==null||root.packageName?.toString() !in setOf("com.google.android.providers.media.module","com.android.providers.media.module"))false
            else {
                val matches=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun visit(node:android.view.accessibility.AccessibilityNodeInfo){if(node.isClickable&&normalize(node.contentDescription?.toString().orEmpty())==photo.caption)matches+=node;for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)}
                visit(root)
                if(matches.size==1){selected=matches.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);selected}else false
            }
        }
        check(selected)
        compose.waitUntil(45_000){var done=false;model{done=!it.busy&&it.draftPictures.size==1&&!it.draftAttachmentProblem};done}
    }
    private fun screenshot(name:String){check(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName);val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("screenshot_failed");File(context.cacheDir,"qa-$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    private fun safeFailure(stage:String,error:Throwable):Nothing {var diagnostic="";model{diagnostic=" busy=${it.busy} authenticated=${it.token!=null} errorPresent=${it.error.isNotBlank()}"};throw AssertionError("Isolated native stage=$stage class=${error.javaClass.simpleName} line=${error.stackTrace.firstOrNull{it.className==NativeAcceptanceTest::class.java.name}?.lineNumber?:-1}$diagnostic")}

    @Test fun loginLedgerPersistenceAndRemoteRevocation(){
        val input=fixture();var stage="api-probe";var photo:Photo?=null
        listOf("qa-api-baseline.json","qa-business-result.json").forEach{File(context.filesDir,it).delete()}
        try{
            runBlocking{var token:String?=null;val api=BooksApi(BuildConfig.API_BASE_URL){token};token=api.login(input.getString("username"),input.getString("password"),"").getString("token");api.call("/api/logout.json")}
            launch();if(!ledgerVisible())login(input)else ready()
            stage="drawer-back";compose.onNodeWithContentDescription("打开菜单").performClick();compose.waitForIdle();check(compose.onAllNodesWithTag("nav-流水",useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty());scenario!!.onActivity{it.onBackPressedDispatcher.onBackPressed()};awaitLedger()
            stage="account-create";navigate("账户");compose.onNodeWithContentDescription("新建账户").performClick()
            val name="原生验收"+System.currentTimeMillis().toString().takeLast(7);val first=name+"第一笔";val second=name+"第二笔"
            compose.onNodeWithText("账户名称").performTextInput(name);compose.onNodeWithText("保存账户").performClick();compose.waitUntil(45_000){!exists("保存账户")};ready();compose.onNodeWithTag("accounts-list").performScrollToNode(hasText(name));compose.onNodeWithText(name).assertExists()
            stage="editor-open";awaitLedger();compose.onNodeWithTag("add-transaction",useUnmergedTree=true).performClick()
            stage="account-select";compose.onNodeWithTag("entry-account").performClick();choose("$name · CNY")
            stage="category-select";var expectedCategory="";model{expectedCategory=it.categories.single{it.optString("name")==input.getString("expenseCategory")&&it.id("parentId")!="0"&&it.optInt("type")==2}.id()};compose.onNodeWithTag("entry-category-$expectedCategory").assertIsDisplayed().performClick()
            stage="native-photo-picker";photo=preparePhoto();pick(photo)
            stage="new-draft-preview";compose.onNodeWithTag("entry-attachment-0").performClick();compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("新上传图片预览",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithContentDescription("新上传图片预览",useUnmergedTree=true).performScrollTo().assertIsDisplayed();screenshot("new-draft-preview")
            stage="new-draft-remove";compose.onNodeWithText("移除此笔关联").performClick();compose.onNode(hasText("完成") and hasClickAction()).performClick();compose.onNodeWithContentDescription("图片附件，0/10").assertExists()
            stage="native-photo-reselect";pick(photo);enterCent();note(first)
            stage="save-again";compose.onNodeWithTag("entry-key-再记").performClick();compose.waitUntil(45_000){var ok=false;model{ok=!it.busy&&it.showEditor&&it.draftPictures.isEmpty()&&it.draftPreviews.isEmpty()};ok};compose.onNodeWithTag("transaction-amount").assertTextEquals("0.0");compose.onNodeWithContentDescription("图片附件，0/10").assertExists();screenshot("after-again")
            stage="save-next";enterCent();note(second);compose.onNodeWithTag("entry-key-保存").performClick();compose.waitUntil(45_000){compose.onAllNodesWithContentDescription("取消记账").fetchSemanticsNodes().isEmpty()};awaitLedger();ready()
            stage="independent-api-baseline"
            var verifiedFirstId="";val digest=java.security.MessageDigest.getInstance("SHA-256").digest(photo.bytes)
            runBlocking{var token:String?=null;val api=BooksApi(BuildConfig.API_BASE_URL){token};try{token=api.login(input.getString("username"),input.getString("password"),"").getString("token");val records=api.transactions(1);val one=records.filter{it.optString("comment")==first};val two=records.filter{it.optString("comment")==second};check(one.size==1&&two.size==1);verifiedFirstId=one.single().id();check(one.single().getLong("sourceAmount")==1L&&two.single().getLong("sourceAmount")==1L);check(one.single().id("categoryId")==expectedCategory&&two.single().id("categoryId")==expectedCategory);val account=api.accounts().single{it.optString("name")==name};check(account.optString("balance")=="-2");check(one.single().id("sourceAccountId")==account.id()&&two.single().id("sourceAccountId")==account.id());val pictures=one.single().array("pictures");check(pictures.size==1&&two.single().array("pictures").isEmpty());val picture=pictures.single();val extension=android.net.Uri.parse(picture.optString("originalUrl")).path!!.substringAfterLast('.');val bytes=api.picture("/api/v1/family/pictures/${picture.id("pictureId")}.$extension");check(java.security.MessageDigest.isEqual(digest,java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));val decoded=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size);check(decoded!=null&&decoded.width==32&&decoded.height==32);decoded.recycle()}finally{if(token!=null)runCatching{api.call("/api/logout.json")}}}
            File(context.filesDir,"qa-api-baseline.json").writeText(JSONObject().put("uniqueTwoTransactions",true).put("oneCentEach",true).put("accountBalanceMinor",-2).put("categoryAndAccountMatch",true).put("firstPictureCount",1).put("secondPictureCount",0).put("systemPickerInput",true).put("originalPngSha256",digest.joinToString(""){"%02x".format(it)}).put("serverPngShaMatches",true).put("decodedWidth",32).put("decodedHeight",32).toString())
            stage="saved-list-scroll";refresh();compose.onNodeWithTag("ledger-list").performScrollToNode(hasTestTag("transaction-$verifiedFirstId"))
            stage="saved-open-detail";compose.onNodeWithTag("transaction-$verifiedFirstId").performClick()
            stage="saved-picture-scroll";compose.onNodeWithTag("transaction-picture",useUnmergedTree=true).performScrollTo()
            stage="saved-picture-visible";compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("交易图片附件",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithContentDescription("交易图片附件",useUnmergedTree=true).assertIsDisplayed();screenshot("saved-picture-preview")
            stage="edit-cancel-new-empty";compose.onNodeWithTag("transaction-edit").performClick();await("编辑流水");compose.onNodeWithContentDescription("取消记账").performClick();compose.onNodeWithTag("add-transaction",useUnmergedTree=true).performClick();compose.onNodeWithContentDescription("图片附件，0/10").assertExists();compose.onNodeWithContentDescription("取消记账").performClick()
            stage="activity-recreation-session";scenario!!.close();launch();awaitLedger();ready();compose.onNodeWithTag("ledger-list").performScrollToNode(hasTestTag("transaction-$verifiedFirstId"));compose.onNodeWithTag("transaction-$verifiedFirstId").assertExists()
            File(context.filesDir,"qa-business-result.json").writeText(JSONObject().put("firstComment",first).put("firstTransactionId",verifiedFirstId).put("secondComment",second).put("uniqueRecords",true).put("oneCentEach",true).put("accountBalanceMinusTwoMinor",true).put("nativePhotoPicker",true).put("unsavedPreviewAndRemove",true).put("originalPngSha256",digest.joinToString(""){"%02x".format(it)}).put("serverPngShaMatches",true).put("activityRecreationPersistence",true).put("processForceStopVerified",false).toString())
            if(InstrumentationRegistry.getArguments().getString("deferRemoteRevocation")!="true")exerciseRevocations(input,verifiedFirstId)
        }catch(error:Throwable){if(stage.startsWith("saved-")||stage=="edit-cancel-new-empty")runCatching{screenshot("failure-$stage")};safeFailure(stage,error)}finally{photo?.let{runCatching{context.contentResolver.delete(it.uri,null,null)}};scenario?.close();scenario=null}
    }
    private var sessionStage="session-start"
    private fun exerciseRevocations(input:JSONObject,firstId:String){
        var old:String?=null;model{old=it.token};check(old!=null)
        sessionStage="session-ui-logout";navigate("设置");compose.onNodeWithText("退出登录",substring=false).performClick();await("退出此设备？");compose.onNodeWithText("退出",substring=false).performClick();await("用户名或邮箱");reject(old!!)
        login(input)
        var a:String?=null;var b:String?=null
        runBlocking{val api=BooksApi(BuildConfig.API_BASE_URL){null};a=api.login(input.getString("username"),input.getString("password"),"").getString("token");b=api.login(input.getString("username"),input.getString("password"),"").getString("token")}
        sessionStage="session-ui-revoke-others";navigate("登录设备");ready();compose.onNodeWithText("撤销其他所有会话").performClick();compose.onNodeWithText("确认撤销").performClick();ready();reject(a!!);reject(b!!);model{old=it.token};runBlocking{BooksApi(BuildConfig.API_BASE_URL){old}.accounts()}
        sessionStage="session-ui-revoke-current";compose.onNodeWithTag("revoke-current-session").performClick();compose.onNodeWithText("确认撤销").performClick();await("用户名或邮箱");reject(old!!)
        sessionStage="session-relogin";login(input)
        var familyPath="";model{vm->old=vm.token;val item=vm.transactions.single{it.id()==firstId};val picture=item.array("pictures").single();familyPath="/api/v1/family/pictures/${picture.id("pictureId")}."+android.net.Uri.parse(picture.optString("originalUrl")).path!!.substringAfterLast('.')}
        sessionStage="session-remote-revoke"
        runBlocking{var token:String?=null;val api=BooksApi(BuildConfig.API_BASE_URL){token};token=api.login(input.getString("username"),input.getString("password"),"").getString("token");api.call("/api/v1/tokens/revoke_all.json",JSONObject());api.call("/api/logout.json")}
        reject(old!!);val imageFailure=runBlocking{runCatching{BooksApi(BuildConfig.API_BASE_URL){old}.picture(familyPath)}.exceptionOrNull()};check(imageFailure is ApiFailure&&imageFailure.sessionExpired)
        sessionStage="session-image-fetch-after-revoke";compose.onNodeWithTag("ledger-list").performScrollToNode(hasTestTag("transaction-$firstId"));compose.onNodeWithTag("transaction-$firstId").performClick();await("用户名或邮箱")
        File(context.filesDir,"qa-revocation-result.json").writeText(JSONObject().put("logoutRevokedServerToken",true).put("otherSessionsRevoked",true).put("currentSessionUiRevoked",true).put("image401ClearedLogin",true).toString())
    }
    @Test fun currentAndRemoteSessionRevocation(){val input=fixture();val saved=File(context.filesDir,"qa-business-result.json");assumeTrue("Requires completed synthetic business phase",saved.isFile);try{launch();ready();var authenticated=false;model{authenticated=it.token!=null};if(!authenticated)login(input)else awaitLedger();exerciseRevocations(input,JSONObject(saved.readText()).getString("firstTransactionId"))}catch(error:Throwable){if(sessionStage=="session-image-fetch-after-revoke")runCatching{screenshot("failure-$sessionStage")};safeFailure(sessionStage,error)}finally{scenario?.close();scenario=null}}
    private var oidcStage="native-oidc-start"
    private val browserPackages=linkedSetOf<String>()
    private fun nativeBrowserLogin(input:JSONObject,knownTransaction:String,linkStart:Boolean=false,expireScope:Boolean=false,wrongFirst:Boolean=false):Boolean{
        var originalOrdinary:String?=null;model{originalOrdinary=it.token}
        if(linkStart)model{it.beginOidc(true)}else compose.onNodeWithText("使用 Pocket ID 登录").performClick()
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        var passwordShown=false;var passwordSubmitted=false;var browserObservedThisFlow=false
        compose.waitUntil(75_000){
            val root=ui.rootInActiveWindow
            val packageName=root?.packageName?.toString()
            when{
                packageName==context.packageName->{
                    var requiresPassword=false;var authenticated=false;var idle=false
                    model{requiresPassword=it.oidcNeedsPassword;authenticated=it.token!=null;idle=!it.busy}
                    if(requiresPassword&&!passwordSubmitted){
                        passwordShown=true
                        if(wrongFirst){
                            model{it.finishOidc("synthetic-incorrect-password", "")};ready()
                            model{check(it.error=="本地密码不正确，请重新输入");check(it.oidcCallbackToken!=null&&it.oidcNeedsPassword);check(it.token==originalOrdinary)}
                        }
                        if(expireScope){
                            var scoped:String?=null;model{scoped=it.oidcCallbackToken}
                            runBlocking{
                                val api=BooksApi(BuildConfig.API_BASE_URL){originalOrdinary};api.call("/api/oauth2/cancel.json",JSONObject(),true,scoped)
                                val failure=runCatching{api.call("/api/oauth2/authorize.json",JSONObject().put("password",input.getString("password")).put("token",originalOrdinary),true,scoped)}.exceptionOrNull() as? ApiFailure
                                File(context.filesDir,"qa-native-scoped-code.json").writeText(JSONObject().put("errorCode",failure?.code?:0).put("sessionExpired",failure?.sessionExpired?:false).toString())
                            }
                            model{it.finishOidc(input.getString("password"), "")};ready()
                            model{
                                File(context.filesDir,"qa-native-scoped-probe.json").writeText(JSONObject().put("temporaryCleared",it.oidcCallbackToken==null&&!it.oidcNeedsPassword).put("ordinaryRetained",it.token==originalOrdinary&&it.token!=null).put("restartPrompt",it.error.contains("确认已失效")).toString())
                                oidcStage="native-scope-temporary-cleared";check(it.oidcCallbackToken==null&&!it.oidcNeedsPassword)
                                oidcStage="native-scope-ordinary-retained";check(it.token==originalOrdinary&&it.token!=null)
                                oidcStage="native-scope-restart-prompt";check(it.error.contains("确认已失效"))
                            }
                            return@waitUntil true
                        }
                        compose.onNodeWithText("本地密码",substring=false).performTextInput(input.getString("password"));compose.onNodeWithText("确认关联",substring=false).performClick();passwordSubmitted=true;false
                    }
                    else if(authenticated&&idle&&browserObservedThisFlow&&ledgerVisible()){var originalData=false;model{originalData=it.transactions.any{item->item.id()==knownTransaction}&&it.externalLinked};check(originalData);scenario!!.onActivity{check(it.intent.data==null)};true}else false
                }
                packageName in setOf("com.android.chrome","com.google.android.apps.chrome","com.android.intentresolver","android")->{
                    if(packageName!!.contains("chrome")){browserPackages+=packageName;browserObservedThisFlow=true}
                    val allowed=listOf("Use without an account","Continue without an account","Accept & continue","No thanks","Not now","Got it","Skip","Don't allow","Open","Just once")
                    val nodes=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                    fun visit(node:android.view.accessibility.AccessibilityNodeInfo){nodes+=node;for(index in 0 until node.childCount)node.getChild(index)?.let(::visit)}
                    root?.let(::visit)
                    for(label in allowed){
                        val matches=nodes.filter{it.text?.toString()==label||it.contentDescription?.toString()==label}.mapNotNull{candidate->var node:android.view.accessibility.AccessibilityNodeInfo?=candidate;while(node!=null&&!node.isClickable)node=node.parent;node}.distinct()
                        if(matches.size==1){matches.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);break}
                    }
                    false
                }
                else->false
            }
        }
        check(browserPackages.isNotEmpty())
        return passwordShown
    }
    private fun logoutUi(){navigate("设置");compose.onNodeWithText("退出登录",substring=false).performClick();await("退出此设备？");compose.onNodeWithText("退出",substring=false).performClick();await("用户名或邮箱")}
    @Test fun nativeOidcSystemBrowserRoundtrip(){
        val input=fixture();val saved=File(context.filesDir,"qa-business-result.json");assumeTrue("Requires synthetic business baseline",saved.isFile)
        val known=JSONObject(saved.readText()).getString("firstTransactionId")
        try{
            launch();ready();var authenticated=false;model{authenticated=it.token!=null};if(authenticated)logoutUi()
            oidcStage="native-oidc-bound";check(!nativeBrowserLogin(input,known))
            oidcStage="native-oidc-logout";logoutUi()
            oidcStage="native-oidc-bound-relogin";check(!nativeBrowserLogin(input,known))
            oidcStage="native-oidc-unlink-synthetic";var ordinary:String?=null;model{ordinary=it.token}
            runBlocking{val api=BooksApi(BuildConfig.API_BASE_URL){ordinary};check(api.call("/api/v1/users/external_auth/unlink.json",JSONObject().put("externalAuthType","oidc").put("password",input.getString("password")))==true);val links=(api.call("/api/v1/users/external_auth/list.json") as org.json.JSONArray).objects();check(links.none{it.optString("externalAuthType")=="oidc"&&it.optBoolean("linked")})}
            logoutUi()
            oidcStage="native-oidc-first-password-binding";check(nativeBrowserLogin(input,known))
            File(context.filesDir,"qa-native-oidc-result.json").writeText(JSONObject().put("systemBrowserObserved",true).put("browserPackages",org.json.JSONArray(browserPackages.toList())).put("customSchemeReturnedToPackage",context.packageName).put("intentCallbackDataCleared",true).put("boundLogin",true).put("logoutAndNativeRelogin",true).put("syntheticUnlinkApiVerified",true).put("firstBindingPasswordConfirmed",true).put("originalPrivateTransactionStillVisible",true).put("browserCallbackUrlLogged",false).toString())
        }catch(error:Throwable){safeFailure(oidcStage,error)}finally{scenario?.close();scenario=null}
    }
    @Test fun nativeOidcWrongPasswordAndExpiredScopePreserveOrdinarySession(){
        val input=fixture();val saved=File(context.filesDir,"qa-business-result.json");assumeTrue("Requires synthetic business baseline",saved.isFile)
        val known=JSONObject(saved.readText()).getString("firstTransactionId")
        try{
            launch();ready();var authenticated=false;model{authenticated=it.token!=null};if(!authenticated)login(input)
            oidcStage="native-link-synthetic-unlink";var ordinary:String?=null;model{ordinary=it.token}
            runBlocking{val api=BooksApi(BuildConfig.API_BASE_URL){ordinary};val links=(api.call("/api/v1/users/external_auth/list.json") as org.json.JSONArray).objects();if(links.any{it.optString("externalAuthType")=="oidc"&&it.optBoolean("linked")})check(api.call("/api/v1/users/external_auth/unlink.json",JSONObject().put("externalAuthType","oidc").put("password",input.getString("password")))==true)}
            oidcStage="native-link-wrong-password-and-expired-scope";check(nativeBrowserLogin(input,known,true,true,true))
            oidcStage="native-link-restart-after-expired-scope";check(nativeBrowserLogin(input,known,true,false,true))
            model{check(it.token==ordinary&&it.externalLinked)}
            File(context.filesDir,"qa-native-oidc-errors-result.json").writeText(JSONObject().put("wrongLocalPasswordFriendly",true).put("wrongPasswordRetainsScopedRetry",true).put("cancelledScopedAuthorizationCleared",true).put("ordinarySessionRetained",true).put("freshBrowserLinkSucceeded",true).put("noReplacementOrdinarySession",true).toString())
        }catch(error:Throwable){safeFailure(oidcStage,error)}finally{scenario?.close();scenario=null}
    }
    @Test fun tagDraftSelectionSaveCancelAndManageReturn(){
        val input=fixture();var stage="tag-fixture"
        try{
            launch();ready();var ordinary:String?=null;model{ordinary=it.token};if(ordinary==null){login(input);model{ordinary=it.token}}
            val tagIds=runBlocking{
                val api=BooksApi(BuildConfig.API_BASE_URL){ordinary}
                val all=api.tags();val result=listOf("原生选择验收A","原生选择验收B").map{name->all.firstOrNull{it.optString("name")==name}?.id()?: (api.call("/api/v1/transaction/tags/add.json",JSONObject().put("name",name).put("groupId","0")) as JSONObject).id()}
                val categories=api.categories();val parents=categories.filter{it.optString("name") in setOf("钱迹参照支出","钱迹参照收入")&&it.id("parentId")=="0"}.map{it.id()}.toSet()
                val icons=mapOf("孩子" to "212","宠物" to "580","话费网费" to "400","烟酒" to "41","医疗" to "800","水电煤" to "231","其它" to "920","工资" to "5200","其他收入" to "5200")
                categories.filter{it.id("parentId") in parents&&icons.containsKey(it.optString("name"))}.forEach{item->val icon=icons.getValue(item.optString("name"));if(item.id("icon")!=icon) api.call("/api/v1/transaction/categories/modify.json",JSONObject(item.toString()).put("icon",icon))}
                result
            }
            model{it.reload()};ready();awaitLedger();compose.onNodeWithTag("add-transaction",useUnmergedTree=true).performClick();enterCent();note("标签草稿验收")
            fun open(){compose.onNodeWithText("#",substring=false).performClick();compose.onNodeWithTag("entry-tags-picker").assertExists()}
            stage="tag-save";open();compose.onNodeWithTag("entry-tags-option-${tagIds[0]}",useUnmergedTree=true).performClick();compose.onNodeWithTag("entry-tags-save",useUnmergedTree=true).performClick();open();compose.onNodeWithTag("entry-tags-option-${tagIds[0]}",useUnmergedTree=true).assertIsOn()
            stage="tag-cancel";compose.onNodeWithTag("entry-tags-option-${tagIds[1]}",useUnmergedTree=true).performClick();check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));compose.waitUntil(5000){compose.onAllNodesWithTag("entry-tags-picker").fetchSemanticsNodes().isEmpty()};open();compose.onNodeWithTag("entry-tags-option-${tagIds[0]}",useUnmergedTree=true).assertIsOn();compose.onNodeWithTag("entry-tags-option-${tagIds[1]}",useUnmergedTree=true).assertIsOff()
            stage="tag-manage-return";compose.onNodeWithTag("entry-tags-option-${tagIds[1]}",useUnmergedTree=true).performClick();compose.onNodeWithTag("entry-tags-manage",useUnmergedTree=true).performClick();await("分类与标签");compose.onNodeWithContentDescription("返回记账").performClick();compose.onNodeWithTag("entry-comment").assertTextEquals("标签草稿验收");open();tagIds.forEach{compose.onNodeWithTag("entry-tags-option-$it",useUnmergedTree=true).assertIsOn()};screenshot("tag-selected-green");compose.onNodeWithTag("entry-tags-save",useUnmergedTree=true).performClick()
            File(context.filesDir,"qa-tag-draft-result.json").writeText(JSONObject().put("saveRetainedSelection",true).put("backCancelledPendingOnly",true).put("manageRetainedSelectionAndMemo",true).put("noTransactionSubmitted",true).put("syntheticFixtureOnly",true).toString())
        }catch(error:Throwable){safeFailure(stage,error)}finally{scenario?.close();scenario=null}
    }
    @Test fun newDraftPreviewStableCapture(){
        val input=fixture();var stage="draft-preview-login";var photo:Photo?=null
        try{
            launch();ready();var authenticated=false;model{authenticated=it.token!=null};if(!authenticated)login(input)
            stage="draft-preview-photo-picker";awaitLedger();compose.onNodeWithTag("add-transaction",useUnmergedTree=true).performClick();compose.waitUntil(5000){compose.onAllNodesWithTag("entry-pictures").fetchSemanticsNodes().isNotEmpty()};photo=preparePhoto();pick(photo)
            compose.onNodeWithTag("entry-attachment-0").performClick()
            stage="draft-preview-visible-render";compose.waitUntil(20_000){compose.onAllNodesWithContentDescription("新上传图片预览",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithContentDescription("新上传图片预览",useUnmergedTree=true).performScrollTo().assertIsDisplayed();compose.waitForIdle()
            var stableFrames=0
            compose.waitUntil(10_000){
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:return@waitUntil false
                var blue=0
                for(y in 300 until bitmap.height step 3)for(x in 60 until bitmap.width-60 step 3){val color=bitmap.getPixel(x,y);if(android.graphics.Color.red(color) in 40..75&&android.graphics.Color.green(color) in 120..170&&android.graphics.Color.blue(color)>200)blue++}
                bitmap.recycle();if(blue>3000)stableFrames++else stableFrames=0;stableFrames>=3
            }
            val actualRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?:error("missing_actual_window")
            check(actualRoot.packageName?.toString()==context.packageName)
            File(context.cacheDir,"qa-final-new-draft-visible.xml").outputStream().use{out->
                val xml=android.util.Xml.newSerializer();xml.setOutput(out,"UTF-8");xml.startDocument("UTF-8",true);xml.startTag(null,"hierarchy")
                fun emit(node:android.view.accessibility.AccessibilityNodeInfo){
                    val rect=android.graphics.Rect();node.getBoundsInScreen(rect);xml.startTag(null,"node")
                    for((key,value) in listOf("package" to node.packageName?.toString().orEmpty(),"text" to node.text?.toString().orEmpty(),"content-desc" to node.contentDescription?.toString().orEmpty(),"resource-id" to node.viewIdResourceName.orEmpty(),"class" to node.className?.toString().orEmpty(),"bounds" to "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]","clickable" to node.isClickable.toString(),"visible-to-user" to node.isVisibleToUser.toString()))xml.attribute(null,key,value)
                    for(index in 0 until node.childCount)node.getChild(index)?.let(::emit)
                    xml.endTag(null,"node")
                }
                emit(actualRoot);xml.endTag(null,"hierarchy");xml.endDocument()
            }
            screenshot("final-new-draft-visible")
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName)
            File(context.filesDir,"qa-final-draft-visual-result.json").writeText(JSONObject().put("actualPhotoPickerSelection",true).put("newUnsavedDraft",true).put("previewImageDisplayed",true).put("bluePixelsVisibleAcrossThreeFrames",true).put("noTransactionSubmitted",true).put("sourcePngSha256",java.security.MessageDigest.getInstance("SHA-256").digest(photo.bytes).joinToString(""){"%02x".format(it)}).toString())
            // Optional bounded capture window for independent package/XML/PNG guards.
            if(InstrumentationRegistry.getArguments().getString("holdPreviewForExternalCapture")=="true") android.os.SystemClock.sleep(20_000)
        }catch(error:Throwable){safeFailure(stage,error)}finally{photo?.let{context.contentResolver.delete(it.uri,null,null)};scenario?.close();scenario=null}
    }
    @Test fun validPngApiRoundtrip() {
        assumeTrue("Requires isolated emulator build",BuildConfig.DEBUG&&BuildConfig.API_BASE_URL in setOf("http://10.0.2.2:18090","http://127.0.0.1:18090"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,"qa-config.json");assumeTrue("Requires private synthetic input",file.isFile)
        val fixture=JSONObject(file.readText());var stage="api-login"
        runBlocking {
            var token:String?=null;val api=BooksApi(BuildConfig.API_BASE_URL){token}
            try {
                token=api.login(fixture.getString("username"),fixture.getString("password"),"").getString("token")
                stage="png-generate"
                val png=java.io.ByteArrayOutputStream().use { output->val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));bitmap.recycle();output.toByteArray() }
                stage="png-upload";val picture=api.upload(png,"image/png")
                stage="api-sample-account"
                val account=api.call("/api/v1/accounts/add.json",JSONObject().put("name","附件API验收"+System.currentTimeMillis().toString().takeLast(6)).put("category",1).put("icon","1").put("iconType",0).put("color","6CA8F5").put("comment","隔离合成验收").put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",java.time.Instant.now().epochSecond).put("clientSessionId",java.util.UUID.randomUUID().toString())) as JSONObject
                val category=api.categories().single { it.optString("name")==fixture.getString("expenseCategory")&&it.id("parentId")!="0"&&it.optInt("type")==2 }
                stage="api-sample-transaction"
                api.call("/api/v1/transactions/add.json",JSONObject().put("type",3).put("categoryId",category.id()).put("time",java.time.Instant.now().epochSecond).put("utcOffset",480).put("sourceAccountId",account.id()).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("tagIds",org.json.JSONArray()).put("pictureIds",org.json.JSONArray().put(picture.id("pictureId"))).put("comment","隔离PNG解码验收").put("clientSessionId",java.util.UUID.randomUUID().toString()))
                stage="png-api-read-decode";val read=api.picture("/api/v1/family/pictures/${picture.id("pictureId")}.png")
                val decoded=android.graphics.BitmapFactory.decodeByteArray(read,0,read.size);check(decoded!=null&&decoded.width==16&&decoded.height==16);decoded.recycle()
            } catch(e:Throwable) { throw AssertionError("Isolated PNG API stage=$stage class="+e.javaClass.simpleName) }
            finally { if(token!=null) runCatching { api.call("/api/logout.json") } }
        }
    }
    @Test fun aboutIsAvailableWithoutLogin(){fixture();try{launch();if(compose.onAllNodesWithContentDescription("打开菜单").fetchSemanticsNodes().isNotEmpty()){navigate("设置");compose.onNodeWithText("关于").performClick()}else compose.onNodeWithContentDescription("关于").performClick();await("当前版本")}finally{scenario?.close();scenario=null}}
}
