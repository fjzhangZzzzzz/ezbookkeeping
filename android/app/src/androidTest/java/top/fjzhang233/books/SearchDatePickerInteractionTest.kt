package top.fjzhang233.books

import android.widget.DatePicker
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Local widget fixture; does not claim Main/API or real Moneyjie acceptance. */
class SearchDatePickerInteractionTest {
    @get:Rule val compose=createComposeRule()
    private fun capture(label:String){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()?:error("No screenshot")
        File(instrumentation.targetContext.cacheDir,"qa-search-date-$label.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun assertNativeDate(expected:LocalDate){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            fun find(view:View):DatePicker? {
                if(view is DatePicker)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                return null
            }
            val picker=WindowInspector.getGlobalWindowViews().mapNotNull(::find).single()
            assertEquals(expected.year,picker.year);assertEquals(expected.monthValue-1,picker.month);assertEquals(expected.dayOfMonth,picker.dayOfMonth)
        }
    }
    @Test fun quickDatesSynchronizeNativeHighlightAndConfirmedValue(){
        val shown=mutableStateOf(true);var result:String?=null
        compose.setContent{FamilyTheme{if(shown.value)SearchDatePicker("2020-02-29",{shown.value=false}){result=it;shown.value=false}}}
        compose.onNodeWithContentDescription("切换日期显示模式").performClick();compose.waitForIdle();assertNativeDate(LocalDate.of(2020,2,29))
        for((label,offset) in listOf("昨" to 1L,"今" to 0L,"前" to 2L)){
            compose.onNodeWithText(label).performClick();compose.waitForIdle();assertNativeDate(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(offset));capture("quick-$offset")
        }
        compose.onNodeWithText("确定").performClick();compose.runOnIdle{assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2).toString(),result);assertFalse(shown.value)}
    }
    @Test fun quickDateThenCancelDoesNotCommitSelection(){
        val shown=mutableStateOf(true);var commits=0
        compose.setContent{FamilyTheme{if(shown.value)SearchDatePicker("2020-02-29",{shown.value=false}){commits++;shown.value=false}}}
        compose.onNodeWithContentDescription("切换日期显示模式").performClick();compose.onNodeWithText("今").performClick();compose.waitForIdle();assertNativeDate(LocalDate.now(ZoneId.of("Asia/Shanghai")))
        compose.onNodeWithText("取消").performClick();compose.runOnIdle{assertEquals(0,commits);assertFalse(shown.value)}
    }
}
