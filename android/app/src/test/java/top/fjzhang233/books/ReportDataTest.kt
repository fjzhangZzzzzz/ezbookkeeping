package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.YearMonth

class ReportDataTest {
    @Test fun familyGroupingIncludesPriorOnlyAndDirectEntriesAndKeepsOffsettingDays() {
        val payload = JSONObject("""{"categories":[
            {"id":"1","type":2,"name":"日常","amountMinor":"10","previousAmountMinor":"9"},
            {"id":"2","type":2,"name":"水费","parent":{"id":"1","name":"日常"},"amountMinor":"15","previousAmountMinor":"4"}],
            "priorOnlyCategories":[{"id":"3","type":2,"parent":{"id":"1","name":"日常"},"previousAmountMinor":"20"}],
            "days":[{"date":"2024-02-01","expenseMinor":"0","incomeMinor":"0"}]}""")
        val report = familyReportSnapshot(payload, ReportPeriod(YearMonth.of(2024, 2)))
        val parent = report.parentRanking(2, report.familyCatalog).single()
        assertEquals(BigInteger("25"), parent.amount)
        assertEquals(BigInteger("33"), parent.previous)
        assertEquals(listOf("2", "1"), report.familyCategoryParts("1", 2).map { it.category.id() })
        assertEquals(listOf("水费", "直接记入"), report.familyCategoryParts("1", 2).map { it.category.optString("name") })
        assertEquals("日常", parent.category.optString("name"))
        assertEquals(1, report.days.count { it.active })
    }

