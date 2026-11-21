package top.fjzhang233.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigInteger
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReportCategoryBreakdown(
    category:JSONObject,
    ranked:List<ReportCategory>,
    period:ReportPeriod,
    hasChildren:Boolean,
    loading:Boolean,
    visible:Boolean,
    openStatistics:()->Unit,
    openChild:(ReportCategory)->Unit,
    dismiss:()->Unit,
){
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    if(!visible) return
    val colors=rememberReportCategoryPalette(category.optInt("type"))
    val parts=remember(ranked,hasChildren,category.id()) {
        ranked.map { row ->
            if(hasChildren && row.category.id()==category.id()){
                row.copy(category=JSONObject(row.category.toString()).put("name","直接记入"))
            } else row
        }
    }
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=sheet,containerColor=reportSurface,dragHandle=null){
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).navigationBarsPadding().testTag("report-parent-category-sheet")){
            ReportCategorySheetHeader(category.optString("name"),"report-parent-category-statistics",openStatistics)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max=460.dp),
                state=listState,contentPadding=PaddingValues(bottom=12.dp),
            ){
                if(parts.isNotEmpty()){
                    item(key="chart"){
                        Box(Modifier.fillMaxWidth().testTag("report-parent-category-chart")){
                            ReportDonut(parts,"分类占比",diameter=120.dp,height=148.dp,showGuide=false,categoryColors=colors)
                        }
                    }
                    item(key="categories"){ ReportCategoryRanking(parts,period,"report-child-category",colors,openChild) }
                } else if(!loading){
                    item(key="empty"){ Text("所选期间暂无分类数据",Modifier.padding(vertical=24.dp),color=reportMuted) }
                }
            }
        }
    }
}

