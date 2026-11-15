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

/** Read-only isolated host acceptance. Does NOT certify Main navigation or shared real details. */
class SearchAcceptanceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private var activity:ActivityScenario<MainActivity>?=null
    private fun model(block:(BooksModel)->Unit){activity!!.onActivity{block(ViewModelProvider(it)[BooksModel::class.java])}}
    private fun nodes():List<AccessibilityNodeInfo>{
        val root=instrumentation.uiAutomation.rootInActiveWindow?:return emptyList()
        if(root.packageName?.toString()!=context.packageName)return emptyList()
        val list=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo){list+=node;for(index in 0 until node.childCount)node.getChild(index)?.let(::visit)}
        visit(root);return list
    }
    private fun waitFor(label:String,predicate:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+45_000;while(android.os.SystemClock.elapsedRealtime()<end){if(predicate())return;android.os.SystemClock.sleep(150)};throw AssertionError("Search isolated stage timed out: $label")}
    private fun has(text:String)=nodes().any{it.text?.toString()==text}
    private fun tap(label:String){
        val node=nodes().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}?:error("Search control missing")
        var target:AccessibilityNodeInfo?=node
        while(target!=null&&!target.isClickable)target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun checkTouchTargets(){
        val labels=listOf("不分组","按天","按月","本月","上月","今年","去年","全部","开始日期","截止日期")
        val measured=org.json.JSONArray()
        labels.forEach{label->var node:AccessibilityNodeInfo?=nodes().lastOrNull{it.text?.toString()==label}?:error("Date touch label missing");while(node!=null&&!node.isClickable)node=node.parent;check(node!=null);val rect=android.graphics.Rect();node!!.getBoundsInScreen(rect);val dp=rect.height()/context.resources.displayMetrics.density;check(dp>=47.9f){"Date touch target below48dp"};measured.put(JSONObject().put("label",label).put("bounds",rect.toShortString()).put("heightDp",dp))}
        File(context.filesDir,"qa-search-touch-targets.json").writeText(measured.toString())
    }
    private fun screenshot(name:String){android.os.SystemClock.sleep(350);val captured=nodes();check(captured.isNotEmpty());val xml=android.util.Xml.newSerializer();File(context.cacheDir,"qa-search-$name.xml").outputStream().use{out->xml.setOutput(out,"UTF-8");xml.startDocument("UTF-8",true);xml.startTag(null,"accessibility-snapshot");xml.attribute(null,"source","native-accessibility-flat");xml.attribute(null,"package",context.packageName);captured.forEach{node->xml.startTag(null,"node");xml.attribute(null,"text",node.text?.toString().orEmpty());xml.attribute(null,"content-desc",node.contentDescription?.toString().orEmpty());xml.attribute(null,"class",node.className?.toString().orEmpty());val rect=android.graphics.Rect();node.getBoundsInScreen(rect);xml.attribute(null,"bounds",rect.toShortString());xml.attribute(null,"clickable",node.isClickable.toString());xml.endTag(null,"node")};xml.endTag(null,"accessibility-snapshot");xml.endDocument()};val bitmap=instrumentation.uiAutomation.takeScreenshot()?:error("screenshot failed");File(context.cacheDir,"qa-search-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    @Test fun existingDataSearchFiltersSummaryClearAndEmptyState(){
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL in setOf("http://127.0.0.1:18090","http://10.0.2.2:18090"))
        val fixture=File(context.filesDir,"qa-config.json");assumeTrue(fixture.isFile)
        val input=JSONObject(fixture.readText());var helperToken:String?=null
        val api=BooksApi(BuildConfig.API_BASE_URL){helperToken}
        var stage="helper-login"
        try{
            runBlocking{helperToken=api.login(input.getString("username"),input.getString("password"),"").getString("token")}
            val expected=runBlocking{api.completeSearch(LedgerSearchFilter())};check(expected.items.isNotEmpty())
            val comment=expected.items.firstOrNull{it.optString("comment").isNotBlank()}?.optString("comment")?:error("Synthetic comment required")
            val matching=runBlocking{api.completeSearch(LedgerSearchFilter(keyword=comment))}
            stage="launch";activity=ActivityScenario.launch(MainActivity::class.java)
            var logged=false;model{logged=it.token!=null;if(!logged)it.login(input.getString("username"),input.getString("password"),"")}
            waitFor("native-ready"){var ready=false;model{ready=it.token!=null&&!it.busy};ready}
            stage="independent-search-host";activity!!.onActivity{host->val vm=ViewModelProvider(host)[BooksModel::class.java];host.setContent{FamilyTheme{var closed by remember{mutableStateOf(false)};if(closed)Text("搜索主体已关闭")else LedgerSearch(vm,{closed=true}){_,_->error("Detail must be verified in the final shared-component integration, not this body test")}}}}
            waitFor("all-complete"){has("${expected.items.size}笔账单")&&has("CNY · 完整匹配结果")};screenshot("all");tap("全部");waitFor("date-panel"){has("分组")};screenshot("date");checkTouchTargets();instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor("date-cancelled"){has("搜索汇总")&&!has("分组")}
            stage="date-quick";val today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));val previous=java.time.YearMonth.from(today).minusMonths(1)
            val twoYears="${today.year-1}~${today.year}";val twoYearResult=runBlocking{api.completeSearch(LedgerSearchFilter(firstDate="${today.year-1}-01-01",lastDate="${today.year}-12-31"))}
            tap("全部");waitFor("two-years-panel"){has(twoYears)};tap(twoYears);waitFor("two-years-applied"){!has("分组")&&has(twoYears)&&has("${twoYearResult.items.size}笔账单")};screenshot("date-two-years")
            tap(twoYears);waitFor("two-years-restore-panel"){has("分组")};tap("全部");waitFor("two-years-restored"){!has("分组")&&has("${expected.items.size}笔账单")}
            val previousResult=runBlocking{api.completeSearch(LedgerSearchFilter(firstDate=previous.atDay(1).toString(),lastDate=previous.atEndOfMonth().toString()))}
            tap("全部");waitFor("date-quick-panel"){has("分组")};tap("上月");waitFor("previous-month-applied"){!has("分组")&&has("上月")&&has("${previousResult.items.size}笔账单")};screenshot("date-quick")
            tap("上月");waitFor("group-panel"){has("分组")};tap("按月");waitFor("group-applied"){!has("分组")&&has("搜索汇总")}
            tap("上月");waitFor("all-restore-panel"){has("分组")};tap("全部");waitFor("all-restore"){!has("分组")&&has("${expected.items.size}笔账单")}
            tap("全部");waitFor("day-group-panel"){has("分组")};tap("按天");waitFor("day-group-applied"){!has("分组")&&has("搜索汇总")}
            stage="custom-date";tap("全部");waitFor("custom-panel"){has("分组")};tap("开始日期");waitFor("date-wheel"){nodes().any{it.className?.toString()=="android.widget.NumberPicker"}};screenshot("date-wheel");tap("昨");tap("确定")
            val custom=runBlocking{api.completeSearch(LedgerSearchFilter(firstDate=today.minusDays(1).toString()))}
            waitFor("custom-applied"){!has("分组")&&has("自定义")&&has("${custom.items.size}笔账单")};screenshot("date-custom")
            tap("自定义");waitFor("custom-reopen"){has("分组")};tap("截止日期");waitFor("end-date-wheel"){nodes().any{it.className?.toString()=="android.widget.NumberPicker"}};tap("前");tap("确定");waitFor("invalid-range-rejected"){has("截止日期不能早于开始日期")};screenshot("date-invalid")
            tap(today.minusDays(2).toString());waitFor("end-date-correct"){nodes().any{it.className?.toString()=="android.widget.NumberPicker"}};tap("今");tap("确定");waitFor("range-applied"){!has("分组")&&has("自定义")&&has("搜索汇总")}
            tap("自定义");waitFor("final-date-restore-panel"){has("分组")};tap("全部");waitFor("date-restored"){!has("分组")&&has("${expected.items.size}笔账单")}
            stage="keyword";val field=nodes().firstOrNull{it.className?.toString()=="android.widget.EditText"}?:error("Search input missing")
            val args=android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,comment)}
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));waitFor("keyword-edit-applied"){nodes().any{it.className?.toString()=="android.widget.EditText"&&it.text?.toString()==comment}};tap("搜索")
            waitFor("keyword-complete"){has("${matching.items.size}笔账单")&&has("CNY · 完整匹配结果")};screenshot("keyword")
            stage="clear";tap("清除搜索");waitFor("clear-complete"){has("${expected.items.size}笔账单")&&has("CNY · 完整匹配结果")}
            stage="images";tap("筛选");waitFor("filter-open"){has("仅含图片的账单")};screenshot("filters");tap("仅含图片的账单");tap("确定")
            val pictures=runBlocking{api.completeSearch(LedgerSearchFilter(picturesOnly=true))}
            waitFor("pictures-complete"){has("${pictures.items.size}笔账单")&&has("CNY · 完整匹配结果")};screenshot("pictures")
            stage="no-match";val inputNode=nodes().firstOrNull{it.className?.toString()=="android.widget.EditText"}?:error("Search input missing")
            val absent="qa-no-match-"+java.util.UUID.randomUUID().toString()
            val emptyArgs=android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,absent)}
            check(inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,emptyArgs));tap("搜索")
            waitFor("empty-complete"){has("0笔账单")&&has("暂无匹配账单")};screenshot("empty")
            stage="close";tap("返回");waitFor("body-closed"){has("搜索主体已关闭")}
            File(context.filesDir,"qa-search-body-result.json").writeText(JSONObject().put("passed",true).put("readOnly",true).put("scope","independent-search-body-not-main-or-details").put("allCount",expected.items.size).put("matchedCount",matching.items.size).put("picturesCount",pictures.items.size).put("tested",org.json.JSONArray(listOf("open","date-back","date-two-years","date-quick","group-immediate","custom-date","date-invalid","keyword","clear","pictures","empty","back"))).toString())
        }catch(error:Throwable){runCatching{screenshot("failure-"+stage)};throw AssertionError("Isolated search stage=$stage class=${error.javaClass.simpleName}")}
        finally{if(helperToken!=null)runBlocking{runCatching{api.call("/api/logout.json")}};activity?.close()}
    }
}
