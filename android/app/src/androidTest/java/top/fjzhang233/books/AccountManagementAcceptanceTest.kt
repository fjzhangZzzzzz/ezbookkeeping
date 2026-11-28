package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Account mutations use a dedicated synthetic backend and the normal Settings entry point. */
class AccountManagementAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun defaultOrderArchiveRestoreAndDeleteRefreshTheVisibleAccountList() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.API_BASE_URL == "http://127.0.0.1:18090")
        val fixture = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa-config.json")
        assumeTrue(fixture.isFile)
        val login = JSONObject(fixture.readText())
        val model = ViewModelProvider(compose.activity)[BooksModel::class.java]
        if (model.token == null) compose.runOnIdle { model.login(login.getString("username"), login.getString("password"), "") }
        compose.waitUntil(30_000) { model.token != null && !model.busy }
        val suffix = UUID.randomUUID().toString().take(8)
        val previousDefault = model.defaultAccountId.takeUnless { it.isBlank() || it == "0" }
            ?: model.accounts.firstOrNull { !it.optBoolean("hidden") }?.id()
        suspend fun addAccount(name: String, balance: Int): String {
            val body = JSONObject().put("name", name).put("type", 1).put("category", 1)
                .put("currency", "CNY").put("balance", balance.toString())
                .put("balanceTime", System.currentTimeMillis() / 1000).put("icon", "1").put("iconType", 0)
                .put("color", "006B5C").put("comment", "").put("clientSessionId", UUID.randomUUID().toString())
            return (model.api.call("/api/v1/accounts/add.json", body) as JSONObject).id()
        }
        val first = runBlocking { addAccount("QA顺序甲-$suffix", 0) }
        val second = runBlocking { addAccount("QA顺序乙-$suffix", 0) }
        val archive = runBlocking { addAccount("QA归档-$suffix", 100) }
        val list = compose.onNodeWithTag("accounts-list")
        fun scopedAction(id: String, action: String) {
            list.performScrollToNode(hasTestTag("account-row-$id"))
            if (action == "up" || action == "down") {
                val label = if (action == "up") "上移" else "下移"
                val actions = compose.onNodeWithTag("account-sort-handle-$id", useUnmergedTree = true).fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                compose.runOnIdle { assertTrue(actions.single { it.label == label }.action()) }
                return
            }
            if (action != "restore") compose.onNodeWithTag("account-menu-$id").performClick()
            compose.onNodeWithTag("account-$action-$id", useUnmergedTree = true).performClick()
        }
        fun waitForList(condition: () -> Boolean) {
            compose.waitUntil(30_000) { !model.busy && condition() }
            assertFalse(model.accountRefreshFailed)
        }
        fun order(): List<String> = sortAccountsForDisplay(model.accounts).map { it.id() }.filter { it == first || it == second }
        fun confirmRemoval(id: String) {
            scopedAction(id, "remove")
            compose.onNodeWithText("确认", substring = false).performClick()
        }
        try {
            compose.runOnIdle { model.reload() }
            waitForList { model.accounts.any { it.id() == archive } }
            compose.onNodeWithContentDescription("打开菜单").performClick()
            compose.onNodeWithTag("nav-设置").performClick()
            compose.onNodeWithTag("settings-accounts").performClick()

            scopedAction(first, "set-default")
            waitForList { model.defaultAccountId == first }
            compose.onNodeWithTag("account-default-$first", useUnmergedTree = true).assertIsDisplayed()

            val before = order()
            assertEquals(2, before.size)
            scopedAction(before.first(), "down")
            assertEquals(before, order())
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("放弃", substring = false).performClick()
            assertEquals(before, order())
            scopedAction(before.first(), "down")
            compose.onNodeWithText("保存排序").performClick()
            waitForList { order() == before.reversed() }
            scopedAction(before.first(), "up")
            compose.onNodeWithText("保存排序").performClick()
            waitForList { order() == before }
            assertEquals(first, model.defaultAccountId)

            confirmRemoval(archive)
            waitForList { model.accounts.any { it.id() == archive && it.optBoolean("hidden") } }
            val showArchives = hasText("显示归档账户", substring = true)
            list.performScrollToNode(showArchives)
            compose.onNode(showArchives).performClick()
            scopedAction(archive, "restore")
            waitForList { model.accounts.any { it.id() == archive && !it.optBoolean("hidden") } }
            list.performScrollToNode(hasTestTag("account-row-$archive"))
            compose.onNodeWithTag("account-row-$archive").assertIsDisplayed()

            confirmRemoval(second)
            waitForList { model.accounts.none { it.id() == second } }
            val serverRows = runBlocking { model.api.accounts() }
            assertTrue(serverRows.none { it.id() == second })
            assertTrue(serverRows.any { it.id() == archive && !it.optBoolean("hidden") && it.optString("balance") == "100" })
        } finally {
            runBlocking {
                val api = model.api
                if (model.defaultAccountId == first && previousDefault != null) {
                    runCatching {
                        api.call("/api/v1/users/profile/update.json", JSONObject().put("defaultAccountId", previousDefault))
                    }
                }
                val currentDefault = api.userProfile().id("defaultAccountId")
                listOf(first, second, archive).filterNot { it == currentDefault }.forEach { id ->
                    runCatching { api.call("/api/v1/accounts/delete.json", JSONObject().put("id", id)) }
                }
            }
        }
    }
}
