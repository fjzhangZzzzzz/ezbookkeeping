package top.fjzhang233.books

import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal data class ReportPeriod(val month:YearMonth,val yearly:Boolean=false,val customFirst:LocalDate?=null,val customLast:LocalDate?=null) {
    init {require((customFirst==null)==(customLast==null));if(customFirst!=null){require(!customLast!!.isBefore(customFirst));require(java.time.temporal.ChronoUnit.DAYS.between(customFirst,customLast)<3660){"自定义周期最多十年"}}}
    val custom:Boolean get()=customFirst!=null
    val first:LocalDate get()=customFirst?:if(yearly)LocalDate.of(month.year,1,1) else month.atDay(1)
    val until:LocalDate get()=customLast?.plusDays(1)?:if(yearly)first.plusYears(1) else first.plusMonths(1)
    val bounds:Pair<Long,Long> get(){val zone=ZoneId.of("Asia/Shanghai");return first.atStartOfDay(zone).toEpochSecond() to until.atStartOfDay(zone).toEpochSecond()-1}
    val days:Int get()=java.time.temporal.ChronoUnit.DAYS.between(first,until).toInt()
    fun shift(amount:Long)=if(custom)copy(customFirst=first.plusDays(days*amount),customLast=until.minusDays(1).plusDays(days*amount))else copy(month=if(yearly)month.plusYears(amount) else month.plusMonths(amount))
    val label:String get()=if(custom)"$first — ${until.minusDays(1)}" else if(yearly)"${month.year}年" else month.toString()
}
internal data class ReportFilter(val accountIds:Set<String> = emptySet(),val categoryIds:Set<String> = emptySet(),val tagIds:Set<String> = emptySet(),val allTags:Boolean=false) {
    val tagQuery:String get()=if(tagIds.isEmpty())"" else java.net.URLEncoder.encode((if(allTags)"1:" else "0:")+tagIds.sorted().joinToString(","),"UTF-8")
    fun acceptsAccount(id:String)=accountIds.isEmpty()||id in accountIds
    fun acceptsCategory(id:String)=categoryIds.isEmpty()||id in categoryIds
}
internal data class ReportDay(val date:LocalDate,val income:BigInteger,val expense:BigInteger,val active:Boolean=income!=BigInteger.ZERO||expense!=BigInteger.ZERO)
internal data class ReportCategory(val category:JSONObject,val amount:BigInteger,val previous:BigInteger)
internal data class ReportSnapshot(val period:ReportPeriod,val days:List<ReportDay>,val categories:List<ReportCategory>,val excludedForeign:Boolean) {
    val activeMonths:Int get()=days.filter{it.active}.map{YearMonth.from(it.date)}.distinct().size
    val income:BigInteger get()=days.fold(BigInteger.ZERO){sum,row->sum+row.income}
    val expense:BigInteger get()=days.fold(BigInteger.ZERO){sum,row->sum+row.expense}
    fun ranking(type:Int)=categories.filter{it.category.optInt("type")==type}.sortedByDescending{it.amount}
}
internal fun reportSnapshot(period:ReportPeriod,daily:List<JSONObject>,items:List<JSONObject>,previous:List<JSONObject>,accounts:List<JSONObject>,categories:List<JSONObject>,filter:ReportFilter=ReportFilter()):ReportSnapshot {
    val accountById=accounts.associateBy{it.id()};val categoryById=categories.associateBy{it.id()}
    var foreign=false
    fun aggregate(rows:List<JSONObject>):Map<String,BigInteger>{val result=mutableMapOf<String,BigInteger>()
        for(row in rows){if(row.has("relatedAccountId"))continue;if(!filter.acceptsAccount(row.id("accountId"))||!filter.acceptsCategory(row.id("categoryId")))continue;val account=accountById[row.id("accountId")]?:error("账户统计资料不完整，请刷新后重试")
            if(account.optString("currency")!="CNY"){foreign=true;continue}
            val category=categoryById[row.id("categoryId")]?:error("分类统计资料不完整，请刷新后重试")
            if(category.optInt("type") !in 1..2 || row.has("relatedAccountId"))continue
            val id=category.id();result[id]=(result[id]?:BigInteger.ZERO)+row.getString("amount").toBigInteger()
        };return result}
    val current=aggregate(items);val before=aggregate(previous)
    val byDate=daily.associateBy{LocalDate.parse(it.getString("date"))}
    val days=(0 until period.days).map{offset->val date=period.first.plusDays(offset.toLong());val amounts=byDate[date]?.array("amounts").orEmpty()
        if(amounts.any{it.optString("currency")!="CNY"})foreign=true
        val cny=amounts.firstOrNull{it.optString("currency")=="CNY"}
        ReportDay(date,cny?.optString("incomeAmount","0")?.toBigInteger()?:BigInteger.ZERO,cny?.optString("expenseAmount","0")?.toBigInteger()?:BigInteger.ZERO,cny!=null)}
    return ReportSnapshot(period,days,current.map{(id,value)->ReportCategory(categoryById.getValue(id),value,before[id]?:BigInteger.ZERO)},foreign)
}
internal suspend fun BooksApi.report(period:ReportPeriod,accounts:List<JSONObject>,categories:List<JSONObject>,filter:ReportFilter=ReportFilter()):ReportSnapshot {
    val tag=if(filter.tagIds.isEmpty())"" else "&tag_filter=${filter.tagQuery}"
    suspend fun stats(p:ReportPeriod):List<JSONObject>{val(a,b)=p.bounds;return (call("/api/v1/transactions/statistics.json?start_time=$a&end_time=$b&use_transaction_timezone=false$tag") as JSONObject).array("items")}
    val excludedAccounts=accounts.filterNot{filter.acceptsAccount(it.id())}.joinToString(","){it.id()}
    val excludedCategories=categories.filterNot{filter.acceptsCategory(it.id())}.joinToString(","){it.id()}
    val (start,end)=period.bounds
    val(daily,current,previous)=coroutineScope {
        val dailyRequest=async { (call("/api/v1/transactions/amounts/daily.json?start_time=$start&end_time=$end&use_transaction_timezone=false&exclude_account_ids=$excludedAccounts&exclude_category_ids=$excludedCategories$tag") as org.json.JSONArray).objects() }
        val currentRequest=async { stats(period) }
        val previousRequest=async { stats(period.shift(-1)) }
        Triple(dailyRequest.await(),currentRequest.await(),previousRequest.await())
    }
    return reportSnapshot(period,daily,current,previous,accounts,categories,filter)
}
internal suspend fun BooksApi.reportCategoryPage(period:ReportPeriod,categoryId:String,accounts:List<JSONObject>,page:Int,filter:ReportFilter=ReportFilter()):Pair<List<JSONObject>,Long>{
    require(Regex("[1-9][0-9]*").matches(categoryId))
    return reportTransactionPage(period,accounts,page,filter,setOf(categoryId))
}

