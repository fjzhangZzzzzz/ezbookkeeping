package top.fjzhang233.books

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.YearMonth

internal val reportSurface:Color @Composable get()=ledgerColors.surface
internal val reportMuted:Color @Composable get()=ledgerColors.muted
internal val reportRed:Color @Composable get()=ledgerColors.expense
internal val reportGreen:Color @Composable get()=ledgerColors.income
internal fun reportAverage(value:BigInteger,count:Long):String=if(count<=0)"0.00" else value.toBigDecimal().divide(BigDecimal.valueOf(count),0,RoundingMode.HALF_EVEN).movePointLeft(2).setScale(2).toPlainString()
internal fun reportRatio(value:BigInteger,total:BigInteger):Float=if(total.signum()<=0)0f else value.toBigDecimal().divide(total.toBigDecimal(),8,RoundingMode.HALF_UP).toFloat()
internal fun reportPercent(value:BigInteger,total:BigInteger)=when {
    value.signum()<0 -> "—"
    total.signum()<=0 -> "0.00%"
    else -> value.toBigDecimal().multiply(BigDecimal(100)).divide(total.toBigDecimal(),2,RoundingMode.HALF_UP).toPlainString()+"%"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LedgerReport(
    vm: BooksModel,
    dismiss: () -> Unit,
    openChart: (FullscreenChartPage, ReportSnapshot) -> Unit,
    showReportSheets: Boolean,
    openStatistics: (CategoryStatisticsPage) -> Unit,
){
    var selectedMonth by rememberSaveable{mutableStateOf(vm.ledgerMonth)}
    var yearly by rememberSaveable{mutableStateOf(false)}
    var customFirst by rememberSaveable{mutableStateOf("")};var customLast by rememberSaveable{mutableStateOf("")}
    val period=ReportPeriod(YearMonth.parse(selectedMonth),yearly,customFirst.takeIf{it.isNotBlank()}?.let(java.time.LocalDate::parse),customLast.takeIf{it.isNotBlank()}?.let(java.time.LocalDate::parse))
    fun adopt(value:ReportPeriod){selectedMonth=value.month.toString();customFirst=value.customFirst?.toString().orEmpty();customLast=value.customLast?.toString().orEmpty()}
    var snapshot by remember{mutableStateOf<ReportSnapshot?>(null)}
    var loading by remember{mutableStateOf(true)};var failure by remember{mutableStateOf("")};var reload by remember{mutableIntStateOf(0)}
    var chartType by rememberSaveable{mutableIntStateOf(2)};var categoryType by rememberSaveable{mutableIntStateOf(2)}
    var lines by rememberSaveable{mutableStateOf(false)}
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChild by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDay by rememberSaveable{mutableStateOf<String?>(null)}
    var choosePeriod by remember{mutableStateOf(false)};var filtering by remember{mutableStateOf(false)};var savedFilter by rememberSaveable{mutableStateOf("{}")};val filter=decodeReportFilter(savedFilter)
    LaunchedEffect(period,reload,vm.token,filter,vm.searchInvalidation,vm.accounts,vm.categories){
        loading=true;failure="";snapshot=null
        try { snapshot=vm.api.report(period,vm.accounts,vm.categories,filter) }
        catch(e:CancellationException){throw e}
        catch(e:Exception){vm.imageFailed(e);failure=if(e is ApiFailure)e.message.orEmpty() else "报表暂时无法加载，请重试"}
        finally {loading=false}
    }
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
                    IconButton(
                        onClick = { openChart(FullscreenChartPage(ReportQuery(period, filter), chartType, lines), data) },
                        modifier = Modifier.size(36.dp),
                    ) { Icon(Icons.Outlined.Fullscreen, "全屏图表", tint = reportMuted) }
                    IconButton(onClick={lines=!lines},modifier=Modifier.size(36.dp).testTag("report-chart-toggle")){Icon(if(lines)Icons.Outlined.BarChart else Icons.Outlined.ShowChart,if(lines)"切换柱状图" else "切换折线图",tint=reportMuted)}
                }){
                    ReportChart(data,chartType,lines)
                    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.Center){ReportSegments(if(yearly)listOf("支出","收入","结余","全部") else listOf("支出","收入","全部"),chartType-2,"trend"){chartType=it+2}}}}
                item{ReportCard("分类报表"){
                    val ranked=data.parentRanking(categoryType,vm.categories)
                    val colors=rememberReportCategoryPalette(categoryType)
                    ReportDonut(ranked,if(categoryType==2)"支出比例" else "收入比例",categoryColors=colors)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){
                        ReportSegments(listOf("支出","收入"),if(categoryType==2)0 else 1,"category"){categoryType=if(it==0)2 else 1}
                    }
                    ReportCategoryRanking(ranked,period,"report-category",colors) { row -> selectedCategory=row.category.id() }
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
        val reportQuery=ReportQuery(period,filter)
        val parentIds=remember(vm.categories){vm.categories.reportParentIds()}
        val directParentIds=snapshot?.categories.orEmpty().mapNotNull{it.category.id().takeIf(parentIds::contains)}.toSet()
        selectedDay?.let{day->
            ReportDayTransactions(vm,java.time.LocalDate.parse(day),reportQuery,directParentIds,showReportSheets,openStatistics,{selectedDay=null}){reload++}
        }
        val category=vm.categories.firstOrNull { it.id()==selectedCategory }
        if(category!=null) {
            val type=category.optInt("type")
            val breakdown=category.id("parentId")=="0"
            if(breakdown) {
                val scope=reportCategoryScope(category.id(),vm.categories,filter.categoryScope)
                val children=snapshot?.ranking(type).orEmpty().filter { scope.accepts(it.category.id()) }
                ReportCategoryBreakdown(
                    category,children,period,category.id() in parentIds,loading,showReportSheets,
                    openStatistics={openStatistics(CategoryStatisticsPage(reportQuery,category.id(),category.optString("name"),type))},
                    openChild={selectedChild=it.category.id()},
                    dismiss={selectedChild=null;selectedCategory=null},
                )
            }
            val detailId=if(breakdown)selectedChild else selectedCategory
            val detail=vm.categories.firstOrNull { it.id()==detailId }
            if(detail!=null) key(detail.id()) {
                val amount=snapshot?.categories?.firstOrNull { it.category.id()==detail.id() }?.amount
                val exactParent=detail.id() in parentIds
                val detailQuery=if(exactParent) ReportQuery(period,filter.copy(categoryIds=setOf(detail.id()))) else reportQuery
                val title=if(breakdown && exactParent) "${detail.optString("name")}（直接记入）" else detail.optString("name")
                ReportCategoryDetails(
                    vm,detailQuery,reportQuery,detail,title,amount,parentIds,directParentIds,showReportSheets,openStatistics,
                ) {
                    if(breakdown) selectedChild=null else selectedCategory=null
                }
            }
        }
    }
        }
}
@Composable internal fun ReportCategoryRanking(
    ranked:List<ReportCategory>,period:ReportPeriod,tagPrefix:String,colors:ReportCategoryPalette,open:(ReportCategory)->Unit,
){
    val total=ranked.fold(BigInteger.ZERO){sum,row -> sum+row.amount.max(BigInteger.ZERO)}
    val comparison=when {
        period.custom -> "上一期间"
        period.yearly -> "上年"
        else -> "上月"
    }
    if(ranked.any { it.amount.signum()<0 }) {
        Text("占比按正净额计算。负净额计入排名和合计。",fontSize=12.sp,color=reportMuted)
    }
    if(ranked.any { it.amount!=it.previous }) {
        Text("较$comparison",Modifier.fillMaxWidth(),fontSize=12.sp,lineHeight=16.sp,color=reportMuted,textAlign=TextAlign.End)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowWidth=maxWidth
        Column {
            ranked.forEachIndexed { index,row ->
                ReportRankingRow(row,total,colors[index],comparison,rowWidth,tagPrefix) { open(row) }
            }
        }
    }
}

