package top.fjzhang233.books

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/** Internal-only activity. Receives amounts already fetched by the report; never an authentication token. */
class ReportChartActivity:ComponentActivity(){
    private var chartPayload:String?=null
    override fun onSaveInstanceState(outState:Bundle){chartPayload?.let{outState.putString("chart",it)};super.onSaveInstanceState(outState)}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val payload=savedInstanceState?.getString("chart")?:intent.getStringExtra("chart");chartPayload=payload
        val data=runCatching{decodeReportChart(payload?:error("missing_chart"))}.getOrNull()
        if(data==null){finish();return}
        intent.removeExtra("chart")
        setContent{FamilyTheme{CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface){
            var type by remember{mutableIntStateOf(data.second.first)}
            var lines by remember{mutableStateOf(data.second.second)}
            Column(Modifier.fillMaxSize().background(ledgerColors.background).systemBarsPadding().padding(horizontal=20.dp).testTag("report-chart-fullscreen")){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(data.first.period.label,Modifier.weight(1f));TextButton(onClick={lines=!lines}){Text(if(lines)"柱状图" else "折线图")};TextButton(onClick={finish()}){Text("关闭")}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){(if(data.first.period.yearly)listOf("支出","收入","结余","全部")else listOf("支出","收入","全部")).forEachIndexed{index,label->TextButton(onClick={type=index+2}){Text(label,color=if(type==index+2)MaterialTheme.colorScheme.primary else ledgerColors.muted)}}}
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)){ReportChart(data.first,type,lines,(maxHeight-52.dp).coerceAtLeast(40.dp))}
            }
        }}}
    }
}
internal fun encodeReportChart(data:ReportSnapshot,type:Int,lines:Boolean):String=JSONObject().put("month",data.period.month.toString()).put("yearly",data.period.yearly).put("first",data.period.customFirst?.toString()).put("last",data.period.customLast?.toString()).put("type",type).put("lines",lines).put("days",JSONArray(data.days.map{JSONObject().put("date",it.date.toString()).put("income",it.income.toString()).put("expense",it.expense.toString()).put("active",it.active)})).toString()
internal fun decodeReportChart(value:String):Pair<ReportSnapshot,Pair<Int,Boolean>>{
    require(value.length<=1024*1024)
    val json=JSONObject(value);val p=ReportPeriod(YearMonth.parse(json.getString("month")),json.getBoolean("yearly"),json.optString("first").takeIf{it.isNotBlank()}?.let(LocalDate::parse),json.optString("last").takeIf{it.isNotBlank()}?.let(LocalDate::parse))
    val rows=json.array("days");require(rows.size==p.days)
    val days=rows.mapIndexed{index,row->val date=LocalDate.parse(row.getString("date"));require(date==p.first.plusDays(index.toLong()));ReportDay(date,row.getString("income").toBigInteger(),row.getString("expense").toBigInteger(),row.getBoolean("active"))}
    val type=json.getInt("type");require(type in 2..if(p.yearly)5 else 4)
    return ReportSnapshot(p,days,emptyList(),false) to (type to json.getBoolean("lines"))
}
internal fun openReportChart(context:Context,data:ReportSnapshot,type:Int,lines:Boolean){context.startActivity(Intent(context,ReportChartActivity::class.java).putExtra("chart",encodeReportChart(data,type,lines)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
