package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransactionDetailsUiTest {
    @get:Rule val compose=createComposeRule()
    private fun item()=JSONObject("""{"id":"101","type":3,"sourceAmount":1,"time":1700000000,"editable":true,"category":{"name":"虚构分类"},"sourceAccount":{"name":"虚构账户","currency":"CNY"},"comment":"虚构备注"}""")
    @Test fun deleteRequiresConfirmationAndCancelNeverDeletes(){
        var removed=0
        compose.setContent{FamilyTheme{LedgerTransactionDetailContent(item(),false,"",emptyList(),{}, {}, {}, {removed++})}}
        compose.onNodeWithTag("transaction-delete").performClick();compose.onNodeWithText("删除这笔流水？").assertExists()
        compose.onNodeWithText("取消").performClick();compose.runOnIdle{assertEquals(0,removed)}
        compose.onNodeWithTag("transaction-delete").performClick();compose.onNodeWithTag("confirm-transaction-delete").performClick()
        compose.runOnIdle{assertEquals(1,removed)}
    }
    @Test fun copyOpensDraftOnlyAndDoesNotMutateSource(){
        val original=item();val before=original.toString();var copies=0;var removed=0
        compose.setContent{FamilyTheme{LedgerTransactionDetailContent(original,false,"",emptyList(),{}, {}, {copies++}, {removed++})}}
        compose.onNodeWithTag("transaction-copy").performClick()
        compose.runOnIdle{assertEquals(1,copies);assertEquals(0,removed);assertEquals(before,original.toString())}
    }
    @Test fun deleteFailureRemainsVisibleAndReadOnlyRecordCannotMutate(){
        val original=item().put("editable",false)
        compose.setContent{FamilyTheme{LedgerTransactionDetailContent(original,false,"删除未完成，请重试",emptyList(),{}, {}, {}, {})}}
        compose.onNodeWithTag("transaction-detail-error").assertTextEquals("删除未完成，请重试")
        compose.onNodeWithTag("transaction-delete").assertIsNotEnabled()
        compose.onNodeWithTag("transaction-edit").assertIsNotEnabled()
        compose.onNodeWithText("−0.01").assertExists()
    }
}