@Composable private fun ReportRankingRow(
    row:ReportCategory,total:BigInteger,color:ReportCategoryColor,comparison:String,rowWidth:Dp,tagPrefix:String,open:()->Unit,
){
    val difference=row.amount-row.previous
    val name=row.category.optString("name")
    val share=reportPercent(row.amount,total)
    val amount=money(row.amount.toString())
    val change=if(difference.signum()==0) "" else money(difference.abs().toString())
    val changeModifier=Modifier.testTag("$tagPrefix-change-${row.category.id()}")
    val textStyle=LocalTextStyle.current
    val shareStyle=textStyle.copy(fontSize=12.sp,lineHeight=16.sp,fontFeatureSettings="tnum")
    val amountStyle=textStyle.copy(fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,fontFeatureSettings="tnum")
    val changeStyle=shareStyle.copy(fontWeight=FontWeight.Medium)
    val measurer=rememberTextMeasurer()
    val compact=with(LocalDensity.current) {
        fun width(value:String,style:TextStyle)=measurer.measure(value,style,softWrap=false,maxLines=1).size.width
        val iconAndSpacing=52.dp.roundToPx()
        val classificationNameAndGap=36.dp.roundToPx()
        val badgeWidth=if(change.isEmpty()) 0 else width(change,changeStyle)+29.dp.roundToPx()
        val required=iconAndSpacing+classificationNameAndGap+width(share,shareStyle)+width(amount,amountStyle)+badgeWidth
        fontScale<=1.25f && required<=rowWidth.roundToPx()
    }
    val values:@Composable ()->Unit={
        if(change.isNotEmpty()) {
            ReportChangeBadge(change,difference.signum()>0,row.category.optInt("type"),comparison,changeModifier,changeStyle)
        }
        Text(amount,style=amountStyle,softWrap=false)
    }
    Row(
        Modifier.fillMaxWidth().testTag("$tagPrefix-${row.category.id()}").clickable(onClick=open)
            .heightIn(min=52.dp).padding(vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp),
    ){
        Box(Modifier.size(32.dp).background(color.fill,CircleShape),contentAlignment=Alignment.Center){
            BuiltinIcon(row.category,tint=color.onFill,glyphSize=with(LocalDensity.current){20.dp.toSp()})
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            if(compact){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                    ReportRankingIdentity(name,share,shareStyle,Modifier.weight(1f))
                    values()
                }
            }else{
                ReportRankingIdentity(name,share,shareStyle,Modifier.fillMaxWidth())
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),
                    verticalArrangement=Arrangement.spacedBy(2.dp),
                ){
                    values()
                }
            }
            if(total.signum()>0){
                val fraction=reportRatio(row.amount.max(BigInteger.ZERO),total).coerceIn(0f,1f)
                Box(Modifier.fillMaxWidth().height(4.dp).background(ledgerColors.field,CircleShape)){
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color.fill,CircleShape))
                }
            }
        }
    }
}

