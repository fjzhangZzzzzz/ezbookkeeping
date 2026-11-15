package top.fjzhang233.books

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offline synthetic UI only; mutation and synchronisation require the isolated backend. */
class SharedCategoryUiOfflineTest {
    @get:Rule val compose=createComposeRule()
    private class App(base:Context):Application(){init{attachBaseContext(base)};override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences("qa-shared-categories-$name",mode)}
    @Test fun lightEditorLocksUnversionedWrites(){ editor("light") }
    @Test fun darkEditorLocksUnversionedWrites(){ editor("dark") }
    private fun editor(mode:String){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
        val previous=prefs.getString("theme",null)
        try {
            var background=Color.Unspecified
            val vm=BooksModel(App(context))
            val original=JSONObject().put("id","101").put("name","合成一级分类").put("type",2).put("parentId","0").put("hidden",false).put("icon","1").put("color","006B5C")
            run {
                check(prefs.edit().putString("theme",mode).commit())
                compose.setContent { FamilyTheme { val color=MaterialTheme.colorScheme.background; SideEffect { background=color }; CatalogEditor(vm,0,original,close={}) } }
                compose.onNodeWithText("一级分类不能调整为二级分类").assertIsDisplayed()
                compose.onNodeWithText("隐藏分类").assertIsDisplayed().assertIsNotEnabled()
                compose.onNodeWithText("删除分类").assertIsDisplayed().assertIsNotEnabled()
                compose.onNodeWithText("保存分类").assertIsNotEnabled()
                compose.runOnIdle { if(mode=="light") assertTrue(background.red>0.5f) else assertTrue(background.red<0.5f) }
            }
        } finally { if(previous==null)prefs.edit().remove("theme").commit() else prefs.edit().putString("theme",previous).commit() }
    }
}
