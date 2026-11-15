package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Only the three exact localhost QA records in the host-owned plan are opened. */
class NegativeEntryAcceptanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name:String){
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("No screenshot")
        File(context.cacheDir,"qa-negative-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun memoOnlyNegativeEditsPersistAndNegativeTransferIsRejected(){
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL=="http://127.0.0.1:18090")
        val plan=JSONObject(File(context.filesDir,"qa-negative-plan.json").readText())
        val privateInput=JSONObject(File(context.filesDir,"qa-config.json").readText())
        val vm=ViewModelProvider(compose.activity)[BooksModel::class.java]
        compose.runOnIdle{if(vm.token!=null)vm.logout()};compose.waitUntil(30_000){vm.token==null&&!vm.busy}
        compose.runOnIdle{vm.login(privateInput.getString("username"),privateInput.getString("password"),"")};compose.waitUntil(30_000){vm.token!=null&&!vm.busy&&vm.accounts.isNotEmpty()}
        for(kind in listOf("E","I")){
            val id=plan.getString(kind+"transaction")
            compose.runOnIdle{vm.openTransaction(id)};compose.waitUntil(30_000){!vm.busy&&vm.showEditor&&vm.editTransaction?.id()==id}
            compose.onNodeWithTag("entry-comment").performTextReplacement(plan.getString("marker")+"-"+kind+"-EDIT")
            compose.onNodeWithTag("entry-comment").performImeAction();compose.waitForIdle();screenshot(kind+"-before-save")
            compose.onNodeWithTag("entry-key-保存").performClick();compose.waitUntil(30_000){!vm.busy&&!vm.showEditor}
            compose.runOnIdle{assertTrue(vm.error.isBlank())}
        }
        val transfer=plan.getString("Ttransaction")
        compose.runOnIdle{vm.openTransaction(transfer)};compose.waitUntil(30_000){!vm.busy&&vm.showEditor&&vm.editTransaction?.id()==transfer}
        repeat(4){compose.onNodeWithTag("entry-key-⌫").performClick()}
        for(key in listOf("1","−","2"))compose.onNodeWithTag("entry-key-$key").performClick()
        compose.onNodeWithTag("entry-key-保存").performClick()
        compose.onNodeWithText("转账金额必须大于零").assertIsDisplayed();screenshot("transfer-rejected")
        compose.runOnIdle{assertTrue(vm.showEditor);assertFalse(vm.busy);assertFalse(vm.transactionNeedsConfirmation)}
        compose.onNodeWithContentDescription("取消记账").performClick();compose.waitUntil(5000){!vm.showEditor}
        File(context.filesDir,"qa-negative-result.json").writeText(JSONObject().put("passed",true).put("negativeMemoEdits",2).put("negativeTransferRejected",true).toString())
    }
}
