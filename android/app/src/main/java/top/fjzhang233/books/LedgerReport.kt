package top.fjzhang233.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.YearMonth

internal val reportSurface:Color @Composable get()=ledgerColors.surface
internal val reportMuted:Color @Composable get()=ledgerColors.muted
internal val reportRed:Color @Composable get()=ledgerColors.expense
internal val reportGreen:Color @Composable get()=ledgerColors.income
internal val reportPalette:List<Color> @Composable get()=ledgerReportPalette(ledgerColors.dark)
internal fun ledgerReportPalette(dark:Boolean):List<Color> =if(dark)listOf(Color(0xFFFFA936),Color(0xFFFF8267),Color(0xFF72C4D6),Color(0xFFBCA0DE),Color(0xFF93C695))else listOf(Color(0xFF9C5700),Color(0xFFB82431),Color(0xFF126E80),Color(0xFF68409A),Color(0xFF3D713C))
internal fun reportAverage(value:BigInteger,count:Long):String=if(count<=0)"0.00" else value.toBigDecimal().divide(BigDecimal.valueOf(count),0,RoundingMode.HALF_EVEN).movePointLeft(2).setScale(2).toPlainString()
internal fun reportRatio(value:BigInteger,total:BigInteger):Float=if(total.signum()<=0)0f else value.toBigDecimal().divide(total.toBigDecimal(),8,RoundingMode.HALF_UP).toFloat()
private fun reportPercent(value:BigInteger,total:BigInteger)=if(total.signum()<=0)"0.00%" else value.toBigDecimal().multiply(BigDecimal(100)).divide(total.toBigDecimal(),2,RoundingMode.HALF_UP).toPlainString()+"%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LedgerReport(vm:BooksModel,dismiss:()->Unit){
    var selectedMonth by rememberSaveable{mutableStateOf(vm.ledgerMonth)}
    var yearly by rememberSaveable{mutableStateOf(false)}
    var customFirst by rememberSaveable{mutableStateOf("")};var customLast by rememberSaveable{mutableStateOf("")}
    val period=ReportPeriod(YearMonth.parse(selectedMonth),yearly,customFirst.takeIf{it.isNotBlank()}?.let(java.time.LocalDate::parse),customLast.takeIf{it.isNotBlank()}?.let(java.time.LocalDate::parse))
    fun adopt(value:ReportPeriod){selectedMonth=value.month.toString();customFirst=value.customFirst?.toString().orEmpty();customLast=value.customLast?.toString().orEmpty()}
    var snapshot by remember{mutableStateOf<ReportSnapshot?>(null)}
    var loading by remember{mutableStateOf(true)};var failure by remember{mutableStateOf("")};var reload by remember{mutableIntStateOf(0)}
    var chartType by rememberSaveable{mutableIntStateOf(2)};var categoryType by rememberSaveable{mutableIntStateOf(2)}
    var lines by rememberSaveable{mutableStateOf(false)};var category by remember{mutableStateOf<ReportCategory?>(null)}
    var selectedDay by rememberSaveable{mutableStateOf<String?>(null)}
    var choosePeriod by remember{mutableStateOf(false)};var filtering by remember{mutableStateOf(false)};var savedFilter by rememberSaveable{mutableStateOf("{}")};val filter=decodeReportFilter(savedFilter)
    LaunchedEffect(period,reload,vm.token,filter){
        loading=true;failure="";snapshot=null
        try { snapshot=vm.api.report(period,vm.accounts,vm.categories,filter) }
        catch(e:CancellationException){throw e}
        catch(e:Exception){vm.imageFailed(e);failure=if(e is ApiFailure)e.message.orEmpty() else "报表暂时无法加载，请重试"}
        finally {loading=false}
    }
    LaunchedEffect(vm.token){if(vm.token==null)dismiss()}
    BackHandler(onBack=dismiss)
    Surface(Modifier.fillMaxSize(),color=ledgerColors.background,contentColor=MaterialTheme.colorScheme.onSurface){
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface){Column(Modifier.fillMaxSize().background(ledgerColors.background).systemBarsPadding().testTag("ledger-report")){
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=dismiss){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}
                Spacer(Modifier.weight(1f));ReportSegments(listOf("月","年"),if(yearly)1 else 0,"period"){yearly=it==1;chartType=2;customFirst="";customLast=""};Spacer(Modifier.weight(1f));IconButton(onClick={filtering=true},modifier=Modifier.testTag("report-filter")){Icon(Icons.Outlined.FilterAlt,"筛选报表")}}
            Row(Modifier.fillMaxWidth().height(56.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={adopt(period.shift(-1))}){Icon(Icons.Outlined.ChevronLeft,"上一${if(yearly)"年" else "月"}")}
                TextButton(onClick={choosePeriod=true}){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(period.label,fontSize=if(period.custom)12.sp else 18.sp,lineHeight=22.sp,color=ledgerColors.text);Text("CNY · 上海时间 · "+(if(snapshot?.excludedForeign==true)"已排除外币" else "外币不合并")+(if(yearly&&!period.custom)" · 有账月份均值"else ""),fontSize=8.sp,lineHeight=10.sp,color=reportMuted,maxLines=1)};Icon(Icons.Outlined.ExpandMore,null)}
                IconButton(onClick={adopt(period.shift(1))}){Icon(Icons.Outlined.ChevronRight,"下一${if(yearly)"年" else "月"}")}}
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(failure.isNotEmpty())Column(Modifier.fillMaxWidth().padding(24.dp)){Text(failure);TextButton(onClick={reload++}){Text("重新加载")}}
            snapshot?.let{data->LazyColumn(Modifier.fillMaxSize().testTag("report-list"),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=4.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                item{ReportCard("收支总览"){
                    Row(Modifier.fillMaxWidth()){ReportMetric("支出",money(data.expense.toString()),Modifier.weight(1f));ReportMetric("收入",money(data.income.toString()),Modifier.weight(1f))}
                    Row(Modifier.fillMaxWidth()){ReportMetric("结余",money((data.income-data.expense).toString()),Modifier.weight(1f));ReportMetric(if(yearly&&!period.custom)"月均支出" else "日均支出",reportAverage(data.expense,if(yearly&&!period.custom)data.activeMonths.toLong()else period.days.toLong()),Modifier.weight(1f))}
}}
                if(data.days.any{it.active}) {
                item{ReportCard(if(yearly)"月度对比" else "每日统计",actions={
                    IconButton(onClick={openReportChart(vm.getApplication(),data,chartType,lines)},modifier=Modifier.size(36.dp)){Icon(Icons.Outlined.Fullscreen,"全屏图表",tint=reportMuted)}
                    IconButton(onClick={lines=!lines},modifier=Modifier.size(36.dp).testTag("report-chart-toggle")){Icon(if(lines)Icons.Outlined.BarChart else Icons.Outlined.ShowChart,if(lines)"切换柱状图" else "切换折线图",tint=reportMuted)}
                }){
                    ReportChart(data,chartType,lines)
                    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.Center){ReportSegments(if(yearly)listOf("支出","收入","结余","全部") else listOf("支出","收入","全部"),chartType-2,"trend"){chartType=it+2}}}}
                item{ReportCard("分类报表"){
                    val ranked=data.ranking(categoryType);val positive=ranked.filter{it.amount.signum()>0};val total=positive.fold(BigInteger.ZERO){s,r->s+r.amount}
                    ReportDonut(ranked,if(categoryType==2)"支出比例" else "收入比例")
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){ReportSegments(listOf("支出","收入"),if(categoryType==2)0 else 1,"category"){categoryType=if(it==0)2 else 1}}
                    if(ranked.any{it.amount.signum()<0})Text("负数净额不绘入占比环图，仍显示于排名",fontSize=10.sp,color=reportMuted)
                    ranked.forEachIndexed{index,row->val color=reportPalette[index%reportPalette.size]
                        Row(Modifier.fillMaxWidth().clickable{category=row}.padding(vertical=12.dp).testTag("report-category-${row.category.id()}"),verticalAlignment=Alignment.CenterVertically){
                            Box(Modifier.size(36.dp).background(color,CircleShape),contentAlignment=Alignment.Center){BuiltinIcon(row.category,tint=if(color.luminance()>.179f)Color.Black else Color.White)}
                            Column(Modifier.weight(1f).padding(start=12.dp)){
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(row.category.optString("name"),Modifier.weight(1f),fontSize=16.sp,lineHeight=20.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text(" "+reportPercent(row.amount,total),fontSize=12.sp,lineHeight=18.sp,color=reportMuted);Spacer(Modifier.width(8.dp));Text(money(row.amount.toString()),fontSize=16.sp,lineHeight=20.sp)}
                                val difference=row.amount-row.previous
                                Text("较${if(period.custom)"上一期间" else if(yearly)"上年" else "上月"} ${if(difference.signum()>0)"+" else ""}${money(difference.toString())}",fontSize=10.sp,color=reportMuted)
                                LinearProgressIndicator(progress={reportRatio(row.amount.max(BigInteger.ZERO),total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp).padding(top=1.dp),color=color,trackColor=ledgerColors.divider)}}}
                    if(ranked.isEmpty())Text("所选期间没有${if(categoryType==2)"支出" else "收入"}",Modifier.padding(16.dp),color=reportMuted)}}
                item{ReportCard(if(yearly)"月报表" else "日报表"){
                    ReportTableRow("日期","收入","支出","结余",true)
                    if(yearly&&!period.custom)reportTableDays(data).forEach{(label,income,expense)->ReportTableRow(label,money(income.toString()),money(expense.toString()),money((income-expense).toString()))}
                    else data.days.filter{it.active}.asReversed().forEach{day->ReportTableRow(day.date.toString().substring(5),money(day.income.toString()),money(day.expense.toString()),money((day.income-day.expense).toString()),modifier=Modifier.testTag("report-day-row-${day.date}").semantics(mergeDescendants=true){contentDescription="${day.date}，收入${money(day.income.toString())}，支出${money(day.expense.toString())}，结余${money((day.income-day.expense).toString())}"},onClick={selectedDay=day.date.toString()})}
                    if(yearly&&!period.custom)ReportTableRow("平均",reportAverage(data.income,data.activeMonths.toLong()),reportAverage(data.expense,data.activeMonths.toLong()),reportAverage(data.income-data.expense,data.activeMonths.toLong()))}}
                } else item {Text("暂无数据",Modifier.fillMaxWidth().padding(40.dp),color=reportMuted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
            }}
        }
        if(choosePeriod)ReportPeriodChooser(period,{choosePeriod=false}){selectedMonth=it.toString();customFirst="";customLast="";choosePeriod=false}
        if(filtering)ReportFilterPanel(vm,filter,{filtering=false},{savedFilter=encodeReportFilter(it);filtering=false},quick={quick->val now=YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"));yearly=quick>=2;chartType=2;customFirst="";customLast="";selectedMonth=when(quick){1->now.minusMonths(1);3->now.minusYears(1);else->now}.toString();filtering=false},custom={first,last->yearly=false;chartType=2;customFirst=first.toString();customLast=last.toString();filtering=false})
        selectedDay?.let{day->ReportDayTransactions(vm,java.time.LocalDate.parse(day),filter,{selectedDay=null}){reload++}}
        category?.let{selected->val current=snapshot?.categories?.firstOrNull{it.category.id()==selected.category.id()}
            ReportCategoryDetails(vm,period,selected.copy(amount=current?.amount?:BigInteger.ZERO,previous=current?.previous?:BigInteger.ZERO),filter,{category=null}){reload++}}
    }
        }
}
@Composable internal fun ReportCard(title:String,actions:(@Composable RowScope.()->Unit)?=null,content:@Composable ColumnScope.()->Unit){
    Surface(shape=RoundedCornerShape(8.dp),color=reportSurface){Column(Modifier.fillMaxWidth().padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
        if(title.isNotEmpty())Box(Modifier.fillMaxWidth().height(36.dp),contentAlignment=Alignment.TopCenter){
            Text(title,fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold,color=ledgerColors.text)
            if(actions!=null)Row(Modifier.align(Alignment.CenterEnd),content=actions)
        }
        content()
    }}
}
@Composable internal fun ReportMetric(label:String,value:String,modifier:Modifier){Column(modifier.padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(label,color=reportMuted,fontSize=14.sp,lineHeight=20.sp);Text(value,fontSize=16.sp,lineHeight=20.sp,fontWeight=FontWeight.Bold,color=ledgerColors.text)}}
@Composable internal fun ReportSegments(labels:List<String>,selected:Int,group:String="generic",choose:(Int)->Unit){
    val period=group=="period";val shape=if(period)RoundedCornerShape(8.dp)else CircleShape
    Box(Modifier.height(48.dp),contentAlignment=Alignment.Center){
        Box(Modifier.matchParentSize().padding(vertical=7.dp).background(if(period)ledgerColors.background else ledgerColors.background,shape))
        Row(Modifier.padding(horizontal=4.dp)){
            labels.forEachIndexed{index,label->
                Box(Modifier.width(if(period)48.dp else 52.dp).height(48.dp).testTag("report-segment-$group-$index").clickable{choose(index)},contentAlignment=Alignment.Center){
                    if(index==selected)Box(Modifier.fillMaxWidth().height(28.dp).background(if(period)reportSurface else reportSurface,if(period)RoundedCornerShape(5.dp)else CircleShape))
                    Text(label,fontSize=if(period)18.sp else 14.sp,lineHeight=20.sp,color=if(index==selected)ledgerColors.text else reportMuted)
                }
            }
        }
    }
}
internal fun reportTableDays(data:ReportSnapshot):List<Triple<String,BigInteger,BigInteger>> = if(data.period.yearly&&!data.period.custom)data.days.groupBy{it.date.monthValue}.filterValues{rows->rows.any{it.active}}.toSortedMap(compareByDescending{it}).map{(month,rows)->Triple("${month}月",rows.fold(BigInteger.ZERO){s,r->s+r.income},rows.fold(BigInteger.ZERO){s,r->s+r.expense})} else data.days.filter{it.active}.asReversed().map{Triple(it.date.toString().substring(5),it.income,it.expense)}
@Composable internal fun ReportTableRow(date:String,income:String,expense:String,net:String,header:Boolean=false,modifier:Modifier=Modifier,onClick:(()->Unit)?=null){
    Row(modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(role=androidx.compose.ui.semantics.Role.Button,onClick=onClick)else Modifier).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        listOf(date,income,expense,net).forEachIndexed{index,value->Text(value,Modifier.weight(1f),fontSize=if(header)14.sp else 13.sp,fontWeight=if(header)FontWeight.Bold else FontWeight.Normal,color=if(!header&&index==3){if(value.startsWith("-"))reportRed else reportGreen}else ledgerColors.text,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReportDayTransactions(vm:BooksModel,date:java.time.LocalDate,filter:ReportFilter,dismiss:()->Unit,changed:()->Unit){
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())};var page by remember{mutableIntStateOf(1)};var total by remember{mutableLongStateOf(0)}
    var loading by remember{mutableStateOf(true)};var failure by remember{mutableStateOf("")};var retry by remember{mutableIntStateOf(0)};var selected by remember{mutableStateOf<JSONObject?>(null)}
    fun refresh(){page=1;retry++;changed()}
    ReportRefreshAfterEditor(vm,::refresh)
    LaunchedEffect(date,page,retry){loading=true;failure="";try{val(result,count)=vm.api.reportDayPage(date,vm.accounts,vm.categories,page,filter);rows=if(page==1)result else(rows+result).distinctBy{it.id()};total=count}catch(e:CancellationException){throw e}catch(e:Exception){vm.imageFailed(e);failure="当日流水加载失败，请重试"}finally{loading=false}}
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=reportSurface,dragHandle=null){Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag("report-day-transactions")){
        Text(date.toString(),Modifier.padding(16.dp),fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("CNY · 上海时间 · 沿用报表筛选",Modifier.padding(horizontal=16.dp),fontSize=11.sp,color=reportMuted)
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(failure.isNotEmpty())Row{Text(failure,color=reportRed);TextButton(onClick={retry++}){Text("重试")}}
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp)){
            items(rows,key={it.id()}){row->ListItem(headlineContent={Text(transactionLabel(row))},supportingContent={if(row.optString("comment").isNotBlank())Text(row.optString("comment"))},trailingContent={Text(transactionSignedAmount(row.optInt("type"),row.optString("sourceAmount"))+" CNY",color=if(row.optInt("type")==2)reportGreen else reportRed)},modifier=Modifier.testTag("report-day-transaction-${row.id()}").clickable{selected=row})}
            if(!loading&&failure.isEmpty()&&rows.size<total)item{TextButton(onClick={page++}){Text("加载更多")}}
            if(!loading&&failure.isEmpty()&&rows.isEmpty())item{Text("暂无匹配的当日流水",Modifier.padding(24.dp),color=reportMuted)}
        }
    }}
    selected?.let{row->LedgerTransactionDetails(vm,row){selected=null;refresh()}}
}
@Composable internal fun ReportPeriodChooser(period:ReportPeriod,dismiss:()->Unit,choose:(YearMonth)->Unit){
    var year by remember{mutableIntStateOf(period.month.year)}
    AlertDialog(
        onDismissRequest=dismiss,
        shape=RoundedCornerShape(4.dp),containerColor=reportSurface,
        text={Row(Modifier.fillMaxWidth().height(300.dp)){
            androidx.compose.foundation.lazy.LazyColumn(Modifier.width(96.dp)){
                items((period.month.year+3 downTo period.month.year-20).toList()){item->
                    TextButton(onClick={year=item;if(period.yearly)choose(YearMonth.of(item,period.month.monthValue))},modifier=Modifier.fillMaxWidth(),shape=CircleShape,colors=ButtonDefaults.textButtonColors(containerColor=if(item==year)MaterialTheme.colorScheme.primary else Color.Transparent,contentColor=ledgerColors.text)){Text("${item}年")}
                }
            }
            if(!period.yearly)androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),Modifier.weight(1f)){
                items(12){index->TextButton(onClick={choose(YearMonth.of(year,index+1))}){Text("${index+1}月")}}
            }
        }},
        confirmButton={},dismissButton={}
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReportCategoryDetails(vm:BooksModel,period:ReportPeriod,category:ReportCategory,filter:ReportFilter,dismiss:()->Unit,changed:()->Unit){
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())};var page by remember{mutableIntStateOf(1)};var total by remember{mutableLongStateOf(0)};var loading by remember{mutableStateOf(true)};var failure by remember{mutableStateOf("")};var retry by remember{mutableIntStateOf(0)};var statistics by remember{mutableStateOf(false)}
    LaunchedEffect(page,retry){loading=true;failure="";try{val(result,count)=vm.api.reportCategoryPage(period,category.category.id(),vm.accounts,page,filter);rows=if(page==1)result else(rows+result).distinctBy{it.id()};total=count}catch(e:CancellationException){throw e}catch(e:Exception){vm.imageFailed(e);failure="明细加载失败，请重试"}finally{loading=false}}
    if(statistics){ReportCategoryStatistics(vm,period,category,filter,{statistics=false},{page=1;retry++;changed()});return}
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    fun refresh(){page=1;retry++;changed()}
    ReportRefreshAfterEditor(vm,::refresh)
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=reportSurface){Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).navigationBarsPadding().testTag("report-category-sheet")){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(category.category.optString("name"),Modifier.weight(1f),fontSize=20.sp);TextButton(onClick=dismiss){Text("关闭")};TextButton(onClick={statistics=true}){Text("统计")}}
        Text("${period.label} · CNY · ${money(category.amount.toString())}",color=reportMuted)
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(failure.isNotEmpty())Row{Text(failure,color=reportRed);TextButton(onClick={retry++}){Text("重试")}}
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp)){
            items(rows,key={it.id()}){row->ListItem(headlineContent={Text(category.category.optString("name"))},supportingContent={Text(row.optString("comment"))},trailingContent={Text(money((if(category.category.optInt("type")==2)row.optString("sourceAmount").toBigInteger().negate() else row.optString("sourceAmount").toBigInteger()).toString())+" CNY",color=if(category.category.optInt("type")==2)reportRed else reportGreen)},modifier=Modifier.testTag("report-category-row-${row.id()}").clickable{selected=row})}
            if(!loading&&failure.isEmpty()&&rows.size<total)item{TextButton(onClick={page++}){Text("加载更多")}}
            if(!loading&&rows.isEmpty())item{Text("暂无明细",Modifier.padding(24.dp))}
        }
    }}
    selected?.let{row->LedgerTransactionDetails(vm,row){selected=null;refresh()}}
}