internal fun familyReportSnapshot(payload:JSONObject, period:ReportPeriod):ReportSnapshot {
    val categories = payload.array("categories").map { row ->
        JSONObject().put("id", row.id()).put("name", row.optString("name").ifBlank { "未分类" }).put("type", row.optInt("type")).put("icon", row.optString("icon", "1")).put("iconType", row.optInt("iconType")).put("color", row.optString("color"))
    }
    val categoryById = categories.associateBy { it.id() }
    val previous = payload.array("categories").associate { row -> row.id() to row.optString("previousAmountMinor", "0").toBigInteger() }
    val dayRows = payload.array("days").associateBy { java.time.LocalDate.parse(it.getString("date")) }
    val days = (0 until period.days).map { offset ->
        val date = period.first.plusDays(offset.toLong())
        val row = dayRows[date]
        ReportDay(date, row?.optString("incomeMinor", "0")?.toBigInteger() ?: BigInteger.ZERO, row?.optString("expenseMinor", "0")?.toBigInteger() ?: BigInteger.ZERO)
    }
    return ReportSnapshot(period, days, payload.array("categories").mapNotNull { row ->
        val category = categoryById[row.id()] ?: return@mapNotNull null
        ReportCategory(category, row.optString("amountMinor", "0").toBigInteger(), previous[row.id()] ?: BigInteger.ZERO)
    }, payload.optBoolean("hasExcludedForeignCurrency"))
}
/** Same CNY/Shanghai/account/category/tag scope as the clicked daily summary, with full server count. */
internal suspend fun BooksApi.reportDayPage(date:LocalDate,accounts:List<JSONObject>,categories:List<JSONObject>,page:Int,filter:ReportFilter=ReportFilter()):Pair<List<JSONObject>,Long>{
    val flowCategories=categories.filter{it.optInt("type") in 1..2&&it.id("parentId")!="0"&&filter.acceptsCategory(it.id())}.map{it.id()}.toSet()
    if(flowCategories.isEmpty())return emptyList<JSONObject>() to 0L
    return reportTransactionPage(ReportPeriod(YearMonth.from(date),false,date,date),accounts,page,filter,flowCategories)
}
private suspend fun BooksApi.reportTransactionPage(period:ReportPeriod,accounts:List<JSONObject>,page:Int,filter:ReportFilter,categoryIds:Set<String>):Pair<List<JSONObject>,Long>{
    require(page>0);require(categoryIds.isNotEmpty()&&categoryIds.all{Regex("[1-9][0-9]*").matches(it)})
    val cny=accounts.filter{it.optString("currency")=="CNY"&&filter.acceptsAccount(it.id())}.joinToString(","){it.id()}
    if(cny.isEmpty())return emptyList<JSONObject>() to 0L
    val(start,end)=period.bounds
    val tag=if(filter.tagIds.isEmpty())"" else "&tag_filter=${filter.tagQuery}"
    val result=call("/api/v1/transactions/list.json?count=30&page=$page&with_count=true&with_pictures=true&category_ids=${categoryIds.sorted().joinToString(",")}&account_ids=$cny&min_time=${Math.multiplyExact(start,1000)}&max_time=${Math.addExact(Math.multiplyExact(end,1000),999)}$tag") as JSONObject
    return result.array("items") to result.getLong("totalCount")
}
