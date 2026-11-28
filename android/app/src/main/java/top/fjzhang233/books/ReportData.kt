package top.fjzhang233.books

import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val reportZone = ZoneId.of("Asia/Shanghai")
private val reportCategoryIdPattern = Regex("[1-9][0-9]*")
internal fun isReportCategoryId(id: String) = reportCategoryIdPattern.matches(id)
internal fun shanghaiReportDate(time: Long): LocalDate = Instant.ofEpochSecond(time).atZone(reportZone).toLocalDate()

internal data class ReportPeriod(val month:YearMonth,val yearly:Boolean=false,val customFirst:LocalDate?=null,val customLast:LocalDate?=null) {
    init {require((customFirst==null)==(customLast==null));if(customFirst!=null){require(!customLast!!.isBefore(customFirst));require(java.time.temporal.ChronoUnit.DAYS.between(customFirst,customLast)<3660){"自定义周期最多十年"}}}
    val custom:Boolean get()=customFirst!=null
    val first:LocalDate get()=customFirst?:if(yearly)LocalDate.of(month.year,1,1) else month.atDay(1)
    val until:LocalDate get()=customLast?.plusDays(1)?:if(yearly)first.plusYears(1) else first.plusMonths(1)
    val bounds:Pair<Long,Long> get()=first.atStartOfDay(reportZone).toEpochSecond() to until.atStartOfDay(reportZone).toEpochSecond()-1
    val days:Int get()=java.time.temporal.ChronoUnit.DAYS.between(first,until).toInt()
    fun shift(amount:Long)=if(custom)copy(customFirst=first.plusDays(days*amount),customLast=until.minusDays(1).plusDays(days*amount))else copy(month=if(yearly)month.plusYears(amount) else month.plusMonths(amount))
    val label:String get()=if(custom)"$first — ${until.minusDays(1)}" else if(yearly)"${month.year}年" else month.toString()
}
internal data class ReportFilter(val accountIds:Set<String> = emptySet(),val categoryIds:Set<String> = emptySet(),val tagIds:Set<String> = emptySet(),val allTags:Boolean=false) {
    val tagQuery:String get()=if(tagIds.isEmpty())"" else java.net.URLEncoder.encode((if(allTags)"1:" else "0:")+tagIds.sorted().joinToString(","),"UTF-8")
    fun acceptsAccount(id:String)=accountIds.isEmpty()||id in accountIds
    val categoryScope: ReportCategoryScope get()=if(categoryIds.isEmpty()) ReportCategoryScope.All else ReportCategoryScope.Only(categoryIds)
}

internal sealed interface ReportCategoryScope {
    data object All : ReportCategoryScope
    data object Empty : ReportCategoryScope
    data class Only(val ids: Set<String>) : ReportCategoryScope { init { require(ids.isNotEmpty()) } }

    fun accepts(id: String) = when (this) { All -> true; Empty -> false; is Only -> id in ids }
}

internal fun reportCategoryScope(categoryId: String, categories: List<JSONObject>, source: ReportCategoryScope): ReportCategoryScope {
    require(isReportCategoryId(categoryId))
    val ids = categories.asSequence().filter { it.id("parentId") == categoryId }.map { it.id() }.toSet() + categoryId
    val matching = ids.filterTo(mutableSetOf(), source::accepts)
    return if (matching.isEmpty()) ReportCategoryScope.Empty else ReportCategoryScope.Only(matching)
}
internal data class ReportDay(val date:LocalDate,val income:BigInteger,val expense:BigInteger,val active:Boolean=income!=BigInteger.ZERO||expense!=BigInteger.ZERO)
internal data class ReportCategory(val category:JSONObject,val amount:BigInteger,val previous:BigInteger)
internal data class ReportSnapshot(
    val period:ReportPeriod,val days:List<ReportDay>,val categories:List<ReportCategory>,val excludedForeign:Boolean,
    val previousAmounts: Map<String, BigInteger> = emptyMap(),
    val familyCatalog: List<JSONObject> = emptyList(),
) {
    val activeMonths:Int get()=days.filter{it.active}.map{YearMonth.from(it.date)}.distinct().size
    val income:BigInteger get()=days.fold(BigInteger.ZERO){sum,row->sum+row.income}
    val expense:BigInteger get()=days.fold(BigInteger.ZERO){sum,row->sum+row.expense}
    fun ranking(type:Int)=categories.filter{it.category.optInt("type")==type}.sortedByDescending{it.amount}
}

internal fun ReportSnapshot.parentRanking(type: Int, catalog: List<JSONObject>): List<ReportCategory> {
    val byId = catalog.associateBy { it.id() }
    fun parent(category: JSONObject) = byId[category.id("parentId")] ?: category
    val priorByParent = mutableMapOf<String, BigInteger>()
    for ((id, amount) in previousAmounts) {
        val category = byId[id] ?: continue
        val parentId = parent(category).id()
        priorByParent[parentId] = (priorByParent[parentId] ?: BigInteger.ZERO) + amount
    }
    return ranking(type).groupBy { parent(it.category).id() }.map { (id, rows) ->
        ReportCategory(parent(rows.first().category), rows.fold(BigInteger.ZERO) { sum, row -> sum + row.amount },
            priorByParent[id] ?: BigInteger.ZERO)
    }.sortedByDescending { it.amount }
}