@Composable private fun ReportCategorySheetHeader(title:String,tag:String,openStatistics:()->Unit){
    Row(
        Modifier.fillMaxWidth().heightIn(min=56.dp).padding(vertical=4.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically,
    ){
        Text(title,Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
        LedgerControl(Modifier.testTag(tag), onClick = openStatistics) { interactions ->
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier.ledgerControlFeedback(interactions, shape).border(1.dp,ledgerColors.divider,shape)
                    .padding(horizontal=9.dp,vertical=5.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically,
            ){
                Text("统计",fontSize=14.sp,lineHeight=18.sp,color=reportMuted)
                Icon(Icons.Outlined.PieChart,null,Modifier.size(14.dp),tint=reportMuted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReportCategoryDetails(
    vm: BooksModel,
    query: ReportQuery,
    sourceQuery: ReportQuery,
    category: JSONObject,
    title: String,
    amount: BigInteger?,
    parentIds: Set<String>,
    directParentIds: Set<String>,
    visible: Boolean,
    openStatistics: (CategoryStatisticsPage) -> Unit,
    dismiss: () -> Unit,
){
    val period=query.period()
    val filter=query.filter()
    val resolved=reportCategoryScope(category.id(),vm.categories,filter.categoryScope)
    val ids=(resolved as? ReportCategoryScope.Only)?.ids.orEmpty() - (parentIds - directParentIds)
    val scope=if(ids.isEmpty()) ReportCategoryScope.Empty else ReportCategoryScope.Only(ids)
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var page by remember(vm.searchInvalidation,scope){mutableIntStateOf(1)}
    var total by remember{mutableLongStateOf(0)};var loading by remember{mutableStateOf(true)};var failure by remember{mutableStateOf("")};var retry by remember{mutableIntStateOf(0)}
    LaunchedEffect(query,scope,page,retry,vm.searchInvalidation,vm.accounts,directParentIds){
        loading=true;failure=""
        try{val(result,count)=vm.api.reportCategoryPage(period,scope,vm.accounts,page,filter,directParentIds);rows=if(page==1)result else(rows+result).distinctBy{it.id()};total=count}
        catch(e:CancellationException){throw e}catch(e:Exception){vm.imageFailed(e);failure="明细加载失败，请重试"}finally{loading=false}
    }
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    if (visible) ModalBottomSheet(onDismissRequest=dismiss,sheetState=sheet,containerColor=reportSurface,dragHandle=null){Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).navigationBarsPadding().testTag("report-category-sheet")){
        ReportCategorySheetHeader(title,"report-category-sheet-statistics"){
            openStatistics(CategoryStatisticsPage(query,category.id(),title,category.optInt("type")))
        }
        val context=listOfNotNull(period.label,amount?.let { money(it.toString()) }).joinToString(" · ")
        Text(context,Modifier.padding(bottom=6.dp),fontSize=12.sp,color=reportMuted)
        LedgerTransactionDivider(Modifier.fillMaxWidth())
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(failure.isNotEmpty())Row{Text(failure,color=reportRed);TextButton(onClick={retry++}){Text("重试")}}
        LazyColumn(Modifier.fillMaxWidth().heightIn(min=144.dp,max=420.dp),state=listState){
            itemsIndexed(rows,key={_,row->row.id()}){index,row->
                val date=shanghaiReportDate(row.optLong("time")).toString()
                LedgerTransactionRow(row,index==0,Modifier.testTag("report-category-row-${row.id()}"),dateLabel=date){selected=row}
            }
            if(!loading&&failure.isEmpty()&&rows.size<total)item{TextButton(onClick={page++}){Text("加载更多")}}
            if(!loading&&failure.isEmpty()&&rows.isEmpty())item{Text("暂无明细",Modifier.padding(24.dp),color=reportMuted)}
        }
    }}
    if(visible) selected?.let{row->LedgerTransactionDetails(vm,row,openStatistics,sourceQuery){selected=null}}
}

@Composable internal fun ReportCategoryStatistics(vm: BooksModel, route: CategoryStatisticsPage, dismiss: () -> Unit) {
    var query by rememberSaveable(stateSaver = ReportQuery.saver) { mutableStateOf(route.query) }
    val period = query.period()
    val filter = query.filter()
    val scope = remember(route.categoryId, vm.categories, filter.categoryIds) {
        reportCategoryScope(route.categoryId, vm.categories, filter.categoryScope)
    }
    val parentIds = remember(vm.categories) { vm.categories.reportParentIds() }
    val expense = route.categoryType == 2
    var data by remember(query, scope, vm.searchInvalidation) { mutableStateOf<ReportSnapshot?>(null) }
    var slice by rememberSaveable { mutableStateOf<String?>(null) }
    var yearPicker by rememberSaveable { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var reportError by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val monthsState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = 12 - period.month.monthValue)
    val coroutineScope = rememberCoroutineScope()

    fun change(period: ReportPeriod, filter: ReportFilter) {
        query = ReportQuery(period, filter)
        slice = null
        coroutineScope.launch { listState.scrollToItem(0) }
    }
    LaunchedEffect(query, scope, retry, vm.searchInvalidation, vm.accounts, vm.categories) {
        reportError = ""
        try {
            data = vm.api.report(period, vm.accounts, vm.categories, filter, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.imageFailed(e)
            reportError = "分类统计加载失败"
        }
    }

    val ranked = data?.ranking(route.categoryType).orEmpty()
    val activeSlice = ranked.firstOrNull { it.category.id() == slice }
    val detailScope = data?.detailCategoryScope(activeSlice?.category?.id())
    val directParents = ranked.mapNotNull { it.category.id().takeIf(parentIds::contains) }.toSet()
    var page by remember(query, detailScope, vm.searchInvalidation) { mutableIntStateOf(1) }
    var rows by remember(query, detailScope, vm.searchInvalidation) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember(query, detailScope, vm.searchInvalidation) { mutableStateOf<Long?>(null) }
    var loadingRows by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }
    LaunchedEffect(query, detailScope, page, retry, vm.searchInvalidation, vm.accounts, directParents) {
        if (detailScope == null) return@LaunchedEffect
        loadingRows = true
        detailError = ""
        try {
            val (result, count) = vm.api.reportCategoryPage(period, detailScope, vm.accounts, page, filter, directParents)
            rows = if (page == 1) result else (rows + result).distinctBy { it.id() }
            total = count
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.imageFailed(e)
            detailError = "流水加载失败"
        } finally {
            loadingRows = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = ledgerColors.background, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().testTag("report-category-statistics")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回明细") }
                Column(Modifier.weight(1f).padding(vertical=6.dp)) {
                    Text(route.categoryName, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("分类统计 · ${period.label}", Modifier.testTag("category-statistics-period"), fontSize = 12.sp, color = reportMuted)
                }
                IconButton(onClick = { filtering = true }, modifier = Modifier.testTag("category-statistics-filter")) {
                    Icon(Icons.Outlined.FilterAlt, "筛选分类统计")
                }
            }
            Row(Modifier.fillMaxWidth().background(reportSurface).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { yearPicker = true }) { Text("${period.month.year}年", color = ledgerColors.text) }
                CategoryPeriodButton("全年", period.yearly && !period.custom, "category-report-year") {
                    change(ReportPeriod(period.month, yearly = true), filter)
                }
                androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f), state = monthsState) {
                    items((12 downTo 1).toList()) { month ->
                        CategoryPeriodButton("${month}月", !period.yearly && !period.custom && month == period.month.monthValue, "category-report-month-$month") {
                            change(ReportPeriod(YearMonth.of(period.month.year, month)), filter)
                        }
                    }
                }
            }
            val appliedFilters = listOfNotNull(
                filter.accountIds.size.takeIf { it > 0 }?.let { "账户 $it" },
                filter.categoryIds.size.takeIf { it > 0 }?.let { "分类 $it" },
                filter.tagIds.size.takeIf { it > 0 }?.let { "标签 $it" },
            )
            if (appliedFilters.isNotEmpty()) Text(appliedFilters.joinToString(" · "), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = reportMuted)
            if ((data == null && reportError.isEmpty()) || loadingRows) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (reportError.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reportError, Modifier.padding(start = 16.dp), color = reportRed)
                TextButton(onClick = { retry++ }) { Text("重试") }
            }

            val byMonth = period.yearly && !period.custom
            val groups = rows.groupBy {
                val date = shanghaiReportDate(it.optLong("time"))
                when {
                    byMonth -> "${date.monthValue}月"
                    period.first.year != period.until.minusDays(1).year -> date.toString()
                    else -> date.toString().substring(5).replace('-', '.')
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("category-statistics-list").semantics {
                    stateDescription = when {
                        detailError.isNotEmpty() -> "流水加载失败"
                        total == null -> "正在加载流水"
                        else -> "流水已加载"
                    }
                },
                state = listState, contentPadding = PaddingValues(12.dp),
            ) {
                data?.let { snapshot ->
                    val amount = if (expense) snapshot.expense else snapshot.income
                    item(key = "summary") {
                        ReportCard("") {
                            Row(Modifier.fillMaxWidth()) {
                                ReportMetric(if (expense) "总支出" else "总收入", money(amount.toString()), Modifier.weight(1f))
                                ReportMetric("结余", money((snapshot.income - snapshot.expense).toString()), Modifier.weight(1f))
                                ReportMetric("平均每天", reportAverage(amount, period.days.toLong()), Modifier.weight(1f))
                            }
                            if (snapshot.days.any { it.active }) ReportChart(snapshot, if (expense) 2 else 3, false, 98.dp)
                            Text(if (snapshot.excludedForeign) "CNY · 上海时间 · 已排除外币" else "CNY · 上海时间",
                                Modifier.testTag("category-statistics-summary"), fontSize = 12.sp, color = reportMuted)
                        }
                    }
                    if (ranked.isNotEmpty()) item(key = "composition") {
                        Spacer(Modifier.height(8.dp))
                        CategoryComposition(ranked, route, route.categoryId in parentIds, activeSlice?.category?.id()) { slice = it }
                    }
                    item(key = "transactions-heading") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (activeSlice == null) "流水" else categorySliceName(activeSlice, route, route.categoryId in parentIds),
                                    Modifier.align(Alignment.CenterVertically),fontWeight = FontWeight.SemiBold)
                                val count = total
                                if (count != null) Text("${count} 笔 · 平均每笔 ${reportAverage(activeSlice?.amount ?: amount, count)}",
                                    Modifier.align(Alignment.CenterVertically).testTag("category-statistics-count"), fontSize = 12.sp, color = reportMuted)
                            }
                            if (activeSlice != null) TextButton(onClick = { slice = null }, modifier = Modifier.testTag("category-statistics-clear-slice")) { Text("查看全部流水") }
                        }
                    }
                }
                groups.forEach { (label, group) ->
                    item(key = "group-$label") {
                        Surface(
                            Modifier.fillMaxWidth().padding(top = 6.dp).testTag("category-statistics-group-$label"),
                            color = reportSurface, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                        ) {
                            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    itemsIndexed(group, key = { _, row -> row.id() }) { index, row ->
                        val date=if(byMonth) shanghaiReportDate(row.optLong("time")).toString().substring(5) else null
                        LedgerTransactionRow(row, index == 0, Modifier.testTag("category-statistics-row-${row.id()}"), dateLabel = date) { selected = row }
                    }
                }
                if (detailError.isNotEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(detailError, color = reportRed)
                        TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                }
                if (!loadingRows && detailError.isEmpty() && rows.size.toLong() < (total ?: 0L)) {
                    item { TextButton(onClick = { page++ }) { Text("加载更多") } }
                }
                if (total == 0L && detailError.isEmpty()) item {
                    Text("所选范围内暂无流水", Modifier.fillMaxWidth().padding(24.dp), color = reportMuted)
                }
            }
        }
        selected?.let { row -> LedgerTransactionDetails(vm, row) { selected = null } }
        if (yearPicker) ReportPeriodChooser(period.copy(yearly = true), { yearPicker = false }) { chosen ->
            change(ReportPeriod(YearMonth.of(chosen.year, period.month.monthValue), yearly = period.yearly), filter)
            yearPicker = false
        }
        if (filtering) ReportFilterPanel(
            vm, filter, { filtering = false }, { change(period, it); filtering = false },
            categoryOptions = vm.categories.filter { it.id("parentId") == route.categoryId || (route.categoryId in parentIds && it.id() == route.categoryId) },
        )
    }
}

