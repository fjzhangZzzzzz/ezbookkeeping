package top.fjzhang233.books

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigInteger
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.abs

/** Exact integer ticks; converting ratios to Float is limited to paint coordinates. */
internal fun reportAxisTicks(peak:BigInteger):List<BigInteger>{
    val peakValue=peak.abs().max(BigInteger.ONE);var magnitude=BigInteger.ONE
    while(true){for(factor in listOf(1L,2L,5L)){val step=magnitude*BigInteger.valueOf(factor);val count=(peakValue+step-BigInteger.ONE)/step;if(count<=BigInteger.valueOf(6))return (0..count.toInt()).map{step*BigInteger.valueOf(it.toLong())}};magnitude*=BigInteger.TEN}
}
internal fun reportChartPoints(data:ReportSnapshot):List<ReportDay> = if(data.period.yearly&&!data.period.custom)
    data.days.groupBy{it.date.monthValue}.values.map{list->ReportDay(list.first().date,list.fold(BigInteger.ZERO){s,r->s+r.income},list.fold(BigInteger.ZERO){s,r->s+r.expense},list.any{it.active})}
    else data.days
internal fun reportPointIndex(x:Float,left:Float,right:Float,count:Int):Int? =
    if(!x.isFinite()||right<=left||count<=0||x<left||x>right)null else (((x-left)/(right-left))*count).toInt().coerceIn(0,count-1)
