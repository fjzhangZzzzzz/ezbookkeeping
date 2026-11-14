package top.fjzhang233.books

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun TransactionEditor(vm: BooksModel) {
    val original = vm.editTransaction
    var type by rememberSaveable(original?.id()) { mutableIntStateOf(original?.optInt("type") ?: 3) }
    var amount by rememberSaveable(original?.id()) { mutableStateOf(original?.let { money(it.optString("sourceAmount")) } ?: "") }
    var destinationAmount by rememberSaveable { mutableStateOf(original?.let { money(it.optString("destinationAmount", it.optString("sourceAmount"))) } ?: "") }
    var account by rememberSaveable { mutableStateOf(original?.id("sourceAccountId") ?: vm.accounts.firstOrNull { !it.optBoolean("hidden") }?.id().orEmpty()) }
    var destination by rememberSaveable { mutableStateOf(original?.id("destinationAccountId") ?: "") }
    var category by rememberSaveable { mutableStateOf(original?.id("categoryId") ?: "") }
    var comment by rememberSaveable { mutableStateOf(original?.optString("comment") ?: "") }
    var whenText by rememberSaveable { mutableStateOf(LocalDateTime.ofInstant(Instant.ofEpochSecond(original?.optLong("time") ?: Instant.now().epochSecond), ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) }
    var selectedTags by rememberSaveable { mutableStateOf<List<String>>(original?.optJSONArray("tagIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()) }
    val pictures=vm.draftPictures
    var localError by remember { mutableStateOf("") }
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val context = LocalContext.current
    var memoFocused by remember{mutableStateOf(false)}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { vm.draftAttachmentProblem=true;vm.act {
        require(pictures.size < 10) { "每笔最多 10 张图片" }
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use { stream ->
                require(stream != null) { "无法读取所选图片" }; val out = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val n = stream.read(chunk); if (n < 0) break; require(out.size() + n <= 10 * 1024 * 1024) { "图片不能超过 10 MB" }; out.write(chunk, 0, n) }; out.toByteArray()
            }
        }
        val thumbnail=withContext(Dispatchers.IO){draftThumbnail(bytes)}
        val result = vm.api.upload(bytes, context.contentResolver.getType(uri) ?: "image/jpeg")
        val pictureId=result.id("pictureId");require(Regex("[1-9][0-9]*").matches(pictureId)){"服务器未返回有效图片"}
        vm.draftPreviews=vm.draftPreviews+(pictureId to thumbnail)
        vm.draftPictures = vm.draftPictures + pictureId;vm.draftAttachmentProblem=false
    } } }
    val leaves = vm.categories.filter { !it.optBoolean("hidden") && (!it.optBoolean("familyInternalTransfer") || type==4) && it.id("parentId") != "0" && it.optInt("type") == when(type) { 2 -> 1; 4 -> 3; else -> 2 } }

    val feedback=remember{SnackbarHostState()}
    LaunchedEffect(vm.error,vm.notice,localError){val message=localError.ifBlank{vm.error.ifBlank{vm.notice}};if(message.isNotBlank()){if(localError.isBlank()&&vm.error.isBlank())android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show() else feedback.showSnackbar(message,duration=SnackbarDuration.Long);vm.dismissFeedback(message);if(localError==message)localError=""}}
    var sheet by rememberSaveable { mutableStateOf("") }
    var manageCategories by rememberSaveable{mutableStateOf(false)}
    LaunchedEffect(type, leaves) { if(leaves.none { it.id()==category }) category=leaves.firstOrNull()?.id().orEmpty() }
    fun close() { vm.closeTransaction() }
    fun save(again:Boolean) {
        runCatching {
            require(account.isNotBlank()&&category.isNotBlank()) { "请选择账户和分类" }
            if(type==4) require(destination.isNotBlank()&&destination!=account) { "请选择不同的转入账户" }
            val time=LocalDateTime.parse(whenText,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()
            JSONObject().put("type",type).put("categoryId",category).put("time",time).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",entryMinor(amount)).put("destinationAccountId",if(type==4) destination else "0").put("destinationAmount",if(type==4) entryMinor(destinationAmount) else 0).put("hideAmount",original?.optBoolean("hideAmount")?:false).put("tagIds",JSONArray(selectedTags)).put("pictureIds",JSONArray(pictures)).put("comment",comment).also { if(original!=null) it.put("id",original.id()) else it.put("clientSessionId",requestId) }
        }.onSuccess { payload -> vm.saveTransaction(payload,original!=null,again&&original==null) {
            amount="";destinationAmount="";comment="";selectedTags=emptyList();requestId=UUID.randomUUID().toString();localError=""
        } }.onFailure { localError=if(it is java.time.DateTimeException) "请选择有效日期时间" else it.message?:"请检查输入" }
    }
    val currency=vm.accounts.firstOrNull { it.id()==account }?.optString("currency")?:"CNY"
    val usable=!vm.busy&&!vm.draftAttachmentProblem&&(original==null||original.optBoolean("editable",true))
    BackHandler { if(!vm.busy) close() }
    Dialog(onDismissRequest={if(!vm.busy) close()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        val editingMemo=memoFocused&&WindowInsets.isImeVisible
        Surface(Modifier.fillMaxSize()) {
          Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=::close,enabled=!vm.busy) { Icon(Icons.Outlined.Close,"取消记账") }
                    Row(Modifier.weight(1f),horizontalArrangement=Arrangement.Center) { listOf(3 to "支出",2 to "收入",4 to "转账").forEach { (value,label) ->
                        Column(Modifier.width(64.dp).clickable(enabled=!vm.busy) { type=value;category="" }.padding(top=16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(label,color=if(type==value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=if(type==value) FontWeight.SemiBold else FontWeight.Normal)
                            Spacer(Modifier.height(12.dp));HorizontalDivider(Modifier.width(28.dp),thickness=2.dp,color=if(type==value) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                        }
                    }
                    }
                    IconButton(onClick={manageCategories=true},enabled=!vm.busy){Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFBDBDBD)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Add,"分类管理",Modifier.size(22.dp),tint=Color.Black)}}
                }
                if(original!=null) Text("编辑流水",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelMedium)
                if(vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                if(type!=4) {
                    LazyVerticalGrid(GridCells.Fixed(5),Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A1A)).testTag("entry-categories"),contentPadding=PaddingValues(0.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(leaves,key={it.id()}) { item ->
                            val selected=item.id()==category
                            Column(Modifier.fillMaxWidth().height(68.dp).testTag("entry-category-${item.id()}").semantics{this.selected=selected}.clickable(enabled=!vm.busy) { category=item.id() }.padding(top=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(if(selected) Color(0xFF1D2630) else Color.Transparent),contentAlignment=Alignment.Center) {
                                    BuiltinIcon(item,Modifier.size(28.dp),tint=if(selected) MaterialTheme.colorScheme.primary else Color(0xFFAAAAAA))
                                }
                                Spacer(Modifier.height(5.dp));Text(item.optString("name"),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=12.sp,lineHeight=18.sp,color=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    if(leaves.isEmpty()) Text("尚无可用分类，请先在菜单中维护分类。",Modifier.padding(20.dp))
                } else {
                    Column(Modifier.weight(1f).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("账户间转账",style=MaterialTheme.typography.titleLarge)
                        EntryAccountRow("转出账户",vm.accounts.firstOrNull{it.id()==account},onClick={sheet="account"})
                        Icon(Icons.Outlined.South,null,Modifier.align(Alignment.CenterHorizontally))
                        EntryAccountRow("转入账户",vm.accounts.firstOrNull{it.id()==destination},onClick={sheet="destination"})
                        if(vm.accounts.firstOrNull{it.id()==destination}?.optString("currency")!=currency) OutlinedTextField(destinationAmount,{destinationAmount=it},label={Text("转入金额（${vm.accounts.firstOrNull{it.id()==destination}?.optString("currency")?:"请选择账户"}）")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                        else LaunchedEffect(amount) { destinationAmount=amount }
                    }
                }
                val accent=if(type==3) Color(0xFFEB595E) else if(type==2) Color(0xFF25B87A) else MaterialTheme.colorScheme.primary
                Row(Modifier.fillMaxWidth().height(56.dp).padding(start=8.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.width(40.dp).height(48.dp).clickable{sheet="tags"},contentAlignment=Alignment.Center){Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF1A1A1A)),contentAlignment=Alignment.Center){Text("#",fontSize=20.sp,color=Color(0xFFBDBDBD))}}
                    BasicTextField(comment,{if(it.length<=255) comment=it},Modifier.weight(1f).testTag("entry-comment").onFocusChanged{memoFocused=it.isFocused},singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(fontSize=14.sp,color=MaterialTheme.colorScheme.onSurface),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide();focus.clearFocus()}),decorationBox={inner->Box{if(comment.isBlank()) Text("点此输入备注...",color=Color(0xFF666666),fontSize=14.sp);inner()}})
                    Text(amount.ifBlank{"0.0"},Modifier.offset(y=6.dp).testTag("transaction-amount").clickable{keyboard?.hide();focus.clearFocus()}.padding(start=8.dp),fontSize=24.sp,fontFamily=androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.NORMAL)),color=accent,maxLines=1)
                    if(currency!="CNY") Text(currency,Modifier.padding(start=5.dp),fontSize=10.sp)
                }
                if(!editingMemo){
                Row(Modifier.fillMaxWidth().heightIn(min=40.dp).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    EntryPill(vm.accounts.firstOrNull{it.id()==account}?.optString("name")?:"选择账户","entry-account"){sheet="account"}
                    EntryPill(if(whenText.take(10)==java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) "今天 "+whenText.takeLast(5) else whenText.substring(5),"entry-date"){sheet="date"}
                    EntryPill(if(pictures.isEmpty()) "图片" else "图片 ${pictures.size}","entry-pictures",!vm.busy&&pictures.size<10,"图片附件，${pictures.size}/10"){picker.launch("image/*")}
                }
                if(pictures.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    pictures.forEachIndexed { index,id -> InputChip(selected=false,onClick={sheet="pictures"},modifier=Modifier.testTag("entry-attachment-$index"),label={Text("图片 ${index+1}")},trailingIcon={IconButton(onClick={vm.removeDraftPicture(id)},modifier=Modifier.size(24.dp)){Icon(Icons.Outlined.Close,"移除图片 ${index+1}",Modifier.size(16.dp))}}) }
                }
                if(vm.draftAttachmentProblem) { Text(if(vm.busy) "正在上传图片…" else "图片上传未完成，请重选或取消后保存",Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error);if(!vm.busy) TextButton(onClick={vm.draftAttachmentProblem=false}){Text("取消本次失败附件")} }
                val keys=listOf(listOf("1","2","3","⌫"),listOf("4","5","6","−"),listOf("7","8","9","+"),listOf("再记","0",".","保存"))
                Column(Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top=0.dp,bottom=4.dp)) { keys.forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { row.forEach { key ->
                    val saveKey=key=="保存"||key=="再记"
                    val enabled=if(saveKey) usable&&(key!="再记"||original==null) else !vm.busy
                    Box(Modifier.weight(1f).height(52.dp).testTag("entry-key-$key").clickable(enabled=enabled){when(key){"保存"->save(false);"再记"->save(true);else->{amount=entryKey(amount,key);localError=""}}},contentAlignment=Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(43.dp).clip(RoundedCornerShape(8.dp)).background(if(key=="保存") accent else Color(0xFF1A1A1A)),contentAlignment=Alignment.Center){if(key=="⌫") Icon(Icons.Outlined.Backspace,"删除",Modifier.size(20.dp),tint=Color(0xFFBDBDBD)) else Text(key,Modifier.offset(y=(-1).dp),fontSize=if(saveKey) 14.sp else 20.sp,color=if(key=="保存") Color.White else Color(0xFFBDBDBD))}
                    }
                } } } }

                }
            }
            SnackbarHost(feedback,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=72.dp))
          }
        }
        if(sheet=="account"||sheet=="destination") EntryPicker(if(sheet=="account") "选择账户" else "转入账户",vm.accounts.filter{!it.optBoolean("hidden")&&(sheet!="destination"||it.id()!=account)}.map{it.id() to "${it.optString("name")} · ${it.optString("currency")}"},{sheet=""}) { id->if(sheet=="account") account=id else destination=id;sheet="" }

        if(sheet=="tags") LedgerTagPicker(vm.tags,selectedTags,{selectedTags=it},{sheet=""},{manageCategories=true})
        if(sheet=="date") EntryDatePicker(whenText,{sheet=""}){whenText=it;sheet=""}
        if(manageCategories) Dialog(onDismissRequest={manageCategories=false},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={manageCategories=false}){Icon(Icons.Outlined.ArrowBack,"返回记账")};Text("分类与标签",style=MaterialTheme.typography.titleLarge)};CatalogPage(vm)}}}

        if(sheet=="pictures") ModalBottomSheet(onDismissRequest={sheet=""}) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("图片附件",style=MaterialTheme.typography.titleLarge)
            pictures.forEach{id->
                vm.draftPreviews[id]?.let{DraftPicture(it)}?:run{original?.array("pictures")?.firstOrNull{it.id("pictureId")==id}?.let{item->val ext=android.net.Uri.parse(item.optString("originalUrl")).path?.substringAfterLast('.')?.lowercase();if(ext in setOf("jpg","jpeg","png","webp"))Picture(vm,"/api/v1/family/pictures/$id.$ext")}}
                TextButton(onClick={vm.removeDraftPicture(id)}){Text("移除此笔关联")}
            }
            TextButton(onClick={sheet=""}){Text("完成")}
        } }

    }
}

