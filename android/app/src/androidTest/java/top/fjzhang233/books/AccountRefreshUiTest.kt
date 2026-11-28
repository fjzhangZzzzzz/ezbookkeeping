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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class AccountRefreshUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun acknowledgedAccountSurvivesReadFailureAndRetryOnlyReads() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.API_BASE_URL == "http://127.0.0.1:18096")
        val writes = AtomicInteger()
        val postSaveReads = AtomicInteger()
        val oldAccount = JSONObject().put("id", "1").put("name", "原账户").put("currency", "CNY").put("category", 1)
        val newAccount = JSONObject(oldAccount.toString()).put("id", "2").put("name", "测试新账户")

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val result = when (request.requestUrl?.encodedPath) {
                        "/api/authorize.json" -> JSONObject().put("token", "synthetic")
                        "/api/v1/tokens/list.json" ->
                            JSONArray().put(JSONObject().put("isCurrent", true).put("tokenType", 1).put("tokenId", "7:8:9"))
                        "/api/v1/family/overview.json" ->
                            JSONObject().put("accounts", JSONArray().put(JSONObject().put("id", "1").put("ownerId", "7")))
                        "/api/v1/accounts/list.json" -> {
                            if (writes.get() == 0) JSONArray().put(oldAccount)
                            else if (postSaveReads.incrementAndGet() == 1) {
                                return MockResponse().setResponseCode(500).setBody("""{"success":false}""")
                            } else JSONArray().put(oldAccount).put(newAccount)
                        }
                        "/api/v1/accounts/add.json" -> {
                            check(request.method == "POST")
                            writes.incrementAndGet()
                            newAccount
                        }
                        "/api/v1/transaction/categories/revision.json" -> JSONObject().put("revision", "1")
                        "/api/v1/transaction/categories/list.json", "/api/v1/users/profile/get.json" -> JSONObject()
                        "/api/v1/transactions/list.json" -> JSONObject().put("items", JSONArray()).put("totalCount", 0)
                        "/api/v1/transaction/tags/list.json", "/api/v1/transaction/tags/groups/list.json",
                        "/api/v1/users/external_auth/list.json", "/api/v1/transactions/amounts/daily.json" -> JSONArray()
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
                    compose.onNodeWithContentDescription("打开菜单").performClick()
                    compose.onNodeWithTag("nav-设置").performClick()
                    compose.onNodeWithTag("settings-accounts").performClick()
                    compose.onNodeWithContentDescription("新建账户").performClick()
                    compose.onNodeWithText("账户名称").performTextInput("测试新账户")
                    compose.onNodeWithText("保存账户").performClick()

                    compose.waitUntil(15_000) { model.accountRefreshFailed && !model.busy }
                    compose.onNodeWithText("已保存，账户列表刷新失败").assertIsDisplayed()
                    compose.onNodeWithText("保存账户").assertDoesNotExist()
                    assertEquals(1, writes.get())
                    assertEquals(1, postSaveReads.get())
                    compose.onNodeWithText("重试刷新账户").performClick()
                    compose.waitUntil(15_000) { !model.accountRefreshFailed && !model.busy }
                    compose.onNodeWithText("测试新账户").assertIsDisplayed()
                    compose.onNodeWithText("重试刷新账户").assertDoesNotExist()
                    assertEquals(1, writes.get())
                    assertEquals(2, postSaveReads.get())
                } finally {
                    scenario.onActivity { model.logout() }
                    compose.waitUntil(15_000) { model.token == null }
                }
            }
        }
    }
}
