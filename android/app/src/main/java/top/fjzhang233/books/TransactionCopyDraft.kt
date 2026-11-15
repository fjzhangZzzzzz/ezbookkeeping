package top.fjzhang233.books

import org.json.JSONArray
import org.json.JSONObject

/** Copy editable business fields only. Identity, request identity, picture links and URL tokens never transfer. */
internal fun transactionCopyDraft(source:JSONObject,now:Long):JSONObject {
    require(source.optInt("type") in 2..4) { "余额调整不能复制为普通收支" }
    require(now>0)
    val result=JSONObject().put("type",source.getInt("type")).put("time",now).put("utcOffset",480)
    for(key in listOf("categoryId","sourceAccountId","sourceAmount","destinationAccountId","destinationAmount","comment","hideAmount")) {
        if(source.has(key))result.put(key,source.get(key))
    }
    result.put("tagIds",JSONArray(source.optJSONArray("tagIds")?.toString()?:"[]"))
    return result
}
