package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.time.Instant

class SearchDataTest {
    private fun row(id:Int,type:Int=3,minor:String="1",currency:String="CNY")=JSONObject().put("id",id.toString()).put("type",type).put("sourceAmount",minor).put("sourceAccount",JSONObject().put("currency",currency)).put("time",Instant.parse("2026-11-14T16:01:00Z").epochSecond)
    @Test fun shanghaiInclusiveBoundsAndEncodedKeywords() {
        val query=LedgerSearchFilter(keyword="午饭&分类=1 +图",firstDate="2026-11-15",lastDate="2026-11-15",accountIds=setOf("12","3"),tagIds=setOf("8"),tagMode=1,picturesOnly=true).query(2)
        val values=query.split('&').associate{val pair=it.split('=',limit=2);pair[0] to URLDecoder.decode(pair[1],"UTF-8")}
        assertEquals("午饭&分类=1 +图",values["keyword"])
        assertEquals(Instant.parse("2026-11-14T16:00:00Z").toEpochMilli().toString(),values["min_time"])
        assertEquals((Instant.parse("2026-11-15T16:00:00Z").toEpochMilli()-1).toString(),values["max_time"])
        assertEquals("1:8",values["tag_filter"]);assertEquals("true",values["must_have_pictures"]);assertEquals("2",values["page"])
        assertEquals("2026-11-15",searchRowDay(row(1)).toString())
    }
    @Test fun exactMinorUnitsAndInvalidCriteriaFailBeforeNetworking() {
        assertEquals(1234567890123L,searchMinor("12345678901.23"))
        assertEquals(-1L,searchMinor("-0.01"));assertEquals(0L,searchMinor("0"))
        for(value in listOf("NaN","1e2","1.001","1,2"))assertThrows(IllegalArgumentException::class.java){searchMinor(value)}
        assertThrows(ArithmeticException::class.java){searchMinor("92233720368547758.08")}
        assertThrows(IllegalArgumentException::class.java){LedgerSearchFilter(firstDate="2026-12-01",lastDate="2026-11-01").query(1)}
        assertThrows(IllegalArgumentException::class.java){LedgerSearchFilter(amountMode="bt",amount1="2",amount2="1").query(1)}
        assertThrows(IllegalArgumentException::class.java){LedgerSearchFilter(accountIds=setOf("1&keyword=x")).query(1)}
    }
    @Test fun criteriaRoundTripKeepsEverySupportedFilter() {
        val original=LedgerSearchFilter("a中",3,"2026-01-01","2026-12-31",setOf("1"),setOf("2"),setOf("3"),1,"bt","0.01","20.34",true,false)
        assertEquals(original,LedgerSearchFilter.fromJson(original.toJson()))
        assertEquals(LedgerSearchFilter(),LedgerSearchFilter.fromJson(LedgerSearchFilter().toJson()))
    }
    @Test fun summaryIncludesLaterPagesAndExcludesForeignAndNonFlowTypes()=runBlocking {
        val calls=mutableListOf<Int>()
        val first=(1..30).map{row(it)}
        val last=listOf(row(31,2,"100").put("sourceAmount",100L),row(32,3,"90","USD"),row(33,4,"999"),row(34,1,"999"))
        val snapshot=completeSearch{page->calls+=page;LedgerSearchResult(if(page==1)first else last,34)}
        assertEquals(listOf(1,2),calls);assertEquals(34,snapshot.items.size)
        assertEquals("100",snapshot.amounts.income.toString());assertEquals("30",snapshot.amounts.expense.toString());assertEquals("70",snapshot.amounts.balance.toString());assertTrue(snapshot.excludedForeign)
    }
    @Test fun incompleteChangingAndExcessiveResultsNeverProduceSummary()=runBlocking {
        assertTrue(runCatching{completeSearch{LedgerSearchResult(emptyList(),20_001)}}.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching{completeSearch{page->LedgerSearchResult(if(page==1)(1..30).map{row(it)}else emptyList(),31)}}.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching{completeSearch{page->LedgerSearchResult(if(page==1)(1..30).map{row(it)}else listOf(row(1)),31)}}.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching{completeSearch{page->LedgerSearchResult(if(page==1)(1..30).map{row(it)}else listOf(row(31)),if(page==1)31 else 32)}}.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0,completeSearch{LedgerSearchResult(emptyList(),0)}.items.size)
    }
    @Test fun calendarYearPresetsRollForwardAndKeepLeapBoundaries(){
        val current=searchDatePresets(java.time.LocalDate.of(2026,11,15)).single{it.label=="2025~2026"}
        assertEquals("2025-01-01",current.firstDate);assertEquals("2026-12-31",current.lastDate)
        val next=searchDatePresets(java.time.LocalDate.of(2027,1,1)).single{it.label=="2026~2027"}
        assertEquals("2026-01-01",next.firstDate);assertEquals("2027-12-31",next.lastDate)
        val leap=searchDatePresets(java.time.LocalDate.of(2028,3,1)).single{it.label=="上月"}
        assertEquals("2028-02-01",leap.firstDate);assertEquals("2028-02-29",leap.lastDate)
        val all=searchDatePresets(java.time.LocalDate.of(2026,11,15)).last();assertEquals("全部",all.label);assertNull(all.firstDate);assertNull(all.lastDate)
    }
}
