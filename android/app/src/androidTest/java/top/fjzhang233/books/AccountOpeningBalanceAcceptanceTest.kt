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
import java.math.BigInteger
import java.util.UUID

/** Nonzero balances via the actual account editor; only uniquely named QA accounts are removed. */
class AccountOpeningBalanceAcceptanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get()=context.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE)
    private val oldTheme by lazy{prefs.getString("theme",null)}
    @After fun restoreTheme(){val old=oldTheme;check(if(old==null)prefs.edit().remove("theme").commit()else prefs.edit().putString("theme",old).commit())}
    private fun capture(name:String){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(600);val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("No image");File(context.cacheDir,"qa-account-balance-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun totals(value:JSONObject)=listOf("incomeMinor","expenseMinor","netMinor").map{value.getJSONObject("totals").getString(it)}
    private fun sumBalances(value:JSONObject)=value.array("accounts").filter{it.optString("currency")=="CNY"}.fold(BigInteger.ZERO){a,item->a+BigInteger(item.getString("balanceMinor"))}
    private data class Case(val key:String,val amount:String,val category:Int,val stored:Long)
    @Test fun nonzeroDebtAndAssetBalancesReachFamilyWithCorrectSignsAndEditNeverResetsBalance(){
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL=="http://127.0.0.1:18090")
        val input=JSONObject(File(context.filesDir,"qa-config.json").readText());oldTheme;check(prefs.edit().putString("theme","light").commit())
        val vm=ViewModelProvider(compose.activity)[BooksModel::class.java]
        if(vm.token==null)compose.runOnIdle{vm.login(input.getString("username"),input.getString("password"),"")}
        compose.waitUntil(30_000){vm.token!=null&&!vm.busy&&vm.accounts.isNotEmpty()};check(!vm.entityBlocked&&!vm.transactionNeedsConfirmation)
        File(context.filesDir,"qa-account-balance-result.json").writeText(JSONObject().put("passed",false).put("inProgress",true).toString())
        val api=vm.api;val prefix="QA-account-balance-"+UUID.randomUUID().toString().replace("-","").take(16)
        val cases=listOf(Case("credit-card","12.34",3,-1234),Case("borrow","56.78",5,-5678),Case("cash","100.00",1,10000))
        val ids=linkedMapOf<String,String>();val baseline=runBlocking{api.completeSearch(LedgerSearchFilter()).items.map{it.id()}.toSet()}
        val originalBalances=runBlocking{api.accounts().associate{it.id() to it.optString("balance")}}
        val familyBefore=runBlocking{api.family(BooksApi.month())};val owner=File(context.filesDir,"qa-account-balance-owner.json")
        fun record(){owner.writeText(JSONObject().put("prefix",prefix).put("accounts",JSONObject(ids as Map<*,*>)).toString())};record()
        try{
            for(case in cases){
                val name="$prefix-${case.key}";check(name.length<=64)
                compose.onNodeWithTag("add-transaction",useUnmergedTree=true).performClick();compose.onNodeWithTag("entry-account").performClick();compose.onNodeWithTag("entry-account-create").performClick()
                compose.onNodeWithTag("entry-asset-groups").performScrollToNode(hasTestTag("entry-asset-${case.key}"));compose.onNodeWithTag("entry-asset-${case.key}").performClick()
                compose.onNodeWithText("账户名称").performTextReplacement(name);compose.onNodeWithText("账户名称").assertTextContains(name)
                val label=if(case.category in listOf(3,5))"欠款余额（CNY）" else "初始余额（CNY）"
                compose.onNodeWithText(label).performTextReplacement(case.amount);capture("${case.key}-input")
                if(case.category==3){
                    compose.onNodeWithText(label).performTextReplacement("-12.34");compose.onNodeWithText("保存账户").performClick();compose.onNodeWithText("欠款余额不能为负数").assertIsDisplayed()
                    assertTrue(runBlocking{api.accounts().none{it.optString("name")==name}});compose.onNodeWithText(label).performTextReplacement(case.amount)
                }
                compose.onNodeWithText("保存账户").performClick();compose.waitUntil(30_000){!vm.busy&&vm.accounts.any{it.optString("name")==name}}
                val created=runBlocking{api.accounts().single{it.optString("name")==name}};ids[case.key]=created.id();record();assertEquals(case.category,created.optInt("category"));assertEquals(case.stored.toString(),created.optString("balance"))
                compose.onNodeWithTag("entry-account-none").performClick();compose.onNodeWithContentDescription("取消记账").performClick();compose.waitUntil(5000){!vm.showEditor}
            }
            val familyAfter=runBlocking{api.family(BooksApi.month())}
            for(case in cases)assertEquals(case.stored.toString(),familyAfter.array("accounts").single{it.id()==ids[case.key]}.optString("balanceMinor"))
            assertEquals(BigInteger("3088"),sumBalances(familyAfter)-sumBalances(familyBefore));assertEquals(totals(familyBefore),totals(familyAfter))
            compose.onNodeWithContentDescription("打开菜单").performClick();compose.onNodeWithText("家庭",substring=false).performClick();compose.onNodeWithTag("family-list").assertExists()
            for(case in cases){
                val id=ids.getValue(case.key);compose.onNodeWithTag("family-list").performScrollToNode(hasTestTag("family-account-$id"))
                val value="CNY "+money(case.stored.toString());compose.onNodeWithTag("family-account-$id").assert(hasText(value) or hasAnyDescendant(hasText(value)));capture("family-${case.key}")
            }
            compose.onNodeWithContentDescription("返回").performClick();compose.onNodeWithContentDescription("打开菜单").performClick();compose.onNodeWithText("设置",substring=false).performClick();compose.onNodeWithText("账户",substring=false).performClick()
            val creditName="$prefix-credit-card";compose.onNodeWithTag("accounts-list").performScrollToNode(hasText(creditName));compose.onNodeWithText(creditName).performClick()
            compose.onNodeWithText("编辑资料不会调整余额。余额随记账更新。").assertIsDisplayed();compose.onNodeWithText("欠款余额（CNY）").assertDoesNotExist()
            val beforeAdjustment=runBlocking{api.completeSearch(LedgerSearchFilter(accountIds=setOf(ids.getValue("credit-card")))).items.map{it.id()}.toSet()}
            compose.onNodeWithText("备注").performTextReplacement("QA memo edit keeps opening debt");compose.onNodeWithText("保存账户").performClick();compose.waitUntil(30_000){!vm.busy&&compose.onAllNodesWithText("编辑账户资料").fetchSemanticsNodes().isEmpty()}
            compose.runOnIdle{check(!vm.entityBlocked&&!vm.entityAwaitingReview){"Credit edit requires a confirmed ACK; closed form is not success"}}
            val editedCredit=runBlocking{api.accounts().single{it.id()==ids["credit-card"]}}
            assertEquals("QA memo edit keeps opening debt",editedCredit.optString("comment"));assertEquals("-1234",editedCredit.optString("balance"))
            assertEquals(beforeAdjustment,runBlocking{api.completeSearch(LedgerSearchFilter(accountIds=setOf(ids.getValue("credit-card")))).items.map{it.id()}.toSet()})
            assertEquals(totals(familyBefore),totals(runBlocking{api.family(BooksApi.month())}))
            File(context.filesDir,"qa-account-balance-result.json").writeText(JSONObject().put("passed",true).put("creditMinor","-1234").put("borrowMinor","-5678").put("assetMinor","10000").put("familyNetDeltaMinor","3088").put("negativeDebtInputRejectedBeforePost",true).put("familyMonthIncomeExpenseUnchanged",true).put("editLeavesBalanceAndOpeningAdjustmentUnchanged",true).put("editMemoAckAndReadback",true).toString())
        }finally{
            compose.runOnIdle{if(vm.showEditor&&!vm.busy&&!vm.transactionNeedsConfirmation)vm.closeTransaction()}
            val cleanup=File(context.filesDir,"qa-account-balance-cleanup.json")
            if(vm.entityBlocked||vm.entityAwaitingReview){
                // Do not erase an account still referenced by a fail-closed pending request.
                owner.writeText(JSONObject().put("prefix",prefix).put("accounts",JSONObject(ids as Map<*,*>)).put("cleanupDeferred",true).toString())
                cleanup.writeText(JSONObject().put("cleanupDeferred",true).put("pendingEntityPreserved",true).toString())
            }else runBlocking{
                val owned=api.accounts().filter{item->cases.any{item.optString("name")=="$prefix-${it.key}"}}
                for(item in owned){
                    val rows=api.completeSearch(LedgerSearchFilter(accountIds=setOf(item.id()))).items;check(rows.size<=1&&rows.all{it.optInt("type")==1})
                    api.call("/api/v1/accounts/delete.json",JSONObject().put("id",item.id()))
                }
                val ownedIds=ids.values.toSet();val remainingRows=api.completeSearch(LedgerSearchFilter()).items.filter{it.optString("sourceAccountId") !in ownedIds&&it.optString("destinationAccountId") !in ownedIds};check(remainingRows.map{it.id()}.toSet()==baseline);check(api.accounts().filter{it.id() !in ownedIds}.associate{it.id() to it.optString("balance")}==originalBalances.filterKeys{it !in ownedIds});check(api.accounts().filter{it.id() in ownedIds}.all{it.optBoolean("hidden")})
                check(totals(api.family(BooksApi.month()))==totals(familyBefore))
                cleanup.writeText(JSONObject().put("cleanupDeferred",false).put("originalRowsAndBalancesVerified",true).put("pendingEntityPreserved",false).toString())
            }
        }
    }
}