internal fun List<ReportCategory>.labelDirectEntries(parentId: String, hasChildren: Boolean): List<ReportCategory> =
    if (!hasChildren) this else map { row ->
        if (row.category.id() == parentId) row.copy(category = JSONObject(row.category.toString()).put("name", "直接记入"))
        else row
    }

internal fun ReportSnapshot.familyCategoryParts(parentId: String, type: Int): List<ReportCategory> =
    ranking(type).filter { it.category.id() == parentId || it.category.id("parentId") == parentId }
        .labelDirectEntries(parentId, familyCatalog.any { it.id("parentId") == parentId })

internal fun List<JSONObject>.reportParentIds(): Set<String> =
    map { it.id("parentId") }.filter(::isReportCategoryId).toSet()

internal fun ReportSnapshot.detailCategoryScope(selection: String? = null): ReportCategoryScope {
    val ids = categories.map { it.category.id() }.filterTo(mutableSetOf()) { selection == null || it == selection }
    return if (ids.isEmpty()) ReportCategoryScope.Empty else ReportCategoryScope.Only(ids)
}

internal fun reportSnapshot(
    period:ReportPeriod,daily:List<JSONObject>,items:List<JSONObject>,previous:List<JSONObject>,
    accounts:List<JSONObject>,categories:List<JSONObject>,filter:ReportFilter=ReportFilter(),
    scope:ReportCategoryScope=filter.categoryScope,
):ReportSnapshot {
    val accountById=accounts.associateBy{it.id()};val categoryById=categories.associateBy{it.id()}
    var foreign=false
    fun aggregate(rows:List<JSONObject>):Map<String,BigInteger>{val result=mutableMapOf<String,BigInteger>()
        for(row in rows){if(row.has("relatedAccountId"))continue;if(!filter.acceptsAccount(row.id("accountId"))||!scope.accepts(row.id("categoryId")))continue;val account=accountById[row.id("accountId")]?:error("账户统计资料不完整，请刷新后重试")
            if(account.optString("currency")!="CNY"){foreign=true;continue}
            val category=categoryById[row.id("categoryId")]?:error("分类统计资料不完整，请刷新后重试")
            if(category.optInt("type") !in 1..2 || row.has("relatedAccountId"))continue
            val id=category.id();result[id]=(result[id]?:BigInteger.ZERO)+row.getString("amount").toBigInteger()
        };return result}
    val current=aggregate(items);val before=aggregate(previous)
    val byDate=if(scope==ReportCategoryScope.Empty) emptyMap() else daily.associateBy{LocalDate.parse(it.getString("date"))}
    val days=(0 until period.days).map{offset->val date=period.first.plusDays(offset.toLong());val amounts=byDate[date]?.array("amounts").orEmpty()
        if(amounts.any{it.optString("currency")!="CNY"})foreign=true
        val cny=amounts.firstOrNull{it.optString("currency")=="CNY"}
        ReportDay(date,cny?.optString("incomeAmount","0")?.toBigInteger()?:BigInteger.ZERO,cny?.optString("expenseAmount","0")?.toBigInteger()?:BigInteger.ZERO,cny!=null)}
    return ReportSnapshot(period,days,current.map{(id,value)->ReportCategory(categoryById.getValue(id),value,before[id]?:BigInteger.ZERO)},foreign,before)
}
internal suspend fun BooksApi.report(
    period:ReportPeriod,accounts:List<JSONObject>,categories:List<JSONObject>,
    filter:ReportFilter=ReportFilter(),scope:ReportCategoryScope=filter.categoryScope,
):ReportSnapshot {
    if(scope==ReportCategoryScope.Empty) return reportSnapshot(period,emptyList(),emptyList(),emptyList(),accounts,categories,filter,scope)
    val tag=if(filter.tagIds.isEmpty())"" else "&tag_filter=${filter.tagQuery}"
    suspend fun stats(p:ReportPeriod):List<JSONObject>{val(a,b)=p.bounds;return (call("/api/v1/transactions/statistics.json?start_time=$a&end_time=$b&use_transaction_timezone=false$tag") as JSONObject).array("items")}
    val excludedAccounts=accounts.filterNot{filter.acceptsAccount(it.id())}.joinToString(","){it.id()}
    val excludedCategories=categories.filterNot{scope.accepts(it.id())}.joinToString(","){it.id()}
    val (start,end)=period.bounds
    val(daily,current,previous)=coroutineScope {
        val dailyRequest=async { (call("/api/v1/transactions/amounts/daily.json?start_time=$start&end_time=$end&use_transaction_timezone=false&exclude_account_ids=$excludedAccounts&exclude_category_ids=$excludedCategories$tag") as org.json.JSONArray).objects() }
        val currentRequest=async { stats(period) }
        val previousRequest=async { stats(period.shift(-1)) }
        Triple(dailyRequest.await(),currentRequest.await(),previousRequest.await())
    }
    return reportSnapshot(period,daily,current,previous,accounts,categories,filter,scope)
}
internal suspend fun BooksApi.reportCategoryPage(
    period:ReportPeriod,categoryId:String,accounts:List<JSONObject>,page:Int,filter:ReportFilter=ReportFilter(),
) = reportCategoryPage(period,reportCategoryScope(categoryId,emptyList(),filter.categoryScope),accounts,page,filter)