internal fun draftThumbnail(bytes:ByteArray):ByteArray {
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=50_000_000){"图片尺寸无效或过大"}
    var sample=1;while(bounds.outWidth/sample>512||bounds.outHeight/sample>512)sample*=2
    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:throw IllegalArgumentException("无法读取所选图片")
    try{return ByteArrayOutputStream().use{out->check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out));require(out.size()<=512*1024){"图片预览过大"};out.toByteArray()}}finally{bitmap.recycle()}
}
@Composable private fun DraftPicture(bytes:ByteArray){
    val bitmap by produceState<android.graphics.Bitmap?>(null,bytes){value=withContext(Dispatchers.IO){BitmapFactory.decodeByteArray(bytes,0,bytes.size)}}
    bitmap?.let{Image(it.asImageBitmap(),"新上传图片预览",Modifier.fillMaxWidth().heightIn(min=100.dp,max=280.dp))}?:Text("正在加载图片…")
}
@Composable private fun EntryAccountRow(label:String,item:JSONObject?,onClick:()->Unit) { ListItem(headlineContent={Text(item?.optString("name")?:"请选择")},supportingContent={Text(label)},leadingContent={Icon(Icons.Outlined.AccountBalanceWallet,null)},trailingContent={Text(item?.optString("currency")?:"")},modifier=Modifier.clickable(onClick=onClick)) }
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EntryPicker(title:String,values:List<Pair<String,String>>,dismiss:()->Unit,choose:(String)->Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) { Text(title,Modifier.padding(20.dp),style=MaterialTheme.typography.titleLarge);LazyColumn(Modifier.fillMaxWidth().heightIn(max=400.dp).testTag("choice-list")) { items(values,key={it.first}){item->ListItem(headlineContent={Text(item.second)},leadingContent={Icon(Icons.Outlined.AccountBalanceWallet,null)},modifier=Modifier.clickable{choose(item.first)}.heightIn(min=52.dp))} };Spacer(Modifier.height(16.dp)) }
}
@Composable private fun EntryPill(label:String,tag:String,enabled:Boolean=true,description:String=label,action:()->Unit) {
    Box(Modifier.height(48.dp).testTag(tag).semantics{contentDescription=description}.clickable(enabled=enabled,onClick=action),contentAlignment=Alignment.Center){Box(Modifier.height(32.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A1A1A)).padding(horizontal=12.dp),contentAlignment=Alignment.Center){Text(label,fontSize=14.sp,color=Color(0xFFBDBDBD))}}
}
@Composable private fun EntryDatePicker(value:String,dismiss:()->Unit,select:(String)->Unit) {
    val context=LocalContext.current;val density=androidx.compose.ui.platform.LocalDensity.current
    val original=remember(value){LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}
    var year by rememberSaveable{mutableIntStateOf(original.year)};var month by rememberSaveable{mutableIntStateOf(original.monthValue)};var day by rememberSaveable{mutableIntStateOf(original.dayOfMonth)}
    var hour by rememberSaveable{mutableIntStateOf(original.hour)};var minute by rememberSaveable{mutableIntStateOf(original.minute)};var reset by remember{mutableIntStateOf(0)};var calendarMode by rememberSaveable{mutableStateOf(false)}
    fun quick(offset:Long){val date=java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(offset);year=date.year;month=date.monthValue;day=date.dayOfMonth;reset++}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
      Surface(Modifier.fillMaxWidth(.832f).height(if(calendarMode)360.dp else 280.dp),shape=RoundedCornerShape(4.dp),color=Color(0xFF1A1A1A)){
       Column {
        Box(Modifier.fillMaxWidth().height(if(calendarMode)260.dp else 180.dp),contentAlignment=Alignment.Center){
         if(calendarMode) androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.fillMaxSize(),factory={ctx->android.widget.DatePicker(android.view.ContextThemeWrapper(ctx,android.R.style.Theme_Material)).apply{calendarViewShown=true;spinnersShown=false;init(year,month-1,day){_,y,m,d->year=y;month=m+1;day=d}}})
         else key(reset){androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.width(208.dp).fillMaxHeight(),factory={ctx->
            val themed=android.view.ContextThemeWrapper(ctx,android.R.style.Theme_Material);val row=android.widget.LinearLayout(themed);row.orientation=android.widget.LinearLayout.HORIZONTAL
            val years=android.widget.NumberPicker(themed).apply{minValue=1900;maxValue=2100;this.value=year;wrapSelectorWheel=false}
            val months=android.widget.NumberPicker(themed).apply{minValue=1;maxValue=12;displayedValues=(1..12).map{"${it}月"}.toTypedArray();this.value=month}
            val days=android.widget.NumberPicker(themed).apply{minValue=1;maxValue=java.time.YearMonth.of(year,month).lengthOfMonth();this.value=day.coerceAtMost(maxValue)}
            fun adjust(){days.maxValue=java.time.YearMonth.of(year,month).lengthOfMonth();day=day.coerceAtMost(days.maxValue);days.value=day}
            years.setOnValueChangedListener{_,_,v->year=v;adjust()};months.setOnValueChangedListener{_,_,v->month=v;adjust()};days.setOnValueChangedListener{_,_,v->day=v}
            listOf(years,months,days).forEachIndexed{index,picker->if(android.os.Build.VERSION.SDK_INT>=29)picker.textColor=android.graphics.Color.rgb(55,146,250);row.addView(picker,android.widget.LinearLayout.LayoutParams(with(density){64.dp.roundToPx()},android.widget.LinearLayout.LayoutParams.MATCH_PARENT).apply{if(index<2)marginEnd=with(density){8.dp.roundToPx()}})};row
         })}
         IconButton(onClick={calendarMode=!calendarMode},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp)){Icon(Icons.Outlined.ViewDay,"切换日期显示模式",Modifier.size(24.dp),tint=Color(0xFF777777))}
        }
        Row(Modifier.width(258.dp).height(48.dp).align(Alignment.CenterHorizontally),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
            listOf("今","昨","前").forEachIndexed{offset,label->val date=java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(offset.toLong());val selected=year==date.year&&month==date.monthValue&&day==date.dayOfMonth
                Box(Modifier.width(48.dp).height(48.dp).clickable{quick(offset.toLong())},contentAlignment=Alignment.Center){Box(Modifier.width(48.dp).height(32.dp).clip(CircleShape).background(if(selected)Color(0xFF1D4061)else Color(0xFF242424)),contentAlignment=Alignment.Center){Text(label,fontSize=14.sp,color=if(selected)Color(0xFF3792FA)else Color(0xFFBDBDBD))}}
            }
            Box(Modifier.width(90.dp).height(48.dp).clickable{android.app.TimePickerDialog(android.view.ContextThemeWrapper(context,android.R.style.Theme_Material_Dialog_Alert),{_,h,m->hour=h;minute=m},hour,minute,true).show()},contentAlignment=Alignment.Center){Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF242424)),contentAlignment=Alignment.Center){Text("时间 %02d:%02d".format(hour,minute),fontSize=14.sp)}}
        }
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=8.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically){TextButton(onClick=dismiss,modifier=Modifier.width(64.dp),colors=ButtonDefaults.textButtonColors(contentColor=Color(0xFF777777))){Text("取消",fontSize=14.sp)};Spacer(Modifier.width(8.dp));TextButton(onClick={select(LocalDateTime.of(year,month,day,hour,minute).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))},modifier=Modifier.width(64.dp)){Text("确定",fontSize=14.sp,fontWeight=FontWeight.Bold)}}
       }
      }
    }
}
internal fun entryKey(value:String,key:String):String {
    if(key=="⌫") return value.dropLast(1)
    if(value.length>=30) return value
    if(key in listOf("+","−")) return if(value.isEmpty()) value else if(value.last()=='+'||value.last()=='−') value.dropLast(1)+key else value+key
    val operand=value.substringAfterLast('+').substringAfterLast('−')
    if(key==".") return if('.' in operand) value else value+if(operand.isEmpty()) "0." else "."
    if(key.length!=1||!key[0].isDigit()||('.' in operand&&operand.substringAfter('.').length>=2)) return value
    return if(operand=="0") value.dropLast(1)+key else value+key
}
internal fun entryMinor(value:String):Long {
    require(value.isNotBlank()&&value.last()!='+'&&value.last()!='−') { "请输入完整金额" }
    var sum=java.math.BigDecimal.ZERO;var sign=1;var part=StringBuilder()
    for(c in value+"+") { if(c=='+'||c=='−') { require(part.isNotEmpty());sum=sum.add(java.math.BigDecimal(part.toString()).multiply(java.math.BigDecimal(sign)));part=StringBuilder();sign=if(c=='−') -1 else 1 } else part.append(c) }
    return sum.movePointRight(2).longValueExact().also { require(it>0) { "金额必须大于零" } }
}