@Composable private fun ReportRankingIdentity(name:String,share:String,shareStyle:TextStyle,modifier:Modifier){
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically){
        Text(name,Modifier.weight(1f,fill=false),fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,
            maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(share,style=shareStyle,color=reportMuted,softWrap=false)
    }
}

@Composable private fun ReportChangeBadge(
    amount:String,increase:Boolean,categoryType:Int,comparison:String,modifier:Modifier,style:TextStyle,
){
    val palette=ledgerColors
    val favorable=if(categoryType==1) increase else !increase
    val color=if(favorable) palette.income else palette.expense
    val direction=if(increase) "增加" else "减少"
    Row(
        modifier.background(color.copy(alpha=if(palette.dark) .20f else .09f),RoundedCornerShape(6.dp))
            .padding(horizontal=5.dp,vertical=2.dp)
            .clearAndSetSemantics { contentDescription="较$comparison$direction $amount" },
        horizontalArrangement=Arrangement.spacedBy(3.dp),
        verticalAlignment=Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width=8.dp,height=5.5.dp)) {
            val corner=.65.dp.toPx()
            val middle=size.width/2f
            val triangle=Path().apply {
                moveTo(middle-corner,corner)
                quadraticTo(middle,-corner,middle+corner,corner)
                lineTo(size.width-corner/4f,size.height-corner/2f)
                quadraticTo(size.width,size.height,size.width-corner,size.height)
                lineTo(corner,size.height)
                quadraticTo(0f,size.height,corner/4f,size.height-corner/2f)
                close()
            }
            scale(scaleX=1f,scaleY=if(increase) 1f else -1f) {
                drawPath(triangle,color)
            }
        }
        Text(amount,style=style,color=color,softWrap=false)
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
@Composable internal fun ReportSegments(labels:List<String>,selected:Int,group:String="generic",segmentWidth:Dp=52.dp,choose:(Int)->Unit){
    val period = group == "period"
    val trackShape = if (period) RoundedCornerShape(8.dp) else CircleShape
    val segmentShape = if (period) RoundedCornerShape(5.dp) else CircleShape
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().padding(vertical = 7.dp).background(ledgerColors.background, trackShape))
        Row(Modifier.padding(horizontal = 4.dp).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                LedgerControl(
                    Modifier.width(if (period) 48.dp else segmentWidth).testTag("report-segment-$group-$index"),
                    selected = active, onClick = { choose(index) },
                ) { interactions ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 28.dp)
                            .ledgerControlFeedback(interactions, segmentShape)
                            .background(if (active) reportSurface else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = if (period) 18.sp else 14.sp, lineHeight = 20.sp,
                            color = if (active) ledgerColors.text else reportMuted, textAlign = TextAlign.Center)
                    }
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
@Composable private fun ReportDayTransactions(
    vm:BooksModel,date:java.time.LocalDate,query:ReportQuery,directParentIds:Set<String>,visible:Boolean,
    openStatistics:(CategoryStatisticsPage)->Unit,dismiss:()->Unit,changed:()->Unit,
){
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())};var page by remember(vm.searchInvalidation){mutableIntStateOf(1)};var total by remember{mutableLongStateOf(0)}
    var loading by remember{mutableStateOf(true)};var failure by remember{mutableStateOf("")};var retry by remember{mutableIntStateOf(0)};var selected by remember{mutableStateOf<JSONObject?>(null)}
    fun refresh(){page=1;retry++;changed()}
    ReportRefreshAfterEditor(vm,::refresh)
    LaunchedEffect(date,page,retry,query,vm.searchInvalidation,vm.accounts,vm.categories,directParentIds){
        loading=true;failure=""
        try{val(result,count)=vm.api.reportDayPage(date,vm.accounts,vm.categories,page,query.filter(),directParentIds);rows=if(page==1)result else(rows+result).distinctBy{it.id()};total=count}
        catch(e:CancellationException){throw e}catch(e:Exception){vm.imageFailed(e);failure="当日流水加载失败，请重试"}finally{loading=false}
    }
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    if(visible) ModalBottomSheet(onDismissRequest=dismiss,containerColor=reportSurface,dragHandle=null){Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag("report-day-transactions")){
        Text(date.toString(),Modifier.padding(16.dp),fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("CNY · 上海时间 · 沿用报表筛选",Modifier.padding(horizontal=16.dp),fontSize=11.sp,color=reportMuted)
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(failure.isNotEmpty())Row{Text(failure,color=reportRed);TextButton(onClick={retry++}){Text("重试")}}
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp),state=listState){
            itemsIndexed(rows,key={_,row->row.id()}){index,row->
                LedgerTransactionRow(row,index==0,Modifier.testTag("report-day-transaction-${row.id()}")){selected=row}
            }
            if(!loading&&failure.isEmpty()&&rows.size<total)item{TextButton(onClick={page++}){Text("加载更多")}}
            if(!loading&&failure.isEmpty()&&rows.isEmpty())item{Text("暂无匹配的当日流水",Modifier.padding(24.dp),color=reportMuted)}
        }
    }}
    if(visible) selected?.let{row->LedgerTransactionDetails(vm,row,openStatistics,query){selected=null}}
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
/** Editor/copy remains over this route; refresh only once it closes, without losing its period/filter. */
@Composable private fun ReportRefreshAfterEditor(vm:BooksModel,refresh:()->Unit){
    var wasEditing by remember{mutableStateOf(vm.showEditor)}
    val latestRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(vm.showEditor){if(wasEditing&&!vm.showEditor)latestRefresh();wasEditing=vm.showEditor}
}

