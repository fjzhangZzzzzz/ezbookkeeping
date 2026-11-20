package top.fjzhang233.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure controlled fixture for busy-state transitions/drag; real authenticated pictures use Main acceptance. */
class DetailSheetInteractionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun busyBackIsBlockedThenNormalDragStillDismisses(){
        val busy=mutableStateOf(false);val shown=mutableStateOf(true)
        val item=JSONObject().put("id","1").put("type",3).put("sourceAmount",-1).put("editable",true).put("time",1).put("sourceAccount",JSONObject().put("name","合成账户").put("currency","CNY"))
        compose.setContent{FamilyTheme{if(shown.value)LedgerTransactionDetailContent(item,busy.value,"",emptyList(),{shown.value=false},{},{},{}){Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Blue)){Text("合成图片布局，不代表认证附件")}}}}
        compose.onNodeWithTag("transaction-details").assertIsDisplayed();compose.onNodeWithText("+0.01").assertExists()
        compose.runOnIdle{busy.value=true};compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle();compose.onNodeWithTag("transaction-details").assertIsDisplayed();compose.runOnIdle{assertTrue(shown.value);busy.value=false}
        compose.waitForIdle();compose.onNodeWithTag("transaction-details").performTouchInput{swipeDown(startY=height*.1f,endY=height*.95f,durationMillis=500)}
        compose.waitUntil(5000){!shown.value};compose.onNodeWithTag("transaction-details").assertDoesNotExist()
    }
}
