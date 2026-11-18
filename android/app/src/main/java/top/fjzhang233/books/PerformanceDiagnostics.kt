package top.fjzhang233.books

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Local-only, bounded performance evidence. It never stores accounts, amounts, keywords or tokens. */
internal class PerformanceDiagnostics(context:Context) {
    private val file=File(context.filesDir,"performance-events.jsonl")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    fun record(name:String,durationMs:Long,outcome:String="ok"){
        require(name.matches(Regex("[a-z0-9_.-]{1,64}")))
        require(outcome.matches(Regex("[a-z0-9_.-]{1,32}")))
        scope.launch {
            runCatching {
                synchronized(file){
                    file.parentFile?.mkdirs()
                    file.appendText(JSONObject().put("ts",System.currentTimeMillis()).put("name",name).put("durationMs",durationMs.coerceAtLeast(0)).put("outcome",outcome).toString()+"\n")
                    if(file.length()>256*1024){
                        val lines=file.readLines().takeLast(500)
                        file.writeText(lines.joinToString("\n")+if(lines.isEmpty())"" else "\n")
                    }
                }
            }
        }
    }
    fun readRecent(limit:Int=120):String=synchronized(file){if(!file.exists())"暂无性能记录" else file.readLines().takeLast(limit.coerceIn(1,500)).joinToString("\n").ifBlank{"暂无性能记录"}}
    fun clear(){synchronized(file){file.delete()}}
}
