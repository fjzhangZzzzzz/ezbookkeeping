package top.fjzhang233.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyReportPage(
    vm: BooksModel,
    dismiss: () -> Unit,
    openChart: (FullscreenChartPage, ReportSnapshot) -> Unit,
    openStatistics: (FamilyStatisticsPage) -> Unit,
    showSheets: Boolean,
) {
    var selectedMonth by rememberSaveable { mutableStateOf(vm.month) }
    var yearly by rememberSaveable { mutableStateOf(false) }
    var memberId by rememberSaveable { mutableStateOf("0") }
    var choosePeriod by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<ReportSnapshot?>(null) }
    var payload by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var chartType by rememberSaveable { mutableIntStateOf(2) }
    var categoryType by rememberSaveable { mutableIntStateOf(2) }
    var lines by rememberSaveable { mutableStateOf(false) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var parentId by rememberSaveable { mutableStateOf<String?>(null) }
    var childId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseMember by remember { mutableStateOf(false) }
    val period = ReportPeriod(YearMonth.parse(selectedMonth), yearly)
    val scopeLabel = if (memberId == "0") "全家" else payload?.array("members")?.firstOrNull { it.id() == memberId }?.optString("nickname").orEmpty().ifBlank { "当前成员" }

    fun adopt(value: ReportPeriod) { selectedMonth = value.month.toString(); yearly = value.yearly }

    LaunchedEffect(period, memberId, reload, vm.token) {
        loading = true; failure = ""; payload = null; snapshot = null
        try {
            val result = vm.api.familyReport(period, memberId)
            payload = result
            snapshot = familyReportSnapshot(result, period)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.imageFailed(e); failure = if (e is ApiFailure) e.message.orEmpty() else "家庭报表暂时无法加载，请重试" }
        finally { loading = false }
    }
    LaunchedEffect(vm.token) { if (vm.token == null) dismiss() }
        Surface(Modifier.fillMaxSize().testTag("family-report"), color = ledgerColors.background) {
            Column(Modifier.fillMaxSize().background(ledgerColors.background).systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Spacer(Modifier.weight(1f)); ReportSegments(listOf("月", "年"), if (yearly) 1 else 0, "family-period", period = true) { yearly = it == 1; chartType = 2 }; Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.FilterAltOff, "家庭报表只读", tint = reportMuted)
                }
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { adopt(period.shift(-1)) }, enabled = !loading) { Icon(Icons.Outlined.ChevronLeft, "上一${if (yearly) "年" else "月"}") }
                    TextButton(onClick = { choosePeriod = true }) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(period.label, fontSize = 18.sp, lineHeight = 22.sp, color = ledgerColors.text); Text("CNY · 上海时间 · ${if (payload?.optBoolean("hasExcludedForeignCurrency") == true) "外币已排除" else "外币不合并"} · $scopeLabel", fontSize = 8.sp, lineHeight = 10.sp, color = reportMuted) }; Icon(Icons.Outlined.ExpandMore, null) }
                    IconButton(onClick = { adopt(period.shift(1)) }, enabled = !loading) { Icon(Icons.Outlined.ChevronRight, "下一${if (yearly) "年" else "月"}") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failure.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp)) { Text(failure); TextButton(onClick = { reload++ }) { Text("重新加载") } }
                payload?.let { data ->
                    val members = data.array("members")
                    LazyColumn(Modifier.fillMaxSize().testTag("family-list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { FamilyMemberScopeBar(scopeLabel, enabled = !loading) { chooseMember = true } }
                        snapshot?.let { report ->
                            item { FamilySummaryCard(report) }
                            if (report.days.any { it.active }) {
                                item {
                                    ReportCard(if (yearly) "月度对比" else "每日统计", actions = {
                                        IconButton(onClick = { openChart(FullscreenChartPage(ReportQuery(period), chartType, lines, memberId), report) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Fullscreen, "全屏图表", tint = reportMuted) }
                                        IconButton(onClick = { lines = !lines }, modifier = Modifier.size(36.dp).testTag("family-report-chart-toggle")) { Icon(if (lines) Icons.Outlined.BarChart else Icons.Outlined.ShowChart, if (lines) "切换柱状图" else "切换折线图", tint = reportMuted) }
                                    }) {
                                        ReportChart(report, chartType, lines)
                                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) { ReportSegments(if (yearly) listOf("支出", "收入", "结余", "全部") else listOf("支出", "收入", "全部"), chartType - 2, "family-trend") { chartType = it + 2 } }
                                    }
                                }
                                item(key = "category-report") {
                                    ReportCard("分类报表") {
                                        val ranked = report.parentRanking(categoryType, report.familyCatalog)
                                        val colors = rememberReportCategoryPalette(categoryType)
                                        if (ranked.isNotEmpty()) ReportDonut(ranked, if (categoryType == 2) "支出比例" else "收入比例", categoryColors = colors)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ReportSegments(listOf("支出", "收入"), if (categoryType == 2) 0 else 1, "family-category") { categoryType = if (it == 0) 2 else 1 } }
                                        ReportCategoryRanking(ranked, period, "family-report-category", colors) { parentId = it.category.id() }
                                        if (ranked.isEmpty()) Text("所选期间没有${if (categoryType == 2) "支出" else "收入"}", Modifier.padding(16.dp), color = reportMuted)
                                    }
                                }
                                item {
                                    ReportCard(if (yearly) "月报表" else "日报表") {
                                        ReportTableRow("日期", "收入", "支出", "结余", true)
                                        if (yearly) reportTableDays(report).forEach { (label, income, expense) -> ReportTableRow(label, money(income.toString()), money(expense.toString()), money((income - expense).toString())) }
                                        else report.days.filter { it.active }.asReversed().forEach { day -> ReportTableRow(day.date.toString().substring(5), money(day.income.toString()), money(day.expense.toString()), money((day.income - day.expense).toString()), modifier = Modifier.testTag("family-report-day-${day.date}"), onClick = { selectedDay = day.date.toString() }) }
                                        if (yearly) ReportTableRow("平均", reportAverage(report.income, report.activeMonths.toLong()), reportAverage(report.expense, report.activeMonths.toLong()), reportAverage(report.income - report.expense, report.activeMonths.toLong()))
                                    }
                                }
                            } else item { Text("暂无数据", Modifier.fillMaxWidth().padding(24.dp), color = reportMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                        }
                        item { FamilyMembersAndAccounts(data) }
                    }
                }
            }
        }
    if (choosePeriod) ReportPeriodChooser(period, { choosePeriod = false }) { selectedMonth = it.toString(); choosePeriod = false; reload++ }
    if (chooseMember) FamilyMemberScopeSheet(payload?.array("members").orEmpty(), memberId, { chooseMember = false }) { value -> memberId = value; reload++ }
    selectedDay?.let { date -> FamilyReportTransactionsSheet(vm, period, memberId, date, "", date, dismiss = { selectedDay = null }) }
    val report = snapshot
    if (report != null && parentId != null) {
        val parent = report.parentRanking(categoryType, report.familyCatalog).firstOrNull { it.category.id() == parentId }
        if (parent != null) {
            fun statistics(row: ReportCategory, group: Boolean) {
                openStatistics(FamilyStatisticsPage(ReportQuery(period), memberId, scopeLabel, row.category.id(),
                    row.category.optString("name"), categoryType, group))
            }
            val parts = report.familyCategoryParts(parent.category.id(), categoryType)
            ReportCategoryBreakdown(parent.category, parts, period,
                false, showSheets, { statistics(parent, true) }, { childId = it.category.id() }, { parentId = null })
            val child = parts.firstOrNull { it.category.id() == childId }
            if (child != null) FamilyReportTransactionsSheet(vm, period, memberId, "", child.category.id(),
                child.category.optString("name"), visible = showSheets, statistics = { statistics(child, false) }, dismiss = { childId = null })
        }
    }
}

