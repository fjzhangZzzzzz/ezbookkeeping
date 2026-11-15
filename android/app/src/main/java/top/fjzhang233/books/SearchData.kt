package top.fjzhang233.books

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

internal const val SEARCH_PAGE_SIZE=30
internal data class LedgerSearchFilter(
    val keyword:String="",
    val type:Int=0,
    val firstDate:String?=null,
    val lastDate:String?=null,
    val accountIds:Set<String> = emptySet(),
    val categoryIds:Set<String> = emptySet(),
    val tagIds:Set<String> = emptySet(),
    val tagMode:Int=0,
    val amountMode:String="",
    val amount1:String="",
    val amount2:String="",
    val picturesOnly:Boolean=false,
    val ignoreCase:Boolean=true,
) {
    fun query(page:Int):String {
        require(page>0&&type in 0..4&&tagMode in 0..3)
        require(keyword.length<=255){"搜索内容最多255字"}
        require((accountIds+categoryIds+tagIds).all{Regex("[0-9]+").matches(it)}){"筛选项目无效，请重新选择"}
        val first=firstDate?.let(LocalDate::parse);val last=lastDate?.let(LocalDate::parse)
        require(first==null||last==null||!last.isBefore(first)){"结束日期不能早于开始日期"}
        val params=linkedMapOf("count" to SEARCH_PAGE_SIZE.toString(),"page" to page.toString(),"with_count" to "true","with_pictures" to "true","type" to type.toString(),"keyword" to keyword.trim(),"match_mode" to if(ignoreCase)"1" else "0")
        val zone=ZoneId.of("Asia/Shanghai")
        first?.let{params["min_time"]=it.atStartOfDay(zone).toInstant().toEpochMilli().toString()}
        last?.let{params["max_time"]=Math.subtractExact(it.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),1).toString()}
        if(accountIds.isNotEmpty())params["account_ids"]=accountIds.sorted().joinToString(",")
        if(categoryIds.isNotEmpty())params["category_ids"]=categoryIds.sorted().joinToString(",")
        if(tagIds.isNotEmpty())params["tag_filter"]="$tagMode:"+tagIds.sorted().joinToString(",")
        if(amountMode.isNotEmpty()){
            require(amountMode in setOf("gt","lt","eq","ne","bt","nb")){"请选择有效金额条件"}
            val a=searchMinor(amount1)
            params["amount_filter"]=if(amountMode in setOf("bt","nb")){val b=searchMinor(amount2);require(b>=a){"金额上限不能小于下限"};"$amountMode:$a:$b"}else "$amountMode:$a"
        }
        if(picturesOnly)params["must_have_pictures"]="true"
        return params.entries.joinToString("&"){(key,value)->key+"="+URLEncoder.encode(value,"UTF-8")}
    }
    fun toJson():String=JSONObject().put("keyword",keyword).put("type",type).put("firstDate",firstDate).put("lastDate",lastDate).put("accountIds",JSONArray(accountIds.sorted())).put("categoryIds",JSONArray(categoryIds.sorted())).put("tagIds",JSONArray(tagIds.sorted())).put("tagMode",tagMode).put("amountMode",amountMode).put("amount1",amount1).put("amount2",amount2).put("picturesOnly",picturesOnly).put("ignoreCase",ignoreCase).toString()
    companion object {
        fun fromJson(text:String):LedgerSearchFilter {
            val obj=JSONObject(text)
            fun ids(key:String):Set<String>{val array=obj.optJSONArray(key)?:return emptySet();return (0 until array.length()).map{array.getString(it)}.toSet()}
            fun date(key:String)=obj.optString(key).takeIf{it.isNotBlank()&&it!="null"}
            return LedgerSearchFilter(obj.optString("keyword"),obj.optInt("type"),date("firstDate"),date("lastDate"),ids("accountIds"),ids("categoryIds"),ids("tagIds"),obj.optInt("tagMode"),obj.optString("amountMode"),obj.optString("amount1"),obj.optString("amount2"),obj.optBoolean("picturesOnly"),obj.optBoolean("ignoreCase",true))
        }
    }
}
internal fun searchMinor(value:String):Long {
    require(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?").matches(value.trim())){"请输入有效金额，最多两位小数"}
    return BigDecimal(value.trim()).movePointRight(2).longValueExact()
}
internal data class LedgerSearchResult(val items:List<JSONObject>,val total:Long) {
    fun hasMore(page:Int):Boolean=page.toLong()*SEARCH_PAGE_SIZE<total
}
internal suspend fun BooksApi.search(filter:LedgerSearchFilter,page:Int):LedgerSearchResult {
    val response=call("/api/v1/transactions/list.json?"+filter.query(page)) as JSONObject
    return LedgerSearchResult(response.array("items"),response.getLong("totalCount"))
}

/** A bounded complete result, never a first-page financial summary. */
internal data class LedgerSearchSnapshot(val items:List<JSONObject>) {
    val amounts:CalendarAmounts
    val excludedForeign:Boolean
    init {
        var income=java.math.BigInteger.ZERO;var expense=java.math.BigInteger.ZERO;var foreign=false
        items.forEach { row ->
            if(row.optInt("type") !in setOf(2,3)) return@forEach
            val currency=row.optJSONObject("sourceAccount")?.optString("currency").orEmpty()
            require(currency.isNotBlank()) { "账单缺少币种，无法计算搜索汇总，请刷新后重试" }
            if(currency!="CNY") { foreign=true;return@forEach }
            val raw=row.optString("sourceAmount");require(Regex("-?[0-9]+").matches(raw)){"账单金额无效，无法计算搜索汇总"};val amount=raw.toBigInteger()
            if(row.getInt("type")==2)income+=amount else expense+=amount
        }
        amounts=CalendarAmounts(income,expense);excludedForeign=foreign
    }
}
internal suspend fun completeSearch(fetch:suspend(Int)->LedgerSearchResult):LedgerSearchSnapshot {
    val limit=20_000L
    val first=fetch(1)
    require(first.total in 0..limit) { "匹配账单过多，请缩小日期范围后重试（最多2万笔）" }
    val rows=ArrayList<JSONObject>();val seen=HashSet<String>()
    fun append(page:LedgerSearchResult) {
        require(page.total==first.total) { "搜索期间账单有变化，请重新搜索" }
        page.items.forEach { row -> require(row.id()!="0" && seen.add(row.id())) { "搜索期间账单有变化，请重新搜索" };rows.add(row) }
        require(rows.size.toLong()<=first.total) { "搜索期间账单有变化，请重新搜索" }
    }
    append(first)
    val pages=(first.total+SEARCH_PAGE_SIZE-1)/SEARCH_PAGE_SIZE
    for(page in 2..pages.toInt()) { val next=fetch(page);require(next.items.isNotEmpty()) { "搜索结果不完整，请重新搜索" };append(next) }
    require(rows.size.toLong()==first.total) { "搜索结果不完整，请重新搜索" }
    return LedgerSearchSnapshot(rows)
}
internal suspend fun BooksApi.completeSearch(filter:LedgerSearchFilter):LedgerSearchSnapshot = completeSearch { page->search(filter,page) }
internal fun searchRowDay(row:JSONObject):LocalDate=java.time.Instant.ofEpochSecond(row.getLong("time")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()


internal data class LedgerSearchDatePreset(val label:String,val firstDate:String?,val lastDate:String?)
/** Reference id search_time_chip_last_two_years and 2026 label 2025~2026 imply two calendar years. */
internal fun searchDatePresets(today:LocalDate):List<LedgerSearchDatePreset>{
    val month=java.time.YearMonth.from(today)
    fun preset(label:String,first:LocalDate,last:LocalDate)=LedgerSearchDatePreset(label,first.toString(),last.toString())
    return listOf(
        preset("本月",month.atDay(1),month.atEndOfMonth()),
        month.minusMonths(1).let{preset("上月",it.atDay(1),it.atEndOfMonth())},
        preset("今年",LocalDate.of(today.year,1,1),LocalDate.of(today.year,12,31)),
        preset("去年",LocalDate.of(today.year-1,1,1),LocalDate.of(today.year-1,12,31)),
        preset("${today.year-1}~${today.year}",LocalDate.of(today.year-1,1,1),LocalDate.of(today.year,12,31)),
        LedgerSearchDatePreset("全部",null,null)
    )
}
