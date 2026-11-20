package top.fjzhang233.books

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LedgerControlInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reportSegmentClipsPressAndKeepsFullHitArea() {
        var clicks = 0
        compose.setContent {
            FamilyTheme {
                Surface {
                    var selected by remember { mutableIntStateOf(0) }
                    ReportSegments(listOf("支出", "收入", "全部"), selected, "test") {
                        selected = it
                        clicks++
                    }
                }
            }
        }
        val all = compose.onNodeWithTag("report-segment-test-2")
        val idle = all.captureToImage().toPixelMap()
        all.performTouchInput { down(center) }
        SystemClock.sleep(450)
        val held = all.captureToImage().toPixelMap()
        for (x in listOf(3, idle.width / 2, idle.width - 4)) {
            for (y in listOf(3, idle.height - 4)) assertEquals(idle[x, y], held[x, y])
        }
        assertTrue((idle.width / 4..idle.width * 3 / 4).any { x -> idle[x, idle.height / 2] != held[x, held.height / 2] })
        all.performTouchInput { cancel() }
        SystemClock.sleep(400)
        val released = all.captureToImage().toPixelMap()
        assertEquals(idle[idle.width / 4, idle.height / 2], released[released.width / 4, released.height / 2])
        all.assertIsNotSelected()
        compose.runOnIdle { assertEquals(0, clicks) }

        all.performTouchInput { click(Offset(center.x, 2f)) }
        all.assertIsSelected()
        compose.onNodeWithTag("report-segment-test-0").assertIsNotSelected()
        all.performTouchInput { click(center); click(center) }
        compose.runOnIdle { assertEquals(3, clicks) }
    }

    @Test fun keyboardFocusAndActivationReachOuterControl() {
        var clicks = 0
        compose.setContent {
            FamilyTheme {
                LedgerControl(Modifier.testTag("keyboard-control"), onClick = { clicks++ }) { interactions ->
                    Box(Modifier.size(44.dp, 28.dp).ledgerControlFeedback(interactions), contentAlignment = Alignment.Center) {
                        Text("统计")
                    }
                }
            }
        }
        val control = compose.onNodeWithTag("keyboard-control")
        val idle = control.captureToImage().toPixelMap()
        val input = InstrumentationRegistry.getInstrumentation()
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        control.assertIsFocused()
        val focused = control.captureToImage().toPixelMap()
        assertTrue((idle.height / 5..idle.height * 2 / 5).any { y -> idle[idle.width / 2, y] != focused[focused.width / 2, y] })
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