@Composable
private fun FamilyMemberScopeBar(value: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.Button
                contentDescription = "选择成员范围，当前$value"
                stateDescription = if (enabled) "可展开" else "正在加载"
            }
            .testTag("family-member-scope"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = ledgerColors.controlSurface,
        border = if (ledgerColors.dark) BorderStroke(1.dp, ledgerColors.divider) else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.People, null, Modifier.size(20.dp), tint = ledgerColors.muted)
            Text("成员范围", Modifier.padding(start = 10.dp), fontSize = 14.sp, color = ledgerColors.muted)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ledgerColors.text)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 8.dp).size(22.dp), tint = ledgerColors.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilyMemberScopeSheet(members: List<JSONObject>, selected: String, dismiss: () -> Unit, choose: (String) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = dismiss,
        containerColor = ledgerColors.surface,
        contentColor = ledgerColors.text,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).selectableGroup()) {
            Text("选择成员范围", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            FamilyMemberScopeOption("0", "全家", "统计所有活跃成员", selected, true, choose)
            members.forEach { member ->
                FamilyMemberScopeOption(member.id(), member.optString("nickname"), "仅统计该成员", selected, false, choose)
            }
        }
    }
}

@Composable
private fun FamilyMemberScopeOption(id: String, label: String, supporting: String, selected: String, allMembers: Boolean, choose: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = id == selected, role = Role.RadioButton, onClick = { choose(id) })
            .testTag("family-scope-member-$id")
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (allMembers) Icons.Outlined.People else Icons.Outlined.Person, null, Modifier.size(22.dp), tint = if (id == selected) ledgerColors.accent else ledgerColors.muted)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, fontSize = 16.sp, color = ledgerColors.text)
            Text(supporting, fontSize = 12.sp, color = ledgerColors.muted)
        }
        RadioButton(selected = id == selected, onClick = null)
    }
}


