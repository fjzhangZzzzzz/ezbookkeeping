package top.fjzhang233.books

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PendingTransactionDialogUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun originalAmountAccountMemoAndAttachmentCountAreExplicitRegardlessOfNewEditor(){
        val original=JSONObject("""{"type":3,"sourceAmount":1,"time":1700000000,"sourceAccount":{"name":"原账户","currency":"CNY"},"comment":"原备注","pictureIds":["50"],"tagIds":[]}""")
        var confirmed=0
        compose.setContent{FamilyTheme{Box{Text("新草稿：999.00");PendingTransactionDialog(original,false,"",{}, {confirmed++})}}}
        compose.onNodeWithText("金额：0.01 CNY").assertIsDisplayed();compose.onNodeWithText("账户：原账户").assertIsDisplayed()
        compose.onNodeWithText("备注：原备注").assertIsDisplayed();compose.onNodeWithText("图片：1 张 · 标签：0 个").assertIsDisplayed()
        compose.onNodeWithTag("confirm-pending-transaction").performClick();compose.runOnIdle{assertEquals(1,confirmed)}
    }
    @Test fun unavailableIdentityAllowsOnlyRefreshAndNeverPosts(){
        var refreshed=0;var posted=0
        compose.setContent{FamilyTheme{PendingTransactionDialog(null,false,"",{refreshed++},{posted++})}}
        compose.onNodeWithTag("confirm-pending-transaction").assertDoesNotExist()
        compose.onNodeWithTag("refresh-pending-identity").performClick();compose.runOnIdle{assertEquals(1,refreshed);assertEquals(0,posted)}
    }
}
