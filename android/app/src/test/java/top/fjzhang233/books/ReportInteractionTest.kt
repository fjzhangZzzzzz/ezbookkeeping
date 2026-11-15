package top.fjzhang233.books
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
class ReportInteractionTest {
 @Test fun monthlyChartAggregationAndTooltipNeverRoundMoneyThroughCoordinates(){
  val period=ReportPeriod(YearMonth.of(2026,1),true)
  val data=ReportSnapshot(period,listOf(ReportDay(LocalDate.parse("2026-01-01"),BigInteger("9007199254740993"),BigInteger("125")),ReportDay(LocalDate.parse("2026-01-02"),BigInteger("-2"),BigInteger("-5")),ReportDay(LocalDate.parse("2026-02-01"),BigInteger.ZERO,BigInteger.ZERO,true)),emptyList(),false)
  val rows=reportChartPoints(data);assertEquals(2,rows.size);assertEquals(BigInteger("9007199254740991"),rows[0].income);assertEquals(BigInteger("120"),rows[0].expense);assertTrue(rows[1].active)
  assertEquals(listOf("2026-01","收入: 90071992547409.91","支出: 1.20","结余: 90071992547408.71"),reportPointDescription(data,rows[0]))
 }
 @Test fun selectingDateUsesOnlyPlotIndexAndClampsRightEdge(){
  assertNull(reportPointIndex(-1f,0f,300f,30));assertNull(reportPointIndex(Float.NaN,0f,300f,30));assertNull(reportPointIndex(10f,0f,300f,0))
  assertEquals(0,reportPointIndex(0f,0f,300f,30));assertEquals(13,reportPointIndex(135f,0f,300f,30));assertEquals(29,reportPointIndex(300f,0f,300f,30))
 }
 @Test fun negativeTooltipAndCrossYearDayRemainUnambiguous(){
  val p=ReportPeriod(YearMonth.of(2025,12),false,LocalDate.parse("2025-12-31"),LocalDate.parse("2026-01-01"));val row=ReportDay(p.first,BigInteger("-25"),BigInteger("125"));val data=ReportSnapshot(p,listOf(row),emptyList(),false)
  assertEquals(listOf("2025-12-31","收入: -0.25","支出: 1.25","结余: -1.50"),reportPointDescription(data,row))
 }
 @Test fun donutSelectionHandlesWrapAndRotationWithoutChangingAmounts(){
  val values=listOf(BigInteger("5"),BigInteger.ONE);assertEquals(0,reportDonutIndex(values,180f));assertEquals(1,reportDonutIndex(values,330f));assertEquals(0,reportDonutIndex(values,360f));assertEquals(1,reportDonutIndex(values,-30f))
  assertNull(reportDonutIndex(listOf(BigInteger.ZERO),1f));assertEquals(2f,reportRotationDelta(179f,-179f),0f);assertEquals(-2f,reportRotationDelta(-179f,179f),0f);assertEquals(350f,reportNormalizedAngle(-10f),0f)
 }
 @Test fun dailyDrilldownKeepsEveryFilterCurrencyBoundaryAndPictureMetadata()=runBlocking{
  val server=MockWebServer();server.start()
  try{
   server.enqueue(MockResponse().setBody("""{"success":true,"result":{"items":[],"totalCount":31}}"""))
   val accounts=listOf(JSONObject("""{"id":"1","currency":"CNY"}"""),JSONObject("""{"id":"2","currency":"USD"}"""))
   val categories=listOf(JSONObject("""{"id":"10","type":1,"parentId":"9"}"""),JSONObject("""{"id":"20","type":2,"parentId":"19"}"""),JSONObject("""{"id":"30","type":3,"parentId":"29"}"""))
   val api=BooksApi(server.url("/").toString()){null};val date=LocalDate.parse("2024-02-29")
   val(_,count)=api.reportDayPage(date,accounts,categories,2,ReportFilter(setOf("1","2"),setOf("20"),setOf("8"),true));assertEquals(31L,count)
   val query=server.takeRequest().requestUrl!!;assertEquals("2",query.queryParameter("page"));assertEquals("1",query.queryParameter("account_ids"));assertEquals("20",query.queryParameter("category_ids"));assertEquals("1:8",query.queryParameter("tag_filter"));assertEquals("true",query.queryParameter("with_pictures"));val bounds=BooksApi.dayBounds(date.toString());assertEquals((bounds.first*1000).toString(),query.queryParameter("min_time"));assertEquals((bounds.second*1000+999).toString(),query.queryParameter("max_time"))
   assertEquals(0L,api.reportDayPage(date,accounts,categories,1,ReportFilter(setOf("2"))).second);assertEquals(1,server.requestCount)
  }finally{server.shutdown()}
 }
}