@Composable private fun CategoryPeriodButton(label: String, selected: Boolean, tag: String, click: () -> Unit) {
    LedgerControl(Modifier.testTag(tag), selected = selected, onClick = click) { interactions ->
        Text(label, Modifier.padding(horizontal = 3.dp).ledgerControlFeedback(interactions)
            .background(if (selected) ledgerColors.selection else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 5.dp), color = if (selected) MaterialTheme.colorScheme.primary else reportMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun categorySliceName(row:ReportCategory,route:CategoryStatisticsPage,parent:Boolean):String =
    if (parent && row.category.id() == route.categoryId) "${route.categoryName}（直接记入）" else row.category.optString("name")

@Composable private fun CategoryComposition(
    ranked:List<ReportCategory>,route:CategoryStatisticsPage,parent:Boolean,selection:String?,select:(String?)->Unit,
) {
    val total = ranked.fold(BigInteger.ZERO) { sum, row -> sum + row.amount.max(BigInteger.ZERO) }
    val colors = rememberReportCategoryPalette(route.categoryType)
    ReportCard("") {
        Text("分类构成",Modifier.align(Alignment.Start),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
        val chartRows = ranked.map { row ->
            val label = categorySliceName(row, route, parent)
            if(label == row.category.optString("name")) row
            else row.copy(category = JSONObject(row.category.toString()).put("name", label))
        }
        ReportDonut(chartRows, "¥${money(total.toString())}", diameter = 140.dp, height = 156.dp,
            selection = selection, onSelectionChange = select, showGuide = false, categoryColors = colors)
        if (ranked.any { it.amount.signum() < 0 }) {
            Text("占比按正净额计算。负净额计入合计和流水。", fontSize = 12.sp, color = reportMuted)
        }
        chartRows.forEachIndexed { index, row ->
            val id = row.category.id()
            if (index > 0) LedgerTransactionDivider(Modifier.padding(start = 20.dp))
            Row(
                Modifier.fillMaxWidth().testTag("category-statistics-slice-$id")
                    .background(if (selection == id) ledgerColors.selection else Color.Transparent, RoundedCornerShape(8.dp))
                    .heightIn(min = 48.dp).clickable(onClickLabel = "查看分项流水") { select(if (selection == id) null else id) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (row.amount.signum() > 0) colors[index].fill else reportMuted, CircleShape))
                Text(row.category.optString("name"), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(reportPercent(row.amount, total), fontSize = 12.sp, color = reportMuted)
                Text(money(row.amount.toString()), fontSize = 14.sp)
            }
        }
    }
}
