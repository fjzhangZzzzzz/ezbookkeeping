package top.fjzhang233.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Fixed, synthetic month data checks the visible date/amount bug without any account or backend. */
class CalendarUiTest {
    @get:Rule val compose=createComposeRule()
    private val month=YearMonth.of(2026,11)
    private fun snapshot():CalendarMonthAmounts {
        val rows=listOf(JSONObject().put("date","2026-11-14").put("amounts",JSONArray().put(JSONObject().put("currency","CNY").put("incomeAmount","10").put("expenseAmount","6")).put(JSONObject().put("currency","USD").put("incomeAmount","99900").put("expenseAmount","99900"))))
        return calendarAmounts(month,rows)
    }
    @Test fun datesAndSignedDailyAmountsRemainVisibleInEveryDisplayMode(){
        var shifted=0L
        compose.setContent { FamilyTheme {
            var mode by remember{mutableStateOf(CalendarAmountMode.BOTH)}
            var selected by remember{mutableStateOf(LocalDate.of(2026,11,15))}
            CalendarMonthCard(month,snapshot(),selected,1,mode,true,{mode=it},{selected=it},{shifted+=it})
        } }
        val record=compose.onNodeWithTag("calendar-day-2026-11-14")
        record.assertTextContains("14",substring=true).assertTextContains("-0.06",substring=true).assertTextContains("+0.1",substring=true)
        compose.onNodeWithTag("calendar-month-totals").assertTextContains("月收入:0.10, 月支出:0.06, 月结余:0.04")
        compose.onNodeWithTag("calendar-foreign-notice").assertIsDisplayed()
        record.performClick().assertIsSelected()
        compose.onNodeWithTag("calendar-mode-BALANCE").performClick()
        record.assertTextContains("+0.04",substring=true)
        compose.onNodeWithTag("calendar-mode-INCOME").performClick()
        record.assertTextContains("+0.1",substring=true)
        compose.onNodeWithText("-0.06").assertDoesNotExist()
        compose.onNodeWithTag("calendar-mode-EXPENSE").performClick()
        record.assertTextContains("-0.06",substring=true)
        compose.onNodeWithText("+0.1").assertDoesNotExist()
        compose.onNodeWithTag("calendar-day-2026-11-15").performClick().assertIsSelected()
        compose.onNodeWithTag("calendar-month-card").assertIsDisplayed()
        compose.onNodeWithTag("calendar-month-card").performTouchInput{swipeLeft()};assertEquals(1L,shifted)
        compose.onNodeWithTag("calendar-month-card").performTouchInput{swipeRight()};assertEquals(0L,shifted)
    }
    @Test fun leapDayAndLastDayAreVisibleAndCanBeChosen(){
        val february=YearMonth.of(2024,2)
        compose.setContent { FamilyTheme { var selected by remember{mutableStateOf(february.atDay(1))};CalendarMonthCard(february,calendarAmounts(february,emptyList()),selected,1,CalendarAmountMode.BOTH,true,{}, {selected=it},{}) } }
        compose.onNodeWithTag("calendar-day-2024-02-01").assertIsDisplayed()
        compose.onNodeWithTag("calendar-day-2024-02-29").performClick().assertIsSelected()
        compose.onNodeWithTag("calendar-day-2024-02-30").assertDoesNotExist()
    }
    @Test fun lunarLabelsMatchTheCapturedReferenceDates(){
        assertEquals("初七",calendarLunarLabel(LocalDate.of(2026,11,15)))
        assertEquals("十月",calendarLunarLabel(LocalDate.of(2026,11,9)))
        assertEquals("初六",calendarLunarLabel(LocalDate.of(2026,11,14)))
    }
    @Test fun negativeStoredFlowsRenderOneDirectionInAllCalendarModes(){
        compose.setContent{FamilyTheme{var mode by remember{mutableStateOf(CalendarAmountMode.BOTH)};CalendarMonthCard(month,CalendarMonthAmounts(mapOf(LocalDate.of(2026,11,14) to CalendarAmounts(java.math.BigInteger("-2"),java.math.BigInteger("-3"))),CalendarAmounts(java.math.BigInteger("-2"),java.math.BigInteger("-3")),false),LocalDate.of(2026,11,15),1,mode,true,{mode=it},{},{})}}
        val row=compose.onNodeWithTag("calendar-day-2026-11-14");row.assertTextContains("+0.03",substring=true).assertTextContains("-0.02",substring=true)
        compose.onNodeWithText("结余",substring=false).performClick();row.assertTextContains("+0.01",substring=true)
        compose.onNodeWithText("收入",substring=false).performClick();row.assertTextContains("-0.02",substring=true)
        compose.onNodeWithText("支出",substring=false).performClick();row.assertTextContains("+0.03",substring=true)
    }
}
