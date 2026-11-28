package top.fjzhang233.books

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AccountSortingAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun draggingStagesOrderUntilSaveAndCancelRestoresIt() {
        assumeTrue(signedInQaEnabled())
        val vm = ViewModelProvider(compose.activity)[BooksModel::class.java]
        compose.waitUntil(30_000) { vm.token != null && !vm.busy && vm.accounts.isNotEmpty() }
        val created = mutableListOf<String>()
        val previousDefault = runBlocking { vm.api.userProfile().id("defaultAccountId") }
        try {
            repeat(2) {
                val name = "排序验收-" + UUID.randomUUID().toString().take(8)
                val body = JSONObject().put("name", name).put("category", 1).put("type", 1).put("currency", "CNY")
                    .put("balance", "0").put("balanceTime", Instant.now().epochSecond).put("color", "006B5C")
                    .put("icon", "1").put("iconType", 0).put("clientSessionId", UUID.randomUUID().toString())
                created += runBlocking { (vm.api.call("/api/v1/accounts/add.json", body) as JSONObject).id() }
            }
            fun persisted() = runBlocking { sortAccountsForDisplay(vm.api.accounts()).map { it.id() }.filter { it in created } }
            val original = persisted()
            compose.runOnIdle { vm.reload() }
            compose.waitUntil(30_000) { !vm.busy && vm.accounts.any { it.id() == original.last() } }
            compose.onNodeWithContentDescription("打开菜单").performClick()
            compose.onNodeWithTag("nav-设置").performClick()
            compose.onNodeWithTag("settings-accounts").performClick()
            fun drag() {
                val source = "account-sort-handle-" + original.last()
                val target = "account-sort-handle-" + original.first()
                compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag(source))
                val handle = compose.onNodeWithTag(source)
                val offset = compose.onNodeWithTag(target).fetchSemanticsNode().boundsInRoot.center.y -
                    handle.fetchSemanticsNode().boundsInRoot.center.y
                handle.performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(0f, offset)); up() }
                compose.onNodeWithText("保存排序").assertIsDisplayed()
            }
            drag()
            assertEquals(original, persisted())
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("放弃", substring = false).performClick()
            assertEquals(original, persisted())
            drag()
            compose.onNodeWithText("保存排序").performClick()
            compose.waitUntil(30_000) {
                !vm.busy && sortAccountsForDisplay(vm.accounts).map { it.id() }.filter { it in created } == original.reversed()
            }
            assertEquals(original.reversed(), persisted())
            assertEquals(previousDefault, runBlocking { vm.api.userProfile().id("defaultAccountId") })
        } finally {
            runBlocking { created.forEach { vm.api.call("/api/v1/accounts/delete.json", JSONObject().put("id", it)) } }
        }
    }
}
