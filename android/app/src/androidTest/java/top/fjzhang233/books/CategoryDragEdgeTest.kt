package top.fjzhang233.books

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test

/** Exercise the library's edge scroller with a held system pointer and the real display clock. */
class CategoryDragEdgeTest {
    @Test fun heldChildScrollsToPreviouslyOffscreenSiblings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(nodes(it)) }
        }
        fun find(label: String) = automation.rootInActiveWindow?.let { root ->
            nodes(root).firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
        }
        fun waitFor(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5000
            do { find(label)?.let { return it }; SystemClock.sleep(40) } while (SystemClock.uptimeMillis() < deadline)
            error("未显示 $label，当前节点 " + automation.rootInActiveWindow?.let { root -> nodes(root).mapNotNull { it.text ?: it.contentDescription }.take(40).toList() })
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun touch(started: Long, action: Int, x: Int, y: Int) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = object : android.app.Application() {
                    init { attachBaseContext(activity.applicationContext) }
                    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa-edge-$name", mode)
                }
                val vm = BooksModel(app)
                val data = (0..32).map { index -> JSONObject().put("id", (index + 1).toString())
                    .put("parentId", if (index == 0) "0" else "1").put("type", 2).put("displayOrder", index)
                    .put("name", if (index == 0) "大类" else "子项$index") }
                vm.javaClass.getDeclaredMethod("setCategories", List::class.java).apply { isAccessible = true }.invoke(vm, data)
                vm.javaClass.getDeclaredMethod("setCategoryRevision", String::class.java).apply { isAccessible = true }.invoke(vm, "1")
                activity.setContent { FamilyTheme { CatalogPage(vm) } }
            }
            val parent = bounds(waitFor("大类"))
            var started = SystemClock.uptimeMillis()
            touch(started, MotionEvent.ACTION_DOWN, parent.centerX(), parent.centerY())
            touch(started, MotionEvent.ACTION_UP, parent.centerX(), parent.centerY())
            val handle = bounds(waitFor("长按拖动排序 子项1"))
            check(find("子项25") == null)
            started = SystemClock.uptimeMillis()
            var y = handle.centerY()
            touch(started, MotionEvent.ACTION_DOWN, handle.centerX(), y)
            try {
                SystemClock.sleep(750)
                // Cross several rows to open the fixed save bar, then approach the new viewport edge.
                repeat(12) {
                    y += handle.height() / 4
                    touch(started, MotionEvent.ACTION_MOVE, handle.centerX(), y)
                    SystemClock.sleep(20)
                }
                waitFor("保存排序")
                val viewport = nodes(automation.rootInActiveWindow).first { it.isScrollable }
                val edge = bounds(viewport).bottom - 4
                val from = y
                repeat(20) { step ->
                    y = from + (edge - from) * (step + 1) / 20
                    touch(started, MotionEvent.ACTION_MOVE, handle.centerX(), y)
                    SystemClock.sleep(20)
                }
                waitFor("子项25")
            } finally { touch(started, MotionEvent.ACTION_UP, handle.centerX(), y) }
            waitFor("保存排序")
        }
    }
}