    private val accounts=listOf(JSONObject("{\"id\":\"1\",\"currency\":\"CNY\"}"),JSONObject("{\"id\":\"2\",\"currency\":\"USD\"}"))
    private val categories=listOf(JSONObject("{\"id\":\"10\",\"type\":2}"),JSONObject("{\"id\":\"11\",\"type\":1}"),JSONObject("{\"id\":\"12\",\"type\":3}"))
    @Test fun periodUsesShanghaiBoundariesAndLeapYear(){
        val month=ReportPeriod(YearMonth.of(2024,2));assertEquals(29,month.days)
        assertEquals(BooksApi.monthBounds("2024-02"),month.bounds)
        val year=month.copy(yearly=true);assertEquals(366,year.days)
        assertEquals(BooksApi.dayBounds("2024-01-01").first,year.bounds.first)
        assertEquals(BooksApi.dayBounds("2024-12-31").second,year.bounds.second)
        assertEquals(2023,year.shift(-1).month.year)
    }
    @Test fun currenciesTransferAndNegativeAmountsAreNeverMixed(){
        val items=listOf(JSONObject("{\"accountId\":\"1\",\"categoryId\":\"10\",\"amount\":\"9007199254740993\"}"),JSONObject("{\"accountId\":\"1\",\"categoryId\":\"10\",\"amount\":\"-2\"}"),JSONObject("{\"accountId\":\"2\",\"categoryId\":\"10\",\"amount\":\"9999\"}"),JSONObject("{\"accountId\":\"1\",\"categoryId\":\"12\",\"relatedAccountId\":\"2\",\"amount\":\"500\"}"))
        val daily=listOf(JSONObject("{\"date\":\"2024-02-01\",\"amounts\":[{\"currency\":\"CNY\",\"incomeAmount\":\"10\",\"expenseAmount\":\"6\"},{\"currency\":\"USD\",\"incomeAmount\":\"100\",\"expenseAmount\":\"9999\"}]}"))
        val data=reportSnapshot(ReportPeriod(YearMonth.of(2024,2)),daily,items,listOf(JSONObject("{\"accountId\":\"1\",\"categoryId\":\"10\",\"amount\":\"1\"}")),accounts,categories)
        assertTrue(data.excludedForeign);assertEquals(BigInteger("6"),data.expense);assertEquals(BigInteger("10"),data.income)
        assertEquals(1,data.categories.size);assertEquals(BigInteger("9007199254740991"),data.ranking(2).single().amount);assertEquals(BigInteger.ONE,data.ranking(2).single().previous)
        assertEquals(29,data.days.size);assertEquals("0.00",reportAverage(data.expense,29));assertEquals("0.03",reportAverage(data.expense,2))
    }
    @Test fun serverStatisticsAreCompleteAndDetailsAreServerPaged()=runBlocking{
        val server=MockWebServer();server.start()
        try{
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
                return when(request.requestUrl!!.encodedPath){
                    "/api/v1/transactions/amounts/daily.json"->MockResponse().setBody("{\"success\":true,\"result\":[]}")
                    "/api/v1/transactions/statistics.json"->MockResponse().setBody("{\"success\":true,\"result\":{\"items\":[]}}")
                    "/api/v1/transactions/list.json"->MockResponse().setBody("{\"success\":true,\"result\":{\"items\":[{\"id\":\"100\"}],\"totalCount\":61}}")
                    else->MockResponse().setResponseCode(404)
                }
            }}
            val api=BooksApi("http://127.0.0.1:${server.port}"){"synthetic"}
            val p=ReportPeriod(YearMonth.of(2024,2));api.report(p,accounts,categories)
            val reportRequests=List(3){server.takeRequest().requestUrl!!};val daily=reportRequests.single{it.encodedPath.endsWith("amounts/daily.json")};assertEquals("false",daily.queryParameter("use_transaction_timezone"));val stats=reportRequests.single{it.encodedPath.endsWith("statistics.json")&&it.queryParameter("start_time")==p.bounds.first.toString()};assertEquals("false",stats.queryParameter("use_transaction_timezone"));assertEquals(p.bounds.first.toString(),stats.queryParameter("start_time"))
            val before=reportRequests.single{it.encodedPath.endsWith("statistics.json")&&it.queryParameter("start_time")==p.shift(-1).bounds.first.toString()};assertEquals(p.shift(-1).bounds.first.toString(),before.queryParameter("start_time"))
            val(rows,total)=api.reportCategoryPage(p,"10",accounts,2);assertEquals(1,rows.size);assertEquals(61L,total)
            val detail=server.takeRequest().requestUrl!!;assertEquals("true",detail.queryParameter("with_pictures"));assertEquals("2",detail.queryParameter("page"));assertEquals("1",detail.queryParameter("account_ids"));assertEquals("10",detail.queryParameter("category_ids"));assertEquals((p.bounds.first*1000).toString(),detail.queryParameter("min_time"));assertEquals((p.bounds.second*1000+999).toString(),detail.queryParameter("max_time"))
        }finally{server.shutdown()}
    }
    @Test fun filtersStayConsistentAcrossDailyStatisticsAndDetails()=runBlocking{
        val server=MockWebServer();server.start()
        try{
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
                return when(request.requestUrl!!.encodedPath){
                    "/api/v1/transactions/amounts/daily.json"->MockResponse().setBody("{\"success\":true,\"result\":[]}")
                    "/api/v1/transactions/statistics.json"->MockResponse().setBody("{\"success\":true,\"result\":{\"items\":[{\"accountId\":\"1\",\"categoryId\":\"10\",\"amount\":\"6\"},{\"accountId\":\"1\",\"categoryId\":\"11\",\"amount\":\"10\"}]}}")
                    "/api/v1/transactions/list.json"->MockResponse().setBody("{\"success\":true,\"result\":{\"items\":[],\"totalCount\":0}}")
                    else->MockResponse().setResponseCode(404)
                }
            }}
            val api=BooksApi("http://127.0.0.1:${server.port}"){"synthetic"};val period=ReportPeriod(YearMonth.of(2024,2));val filter=ReportFilter(setOf("1"),setOf("10"),setOf("21","20"),true)
            val result=api.report(period,accounts,categories,filter);assertEquals(listOf("10"),result.categories.map{it.category.id()})
            val reportRequests=List(3){server.takeRequest().requestUrl!!};val daily=reportRequests.single{it.encodedPath.endsWith("amounts/daily.json")};assertEquals("2",daily.queryParameter("exclude_account_ids"));assertEquals(setOf("11","12"),daily.queryParameter("exclude_category_ids")!!.split(",").toSet());assertEquals("1:20,21",daily.queryParameter("tag_filter"))
            reportRequests.filterNot{it==daily}.forEach{assertEquals("1:20,21",it.queryParameter("tag_filter"))}
            api.reportCategoryPage(period,"10",accounts,1,filter)
            val detail=server.takeRequest().requestUrl!!;assertEquals("true",detail.queryParameter("with_pictures"));assertEquals("1:20,21",detail.queryParameter("tag_filter"));assertEquals("1",detail.queryParameter("account_ids"));assertEquals("10",detail.queryParameter("category_ids"))
        }finally{server.shutdown()}
    }
    @Test fun customPeriodHasInclusiveEndAndNonOverlappingPreviousRange(){
        val p=ReportPeriod(YearMonth.of(2024,2),false,java.time.LocalDate.parse("2024-02-28"),java.time.LocalDate.parse("2024-03-01"))
        assertEquals(3,p.days);assertEquals(BooksApi.dayBounds("2024-03-01").second,p.bounds.second)
        assertEquals(p.first,p.shift(-1).until)
        assertThrows(IllegalArgumentException::class.java){ReportPeriod(p.month,false,p.until,p.first)}
    }
    @Test fun reportRouteArgumentsRepresentTheOriginalPeriodAndFilter() {
        val period = ReportPeriod(YearMonth.of(2024, 2), false, java.time.LocalDate.parse("2024-02-28"), java.time.LocalDate.parse("2024-03-01"))
        val filter = ReportFilter(setOf("1"), setOf("10"), setOf("20", "21"), allTags = true)
        val query = ReportQuery(period, filter)
        assertEquals(period, query.period())
        assertEquals(filter, query.filter())
    }
    @Test fun parentRankingIncludesPriorOnlyChildrenAndItsDirectEntries() {
        val catalog = listOf(
            JSONObject("""{"id":"30","name":"饮食","type":2}"""),
            JSONObject("""{"id":"31","parentId":"30","type":2}"""),
            JSONObject("""{"id":"32","parentId":"30","type":2}"""),
            JSONObject("""{"id":"33","parentId":"30","type":2}"""),
            JSONObject("""{"id":"40","type":2}"""),
        )
        fun amount(id:String, value:Int, account:String="1")=
            JSONObject().put("accountId",account).put("categoryId",id).put("amount",value.toString())
        val period=ReportPeriod(YearMonth.of(2026,11))
        val current=listOf(amount("30",100),amount("31",650),amount("31",-50),amount("32",200),amount("31",99,"2"))
        val prior=listOf(amount("31",700),amount("33",50),amount("40",300))
        val parent=reportCategoryScope("30",catalog,ReportCategoryScope.All)
        assertEquals(ReportCategoryScope.Only(setOf("30","31","32","33")),parent)
        assertEquals(ReportCategoryScope.Only(setOf("32")),reportCategoryScope("30",catalog,ReportCategoryScope.Only(setOf("32","40"))))
        assertEquals(ReportCategoryScope.Empty,reportCategoryScope("30",catalog,ReportCategoryScope.Only(setOf("40"))))
        val report=reportSnapshot(period,emptyList(),current,prior,accounts,catalog)
        val rank=report.parentRanking(2,catalog).single()
        assertEquals("30",rank.category.id())
        assertEquals(BigInteger("900"),rank.amount)
        assertEquals(BigInteger("750"),rank.previous)
        assertEquals(listOf("31","32","30"),report.ranking(2).map{it.category.id()})
        assertEquals(BigInteger.ZERO,report.ranking(2)[1].previous)
        assertEquals(ReportCategoryScope.Only(setOf("32")),report.detailCategoryScope("32"))
        assertEquals(ReportCategoryScope.Empty,report.detailCategoryScope("33"))
        val empty=reportSnapshot(period,listOf(JSONObject().put("date",period.first.toString()).put("amounts",org.json.JSONArray().put(
            JSONObject().put("currency","CNY").put("expenseAmount","900")))),current,prior,accounts,catalog,scope=ReportCategoryScope.Empty)
        assertEquals(BigInteger.ZERO,empty.expense)
        assertTrue(empty.categories.isEmpty())
    }
    @Test fun legacyParentDetailsUseTheExactScopeAndEmptyScopeMakesNoRequests() = runBlocking {
        MockWebServer().use { server ->
            val api=BooksApi("http://127.0.0.1:${server.port}"){"synthetic"}
            val period=ReportPeriod(YearMonth.of(2026,11))
            api.report(period,accounts,categories,scope=ReportCategoryScope.Empty)
            assertEquals(0L,api.reportCategoryPage(period,ReportCategoryScope.Empty,accounts,1,ReportFilter()).second)
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("""{"success":true,"result":{"totalCount":4,"items":[
                {"id":"101","type":3,"categoryId":"30"},{"id":"102","type":3,"categoryId":"31"},
                {"id":"103","type":3,"categoryId":"32"},{"id":"104","type":4,"categoryId":"30"}]}}"""))
            val (rows,total)=api.reportCategoryPage(period,ReportCategoryScope.Only(setOf("30","31")),accounts,1,
                ReportFilter(setOf("1"),tagIds=setOf("20")),setOf("30"))
            assertEquals(listOf("101","102"),rows.map{it.id()})
            assertEquals(2L,total)
            val query=server.takeRequest().requestUrl!!
            assertNull(query.queryParameter("category_ids"))
            assertEquals("1",query.queryParameter("account_ids"))
            assertEquals("0:20",query.queryParameter("tag_filter"))
        }
    }
    @Test fun billStatisticsUsesShanghaiYearAndKeepsReportOrigin() {
        val time=java.time.Instant.parse("2025-12-31T16:05:00Z").epochSecond
        val bill=JSONObject().put("type",3).put("time",time).put("categoryId","10")
        val route=transactionCategoryStatistics(bill,categories,null)!!
        assertEquals("10",route.categoryId)
        assertEquals(ReportPeriod(YearMonth.of(2026,1),yearly=true),route.query.period())
        val query=ReportQuery(ReportPeriod(YearMonth.of(2025,12)),ReportFilter(setOf("1"),tagIds=setOf("20")))
        assertEquals(query,transactionCategoryStatistics(bill,categories,query)?.query)
        assertNull(transactionCategoryStatistics(JSONObject().put("type",4).put("categoryId","10"),categories,null))
        assertNull(transactionCategoryStatistics(bill.put("categoryId","0"),categories,null))
    }
    @Test fun monthlyMeanIncludesIncomeOnlyMonthsAndUsesSymmetricDecimalHalfEven(){
        val period=ReportPeriod(YearMonth.of(2025,11),true)
        val data=reportSnapshot(period,listOf(
            JSONObject("{\"date\":\"2025-01-01\",\"amounts\":[{\"currency\":\"CNY\",\"incomeAmount\":\"0\",\"expenseAmount\":\"25\"}]}"),
            JSONObject("{\"date\":\"2025-02-01\",\"amounts\":[{\"currency\":\"CNY\",\"incomeAmount\":\"0\",\"expenseAmount\":\"100\"}]}"),
            JSONObject("{\"date\":\"2025-03-01\",\"amounts\":[{\"currency\":\"CNY\",\"incomeAmount\":\"1\",\"expenseAmount\":\"0\"}]}")),emptyList(),emptyList(),accounts,categories)
        assertEquals(3,data.activeMonths);assertEquals(listOf("3月","2月","1月"),reportTableDays(data).map{it.first})
        assertEquals("0.38",reportAverage(BigInteger("75"),2));assertEquals("0.62",reportAverage(BigInteger("125"),2));assertEquals("-0.62",reportAverage(BigInteger("-125"),2))
    }
    @Test fun familyReportPayloadUsesPersonalReportShapeAndExactMoney(){
        val payload=JSONObject("""
            {"hasExcludedForeignCurrency":true,"categories":[
              {"id":"10","name":"餐饮","type":2,"icon":"1","amountMinor":"125","previousAmountMinor":"100"},
              {"id":"11","name":"工资","type":1,"icon":"1","amountMinor":"900","previousAmountMinor":"800"}],
             "days":[{"date":"2024-02-01","incomeMinor":"900","expenseMinor":"125"}]}
        """.trimIndent())
        val data=familyReportSnapshot(payload,ReportPeriod(YearMonth.of(2024,2)))
        assertTrue(data.excludedForeign);assertEquals(BigInteger("900"),data.income);assertEquals(BigInteger("125"),data.expense)
        assertEquals(BigInteger("125"),data.ranking(2).single().amount);assertEquals(BigInteger("100"),data.ranking(2).single().previous);assertEquals(29,data.days.size)
        val yearly=familyReportSnapshot(payload,ReportPeriod(YearMonth.of(2024,2),true));assertEquals(366,yearly.days.size);assertEquals(BigInteger("900"),yearly.income)
    }
    @Test fun axisTicksStayExactMonotonicAndCoverHugeAmounts(){
        for(top in listOf(BigInteger.ONE,BigInteger.valueOf(6),BigInteger("9007199254740993"))){
            val ticks=reportAxisTicks(top);assertEquals(BigInteger.ZERO,ticks.first());assertTrue(ticks.last()>=top);assertEquals(ticks.sorted().distinct(),ticks);assertTrue(ticks.size<=7)
        }
        assertEquals(listOf(0L,2L,4L,6L,8L,10L).map{BigInteger.valueOf(it)},reportAxisTicks(BigInteger.TEN))
        assertEquals(reportAxisTicks(BigInteger.valueOf(6)),reportAxisTicks(BigInteger.valueOf(-6)))
    }
}
