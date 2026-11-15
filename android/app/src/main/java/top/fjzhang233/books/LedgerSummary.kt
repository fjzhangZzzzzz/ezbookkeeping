package top.fjzhang233.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Composable internal fun WeeklySpendingCard(rows:List<JSONObject>){
    val today=LocalDate.now(ZoneId.of("Asia/Shanghai"));val days=(6 downTo 0).map{today.minusDays(it.toLong())};val byDate=rows.associateBy{it.optString("date")}
    val amounts=days.map{date->byDate[date.toString()]?.array("amounts")?.firstOrNull{it.optString("currency")=="CNY"}?.optString("expenseAmount","0")?.toBigInteger()?:BigInteger.ZERO}
    val total=amounts.fold(BigInteger.ZERO,BigInteger::add);val peak=amounts.maxOrNull()?.max(BigInteger.ONE)?:BigInteger.ONE
    Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp).height(184.dp),shape=RoundedCornerShape(8.dp),color=ledgerColors.surface){
        Column(Modifier.padding(12.dp)){
            Text("最近7日支出",fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Bold);Text("总计: ${money(total.toString())} · CNY",fontSize=11.sp,lineHeight=16.sp,color=ledgerColors.muted)
            Row(Modifier.fillMaxWidth().weight(1f).padding(top=10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom){
                days.forEachIndexed{index,date->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                    if(amounts[index]!=BigInteger.ZERO)Text(money(amounts[index].toString()),fontSize=9.sp,lineHeight=12.sp,color=ledgerColors.expense)else Spacer(Modifier.height(12.dp))
                    // Floating-point ratios are used only for drawing; monetary sums stay exact integers.
                    val ratio=amounts[index].max(BigInteger.ZERO).toBigDecimal().divide(peak.toBigDecimal(),8,RoundingMode.HALF_UP).toFloat()
                    Box(Modifier.height(74.dp).width(12.dp),contentAlignment=Alignment.BottomCenter){if(ratio>0)Box(Modifier.fillMaxWidth().fillMaxHeight(ratio).background(ledgerColors.expense,RoundedCornerShape(4.dp)))}
                    Spacer(Modifier.height(6.dp));Text(listOf("周一","周二","周三","周四","周五","周六","周日")[date.dayOfWeek.value-1],fontSize=10.sp,lineHeight=14.sp,color=ledgerColors.muted)
                }}
            }
        }
    }
}