/** Editor/copy remains over this route; refresh only once it closes, without losing its period/filter. */
@Composable private fun ReportRefreshAfterEditor(vm:BooksModel,refresh:()->Unit){
    var wasEditing by remember{mutableStateOf(vm.showEditor)}
    val latestRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(vm.showEditor){if(wasEditing&&!vm.showEditor)latestRefresh();wasEditing=vm.showEditor}
}

@Composable private fun ReportFilterPanel(vm:BooksModel,initial:ReportFilter,dismiss:()->Unit,apply:(ReportFilter)->Unit,quick:(Int)->Unit,custom:(java.time.LocalDate,java.time.LocalDate)->Unit){
    var accounts by remember{mutableStateOf(initial.accountIds)};var categories by remember{mutableStateOf(initial.categoryIds)};var tags by remember{mutableStateOf(initial.tagIds)};var allTags by remember{mutableStateOf(initial.allTags)};var first by rememberSaveable{mutableStateOf("")};var last by rememberSaveable{mutableStateOf("")};var dateError by remember{mutableStateOf("")}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Box(Modifier.fillMaxSize().clickable(onClick=dismiss),contentAlignment=Alignment.CenterEnd){
            Column(Modifier.fillMaxHeight().width(320.dp).background(reportSurface).clickable{}.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)){
                Text("筛选报表",fontSize=20.sp,fontWeight=FontWeight.Bold)
                Text("日期",Modifier.padding(top=16.dp),color=reportMuted)
                listOf("本月","上月","今年","去年").chunked(2).forEachIndexed{row,labels->Row{labels.forEachIndexed{index,label->TextButton(onClick={quick(row*2+index)}){Text(label)}}}}
                OutlinedTextField(first,{first=it},label={Text("开始日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(last,{last=it},label={Text("结束日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(dateError.isNotEmpty())Text(dateError,color=reportRed,fontSize=12.sp)
                TextButton(onClick={runCatching{val a=java.time.LocalDate.parse(first);val b=java.time.LocalDate.parse(last);ReportPeriod(YearMonth.from(a),false,a,b);a to b}.onSuccess{custom(it.first,it.second)}.onFailure{dateError="请填写有效起止日期，结束不早于开始，最多十年"}}){Text("使用自定义周期")}
                Text("资金账户 · CNY",Modifier.padding(top=16.dp),color=reportMuted)
                Text("不勾选表示全部",fontSize=11.sp,color=reportMuted)
                vm.accounts.filter{it.optString("currency")=="CNY"}.forEach{item->ReportFilterChoice(item.optString("name"),item.id() in accounts){accounts=if(item.id() in accounts)accounts-item.id() else accounts+item.id()}}
                Text("分类",Modifier.padding(top=16.dp),color=reportMuted)
                vm.categories.filter{it.optInt("type") in 1..2&&it.id("parentId")!="0"}.forEach{item->ReportFilterChoice(item.optString("name"),item.id() in categories){categories=if(item.id() in categories)categories-item.id() else categories+item.id()}}
                Text("标签",Modifier.padding(top=16.dp),color=reportMuted)
                ReportFilterChoice("必须包含全部所选标签",allTags){allTags=!allTags}
                vm.tags.filterNot{it.optBoolean("hidden")}.forEach{item->ReportFilterChoice(item.optString("name"),item.id() in tags){tags=if(item.id() in tags)tags-item.id() else tags+item.id()}}
                Row{TextButton(onClick={accounts=emptySet();categories=emptySet();tags=emptySet();allTags=false}){Text("重置")};TextButton(onClick=dismiss){Text("取消")};Button(onClick={apply(ReportFilter(accounts,categories,tags,allTags))}){Text("应用")}}
            }
        }
    }
}
@Composable private fun ReportFilterChoice(label:String,checked:Boolean,click:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=click).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(checked,onCheckedChange={click()});Text(label,fontSize=14.sp)}}
@Composable private fun ReportCategoryStatistics(vm:BooksModel,initial:ReportPeriod,category:ReportCategory,filter:ReportFilter,dismiss:()->Unit,changed:()->Unit){
    var period by remember{mutableStateOf(initial)}
    var data by remember{mutableStateOf<ReportSnapshot?>(null)}
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var total by remember{mutableLongStateOf(0)};var page by remember{mutableIntStateOf(1)}
    var failure by remember{mutableStateOf("")};var retry by remember{mutableIntStateOf(0)};var loading by remember{mutableStateOf(true)}
    var yearPicker by remember{mutableStateOf(false)}
    var graphKind by remember{mutableIntStateOf(if(category.category.optInt("type")==2)2 else 1)}
    val selectedFilter=filter.copy(categoryIds=setOf(category.category.id()))
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    fun refresh(){page=1;rows=emptyList();data=null;total=0;retry++;changed()}
    ReportRefreshAfterEditor(vm,::refresh)
    fun changePeriod(value:ReportPeriod){period=value;page=1;rows=emptyList();data=null;total=0}
    LaunchedEffect(period,page,retry){
        loading=true;failure=""
        try{
            if(page==1){
                val(next,pageResult)=coroutineScope{val snapshot=async{vm.api.report(period,vm.accounts,vm.categories,selectedFilter)};val details=async{vm.api.reportCategoryPage(period,category.category.id(),vm.accounts,page,selectedFilter)};snapshot.await() to details.await()}
                data=next;rows=pageResult.first;total=pageResult.second
            }else{
                val(result,count)=vm.api.reportCategoryPage(period,category.category.id(),vm.accounts,page,selectedFilter)
                rows=(rows+result).distinctBy{it.id()};total=count
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){vm.imageFailed(e);failure="分类统计加载失败，请重试"}finally{loading=false}
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Column(Modifier.fillMaxSize().background(ledgerColors.background).systemBarsPadding().testTag("report-category-statistics")){
            Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=dismiss){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回明细")}
                Column(Modifier.weight(1f)){Text("分类统计-"+category.category.optString("name"),fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text(period.label,fontSize=12.sp,lineHeight=16.sp,color=reportMuted)}
            }
            Row(Modifier.fillMaxWidth().background(reportSurface),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={yearPicker=true},modifier=Modifier.width(86.dp)){Text("${period.month.year}年",fontSize=16.sp,fontWeight=FontWeight.Bold,color=ledgerColors.text)}
                androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f),state=androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex=12-period.month.monthValue)){
                    items((12 downTo 1).toList()){month->
                        Box(Modifier.width(62.dp).height(48.dp).testTag("category-report-month-$month").clickable{changePeriod(ReportPeriod(java.time.YearMonth.of(period.month.year,month)))},contentAlignment=Alignment.Center){
                            if(!period.yearly&&!period.custom&&month==period.month.monthValue)Box(Modifier.width(52.dp).height(28.dp).background(ledgerColors.selection,CircleShape))
                            Text("${month}月",fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold,color=if(!period.yearly&&!period.custom&&month==period.month.monthValue)MaterialTheme.colorScheme.primary else reportMuted)
                        }
                    }
                }
            }
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(failure.isNotEmpty())Row{Text(failure);TextButton(onClick={retry++}){Text("重试")}}
            LazyColumn(Modifier.fillMaxSize().testTag("category-statistics-list"),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                data?.let{snapshot->val expenseCategory=category.category.optInt("type")==2;val amount=if(expenseCategory)snapshot.expense else snapshot.income
                    item{ReportCard(""){
                        Row(Modifier.fillMaxWidth()){ReportMetric(if(expenseCategory)"总支出"else "总收入",money(amount.toString()),Modifier.weight(1f));ReportMetric("结余",money((snapshot.income-snapshot.expense).toString()),Modifier.weight(1f));ReportMetric("总笔数",total.toString(),Modifier.weight(1f))}
                        Row(Modifier.fillMaxWidth()){ReportMetric("平均每笔",reportAverage(amount,total),Modifier.weight(1f));ReportMetric("平均每天",reportAverage(amount,period.days.toLong()),Modifier.weight(1f));Spacer(Modifier.weight(1f))}
                        if(snapshot.days.any{it.active}){
                            ReportChart(snapshot,if(graphKind==2)2 else 3,false,118.dp)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){ReportSegments(listOf("支出","收入"),if(graphKind==2)0 else 1,"category-detail-kind"){graphKind=if(it==0)2 else 1}}
                            ReportDonut(snapshot.ranking(graphKind),"",diameter=146.dp,height=190.dp)
                        }else Text("暂无收支",Modifier.padding(32.dp),color=reportMuted)
                        Text("个人 · CNY · 上海时间 · 外币不合并",fontSize=9.sp,lineHeight=12.sp,color=reportMuted)
                    }}
                }
                rows.groupBy{java.time.Instant.ofEpochSecond(it.optLong("time")).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()}.forEach{(date,dayRows)->
                    item{Surface(Modifier.fillMaxWidth(),color=reportSurface,shape=RoundedCornerShape(topStart=8.dp,topEnd=8.dp)){Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(date.toString().substring(5).replace('-','.'),fontSize=14.sp,fontWeight=FontWeight.Bold);Text(if(category.category.optInt("type")==2)"支出流水"else "收入流水",fontSize=12.sp)}}}
                    items(dayRows,key={it.id()}){row->Surface(color=reportSurface){ListItem(headlineContent={Text(category.category.optString("name"),fontSize=16.sp)},supportingContent={Text(row.optString("comment"),fontSize=12.sp)},trailingContent={val value=row.optString("sourceAmount").toBigInteger();Text(money((if(category.category.optInt("type")==2)value.negate() else value).toString()),color=if(category.category.optInt("type")==2)reportRed else reportGreen)},modifier=Modifier.testTag("category-statistics-row-${row.id()}").clickable{selected=row})}}
                }
                if(!loading&&failure.isEmpty()&&rows.size<total)item{TextButton(onClick={page++}){Text("加载更多")}}
            }
        }
        selected?.let{row->LedgerTransactionDetails(vm,row){selected=null;refresh()}}
        if(yearPicker)ReportPeriodChooser(period.copy(yearly=true),{yearPicker=false}){chosen->changePeriod(ReportPeriod(java.time.YearMonth.of(chosen.year,period.month.monthValue),period.yearly));yearPicker=false}
    }
}

internal fun encodeReportFilter(filter:ReportFilter):String=JSONObject().put("accounts",org.json.JSONArray(filter.accountIds.sorted())).put("categories",org.json.JSONArray(filter.categoryIds.sorted())).put("tags",org.json.JSONArray(filter.tagIds.sorted())).put("all",filter.allTags).toString()
internal fun decodeReportFilter(value:String):ReportFilter {val obj=JSONObject(value);fun ids(key:String):Set<String>{val rows=obj.optJSONArray(key)?:return emptySet();return(0 until rows.length()).map{rows.getString(it)}.toSet()};return ReportFilter(ids("accounts"),ids("categories"),ids("tags"),obj.optBoolean("all"))}
