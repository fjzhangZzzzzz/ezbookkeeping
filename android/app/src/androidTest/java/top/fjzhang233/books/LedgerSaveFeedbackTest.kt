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
        val slowRetry = AtomicBoolean(false)
        val holdNextLedgerRead = AtomicBoolean(false)
        val ledgerReadStarted = CountDownLatch(1)
        val releaseLedgerRead = CountDownLatch(1)
        val revocations = AtomicInteger()
        val failTags = AtomicBoolean(false)
        val delayBalanceAfterAck = AtomicBoolean(false)
        val holdBalance = AtomicBoolean(false)
        val balanceStarted = CountDownLatch(1)
        val releaseBalance = CountDownLatch(1)
        val startupReadStarted = CountDownLatch(1)
        val releaseStartupRead = CountDownLatch(1)
        val categoryReads = AtomicInteger()
        val failNextSave = AtomicBoolean(false)
        val unknownRequest = AtomicReference<String>()
        val reviewedRequest = AtomicReference<String>()
        val saved = AtomicReference<JSONObject>()
        val account = JSONObject().put("id", "1").put("name", "测试账户").put("currency", "CNY").put("category", 1)
        val category = JSONObject().put("id", "3").put("parentId", "2").put("name", "午餐").put("type", 2)
        val categories = JSONObject().put("2", JSONArray().put(
            JSONObject().put("id", "2").put("parentId", "0").put("name", "餐饮").put("type", 2)
                .put("subCategories", JSONArray().put(category)
                    .put(JSONObject().put("id", "4").put("parentId", "2").put("name", "茶饮").put("type", 2))),
        )).put("1", JSONArray().put(
            JSONObject().put("id", "5").put("parentId", "0").put("name", "工作").put("type", 1)
                .put("subCategories", JSONArray().put(
                    JSONObject().put("id", "6").put("parentId", "5").put("name", "工资").put("type", 1),
                )),
        ))
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val currentMonth = java.time.YearMonth.from(today)
        val firstMillis = BooksApi.monthBounds(currentMonth.toString()).first * 1_000
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val result = when (request.requestUrl?.encodedPath) {
                        "/api/authorize.json" -> JSONObject().put("token", "synthetic")
                        "/api/v1/tokens/refresh.json" -> JSONObject().put("newToken", "synthetic-renewed")
                            .put("oldTokenId", "synthetic-previous")
                        "/api/v1/tokens/revoke.json" -> {
                            check(JSONObject(request.body.readUtf8()).getString("tokenId") == "synthetic-previous")
                            if (revocations.incrementAndGet() == 1) {
                                startupReadStarted.countDown()
                                releaseStartupRead.await(20, TimeUnit.SECONDS)
                            }
                            true
                        }
                        "/api/v1/accounts/list.json" -> {
                            if (holdBalance.getAndSet(false)) {
                                balanceStarted.countDown()
                                releaseBalance.await(20, TimeUnit.SECONDS)
                                return MockResponse().setResponseCode(500).setBody("""{"success":false}""")
                            }
                            JSONArray().put(JSONObject(account.toString())
                                .put("balance", 1_000L - (saved.get()?.optLong("sourceAmount") ?: 0L)))
                        }
                        "/api/v1/family/overview.json" -> JSONObject().put(
                            "accounts", JSONArray().put(JSONObject().put("id", "1").put("ownerId", "7")),
                        )
                        "/api/v1/transaction/categories/revision.json" -> JSONObject().put("revision", "1")
                        "/api/v1/transaction/categories/list.json" -> {
                            categoryReads.incrementAndGet()
                            categories
                        }
                        "/api/v1/users/profile/get.json" -> JSONObject()
                        "/api/v1/transaction/tags/list.json" -> {
                            if (failTags.get()) return MockResponse().setResponseCode(500).setBody("""{"success":false}""")
                            JSONArray()
                        }
                        "/api/v1/transaction/tags/groups/list.json",
                        "/api/v1/users/external_auth/list.json" -> JSONArray()
                        "/api/v1/transactions/amounts/daily.json" -> JSONArray().apply {
                            saved.get()?.let { entry ->
                                val time = entry.getLong("time")
                                val url = requireNotNull(request.requestUrl)
                                val start = requireNotNull(url.queryParameter("start_time")).toLong()
                                val end = requireNotNull(url.queryParameter("end_time")).toLong()
                                if (time !in start..end) return@let
                                val day = java.time.Instant.ofEpochSecond(time)
                                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                                put(JSONObject().put("date", day.toString()).put("amounts", JSONArray().put(
                                    JSONObject().put("currency", "CNY").put("incomeAmount", 0)
                                        .put("expenseAmount", entry.getLong("sourceAmount")),
                                )))
                            }
                        }
                        "/api/v1/transactions/list.json" -> {
                            val snapshot = saved.get()
                            if (holdNextLedgerRead.getAndSet(false)) {
                                ledgerReadStarted.countDown()
                                releaseLedgerRead.await(25, TimeUnit.SECONDS)
                            }
                            val start = request.requestUrl?.queryParameter("min_time")?.toLong()
                            val end = request.requestUrl?.queryParameter("max_time")?.toLong()
                            if (snapshot != null && !refreshAvailable.get() && start == firstMillis) {
                                return MockResponse().setResponseCode(500).setBody("""{"success":false}""")
                            }
                            val rows = JSONArray()
                            snapshot?.let { entry ->
                                val time = entry.getLong("time") * 1_000
                                if ((start != null && time < start) || (end != null && time > end)) return@let
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
                            if (delayBalanceAfterAck.getAndSet(false)) holdBalance.set(true)
                            JSONObject(body).put("id", id).put("sourceAccount", account)
                                .put("category", category).also(saved::set)
                        }
                        "/api/v1/transactions/modify.json" -> {
                            writes.incrementAndGet()
                            releaseLedgerRead.countDown()
                            JSONObject(request.body.readUtf8()).put("sourceAccount", account)
                                .put("category", category).also(saved::set)
                        }
                        "/api/logout.json" -> true
                        else -> return MockResponse().setResponseCode(404).setBody("""{"success":false}""")
                    }
                    return MockResponse().setBody(JSONObject().put("success", true).put("result", result).toString())
                        .apply {
                            if (request.requestUrl?.encodedPath == "/api/v1/transactions/list.json" &&
                                slowRetry.getAndSet(false)
                            ) setBodyDelay(2, TimeUnit.SECONDS)
                        }
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 18096)
            var verifyCachedEditor = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: BooksModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[BooksModel::class.java]
                    model.updateInstantEntry(false)
                    if (model.showEditor) model.closeTransaction()
                    if (model.token == null) model.login("synthetic", "synthetic", "")
                }
                compose.waitUntil(15_000) { model.token != null && !model.busy && model.accounts.isNotEmpty() }
                try {
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithTag("add-transaction", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
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
                        compose.onAllNodesWithText("最新流水读取失败").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(1, writes.get())
                    compose.onNodeWithText("重试读取流水").assertIsDisplayed()
                    compose.onNodeWithTag("ledger-month-expense").assertDoesNotExist()
                    val otherMonth = currentMonth.minusMonths(1).toString()
                    scenario.onActivity { model.changeLedgerMonth(otherMonth) }
                    compose.waitUntil(10_000) { model.ledgerMonth == otherMonth && !model.busy }
                    compose.onNodeWithTag("ledger-month-expense").assertTextEquals("0.00")
                    compose.onNodeWithText("重试读取流水").assertDoesNotExist()
                    scenario.onActivity { model.changeLedgerMonth(currentMonth.toString()) }
                    compose.waitUntil(10_000) { model.ledgerMonth == currentMonth.toString() && !model.busy }
                    compose.onNodeWithText("重试读取流水").assertIsDisplayed()
                    refreshAvailable.set(true)
                    slowRetry.set(true)
                    compose.onNodeWithText("重试读取流水").performClick()
                    compose.waitUntil(15_000) {
                        !model.ledgerNeedsRefresh && model.transactions.size == 2 &&
                            model.accounts.singleOrNull()?.optLong("balance") == 877L
                    }
                    assertEquals(1, writes.get())
                    compose.onNodeWithTag("ledger-month-expense").assertTextEquals("1.23")
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
                    holdNextLedgerRead.set(true)
                    compose.onNodeWithTag("confirm-pending-transaction").performClick()
                    compose.waitUntil(15_000) { !model.busy && !model.transactionNeedsConfirmation }
                    compose.onNodeWithText("核对上次保存的记录").assertDoesNotExist()
                    compose.onNodeWithTag("transaction-amount").assertTextEquals("0.0")
                    assertEquals(3, writes.get())
                    assertEquals(unknownRequest.get(), reviewedRequest.get())
                    assertTrue("Post-save read did not start", ledgerReadStarted.await(5, TimeUnit.SECONDS))
                    compose.onNodeWithContentDescription("取消记账").performClick()
                    val beforeEdit = requireNotNull(saved.get())
                    val nextDate = today.minusDays(1)
                    scenario.onActivity {
                        model.newTransaction()
                        model.saveTransaction(JSONObject(beforeEdit.toString())
                            .put("time", BooksApi.dayBounds(nextDate.toString()).first)
                            .put("sourceAmount", 345), existing = true)
                    }
                    compose.waitUntil(8_000) {
                        !model.busy && !model.showEditor && !model.ledgerNeedsRefresh &&
                            model.weeklyAmounts.singleOrNull()?.optString("date") == nextDate.toString() &&
                            model.accounts.singleOrNull()?.optLong("balance") == 655L
                    }
                    val sameMonth = today.month == nextDate.month
                    val expectedMonthExpense = if (sameMonth) "3.45" else "0.00"
                    compose.onNodeWithTag("ledger-month-expense").assertTextEquals(expectedMonthExpense)
                    compose.runOnIdle {
                        assertEquals(4, writes.get())
                        assertEquals(listOf(nextDate.toString()).filter { sameMonth }, model.dailyAmounts.map { it.getString("date") })
                        assertEquals(nextDate.toString(), model.weeklyAmounts.single().getString("date"))
                        if (sameMonth) assertEquals(345L, model.transactions.first().getLong("sourceAmount"))
                        assertEquals(655L, model.accounts.single().getLong("balance"))
                    }
                    verifyEdgeBackIfGestureNavigationEnabled()
                    scenario.onActivity {
                        model.updateInstantEntry(true)
                        check(it.getSharedPreferences("family-books-preferences-v1", 0).edit()
                            .putLong("last_session_refresh", 0L).commit())
                    }
                    verifyCachedEditor = true
                } finally {
                    respondToSave.countDown()
                    releaseLedgerRead.countDown()
                    if (!verifyCachedEditor) {
                        scenario.onActivity { model.logout() }
                        compose.waitUntil(15_000) { model.token == null }
                    }
                }
            }
            failTags.set(true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    assertTrue("Startup token revocation did not start", startupReadStarted.await(5, TimeUnit.SECONDS))
                    compose.onNodeWithTag("entry-key-1").assertIsEnabled().performClick()
                    compose.onNodeWithTag("transaction-amount").assertTextEquals("1")
                    compose.onNodeWithText("茶饮").performClick()
                    val area = compose.onNodeWithTag("entry-categories")
                    area.performTouchInput { swipe(centerRight.copy(x = width * .8f), centerLeft.copy(x = width * .2f)) }
                    compose.onNodeWithText("工资").assertIsDisplayed()
                    area.performTouchInput { swipe(centerLeft.copy(x = width * .2f), centerRight.copy(x = width * .8f)) }
                    compose.onNodeWithText("茶饮").assertIsSelected()
                    compose.onNodeWithTag("transaction-amount").assertTextEquals("1")
                    lateinit var model: BooksModel
                    scenario.onActivity { model = ViewModelProvider(it)[BooksModel::class.java] }
                    val pausedReads = categoryReads.get()
                    compose.onNodeWithTag("entry-key-保存").performClick()
                    compose.waitUntil(8_000) { writes.get() == 5 && !model.showEditor }
                    compose.waitUntil(15_000) {
                        revocations.get() == 2 && categoryReads.get() > pausedReads && model.backgroundRefreshFailed
                    }
                    releaseStartupRead.countDown()
                    compose.onNodeWithTag("add-transaction").performClick()
                    compose.onNodeWithTag("entry-key-1").performClick()
                    compose.waitUntil(15_000) { model.backgroundRefreshFailed }
                    delayBalanceAfterAck.set(true)
                    refreshAvailable.set(false)
                    compose.onNodeWithTag("entry-key-保存").performClick()
                    compose.waitUntil(15_000) { !model.busy && !model.showEditor && model.ledgerRefreshFailed }
                    assertTrue(balanceStarted.await(5, TimeUnit.SECONDS))
                    refreshAvailable.set(true)
                    compose.onNodeWithText("重试读取流水").performClick()
                    compose.waitUntil(10_000) { !model.ledgerNeedsRefresh && !model.ledgerRefreshFailed }
                    assertTrue("Balance read should still be pending", model.accountRefreshPending)
                    assertEquals(6, writes.get())
                    compose.onNodeWithText("标签等辅助资料读取失败").assertIsDisplayed()
                    compose.onNodeWithText("重试读取流水").assertDoesNotExist()
                    compose.onNodeWithContentDescription("打开菜单").performClick()
                    compose.onNodeWithTag("nav-设置").performClick()
                    compose.onNodeWithTag("settings-accounts").performClick()
                    compose.onNodeWithText("账户余额更新中，暂显示上次结果").assertIsDisplayed()
                    releaseBalance.countDown()
                    compose.waitUntil(10_000) { model.accountRefreshFailed }
                    compose.onNodeWithText("重试刷新账户").performClick()
                    compose.waitUntil(10_000) { !model.accountRefreshFailed && model.accounts.single().getLong("balance") == 900L }
                    assertEquals(6, writes.get())
                } finally {
                    releaseStartupRead.countDown()
                    releaseBalance.countDown()
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[BooksModel::class.java]
                        model.updateInstantEntry(false)
                        model.forgetLocal()
                    }
                }
            }
        }
    }

    private fun verifyEdgeBackIfGestureNavigationEnabled() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        if (shell("settings get secure navigation_mode") != "2") return

        val baseline = requireNotNull(automation.takeScreenshot())
        try {
            val width = baseline.width
            val height = baseline.height
            fun showsLedger(): Boolean {
                val frame = automation.takeScreenshot() ?: return false
                try {
                    var differences = 0
                    for (y in height / 8 until height * 7 / 8 step height / 36) {
                        for (x in width / 8 until width * 7 / 8 step width / 24) {
                            if (baseline.getPixel(x, y) != frame.getPixel(x, y)) differences++
                        }
                    }
                    return differences <= 5
                } finally {
                    frame.recycle()
                }
            }
            for ((from, to) in listOf(1 to width / 3, width - 2 to width * 2 / 3)) {
                compose.onNodeWithTag("add-transaction").performClick()
                compose.onNodeWithTag("entry-key-1").assertIsDisplayed()
                compose.waitUntil(5_000) { !showsLedger() }
                shell("input swipe $from ${height / 2} $to ${height / 2} 320")
                compose.waitUntil(5_000) { showsLedger() }
                compose.onNodeWithTag("ledger-month-expense").assertIsDisplayed()
            }
        } finally {
            baseline.recycle()
        }
    }
}
