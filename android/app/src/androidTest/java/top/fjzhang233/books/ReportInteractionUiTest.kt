package top.fjzhang233.books

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth

class ReportInteractionUiTest {
    @get:Rule val compose=createComposeRule()
    private fun custom(node:SemanticsNodeInteraction,label:String){val action=node.fetchSemanticsNode().config[SemanticsActions.CustomActions].single{it.label==label};compose.runOnIdle{check(action.action())}}
    private fun snapshot()=ReportSnapshot(ReportPeriod(YearMonth.of(2026,11)),listOf(
        ReportDay(LocalDate.of(2026,11,13),BigInteger.ZERO,BigInteger.ONE),
        ReportDay(LocalDate.of(2026,11,14),BigInteger.TEN,BigInteger.valueOf(6)),
        ReportDay(LocalDate.of(2026,11,15),BigInteger.ZERO,BigInteger.ZERO)),emptyList(),false)
    @Test fun pointerTooltipAndAccessibleDates(){
        compose.setContent{FamilyTheme{ReportChart(snapshot(),1,true)}}
        val chart=compose.onNodeWithTag("report-interactive-chart")
        chart.performTouchInput{click(Offset(width*.55f,height*.5f))}
        compose.onNodeWithTag("report-chart-tooltip").assertExists()
        listOf("11-14","收入: 0.10","支出: 0.06","结余: 0.04").forEach{compose.onNodeWithText(it).assertIsDisplayed()}
        custom(chart,"下一日期")
        compose.onNodeWithText("11-15").assertIsDisplayed()
        custom(chart,"关闭金额提示")
        compose.onNodeWithTag("report-chart-tooltip").assertDoesNotExist()
    }
    @Test fun donutPointerSelectionRotationAndAccessibleSelection(){
        val ranked=listOf(ReportCategory(JSONObject().put("id","1").put("name","零食"),BigInteger.valueOf(5),BigInteger.ZERO),ReportCategory(JSONObject().put("id","2").put("name","三餐"),BigInteger.ONE,BigInteger.ZERO))
        compose.setContent{FamilyTheme{Column{ReportDonut(ranked,"支出比例",categoryColors=rememberReportCategoryPalette(2))}}}
        val donut=compose.onNodeWithTag("report-interactive-donut")
        donut.performTouchInput{click(Offset(width*.5f- width*.15f,height*.5f))}
        donut.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"零食，0.05"))
        val before=donut.captureToImage().toPixelMap()
        donut.performTouchInput{swipe(Offset(width*.5f-width*.15f,height*.5f),Offset(width*.5f,height*.5f-height*.25f),500)}
        val after=donut.captureToImage().toPixelMap();var different=0
        for(y in 0 until before.height step 4)for(x in 0 until before.width step 4)if(before[x,y]!=after[x,y])different++
        check(different>20){"Ring gesture did not rotate rendered sectors"}
        donut.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"零食，0.05"))
        custom(donut,"下一分类")
        donut.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"三餐，0.01"))
    }
}
