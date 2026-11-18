package top.fjzhang233.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val searchSurface:Color @Composable get()=ledgerColors.surface
private val searchMuted:Color @Composable get()=ledgerColors.muted
private val searchBlue:Color @Composable get()=ledgerColors.accent
private val searchRed:Color @Composable get()=ledgerColors.expense
private val searchGreen:Color @Composable get()=ledgerColors.income

/** Own transactions only. Shared family records keep their separate read-only view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LedgerSearch(vm:BooksModel,dismiss:()->Unit,detail:@Composable (JSONObject,()->Unit)->Unit) {
    val cached=vm.searchCache
    var savedFilter by rememberSaveable{mutableStateOf(cached?.filterJson?:LedgerSearchFilter().toJson())}
    val filter=remember(savedFilter){LedgerSearchFilter.fromJson(savedFilter)}
    var keyword by rememberSaveable{mutableStateOf(filter.keyword)}
    var panel by rememberSaveable{mutableStateOf<String?>(null)}
    var group by rememberSaveable{mutableStateOf("day")}
    var ascending by rememberSaveable{mutableStateOf(false)}
    var items by remember{mutableStateOf(cached?.items.orEmpty())}
    var total by remember{mutableStateOf(cached?.total?:-1L)}
    var summary by remember{mutableStateOf(cached?.summary)}
    var nextTimeSequenceId by remember{mutableStateOf(cached?.nextTimeSequenceId)}
    var loading by remember{mutableStateOf(false)}
    var loadingMore by remember{mutableStateOf(false)}
    var problem by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    val scope=rememberCoroutineScope();val focus=LocalFocusManager.current
    fun saveCache(currentFilter:LedgerSearchFilter){vm.searchCache=LedgerSearchCache(currentFilter.toJson(),items,total,summary,nextTimeSequenceId,System.currentTimeMillis())}
    fun runSearch(next:LedgerSearchFilter=filter) {
        if(loading)return
        focus.clearFocus();panel=null
        runCatching{next.query(1)}.onFailure{problem=it.message?:"请检查筛选条件"}.onSuccess{
            savedFilter=next.toJson();keyword=next.keyword;loading=true;problem="";items=emptyList();summary=null;nextTimeSequenceId=null
            scope.launch{
                try {
                    val first=async(Dispatchers.IO){vm.api.search(next,1,null,false)}
                    val aggregate=async(Dispatchers.IO){vm.api.searchSummary(next)}
                    val page=first.await();val totals=aggregate.await()
                    items=page.items;total=totals.total;summary=totals;nextTimeSequenceId=page.nextTimeSequenceId;saveCache(next)
                }
                catch(e:CancellationException){throw e}
                catch(e:ApiFailure){problem=e.message?:"搜索未完成";if(e.sessionExpired)vm.act{throw e}}
                catch(e:IllegalArgumentException){problem=e.message?:"请检查筛选条件"}
                catch(_:Exception){problem="搜索未完成，请检查连接后重试"}
                finally{loading=false}
            }
        }
    }
    fun loadMore(){
        val cursor=nextTimeSequenceId?:return
        if(loading||loadingMore)return
        loadingMore=true;problem=""
        scope.launch{
            try{
                val page=withContext(Dispatchers.IO){vm.api.search(filter,1,searchNextMaxTime(cursor),false)}
                val known=items.mapTo(HashSet()){it.id()};items=items+page.items.filter{known.add(it.id())};nextTimeSequenceId=page.nextTimeSequenceId;saveCache(filter)
            }catch(e:CancellationException){throw e}catch(e:Exception){problem=e.message?:"加载更多失败，请重试"}finally{loadingMore=false}
        }
    }
    LaunchedEffect(Unit){if(cached==null||System.currentTimeMillis()-cached.storedAt>5*60_000)runSearch()}
    LaunchedEffect(vm.searchInvalidation){if(vm.searchInvalidation>0&&!vm.showEditor&&items.isNotEmpty())runSearch()}
    val rows=remember(items,ascending){items.sortedWith(if(ascending)compareBy<JSONObject>{it.optLong("time")}.thenBy{it.id()}else compareByDescending<JSONObject>{it.optLong("time")}.thenByDescending{it.id()})}
    val groups=remember(rows,group){rows.groupBy{when(group){"none"->"";"month"->YearMonth.from(searchRowDay(it)).toString();else->searchRowDay(it).toString()}}}
    Surface(Modifier.fillMaxSize(),color=ledgerColors.background,contentColor=ledgerColors.text){
        BackHandler{if(panel!=null)panel=null else dismiss()}
        Column(Modifier.fillMaxSize()){
                Row(Modifier.fillMaxWidth()){
                    SearchDrop(searchDateLabel(filter),panel=="date",Modifier.weight(1f)){panel=if(panel=="date")null else "date"}
                    SearchDrop(if(filter.accountIds.isEmpty())"全部账户" else "${filter.accountIds.size}个账户",panel=="accounts",Modifier.weight(1f)){panel=if(panel=="accounts")null else "accounts"}
                    SearchDrop("筛选",panel=="filters",Modifier.weight(1f)){panel=if(panel=="filters")null else "filters"}
                }
                Box(Modifier.weight(1f).fillMaxWidth()){
                    LazyColumn(Modifier.fillMaxSize().then(if(panel!=null)Modifier.clearAndSetSemantics{} else Modifier).testTag("search-results"),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(0.dp)){
                        item{
                        BasicTextField(keyword,{keyword=it},singleLine=true,textStyle=TextStyle(color=ledgerColors.text,fontSize=14.sp),cursorBrush=SolidColor(searchBlue),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={runSearch(filter.copy(keyword=keyword))}),modifier=Modifier.fillMaxWidth().testTag("search-keyword"),decorationBox={field->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(8.dp)).background(searchSurface),verticalAlignment=Alignment.CenterVertically){IconButton(onClick={runSearch(filter.copy(keyword=keyword))},enabled=!loading){Icon(Icons.Outlined.Search,"搜索")};Box(Modifier.weight(1f)){if(keyword.isEmpty())Text("搜索：分类、备注、标签、金额",fontSize=14.sp,color=searchMuted);field()};if(keyword.isNotEmpty())IconButton(onClick={keyword="";runSearch(filter.copy(keyword=""))},enabled=!loading){Icon(Icons.Outlined.Close,"清除搜索")}}})
                        }

                        if(loading&&items.isEmpty())item{Row(Modifier.fillMaxWidth().padding(24.dp),horizontalArrangement=Arrangement.Center){CircularProgressIndicator(Modifier.size(24.dp));Text("搜索中…",Modifier.padding(start=12.dp))}}
                        if(loading&&items.isNotEmpty())item{Text("正在更新搜索结果…",Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),fontSize=12.sp,color=searchMuted)}
                        if(problem.isNotBlank())item{Column{Text(problem,color=searchRed);TextButton(onClick={runSearch(filter.copy(keyword=keyword))},enabled=!loading){Text("重试")}}}
                        summary?.let{result->
                            item{Spacer(Modifier.height(12.dp));Surface(color=searchSurface,shape=RoundedCornerShape(8.dp)){Column(Modifier.fillMaxWidth().padding(12.dp)){
                                Row(verticalAlignment=Alignment.CenterVertically){Text("搜索汇总",Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=16.sp);Text("${total.coerceAtLeast(0)}笔账单",fontSize=12.sp);IconButton(onClick={ascending=!ascending}){Icon(if(ascending)Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,if(ascending)"时间升序，点击改降序" else "时间降序，点击改升序",Modifier.size(18.dp))}}
                                Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                                    if(result.amounts.expense.signum()!=0)SearchAmount("支出",result.amounts.expense.toString(),searchRed,Modifier.weight(1f))
                                    if(result.amounts.income.signum()!=0)SearchAmount("收入",result.amounts.income.toString(),searchGreen,Modifier.weight(1f))
                                    SearchAmount("结余",result.amounts.balance.toString(),ledgerColors.text,Modifier.weight(1f))
                                }
                                Text("CNY · 完整匹配结果",Modifier.padding(top=10.dp),fontSize=11.sp,color=searchMuted)
                                if(result.excludedForeign)Text("外币未计入 CNY 汇总",Modifier.padding(top=6.dp),fontSize=11.sp,color=searchMuted)
                            }}}
                            if(items.isEmpty())item{Text("暂无匹配账单",Modifier.fillMaxWidth().padding(32.dp),color=searchMuted)}
                            groups.forEach{(date,dayRows)->
                                if(date.isNotBlank())item(key="heading-$date"){
                                    val amount=remember(dayRows){LedgerSearchSnapshot(dayRows).amounts}
                                    Row(Modifier.fillMaxWidth().padding(top=20.dp,bottom=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(date.replace('-','.'),fontSize=14.sp,fontWeight=FontWeight.Bold);Text("收:${money(amount.income.toString())} 支:${money(amount.expense.toString())}",fontSize=11.sp,color=searchMuted)}
                                }
                                itemsIndexed(dayRows,key={_,row->row.id()}){index,row->SearchTransactionRow(row,index==0,index==dayRows.lastIndex){selected=row}}
                            }
                        }
                        if(nextTimeSequenceId!=null)item{TextButton(onClick=::loadMore,enabled=!loadingMore,modifier=Modifier.fillMaxWidth()){Text(if(loadingMore)"加载中…" else "加载更多")}}
                    }
                    if(panel!=null){
                        Box(Modifier.fillMaxSize().background(ledgerColors.background.copy(alpha=0.6f)).clickable{panel=null})
                        SearchFilterPanel(vm,filter.copy(keyword=keyword),panel!!,group,{group=it},{panel=null},{runSearch(it)})
                    }
                }
        }
    }
    selected?.let{item->detail(item){selected=null;runSearch()}}
}
@Composable private fun SearchAmount(label:String,amount:String,color:Color,modifier:Modifier){Column(modifier){Text(label,fontSize=12.sp,color=searchMuted);Text(money(amount),Modifier.padding(top=6.dp),fontSize=20.sp,color=color,maxLines=1,overflow=TextOverflow.Ellipsis)}}
@Composable private fun SearchDrop(label:String,active:Boolean,modifier:Modifier,onClick:()->Unit){Row(modifier.heightIn(min=48.dp).clickable(role=Role.Button,onClick=onClick).padding(horizontal=8.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Text(label,fontSize=14.sp,color=if(active)searchBlue else ledgerColors.text,maxLines=1,overflow=TextOverflow.Ellipsis);Icon(if(active)Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,null,Modifier.size(18.dp),tint=searchMuted)}}
@Composable private fun SearchTransactionRow(row:JSONObject,first:Boolean,last:Boolean,open:()->Unit){
    val type=row.optInt("type");val color=when(type){2->searchGreen;3->searchRed;else->searchMuted}
    val label=when(type){1->"余额调整";4->"账户转账";else->row.optJSONObject("category")?.optString("name")?.takeIf{it.isNotBlank()}?:row.optString("categoryName").ifBlank{"未分类"}}
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=if(first)8.dp else 0.dp,topEnd=if(first)8.dp else 0.dp,bottomStart=if(last)8.dp else 0.dp,bottomEnd=if(last)8.dp else 0.dp)).clickable(onClick=open).testTag("search-transaction-${row.id()}"),color=searchSurface){Row(Modifier.heightIn(min=60.dp).padding(12.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(4.dp).background(color,CircleShape));Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(label,fontSize=14.sp);if(row.optString("comment").isNotBlank())Text(row.optString("comment"),fontSize=11.sp,color=searchMuted,maxLines=1,overflow=TextOverflow.Ellipsis);if(row.array("pictures").isNotEmpty())Icon(Icons.Outlined.Image,"含图片",Modifier.size(14.dp),tint=searchMuted)};Column(horizontalAlignment=Alignment.End){Text(transactionSignedAmount(type,row.optString("sourceAmount")),fontSize=16.sp,color=color);Text(row.optJSONObject("sourceAccount")?.optString("currency").orEmpty(),fontSize=10.sp,color=searchMuted)}}}
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SearchFilterPanel(vm:BooksModel,initial:LedgerSearchFilter,panel:String,group:String,changeGroup:(String)->Unit,dismiss:()->Unit,apply:(LedgerSearchFilter)->Unit){
    var state by remember(panel,initial.toJson()){mutableStateOf(initial)}
    var first by remember(panel){mutableStateOf(initial.firstDate.orEmpty())};var last by remember(panel){mutableStateOf(initial.lastDate.orEmpty())}
    var error by remember(panel){mutableStateOf("")};var draftGroup by remember(panel){mutableStateOf(group)};var datePicker by rememberSaveable{mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart=8.dp,bottomEnd=8.dp)).background(searchSurface)){
        Column(Modifier.fillMaxWidth().weight(1f,false).heightIn(max=520.dp).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=if(panel=="date")0.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(if(panel=="date")4.dp else 8.dp)){
            if(panel=="date"){
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("分组",fontSize=16.sp,fontWeight=FontWeight.Bold,color=searchMuted,modifier=Modifier.padding(end=8.dp).offset(y=(-4).dp))
                    listOf("none" to "不分组","day" to "按天","month" to "按月").forEach{(key,label)->SearchPill(label,draftGroup==key,raiseVisual=true){draftGroup=key;changeGroup(key);apply(state)}}
                }
                HorizontalDivider(color=ledgerColors.field)
                Text("账单日期",fontSize=16.sp,fontWeight=FontWeight.Bold,color=searchMuted,modifier=Modifier.padding(top=3.dp))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    searchDatePresets(LocalDate.now(ZoneId.of("Asia/Shanghai"))).forEach{preset->
                        SearchPill(preset.label,first==preset.firstDate.orEmpty()&&last==preset.lastDate.orEmpty()){first=preset.firstDate.orEmpty();last=preset.lastDate.orEmpty();apply(state.copy(firstDate=preset.firstDate,lastDate=preset.lastDate))}
                    }
                }
                HorizontalDivider(color=ledgerColors.field)
                Text("自定义",fontSize=16.sp,fontWeight=FontWeight.Bold,color=searchMuted,modifier=Modifier.padding(top=3.dp))
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    SearchPill(first.ifBlank{"开始日期"},first.isNotBlank()){datePicker="first"}
                    Text("－",color=searchMuted)
                    SearchPill(last.ifBlank{"截止日期"},last.isNotBlank()){datePicker="last"}
                }
            }else if(panel=="accounts"){
                Text("资金账户 · 不选表示全部",fontSize=14.sp,color=searchMuted)
                vm.accounts.forEach{item->SearchChoice(item.optString("name")+" · "+item.optString("currency"),item.id() in state.accountIds){state=state.copy(accountIds=state.accountIds.toggle(item.id()))}}
            }else{
                Text("类型",color=searchMuted)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(0 to "全部",3 to "支出",2 to "收入",4 to "转账",1 to "余额调整").forEach{(key,label)->FilterChip(state.type==key,{state=state.copy(type=key)},label={Text(label)})}}
                SearchChoice("仅含图片的账单",state.picturesOnly){state=state.copy(picturesOnly=!state.picturesOnly)}
                SearchChoice("备注忽略大小写",state.ignoreCase){state=state.copy(ignoreCase=!state.ignoreCase)}
                Text("金额 · 按各账单原币种数值",color=searchMuted)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("" to "不限","eq" to "等于","gt" to "大于","lt" to "小于","bt" to "区间").forEach{(key,label)->FilterChip(state.amountMode==key,{state=state.copy(amountMode=key)},label={Text(label)})}}
                if(state.amountMode.isNotBlank()){
                    OutlinedTextField(state.amount1,{state=state.copy(amount1=it)},label={Text(if(state.amountMode=="bt")"最低金额（含）" else "金额")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(state.amountMode=="bt")OutlinedTextField(state.amount2,{state=state.copy(amount2=it)},label={Text("最高金额（含）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
                }
                Text("分类 · 不选表示全部",color=searchMuted)
                vm.categories.filter{!it.optBoolean("hidden")&&it.id("parentId")!="0"}.forEach{item->SearchChoice(item.optString("name"),item.id() in state.categoryIds){state=state.copy(categoryIds=state.categoryIds.toggle(item.id()))}}
                Text("标签 · 不选表示全部",color=searchMuted)
                SearchChoice("包含全部所选标签",state.tagMode==1){state=state.copy(tagMode=if(state.tagMode==1)0 else 1)}
                vm.tags.filterNot{it.optBoolean("hidden")}.forEach{item->SearchChoice(item.optString("name"),item.id() in state.tagIds){state=state.copy(tagIds=state.tagIds.toggle(item.id()))}}
            }
            if(error.isNotBlank())Text(error,color=searchRed,fontSize=12.sp)
        }
        if(panel!="date"){Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedButton(onClick={state=if(panel=="accounts")state.copy(accountIds=emptySet())else if(panel=="filters")LedgerSearchFilter(keyword=initial.keyword,firstDate=initial.firstDate,lastDate=initial.lastDate,accountIds=initial.accountIds)else state;first="";last=""},modifier=Modifier.weight(1f)){Text("重置")}
            Button(onClick={runCatching{val next=if(panel=="date")state.copy(firstDate=first.takeIf{it.isNotBlank()},lastDate=last.takeIf{it.isNotBlank()})else state;next.query(1);next}.onSuccess{if(panel=="date")changeGroup(draftGroup);apply(it)}.onFailure{error="请检查日期和金额，结束不得早于开始"}},modifier=Modifier.weight(1f)){Text("确定")}
        }
        TextButton(onClick=dismiss,modifier=Modifier.fillMaxWidth()){Text("取消")}}
    }
    datePicker?.let{endpoint->SearchDatePicker(if(endpoint=="first")first else last,{datePicker=null}){chosen->
        if(endpoint=="first")first=chosen else last=chosen
        val next=state.copy(firstDate=first.takeIf{it.isNotBlank()},lastDate=last.takeIf{it.isNotBlank()})
        runCatching{next.query(1)}.onSuccess{datePicker=null;apply(next)}.onFailure{datePicker=null;error="截止日期不能早于开始日期"}
    }}
}
@Composable private fun SearchChoice(label:String,selected:Boolean,change:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=selected,role=Role.Checkbox,onValueChange={change()}),verticalAlignment=Alignment.CenterVertically){Checkbox(selected,onCheckedChange=null);Text(label,fontSize=14.sp)}}
private fun Set<String>.toggle(id:String)=if(id in this)this-id else this+id








@Composable private fun SearchPill(label:String,selected:Boolean,raiseVisual:Boolean=false,onClick:()->Unit){
    Box(Modifier.heightIn(min=48.dp).clickable(role=Role.Button,onClick=onClick).padding(vertical=4.dp),contentAlignment=Alignment.Center){
        Box(Modifier.offset(y=if(raiseVisual)(-4).dp else 0.dp).heightIn(min=32.dp).clip(RoundedCornerShape(24.dp)).background(if(selected)ledgerColors.selection else ledgerColors.field).padding(horizontal=14.dp,vertical=4.dp),contentAlignment=Alignment.Center){Text(label,fontSize=14.sp,color=if(selected)searchBlue else searchMuted)}
    }
}

@Composable internal fun SearchDatePicker(value:String,dismiss:()->Unit,select:(String)->Unit){
    val density=androidx.compose.ui.platform.LocalDensity.current
    val nativeStyle=if(ledgerColors.dark)android.R.style.Theme_Material else android.R.style.Theme_Material_Light
    val nativeAccent=ledgerColors.accent.toArgb()
    val initial=remember(value){runCatching{LocalDate.parse(value)}.getOrDefault(LocalDate.now(ZoneId.of("Asia/Shanghai")))}
    var date by rememberSaveable{mutableStateOf(initial.toString())};var reset by remember{mutableIntStateOf(0)};var calendar by rememberSaveable{mutableStateOf(false)}
    val selected=LocalDate.parse(date)
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth(.832f).height(if(calendar)360.dp else 280.dp),color=searchSurface,contentColor=ledgerColors.text,shape=RoundedCornerShape(4.dp)){
            Column{
                Box(Modifier.fillMaxWidth().height(if(calendar)260.dp else 180.dp),contentAlignment=Alignment.Center){
                    key(nativeStyle){if(calendar)androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.fillMaxSize(),factory={ctx->android.widget.DatePicker(android.view.ContextThemeWrapper(ctx,nativeStyle)).apply{init(selected.year,selected.monthValue-1,selected.dayOfMonth){_,y,m,d->date=LocalDate.of(y,m+1,d).toString()}}},update={picker->if(picker.year!=selected.year||picker.month!=selected.monthValue-1||picker.dayOfMonth!=selected.dayOfMonth)picker.updateDate(selected.year,selected.monthValue-1,selected.dayOfMonth)})
                    else key(reset){androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.width(208.dp).fillMaxHeight(),factory={ctx->
                        val themed=android.view.ContextThemeWrapper(ctx,nativeStyle);val row=android.widget.LinearLayout(themed).apply{orientation=android.widget.LinearLayout.HORIZONTAL}
                        val years=android.widget.NumberPicker(themed).apply{minValue=1900;maxValue=2100;this.value=selected.year.coerceIn(1900,2100);wrapSelectorWheel=false;contentDescription="年份"}
                        val months=android.widget.NumberPicker(themed).apply{minValue=1;maxValue=12;displayedValues=(1..12).map{"${it}月"}.toTypedArray();this.value=selected.monthValue;contentDescription="月份"}
                        val days=android.widget.NumberPicker(themed).apply{minValue=1;maxValue=selected.lengthOfMonth();this.value=selected.dayOfMonth;contentDescription="日期"}
                        fun change(){val month=YearMonth.of(years.value,months.value);val day=days.value.coerceAtMost(month.lengthOfMonth());days.maxValue=month.lengthOfMonth();days.value=day;date=month.atDay(day).toString()}
                        years.setOnValueChangedListener{_,_,_->change()};months.setOnValueChangedListener{_,_,_->change()};days.setOnValueChangedListener{_,_,_->change()}
                        listOf(years,months,days).forEachIndexed{index,picker->if(android.os.Build.VERSION.SDK_INT>=29)picker.textColor=nativeAccent;row.addView(picker,android.widget.LinearLayout.LayoutParams(with(density){64.dp.roundToPx()},android.widget.LinearLayout.LayoutParams.MATCH_PARENT).apply{if(index<2)marginEnd=with(density){8.dp.roundToPx()}})};row
                    })}
                    }
                    IconButton(onClick={calendar=!calendar},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp)){Icon(Icons.Outlined.ViewDay,"切换日期显示模式",tint=searchMuted)}
                }
                Row(Modifier.fillMaxWidth().height(48.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){listOf("今","昨","前").forEachIndexed{offset,label->val day=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(offset.toLong());Box(Modifier.width(56.dp).height(48.dp).clickable{date=day.toString();reset++},contentAlignment=Alignment.Center){Box(Modifier.size(40.dp,32.dp).clip(CircleShape).background(if(day==selected)ledgerColors.selection else ledgerColors.field),contentAlignment=Alignment.Center){Text(label,fontSize=14.sp,color=if(day==selected)searchBlue else searchMuted)}}}}
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=8.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically){TextButton(onClick=dismiss){Text("取消",color=searchMuted)};TextButton(onClick={select(date)}){Text("确定",color=searchBlue,fontWeight=FontWeight.Bold)}}
            }
        }
    }
}


private fun searchDateLabel(filter:LedgerSearchFilter):String = searchDatePresets(LocalDate.now(ZoneId.of("Asia/Shanghai"))).firstOrNull{filter.firstDate==it.firstDate&&filter.lastDate==it.lastDate}?.label?:"自定义"
