package top.fjzhang233.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.math.BigInteger
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyCategoryStatistics(vm: BooksModel, route: FamilyStatisticsPage, back: () -> Unit) {
    var month by rememberSaveable { mutableStateOf(route.query.month) }
    val period = ReportPeriod(YearMonth.parse(month), route.query.yearly)
    var retry by remember { mutableIntStateOf(0) }
    var snapshot by remember(period, route) { mutableStateOf<ReportSnapshot?>(null) }
    var failed by remember(period, retry) { mutableStateOf(false) }
    var detail by rememberSaveable(period.month.toString()) { mutableStateOf<String?>(null) }
    LaunchedEffect(period, route, retry) {
        try {
            snapshot = familyReportSnapshot(vm.api.familyReport(period, route.memberId), period)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    val data = snapshot
    val parts = remember(data, route) {
        if (route.parent) data?.familyCategoryParts(route.categoryId, route.categoryType).orEmpty()
        else data?.categories.orEmpty().filter { it.category.id() == route.categoryId }
    }
    Scaffold(containerColor = ledgerColors.background, topBar = {
        CenterAlignedTopAppBar(title = { Text(route.categoryName) }, navigationIcon = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("family-category-statistics"),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = period.shift(-1).month.toString() }) { Icon(Icons.Outlined.ChevronLeft, "上一期间") }
                    Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(period.label)
                        Text("家庭分类统计 · ${route.memberName}", style = MaterialTheme.typography.bodySmall, color = reportMuted)
                    }
                    IconButton(onClick = { month = period.shift(1).month.toString() }) { Icon(Icons.Outlined.ChevronRight, "下一期间") }
                }
            }
            if (failed) item { TextButton(onClick = { retry++ }) { Text("加载失败，重试") } }
            else if (data == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else {
                val current = parts.fold(BigInteger.ZERO) { sum, row -> sum + row.amount }
                val previous = data.familyCatalog.filter { it.id() == route.categoryId || (route.parent && it.id("parentId") == route.categoryId) }
                    .fold(BigInteger.ZERO) { sum, category -> sum + (data.previousAmounts[category.id()] ?: BigInteger.ZERO) }
                item {
                    ReportCard("收支概览") {
                        Row {
                            ReportMetric(if (route.categoryType == 2) "支出" else "收入", money(current.toString()), Modifier.weight(1f))
                            ReportMetric(if (period.yearly) "上年" else "上月", money(previous.toString()), Modifier.weight(1f))
                            ReportMetric("差额", money((current - previous).toString()), Modifier.weight(1f))
                        }
                    }
                }
                if (parts.isEmpty()) item { Text("所选期间暂无分类流水", Modifier.padding(16.dp), color = reportMuted) }
                else item {
                    val colors = rememberReportCategoryPalette(route.categoryType)
                    ReportCard("分类构成") {
                        ReportDonut(parts, "分类占比", categoryColors = colors)
                        ReportCategoryRanking(parts, period, "family-statistics-part", colors) { detail = it.category.id() }
                    }
                }
            }
        }
    }
    detail?.let { id ->
        val title = parts.firstOrNull { it.category.id() == id }?.category?.optString("name") ?: route.categoryName
        FamilyReportTransactionsSheet(vm, period, route.memberId, "", id, title, dismiss = { detail = null })
    }
}
