package top.fjzhang233.books

import android.os.SystemClock
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Synthetic-only report construction, real HTTP reads and actual native interactions. */
class ReportAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario:ActivityScenario<MainActivity>?=null
    private lateinit var vm:BooksModel
    private var capturePhase="none"
    private fun exists(tag:String)=compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
    private fun idle(){compose.waitUntil(60_000){!vm.busy&&vm.token!=null}}
    private fun sha(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun capture(name:String,wait:Boolean=true):Boolean {
        capturePhase="compose-idle";if(wait)compose.waitForIdle();capturePhase="automation";val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        capturePhase="before-package";if(wait)compose.waitUntil(5_000){automation.rootInActiveWindow?.packageName?.toString()==context.packageName}
        check(automation.rootInActiveWindow?.packageName?.toString()==context.packageName)
        capturePhase="screenshot";val image=automation.takeScreenshot()?:error("screenshot_missing")
        val folder=File(context.filesDir,"report-validation").apply{mkdirs()};val png=File(folder,"$name.png")
        png.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        val bitmapWidth=image.width;val bitmapHeight=image.height;image.recycle()
        capturePhase="after-package";val root=automation.rootInActiveWindow?:error("root_missing");check(root.packageName?.toString()==context.packageName)
        val logicalBounds=android.graphics.Rect();root.getBoundsInScreen(logicalBounds)
        var landscape=false;var activityWidth=0;var activityHeight=0
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
            if(activity!=null){landscape=activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;activityWidth=activity.window.decorView.width;activityHeight=activity.window.decorView.height}
        }
        capturePhase="xml";val xml=File(folder,"$name.xml");xml.outputStream().use{out->val serializer=android.util.Xml.newSerializer();serializer.setOutput(out,"UTF-8");serializer.startDocument("UTF-8",true)
            fun visit(node:android.view.accessibility.AccessibilityNodeInfo){serializer.startTag(null,"node");serializer.attribute(null,"package",node.packageName?.toString().orEmpty());serializer.attribute(null,"text",if(node.isPassword)"" else node.text?.toString().orEmpty());serializer.attribute(null,"content-desc",node.contentDescription?.toString().orEmpty());serializer.attribute(null,"class",node.className?.toString().orEmpty());val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);serializer.attribute(null,"bounds",bounds.toShortString());for(index in 0 until node.childCount)node.getChild(index)?.let(::visit);serializer.endTag(null,"node")}
            visit(root);serializer.endDocument()}
        check(automation.rootInActiveWindow?.packageName?.toString()==context.packageName)
        File(folder,"$name-receipt.json").writeText(JSONObject().put("apkSha256",sha(File(context.applicationInfo.sourceDir).readBytes())).put("pngSha256",sha(png.readBytes())).put("xmlSha256",sha(xml.readBytes())).put("beforeAndAfterPackageGuard",true).put("liveAccessibilityXml",true).put("landscape",landscape).put("logicalWidth",logicalBounds.width()).put("logicalHeight",logicalBounds.height()).put("bitmapWidth",bitmapWidth).put("bitmapHeight",bitmapHeight).put("actualActivityWidth",activityWidth).put("actualActivityHeight",activityHeight).toString())
        return landscape
    }
    private fun awaitText(value:String){compose.waitUntil(60_000){compose.onAllNodesWithText(value,substring=false).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
    private fun scroll(tag:String){compose.onNodeWithTag("report-list").performScrollToNode(hasTestTag(tag))}
    private fun back(){check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))}
    @Test fun completeNativeReport(){
        assumeTrue("Requires private synthetic fixture",BuildConfig.DEBUG&&BuildConfig.API_BASE_URL=="http://127.0.0.1:18090")
        val inputFile=File(context.filesDir,"qa-config.json");assumeTrue(inputFile.isFile);val input=JSONObject(inputFile.readText());var stage="launch"
        try{
            scenario=ActivityScenario.launch(MainActivity::class.java);scenario!!.onActivity{vm=ViewModelProvider(it)[BooksModel::class.java]}
            scenario!!.onActivity{vm.login(input.getString("username"),input.getString("password"),"")}
            idle()
            val existing=vm.accounts.lastOrNull{it.optString("name").startsWith("统计参照-")};val suffix=existing?.optString("name")?.substringAfterLast("-")?:UUID.randomUUID().toString().take(6);val name="统计参照-$suffix";var account=existing?.id().orEmpty();var usd=vm.accounts.firstOrNull{it.optString("name")=="统计外币-$suffix"}?.id().orEmpty();var food=vm.categories.firstOrNull{it.optString("name")=="统计三餐-$suffix"}?.id().orEmpty();var snack=vm.categories.firstOrNull{it.optString("name")=="统计零食-$suffix"}?.id().orEmpty();var salary=vm.categories.firstOrNull{it.optString("name")=="统计工资-$suffix"}?.id().orEmpty()
            stage="seed-isolated-api"
            if(existing==null)runBlocking{
                fun base(title:String)=JSONObject().put("name",title).put("icon","1").put("iconType",0).put("color","FF623D").put("comment","").put("clientSessionId",UUID.randomUUID().toString())
                suspend fun addAccount(title:String,currency:String)=(vm.api.call("/api/v1/accounts/add.json",base(title).put("type",1).put("category",1).put("currency",currency).put("balance","0").put("balanceTime",java.time.Instant.now().epochSecond)) as JSONObject).id()
                var categoryRevision=(vm.api.call("/api/v1/transaction/categories/revision.json") as JSONObject).getString("revision")
                suspend fun addCategory(title:String,type:Int,parent:String):String {
                    val result=vm.api.call("/api/v1/transaction/categories/add.json",base(title).put("type",type).put("parentId",parent).put("hidden",false).put("expectedRevision",categoryRevision)) as JSONObject
                    categoryRevision=(vm.api.call("/api/v1/transaction/categories/revision.json") as JSONObject).getString("revision")
                    return result.id()
                }
                account=addAccount(name,"CNY");usd=addAccount("统计外币-$suffix","USD")
                val expenseParent=addCategory("统计支出-$suffix",2,"0");food=addCategory("统计三餐-$suffix",2,expenseParent);snack=addCategory("统计零食-$suffix",2,expenseParent)
                val incomeParent=addCategory("统计收入-$suffix",1,"0");salary=addCategory("统计工资-$suffix",1,incomeParent)
                suspend fun transaction(date:String,type:Int,category:String,amount:Long,source:String=account){val time=LocalDate.parse(date).atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();vm.api.call("/api/v1/transactions/add.json",JSONObject().put("type",type).put("categoryId",category).put("time",time).put("utcOffset",480).put("sourceAccountId",source).put("sourceAmount",amount).put("destinationAccountId","0").put("destinationAmount",0).put("comment","统计对照合成样本").put("clientSessionId",UUID.randomUUID().toString()))}
                transaction("2026-11-14",3,food,1);transaction("2026-11-14",3,snack,5);transaction("2026-11-14",2,salary,10);transaction("2026-11-14",3,food,99999,usd)
                transaction("2026-01-10",3,food,100);transaction("2026-01-10",2,salary,1000);transaction("2026-03-10",3,food,200);transaction("2026-03-10",2,salary,2000);transaction("2026-02-10",2,salary,3000);transaction("2026-04-10",3,food,300)
            }
            scenario!!.onActivity{vm.reload()};idle();scenario!!.onActivity{vm.changeLedgerMonth("2026-11")};idle()
            stage="independent-api"
            runBlocking{
                val p=ReportPeriod(YearMonth.of(2026,11));val filter=ReportFilter(setOf(account,usd));val month=vm.api.report(p,vm.accounts,vm.categories,filter)
                check(month.income==BigInteger.TEN&&month.expense==BigInteger.valueOf(6));check(month.excludedForeign);check(month.ranking(2).map{it.amount}==listOf(BigInteger.valueOf(5),BigInteger.ONE))
                val year=vm.api.report(p.copy(yearly=true),vm.accounts,vm.categories,filter);check(year.activeMonths==5&&year.expense==BigInteger.valueOf(606)&&year.income==BigInteger.valueOf(6010));check(reportAverage(year.expense,5)=="1.21")
            }
            stage="report-month"
            val monthPicker = compose.onNodeWithTag("ledger-month-picker")
            val idleTitle = monthPicker.captureToImage().toPixelMap()
            monthPicker.performTouchInput { down(center) }
            SystemClock.sleep(450)
            val pressedTitle = monthPicker.captureToImage().toPixelMap()
            check((0 until idleTitle.height).all { y ->
                (0 until idleTitle.width).all { x -> idleTitle[x, y] == pressedTitle[x, y] }
            }) { "Month picker draws a pressed background" }
            monthPicker.performTouchInput { up() }
            compose.onNodeWithTag("ledger-month-dialog").assertIsDisplayed()
            back()
            compose.onNodeWithContentDescription("月报表").performClick();compose.waitUntil(30_000){exists("ledger-report")};stage="report-overview";awaitText("收支总览");stage="report-filter"
            compose.onNodeWithTag("report-filter").performClick();compose.onNodeWithText(name,substring=false).performScrollTo().performClick();compose.onNodeWithText("应用",substring=false).performScrollTo().performClick();stage="report-filter-total";awaitText("0.06");capture("01-month")
            scroll("report-segment-trend-1");compose.onNodeWithTag("report-segment-trend-1").performClick();compose.onNodeWithTag("report-chart-toggle").performScrollTo().performClick();capture("02-income-line")
            stage="interactive-chart"
            scroll("report-interactive-chart")
            compose.onNodeWithTag("report-interactive-chart").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.47f,height*.65f))}
            compose.onNodeWithTag("report-chart-tooltip").assertExists();capture("09-real-point-tooltip")
            stage="interactive-donut"
            scroll("report-interactive-donut")
            compose.onNodeWithTag("report-interactive-donut").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){check(it())}
            compose.onNodeWithTag("report-interactive-donut").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"统计支出-$suffix，0.06"));capture("10-selected-sector")
            stage="parent-category-slice"
            val parent=vm.categories.single{it.id()==snack}.id("parentId")
            scroll("report-category-$parent")
            compose.onNodeWithText("明细分类", substring = false).assertDoesNotExist()
            compose.onNodeWithText("一级分类", substring = false).assertDoesNotExist()
            compose.onNodeWithTag("report-category-$snack").assertDoesNotExist()
            capture("03-ranking")
            compose.onNodeWithTag("report-category-$parent").performClick()
            compose.waitUntil(30_000){exists("report-parent-category-sheet")}
            compose.onNodeWithTag("report-parent-category-chart").assertIsDisplayed()
            compose.onNodeWithTag("report-parent-category-statistics").assertHasClickAction()
            compose.onNodeWithTag("report-child-category-$snack").assertTextContains("83.33%")
            compose.onNodeWithTag("report-child-category-$food").assertTextContains("16.67%")
            compose.onNodeWithContentDescription("较上月增加 0.05",useUnmergedTree=true).assertExists()
            compose.onNodeWithTag("report-category-sheet").assertDoesNotExist()
            compose.onAllNodesWithText("关闭",substring=false).assertCountEquals(0)
            capture("12-parent-breakdown")

            stage="parent-child-ledger"
            val ids=runBlocking{
                val period=ReportPeriod(YearMonth.of(2026,11))
                val filter=ReportFilter(setOf(account))
                listOf(snack,food).map{vm.api.reportCategoryPage(period,it,vm.accounts,1,filter).first.single().id()}
            }
            val snackRow="report-category-row-${ids[0]}"
            val foodRow="report-category-row-${ids[1]}"
            compose.onNodeWithTag("report-child-category-$snack").performClick()
            compose.waitUntil(30_000){exists("report-category-sheet")&&exists(snackRow)}
            compose.onNodeWithTag(snackRow).assertTextContains(transactionSignedAmount(3,"5"))
            compose.onNodeWithTag(foodRow).assertDoesNotExist()
            compose.onAllNodesWithText("关闭",substring=false).assertCountEquals(0)
            capture("13-stacked-child-ledger")
            compose.onNodeWithTag("report-category-sheet-statistics").performClick()
            compose.waitUntil(30_000){exists("report-category-statistics")&&!exists("report-category-sheet")}
            compose.onNodeWithTag("category-statistics-period").assertTextContains("2026-11",substring=true)
            awaitText("账户 1")
            back();compose.waitUntil(30_000){exists("report-category-sheet")&&exists(snackRow)}
            back();compose.waitUntil(30_000){exists("report-parent-category-sheet")&&!exists("report-category-sheet")}

            stage="parent-category-statistics"
            compose.onNodeWithTag("report-parent-category-statistics").performClick();compose.waitUntil(30_000){exists("report-category-statistics")}
            val statsList=compose.onNodeWithTag("category-statistics-list")
            compose.waitUntil(30_000){exists("category-statistics-summary")}
            statsList.performScrollToNode(hasTestTag("category-statistics-slice-$snack"))
            compose.onNodeWithTag("category-statistics-slice-$snack").performClick()
            statsList.performScrollToNode(hasTestTag("category-statistics-clear-slice"))
            compose.waitUntil(30_000){exists("category-statistics-count")}
            awaitText("1 笔 · 平均每笔 0.05")
            compose.onNodeWithTag("category-statistics-clear-slice").performClick()
            awaitText("2 笔 · 平均每笔 0.03")
            capture("14-parent-statistics")
            back()
            compose.waitUntil(30_000){exists("report-parent-category-sheet")&&!exists("report-category-statistics")}
            back();compose.waitUntil(30_000){exists("ledger-report")&&!exists("report-parent-category-sheet")}
            stage="daily-drilldown"
            scroll("report-day-row-2026-11-14");compose.onNodeWithTag("report-day-row-2026-11-14").performClick()
            compose.waitUntil(30_000){exists("report-day-transactions")};awaitText(transactionSignedAmount(3,"5"))
            val expectedDay=runBlocking{vm.api.reportDayPage(LocalDate.of(2026,11,14),vm.accounts,vm.categories,1,ReportFilter(setOf(account)))}
            check(expectedDay.second==3L&&expectedDay.first.size==3&&expectedDay.first.all{it.id("sourceAccountId")==account})
            val selected=expectedDay.first.first{it.optString("sourceAmount")=="5"};compose.onNodeWithTag("report-day-transaction-${selected.id()}").performClick()
            compose.waitUntil(30_000){exists("transaction-details")};capture("11-daily-shared-detail")
            compose.onNodeWithTag("transaction-category-statistics").performClick()
            compose.waitUntil(30_000){exists("report-category-statistics")&&!exists("transaction-details")}
            compose.onNodeWithTag("category-statistics-period").assertTextContains("2026-11",substring=true)
            awaitText("账户 1")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(30_000){exists("report-day-transactions")&&!exists("report-category-statistics")};androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(30_000){exists("ledger-report")};awaitText("0.06")
            stage="year"
            compose.onNodeWithTag("report-segment-period-1").performClick();awaitText("6.06");awaitText("1.21");capture("06-year")
            stage = "fullscreen-landscape"
            scroll("report-segment-trend-2")
            compose.onNodeWithTag("report-segment-trend-2").performClick()
            compose.onNodeWithContentDescription("全屏图表").performScrollTo().performClick()
            compose.waitUntil(30_000) {
                var landscape = false
                scenario!!.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                landscape && exists("report-chart-fullscreen")
            }
            check(capture("07-fullscreen-landscape"))
            stage = "fullscreen-return"
            compose.onNodeWithText("关闭",substring=false).performClick()
            compose.waitUntil(30_000){exists("ledger-report")}
            awaitText("6.06")
            stage="custom"
            compose.onNodeWithTag("report-filter").performClick();compose.onNodeWithText("开始日期 YYYY-MM-DD").performScrollTo().performTextInput("2026-11-14");compose.onNodeWithText("结束日期 YYYY-MM-DD").performScrollTo().performTextInput("2026-11-14");compose.onNodeWithText("使用自定义周期").performScrollTo().performClick();awaitText("0.06");capture("08-custom-day")
            File(context.filesDir,"report-validation/result.json").writeText(JSONObject().put("syntheticOnly",true).put("realApiTotalsAndRankMatch",true).put("usdExcluded",true).put("yearActiveMonths",5).put("yearExpense","6.06").put("yearIncome","60.10").put("monthlyMean","1.21").put("nativeMonthYearLineSeriesCategoryAndCustom",true).put("realLandscapeActivity",true).put("productionTouched",false).toString())
        }catch(e:Throwable){runCatching{val folder=File(context.filesDir,"report-validation").apply{mkdirs()};val out=StringBuilder();fun trace(failure:Throwable,depth:Int){if(depth>8)return;out.append("exception_type=").append(failure.javaClass.name).append("\n");failure.stackTrace.forEach{out.append("\tat ").append(it.toString()).append("\n")};failure.cause?.let{trace(it,depth+1)};failure.suppressed.forEach{trace(it,depth+1)}};trace(e,0);folder.resolve("sanitized-original-failure-stack.txt").writeText(out.toString())};if(stage.startsWith("report")||stage in setOf("year","custom","fullscreen-return"))runCatching{capture("90-failure",false)}.onFailure{failure->File(context.filesDir,"report-validation").apply{mkdirs()}.resolve("capture-error.json").writeText(JSONObject().put("errorType",failure.javaClass.simpleName).put("capturePhase",capturePhase).toString())};throw AssertionError("Report synthetic stage=$stage type=${e.javaClass.simpleName} capture=$capturePhase")}
        finally{scenario?.close()}
    }
}
