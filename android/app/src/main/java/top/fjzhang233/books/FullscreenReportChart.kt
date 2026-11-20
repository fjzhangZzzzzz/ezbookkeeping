package top.fjzhang233.books

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
internal fun ReportOrientation(chartOpen: Boolean) {
    val activity = LocalActivity.current
    LaunchedEffect(activity, chartOpen) {
        val orientation = if (chartOpen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity != null && activity.requestedOrientation != orientation) {
            activity.requestedOrientation = orientation
        }
    }
}

@Composable
internal fun FullscreenReportChart(vm: BooksModel, route: FullscreenChartPage, close: () -> Unit) {
    var snapshot by remember(route) {
        mutableStateOf(vm.fullscreenChart?.takeIf { it.first == route }?.second)
    }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(route, retry) {
        if (snapshot != null) return@LaunchedEffect
        failed = false
        val period = route.query.period()
        try {
            val data = if (route.memberId != null) {
                familyReportSnapshot(vm.api.familyReport(period, route.memberId), period)
            } else coroutineScope {
                val accounts = async { vm.accounts.ifEmpty { vm.api.accounts() } }
                val categories = async { vm.categories.ifEmpty { vm.api.categories() } }
                vm.api.report(period, accounts.await(), categories.await(), route.query.filter())
            }
            vm.fullscreenChart = route to data
            snapshot = data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.imageFailed(e)
            failed = true
        }
    }
    val data = snapshot
    if (data == null) {
        Column(Modifier.fillMaxSize().background(ledgerColors.background).systemBarsPadding()) {
            TextButton(onClick = close) { Text("关闭") }
            if (failed) {
                Text("图表暂时无法加载", color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { retry++ }) { Text("重试") }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    } else {
        FullscreenReportChartContent(data, route.type, route.lines, close)
    }
}

@Composable
internal fun FullscreenReportChartContent(
    data: ReportSnapshot,
    initialType: Int,
    initialLines: Boolean,
    close: () -> Unit,
) {
    var type by rememberSaveable { mutableIntStateOf(initialType) }
    var lines by rememberSaveable { mutableStateOf(initialLines) }
    Column(
        Modifier.fillMaxSize().background(ledgerColors.background).systemBarsPadding()
            .padding(horizontal = 20.dp).testTag("report-chart-fullscreen"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(data.period.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = { lines = !lines }) { Text(if (lines) "柱状图" else "折线图") }
            TextButton(onClick = close) { Text("关闭") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            val labels = if (data.period.yearly) listOf("支出", "收入", "结余", "全部")
                else listOf("支出", "收入", "全部")
            labels.forEachIndexed { index, label ->
                val series = index + 2
                TextButton(onClick = { type = series }) {
                    Text(label, color = if (type == series) MaterialTheme.colorScheme.primary else ledgerColors.muted)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            ReportChart(data, type, lines, (maxHeight - 52.dp).coerceAtLeast(40.dp))
        }
    }
}
