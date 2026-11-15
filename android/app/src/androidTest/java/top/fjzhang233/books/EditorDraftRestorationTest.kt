package top.fjzhang233.books

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tests editor UI restoration and unknown-result retries without any backend or saved login. */
class EditorDraftRestorationTest {
    @get:Rule val compose=createComposeRule()
    private class DraftApplication(context:Context):Application(){
        init{attachBaseContext(context)}
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences = super.getSharedPreferences("qa-editor-draft-$name",mode)
    }
    private fun model():BooksModel {
        val result=BooksModel(DraftApplication(InstrumentationRegistry.getInstrumentation().targetContext))
        check(result.token==null && !result.browserPending) { "Editor tests require their isolated empty preferences" }
        return result
    }
    @Test fun accountFieldsAndRequestIdentitySurviveRestorationAndRetry(){
        val vm=model();val requests=mutableListOf<JSONObject>();val restoration=StateRestorationTester(compose)
        restoration.setContent{FamilyTheme{AccountEditor(vm,null,close={},submit={_,body,_->requests+=JSONObject(body.toString())})}}
        compose.onNodeWithText("账户名称").performTextInput("虚构草稿账户")
        compose.onNodeWithText("币种代码").performTextReplacement("USD")
        compose.onNodeWithText("初始余额（USD）").performTextReplacement("1.23")
        compose.onNodeWithText("备注").performTextInput("仅编辑器恢复测试")
        compose.onNodeWithText("保存账户").performClick()
        compose.runOnIdle{assertEquals(1,requests.size)}
        // Submission deliberately leaves the editor open, as an unknown transport outcome would.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("虚构草稿账户").assertExists()
        compose.onNodeWithText("USD").assertExists()
        compose.onNodeWithText("1.23").assertExists()
        compose.onNodeWithText("仅编辑器恢复测试").assertExists()
        compose.onNodeWithText("保存账户").performClick()
        compose.runOnIdle{
            assertEquals(2,requests.size)
            assertEquals(requests[0].getString("clientSessionId"),requests[1].getString("clientSessionId"))
            assertEquals(requests[0].getLong("balanceTime"),requests[1].getLong("balanceTime"))
            assertEquals("123",requests[1].getString("balance"))
            assertEquals("USD",requests[1].getString("currency"))
        }
    }
    @Test fun categoryRequestIdentitySurvivesRetryAndRestoration(){
        val vm=model();val requests=mutableListOf<JSONObject>();val restoration=StateRestorationTester(compose)
        restoration.setContent{FamilyTheme{CatalogEditor(vm,0,null,close={},submit={_,body,_->requests+=JSONObject(body.toString())})}}
        compose.onNodeWithText("分类名称").performTextInput("虚构草稿分类")
        repeat(2){compose.onNodeWithText("保存分类").performClick();compose.waitForIdle()}
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("虚构草稿分类").assertExists()
        compose.onNodeWithText("保存分类").performClick()
        compose.runOnIdle{
            assertEquals(3,requests.size)
            assertEquals(1,requests.map{it.getString("clientSessionId")}.toSet().size)
            assertTrue(requests.all{it.getString("name")=="虚构草稿分类" && it.getInt("type")==2 && it.getString("parentId")=="0"})
        }
    }
    @Test fun tagManagementOpensTagsAndReturnsToUnsubmittedTransaction(){
        val vm=model();compose.setContent{FamilyTheme{TransactionEditor(vm)}}
        compose.onNodeWithTag("entry-key-1").performClick()
        compose.onNodeWithTag("entry-comment").performTextInput("虚构未提交草稿")
        compose.onNode(hasText("#") and hasClickAction()).performClick()
        compose.onNodeWithText("管理").performClick()
        compose.onNodeWithText("新建标签").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回记账").performClick()
        compose.onNodeWithTag("entry-comment").assertTextEquals("虚构未提交草稿")
        compose.onNodeWithTag("transaction-amount").assertTextEquals("1")
        assertTrue(vm.draftPictures.isEmpty())
    }
}
