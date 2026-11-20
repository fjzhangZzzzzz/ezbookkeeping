package top.fjzhang233.books

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

@Serializable
internal enum class AppPage : NavKey {
    LEDGER, SEARCH, FAMILY, SETTINGS, ACCOUNTS, CATALOG, SESSIONS, EXTERNAL_AUTH, ABOUT,
    CALENDAR, REPORT,
}

@Serializable
internal data object EntryPage : NavKey

@Serializable
internal data class EntryCatalogPage(val section: Int) : NavKey

@Serializable
internal data class ReportQuery(
    val month: String,
    val yearly: Boolean,
    val first: String?,
    val last: String?,
    val filter: String,
) {
    constructor(period: ReportPeriod, filter: ReportFilter = ReportFilter()) : this(
        period.month.toString(),
        period.yearly,
        period.customFirst?.toString(),
        period.customLast?.toString(),
        encodeReportFilter(filter),
    )

    fun period() = ReportPeriod(YearMonth.parse(month), yearly, first?.let(LocalDate::parse), last?.let(LocalDate::parse))
    fun filter() = decodeReportFilter(filter)

    companion object {
        val saver = listSaver<ReportQuery, Any>(
            save = { listOf(it.month, it.yearly, it.first.orEmpty(), it.last.orEmpty(), it.filter) },
            restore = {
                ReportQuery(it[0] as String, it[1] as Boolean, (it[2] as String).ifEmpty { null },
                    (it[3] as String).ifEmpty { null }, it[4] as String)
            },
        )
    }
}

@Serializable
internal data class CategoryStatisticsPage(
    val query: ReportQuery,
    val categoryId: String,
    val categoryName: String,
    val categoryType: Int,
) : NavKey

/** The key stays small enough to save across rotation or process death. */
@Serializable
internal data class FullscreenChartPage(
    val query: ReportQuery,
    val type: Int,
    val lines: Boolean,
    val memberId: String? = null,
) : NavKey

internal val fullScreenDialog = DialogSceneStrategy.dialog(
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
)
