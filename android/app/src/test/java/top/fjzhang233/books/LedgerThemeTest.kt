package top.fjzhang233.books
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class LedgerThemeTest{
    private fun luminance(c:Color):Double{
        fun v(x:Float):Double=if(x<=.04045f)x/12.92 else ((x+.055)/1.055).pow(2.4)
        return .2126*v(c.red)+.7152*v(c.green)+.0722*v(c.blue)
    }
    private fun contrast(a:Color,b:Color):Double{val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
    @Test fun everyPreferenceHasStableKeysAndUnknownPreferencesFollowSystem(){
        assertEquals(ThemeMode.SYSTEM,decodeThemeMode(null));assertEquals(ThemeMode.SYSTEM,decodeThemeMode("unsupported"));ThemeMode.entries.forEach{assertEquals(it,decodeThemeMode(it.key))}
    }
    @Test fun financialAndSecondaryTextMeetNormalTextContrastInBothThemes(){
        for(dark in listOf(false,true)){val p=ledgerPalette(dark);for(bg in listOf(p.background,p.surface,p.field)){for((name,ink)in listOf("text" to p.text,"muted" to p.muted,"expense" to p.expense,"income" to p.income,"accent" to p.accent)){assertTrue("$dark $name contrast on $bg",contrast(ink,bg)>=4.5)}}
            assertTrue(contrast(p.onAccent,p.accent)>=4.5);assertTrue(contrast(p.onAccent,p.expense)>=4.5)
        }
    }
    @Test fun selectionAndHeatColoursKeepFinancialTextLegible(){
        for(dark in listOf(false,true)){val p=ledgerPalette(dark);assertTrue(contrast(p.accent,p.selection)>=4.5);for(bg in listOf(p.heatExpenseLow,p.heatExpenseHigh))assertTrue(contrast(p.expense,bg)>=4.5);for(bg in listOf(p.heatIncomeLow,p.heatIncomeHigh))assertTrue(contrast(p.income,bg)>=4.5)}
    }
    @Test fun categoryPalettesKeepRankOrderAndReadableForegroundsAcrossThemes(){
        for(dark in listOf(false,true)){
            val theme=ledgerPalette(dark)
            val expenses=reportCategoryPalette(2,theme)
            val income=reportCategoryPalette(1,theme)
            assertEquals(11,expenses.size)
            assertEquals(13,income.size)
            assertEquals(Color(0xFFFDA93D),expenses[0].fill)
            assertEquals(Color(0xFFFC65D5),expenses[10].fill)
            assertEquals(Color(0xFF6ECCAF),income[0].fill)
            assertEquals(Color(0xFF607D8B),income[12].fill)
            for(palette in listOf(expenses,income)){
                assertEquals(palette.size,(0 until palette.size).map{palette[it].fill}.toSet().size)
                assertEquals(palette[0],palette[palette.size])
                for(rank in 0 until palette.size){
                    val style=palette[rank]
                    assertTrue("Category label, dark=$dark rank=$rank",contrast(style.label,theme.surface)>=4.5)
                    assertTrue("Category icon, dark=$dark rank=$rank",contrast(style.onFill,style.fill)>=4.5)
                }
            }
        }
    }
    @Test fun annualNetAndCombinedSeriesKeepNonTextContrastAndSignedMoney(){
        val data=ReportSnapshot(ReportPeriod(java.time.YearMonth.of(2026,11),yearly=true),listOf(ReportDay(java.time.LocalDate.of(2026,11,14),java.math.BigInteger.ONE,java.math.BigInteger.TEN)),emptyList(),false)
        for(dark in listOf(false,true)){
            val p=ledgerPalette(dark);val net=reportChartSeries(data,4,p).single();val combined=reportChartSeries(data,5,p)
            assertEquals(java.math.BigInteger.valueOf(-9),net.first.single());assertEquals(p.accent,net.second);assertEquals(net,combined.last())
            for(series in combined)for(background in listOf(p.background,p.surface))assertTrue("series paint contrast in dark=$dark",contrast(series.second,background)>=3.0)
        }
    }
}