@Composable internal fun ReportFilterPanel(
    vm:BooksModel,initial:ReportFilter,dismiss:()->Unit,apply:(ReportFilter)->Unit,
    quick:((Int)->Unit)?=null,custom:((java.time.LocalDate,java.time.LocalDate)->Unit)?=null,
    categoryOptions:List<JSONObject> = vm.categories.filter{it.optInt("type") in 1..2&&it.id("parentId")!="0"},
){
    var accounts by remember{mutableStateOf(initial.accountIds)};var categories by remember{mutableStateOf(initial.categoryIds)};var tags by remember{mutableStateOf(initial.tagIds)};var allTags by remember{mutableStateOf(initial.allTags)};var first by rememberSaveable{mutableStateOf("")};var last by rememberSaveable{mutableStateOf("")};var dateError by remember{mutableStateOf("")}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Box(Modifier.fillMaxSize().clickable(onClick=dismiss),contentAlignment=Alignment.CenterEnd){
            Column(Modifier.fillMaxHeight().width(320.dp).background(reportSurface).clickable{}.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)){
                Text("筛选报表",fontSize=20.sp,fontWeight=FontWeight.Bold)
                if(quick!=null && custom!=null){
                    Text("日期",Modifier.padding(top=16.dp),color=reportMuted)
                    listOf("本月","上月","今年","去年").chunked(2).forEachIndexed{row,labels->Row{labels.forEachIndexed{index,label->TextButton(onClick={quick(row*2+index)}){Text(label)}}}}
                    OutlinedTextField(first,{first=it},label={Text("开始日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(last,{last=it},label={Text("结束日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(dateError.isNotEmpty())Text(dateError,color=reportRed,fontSize=12.sp)
                    TextButton(onClick={runCatching{val a=java.time.LocalDate.parse(first);val b=java.time.LocalDate.parse(last);ReportPeriod(YearMonth.from(a),false,a,b);a to b}.onSuccess{custom(it.first,it.second)}.onFailure{dateError="请填写有效起止日期，结束不早于开始，最多十年"}}){Text("使用自定义周期")}
                }
                Text("资金账户 · CNY",Modifier.padding(top=16.dp),color=reportMuted)
                Text("不勾选表示全部",fontSize=11.sp,color=reportMuted)
                vm.accounts.filter{it.optString("currency")=="CNY"}.forEach{item->ReportFilterChoice(item.optString("name"),item.id() in accounts){accounts=if(item.id() in accounts)accounts-item.id() else accounts+item.id()}}
                if(categoryOptions.isNotEmpty()){
                    Text("分类",Modifier.padding(top=16.dp),color=reportMuted)
                    categoryOptions.forEach{item->ReportFilterChoice(item.optString("name"),item.id() in categories){categories=if(item.id() in categories)categories-item.id() else categories+item.id()}}
                }
                Text("标签",Modifier.padding(top=16.dp),color=reportMuted)
                ReportFilterChoice("必须包含全部所选标签",allTags){allTags=!allTags}
                vm.tags.filterNot{it.optBoolean("hidden")}.forEach{item->ReportFilterChoice(item.optString("name"),item.id() in tags){tags=if(item.id() in tags)tags-item.id() else tags+item.id()}}
                Row{TextButton(onClick={accounts=emptySet();categories=emptySet();tags=emptySet();allTags=false}){Text("重置")};TextButton(onClick=dismiss){Text("取消")};Button(onClick={apply(ReportFilter(accounts,categories,tags,allTags))}){Text("应用")}}
            }
        }
    }
}
@Composable private fun ReportFilterChoice(label: String, checked: Boolean, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Checkbox, onValueChange = { click() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.size(48.dp))
        Text(label, fontSize = 14.sp)
    }
}
internal fun encodeReportFilter(filter:ReportFilter):String=JSONObject().put("accounts",org.json.JSONArray(filter.accountIds.sorted())).put("categories",org.json.JSONArray(filter.categoryIds.sorted())).put("tags",org.json.JSONArray(filter.tagIds.sorted())).put("all",filter.allTags).toString()
internal fun decodeReportFilter(value:String):ReportFilter {val obj=JSONObject(value);fun ids(key:String):Set<String>{val rows=obj.optJSONArray(key)?:return emptySet();return(0 until rows.length()).map{rows.getString(it)}.toSet()};return ReportFilter(ids("accounts"),ids("categories"),ids("tags"),obj.optBoolean("all"))}
