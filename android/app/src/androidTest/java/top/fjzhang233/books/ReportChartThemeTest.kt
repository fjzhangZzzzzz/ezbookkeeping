package top.fjzhang233.books

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.time.YearMonth
import java.security.MessageDigest

/** Offline synthetic chart: real Activity/window/system-bar and semantic net paint checks on each runtime. */
class ReportChartThemeTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun actualFullscreenWindowAndSignedNetVisibleInLightAndDark(){
        val prefs=context.getSharedPreferences("appearance",0);val present=prefs.contains("theme");val previous=prefs.getString("theme",null)
        val folder=File(context.filesDir,"report-chart-theme-api${android.os.Build.VERSION.SDK_INT}").apply{mkdirs()}
        val period=ReportPeriod(YearMonth.of(2026,11),yearly=true)
        val data=ReportSnapshot(period,(0 until period.days).map{offset->val date=period.first.plusDays(offset.toLong());when{date.monthValue==1&&date.dayOfMonth==10->ReportDay(date,BigInteger.valueOf(100),BigInteger.valueOf(300));date.monthValue==11&&date.dayOfMonth==14->ReportDay(date,BigInteger.valueOf(500),BigInteger.valueOf(100));else->ReportDay(date,BigInteger.ZERO,BigInteger.ZERO)}},emptyList(),false)
        var scenario:ActivityScenario<ReportChartActivity>?=null;var stage="initial"
        try{
            for((mode,dark)in listOf("light" to false,"dark" to true)){
                stage="$mode-launch";check(prefs.edit().putString("theme",mode).commit())
                scenario=ActivityScenario.launch(Intent(context,ReportChartActivity::class.java).putExtra("chart",encodeReportChart(data,4,false)))
                compose.waitUntil(5000){compose.onAllNodesWithTag("report-chart-fullscreen").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};compose.waitForIdle()
                stage="$mode-system-bars";scenario!!.onActivity{a->check(a.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);val control=WindowCompat.getInsetsController(a.window,a.window.decorView);check(control.isAppearanceLightStatusBars==!dark&&control.isAppearanceLightNavigationBars==!dark);check(a.window.statusBarColor==android.graphics.Color.TRANSPARENT);check(a.window.navigationBarColor==android.graphics.Color.TRANSPARENT)}
                for(lines in listOf(false,true)){
                    if(lines)compose.onNodeWithText("折线图",substring=false).performClick();compose.waitForIdle();android.os.SystemClock.sleep(300)
                    val ui=inst.uiAutomation;val info=ui.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.serviceInfo=info;compose.waitUntil(5000){ui.rootInActiveWindow?.packageName?.toString()==context.packageName};check(ui.rootInActiveWindow?.packageName?.toString()==context.packageName)
                    val image=checkNotNull(ui.takeScreenshot());check(image.width>image.height)
                    val canvas=compose.onNodeWithTag("report-interactive-chart").fetchSemanticsNode().boundsInRoot
                    val ink=ledgerPalette(dark).accent;val expected=intArrayOf((ink.red*255).toInt(),(ink.green*255).toInt(),(ink.blue*255).toInt());var matches=0
                    for(y in canvas.top.toInt().coerceAtLeast(0)until canvas.bottom.toInt().coerceAtMost(image.height))for(x in canvas.left.toInt().coerceAtLeast(0)until canvas.right.toInt().coerceAtMost(image.width)){val pixel=image.getPixel(x,y);if(kotlin.math.abs(android.graphics.Color.red(pixel)-expected[0])<=2&&kotlin.math.abs(android.graphics.Color.green(pixel)-expected[1])<=2&&kotlin.math.abs(android.graphics.Color.blue(pixel)-expected[2])<=2)matches++}
                    check(matches>20){"net_series_paint_not_visible"}
                    val top=image.getPixel(image.width/2,5);if(dark)check(android.graphics.Color.red(top)<65)else check(android.graphics.Color.red(top)>220)
                    val name="$mode-"+if(lines)"line"else"bar";val png=File(folder,"$name.png");png.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                    val root=checkNotNull(ui.rootInActiveWindow);check(root.packageName?.toString()==context.packageName)
                    val xmlFile=File(folder,"$name.xml");xmlFile.outputStream().use{out->val xml=android.util.Xml.newSerializer();xml.setOutput(out,"UTF-8");xml.startDocument("UTF-8",true);fun visit(node:android.view.accessibility.AccessibilityNodeInfo,depth:Int){check(depth<35&&!node.isPassword);xml.startTag(null,"node");val b=android.graphics.Rect();node.getBoundsInScreen(b);xml.attribute(null,"package",node.packageName?.toString().orEmpty());xml.attribute(null,"text",node.text?.toString().orEmpty());xml.attribute(null,"content-desc",node.contentDescription?.toString().orEmpty());xml.attribute(null,"bounds",b.toShortString());for(i in 0 until node.childCount)node.getChild(i)?.let{visit(it,depth+1)};xml.endTag(null,"node")};visit(root,0);xml.endDocument()}
                    check(ui.rootInActiveWindow?.packageName?.toString()==context.packageName)
                    File(folder,"$name-receipt.json").writeText(JSONObject().put("apkSha256",sha(File(context.applicationInfo.sourceDir).readBytes())).put("pngSha256",sha(png.readBytes())).put("xmlSha256",sha(xmlFile.readBytes())).put("runtimeApi",android.os.Build.VERSION.SDK_INT).put("foregroundPackageGuard",true).put("landscape",true).put("statusAndNavigationBarsTransparent",true).put("systemIconsMatchTheme",true).put("actualNetSeriesPixels",matches).toString())
                }
                stage="$mode-close";compose.onNodeWithText("关闭",substring=false).performClick();compose.waitUntil(5000){scenario!!.state==Lifecycle.State.DESTROYED};scenario?.close();scenario=null
            }
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("runtimeApi",android.os.Build.VERSION.SDK_INT).put("offlineSyntheticDataOnly",true).put("noCredentialsOrBackend",true).put("positiveAndNegativeNet",true).put("bothThemesAndBothChartKinds",true).toString())
        }catch(error:Throwable){runCatching{val ui=inst.uiAutomation;check(ui.rootInActiveWindow?.packageName?.toString()==context.packageName);val bitmap=checkNotNull(ui.takeScreenshot());File(folder,"90-failure.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()};File(folder,"sanitized-stack.txt").writeText(error.javaClass.name+"\n"+error.stackTrace.joinToString("\n"));throw AssertionError("Report chart theme stage=$stage class=${error.javaClass.simpleName}")}
        finally{scenario?.close();check((if(present)prefs.edit().putString("theme",previous)else prefs.edit().remove("theme")).commit())}
    }
}