internal suspend fun BooksApi.reportCategoryPage(
    period:ReportPeriod,scope:ReportCategoryScope,accounts:List<JSONObject>,page:Int,filter:ReportFilter,
    directParentIds:Set<String> = emptySet(),
):Pair<List<JSONObject>,Long> {
    val ids = (scope as? ReportCategoryScope.Only)?.ids ?: return emptyList<JSONObject>() to 0L
    return reportTransactionPage(period,accounts,page,filter,ids,directParentIds)
}

internal fun familyReportSnapshot(payload:JSONObject, period:ReportPeriod):ReportSnapshot {
    val current = payload.array("categories")
    val all = current + payload.array("priorOnlyCategories")
    val catalog = linkedMapOf<String, JSONObject>()
    for (row in all) {
        val parent = row.optJSONObject("parent")
        if (parent != null) catalog.putIfAbsent(parent.id(), JSONObject(parent.toString()).put("type", row.optInt("type")))
        catalog[row.id()] = JSONObject(row.toString()).put("parentId", parent?.id() ?: "0")
    }
    val previous = all.associate { it.id() to it.optString("previousAmountMinor", "0").toBigInteger() }
    val dayRows = payload.array("days").associateBy { java.time.LocalDate.parse(it.getString("date")) }
    val days = (0 until period.days).map { offset ->
        val date = period.first.plusDays(offset.toLong())
        val row = dayRows[date]
        ReportDay(date, row?.optString("incomeMinor", "0")?.toBigInteger() ?: BigInteger.ZERO, row?.optString("expenseMinor", "0")?.toBigInteger() ?: BigInteger.ZERO, active = row != null)
    }
    return ReportSnapshot(period, days, current.map { row ->
        ReportCategory(catalog.getValue(row.id()), row.optString("amountMinor", "0").toBigInteger(), previous.getValue(row.id()))
    }, payload.optBoolean("hasExcludedForeignCurrency"), previous, catalog.values.toList())
}
/** Same CNY/Shanghai/account/category/tag scope as the clicked daily summary, with full server count. */
internal suspend fun BooksApi.reportDayPage(
    date:LocalDate,accounts:List<JSONObject>,categories:List<JSONObject>,page:Int,filter:ReportFilter=ReportFilter(),
    directParentIds:Set<String> = emptySet(),
):Pair<List<JSONObject>,Long>{
    val parents=categories.reportParentIds()
    val flowCategories=categories.filter{it.optInt("type") in 1..2&&filter.categoryScope.accepts(it.id())&&
        (it.id() !in parents || it.id() in directParentIds)}.map{it.id()}.toSet()
    if(flowCategories.isEmpty())return emptyList<JSONObject>() to 0L
    return reportTransactionPage(ReportPeriod(YearMonth.from(date),false,date,date),accounts,page,filter,flowCategories,directParentIds)
}
private suspend fun BooksApi.reportTransactionPage(
    period:ReportPeriod,accounts:List<JSONObject>,page:Int,filter:ReportFilter,categoryIds:Set<String>,
    directParentIds:Set<String>,
):Pair<List<JSONObject>,Long>{
    require(page>0);require(categoryIds.isNotEmpty()&&categoryIds.all(::isReportCategoryId))
    val cny=accounts.filter{it.optString("currency")=="CNY"&&filter.acceptsAccount(it.id())}.joinToString(","){it.id()}
    if(cny.isEmpty())return emptyList<JSONObject>() to 0L
    val(start,end)=period.bounds
    val tag=if(filter.tagIds.isEmpty())"" else "&tag_filter=${filter.tagQuery}"
    val query="/api/v1/transactions/list.json?with_count=true&with_pictures=true&account_ids=$cny&min_time=${Math.multiplyExact(start,1000)}&max_time=${Math.addExact(Math.multiplyExact(end,1000),999)}$tag"
    // The list API expands parent IDs and omits transactions assigned directly to them.
    // Only scopes with historical direct entries need exact client-side selection.
    if (categoryIds.any { it in directParentIds }) {
        val rows = mutableListOf<JSONObject>()
        val count = 50
        var batch = 1
        do {
            val result = call("$query&count=$count&page=$batch") as JSONObject
            val items = result.array("items")
            rows += items.filter { it.optInt("type") in 2..3 && it.id("categoryId") in categoryIds }
            batch++
        } while (items.size == count && (batch - 1).toLong() * count < result.getLong("totalCount"))
        val matched = rows.distinctBy { it.id() }
        return matched to matched.size.toLong()
    }
    val result=call("$query&count=30&page=$page&category_ids=${categoryIds.sorted().joinToString(",")}") as JSONObject
    return result.array("items") to result.getLong("totalCount")
}
