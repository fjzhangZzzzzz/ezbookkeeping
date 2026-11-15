package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Existing API + real Compose actions. Explicit localhost fixture only; never production credentials. */
class TransactionActionsAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario:ActivityScenario<MainActivity>?=null
    private var stage="initial"
    private fun input():JSONObject {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.API_BASE_URL=="http://127.0.0.1:18090")
        val f=File(context.filesDir,"qa-config.json");assumeTrue(f.isFile);return JSONObject(f.readText())
    }
    private fun model(action:(BooksModel)->Unit){scenario!!.onActivity{action(ViewModelProvider(it)[BooksModel::class.java])}}
    private fun ready(){compose.waitUntil(45000){var done=false;model{done=!it.busy};done}}
    private fun tag(tag:String){compose.waitUntil(45000){compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
    private suspend fun signedApi():BooksApi {
        val fixture=input();val api=BooksApi(BuildConfig.API_BASE_URL){TokenStore(context).read()}
        TokenStore(context).write(api.login(fixture.getString("username"),fixture.getString("password"),"").getString("token"));return api
    }
    private fun launch(){scenario=ActivityScenario.launch(MainActivity::class.java);tag("ledger-list");ready()}
    private fun refresh(){model{it.reload()};ready()}
    private fun detail(id:String){compose.onNodeWithTag("ledger-list").performScrollToNode(hasTestTag("transaction-$id"));compose.onNodeWithTag("transaction-$id").performClick();tag("transaction-details")}
    private fun note(value:String){compose.onNodeWithTag("entry-comment").performTextReplacement(value);compose.onNodeWithTag("entry-comment").performImeAction();compose.waitForIdle()}
    private fun copy(id:String){detail(id);compose.onNodeWithTag("transaction-copy").performClick();tag("entry-comment");ready()}
    private fun remove(id:String,cancelFirst:Boolean=false){
        detail(id);compose.onNodeWithTag("transaction-delete").performClick();tag("confirm-transaction-delete")
        if(cancelFirst){compose.onNodeWithText("取消",substring=false).performClick();tag("transaction-details");compose.onNodeWithTag("transaction-delete").performClick();tag("confirm-transaction-delete")}
        compose.onNodeWithTag("confirm-transaction-delete").performClick();ready();tag("ledger-list")
        compose.waitUntil(45000){compose.onAllNodesWithTag("transaction-details").fetchSemanticsNodes().isEmpty()}
    }
    private suspend fun account(api:BooksApi,label:String)= (api.call("/api/v1/accounts/add.json",JSONObject().put("name",label).put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
    private fun body(type:Int,category:String,source:String,amount:Long,comment:String,destination:String="0",picture:String?=null,tagId:String?=null)=JSONObject().put("type",type).put("categoryId",category).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",source).put("sourceAmount",amount).put("destinationAccountId",destination).put("destinationAmount",if(type==4)amount else 0).put("comment",comment).put("hideAmount",false).put("pictureIds",JSONArray(if(picture==null)emptyList<String>() else listOf(picture))).put("tagIds",JSONArray(if(tagId==null)emptyList<String>() else listOf(tagId))).put("clientSessionId",UUID.randomUUID().toString())
    private suspend fun rows(api:BooksApi,account:String)=(api.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1&with_pictures=true") as JSONObject).array("items").filter{it.optInt("type")!=1}
    private fun png():ByteArray=java.io.ByteArrayOutputStream().use{out->val image=android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.rgb(55,146,250));check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));image.recycle();out.toByteArray()}
    private fun sha(value:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(value)
    private fun receipt(name:String,result:JSONObject){context.openFileOutput(name,android.content.Context.MODE_PRIVATE).use{it.write(result.toString().toByteArray());it.fd.sync()}}
    @Test fun realUiCopiesImagesAndTagsWithoutTouchingOriginalThenDeletesWithConfirmation()=runBlocking {
        try{
            stage="copy-setup";val api=signedApi();val suffix=UUID.randomUUID().toString();val own=account(api,"合成复制-"+suffix)
            val category=api.categories().first{it.optInt("type")==2&&it.id("parentId")!="0"&&!it.optBoolean("hidden")}.id()
            val tagId=(api.call("/api/v1/transaction/tags/add.json",JSONObject().put("name","合成复制标签-"+suffix).put("groupId","0")) as JSONObject).id()
            val image=png();val originalPicture=api.upload(image,"image/png").id("pictureId")
            val originalComment="合成原记录-"+suffix;val copyComment="合成复制记录-"+suffix
            val original=(api.call("/api/v1/transactions/add.json",body(3,category,own,1,originalComment,picture=originalPicture,tagId=tagId)) as JSONObject).id()
            launch();stage="copy-cancel";copy(original)
            var localOnly=false;model{localOnly=it.draftPictures.size==1&&it.draftPictures.single().startsWith("copy-")};assertTrue(localOnly)
            compose.onNodeWithTag("entry-attachment-0").performClick();compose.waitUntil(20000){compose.onAllNodesWithContentDescription("新上传图片预览").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithContentDescription("新上传图片预览").assertIsDisplayed()
            compose.onNodeWithText("完成",substring=false).performClick();compose.onNodeWithContentDescription("取消记账").performClick();ready()
            stage="copy-cancel-verify-count";assertEquals(1,rows(api,own).size);stage="copy-cancel-verify-original-image";assertArrayEquals(sha(image),sha(api.picture("/api/v1/family/pictures/$originalPicture.png")))
            stage="copy-save";copy(original);note(copyComment);compose.onNodeWithTag("entry-key-保存").performClick();ready();tag("ledger-list")
            val records=rows(api,own);assertEquals(2,records.size);val copied=records.single{it.optString("comment")==copyComment};assertEquals(1L,copied.getLong("sourceAmount"));assertEquals(own,copied.id("sourceAccountId"));assertTrue(copied.optJSONArray("tagIds").toString().contains(tagId))
            val newPicture=copied.array("pictures").single().id("pictureId");assertNotEquals(originalPicture,newPicture);assertArrayEquals(sha(image),sha(api.picture("/api/v1/family/pictures/$newPicture.png")));assertArrayEquals(sha(image),sha(api.picture("/api/v1/family/pictures/$originalPicture.png")))
            assertEquals("-2",api.accounts().single{it.id()==own}.optString("balance"))
            stage="delete-cancel-and-confirm";remove(copied.id(),true);assertEquals(1,rows(api,own).size);assertEquals("-1",api.accounts().single{it.id()==own}.optString("balance"));assertEquals(original,api.transaction(original).id());assertArrayEquals(sha(image),sha(api.picture("/api/v1/family/pictures/$originalPicture.png")))
            stage="delete-original";remove(original);assertTrue(rows(api,own).isEmpty());assertEquals("0",api.accounts().single{it.id()==own}.optString("balance"))
            receipt("qa-transaction-copy-delete-result.json",JSONObject().put("uiCopyCancelPreservesOriginalAndLocalPreview",true).put("uiCopySaveCreatesDistinctImageAndPreservesTags",true).put("originalAndCopiedImageShaEqual",true).put("twoOneFenTransactionsAndMinusTwoBalance",true).put("deleteRequiresCancelThenExplicitConfirmation",true).put("deletingCopyPreservesOriginalImage",true).put("allTestTransactionsDeletedAndBalanceZero",true))
            model{it.logout()};ready();scenario!!.close()
        }catch(error:Throwable){throw AssertionError("transaction-actions stage=$stage class=${error.javaClass.simpleName} apiCode=${(error as? ApiFailure)?.code ?: 0}")}
    }
    @Test fun realUiCopiesAndDeletesTransferWithBothBalancesRestored()=runBlocking {
        try{
            stage="transfer-setup";val api=signedApi();val suffix=UUID.randomUUID().toString();val source=account(api,"合成转出-"+suffix);val destination=account(api,"合成转入-"+suffix)
            val categories=api.categories();val income=categories.first{it.optInt("type")==1&&it.id("parentId")!="0"&&!it.optBoolean("hidden")}.id();val transfer=categories.first{it.optInt("type")==3&&it.id("parentId")!="0"&&!it.optBoolean("hidden")}.id()
            val funding=(api.call("/api/v1/transactions/add.json",body(2,income,source,100,"合成资金-"+suffix)) as JSONObject).id()
            val original=(api.call("/api/v1/transactions/add.json",body(4,transfer,source,3,"合成转账-"+suffix,destination)) as JSONObject).id()
            launch();stage="transfer-copy";copy(original);note("合成转账复制-"+suffix);compose.onNodeWithTag("entry-key-保存").performClick();ready();tag("ledger-list")
            val copied=rows(api,source).single{it.optString("comment")=="合成转账复制-"+suffix};assertEquals(4,copied.optInt("type"));assertEquals(destination,copied.id("destinationAccountId"));assertEquals(3L,copied.getLong("sourceAmount"));assertEquals(3L,copied.getLong("destinationAmount"))
            fun balances():Pair<String,String>{val accounts=runBlocking{api.accounts()};return accounts.single{it.id()==source}.optString("balance") to accounts.single{it.id()==destination}.optString("balance")}
            assertEquals("94" to "6",balances());stage="transfer-delete-copy";remove(copied.id());assertEquals("97" to "3",balances());assertEquals(original,api.transaction(original).id())
            stage="transfer-delete-original";remove(original);assertEquals("100" to "0",balances());assertFalse(rows(api,destination).any{it.optInt("type")==4})
            deleteTransactionOrAlreadyAbsent(api,funding);assertEquals("0" to "0",balances())
            receipt("qa-transaction-transfer-result.json",JSONObject().put("realUiCopyPreservesTransferDirectionAmountsAndAccounts",true).put("copiedTransferChangesBothBalances",true).put("confirmedDeleteRestoresBothBalances",true).put("originalTransferUnchangedUntilOwnDelete",true).put("pairedTransferRowsRemoved",true).put("testFundingAndBalancesCleaned",true))
            model{it.logout()};ready();scenario!!.close()
        }catch(error:Throwable){throw AssertionError("transaction-actions stage=$stage class=${error.javaClass.simpleName} apiCode=${(error as? ApiFailure)?.code ?: 0}")}
    }
    private fun pendingStore(uid:String):TokenStore {
        val scope=java.security.MessageDigest.getInstance("SHA-256").digest((BuildConfig.API_BASE_URL.trimEnd('/')+"\n"+uid).toByteArray()).joinToString(""){"%02x".format(it)}
        return TokenStore(context,"pending-transaction-$scope","pending-transaction-$scope")
    }
    private fun loginUi(name:String,password:String){
        compose.onNodeWithText("用户名或邮箱").performTextReplacement(name);compose.onNodeWithText("密码",substring=false).performTextReplacement(password)
        compose.onNodeWithText("登录",substring=false).performScrollTo().performClick();tag("ledger-list");ready()
    }
    private suspend fun recoverOnlyPreviousSyntheticCrossUserScenario(api:BooksApi){
        val identity=submissionIdentity(api.accounts(),api.family(BooksApi.month()).array("accounts"));val pending=pendingStore(identity.uid).readChecked()?:return
        val old=JSONObject(pending);check(old.getString("uid")==identity.uid&&old.getString("origin")==BuildConfig.API_BASE_URL)
        val request=JSONObject(old.getString("body"));val account=request.id("sourceAccountId");check(request.getString("comment").startsWith("合成身份待确认-"))
        check(api.accounts().single{it.id()==account}.getString("name").startsWith("合成身份原账户-"))
        stage="recover-previous-synthetic-pending-via-explicit-ui";launch();tag("confirm-pending-transaction");compose.onNodeWithTag("confirm-pending-transaction").performClick();ready()
        val previous=rows(api,account);check(previous.all{it.optString("comment")==request.getString("comment")||it.optString("comment").startsWith("合成身份原记录-")})
        previous.forEach{deleteTransactionOrAlreadyAbsent(api,it.id())};assertNull(pendingStore(identity.uid).readChecked());assertTrue(rows(api,account).isEmpty())
        model{it.logout()};ready();scenario!!.close();scenario=null;signedApi()
        receipt("qa-transaction-cross-user-prior-failure-cleanup.json",JSONObject().put("onlyVerifiedSyntheticPendingConfirmedByUi",true).put("onlyItsExactSyntheticAccountTransactionsDeleted",true).put("pendingClearedOnlyAfterAcknowledgment",true))
    }
    @Test fun crossUserNeverDisplaysOrReplaysEncryptedPendingAndCannotMutateOriginal()=runBlocking {
        try{
            stage="cross-user-setup";val first=input();val secondaryFile=File(context.filesDir,"qa-secondary.json");assumeTrue(secondaryFile.isFile);val second=JSONObject(secondaryFile.readText())
            val primary=signedApi();recoverOnlyPreviousSyntheticCrossUserScenario(primary);var secondToken:String?=null;val secondary=BooksApi(BuildConfig.API_BASE_URL){secondToken}
            secondToken=secondary.login(second.getString("loginName"),second.getString("password"),"").getString("token")
            val suffix=UUID.randomUUID().toString();val own=account(primary,"合成身份原账户-"+suffix);val other=account(secondary,"合成身份另一账户-"+suffix)
            val category=primary.categories().first{it.optInt("type")==2&&it.id("parentId")!="0"&&!it.optBoolean("hidden")}.id()
            val image=png();val picture=primary.upload(image,"image/png").id("pictureId")
            val original=(primary.call("/api/v1/transactions/add.json",body(3,category,own,1,"合成身份原记录-"+suffix,picture=picture)) as JSONObject).id()
            val identity=submissionIdentity(primary.accounts(),primary.family(BooksApi.month()).array("accounts"));val otherIdentity=submissionIdentity(secondary.accounts(),secondary.family(BooksApi.month()).array("accounts"));assertNotEquals(identity.uid,otherIdentity.uid)
            val pendingBody=body(3,category,own,1,"合成身份待确认-"+suffix)
            val stored=pendingStore(identity.uid);assertNull(stored.readChecked())
            stored.write(JSONObject().put("schema",1).put("origin",BuildConfig.API_BASE_URL.trimEnd('/')).put("uid",identity.uid).put("path","/api/v1/transactions/add.json").put("body",pendingBody.toString()).put("keepOpen",false).toString())
            val before=stored.readChecked();launch();tag("confirm-pending-transaction")
            stage="cross-user-login";model{it.logout()};ready();loginUi(second.getString("loginName"),second.getString("password"))
            var hidden=false;model{hidden=!it.transactionNeedsConfirmation&&it.restoredTransaction==null&&!it.showEditor};assertTrue(hidden)
            compose.onNodeWithTag("confirm-pending-transaction").assertDoesNotExist();compose.onNodeWithText("备注："+pendingBody.getString("comment")).assertDoesNotExist()
            assertEquals(before,stored.readChecked());assertNull(pendingStore(otherIdentity.uid).readChecked())
            stage="cross-user-deny-mutations";val current=BooksApi(BuildConfig.API_BASE_URL){TokenStore(context).read()}
            val removeFailure=runCatching{current.deleteTransaction(original)}.exceptionOrNull();assertTrue(removeFailure is ApiFailure && !removeFailure.sessionExpired)
            val pictureFailure=runCatching{current.removeUnusedPicture(picture)}.exceptionOrNull();assertTrue(pictureFailure is ApiFailure && !pictureFailure.sessionExpired)
            model{it.openTransaction(original)};ready();model{assertFalse(it.showEditor)}
            model{it.copyTransaction(original)};ready();model{assertFalse(it.showEditor)}
            assertArrayEquals(sha(image),sha(current.picture("/api/v1/family/pictures/$picture.png")))
            val shared=current.family(BooksApi.month()).array("accounts");assertEquals("-1",shared.single{it.id()==own}.optString("balanceMinor"));assertEquals("0",current.accounts().single{it.id()==other}.optString("balance"))
            assertEquals(before,stored.readChecked())
            stage="cross-user-return-original";model{it.logout()};ready();loginUi(first.getString("username"),first.getString("password"));tag("confirm-pending-transaction")
            compose.onNodeWithText("备注："+pendingBody.getString("comment")).assertIsDisplayed();compose.onNodeWithTag("confirm-pending-transaction").performClick();ready();tag("ledger-list")
            val finalApi=BooksApi(BuildConfig.API_BASE_URL){TokenStore(context).read()};val records=rows(finalApi,own);assertEquals(2,records.size);assertEquals(1,records.count{it.optString("comment")==pendingBody.getString("comment")});assertEquals("-2",finalApi.accounts().single{it.id()==own}.optString("balance"));assertNull(stored.readChecked())
            assertEquals(original,finalApi.transaction(original).id());assertArrayEquals(sha(image),sha(finalApi.picture("/api/v1/family/pictures/$picture.png")))
            records.forEach{deleteTransactionOrAlreadyAbsent(finalApi,it.id())};assertEquals("0",finalApi.accounts().single{it.id()==own}.optString("balance"))
            receipt("qa-transaction-cross-user-result.json",JSONObject().put("twoRealSyntheticUidsVerifiedByFreshProtectedJoin",true).put("logoutRetainsEncryptedOriginalPending",true).put("otherUserNeverSeesOrReplaysOriginalPending",true).put("otherUserCannotEditCopyDeleteOrRemoveOriginalPicture",true).put("originalPictureShaAndBalanceUnchangedAcrossDeniedOperations",true).put("returningOriginalUserExplicitlyConfirmsOnlyItsOwnPending",true).put("singlePendingTransactionAndBalanceVerified",true).put("testTransactionsCleaned",true))
            model{it.logout()};ready();scenario!!.close();secondary.call("/api/logout.json");Unit
        }catch(error:Throwable){throw AssertionError("transaction-actions stage=$stage class=${error.javaClass.simpleName} apiCode=${(error as? ApiFailure)?.code ?: 0}")}
    }

}
