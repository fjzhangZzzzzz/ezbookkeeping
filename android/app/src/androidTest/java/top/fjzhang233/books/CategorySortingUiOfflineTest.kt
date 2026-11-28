package top.fjzhang233.books

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Device-level UI smoke for the local sorting interaction; network persistence is covered separately. */
class CategorySortingUiOfflineTest {
    @get:Rule val compose = createComposeRule()

    private class App(base: Context) : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("qa-category-sorting-$name", mode)
    }

    @Test fun handlesAreVisibleAndLongPressDragEntersSaveSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = BooksModel(App(context))
        setPrivate(vm, "categories", listOf(
            category("p1", "0", 2, "支出"), category("c1", "p1", 2, "早餐"),
            category("p2", "0", 2, "日常"), category("c2", "p2", 2, "购物")
        ))
        setPrivate(vm, "categoryRevision", "1")
        compose.setContent { FamilyTheme { CatalogPage(vm) } }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag("catalog-sort-handle-p1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("catalog-sort-handle-p1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("catalog-sort-handle-p2", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, -120f))
            up()
        }
        compose.onNodeWithText("保存排序", substring = false).assertIsDisplayed()
        compose.onNodeWithText("取消", substring = false).assertIsDisplayed()
    }

    @Test fun expandedParentStaysAboveChildrenAndSecondChildCanMoveUp() {
        val vm = BooksModel(App(InstrumentationRegistry.getInstrumentation().targetContext))
        val children = (1..28).map { category("c$it", "p1", 2, "子项$it").put("displayOrder", it) }
        setPrivate(vm, "categories", listOf(category("p1", "0", 2, "日常")) + children)
        setPrivate(vm, "categoryRevision", "1")
        compose.setContent { FamilyTheme { CatalogPage(vm) } }
        val parent = compose.onNodeWithTag("catalog-row-p1")
        val top = parent.fetchSemanticsNode().boundsInRoot.top
        parent.performClick()
        compose.waitForIdle()
        check(parent.fetchSemanticsNode().boundsInRoot.top == top)
        compose.onNodeWithTag("catalog-sort-handle-p1").assertDoesNotExist()
        fun handle(index: Int) = compose.onNodeWithTag("catalog-sort-handle-c$index", useUnmergedTree = true)
        val distance = handle(1).fetchSemanticsNode().boundsInRoot.center.y - handle(2).fetchSemanticsNode().boundsInRoot.center.y
        val initialY = handle(2).fetchSemanticsNode().boundsInRoot.center.y
        compose.mainClock.autoAdvance = false
        handle(2).performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(0f, distance / 3)) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        val heldY = handle(2).fetchSemanticsNode().boundsInRoot.center.y
        check(kotlin.math.abs(heldY - initialY - distance / 3) < 3f) { "held=$heldY start=$initialY delta=${distance / 3}" }
        compose.onNodeWithText("保存排序").assertDoesNotExist()
        compose.onRoot().performTouchInput { moveBy(Offset(0f, -distance / 3)); up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("保存排序").assertDoesNotExist()
        handle(2).performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, distance)); up()
        }
        compose.waitForIdle()
        check(handle(2).fetchSemanticsNode().boundsInRoot.top < handle(1).fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("保存排序").assertIsDisplayed()
        // Drag back across the original neighbour: the saved baseline should be clean again.
        handle(2).performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(0f, -distance)); up() }
        compose.onNodeWithText("保存排序").assertDoesNotExist()
    }

    private fun category(id: String, parent: String, type: Int, name: String) = JSONObject()
        .put("id", id).put("parentId", parent).put("type", type).put("name", name)
        .put("displayOrder", if (parent == "0") id.removePrefix("p").toInt() else 1)
        .put("hidden", false).put("familyInternalTransfer", false).put("icon", "1")

    private fun setPrivate(target: Any, property: String, value: Any?) {
        val parameterType = if (property == "categories") List::class.java else String::class.java
        target.javaClass.getDeclaredMethod("set${property.replaceFirstChar { it.uppercase() }}", parameterType)
            .apply { isAccessible = true }.invoke(target, value)
    }
}
