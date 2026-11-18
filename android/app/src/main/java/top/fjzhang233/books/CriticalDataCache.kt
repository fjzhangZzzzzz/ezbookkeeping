package top.fjzhang233.books

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject

internal data class CachedCriticalData(val accounts:List<JSONObject>,val categories:List<JSONObject>,val revision:String?)

/** Encrypted, bounded UI cache; server responses remain authoritative for every write. */
internal class CriticalDataCache(app:Application,scope:String) {
    private val store=TokenStore(app,"critical-data-$scope","critical-data-$scope")
    fun read():CachedCriticalData?=runCatching{
        val value=store.readChecked()?:return@runCatching null;val body=JSONObject(value)
        require(body.optString("version")==BuildConfig.VERSION_NAME)
        require(System.currentTimeMillis()-body.getLong("savedAt")<24*60*60_000L)
        CachedCriticalData(body.getJSONArray("accounts").objects(),body.getJSONArray("categories").objects(),body.optString("revision").takeIf{it.isNotBlank()})
    }.getOrNull()
    fun write(accounts:List<JSONObject>,categories:List<JSONObject>,revision:String?){
        val accountsJson=JSONArray();accounts.forEach(accountsJson::put);val categoriesJson=JSONArray();categories.forEach(categoriesJson::put)
        store.write(JSONObject().put("version",BuildConfig.VERSION_NAME).put("savedAt",System.currentTimeMillis()).put("revision",revision?:"").put("accounts",accountsJson).put("categories",categoriesJson).toString())
    }
    fun clear(){store.clear()}
}
