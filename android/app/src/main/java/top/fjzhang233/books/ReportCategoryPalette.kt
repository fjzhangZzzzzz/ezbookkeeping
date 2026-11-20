package top.fjzhang233.books

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal data class ReportCategoryColor(val fill: Color, val label: Color, val onFill: Color)

internal class ReportCategoryPalette(private val colors: List<ReportCategoryColor>) {
    val size: Int get() = colors.size
    operator fun get(rank: Int): ReportCategoryColor = colors[rank % size]
}

private val expenseFills = listOf(
    Color(0xFFFDA93D), Color(0xFFFD663F), Color(0xFF28C384), Color(0xFF92D66B),
    Color(0xFF448AFF), Color(0xFF03A9F4), Color(0xFF00BFA5), Color(0xFF80CBC4),
    Color(0xFF8C9EFF), Color(0xFFB388FF), Color(0xFFFC65D5),
)

private val incomeFills = listOf(
    Color(0xFF6ECCAF), Color(0xFF00B0FF), Color(0xFFADE792), Color(0xFFFF7043),
    Color(0xFFFFAB00), Color(0xFF0F96E8), Color(0xFF16CD62), Color(0xFFBE8C63),
    Color(0xFFFFAE6D), Color(0xFF7E57C2), Color(0xFFFF80AB), Color(0xFF8C9EFF),
    Color(0xFF607D8B),
)

@Composable
internal fun rememberReportCategoryPalette(categoryType: Int): ReportCategoryPalette {
    val theme = ledgerColors
    return remember(categoryType, theme) { reportCategoryPalette(categoryType, theme) }
}

internal fun reportCategoryPalette(categoryType: Int, theme: LedgerPalette): ReportCategoryPalette {
    val fills = if (categoryType == 2) expenseFills else incomeFills
    val labelInk = if (theme.dark) Color.White else Color.Black
    return ReportCategoryPalette(fills.map { fill ->
        val onFill = if (fill.luminance() > .179f) Color.Black else Color.White
        ReportCategoryColor(fill, reportLabelColor(fill, theme.surface, labelInk), onFill)
    })
}

private fun reportLabelColor(fill: Color, background: Color, ink: Color): Color {
    val surface = background.luminance()
    fun meetsTarget(color: Color): Boolean {
        val value = color.luminance()
        return (maxOf(surface, value) + .05f) / (minOf(surface, value) + .05f) >= 4.55f
    }
    if (meetsTarget(fill)) return fill

    var low = 0f
    var high = 1f
    repeat(10) {
        val blend = (low + high) / 2
        if (meetsTarget(lerp(fill, ink, blend))) high = blend else low = blend
    }
    return lerp(fill, ink, high)
}
