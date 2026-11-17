package top.fjzhang233.books

import android.app.UiModeManager
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Local synthetic account, read-only page navigation; theme is the only persistent setting changed. */
class ThemeAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val folder get()=File(context.filesDir,"theme-validation").apply{mkdirs()}
    private var scenario:ActivityScenario<MainActivity>?=null
    private lateinit var vm:BooksModel
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun exists(tag:String)=compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
    private fun idle(){compose.waitUntil(45_000){!vm.busy&&vm.token!=null};compose.waitForIdle()}
    private fun back(){inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle()}
    private fun capture(name:String){
        compose.waitForIdle();android.os.SystemClock.sleep(250)
        val ui=inst.uiAutomation;val info=ui.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.serviceInfo=info
        compose.waitUntil(5000){ui.rootInActiveWindow?.packageName?.toString()==context.packageName}
        val before=checkNotNull(ui.rootInActiveWindow);check(before.packageName?.toString()==context.packageName)
        val png=File(folder,"$name.png");val image=checkNotNull(ui.takeScreenshot());png.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        val root=checkNotNull(ui.rootInActiveWindow);check(root.packageName?.toString()==context.packageName)
        val file=File(folder,"$name.xml");file.outputStream().use{out->val xml=android.util.Xml.newSerializer();xml.setOutput(out,"UTF-8");xml.startDocument("UTF-8",true)
            fun visit(node:android.view.accessibility.AccessibilityNodeInfo,depth:Int){check(depth<40&&!node.isPassword);xml.startTag(null,"node");val b=android.graphics.Rect();node.getBoundsInScreen(b);xml.attribute(null,"package",node.packageName?.toString().orEmpty());xml.attribute(null,"text",node.text?.toString().orEmpty());xml.attribute(null,"content-desc",node.contentDescription?.toString().orEmpty());xml.attribute(null,"bounds",b.toShortString());for(i in 0 until node.childCount)node.getChild(i)?.let{visit(it,depth+1)};xml.endTag(null,"node")};visit(root,0);xml.endDocument()}
        check(ui.rootInActiveWindow?.packageName?.toString()==context.packageName)
        File(folder,"$name-receipt.json").writeText(JSONObject().put("apkSha256",sha(File(context.applicationInfo.sourceDir).readBytes())).put("pngSha256",sha(png.readBytes())).put("xmlSha256",sha(file.readBytes())).put("beforeAndAfterPackageGuard",true).put("syntheticOnly",true).toString())
    }
    private fun launch(){
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL=="http://127.0.0.1:18090")
        scenario=ActivityScenario.launch(MainActivity::class.java);scenario!!.onActivity{vm=ViewModelProvider(it)[BooksModel::class.java]}
        if(vm.token==null){val input=JSONObject(File(context.filesDir,"qa-config.json").readText());scenario!!.onActivity{vm.login(input.getString("username"),input.getString("password"),"")}}
        idle()
    }
    private fun navigate(name:String){
        if(name in setOf("搜索","家庭","设置")){
            if(compose.onAllNodesWithContentDescription("打开菜单").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()){compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick();compose.waitForIdle()}
            compose.onNodeWithContentDescription("打开菜单").performClick();compose.onNodeWithTag("nav-$name").performClick()
        } else {
            navigate("设置");compose.onNodeWithText(name).performClick()
        }
        compose.waitForIdle();idle()
    }
    private fun settings(){navigate("设置");compose.onNodeWithTag("theme-settings").assertExists()}
    private fun assertBackground(dark:Boolean){
        compose.waitForIdle();android.os.SystemClock.sleep(250)
        val bitmap=inst.uiAutomation.takeScreenshot()?:error("screenshot_missing")
        val bounds=compose.onNodeWithTag("theme-settings").fetchSemanticsNode().boundsInRoot
        // Empty right edge beside the title, far from text, radios and touch ripples.
        val x=(bounds.right-3).toInt().coerceIn(0,bitmap.width-1);val y=(bounds.top+3).toInt().coerceIn(0,bitmap.height-1)
        val color=bitmap.getPixel(x,y);bitmap.recycle();val r=android.graphics.Color.red(color);val g=android.graphics.Color.green(color);val b=android.graphics.Color.blue(color)
        if(dark)check(r<65&&g<65&&b<65){"dark_background_missing"}else check(r>220&&g>220&&b>220){"light_background_missing"}
    }
    private fun fingerprint():String=runBlocking{
        val accounts=vm.api.accounts().sortedBy{it.id()}.map{org.json.JSONArray(listOf(it.id(),it.optString("currency"),it.optString("balance")))}
        val rows=vm.api.completeSearch(LedgerSearchFilter()).items.sortedBy{it.id()}.map{row->
            val fields=listOf("id","type","categoryId","time","utcOffset","sourceAccountId","sourceAmount","destinationAccountId","destinationAmount","comment","hideAmount").map{row.optString(it)}
            val tags=row.optJSONArray("tagIds")?.let{list->(0 until list.length()).map{list.getString(it)}}?:row.array("tags").map{it.id()}
            org.json.JSONArray(fields+listOf(org.json.JSONArray(tags.sorted()),org.json.JSONArray(row.array("pictures").map{it.id("pictureId")}.sorted())))
        }
        sha(org.json.JSONArray(listOf(org.json.JSONArray(accounts),org.json.JSONArray(rows))).toString().toByteArray())
    }
    private fun shellNight(mode:String){check(mode in setOf("yes","no","auto"));android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cmd uimode night $mode")).use{it.readBytes()};android.os.SystemClock.sleep(350)}
    private fun pages(prefix:String){
        capture("$prefix-home")
        val pictured=vm.transactions.firstOrNull{it.array("pictures").isNotEmpty()}?:error("synthetic_picture_fixture_missing")
        val picture=pictured.array("pictures").first();val extension=android.net.Uri.parse(picture.optString("originalUrl")).path?.substringAfterLast('.')?.lowercase();check(extension in setOf("jpg","jpeg","png","webp"))
        val imageSha=runBlocking{sha(vm.api.picture("/api/v1/family/pictures/${picture.id("pictureId")}.$extension"))}
        val imageFile=File(folder,"picture-original-sha.txt");if(imageFile.exists())check(imageFile.readText()==imageSha)else imageFile.writeText(imageSha)
        compose.onNodeWithTag("ledger-list").performScrollToNode(hasTestTag("transaction-${pictured.id()}"));compose.onNodeWithTag("transaction-${pictured.id()}").performClick()
        compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("交易图片附件").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};capture("$prefix-picture");back()

        compose.onNodeWithContentDescription("日历视图").performClick();compose.waitUntil(30_000){exists("calendar-month-card")&&!vm.busy};capture("$prefix-calendar");compose.onNodeWithContentDescription("返回流水").performClick();idle()
        compose.onNodeWithContentDescription("月报表").performClick();compose.waitUntil(30_000){exists("report-list")};capture("$prefix-report");compose.onNodeWithContentDescription("返回").performClick()
        navigate("搜索");compose.waitUntil(45_000){compose.onAllNodesWithText("CNY · 完整匹配结果").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};capture("$prefix-search");back()
        compose.onNodeWithTag("add-transaction").performClick();compose.waitUntil(5000){exists("entry-categories")};capture("$prefix-entry")
        compose.onNodeWithTag("entry-account").performClick();capture("$prefix-account-picker");back()
        compose.onNodeWithTag("entry-date").performClick();capture("$prefix-date")
        compose.onNodeWithTag("entry-date-switch-time").performClick();compose.waitUntil(5000){exists("entry-date-clock")};capture("$prefix-clock");compose.onNodeWithTag("entry-date-cancel").performClick()
        compose.onNode(hasText("#") and hasClickAction()).performClick();capture("$prefix-tags");back();compose.onNodeWithContentDescription("取消记账").performClick();idle()
        for(name in listOf("账户","家庭","分类与标签","登录设备","关于")){navigate(name);capture("$prefix-"+when(name){"账户"->"accounts";"家庭"->"family";"分类与标签"->"catalog";"登录设备"->"sessions";else->"about"});back()}
    }
    @Test fun stage1ActualThemeSwitchSystemAndReadOnlyPages(){
        var stage="launch"
        try{launch();val prefs=context.getSharedPreferences("appearance",0);val night=context.getSystemService(UiModeManager::class.java).nightMode;check(night in 0..2)
            val originalFile=File(folder,"original.json");val original=if(originalFile.exists())JSONObject(originalFile.readText())else JSONObject().put("themePresent",prefs.contains("theme")).put("theme",prefs.getString("theme",null)).put("night",night).put("businessSha",fingerprint()).also{originalFile.writeText(it.toString())}
            if(!original.has("stableBusinessSha")){original.put("stableBusinessSha",fingerprint()).put("businessFingerprintSchema","stable-financial-fields-picture-ids-v1");originalFile.writeText(original.toString())}
            for((key,dark)in listOf("light" to false,"dark" to true)){stage="choose-$key";settings();compose.onNodeWithTag("theme-$key").performClick();compose.onNodeWithTag("theme-$key").assertIsSelected();assertBackground(dark);capture("$key-settings");stage="pages-$key";pages(key)}
            stage="follow-system";settings();compose.onNodeWithTag("theme-system").performClick();shellNight("yes");compose.waitUntil(5000){context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES};assertBackground(true);capture("system-dark")
            shellNight("no");compose.waitUntil(5000){context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_NO};assertBackground(false);capture("system-light")
            stage="persist";compose.onNodeWithTag("theme-light").performClick();assertBackground(false);scenario?.close();scenario=ActivityScenario.launch(MainActivity::class.java);scenario!!.onActivity{vm=ViewModelProvider(it)[BooksModel::class.java]};idle();settings();scenario!!.recreate();idle();compose.onNodeWithTag("theme-light").assertIsSelected();assertBackground(false);capture("light-after-activity-recreate")
            check(fingerprint()==original.getString("stableBusinessSha"));File(folder,"stage1-result.json").writeText(JSONObject().put("passed",true).put("bothThemesActualPages",true).put("systemLightDarkActualCommands",true).put("activityRecreationKeepsLight",true).put("businessUnchanged",true).put("readyForExactPackageColdStart",true).toString())
        }catch(e:Throwable){runCatching{File(folder,"sanitized-failure-stack.txt").writeText(e.javaClass.name+"\n"+e.stackTrace.joinToString("\n"))};runCatching{capture("90-failure-$stage")};throw AssertionError("Theme synthetic stage=$stage class=${e.javaClass.simpleName}")}
        finally{scenario?.close()}
    }
    @Test fun stage2ActualColdStartKeepsSelectionAndRestoresOriginalPreference(){
        var stage="cold-launch"
        try{launch();val original=JSONObject(File(folder,"original.json").readText());val prefs=context.getSharedPreferences("appearance",0);check(prefs.getString("theme",null)=="light");settings();compose.onNodeWithTag("theme-light").assertIsSelected();assertBackground(false);capture("light-after-real-cold-start");check(fingerprint()==original.getString("stableBusinessSha"))
            stage="restore";check((if(original.getBoolean("themePresent"))prefs.edit().putString("theme",original.getString("theme"))else prefs.edit().remove("theme")).commit());shellNight(when(original.getInt("night")){0->"auto";1->"no";else->"yes"});back()
            File(folder,"stage2-result.json").writeText(JSONObject().put("passed",true).put("lightPreferenceRetainedAcrossRealColdStart",true).put("originalPreferenceAndSystemModeRestored",true).put("businessUnchanged",true).toString())
        }catch(e:Throwable){throw AssertionError("Theme synthetic stage=$stage class=${e.javaClass.simpleName}")}
        finally{scenario?.close()}
    }
}
