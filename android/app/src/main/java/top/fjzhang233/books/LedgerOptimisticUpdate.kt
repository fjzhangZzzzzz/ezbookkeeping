package top.fjzhang233.books

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Merges the server-acknowledged transaction into the currently visible page.
 * The server response is used verbatim, so names, currencies, tags and pictures
 * stay consistent with the canonical representation. A background refresh still
 * replaces this page shortly afterwards.
 */
internal fun mergeAcknowledgedLedgerRow(
    current:List<JSONObject>,
    acknowledged:JSONObject,
    ledgerMonth:String,
    ledgerDay:String?
):List<JSONObject>{
    val id=acknowledged.id()
    require(Regex("[1-9][0-9]*").matches(id)) { "服务响应缺少流水编号" }
    val date=Instant.ofEpochSecond(acknowledged.optLong("time")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
    val visible=date.startsWith("$ledgerMonth-")&&(ledgerDay==null||ledgerDay==date)
    val without=current.filterNot{it.id()==id}
    return if(visible)(without+acknowledged).sortedByDescending{it.optLong("time")} else without
}