@Composable
private fun FamilySummaryCard(report: ReportSnapshot) {
    ReportCard("收支总览") {
        Row(Modifier.fillMaxWidth()) { ReportMetric("支出", money(report.expense.toString()), Modifier.weight(1f)); ReportMetric("收入", money(report.income.toString()), Modifier.weight(1f)) }
        Row(Modifier.fillMaxWidth()) { ReportMetric("结余", money((report.income - report.expense).toString()), Modifier.weight(1f)); ReportMetric(if (report.period.yearly) "月均支出" else "日均支出", reportAverage(report.expense, if (report.period.yearly) report.activeMonths.toLong() else report.period.days.toLong()), Modifier.weight(1f)) }
    }
}

@Composable
private fun FamilyMembersAndAccounts(payload: JSONObject) {
    Text("成员统计", Modifier.fillMaxWidth(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    payload.array("members").forEach { row -> ReportCard("") { Row(Modifier.fillMaxWidth()) { Text(row.optString("nickname"), Modifier.weight(1f)); Text("收 ${money(row.optString("incomeMinor"))} · 支 ${money(row.optString("expenseMinor"))}", color = reportMuted, fontSize = 12.sp) } } }
    Text("家庭账户余额", Modifier.fillMaxWidth(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    payload.array("accounts").forEach { row -> ReportCard("") { ListItem(modifier = Modifier.testTag("family-account-${row.id()}"), headlineContent = { Text(row.optString("name")) }, supportingContent = { Text(row.optString("ownerName")) }, trailingContent = { Text("${row.optString("currency", "CNY")} ${money(row.optString("balanceMinor"))}") }) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyReportTransactionsSheet(vm: BooksModel, period: ReportPeriod, memberId: String, date: String, categoryId: String,
    title: String, visible: Boolean = true, statistics: (() -> Unit)? = null, dismiss: () -> Unit) {
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var nextCursor by remember { mutableStateOf("") }; var page by remember { mutableIntStateOf(0) }; var loading by remember { mutableStateOf(true) }; var failure by remember { mutableStateOf("") }; var retry by remember { mutableIntStateOf(0) }; var selected by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(period, memberId, date, categoryId, page, retry) {
        loading = true; failure = ""
        try {
            val result = vm.api.familyReportTransactions(period, memberId, date, categoryId, cursor = if (page == 0) "" else nextCursor)
            rows = if (page == 0) result.array("items") else rows + result.array("items")
            nextCursor = result.optString("nextCursor")
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = "家庭明细加载失败，请重试" }
        finally { loading = false }
    }
    val listState = rememberLazyListState()
    if (!visible) return
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = reportSurface, dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag("family-report-details")) {
            if (statistics != null) Box(Modifier.padding(horizontal = 16.dp)) { ReportCategorySheetHeader(title, "family-category-sheet-statistics", statistics) }
            else Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold); TextButton(onClick = dismiss) { Text("关闭") } }
            Text("CNY · 上海时间 · 家庭只读", Modifier.padding(horizontal = 16.dp), fontSize = 11.sp, color = reportMuted)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure.isNotEmpty()) Row(Modifier.padding(16.dp)) { Text(failure, color = reportRed); TextButton(onClick = { retry++ }) { Text("重试") } }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 144.dp, max = 420.dp), state = listState) {
                itemsIndexed(rows, key = { _, row -> row.id() }) { index, row ->
                    val type = row.optInt("type")
                    val description = listOf(shanghaiReportDate(row.optLong("time")).toString(), row.optString("comment")).filter { it.isNotBlank() }.joinToString(" · ")
                    CompactTransactionRow(row.optString("categoryName"), row.optString("ownerName"), description,
                        transactionSignedAmount(type, row.optString("amountMinor")), type, first = index == 0) { selected = row }
                }
                if (!loading && rows.isEmpty()) item { Text("暂无匹配的家庭流水", Modifier.padding(24.dp), color = reportMuted) }
                if (!loading && nextCursor.isNotBlank()) item { TextButton(onClick = { page++ }) { Text("加载更多") } }
            }
        }
    }
    selected?.let { row -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(row.optString("categoryName")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(row.optString("ownerName") + " · " + row.optString("accountName")); Text("${row.optString("currency", "CNY")} ${money(row.optString("amountMinor"))}"); if (row.optString("comment").isNotBlank()) Text(row.optString("comment")); Text("家庭共享仅查看；编辑自己的流水请前往「流水」。", fontSize = 12.sp, color = reportMuted) } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
}
