package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.time.ZoneId
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.unit.dp

class LedgerSaveFeedbackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun saveWaitsWithoutDialogAndOnlyReviewsAnUnknownResult() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.API_BASE_URL == "http://127.0.0.1:18096")
        val writes = AtomicInteger()
        val saveReceived = CountDownLatch(1)
        val respondToSave = CountDownLatch(1)
        val refreshAvailable = AtomicBoolean(false)
        val failNextSave = AtomicBoolean(false)
        val unknownRequest = AtomicReference<String>()
        val reviewedRequest = AtomicReference<String>()
        val saved = AtomicReference<JSONObject>()
        val account = JSONObject().put("id", "1").put("name", "测试账户").put("currency", "CNY").put("category", 1)
        val category = JSONObject().put("id", "3").put("parentId", "2").put("name", "午餐").put("type", 2)
        val categories = JSONObject().put("2", JSONArray().put(
            JSONObject().put("id", "2").put("parentId", "0").put("name", "餐饮").put("type", 2)
                .put("subCategories", JSONArray().put(category)),
        ))
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val result = when (request.requestUrl?.encodedPath) {
                        "/api/authorize.json" -> JSONObject().put("token", "synthetic")
                        "/api/v1/accounts/list.json" -> JSONArray().put(account)
                        "/api/v1/family/overview.json" -> JSONObject().put(
                            "accounts", JSONArray().put(JSONObject().put("id", "1").put("ownerId", "7")),
                        )
                        "/api/v1/transaction/categories/revision.json" -> JSONObject().put("revision", "1")
                        "/api/v1/transaction/categories/list.json" -> categories
                        "/api/v1/users/profile/get.json" -> JSONObject()
                        "/api/v1/transaction/tags/list.json", "/api/v1/transaction/tags/groups/list.json",
                        "/api/v1/users/external_auth/list.json" -> JSONArray()
                        "/api/v1/transactions/amounts/daily.json" -> if (saved.get() == null) JSONArray() else JSONArray().put(
                            JSONObject().put("date", today.toString()).put("amounts", JSONArray().put(
                                JSONObject().put("currency", "CNY").put("incomeAmount", 0).put("expenseAmount", 123),
                            )),
                        )
                        "/api/v1/transactions/list.json" -> {
                            if (saved.get() != null && !refreshAvailable.get()) {
                                return MockResponse().setResponseCode(500).setBody("""{"success":false}""")
                            }
                            val rows = JSONArray()
                            saved.get()?.let { entry ->
                                rows.put(entry)
                                rows.put(JSONObject(entry.toString()).put("id", "13").put("sourceAccount",
                                    JSONObject().put("name", "历史外币账户").put("currency", "USD")))
                            }
                            JSONObject().put("items", rows).put("totalCount", rows.length())
                        }
                        "/api/v1/transactions/search/summary.json" -> JSONObject().put("totalCount", 2)
                            .put("expenseMinor", 123).put("excludedForeignTransactions", 1)
                        "/api/v1/transactions/add.json" -> {
                            writes.incrementAndGet()
                            saveReceived.countDown()
                            check(respondToSave.await(20, TimeUnit.SECONDS))
                            val body = request.body.readUtf8()
                            if (failNextSave.getAndSet(false)) {
                                unknownRequest.set(body)
                                return MockResponse().setResponseCode(500).setBody("""{"success":false}""")
                            }
                            val id = if (unknownRequest.get() == null) "12" else {
                                reviewedRequest.set(body)
                                "14"
                            }
                            JSONObject(body).put("id", id).put("sourceAccount", account)
                                .put("category", category).also(saved::set)
                        }
                        "/api/logout.json" -> true
                        else -> return MockResponse().setResponseCode(404).setBody("""{"success":false}""")
                    }
                    return MockResponse().setBody(JSONObject().put("success", true).put("result", result).toString())
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 18096)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: BooksModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[BooksModel::class.java]
                    model.updateInstantEntry(false)
                    if (model.token == null) model.login("synthetic", "synthetic", "")
                }
                compose.waitUntil(15_000) { model.token != null && !model.busy && model.accounts.isNotEmpty() }
                try {
                    compose.onNodeWithTag("nav-搜索").assertIsNotDisplayed()
                    compose.onNodeWithTag("add-transaction", useUnmergedTree = true).performClick()
                    compose.runOnIdle { assertTrue("Editor did not open: ${model.error}", model.showEditor) }
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithTag("entry-key-1").fetchSemanticsNodes().isNotEmpty()
                    }
                    for (key in listOf("1", ".", "2", "3", "保存")) {
                        compose.onNodeWithTag("entry-key-$key").performClick()
                    }
                    assertTrue("Save did not reach the fixture", saveReceived.await(10, TimeUnit.SECONDS))
                    compose.onNodeWithText("核对上次保存的记录").assertDoesNotExist()
                    compose.onNodeWithTag("entry-key-保存").assertIsNotEnabled()
                    respondToSave.countDown()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText("账本数据刷新失败").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(1, writes.get())
                    compose.onNodeWithTag("transaction-12").assertIsDisplayed()
                    compose.onNodeWithText("已保存，数据刷新失败，可重试刷新").assertDoesNotExist()
                    refreshAvailable.set(true)
                    compose.onNodeWithText("重试刷新").performClick()
                    compose.waitUntil(15_000) { !model.backgroundRefreshFailed && model.transactions.size == 2 }
                    assertEquals(1, writes.get())
                    val ledger = compose.onNodeWithTag("ledger-list")
                    ledger.performScrollToNode(hasText("支:¥1.23"))
                    compose.onNodeWithText("支:¥1.23").assertIsDisplayed()
                    compose.onNodeWithText("CNY").assertDoesNotExist()
                    compose.onNodeWithText("−1.23 USD").assertIsDisplayed()

                    val originalLeft = ledger.fetchSemanticsNode().boundsInRoot.left
                    ledger.performTouchInput { swipeRight() }
                    compose.onNodeWithTag("nav-搜索").assertIsDisplayed()
                    assertTrue(ledger.fetchSemanticsNode().boundsInRoot.left > originalLeft)
                    compose.onNodeWithTag("nav-搜索").performTouchInput { swipeLeft() }
                    compose.onNodeWithTag("nav-搜索").assertIsNotDisplayed()
                    assertEquals(originalLeft, ledger.fetchSemanticsNode().boundsInRoot.left, 1f)
                    compose.onNodeWithContentDescription("打开菜单").performClick()
                    compose.onNodeWithTag("nav-搜索").performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithTag("search-transaction-12").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("search-transaction-12").assertHeightIsEqualTo(48.dp)
                    compose.onNodeWithText("支:¥1.23").assertIsDisplayed()
                    compose.onNodeWithText("−1.23 USD").assertIsDisplayed()
                    compose.onNodeWithText("CNY").assertDoesNotExist()
                    compose.onNodeWithContentDescription("返回").performClick()
                    compose.onNodeWithContentDescription("打开菜单").performClick()
                    compose.onNodeWithTag("nav-设置").performClick()
                    compose.onNodeWithTag("settings-default-currency").assertIsDisplayed()
                    compose.onNodeWithText("人民币（CNY）").assertIsDisplayed()

                    compose.onNodeWithContentDescription("返回").performClick()
                    compose.onNodeWithTag("add-transaction").performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithTag("entry-key-2").fetchSemanticsNodes().isNotEmpty()
                    }
                    failNextSave.set(true)
                    compose.onNodeWithTag("entry-key-2").performClick()
                    compose.onNodeWithTag("entry-key-再记").performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithTag("confirm-pending-transaction").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("核对上次保存的记录").assertIsDisplayed()
                    compose.onNodeWithText("金额：2.00 CNY").assertIsDisplayed()
                    assertEquals(2, writes.get())
                    compose.onNodeWithTag("confirm-pending-transaction").performClick()
                    compose.waitUntil(15_000) { !model.busy && !model.transactionNeedsConfirmation }
                    compose.onNodeWithText("核对上次保存的记录").assertDoesNotExist()
                    compose.onNodeWithTag("transaction-amount").assertTextEquals("0.0")
                    assertEquals(3, writes.get())
                    assertEquals(unknownRequest.get(), reviewedRequest.get())
                } finally {
                    respondToSave.countDown()
                    scenario.onActivity { model.logout() }
                    compose.waitUntil(15_000) { model.token == null }
                }
            }
        }
    }
}
