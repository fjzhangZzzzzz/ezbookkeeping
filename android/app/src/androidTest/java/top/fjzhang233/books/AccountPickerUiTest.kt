package top.fjzhang233.books

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic UI fixtures only; actual create/return is separately exercised on the QA backend. */
class AccountPickerUiTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get()=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    private val previous by lazy{prefs.getString("theme",null)}
    private fun mode(value:String){previous;check(prefs.edit().putString("theme",value).commit())}
    @After fun restoreTheme(){val old=previous;check(if(old==null)prefs.edit().remove("theme").commit()else prefs.edit().putString("theme",old).commit())}
    private fun capture(name:String){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(600);val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("No image");File(context.cacheDir,"qa-account-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun account(id:String,name:String,hidden:Boolean=false)=JSONObject().put("id",id).put("name",name).put("currency","CNY").put("icon","10").put("color","665CF5").put("hidden",hidden)
    @Test fun lightEmptySheetHasOnlyRealClearAndCreateActions(){
        mode("light");var chosen:String?=null;var creates=0
        compose.setContent{FamilyTheme{LedgerAccountPicker(emptyList(),"",dismiss={},choose={chosen=it},create={creates++})}}
        compose.onNodeWithText("选择一个账户").assertIsDisplayed();compose.onNodeWithTag("entry-account-none").assertIsSelected().performClick()
        compose.runOnIdle{assertEquals("",chosen)};compose.onNodeWithTag("entry-account-create").performClick();compose.runOnIdle{assertEquals(1,creates)}
        capture("light-empty")
    }
    @Test fun darkSheetShowsSelectionHidesHiddenAndAllowsExplicitClear(){
        mode("dark");val selected=mutableStateOf("11")
        val rows=listOf(account("11","合成现金"),account("12","合成储蓄"),account("13","合成隐藏",true))
        compose.setContent{FamilyTheme{LedgerAccountPicker(rows,selected.value,dismiss={},choose={selected.value=it},create={})}}
        compose.onNodeWithTag("entry-account-11").assertIsSelected();compose.onNodeWithTag("entry-account-13").assertDoesNotExist()
        compose.onNodeWithTag("entry-account-12").performClick().assertIsSelected();capture("dark-selected")
        compose.onNodeWithTag("entry-account-none").performClick().assertIsSelected();compose.runOnIdle{assertEquals("",selected.value)}
        val target=compose.onNodeWithTag("entry-account-none").fetchSemanticsNode().boundsInRoot
        val density=context.resources.displayMetrics.density;assertTrue(target.width/density>=48);assertTrue(target.height/density>=48)
    }
    @Test fun assetGroupsAreScrollableAndChooseAnActualBackendCategory(){
        mode("light");var chosen:AccountPreset?=null
        compose.setContent{FamilyTheme{LedgerAssetPresetPicker({}, {chosen=it})}}
        compose.onNodeWithText("选择资产分类").assertIsDisplayed();compose.onNodeWithTag("entry-asset-cash").assertIsDisplayed();capture("light-assets-top")
        compose.onNodeWithTag("entry-asset-groups").performScrollToNode(hasTestTag("entry-asset-borrow"));compose.onNodeWithTag("entry-asset-borrow").performClick()
        compose.runOnIdle{assertEquals("borrow",chosen?.key);assertEquals(5,chosen?.category)};capture("light-assets-debt")
    }
    @Test fun busySelectionAndCreationDoNotAcceptTouches(){
        mode("dark");var calls=0
        compose.setContent{FamilyTheme{LedgerAccountPicker(listOf(account("11","合成现金")),"11",dismiss={},choose={calls++},create={calls++},enabled=false,createEnabled=false)}}
        compose.onNodeWithTag("entry-account-none").assertIsNotEnabled().performTouchInput{click()}
        compose.onNodeWithTag("entry-account-11").assertIsNotEnabled().performTouchInput{click()}
        compose.onNodeWithTag("entry-account-create").assertIsNotEnabled().performTouchInput{click()}
        compose.runOnIdle{assertEquals(0,calls)}
    }
    @Test fun editCreditLimitPresenceMatchesWebAndNeverChangesBalance(){
        mode("light");val vm=BooksModel(DraftApp(context));val bodies=mutableListOf<JSONObject>()
        fun original(id:String,limit:String)=JSONObject().put("id",id).put("name","合成信用卡$id").put("category",3).put("type",1).put("icon","100").put("color","006B5C").put("currency","CNY").put("balance","-1234").put("comment","").put("hidden",false).put("creditCardStatementDate",12).put("creditCardLimit",limit)
        val item=mutableStateOf(original("101","0"))
        compose.setContent{FamilyTheme{key(item.value.id()){AccountEditor(vm,item.value,close={},submit={_,body,_->bodies+=JSONObject(body.toString())})}}}
        for((index,limit) in listOf("0","50000","50000").withIndex()){
            if(index>0)compose.runOnIdle{item.value=original((101+index).toString(),limit)}
            compose.onNodeWithText("编辑资料不会调整余额。余额随记账更新。").assertIsDisplayed()
            if(index==2){compose.onNodeWithText("账户类型：信用卡").performClick();compose.onNodeWithText("现金",substring=false).performClick();compose.onNodeWithText("账户类型：现金").assertExists()}
            compose.onNodeWithText("保存账户").performClick()
            compose.runOnIdle{
                assertEquals(index+1,bodies.size);val body=bodies.last();assertFalse(body.has("balance"))
                if(index==1){assertTrue(body.get("creditCardLimit") is String);assertEquals("50000",body.getString("creditCardLimit"))}else assertFalse(body.has("creditCardLimit"))
                if(index==2){assertEquals(1,body.getInt("category"));assertFalse(body.has("creditCardStatementDate"))}else assertEquals(12,body.getInt("creditCardStatementDate"))
            }
        }
    }
    private class DraftApp(base:Context):Application(){init{attachBaseContext(base)};override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences("qa-account-picker-$name",mode)}
    @Test fun presetDefaultsAndRequestIdentitySurviveDraftRestoration(){
        mode("dark");val vm=BooksModel(DraftApp(context));val bodies=mutableListOf<JSONObject>();val restoration=StateRestorationTester(compose)
        restoration.setContent{FamilyTheme{AccountEditor(vm,null,close={},submit={_,body,_->bodies+=JSONObject(body.toString())},preset=accountPreset("borrow"))}}
        compose.onNodeWithText("借入").assertExists();compose.onNodeWithText("账户类型：负债").assertExists();compose.onNodeWithText("保存账户").performClick()
        restoration.emulateSavedInstanceStateRestore();compose.onNodeWithText("保存账户").performClick()
        compose.runOnIdle{assertEquals(2,bodies.size);assertEquals(5,bodies[0].getInt("category"));assertEquals("601",bodies[0].getString("icon"));assertEquals("0",bodies[0].getString("balance"));assertEquals(bodies[0].getString("clientSessionId"),bodies[1].getString("clientSessionId"));assertEquals(bodies[0].getLong("balanceTime"),bodies[1].getLong("balanceTime"))}
    }
}
