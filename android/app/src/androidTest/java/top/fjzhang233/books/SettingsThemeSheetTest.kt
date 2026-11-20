package top.fjzhang233.books

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class SettingsThemeSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chooseThemeAndDismissWithoutLeavingSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val previous = prefs.getString("theme", null)
        val vm = BooksModel(object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("qa-settings-theme-$name", mode)
        })
        val settings = compose.onNodeWithTag("settings-theme")
        fun awaitSheetClosed() = compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("theme-sheet").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }

        try {
            check(prefs.edit().putString("theme", ThemeMode.SYSTEM.key).commit())
            compose.setContent { FamilyTheme { SettingsPage(vm) { error("Unexpected navigation to $it") } } }
            settings.performClick()
            compose.onNodeWithTag("theme-sheet").assertIsDisplayed()
            compose.onNodeWithTag("theme-system").assertIsSelected()
            compose.onNodeWithText("取消").performClick()
            awaitSheetClosed()
            settings.assert(hasText("跟随系统"))

            settings.performClick()
            compose.onNodeWithTag("theme-dark").performClick()
            awaitSheetClosed()
            settings.assert(hasText("深色"))
            settings.performClick()
            compose.onNodeWithTag("theme-dark").assertIsSelected()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitSheetClosed()
            settings.assertIsDisplayed()
        } finally {
            check(prefs.edit().putString("theme", previous).commit())
        }
    }
}
