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
class SearchMainAcceptanceTest {
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
        val node=nodes().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}?:error("Search control missing")
        var target:AccessibilityNodeInfo?=node
        while(target!=null&&!target.isClickable)target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun tapNative(label:String){var node=nodes().first{it.text?.toString()==label||it.contentDescription?.toString()==label};while(!node.isClickable)node=node.parent?:error("Native click missing");val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);check(!bounds.isEmpty&&bounds.left>=0);val time=android.os.SystemClock.uptimeMillis();val down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);val up=android.view.MotionEvent.obtain(time,time+60,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);try{check(instrumentation.uiAutomation.injectInputEvent(down,true));check(instrumentation.uiAutomation.injectInputEvent(up,true))}finally{down.recycle();up.recycle()}}
    private fun setQuery(value:String){val node=nodes().firstOrNull{it.className?.toString()=="android.widget.EditText"}?:error("Search field missing");check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));waitFor("query-applied"){nodes().any{it.className?.toString()=="android.widget.EditText"&&it.text?.toString()==value}};tap("搜索")}
    private fun openResult(comment:String){var node=nodes().lastOrNull{it.className?.toString()!="android.widget.EditText"&&it.text?.toString()?.contains(comment)==true}?:error("Search result missing");while(!node.isClickable)node=node.parent?:error("Result click missing");check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));waitFor("real-details"){has("详情")&&has("修改")&&has("复制")&&has("删除")}}
    private fun checkTouchTargets(){
        val labels=listOf("不分组","按天","按月","本月","上月","今年","去年","全部","开始日期","截止日期")
        val measured=org.json.JSONArray()
        labels.forEach{label->var node:AccessibilityNodeInfo?=nodes().lastOrNull{it.text?.toString()==label}?:error("Date touch label missing");while(node!=null&&!node.isClickable)node=node.parent;check(node!=null);val rect=android.graphics.Rect();node!!.getBoundsInScreen(rect);val dp=rect.height()/context.resources.displayMetrics.density;check(dp>=47.9f){"Date touch target below48dp"};measured.put(JSONObject().put("label",label).put("bounds",rect.toShortString()).put("heightDp",dp))}
        File(context.filesDir,"qa-search-touch-targets.json").writeText(measured.toString())
    }
    private fun screenshot(name:String){android.os.SystemClock.sleep(350);val captured=nodes();check(captured.isNotEmpty());check(captured.none{it.isPassword}){"Authentication view excluded from non-secret capture"};val xml=android.util.Xml.newSerializer();File(context.cacheDir,"qa-search-$name.xml").outputStream().use{out->xml.setOutput(out,"UTF-8");xml.startDocument("UTF-8",true);xml.startTag(null,"accessibility-snapshot");xml.attribute(null,"source","native-accessibility-flat");xml.attribute(null,"package",context.packageName);captured.forEach{node->xml.startTag(null,"node");xml.attribute(null,"text",node.text?.toString().orEmpty());xml.attribute(null,"content-desc",node.contentDescription?.toString().orEmpty());xml.attribute(null,"class",node.className?.toString().orEmpty());val rect=android.graphics.Rect();node.getBoundsInScreen(rect);xml.attribute(null,"bounds",rect.toShortString());xml.attribute(null,"clickable",node.isClickable.toString());xml.endTag(null,"node")};xml.endTag(null,"accessibility-snapshot");xml.endDocument()};val bitmap=instrumentation.uiAutomation.takeScreenshot()?:error("screenshot failed");File(context.cacheDir,"qa-search-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    @Test fun existingDataSearchFiltersSummaryClearAndEmptyState(){
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.API_BASE_URL in setOf("http://127.0.0.1:18090","http://10.0.2.2:18090"))
        val fixture=File(context.filesDir,"qa-config.json");assumeTrue(fixture.isFile)
        val input=JSONObject(fixture.readText());var helperToken:String?=null
        val api=BooksApi(BuildConfig.API_BASE_URL){helperToken}
        var stage="helper-login";var temporaryId:String?=null
        try{
            runBlocking{helperToken=api.login(input.getString("username"),input.getString("password"),"").getString("token")}
            val expected=runBlocking{api.completeSearch(LedgerSearchFilter())};check(expected.items.isNotEmpty())
            val comment=expected.items.first{it.optString("comment").isNotBlank()&&it.array("pictures").isNotEmpty()}.getString("comment")
            val matching=runBlocking{api.completeSearch(LedgerSearchFilter(keyword=comment))}
            stage="launch";activity=ActivityScenario.launch(MainActivity::class.java)
            stage="ui-login";model{if(it.token!=null)it.logout()};waitFor("fresh-login"){var loggedOut=false;model{loggedOut=it.token==null&&!it.busy};loggedOut};var logged=false;model{logged=it.token!=null}
            if(!logged){waitFor("login-fields"){nodes().count{it.className?.toString()=="android.widget.EditText"}>=2};val fields=nodes().filter{it.className?.toString()=="android.widget.EditText"};val user=fields.first{!it.isPassword};val password=fields.first{it.isPassword};check(user.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,input.getString("username"))}));check(password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,input.getString("password"))}));android.os.SystemClock.sleep(250);tap("登录")}
            waitFor("native-ready"){var ready=false;model{ready=it.token!=null&&!it.busy};ready}
            stage="main-initial";waitFor("main-known-surface"){nodes().any{it.contentDescription?.toString()=="打开菜单"}||has("搜索")};screenshot("main-initial");for(attempt in 0 until 2){if(nodes().any{it.contentDescription?.toString()=="打开菜单"})break;instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);nativeIdle();android.os.SystemClock.sleep(350)};stage="main-drawer";waitFor("main-menu"){nodes().any{it.contentDescription?.toString()=="打开菜单"}};android.os.SystemClock.sleep(300);tapNative("打开菜单");nativeIdle();android.os.SystemClock.sleep(350);waitFor("main-search-entry"){has("搜索")};screenshot("main-drawer");stage="main-search-open";tapNative("搜索");nativeIdle();waitFor("main-search-visible"){has("搜索汇总")};stage="main-search-all"
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
            waitFor("keyword-complete"){has("${matching.items.size}笔账单")&&has("CNY · 完整匹配结果")};screenshot("keyword");stage="existing-real-details";openResult(comment);waitFor("detail-picture"){nodes().any{it.contentDescription?.toString()=="交易图片附件"}};screenshot("shared-details");instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor("details-returned"){!has("详情")&&has("搜索汇总")&&has("${matching.items.size}笔账单")}
            stage="clear";tap("清除搜索");waitFor("clear-complete"){has("${expected.items.size}笔账单")&&has("CNY · 完整匹配结果")}
            stage="images";tap("筛选");waitFor("filter-open"){has("仅含图片的账单")};screenshot("filters");tap("仅含图片的账单");tap("确定")
            val pictures=runBlocking{api.completeSearch(LedgerSearchFilter(picturesOnly=true))}
            waitFor("pictures-complete"){has("${pictures.items.size}笔账单")&&has("CNY · 完整匹配结果")};screenshot("pictures")
            stage="no-match";val inputNode=nodes().firstOrNull{it.className?.toString()=="android.widget.EditText"}?:error("Search input missing")
            val absent="qa-no-match-"+java.util.UUID.randomUUID().toString()
            val emptyArgs=android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,absent)}
            check(inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,emptyArgs));tap("搜索")
            waitFor("empty-complete"){has("0笔账单")&&has("暂无匹配账单")};screenshot("empty")
            stage="temporary-fixture";tap("筛选");waitFor("temporary-reset-panel"){has("重置")};tap("重置");tap("确定");android.os.SystemClock.sleep(200);waitFor("temporary-reset-done"){!has("搜索中…")&&has("CNY · 完整匹配结果")};val marker="QA-search-main-"+java.util.UUID.randomUUID().toString().take(8);val changed="QA-search-after-"+java.util.UUID.randomUUID().toString().take(8)
            runBlocking{val account=api.accounts().first{it.optString("currency")=="CNY"&&!it.optBoolean("hidden")};val category=api.categories().first{it.optInt("type")==2&&it.id("parentId")!="0"&&!it.optBoolean("hidden")};val payload=JSONObject().put("type",3).put("categoryId",category.id()).put("time",java.time.Instant.now().epochSecond).put("utcOffset",480).put("sourceAccountId",account.id()).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("hideAmount",false).put("tagIds",org.json.JSONArray()).put("pictureIds",org.json.JSONArray()).put("comment",marker).put("clientSessionId",java.util.UUID.randomUUID().toString());temporaryId=(api.call("/api/v1/transactions/add.json",payload) as JSONObject).id();check(temporaryId!="0")}
            setQuery(marker);waitFor("temporary-result"){has("1笔账单")&&nodes().any{it.text?.toString()?.contains(marker)==true&&it.className?.toString()!="android.widget.EditText"}}
            stage="real-edit";openResult(marker);screenshot("no-picture-details");instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor("no-picture-single-back"){!has("详情")&&has("1笔账单")};openResult(marker);tap("修改");waitFor("real-editor"){nodes().any{it.contentDescription?.toString()=="取消记账"}&&has(marker)}
            val memo=nodes().first{it.className?.toString()=="android.widget.EditText"&&it.text?.toString()==marker};check(memo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,changed)}));waitFor("memo-applied"){has(changed)};tap("保存");waitFor("old-query-refresh"){has("0笔账单")&&has("暂无匹配账单")};screenshot("edit-return-empty")
            check(runBlocking{api.transaction(temporaryId!!)}.optString("comment")==changed)
            stage="real-copy-cancel";setQuery(changed);waitFor("edited-result"){has("1笔账单")};openResult(changed);tap("复制");waitFor("copied-draft"){has("复制流水 · 保存后生成新记录")&&nodes().any{it.contentDescription?.toString()=="取消记账"}};screenshot("copied-draft");tap("取消记账");waitFor("copy-cancel-return"){has("1笔账单")&&!has("复制流水 · 保存后生成新记录")};check(runBlocking{api.completeSearch(LedgerSearchFilter(keyword=changed))}.items.size==1)
            stage="real-delete";openResult(changed);tap("删除");waitFor("delete-confirm"){has("删除这笔流水？")};tap("取消");waitFor("delete-cancel"){has("详情")&&!has("删除这笔流水？")};tap("删除");waitFor("delete-reconfirm"){has("确认删除")};tap("确认删除");waitFor("delete-return-empty"){has("0笔账单")&&has("暂无匹配账单")};screenshot("delete-return-empty")
            val restored=runBlocking{api.completeSearch(LedgerSearchFilter())};check(restored.items.map{it.id()}.toSet()==expected.items.map{it.id()}.toSet());temporaryId=null
            stage="close";tap("返回");waitFor("main-returned"){nodes().any{it.contentDescription?.toString()=="打开菜单"}};screenshot("main-returned")
            File(context.filesDir,"qa-search-main-result.json").writeText(JSONObject().put("passed",true).put("originalRowsUnchanged",true).put("temporaryRecordCleaned",true).put("scope","main-search-and-real-shared-details").put("allCount",expected.items.size).put("matchedCount",matching.items.size).put("picturesCount",pictures.items.size).put("tested",org.json.JSONArray(listOf("main-menu","shared-detail-picture","edit-refresh","copy-cancel","delete-cancel-confirm-refresh","main-back","date-back","date-two-years","date-quick","group-immediate","custom-date","date-invalid","keyword","clear","pictures","empty","back"))).toString())
        }catch(error:Throwable){if(stage!="ui-login")runCatching{screenshot("failure-"+stage)};throw AssertionError("Isolated search stage=$stage class=${error.javaClass.simpleName}"+(error.message?.takeIf{it.startsWith("Search isolated stage timed out:")}?.let{" observer=$it"}?:""))}
        finally{if(helperToken!=null)runBlocking{temporaryId?.let{id->runCatching{api.deleteTransaction(id)}};runCatching{api.call("/api/logout.json")}};activity?.close()}
    }
}
