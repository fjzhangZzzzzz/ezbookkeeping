package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real localhost account creation only; the transaction draft is always cancelled. */
class AccountPickerFlowAcceptanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get()=context.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE)
    private val oldTheme by lazy{preferences.getString("theme",null)}
    @After fun restoreTheme(){val old=oldTheme;check(if(old==null)preferences.edit().remove("theme").commit()else preferences.edit().putString("theme",old).commit())}
    private fun capture(name:String){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(600);val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("No image");File(context.cacheDir,"qa-account-flow-$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    @Test fun lightCreateReturnsToUnchangedDraftAndExplicitChoice()=flow("light")
    @Test fun darkCreateReturnsToUnchangedDraftAndExplicitChoice()=flow("dark")
    private fun flow(mode:String){
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL=="http://127.0.0.1:18090")
        val privateFile=File(context.filesDir,"qa-config.json");check(privateFile.isFile){"Required isolated fixture missing"};val privateInput=JSONObject(privateFile.readText())
        oldTheme;check(preferences.edit().putString("theme",mode).commit())
        val vm=ViewModelProvider(compose.activity)[BooksModel::class.java]
        if(vm.token==null)compose.runOnIdle{vm.login(privateInput.getString("username"),privateInput.getString("password"),"")}
        compose.waitUntil(30_000){vm.token!=null&&!vm.busy&&vm.accounts.isNotEmpty()};check(!vm.entityBlocked&&!vm.transactionNeedsConfirmation)
        val marker="QA-account-flow-"+UUID.randomUUID().toString();val api=vm.api
        val baseline=runBlocking{api.completeSearch(LedgerSearchFilter()).items.map{it.id()}.toSet()}
        File(context.filesDir,"qa-account-flow-$mode-owner.json").writeText(JSONObject().put("marker",marker).put("createdId","").toString())
        var createdId:String?=null
        try{
            compose.onNodeWithTag("add-transaction",useUnmergedTree=true).performClick();compose.onNodeWithTag("entry-key-1").performClick();compose.onNodeWithTag("entry-comment").performTextInput(marker);compose.onNodeWithTag("entry-comment").performImeAction()
            compose.onNodeWithTag("entry-account").performClick();compose.onNodeWithTag("entry-account-picker").assertIsDisplayed();capture("$mode-accounts")
            compose.onNodeWithTag("entry-account-none").performClick();compose.onNodeWithTag("entry-key-保存").performClick();compose.onNodeWithText("请选择账户和分类").assertIsDisplayed();capture("$mode-clear-required")
            compose.runOnIdle{assertTrue(vm.showEditor);assertFalse(vm.busy)}
            assertEquals(0,runBlocking{api.completeSearch(LedgerSearchFilter(keyword=marker)).items.size})
            compose.onNodeWithTag("entry-account").performClick();compose.onNodeWithTag("entry-account-create").performClick();compose.onNodeWithTag("entry-asset-picker").assertIsDisplayed();capture("$mode-assets")
            compose.onNodeWithTag("entry-asset-cash").performClick();check(compose.onAllNodesWithText("现金").fetchSemanticsNodes().isNotEmpty());compose.onNodeWithText("账户名称").performTextReplacement(marker);capture("$mode-account-editor")
            compose.onNodeWithText("保存账户").performClick();compose.waitUntil(30_000){!vm.busy&&vm.accounts.any{it.optString("name")==marker}}
            check(!vm.entityBlocked&&!vm.entityAwaitingReview){"Entity ACK required before continuing"}
            createdId=vm.accounts.single{it.optString("name")==marker}.id();File(context.filesDir,"qa-account-flow-$mode-owner.json").writeText(JSONObject().put("marker",marker).put("createdId",createdId).toString())
            compose.onNodeWithTag("entry-account-picker").assertIsDisplayed();compose.onNodeWithTag("entry-account-none").assertIsSelected()
            val account=runBlocking{api.accounts().single{it.id()==createdId}};assertEquals(1,account.optInt("category"));assertEquals("10",account.optString("icon"));assertEquals("0",account.optString("balance"))
            compose.onNodeWithTag("entry-account-grid").performScrollToNode(hasTestTag("entry-account-$createdId"));compose.onNodeWithTag("entry-account-$createdId").performClick()
            compose.onNodeWithTag("entry-comment").assertTextEquals(marker);compose.onNodeWithTag("transaction-amount").assertTextEquals("1");compose.onNodeWithTag("entry-account").assertTextContains(marker);capture("$mode-draft-return")
            compose.onNodeWithContentDescription("取消记账").performClick();compose.waitUntil(5000){!vm.showEditor};assertEquals(baseline,runBlocking{api.completeSearch(LedgerSearchFilter()).items.map{it.id()}.toSet()})
            File(context.filesDir,"qa-account-flow-$mode-result.json").writeText(JSONObject().put("passed",true).put("sourceAccountExplicitlyCleared",true).put("emptyAccountSaveRejectedWithoutPost",true).put("presetCreatesRealZeroBalanceAccount",true).put("newAccountNotAutoSelected",true).put("draftAmountAndMemoPreserved",true).put("transactionNotSubmitted",true).toString())
        }finally{
            val cleanupDeferred=vm.busy||vm.entityBlocked||vm.entityAwaitingReview
            File(context.filesDir,"qa-account-flow-$mode-cleanup.json").writeText(JSONObject().put("cleanupDeferred",cleanupDeferred).toString())
            if(!cleanupDeferred){
            compose.runOnIdle{if(vm.showEditor&&!vm.busy&&!vm.transactionNeedsConfirmation)vm.closeTransaction()}
            runBlocking{
                val owned=api.accounts().filter{it.optString("name")==marker};check(owned.size<=1);owned.singleOrNull()?.let{item->check(createdId==null||createdId==item.id());api.call("/api/v1/accounts/delete.json",JSONObject().put("id",item.id()))}
                check(api.accounts().none{it.optString("name")==marker});check(api.completeSearch(LedgerSearchFilter()).items.map{it.id()}.toSet()==baseline)
            }
            }
        }
    }
}
