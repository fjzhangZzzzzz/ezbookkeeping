package top.fjzhang233.books

import android.icu.util.ChineseCalendar
import android.icu.util.Calendar as LunarFields
import android.icu.util.TimeZone as LunarZone
import android.icu.util.ULocale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

internal enum class CalendarAmountMode(val label:String) { BOTH("收&支"), BALANCE("结余"), INCOME("收入"), EXPENSE("支出") }
internal data class CalendarAmounts(val income:BigInteger=BigInteger.ZERO,val expense:BigInteger=BigInteger.ZERO) {
    val balance:BigInteger get()=income-expense
    val hasAmounts:Boolean get()=income!=BigInteger.ZERO||expense!=BigInteger.ZERO
}
internal data class CalendarMonthAmounts(val days:Map<LocalDate,CalendarAmounts>,val total:CalendarAmounts,val excludedForeign:Boolean)

/** The daily API is complete for the month; never derive calendar totals from a 30-row ledger page. */
internal fun calendarAmounts(month:YearMonth,rows:List<JSONObject>):CalendarMonthAmounts {
    val days=mutableMapOf<LocalDate,CalendarAmounts>();var foreign=false
    for(row in rows){
        val date=LocalDate.parse(row.getString("date"));if(YearMonth.from(date)!=month)continue
        var value=days[date]?:CalendarAmounts()
        for(amount in row.array("amounts")){
            if(amount.getString("currency")!="CNY"){foreign=true;continue}
            value=CalendarAmounts(value.income+amount.getString("incomeAmount").toBigInteger(),value.expense+amount.getString("expenseAmount").toBigInteger())
        }
        days[date]=value
    }
    val total=days.values.fold(CalendarAmounts()){sum,day->CalendarAmounts(sum.income+day.income,sum.expense+day.expense)}
    return CalendarMonthAmounts(days,total,foreign)
}
internal fun calendarAmount(minor:BigInteger):String=BigDecimal(minor,2).stripTrailingZeros().toPlainString()
internal fun calendarFirstOffset(month:YearMonth,firstDay:Int):Int {
    require(firstDay in setOf(1,6,7));return (month.atDay(1).dayOfWeek.value-firstDay+7)%7
}
internal fun calendarLunarLabel(date:LocalDate):String {
    // Android's built-in ICU calendar; no copied calendar data or external service.
    val lunar=ChineseCalendar(LunarZone.getTimeZone("Asia/Shanghai"),ULocale.SIMPLIFIED_CHINESE)
    lunar.timeInMillis=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    val day=lunar.get(LunarFields.DAY_OF_MONTH)
    val digits=listOf("一","二","三","四","五","六","七","八","九","十")
    if(day==1){val months=listOf("正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月");return (if(lunar.get(LunarFields.IS_LEAP_MONTH)==1)"闰" else "")+months[lunar.get(LunarFields.MONTH)]}
    return when(day){10->"初十";20->"二十";30->"三十";in 2..9->"初"+digits[day-1];in 11..19->"十"+digits[day-11];else->"廿"+digits[day-21]}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LedgerCalendar(vm:BooksModel,showTransactionSheets:Boolean,openStatistics:(CategoryStatisticsPage)->Unit,dismiss:()->Unit){
    val month=YearMonth.parse(vm.ledgerMonth);val today=LocalDate.now(ZoneId.of("Asia/Shanghai"))
    var dayNumber by rememberSaveable{mutableIntStateOf(vm.ledgerDay?.let{LocalDate.parse(it).dayOfMonth}?:today.dayOfMonth)}
    val selected=month.atDay(dayNumber.coerceAtMost(month.lengthOfMonth()))
    val context=LocalContext.current;val prefs=remember{context.getSharedPreferences("ledger-calendar",0)}
    var firstDay by rememberSaveable{mutableIntStateOf(prefs.getInt("firstDay",1).takeIf{it in setOf(1,6,7)}?:1)}
    var mode by rememberSaveable{mutableStateOf(CalendarAmountMode.BOTH)}
    var menu by remember{mutableStateOf(false)};var weekPicker by remember{mutableStateOf(false)}
    var monthPicker by rememberSaveable { mutableStateOf(false) }
    val pickMonth: () -> Unit = { monthPicker = true }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val snapshot=remember(vm.ledgerMonth,vm.dailyAmounts){calendarAmounts(month,vm.dailyAmounts)}
    LaunchedEffect(vm.ledgerMonth,dayNumber){if(vm.token!=null&&vm.ledgerDay!=selected.toString())vm.changeLedgerDay(selected.toString())}
    if(vm.token==null){LaunchedEffect(Unit){dismiss()};return}
    fun shift(offset:Long){if(!vm.busy)vm.changeLedgerMonth(month.plusMonths(offset).toString())}
    fun choose(date:LocalDate){if(!vm.busy){dayNumber=date.dayOfMonth;if(vm.ledgerDay!=date.toString())vm.changeLedgerDay(date.toString())}}
    fun current(){if(!vm.busy){dayNumber=today.dayOfMonth;if(YearMonth.from(today)!=month)vm.changeLedgerMonth(YearMonth.from(today).toString())else vm.changeLedgerDay(today.toString())}}
        Scaffold(containerColor=ledgerColors.background,topBar={CenterAlignedTopAppBar(expandedHeight=56.dp,title={LedgerMonthControl(vm.ledgerMonth.replace('-', '.'), enabled = !vm.busy, open = pickMonth)},navigationIcon={IconButton(onClick=dismiss){Icon(Icons.Outlined.ArrowBack,"返回流水")}},actions={Box{IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"日历更多选项")};DropdownMenu(expanded=menu,onDismissRequest={menu=false},containerColor=ledgerColors.surface){DropdownMenuItem(text={Text("选择每周第一天")},onClick={menu=false;weekPicker=true})}}})},floatingActionButtonPosition=FabPosition.Center,floatingActionButton={FloatingActionButton(onClick={vm.newTransaction(selected.toString())},modifier=Modifier.size(56.dp).testTag("calendar-add-transaction"),shape=CircleShape,containerColor=ledgerColors.accent,contentColor=Color.White){Icon(Icons.Outlined.Add,"添加新的账单",Modifier.size(28.dp))}}){padding->
            Box(Modifier.fillMaxSize().padding(padding)){
                LazyColumn(Modifier.fillMaxSize().testTag("calendar-ledger"),contentPadding=PaddingValues(top=12.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(0.dp)){
                    if(vm.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                    if(vm.error.isNotBlank())item{Text(vm.error,Modifier.padding(horizontal=16.dp),color=ledgerColors.expense,fontSize=13.sp)}
                    if(vm.backgroundRefreshFailed)item{LedgerRefreshRetry(vm)}
                    item{CalendarMonthCard(month,snapshot,selected,firstDay,mode,!vm.busy,{mode=it},::choose,::shift)}
                    if(vm.ledgerDay==selected.toString()&&!vm.busy&&vm.transactions.isNotEmpty()){
                        item{Spacer(Modifier.height(12.dp));CalendarDayHeader(selected,snapshot.days[selected]?:CalendarAmounts(),today)}
                        itemsIndexed(vm.transactions,key={_,row->row.id()}){index,row->
                            Box(Modifier.padding(horizontal=12.dp)){
                                LedgerTransactionRow(row,index==0,Modifier.testTag("calendar-transaction-${row.id()}")){detailId=row.id()}
                            }
                        }
                        if(vm.hasMoreTransactions)item{TextButton(onClick=vm::loadMore,modifier=Modifier.fillMaxWidth()){Text("加载更多")}}
                    }
                }
                if(selected!=today)FloatingActionButton(onClick=::current,modifier=Modifier.align(Alignment.BottomEnd).padding(end=16.dp,bottom=16.dp).size(56.dp).testTag("calendar-today"),shape=CircleShape,containerColor=ledgerColors.surface,contentColor=ledgerColors.accent){Text("今",fontSize=20.sp,fontWeight=FontWeight.Bold)}
            }
        }
        if(weekPicker)Dialog(onDismissRequest={weekPicker=false}){Surface(shape=RoundedCornerShape(4.dp),color=ledgerColors.surface){Column(Modifier.padding(vertical=8.dp)){Text("选择每周第一天",Modifier.padding(horizontal=24.dp,vertical=12.dp),fontSize=14.sp);listOf(1 to "星期一",6 to "星期六",7 to "星期日").forEach{(value,label)->TextButton(onClick={firstDay=value;prefs.edit().putInt("firstDay",value).apply();weekPicker=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(label)}}}}}
        if (showTransactionSheets) vm.transactions.selectedTransaction(detailId)?.let { row ->
            LedgerTransactionDetails(vm, row, openStatistics) { detailId = null }
        }
    if (monthPicker) LedgerMonthPicker(vm.ledgerMonth, { monthPicker = false }) {
        vm.changeLedgerMonth(it)
        monthPicker = false
    }
}

@Composable internal fun CalendarMonthCard(month:YearMonth,snapshot:CalendarMonthAmounts,selected:LocalDate,firstDay:Int,mode:CalendarAmountMode,enabled:Boolean,onMode:(CalendarAmountMode)->Unit,choose:(LocalDate)->Unit,shift:(Long)->Unit){
    val scale=remember(snapshot){CalendarHeatScale(snapshot.days.values.maxOfOrNull{it.income.abs()}?:BigInteger.ZERO,snapshot.days.values.maxOfOrNull{it.expense.abs()}?:BigInteger.ZERO,snapshot.days.values.maxOfOrNull{it.balance.abs()}?:BigInteger.ZERO)}
    val cellHeight=52.dp*LocalDensity.current.fontScale.coerceAtLeast(1f);val offset=calendarFirstOffset(month,firstDay);val weekCount=(offset+month.lengthOfMonth()+6)/7
    var distance by remember{mutableFloatStateOf(0f)}
    Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("calendar-month-card").pointerInput(month,enabled){detectHorizontalDragGestures(onDragStart={distance=0f},onHorizontalDrag={change,amount->if(enabled){change.consume();distance+=amount}},onDragEnd={if(enabled&&abs(distance)>70.dp.toPx())shift(if(distance<0)1 else -1);distance=0f})},shape=RoundedCornerShape(8.dp),color=ledgerColors.surface){
        Column{
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){repeat(7){index->val day=(firstDay-1+index)%7;Text(listOf("一","二","三","四","五","六","日")[day],Modifier.weight(1f),fontSize=12.sp,color=ledgerColors.muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
            Column(Modifier.fillMaxWidth().padding(horizontal=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                repeat(weekCount){week->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    repeat(7){column->val number=week*7+column-offset+1
                        if(number !in 1..month.lengthOfMonth())Spacer(Modifier.weight(1f).height(cellHeight))
                        else {val date=month.atDay(number);val amount=snapshot.days[date]?:CalendarAmounts();CalendarDateCell(date,amount,mode,date==selected,enabled,scale,Modifier.weight(1f)){choose(date)}}
                    }
                }}
            }
            CalendarModePicker(mode,onMode)
            Text("月收入:${money(snapshot.total.income.toString())}, 月支出:${money(snapshot.total.expense.toString())}, 月结余:${money(snapshot.total.balance.toString())}",Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=6.dp,bottom=14.dp).testTag("calendar-month-totals"),fontSize=12.sp,lineHeight=18.sp,color=ledgerColors.muted)
            if(snapshot.excludedForeign)Text("仅统计 CNY，外币未计入",Modifier.padding(horizontal=16.dp).padding(bottom=12.dp).testTag("calendar-foreign-notice"),fontSize=11.sp,color=ledgerColors.muted)
        }
    }
}

@Composable private fun CalendarModePicker(mode:CalendarAmountMode,onMode:(CalendarAmountMode)->Unit){
    Row(Modifier.fillMaxWidth().height(60.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
        Box(contentAlignment=Alignment.Center){
            // Keep 48dp hit regions while matching the reference's compact 35dp visual track.
            Box(Modifier.matchParentSize().padding(vertical=6.5.dp).clip(CircleShape).background(ledgerColors.background))
            Row(Modifier.padding(horizontal = 4.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                CalendarAmountMode.entries.forEach { value ->
                    val active = mode == value
                    LedgerControl(Modifier.testTag("calendar-mode-${value.name}"), selected = active, onClick = { onMode(value) }) { interactions ->
                        Text(value.label, Modifier.ledgerControlFeedback(interactions)
                            .background(if (active) ledgerColors.surface else Color.Transparent).padding(horizontal = 10.dp, vertical = 3.dp),
                            fontSize = 14.sp, lineHeight = 20.sp, color = if (active) ledgerColors.text else ledgerColors.muted)
                    }
                }
            }
        }
    }
}
private data class CalendarHeatScale(val income:BigInteger,val expense:BigInteger,val balance:BigInteger)
private fun calendarCellBackground(mode:CalendarAmountMode,amount:CalendarAmounts,scale:CalendarHeatScale,selected:Boolean,palette:LedgerPalette):Color {
    if(selected)return palette.selection
    if(mode==CalendarAmountMode.BOTH)return palette.background
    val value=when(mode){CalendarAmountMode.INCOME->amount.income;CalendarAmountMode.EXPENSE->amount.expense;else->amount.balance}
    if(mode==CalendarAmountMode.BALANCE&&value==BigInteger.ZERO)return palette.background
    val peak=when(mode){CalendarAmountMode.INCOME->scale.income;CalendarAmountMode.EXPENSE->scale.expense;else->scale.balance}.max(BigInteger.ONE)
    // Endpoint colours come from the real native reference. Ratios affect paint, never monetary totals.
    val ratio=value.abs().toBigDecimal().divide(peak.toBigDecimal(),6,RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f)
    val red=mode==CalendarAmountMode.EXPENSE||(mode==CalendarAmountMode.BALANCE&&value.signum()<0)
    val low=if(red)palette.heatExpenseLow else palette.heatIncomeLow
    val high=if(red)palette.heatExpenseHigh else palette.heatIncomeHigh
    return Color(red=low.red+(high.red-low.red)*ratio,green=low.green+(high.green-low.green)*ratio,blue=low.blue+(high.blue-low.blue)*ratio,alpha=1f)
}
@Composable private fun CalendarDateCell(date:LocalDate,amount:CalendarAmounts,mode:CalendarAmountMode,isSelected:Boolean,enabled:Boolean,scale:CalendarHeatScale,modifier:Modifier,choose:()->Unit){
    val cellHeight=52.dp*LocalDensity.current.fontScale.coerceAtLeast(1f)
    val info="${date}，收入 ${money(amount.income.toString())} 元，支出 ${money(amount.expense.toString())} 元，结余 ${money(amount.balance.toString())} 元"
    Column(modifier.height(cellHeight).clip(RoundedCornerShape(8.dp)).background(calendarCellBackground(mode,amount,scale,isSelected,ledgerColors)).semantics(mergeDescendants=true){selected=isSelected;contentDescription=info}.testTag("calendar-day-$date").clickable(enabled=enabled,role=Role.Button,onClick=choose),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Text(date.dayOfMonth.toString(),fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.Bold,color=if(isSelected)ledgerColors.accent else ledgerColors.text)
        val lines=when(mode){CalendarAmountMode.BOTH->buildList{if(amount.expense!=BigInteger.ZERO)add(transactionSignedAmount(3,amount.expense.toString(),compact=true) to ledgerColors.expense);if(amount.income!=BigInteger.ZERO)add(transactionSignedAmount(2,amount.income.toString(),compact=true) to ledgerColors.income)};CalendarAmountMode.BALANCE->if(amount.hasAmounts)listOf(transactionSignedAmount(1,amount.balance.toString(),compact=true) to if(amount.balance.signum()<0)ledgerColors.expense else ledgerColors.income)else emptyList();CalendarAmountMode.INCOME->if(amount.income!=BigInteger.ZERO)listOf(transactionSignedAmount(2,amount.income.toString(),compact=true) to ledgerColors.income)else emptyList();CalendarAmountMode.EXPENSE->if(amount.expense!=BigInteger.ZERO)listOf(transactionSignedAmount(3,amount.expense.toString(),compact=true) to ledgerColors.expense)else emptyList()}
        if(lines.isEmpty())Text(remember(date){calendarLunarLabel(date)},fontSize=10.sp,lineHeight=14.sp,color=if(isSelected)ledgerColors.accent else ledgerColors.muted,maxLines=1)
        else lines.forEach{(value,color)->Text(value,fontSize=9.sp,lineHeight=11.sp,color=if(isSelected)ledgerColors.accent else color,maxLines=1,overflow=TextOverflow.Ellipsis)}
    }
}
@Composable private fun CalendarDayHeader(date:LocalDate,amount:CalendarAmounts,today:LocalDate){
    Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp),color=ledgerColors.surface,shape=RoundedCornerShape(topStart=8.dp,topEnd=8.dp)){Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(date.toString().substring(5).replace('-','.')+when(date){today->" 今天";today.minusDays(1)->" 昨天";else->""},fontSize=14.sp,fontWeight=FontWeight.Bold);Text(ledgerDaySummary(amount.income,amount.expense),fontSize=12.sp,fontWeight=FontWeight.Bold)}}
}
