package top.fjzhang233.books

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth

class CalendarDataTest {
    private fun day(date:String,vararg values:Triple<String,String,String>)=JSONObject().put("date",date).put("amounts",JSONArray(values.map{(currency,income,expense)->JSONObject().put("currency",currency).put("incomeAmount",income).put("expenseAmount",expense)}))
    @Test fun completeDailyCnyTotalsExcludeForeignAndOtherMonths(){
        val month=YearMonth.of(2026,11)
        val snapshot=calendarAmounts(month,listOf(day("2026-11-01",Triple("CNY","1","0")),day("2026-11-14",Triple("CNY","10","6"),Triple("USD","900000","800000")),day("2026-12-01",Triple("CNY","999999","0"))))
        assertEquals(BigInteger("11"),snapshot.total.income);assertEquals(BigInteger("6"),snapshot.total.expense);assertEquals(BigInteger("5"),snapshot.total.balance)
        assertEquals(CalendarAmounts(BigInteger.TEN,BigInteger("6")),snapshot.days[LocalDate.of(2026,11,14)])
        assertFalse(snapshot.days.containsKey(LocalDate.of(2026,12,1)));assertTrue(snapshot.excludedForeign)
    }
    @Test fun emptyAndHugeMinorAmountsStayExact(){
        val empty=calendarAmounts(YearMonth.of(2024,2),emptyList());assertEquals(CalendarAmounts(),empty.total);assertFalse(empty.excludedForeign)
        val snapshot=calendarAmounts(YearMonth.of(2024,2),listOf(day("2024-02-29",Triple("CNY","9223372036854775808","1"))))
        assertEquals(BigInteger("9223372036854775807"),snapshot.total.balance)
        assertEquals("92233720368547758.08",calendarAmount(snapshot.total.income));assertEquals("0.01",calendarAmount(BigInteger.ONE));assertEquals("0.1",calendarAmount(BigInteger.TEN));assertEquals("-0.01",calendarAmount(BigInteger.ONE.negate()))
    }
    @Test fun mondaySaturdaySundayOffsetsKeepFirstAndLastDatesInTheGrid(){
        val november=YearMonth.of(2026,11)
        assertEquals(6,calendarFirstOffset(november,1));assertEquals(1,calendarFirstOffset(november,6));assertEquals(0,calendarFirstOffset(november,7))
        assertEquals(3,calendarFirstOffset(YearMonth.of(2024,2),1));assertEquals(29,YearMonth.of(2024,2).lengthOfMonth())
        assertThrows(IllegalArgumentException::class.java){calendarFirstOffset(november,2)}
    }
}
