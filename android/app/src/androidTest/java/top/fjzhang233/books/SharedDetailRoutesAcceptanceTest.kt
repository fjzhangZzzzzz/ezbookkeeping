package top.fjzhang233.books
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
/** Three real own-UID routes, authenticated PNG and isolated two-fen deletion. */
class SharedDetailRoutesAcceptanceTest {
 @get:Rule val compose=createEmptyComposeRule()
 private lateinit var vm:BooksModel
 private fun exists(tag:String)=compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
 private fun await(tag:String)=compose.waitUntil(45_000){exists(tag)}
 private fun idle()=compose.waitUntil(45_000){!vm.busy&&vm.token!=null}
 private fun awaitStatisticsRows(){
  val ready=hasTestTag("category-statistics-list") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"流水已加载")
  compose.waitUntil(30_000){compose.onAllNodes(ready).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
 }
 private fun closeRestoredDetail(sourceTag: String) {
  compose.onNode(hasText("共享详情路由合成样本") and hasAnyAncestor(hasTestTag("transaction-details"))).assertIsDisplayed()
  InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
  compose.waitUntil(30_000){exists(sourceTag) && !exists("transaction-details") && !exists("report-category-statistics")}
 }
 private fun shared(){await("transaction-details");for(tag in listOf("transaction-copy","transaction-edit","transaction-delete"))compose.onNodeWithTag(tag).assertHasClickAction();compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("交易图片附件").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
 @Test fun calendarAndBothReportRoutesShareDetailsAndPreserveContextAfterCancelAndDelete(){
  val signedIn = signedInQaEnabled()
  assumeTrue(signedIn || (BuildConfig.DEBUG && BuildConfig.API_BASE_URL=="http://127.0.0.1:18090"))
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val fixture=File(context.filesDir,"qa-config.json");assumeTrue(signedIn || fixture.isFile)
  var ownedSession=false;var tx:String?=null;var account="";var stage="launch";val scenario=ActivityScenario.launch(MainActivity::class.java)
  try{
   scenario.onActivity{vm=ViewModelProvider(it)[BooksModel::class.java]};if(vm.token==null){check(!signedIn);val input=JSONObject(fixture.readText());ownedSession=true;scenario.onActivity{vm.login(input.getString("username"),input.getString("password"),"")}};idle();check(!vm.transactionNeedsConfirmation)
   val day="2026-11-14";val month="2026-11";val name="共享详情路由-"+UUID.randomUUID().toString().take(8);var category=""
   val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAF0lEQVR4nGMwn/SLJMQwqmFUw69hqwEAAH7DEDfWY2cAAAAASUVORK5CYII=")
   stage="synthetic-seed"
   runBlocking{
    account=(vm.api.call("/api/v1/accounts/add.json",JSONObject().put("name",name).put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
    category=vm.api.categories().first{it.optInt("type")==2&&it.id("parentId")!="0"&&!it.optBoolean("hidden")&&!it.optBoolean("familyInternalTransfer")}.id()
    val picture=vm.api.upload(png,"image/png").id("pictureId")
    tx=(vm.api.call("/api/v1/transactions/add.json",JSONObject().put("type",3).put("categoryId",category).put("time",LocalDate.parse(day).atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",2).put("destinationAccountId","0").put("destinationAmount",0).put("comment","共享详情路由合成样本").put("pictureIds",JSONArray(listOf(picture))).put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
   }
   val createdId=checkNotNull(tx)
   scenario.onActivity{vm.reload()};idle();scenario.onActivity{vm.changeLedgerMonth(month)};idle()
   stage="calendar-shared-edit-cancel"
   stage="calendar-open";val calendarButton=compose.onNodeWithContentDescription("日历视图").fetchSemanticsNode();compose.onRoot().performTouchInput{click(calendarButton.boundsInRoot.center)};await("calendar-ledger");compose.onNodeWithTag("calendar-day-$day").performClick();compose.waitUntil(30_000){vm.ledgerDay==day&&!vm.busy};stage="calendar-detail";compose.onNodeWithTag("calendar-ledger").performScrollToNode(hasTestTag("calendar-transaction-$createdId"));compose.onNodeWithTag("calendar-transaction-$createdId").performClick();shared()
   stage="calendar-category-statistics"
   compose.onNodeWithTag("transaction-category-statistics").performClick();await("report-category-statistics")
   compose.onNodeWithTag("category-statistics-period").assertTextContains("2026年",substring=true)
   compose.onNodeWithTag("category-report-year").assertIsSelected()
   awaitStatisticsRows()
   compose.onNodeWithTag("category-statistics-list").performScrollToNode(hasTestTag("category-statistics-group-11月"))
   InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
   compose.waitUntil(30_000){exists("calendar-ledger")&&!exists("report-category-statistics")};shared()
   stage="calendar-edit";compose.onNodeWithTag("transaction-edit").performClick();compose.waitUntil(30_000){vm.showEditor&&!vm.busy};stage="calendar-edit-cancel";compose.onNodeWithContentDescription("取消记账").performClick();stage="calendar-return";await("calendar-ledger");assertEquals(day,vm.ledgerDay);assertEquals(month,vm.ledgerMonth)
   compose.onNodeWithContentDescription("返回流水").performClick();idle()
   stage="ledger-category-statistics"
   compose.onNodeWithTag("ledger-list").performScrollToNode(hasTestTag("transaction-$createdId"))
   val ledgerRow = compose.onNodeWithTag("transaction-$createdId")
   if (context.resources.configuration.fontScale == 1f) ledgerRow.assertHeightIsEqualTo(54.dp)
   val rowHeight = ledgerRow.getUnclippedBoundsInRoot().let { it.bottom - it.top }
   assertTrue(rowHeight >= 54.dp)
   ledgerRow.performClick();shared()
   compose.onNodeWithTag("transaction-category-statistics").performClick();await("report-category-statistics");awaitStatisticsRows()
   compose.onNodeWithTag("category-report-year").assertIsSelected()
   InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
   shared();closeRestoredDetail("transaction-$createdId")
   stage="search-category-statistics"
   compose.onNodeWithContentDescription("打开菜单").performClick()
   compose.onNodeWithText("搜索",substring=false).performClick();await("search-results")
   compose.onNodeWithTag("search-keyword").performTextInput("共享详情路由合成样本")
   compose.onNodeWithContentDescription("搜索").performClick();await("search-transaction-$createdId")
   compose.onNodeWithTag("search-transaction-$createdId").performClick();shared()
   compose.onNodeWithTag("transaction-category-statistics").performClick();await("report-category-statistics")
   compose.onNodeWithTag("category-report-year").assertIsSelected()
   InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
   shared();closeRestoredDetail("search-transaction-$createdId")
   compose.onNodeWithTag("search-keyword").assertTextContains("共享详情路由合成样本")
   InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
   await("ledger-list")
   stage="report-open";compose.onNodeWithContentDescription("月报表").performClick();await("ledger-report");stage="report-filter";compose.onNodeWithTag("report-filter").performClick();compose.onNodeWithText(name,substring=false).performScrollTo().performClick();compose.onNodeWithText("应用",substring=false).performScrollTo().performClick();stage="report-loaded";await("report-list")
   stage="daily-detail-return"
   compose.onNodeWithTag("report-list").performScrollToNode(hasTestTag("report-day-row-$day"))
   compose.onNodeWithTag("report-day-row-$day").performClick()
   await("report-day-transaction-$createdId")
   compose.onNodeWithTag("report-day-transaction-$createdId").performClick();shared()
   compose.onNodeWithTag("transaction-category-statistics").performClick();await("report-category-statistics")
   compose.onNodeWithContentDescription("返回明细").performClick();shared();closeRestoredDetail("report-day-transactions")
   InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
   compose.waitUntil(30_000){!exists("report-day-transactions")}
   stage="report-category"
   val parent=vm.categories.single{it.id()==category}.id("parentId")
   compose.onNodeWithTag("report-list").performScrollToNode(hasTestTag("report-category-$parent"))
   compose.onNodeWithTag("report-category-$parent").performClick()
   await("report-parent-category-sheet")
   compose.onNodeWithTag("report-child-category-$category").performClick()
   await("report-category-sheet");await("report-category-row-$createdId");compose.onNodeWithTag("report-category-row-$createdId").assertHeightIsEqualTo(rowHeight).performClick();shared()
   compose.onNodeWithTag("transaction-category-statistics").performClick();await("report-category-statistics")
   compose.onNodeWithContentDescription("返回明细").performClick();shared();closeRestoredDetail("report-category-sheet")
   stage="statistics-shared-open-row"
   compose.onNodeWithTag("report-category-sheet-statistics").performClick();await("report-category-statistics");awaitStatisticsRows();compose.onNodeWithTag("category-statistics-list").performScrollToNode(hasTestTag("category-statistics-row-$createdId"));compose.onNodeWithTag("category-statistics-row-$createdId").assertHeightIsEqualTo(rowHeight).performClick();shared()
   stage="statistics-shared-delete-refresh"
   compose.onNodeWithTag("transaction-delete").performClick();compose.onNodeWithTag("confirm-transaction-delete").performClick();compose.waitUntil(30_000){!vm.busy&&!exists("transaction-details")};await("report-category-statistics");awaitStatisticsRows();compose.waitUntil(30_000){!exists("category-statistics-row-$createdId")};compose.onNodeWithTag("category-report-month-11").assertExists()
   runBlocking{assertEquals("0",vm.api.accounts().single{it.id()==account}.optString("balance"));assertEquals(0L,vm.api.reportCategoryPage(ReportPeriod(YearMonth.parse(month)),category,vm.accounts,1,ReportFilter(setOf(account))).second)};tx=null
   compose.onNodeWithContentDescription("返回明细").performClick();await("report-category-sheet");compose.waitUntil(30_000){!exists("report-category-row-$createdId")}
   InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
   await("report-parent-category-sheet")
   InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
   compose.waitUntil(30_000){exists("ledger-report")&&!exists("report-category-sheet")}
   compose.onNodeWithTag("report-filter").performClick();compose.onNodeWithText(name,substring=false).assertExists();compose.onNodeWithText("取消",substring=false).performScrollTo().performClick();compose.onNodeWithContentDescription("返回",substring=false).performClick()
   File(context.filesDir,"shared-detail-routes-result.json").writeText(JSONObject().put("syntheticOnly",true).put("calendarSharedEditCancelKeepsDay",true).put("reportListSharedBackKeepsFilter",true).put("statisticsSharedDeleteKeepsMonth",true).put("authenticatedPngInAllThreeRoutes",true).put("deletedOnlySyntheticTwoFenAndBalanceZero",true).put("productionAccess",false).toString())
  }catch(e:Throwable){runCatching{val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(context.filesDir,"shared-detail-failure-$stage.png").outputStream().use{image?.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image?.recycle()};throw AssertionError("Shared routes stage=$stage type=${e.javaClass.simpleName}",e)}
  finally{tx?.let{id->runBlocking{runCatching{deleteTransactionOrAlreadyAbsent(vm.api,id)}}};if(account.isNotEmpty())runBlocking{vm.api.call("/api/v1/accounts/delete.json", JSONObject().put("id",account))};if(ownedSession&&::vm.isInitialized&&vm.token!=null){scenario.onActivity{vm.logout()};compose.waitUntil(30_000){!vm.busy}};scenario.close()}
 }
}
