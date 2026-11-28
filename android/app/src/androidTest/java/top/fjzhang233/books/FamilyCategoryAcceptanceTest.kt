package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Opt in on an already authenticated, isolated emulator test service. */
internal fun signedInQaEnabled() = BuildConfig.DEBUG &&
    BuildConfig.API_BASE_URL in setOf("http://10.0.2.2:18090", "http://127.0.0.1:18090") &&
    InstrumentationRegistry.getArguments().getString("signedInQa") == "true"

class FamilyCategoryAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun monthlyStatisticsReturnToMemberScopedSheets() = verifyDrilldown(false)
    @Test fun yearlyStatisticsReturnToMemberScopedSheets() = verifyDrilldown(true)

    private fun verifyDrilldown(yearly: Boolean) {
        assumeTrue(signedInQaEnabled())
        val vm = ViewModelProvider(compose.activity)[BooksModel::class.java]
        compose.waitUntil(30_000) { vm.token != null && !vm.busy && vm.accounts.isNotEmpty() && vm.categories.isNotEmpty() }
        val account = vm.accounts.first { !it.optBoolean("hidden") && it.optString("currency") == "CNY" && it.optInt("type") == 1 }
        val child = vm.categories.first { it.optInt("type") == 2 && it.id("parentId") != "0" && !it.optBoolean("familyInternalTransfer") && !it.optBoolean("hidden") }
        val period = ReportPeriod(YearMonth.parse(vm.month), yearly)
        val key = UUID.randomUUID().toString()
        val body = JSONObject().put("type", 3).put("categoryId", child.id()).put("sourceAccountId", account.id())
            .put("sourceAmount", 127).put("destinationAccountId", "0").put("destinationAmount", 0)
            .put("time", period.month.atDay(1).atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond())
            .put("utcOffset", 480).put("comment", key).put("clientSessionId", key)
        val transaction = runBlocking { (vm.api.call("/api/v1/transactions/add.json", body) as JSONObject).id() }
        var previousTransaction: String? = null
        try {
            val prior = JSONObject(body.toString()).put("sourceAmount", 82).put("clientSessionId", UUID.randomUUID().toString())
                .put("time", period.shift(-1).month.atDay(1).atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond())
            previousTransaction = runBlocking { (vm.api.call("/api/v1/transactions/add.json", prior) as JSONObject).id() }
            val detail = runBlocking { vm.api.familyReportTransactions(period, "0", categoryId = child.id()) }
                .array("items").first { it.id() == transaction }
            val member = detail.getString("ownerName")
            compose.onNodeWithContentDescription("打开菜单").performClick()
            compose.onNodeWithTag("nav-家庭").performClick()
            fun await(tag: String) = compose.waitUntil(30_000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
            fun capture(name: String) {
                compose.waitForIdle()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                // Compose can be idle before the dialog window finishes compositing.
                android.os.SystemClock.sleep(500)
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val series = if (yearly) "year" else "month"
                java.io.File(instrumentation.targetContext.cacheDir, "qa-family-$series-$name.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            fun back() {
                assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                compose.waitForIdle()
            }
            await("family-member-scope")
            if (yearly) {
                compose.onNodeWithTag("report-segment-family-period-1").performClick()
                await("family-member-scope")
            }
            compose.onNodeWithTag("family-member-scope").performClick()
            compose.onNodeWithTag("family-scope-member-" + detail.id("ownerId")).performClick()
            await("family-list")
            if (!yearly) {
                compose.onNodeWithTag("family-list").performScrollToNode(hasContentDescription("全屏图表"))
                compose.onNodeWithContentDescription("全屏图表").performClick()
                await("report-chart-fullscreen")
                compose.onNodeWithTag("report-segment-fullscreen-1").performClick().assertIsSelected()
                compose.onNodeWithText("关闭", substring = false).performClick()
                await("family-list")
            }
            val parentTag = "family-report-category-" + child.id("parentId")
            compose.onNodeWithTag("family-list").performScrollToNode(hasTestTag(parentTag))
            compose.onNodeWithTag(parentTag).performClick()
            compose.onNodeWithTag("report-child-category-change-" + child.id(), useUnmergedTree = true).assertExists()
            capture("parent")
            compose.onNodeWithTag("report-parent-category-statistics").performClick()
            await("family-category-statistics")
            compose.onNodeWithText("家庭分类统计 · $member").assertIsDisplayed()
            back()
            compose.onNodeWithTag("report-child-category-" + child.id()).performClick()
            await("family-report-details")
            compose.waitUntil(30_000) { compose.onAllNodesWithText(key, substring = true).fetchSemanticsNodes().isNotEmpty() }
            capture("transactions")
            compose.onNodeWithTag("family-category-sheet-statistics").performClick()
            await("family-category-statistics")
            compose.onNodeWithText("家庭分类统计 · $member").assertIsDisplayed()
            await("family-statistics-part-" + child.id())
            capture("statistics")
            back()
            await("family-report-details")
            compose.onNodeWithText(key, substring = true).assertIsDisplayed()
            back()
            compose.onNodeWithTag("report-parent-category-chart").assertIsDisplayed()
        } finally {
            runBlocking {
                try { previousTransaction?.let { vm.api.deleteTransaction(it) } }
                finally { vm.api.deleteTransaction(transaction) }
            }
        }
    }
}