internal fun reportPointLabel(data:ReportSnapshot,row:ReportDay):String = when {
    data.period.yearly&&!data.period.custom->row.date.toString().take(7)
    data.period.first.year!=data.period.until.minusDays(1).year->row.date.toString()
    else->row.date.toString().substring(5)
}
internal fun reportPointDescription(data:ReportSnapshot,row:ReportDay):List<String> = listOf(reportPointLabel(data,row),"收入: ${money(row.income.toString())}","支出: ${money(row.expense.toString())}","结余: ${money((row.income-row.expense).toString())}")
internal fun reportRotationDelta(from:Float,to:Float):Float=((to-from+540f)%360f)-180f
internal fun reportNormalizedAngle(value:Float):Float=((value%360f)+360f)%360f
internal fun reportDonutIndex(amounts:List<BigInteger>,angle:Float):Int? {
    if(!angle.isFinite()||amounts.isEmpty()||amounts.any{it.signum()<=0})return null
    val total=amounts.fold(BigInteger.ZERO,BigInteger::add);val position=reportNormalizedAngle(angle).toDouble()/360
    var sum=0.0
    amounts.forEachIndexed{index,value->sum+=value.toBigDecimal().divide(total.toBigDecimal(),18,RoundingMode.HALF_EVEN).toDouble();if(position<sum||index==amounts.lastIndex)return index}
    return null
}
/** Signed monetary values stay exact; all series use the active theme's semantic paint colours. */
internal fun reportChartSeries(data:ReportSnapshot,type:Int,palette:LedgerPalette):List<Pair<List<BigInteger>,Color>>{
    val annual=data.period.yearly&&!data.period.custom;val rows=reportChartPoints(data)
    return when{type==2->listOf(rows.map{it.expense} to palette.expense);type==3->listOf(rows.map{it.income} to palette.income);type==4&&annual->listOf(rows.map{it.income-it.expense} to palette.accent);else->listOf(rows.map{it.expense} to palette.expense,rows.map{it.income} to palette.income)+(if(annual)listOf(rows.map{it.income-it.expense} to palette.accent)else emptyList())}
}
@Composable internal fun ReportChart(data:ReportSnapshot,type:Int,lines:Boolean,chartHeight:Dp=170.dp){
    val palette=ledgerColors
    val annual=data.period.yearly&&!data.period.custom;val rows=reportChartPoints(data)
    val series=reportChartSeries(data,type,palette)
    val peak=series.flatMap{it.first}.maxOfOrNull{it.abs()}?.max(BigInteger.ONE)?:BigInteger.ONE
    val ticks=reportAxisTicks(peak);val axisTop=ticks.last();val negative=series.any{it.first.any{v->v.signum()<0}}
    var selected by rememberSaveable(data.period,data.days){mutableStateOf<Int?>(null)};var selectedSeries by remember{mutableIntStateOf(0)}
    var canvasSize by remember{mutableStateOf(IntSize.Zero)};var tipSize by remember{mutableStateOf(IntSize.Zero)};val density=LocalDensity.current
    fun move(delta:Int):Boolean{if(rows.isEmpty())return false;selected=((selected?:if(delta>0)-1 else 1)+delta).coerceIn(0,rows.lastIndex);selectedSeries=0;return true}
    Column(Modifier.fillMaxWidth()){
    Box(Modifier.fillMaxWidth()){
    Canvas(Modifier.fillMaxWidth().height(chartHeight+24.dp).onSizeChanged{canvasSize=it}.testTag("report-interactive-chart").semantics{
        contentDescription="${data.period.label}真实收支${if(lines)"折线" else "柱状"}图"
        stateDescription=selected?.let{rows.getOrNull(it)}?.let{reportPointDescription(data,it).joinToString("，")}?:"未选择日期"
        onClick(label="查看日期金额"){move(1)}
        customActions=listOf(CustomAccessibilityAction("上一日期"){move(-1)},CustomAccessibilityAction("下一日期"){move(1)},CustomAccessibilityAction("关闭金额提示"){selected=null;true})
    }.focusable().pointerInput(rows,type,negative,axisTop){
        fun select(position:Offset){
            val left=28.dp.toPx();val right=size.width-2.dp.toPx();val top=8.dp.toPx();val bottom=size.height-22.dp.toPx()
            val index=if(position.y in top..bottom)reportPointIndex(position.x,left,right,rows.size)else null
            selected=index
            if(index!=null){val baseline=if(negative)(top+bottom)/2 else bottom;val scale=baseline-top
                selectedSeries=series.indices.minByOrNull{abs(baseline-reportRatio(series[it].first[index],axisTop)*scale-position.y)}?:0}
        }
        detectTapGestures(onTap=::select)
    }.pointerInput(rows,type,negative,axisTop){
        detectHorizontalDragGestures(onDragStart={position->selected=reportPointIndex(position.x,28.dp.toPx(),size.width-2.dp.toPx(),rows.size)},onHorizontalDrag={change,_->change.consume();selected=reportPointIndex(change.position.x,28.dp.toPx(),size.width-2.dp.toPx(),rows.size)})
    }){
        val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=palette.muted.toArgb();textSize=10.sp.toPx();textAlign=Paint.Align.RIGHT}
        val left=28.dp.toPx();val right=size.width-2.dp.toPx();val top=8.dp.toPx();val bottom=size.height-22.dp.toPx();val baseline=if(negative)(top+bottom)/2 else bottom;val scale=baseline-top
        ticks.forEach{tick->val ratio=reportRatio(tick,axisTop);val y=baseline-ratio*scale
            drawContext.canvas.nativeCanvas.drawText(money(tick.toString()),left-4.dp.toPx(),y+3.dp.toPx(),labelPaint)
            if(negative&&tick.signum()>0)drawContext.canvas.nativeCanvas.drawText(money(tick.negate().toString()),left-4.dp.toPx(),baseline+ratio*scale+3.dp.toPx(),labelPaint)}
        val axis=palette.outline;drawLine(axis,Offset(left,top),Offset(left,bottom));drawLine(axis,Offset(left,baseline),Offset(right,baseline))
        val step=(right-left)/rows.size.coerceAtLeast(1)
        series.forEachIndexed{seriesIndex,(values,color)->val path=Path();values.forEachIndexed{index,value->val x=left+(index+.5f)*step;val y=baseline-reportRatio(value,axisTop)*scale
            if(lines){if(index==0)path.moveTo(x,y)else path.lineTo(x,y);if(values.size==1)drawCircle(color,3.dp.toPx(),Offset(x,y))}
            else if(value.signum()!=0)drawLine(color,Offset(x+(seriesIndex-(series.size-1)/2f)*3.dp.toPx(),baseline),Offset(x+(seriesIndex-(series.size-1)/2f)*3.dp.toPx(),y),strokeWidth=if(selected==index)5.dp.toPx()else 3.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
        };if(lines)drawPath(path,color,style=Stroke(2.dp.toPx()))}
        selected?.takeIf{it in rows.indices}?.let{index->if(lines){val x=left+(index+.5f)*step;val value=series[selectedSeries.coerceIn(series.indices)].first[index];val y=baseline-reportRatio(value,axisTop)*scale
            val highlight=palette.accent;drawLine(highlight,Offset(x,top),Offset(x,bottom),1.dp.toPx());drawLine(highlight,Offset(left,y),Offset(right,y),1.dp.toPx())}}
        val start=if(annual)"1月"else data.period.first.toString().substring(5);val middle=if(annual)"6月"else data.period.first.plusDays(((data.period.days-1)/2).toLong()).toString().substring(5);val end=if(annual)"12月"else data.period.until.minusDays(1).toString().substring(5)
        labelPaint.textAlign=Paint.Align.LEFT;drawContext.canvas.nativeCanvas.drawText(start,left,size.height-4.dp.toPx(),labelPaint)
        if(rows.size>1){labelPaint.textAlign=Paint.Align.CENTER;drawContext.canvas.nativeCanvas.drawText(middle,(left+right)/2,size.height-4.dp.toPx(),labelPaint);labelPaint.textAlign=Paint.Align.RIGHT;drawContext.canvas.nativeCanvas.drawText(end,right,size.height-4.dp.toPx(),labelPaint)}
    }
    selected?.let{rows.getOrNull(it)}?.let{row->
        val index=checkNotNull(selected);val left=with(density){28.dp.toPx()};val right=canvasSize.width-with(density){2.dp.toPx()};val top=with(density){8.dp.toPx()};val bottom=canvasSize.height-with(density){22.dp.toPx()};val baseline=if(negative)(top+bottom)/2 else bottom
        val x=left+(index+.5f)*(right-left)/rows.size.coerceAtLeast(1);val y=baseline-reportRatio(series[selectedSeries.coerceIn(series.indices)].first[index],axisTop)*(baseline-top)
        Column(Modifier.offset{IntOffset((x-tipSize.width/2).toInt().coerceIn(0,(canvasSize.width-tipSize.width).coerceAtLeast(0)),(y-tipSize.height-with(density){8.dp.toPx()}).toInt().coerceAtLeast(0))}.onSizeChanged{tipSize=it}.background(Color.Black,RoundedCornerShape(4.dp)).padding(horizontal=6.dp,vertical=4.dp).testTag("report-chart-tooltip").semantics{liveRegion=LiveRegionMode.Polite}){
            reportPointDescription(data,row).forEach{Text(it,fontSize=12.sp,lineHeight=16.sp,color=Color.White)}
        }
    }
    }
    if(negative)Text("负数绘于零轴下方；正负净额均计入统计",fontSize=10.sp,lineHeight=12.sp,color=reportMuted)
    }
}
@Composable internal fun ReportDonut(ranked:List<ReportCategory>,centerLabel:String,diameter:Dp=172.dp,height:Dp=216.dp){
    val palette=ledgerColors;val ringColors=reportPalette
    val positive=ranked.filter{it.amount.signum()>0};val total=positive.fold(BigInteger.ZERO){s,r->s+r.amount}
    val keys=positive.map{it.category.id()};var rotation by rememberSaveable(keys){mutableFloatStateOf(0f)};var selected by rememberSaveable(keys){mutableStateOf<String?>(null)}
    fun move(delta:Int):Boolean{if(positive.isEmpty())return false;val old=positive.indexOfFirst{it.category.id()==selected};selected=positive[if(old<0){if(delta>0)0 else positive.lastIndex}else (old+delta+positive.size)%positive.size].category.id();return true}
    val selectedRow=positive.firstOrNull{it.category.id()==selected}
    Canvas(Modifier.fillMaxWidth().height(height).testTag("report-interactive-donut").semantics{
        contentDescription=if(positive.isEmpty())"暂无分类收支"else "$centerLabel，分类占比，可拖动旋转"
        stateDescription=selectedRow?.let{"${it.category.optString("name")}，${money(it.amount.toString())}"}?:"未选择分类"
        onClick(label="选择分类"){move(1)}
        customActions=listOf(CustomAccessibilityAction("上一分类"){move(-1)},CustomAccessibilityAction("下一分类"){move(1)},CustomAccessibilityAction("顺时针旋转"){rotation=reportNormalizedAngle(rotation+45);true},CustomAccessibilityAction("逆时针旋转"){rotation=reportNormalizedAngle(rotation-45);true})
    }.focusable().pointerInput(positive,diameter){
        detectTapGestures{position->val center=Offset(size.width/2f,size.height/2f);val distance=(position-center).getDistance();val outer=diameter.toPx()/2
            selected=if(distance in outer*.55f..outer+8.dp.toPx()){val angle=Math.toDegrees(atan2((position.y-center.y).toDouble(),(position.x-center.x).toDouble())).toFloat();reportDonutIndex(positive.map{it.amount},angle-rotation)?.let{positive[it].category.id()}}else null}
    }.pointerInput(positive,diameter){
        awaitEachGesture{
            val down=awaitFirstDown(requireUnconsumed=false);val center=Offset(size.width/2f,size.height/2f);val outer=diameter.toPx()/2
            if((down.position-center).getDistance() !in outer*.55f..outer+8.dp.toPx())return@awaitEachGesture
            fun angle(position:Offset)=Math.toDegrees(atan2((position.y-center.y).toDouble(),(position.x-center.x).toDouble())).toFloat()
            var last=angle(down.position)
            val start=awaitTouchSlopOrCancellation(down.id){change,_->change.consume()}
            if(start!=null){rotation=reportNormalizedAngle(rotation+reportRotationDelta(last,angle(start.position)));last=angle(start.position)
                drag(start.id){change->val next=angle(change.position);rotation=reportNormalizedAngle(rotation+reportRotationDelta(last,next));last=next;change.consume()}}
        }
    }){
        val center=Offset(size.width/2,size.height/2);val outer=diameter.toPx()/2;val stroke=outer*.45f;val inner=outer-stroke
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=10.sp.toPx()};var start=rotation
        positive.forEachIndexed{index,row->val ratio=reportRatio(row.amount,total);val sweep=ratio*360;val color=ringColors[index%ringColors.size];val edge=outer+if(row.category.id()==selected)6.dp.toPx()else 0f;val width=edge-inner;val middle=(edge+inner)/2
            drawArc(color,start,sweep,false,topLeft=Offset(center.x-middle,center.y-middle),size=Size(middle*2,middle*2),style=Stroke(width))
            val highlight=inner+2.dp.toPx();drawArc(androidx.compose.ui.graphics.lerp(color,Color.White,.4f),start,sweep,false,topLeft=Offset(center.x-highlight,center.y-highlight),size=Size(highlight*2,highlight*2),style=Stroke(3.dp.toPx()))
            if(ratio>=.02f){val angle=(start+sweep/2)*Math.PI/180;val side=if(cos(angle)>=0)1 else -1;val a=Offset(center.x+(edge+2.dp.toPx())*cos(angle).toFloat(),center.y+(edge+2.dp.toPx())*sin(angle).toFloat());val b=Offset(center.x+(edge+8.dp.toPx())*cos(angle).toFloat(),center.y+(edge+8.dp.toPx())*sin(angle).toFloat());val textX=center.x+side*(outer+14.dp.toPx());drawLine(color,a,b,1.dp.toPx());drawLine(color,b,Offset(textX-side*3.dp.toPx(),b.y),1.dp.toPx())
                paint.color=android.graphics.Color.argb(255,(color.red*255).toInt(),(color.green*255).toInt(),(color.blue*255).toInt());paint.textAlign=if(side>0)Paint.Align.LEFT else Paint.Align.RIGHT
                val name=row.category.optString("name").let{if(it.length>3)it.take(3)+"…"else it};val percent=row.amount.toBigDecimal().multiply(BigDecimal(100)).divide(total.toBigDecimal(),1,RoundingMode.HALF_EVEN).toPlainString()+"%"
                drawContext.canvas.nativeCanvas.drawText("$name $percent",textX,b.y+paint.textSize/3,paint)};start+=sweep
        }
        if(centerLabel.isNotEmpty()||positive.isEmpty()||selectedRow!=null){paint.color=palette.muted.toArgb();paint.textSize=12.sp.toPx();paint.textAlign=Paint.Align.CENTER
            val label=selectedRow?.let{it.category.optString("name").let{name->if(name.length>6)name.take(6)+"…"else name}+" "+money(it.amount.toString())}?:if(positive.isEmpty())"暂无收支"else centerLabel
            while(paint.measureText(label)>inner*1.9f&&paint.textSize>9.sp.toPx())paint.textSize-=1
            drawContext.canvas.nativeCanvas.drawText(label,center.x,center.y+paint.textSize/3,paint)}
    }
    if(positive.isNotEmpty())Text("可拖动旋转；小于2%的分类不显示外侧标签",fontSize=10.sp,lineHeight=14.sp,color=reportMuted)
}
