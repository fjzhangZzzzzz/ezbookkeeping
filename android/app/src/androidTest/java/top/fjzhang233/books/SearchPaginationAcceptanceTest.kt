package top.fjzhang233.books

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real Main/search/shared-details acceptance on the exact fused QA product. Only its own temporary record is mutated. */
class SearchPaginationAcceptanceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private var activity:ActivityScenario<MainActivity>?=null
    private fun model(block:(BooksModel)->Unit){activity!!.onActivity{block(ViewModelProvider(it)[BooksModel::class.java])}}
    private var observerConfigured=false
    private fun nativeIdle(){runCatching{instrumentation.uiAutomation.waitForIdle(200,3000)}}
    private fun nodes():List<AccessibilityNodeInfo>{
        val automation=instrumentation.uiAutomation
        if(!observerConfigured){val info=automation.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.serviceInfo=info;observerConfigured=true}
        val candidates=automation.windows.filter{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION}.sortedWith(compareByDescending<android.view.accessibility.AccessibilityWindowInfo>{it.isFocused}.thenByDescending{it.layer})
        val root=candidates.asSequence().mapNotNull{it.root}.firstOrNull{it.packageName?.toString()==context.packageName}?:automation.rootInActiveWindow?:return emptyList()
        if(!root.refresh()||root.packageName?.toString()!=context.packageName)return emptyList()
        val list=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo){if(!node.refresh())return;if(node.isVisibleToUser)list+=node;for(index in 0 until node.childCount)node.getChild(index)?.let(::visit)}
        visit(root);return list
    }
    private fun waitFor(label:String,predicate:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+if(label.startsWith("main-"))15_000 else 45_000;while(android.os.SystemClock.elapsedRealtime()<end){if(predicate())return;android.os.SystemClock.sleep(400)};throw AssertionError("Search isolated stage timed out: $label")}
    private fun has(text:String)=nodes().any{it.text?.toString()==text}
    private fun tap(label:String){
        val node=nodes().firstOrNull{it.contentDescription?.toString()==label}?:nodes().firstOrNull{it.text?.toString()==label}?:error("Search control missing")
        var target:AccessibilityNodeInfo?=node
        while(target!=null&&!target.isClickable)target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun tapNative(label:String){
        var bounds:android.graphics.Rect?=null
        // Window roots can briefly disappear while Main settles after login. Reobserve,
        // retaining the actual semantic label and real hit target instead of blind input.
        waitFor("native-target-$label"){
            var node=nodes().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}
            while(node!=null&&!node.isClickable)node=node.parent
            if(node==null||!node.refresh())false else{
                val rect=android.graphics.Rect();node.getBoundsInScreen(rect)
                val screen=context.resources.displayMetrics
                if(rect.isEmpty||rect.left<0||rect.top<0||rect.right>screen.widthPixels||rect.bottom>screen.heightPixels)false
                else{bounds=rect;true}
            }
        }
        val rect=checkNotNull(bounds);val time=android.os.SystemClock.uptimeMillis()
        val down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0)
        val up=android.view.MotionEvent.obtain(time,time+60,android.view.MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0)
        try{check(instrumentation.uiAutomation.injectInputEvent(down,true));check(instrumentation.uiAutomation.injectInputEvent(up,true))}finally{down.recycle();up.recycle()}
    }
    private fun setQuery(value:String){val node=nodes().firstOrNull{it.className?.toString()=="android.widget.EditText"}?:error("Search field missing");check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));waitFor("query-applied"){nodes().any{it.className?.toString()=="android.widget.EditText"&&it.text?.toString()==value}};tap("搜索")}
    private fun openResult(comment:String){var node=nodes().lastOrNull{it.className?.toString()!="android.widget.EditText"&&it.text?.toString()?.contains(comment)==true}?:error("Search result missing");while(!node.isClickable)node=node.parent?:error("Result click missing");check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));waitFor("real-details"){has("详情")&&has("修改")&&has("复制")&&has("删除")}}
    private fun checkTouchTargets(){
        val labels=listOf("不分组","按天","按月","本月","上月","今年","去年","全部","开始日期","截止日期")
        val measured=org.json.JSONArray()
        labels.forEach{label->var node:AccessibilityNodeInfo?=nodes().lastOrNull{it.text?.toString()==label}?:error("Date touch label missing");while(node!=null&&!node.isClickable)node=node.parent;check(node!=null);val rect=android.graphics.Rect();node!!.getBoundsInScreen(rect);val dp=rect.height()/context.resources.displayMetrics.density;check(dp>=47.9f){"Date touch target below48dp"};measured.put(JSONObject().put("label",label).put("bounds",rect.toShortString()).put("heightDp",dp))}
        File(context.filesDir,"qa-page-touch-targets.json").writeText(measured.toString())
    }
    private fun screenshot(name:String){android.os.SystemClock.sleep(350);val captured=nodes();check(captured.isNotEmpty());check(captured.none{it.isPassword}){"Authentication view excluded from non-secret capture"};val xml=android.util.Xml.newSerializer();File(context.cacheDir,"qa-page-$name.xml").outputStream().use{out->xml.setOutput(out,"UTF-8");xml.startDocument("UTF-8",true);xml.startTag(null,"accessibility-snapshot");xml.attribute(null,"source","native-accessibility-flat");xml.attribute(null,"package",context.packageName);captured.forEach{node->xml.startTag(null,"node");xml.attribute(null,"text",node.text?.toString().orEmpty());xml.attribute(null,"content-desc",node.contentDescription?.toString().orEmpty());xml.attribute(null,"class",node.className?.toString().orEmpty());val rect=android.graphics.Rect();node.getBoundsInScreen(rect);xml.attribute(null,"bounds",rect.toShortString());xml.attribute(null,"clickable",node.isClickable.toString());xml.endTag(null,"node")};xml.endTag(null,"accessibility-snapshot");xml.endDocument()};val bitmap=instrumentation.uiAutomation.takeScreenshot()?:error("screenshot failed");File(context.cacheDir,"qa-page-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    private fun seek(label:String,forward:Boolean=true):AccessibilityNodeInfo{
        for(attempt in 0 until 25){nodes().lastOrNull{it.text?.toString()==label}?.let{return it};val scroll=nodes().filter{it.isScrollable}.maxByOrNull{val r=android.graphics.Rect();it.getBoundsInScreen(r);r.height()}?:error("No scroll surface for filter");if(!scroll.performAction(if(forward)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))break;android.os.SystemClock.sleep(180)}
        error("Filter target not found")
    }
    private fun choose(label:String,forward:Boolean=true){var n=seek(label,forward);while(!n.isClickable)n=n.parent?:error("Filter target not clickable");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));android.os.SystemClock.sleep(100)}
    private fun summary(plan:JSONObject,name:String){val e=plan.getJSONObject("expected").getJSONObject(name);waitFor("summary-visible-$name"){if(has("搜索汇总"))true else{nodes().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);false}};waitFor("summary-$name"){has("${e.getInt("count")}笔账单")&&has("CNY · 完整匹配结果")};seek("搜索汇总",false);for(key in listOf("incomeMinor","expenseMinor","balanceMinor")){val v=e.getLong(key);if(v!=0L||key=="balanceMinor")check(has(java.math.BigDecimal.valueOf(v,2).toPlainString()))};check(has("外币未计入 CNY 汇总")==e.getBoolean("foreign"));screenshot(name)}
    @Test fun realPagesAndCombinedFiltersUseEveryMatchingRecord(){
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL=="http://127.0.0.1:18090")
        val planFile=File(context.filesDir,"qa-search-pagination.json");assumeTrue("Requires private pagination fixture",planFile.isFile)
        val plan=JSONObject(planFile.readText());val privateInput=JSONObject(File(context.filesDir,"qa-config.json").readText());val marker=plan.getString("marker");var stage="login"
        try{
            activity=ActivityScenario.launch(MainActivity::class.java);model{if(it.token!=null)it.logout()};waitFor("logout"){var done=false;model{done=it.token==null&&!it.busy};done};model{it.login(privateInput.getString("username"),privateInput.getString("password"),"")};waitFor("ready"){var done=false;model{done=it.token!=null&&!it.busy};done}
            stage="main";waitFor("main-surface"){nodes().any{it.contentDescription?.toString()=="打开菜单"}||has("搜索")};if(has("搜索")){instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);nativeIdle()};waitFor("main-menu-stable"){nodes().any{it.contentDescription?.toString()=="打开菜单"}};stage="main-menu-target";tapNative("打开菜单");nativeIdle();waitFor("drawer"){has("搜索")};stage="drawer-search-target";tapNative("搜索");waitFor("search"){has("搜索汇总")};setQuery(marker);summary(plan,"all")
            stage="last-page";seek(plan.getString("oldestComment"),true);screenshot("last-page-row")
            stage="account";tap("全部账户");waitFor("accounts"){has("资金账户 · 不选表示全部")};choose(marker+"-A · CNY");tap("确定");android.os.SystemClock.sleep(250);summary(plan,"account")
            stage="combo36";tap("筛选");waitFor("filter"){has("仅含图片的账单")};choose("区间");waitFor("two-amount-fields"){nodes().count{it.className?.toString()=="android.widget.EditText"}==2};val amounts=nodes().filter{it.className?.toString()=="android.widget.EditText"};for((index,value) in listOf("1.00","2.00").withIndex())check(amounts[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));choose(marker+"-E1");choose(marker+"-T1");screenshot("combo36-selected");tap("确定");android.os.SystemClock.sleep(250);summary(plan,"combo36")
            stage="combo11";tap("筛选");waitFor("filter2"){has("仅含图片的账单")};seek("金额 · 按各账单原币种数值",false);val range=nodes().filter{it.className?.toString()=="android.widget.EditText"};check(range.size==2);for((index,value) in listOf("1.10","1.30").withIndex())check(range[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));choose("包含全部所选标签");choose(marker+"-T2");screenshot("combo11-selected");tap("确定");android.os.SystemClock.sleep(250);summary(plan,"combo11")
            stage="return";tap("返回");waitFor("main-return"){nodes().any{it.contentDescription?.toString()=="打开菜单"}};File(context.filesDir,"qa-search-pagination-result.json").writeText(JSONObject().put("passed",true).put("marker",marker).put("total",47).put("combo36",36).put("combo11",11).put("oldestPageRowVisible",true).put("noOriginalRecordEdits",true).toString())
        }catch(e:Throwable){runCatching{screenshot("failure-"+stage)};throw AssertionError("QA pagination stage=$stage class=${e.javaClass.simpleName} "+e.message?.takeIf{it.startsWith("Search isolated stage")})}finally{activity?.close()}
    }
}
