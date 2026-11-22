package top.fjzhang233.books

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.toArgb
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
@Composable internal fun TransactionEditor(vm: BooksModel, manageCatalog: (Int) -> Unit) {
    val original = vm.editTransaction
    val initial = original ?: vm.copiedTransaction ?: vm.restoredTransaction
    val editLocked=vm.busy || vm.transactionNeedsConfirmation
    var type by rememberSaveable(original?.id()) { mutableIntStateOf(initial?.optInt("type") ?: 3) }
    var amount by rememberSaveable(original?.id()) { mutableStateOf(initial?.let { money(it.optString("sourceAmount")) } ?: "") }
    var destinationAmount by rememberSaveable { mutableStateOf(initial?.let { money(it.optString("destinationAmount", it.optString("sourceAmount"))) } ?: "") }
    var account by rememberSaveable { mutableStateOf(initial?.id("sourceAccountId") ?: vm.preferredAccountId()) }
    var destination by rememberSaveable { mutableStateOf(initial?.id("destinationAccountId") ?: "") }
    var category by rememberSaveable { mutableStateOf(initial?.id("categoryId") ?: "") }
    var originalTypeSelection by rememberSaveable(original?.id()) { mutableStateOf(original?.id("categoryId") ?: "") }
    var copiedTypeSelection by rememberSaveable(vm.copiedTransaction?.id()) { mutableStateOf(vm.copiedTransaction?.id("categoryId") ?: "") }
    var copiedTypeNeedsChoice by rememberSaveable(vm.copiedTransaction?.id()) { mutableStateOf(false) }
    var comment by rememberSaveable { mutableStateOf(initial?.optString("comment") ?: "") }
    var whenText by rememberSaveable { mutableStateOf(LocalDateTime.ofInstant(Instant.ofEpochSecond(initial?.optLong("time") ?: (vm.newTransactionDay?.let{java.time.LocalDate.parse(it).atTime(java.time.LocalTime.now(ZoneId.of("Asia/Shanghai"))).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()} ?: Instant.now().epochSecond)), ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) }
    var selectedTags by rememberSaveable { mutableStateOf<List<String>>(initial?.optJSONArray("tagIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()) }
    val pictures=vm.draftPictures
    var localError by remember { mutableStateOf("") }
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val context = LocalContext.current
    var memoFocused by remember{mutableStateOf(false)}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { vm.draftAttachmentProblem=true;vm.act {
        require(!vm.transactionNeedsConfirmation) { "请先确认刚才那笔保存的结果" };require(pictures.size < 10) { "每笔最多 10 张图片" }
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use { stream ->
                require(stream != null) { "无法读取所选图片" }; val out = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val n = stream.read(chunk); if (n < 0) break; require(out.size() + n <= 10 * 1024 * 1024) { "图片不能超过 10 MB" }; out.write(chunk, 0, n) }; out.toByteArray()
            }
        }
        val thumbnail=withContext(Dispatchers.IO){draftThumbnail(bytes)}
        val result = vm.api.upload(bytes, context.contentResolver.getType(uri) ?: "image/jpeg")
        val pictureId=result.id("pictureId");require(Regex("[1-9][0-9]*").matches(pictureId)){"服务器未返回有效图片"}
        vm.registerDraftPicture(pictureId,thumbnail);vm.draftAttachmentProblem=false
    } } }
    val leaves = entryVisibleCategories(vm.categories, type)
    val keepOriginalCategory = entryPreservesOriginalCategory(original, category, type)
    fun categoryDescription(id:String):String {
        val child=vm.categories.firstOrNull{it.id()==id} ?: return ""
        val parent=vm.categories.firstOrNull{it.id()==child.id("parentId")}
        return child.id("parentId")+"|"+parent?.optString("name").orEmpty()+" / "+child.optString("name")
    }
    var observedCategoryId by rememberSaveable { mutableStateOf("") }
    var observedCategoryDescription by rememberSaveable { mutableStateOf("") }
    var changedCategoryDescription by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(category, vm.categories) {
        val description=categoryDescription(category)
        if(category!=observedCategoryId) {
            observedCategoryId=category; observedCategoryDescription=description; changedCategoryDescription=""
        } else if(description.isNotBlank() && observedCategoryDescription.isNotBlank() && description!=observedCategoryDescription) {
            changedCategoryDescription=description
        } else if(observedCategoryDescription.isBlank()) observedCategoryDescription=description
    }
    var requireExplicitCategory by rememberSaveable(vm.copiedTransaction?.id()) { mutableStateOf(false) }
    var hasEstablishedCategory by rememberSaveable(original?.id(), vm.copiedTransaction?.id()) { mutableStateOf(category.isNotBlank() && category != "0") }

    val feedback=remember{SnackbarHostState()}
    LaunchedEffect(vm.error,vm.notice,localError){val message=localError.ifBlank{vm.error.ifBlank{vm.notice}};if(message.isNotBlank()){if(localError.isBlank()&&vm.error.isBlank())android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show() else feedback.showSnackbar(message,duration=SnackbarDuration.Long);vm.dismissFeedback(message);if(localError==message)localError=""}}
    var sheet by rememberSaveable { mutableStateOf("") }
    var accountAssetPicker by rememberSaveable{mutableStateOf(false)}
    var accountPresetKey by rememberSaveable{mutableStateOf("")}
    LaunchedEffect(type, leaves, original?.id(), vm.copiedTransaction?.id()) {
        if (!keepOriginalCategory && category.isNotBlank() && category != "0" && leaves.none { it.id() == category } && hasEstablishedCategory) {
            category=""; requireExplicitCategory=true
            if (original == null && vm.copiedTransaction?.optInt("type") == type) { copiedTypeSelection=""; copiedTypeNeedsChoice=true }
        } else if (!keepOriginalCategory && category.isBlank() && !requireExplicitCategory) {
            val defaultCategory=entryDefaultCategory(leaves)
            if (defaultCategory.isNotBlank()) { category=defaultCategory; hasEstablishedCategory=true }
        }
    }
    fun selectCategory(item:JSONObject) {
        category=item.id(); requireExplicitCategory=false; hasEstablishedCategory=true
        if (original != null && type == original.optInt("type")) originalTypeSelection=category
        if (original == null && vm.copiedTransaction?.optInt("type") == type) { copiedTypeSelection=category; copiedTypeNeedsChoice=false }
    }
    fun close() { vm.closeTransaction() }
    fun saved(){amount="";destinationAmount="";comment="";selectedTags=emptyList();requestId=UUID.randomUUID().toString();localError=""}

    fun save(again:Boolean) {
        runCatching {
            val currentDescription=categoryDescription(category)
            require(!vm.categoryReviewRequired) { "请先重新确认分类" }
            require(changedCategoryDescription.isBlank() && (observedCategoryId!=category || observedCategoryDescription.isBlank() || currentDescription==observedCategoryDescription)) { "请先确认更新后的分类" }
            require(account.isNotBlank() && category.isNotBlank() && (keepOriginalCategory || leaves.any { it.id()==category })) { "请选择账户和分类" }
            if(type==4) require(destination.isNotBlank()&&destination!=account) { "请选择不同的转入账户" }
            val time=LocalDateTime.parse(whenText,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()
            JSONObject().put("type",type).put("categoryId",category).put("time",time).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",entryMinor(amount,type)).put("destinationAccountId",if(type==4) destination else "0").put("destinationAmount",if(type==4) entryMinor(destinationAmount) else 0).put("hideAmount",initial?.optBoolean("hideAmount")?:false).put("tagIds",JSONArray(selectedTags)).put("pictureIds",JSONArray(pictures)).put("comment",comment).also { vm.categoryRevision?.let { revision -> it.put("expectedCategoryRevision",revision) }; if(original!=null) it.put("id",original.id()) else it.put("clientSessionId",requestId) }
        }.onSuccess { payload -> vm.saveTransaction(payload,original!=null,again&&original==null) {
            saved()
        } }.onFailure { localError=if(it is java.time.DateTimeException) "请选择有效日期时间" else it.message?:"请检查输入" }
    }
    val currency=vm.accounts.firstOrNull { it.id()==account }?.optString("currency")?:"CNY"
    val usable=!editLocked&&!vm.draftAttachmentProblem&&(original==null||original.optBoolean("editable",true))
        val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        val editingMemo=memoFocused&&WindowInsets.isImeVisible
        Surface(Modifier.fillMaxSize()) {
          Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=::close,enabled=!editLocked) { Icon(Icons.Outlined.Close,"取消记账") }
                    Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.Center) {
                        listOf(3 to "支出", 2 to "收入", 4 to "转账").forEach { (value, label) ->
                            val active = type == value
                            LedgerControl(Modifier.width(64.dp), selected = active, enabled = !editLocked, onClick = {
                                if (type != value) {
                                    type = value
                                    requireExplicitCategory = original == null && vm.copiedTransaction?.optInt("type") == value && copiedTypeNeedsChoice
                                    category = when {
                                        original != null && original.optInt("type") == value -> originalTypeSelection
                                        original == null && vm.copiedTransaction?.optInt("type") == value -> copiedTypeSelection
                                        else -> ""
                                    }
                                    hasEstablishedCategory = category.isNotBlank() && category != "0"
                                }
                            }) { interactions ->
                                Column(Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, Modifier.ledgerControlFeedback(interactions).padding(horizontal = 8.dp),
                                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider(Modifier.width(28.dp), thickness = 2.dp,
                                        color = if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                                }
                            }
                        }
                    }
                    IconButton(onClick={manageCatalog(0)},enabled=!editLocked){Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(ledgerColors.text),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Add,"分类管理",Modifier.size(22.dp),tint=ledgerColors.background)}}
                }
                if(vm.copiedTransaction!=null) Text("复制流水 · 保存后生成新记录",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelMedium)
                if(original!=null) Text("编辑流水",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelMedium)
                if(vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                if(type!=4) {
                    if (requireExplicitCategory) Text("所选分类不可用，请选择新的分类", Modifier.padding(horizontal=16.dp, vertical=8.dp).testTag("entry-copy-category-required"), style=MaterialTheme.typography.bodySmall)
                    if (keepOriginalCategory && leaves.none { it.id()==category }) {
                        val name = vm.categories.firstOrNull { it.id()==category }?.optString("name")?.takeIf { it.isNotBlank() } ?: original?.optJSONObject("category")?.optString("name")?.takeIf { it.isNotBlank() } ?: "原分类"
                        Text("${name}（已隐藏；保存时保留）", Modifier.padding(horizontal=16.dp, vertical=8.dp).testTag("entry-hidden-original-category"), style=MaterialTheme.typography.bodySmall)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("entry-categories")) {
                        EntryCategoryPicker(
                            categories = vm.categories,
                            transactionType = type,
                            selectedId = category,
                            enabled = !editLocked,
                            onSelect = ::selectCategory,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Column(Modifier.weight(1f).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("账户间转账",style=MaterialTheme.typography.titleLarge)
                        if (keepOriginalCategory && leaves.none { it.id()==category }) Text("原转账分类（已隐藏；保存时保留）", Modifier.testTag("entry-hidden-original-category"))
                        if (requireExplicitCategory) Text("所选转账分类不可用，请选择新的分类", Modifier.testTag("entry-copy-category-required"))
                        if (leaves.isEmpty() && !keepOriginalCategory) Text("暂无可用转账分类，请先新增或显示转账分类", Modifier.testTag("entry-transfer-category-empty"))
                        if (leaves.isNotEmpty()) {
                            var choosing by remember { mutableStateOf(false) }
                            OutlinedButton(onClick={choosing=true},enabled=!editLocked,modifier=Modifier.testTag("entry-transfer-category")) {
                                Text("转账分类："+(leaves.firstOrNull { it.id()==category }?.optString("name") ?: "请选择"))
                            }
                            if (choosing) AlertDialog(onDismissRequest={choosing=false},title={Text("选择转账分类")},text={
                                LazyColumn(Modifier.heightIn(max=320.dp)) { items(leaves,key={it.id()}) { item ->
                                    val parent=vm.categories.firstOrNull { it.id()==item.id("parentId") }?.optString("name").orEmpty()
                                    TextButton(onClick={selectCategory(item);choosing=false},modifier=Modifier.fillMaxWidth().testTag("entry-transfer-category-${item.id()}")) { Text(listOf(parent,item.optString("name")).filter { it.isNotBlank() }.joinToString(" / ")) }
                                } }
                            },confirmButton={TextButton(onClick={choosing=false}){Text("取消")}})
                        }
                        EntryAccountRow("转出账户",vm.accounts.firstOrNull{it.id()==account},enabled=!editLocked,onClick={sheet="account"})
                        Icon(Icons.Outlined.South,null,Modifier.align(Alignment.CenterHorizontally))
                        EntryAccountRow("转入账户",vm.accounts.firstOrNull{it.id()==destination},enabled=!editLocked,onClick={sheet="destination"})
                        if(vm.accounts.firstOrNull{it.id()==destination}?.optString("currency")!=currency) OutlinedTextField(destinationAmount,{destinationAmount=it},label={Text("转入金额（${vm.accounts.firstOrNull{it.id()==destination}?.optString("currency")?:"请选择账户"}）")},singleLine=true,enabled=!editLocked,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                        else LaunchedEffect(amount) { destinationAmount=amount }
                    }
                }
                val accent=if(type==3) ledgerColors.expense else if(type==2) ledgerColors.income else MaterialTheme.colorScheme.primary
                Row(Modifier.fillMaxWidth().height(56.dp).padding(start=8.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    LedgerControl(Modifier.testTag("entry-tags-open"), enabled = !editLocked, onClick = { sheet = "tags" }) { interactions ->
                        Box(Modifier.size(32.dp).ledgerControlFeedback(interactions).background(ledgerColors.controlSurface), contentAlignment = Alignment.Center) {
                            Text("#", fontSize = 20.sp, color = ledgerColors.text)
                        }
                    }
                    BasicTextField(comment,{if(it.length<=255) comment=it},Modifier.weight(1f).testTag("entry-comment").onFocusChanged{memoFocused=it.isFocused},singleLine=true,enabled=!editLocked,textStyle=androidx.compose.ui.text.TextStyle(fontSize=14.sp,color=MaterialTheme.colorScheme.onSurface),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide();focus.clearFocus()}),decorationBox={inner->Box{if(comment.isBlank()) Text("点此输入备注...",color=ledgerColors.muted,fontSize=14.sp);inner()}})
                    Text(amount.ifBlank{"0.0"},Modifier.offset(y=6.dp).testTag("transaction-amount").clickable{keyboard?.hide();focus.clearFocus()}.padding(start=8.dp),fontSize=24.sp,fontFamily=androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.NORMAL)),color=accent,maxLines=1)
                    if(currency!="CNY") Text(currency,Modifier.padding(start=5.dp),fontSize=10.sp)
                }
                if(!editingMemo){
                Row(Modifier.fillMaxWidth().heightIn(min=40.dp).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    EntryPill(vm.accounts.firstOrNull{it.id()==account}?.optString("name")?:"选择账户","entry-account",enabled=!editLocked){sheet="account"}
                    EntryPill(if(whenText.take(10)==java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) "今天 "+whenText.takeLast(5) else whenText.substring(5),"entry-date",enabled=!editLocked){sheet="date"}
                    EntryPill(if(pictures.isEmpty()) "图片" else "图片 ${pictures.size}","entry-pictures",!editLocked&&pictures.size<10,"图片附件，${pictures.size}/10"){picker.launch("image/*")}
                }
                if(pictures.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    pictures.forEachIndexed { index,id -> InputChip(selected=false,onClick={sheet="pictures"},modifier=Modifier.testTag("entry-attachment-$index"),label={Text("图片 ${index+1}")},trailingIcon={IconButton(onClick={vm.removeDraftPicture(id)},enabled=!editLocked,modifier=Modifier.size(24.dp)){Icon(Icons.Outlined.Close,"移除图片 ${index+1}",Modifier.size(16.dp))}}) }
                }
                if(vm.draftAttachmentProblem) { Text(if(vm.busy) "正在上传图片…" else "图片上传未完成，请重选或取消后保存",Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error);if(!editLocked) TextButton(onClick={vm.draftAttachmentProblem=false}){Text("取消本次失败附件")} }
                val keys=listOf(listOf("1","2","3","⌫"),listOf("4","5","6","−"),listOf("7","8","9","+"),listOf("再记","0",".","保存"))
                Column(Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top=0.dp,bottom=4.dp)) { keys.forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { row.forEach { key ->
                    val saveKey=key=="保存"||key=="再记"
                    val enabled=if(saveKey) usable&&(key!="再记"||original==null) else !editLocked
                    LedgerControl(
                        Modifier.weight(1f).height(52.dp).testTag("entry-key-$key"), enabled = enabled,
                        onClick = { when(key){"保存"->save(false);"再记"->save(true);else->{amount=entryKey(amount,key);localError=""}} },
                    ) { interactions ->
                        Box(
                            Modifier.fillMaxWidth().height(43.dp)
                                .ledgerControlFeedback(interactions, RoundedCornerShape(8.dp))
                                .background(if (key == "保存") accent else ledgerColors.controlSurface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (key == "⌫") Icon(Icons.Outlined.Backspace, "删除", Modifier.size(20.dp), tint = ledgerColors.text)
                            else Text(key, Modifier.offset(y = (-1).dp), fontSize = if (saveKey) 14.sp else 20.sp,
                                color = if (key == "保存") ledgerColors.onAccent else ledgerColors.text)
                        }
                    }
                } } } }

                }
            }
            SnackbarHost(feedback,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=72.dp))
          }
        }
        if(vm.categoryReviewRequired) AlertDialog(onDismissRequest={},title={Text("分类已更新")},text={Text(if(vm.categoryReviewReady) "分类已刷新，请核对所选分类后继续。" else "分类刷新失败，请联网重试；草稿已保留。")},confirmButton={TextButton(onClick={if(vm.categoryReviewReady)vm.acknowledgeCategoryReview() else vm.retryCategoryReview()},enabled=!vm.busy){Text(if(vm.categoryReviewReady)"确认" else "重试刷新")}})
        if(!vm.categoryReviewRequired && changedCategoryDescription.isNotBlank()) AlertDialog(onDismissRequest={},title={Text("分类已更新")},text={Text("所选分类现在为：${changedCategoryDescription.substringAfter("|")}。请确认后继续记账。")},confirmButton={TextButton(onClick={observedCategoryDescription=changedCategoryDescription;changedCategoryDescription=""}){Text("确认")}})
        if(vm.showTransactionConfirmation) PendingTransactionDialog(
            vm.transactionConfirmation,vm.busy,vm.error,
            refresh={vm.refreshTransactionConfirmation()},confirm={vm.confirmTransactionSave(::saved)}
        )
        if(sheet=="account"||sheet=="destination") {
            val pickingDestination=sheet=="destination"
            val preset=accountPreset(accountPresetKey)
            when {
                preset!=null -> key(preset.key){AccountEditor(vm,null,close={accountPresetKey=""},preset=preset)}
                accountAssetPicker -> LedgerAssetPresetPicker(dismiss={accountAssetPicker=false},choose={accountPresetKey=it.key;accountAssetPicker=false},enabled=!editLocked&&!vm.entityBlocked)
                else -> LedgerAccountPicker(
                    accounts = vm.accounts.filter { !pickingDestination || it.id() != account },
                    selectedId = if (pickingDestination) destination else account,
                    destination = pickingDestination,
                    dismiss = { sheet = "" },
                    choose = { id ->
                        if (pickingDestination) destination = id else account = id
                        sheet = ""
                    },
                    create = { accountAssetPicker = true },
                    enabled = !editLocked,
                    createEnabled = !editLocked && !vm.entityBlocked,
                    defaultId = vm.defaultAccountId,
                    refreshNotice = { if (vm.accountRefreshFailed) AccountRefreshNotice(vm) },
                )
            }
        }

        if(sheet=="tags") LedgerTagPicker(vm.tags,selectedTags,{selectedTags=it},{sheet=""},{manageCatalog(1)})
        if(sheet=="date") EntryDatePicker(whenText,{sheet=""}){whenText=it;sheet=""}

        if(sheet=="pictures") ModalBottomSheet(onDismissRequest={sheet=""}) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("图片附件",style=MaterialTheme.typography.titleLarge)
            pictures.forEach{id->
                vm.draftPreviews[id]?.let{DraftPicture(it)}?:run{original?.array("pictures")?.firstOrNull{it.id("pictureId")==id}?.let{item->val ext=android.net.Uri.parse(item.optString("originalUrl")).path?.substringAfterLast('.')?.lowercase();if(ext in setOf("jpg","jpeg","png","webp"))Picture(vm,"/api/v1/family/pictures/$id.$ext")}}
                TextButton(onClick={vm.removeDraftPicture(id)},enabled=!editLocked){Text("移除此笔关联")}
            }
            TextButton(onClick={sheet=""}){Text("完成")}
        } }

}

@Composable
internal fun EntryCatalogContent(vm: BooksModel, section: Int, back: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回记账") }
                Text("分类与标签", style = MaterialTheme.typography.titleLarge)
            }
            CatalogPage(vm, initialSection = section)
        }
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
@Composable private fun EntryAccountRow(label:String,item:JSONObject?,enabled:Boolean=true,onClick:()->Unit) { ListItem(headlineContent={Text(item?.optString("name")?:"请选择")},supportingContent={Text(label)},leadingContent={Icon(Icons.Outlined.AccountBalanceWallet,null)},trailingContent={Text(item?.optString("currency")?:"")},modifier=Modifier.clickable(enabled=enabled,onClick=onClick)) }
@Composable private fun EntryPill(label:String,tag:String,enabled:Boolean=true,description:String=label,action:()->Unit) {
    LedgerControl(Modifier.testTag(tag).semantics { contentDescription = description }, enabled = enabled, onClick = action) { interactions ->
        Box(Modifier.heightIn(min = 32.dp).ledgerControlFeedback(interactions).background(ledgerColors.controlSurface)
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, color = ledgerColors.text)
        }
    }
}
@Composable internal fun EntryDatePicker(value:String,dismiss:()->Unit,select:(String)->Unit) {
    val context=LocalContext.current;val density=androidx.compose.ui.platform.LocalDensity.current
    val dark=ledgerColors.dark;val nativeAccent=ledgerColors.accent.toArgb()
    val preferences=remember(context){context.getSharedPreferences("entry-date-display",android.content.Context.MODE_PRIVATE)}
    fun widgetTheme(ctx:android.content.Context):android.content.Context {
        val configuration=android.content.res.Configuration(ctx.resources.configuration).apply{setLocale(java.util.Locale.SIMPLIFIED_CHINESE)}
        return android.view.ContextThemeWrapper(ctx.createConfigurationContext(configuration),if(dark)R.style.BooksEntryPickerTheme else R.style.BooksEntryPickerThemeLight)
    }
    val original=remember(value){LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}
    var year by rememberSaveable{mutableIntStateOf(original.year)};var month by rememberSaveable{mutableIntStateOf(original.monthValue)};var day by rememberSaveable{mutableIntStateOf(original.dayOfMonth)}
    var hour by rememberSaveable{mutableIntStateOf(original.hour)};var minute by rememberSaveable{mutableIntStateOf(original.minute)};var reset by remember{mutableIntStateOf(0)}
    var calendarMode by rememberSaveable(value){mutableStateOf(preferences.getBoolean("calendar",false))}
    var timeMode by rememberSaveable(value){mutableStateOf(false)}
    var pickerToCommit by remember{mutableStateOf<android.view.View?>(null)}
    fun quick(offset:Long){val date=java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(offset);year=date.year;month=date.monthValue;day=date.dayOfMonth;reset++}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
      Surface(Modifier.fillMaxWidth(.832f).height(when{timeMode->488.dp;calendarMode->520.dp;else->280.dp}).testTag("entry-date-dialog"),shape=RoundedCornerShape(4.dp),color=ledgerColors.surface){
       Column {
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())){
        key(dark){Box(Modifier.fillMaxWidth().height(when{timeMode->388.dp;calendarMode->420.dp;else->180.dp}),contentAlignment=Alignment.Center){
         val selectedHour=hour;val selectedMinute=minute;val selectedYear=year;val selectedMonth=month;val selectedDay=day
         if(timeMode) androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.fillMaxSize().testTag("entry-date-clock"),factory={ctx->
            android.widget.TimePicker(widgetTheme(ctx)).apply{setIs24HourView(true);this.hour=selectedHour;this.minute=selectedMinute;setOnTimeChangedListener{_,h,m->hour=h;minute=m};pickerToCommit=this}
         },update={picker->if(picker.hour!=hour)picker.hour=hour;if(picker.minute!=minute)picker.minute=minute})
         else if(calendarMode) androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.fillMaxSize().testTag("entry-date-calendar"),factory={ctx->android.widget.DatePicker(widgetTheme(ctx)).apply{firstDayOfWeek=java.util.Calendar.MONDAY;calendarViewShown=true;spinnersShown=false;init(selectedYear,selectedMonth-1,selectedDay){_,y,m,d->year=y;month=m+1;day=d};dispatchConfigurationChanged(android.content.res.Configuration(resources.configuration));pickerToCommit=this}},update={picker->if(picker.year!=year||picker.month!=month-1||picker.dayOfMonth!=day)picker.updateDate(year,month-1,day)})
         else key(reset){androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.width(208.dp).fillMaxHeight(),factory={ctx->
            val themed=widgetTheme(ctx);val row=android.widget.LinearLayout(themed);row.orientation=android.widget.LinearLayout.HORIZONTAL
            val years=android.widget.NumberPicker(themed).apply{minValue=1900;maxValue=2100;this.value=year;wrapSelectorWheel=false}
            val months=android.widget.NumberPicker(themed).apply{minValue=1;maxValue=12;displayedValues=(1..12).map{"${it}月"}.toTypedArray();this.value=month}
            val days=android.widget.NumberPicker(themed).apply{minValue=1;maxValue=java.time.YearMonth.of(year,month).lengthOfMonth();this.value=day.coerceAtMost(maxValue)}
            fun adjust(){days.maxValue=java.time.YearMonth.of(year,month).lengthOfMonth();day=day.coerceAtMost(days.maxValue);days.value=day}
            years.setOnValueChangedListener{_,_,v->year=v;adjust()};months.setOnValueChangedListener{_,_,v->month=v;adjust()};days.setOnValueChangedListener{_,_,v->day=v}
            listOf(years,months,days).forEachIndexed{index,picker->if(android.os.Build.VERSION.SDK_INT>=29)picker.textColor=nativeAccent;row.addView(picker,android.widget.LinearLayout.LayoutParams(with(density){64.dp.roundToPx()},android.widget.LinearLayout.LayoutParams.MATCH_PARENT).apply{if(index<2)marginEnd=with(density){8.dp.roundToPx()}})};pickerToCommit=row;row
         })}
         if(!timeMode) IconButton(onClick={calendarMode=!calendarMode;preferences.edit().putBoolean("calendar",calendarMode).apply()},modifier=Modifier.align(Alignment.TopEnd).padding(0.dp).size(48.dp).testTag("entry-date-switch-mode")){Icon(Icons.Outlined.ViewDay,"切换日期显示模式",Modifier.size(24.dp),tint=ledgerColors.muted)}
        }}
        }
        Row(Modifier.width(258.dp).height(48.dp).align(Alignment.CenterHorizontally),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("今", "昨", "前").forEachIndexed { offset, label ->
                    val date = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(offset.toLong())
                    val selected = year == date.year && month == date.monthValue && day == date.dayOfMonth
                    LedgerControl(Modifier.width(48.dp).testTag("entry-date-quick-$offset"), selected = selected,
                        onClick = { quick(offset.toLong()) }) { interactions ->
                        Box(Modifier.width(48.dp).heightIn(min = 32.dp).ledgerControlFeedback(interactions)
                            .background(if (selected) ledgerColors.selection else ledgerColors.field), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 14.sp, color = if (selected) ledgerColors.accent else ledgerColors.text)
                        }
                    }
                }
            }
            LedgerControl(Modifier.width(90.dp).testTag("entry-date-switch-time"), onClick = { timeMode = !timeMode }) { interactions ->
                Box(Modifier.fillMaxWidth().heightIn(min = 32.dp).ledgerControlFeedback(interactions).background(ledgerColors.field),
                    contentAlignment = Alignment.Center) {
                    Text(if (timeMode) "日期 %02d-%02d".format(month, day) else "时间 %02d:%02d".format(hour, minute), fontSize = 14.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=8.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically){TextButton(onClick=dismiss,modifier=Modifier.width(64.dp).heightIn(min=48.dp).testTag("entry-date-cancel"),colors=ButtonDefaults.textButtonColors(contentColor=ledgerColors.muted)){Text("取消",fontSize=14.sp)};Spacer(Modifier.width(8.dp));TextButton(onClick={pickerToCommit?.clearFocus();select(LocalDateTime.of(year,month,day,hour,minute).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))},modifier=Modifier.width(64.dp).heightIn(min=48.dp).testTag("entry-date-confirm")){Text("确定",fontSize=14.sp,fontWeight=FontWeight.Bold)}}
       }
      }
    }
}
internal fun entryKey(value:String,key:String):String {
    if(key=="⌫")return value.dropLast(1)
    if(key=="+"||key=="−"){
        if(value.isEmpty())return value
        if(value.last() in charArrayOf('+','−','-'))return value.dropLast(1)+key
        // The second operator commits the preceding calculation; no separate equals key.
        val hasOperation=value.replace('-','−').drop(1).any{it=='+'||it=='−'}
        val left=if(hasOperation)runCatching{entryTotal(value).stripTrailingZeros().toPlainString().replace('-','−')}.getOrElse{return value}else value
        return (left+key).takeIf{it.length<=30}?:value
    }
    if(value.length>=30)return value
    val operand=value.substringAfterLast('+').substringAfterLast('−')
    if(key==".")return if('.' in operand)value else value+if(operand.isEmpty())"0."else"."
    if(key.length!=1||!key[0].isDigit()||('.' in operand&&operand.substringAfter('.').length>=2))return value
    return if(operand=="0")value.dropLast(1)+key else value+key
}
private fun entryTotal(value:String):java.math.BigDecimal {
    val normalized=value.replace('-','−')
    require(Regex("[+−]?[0-9]+(?:\\.[0-9]{0,2})?(?:[+−][0-9]+(?:\\.[0-9]{0,2})?)*").matches(normalized)){"请输入有效金额，最多两位小数"}
    return Regex("[+−]?[0-9]+(?:\\.[0-9]{0,2})?").findAll(normalized).fold(java.math.BigDecimal.ZERO){sum,part->sum.add(java.math.BigDecimal(part.value.replace('−','-')))}
}
internal fun entryMinor(value:String,type:Int=4):Long {
    require(value.isNotBlank()&&value.last()!='+'&&value.last()!='−'&&value.last()!='-'){"请输入完整金额"}
    return entryTotal(value).movePointRight(2).longValueExact().also{minor->
        if(type==2||type==3)require(minor!=0L){"金额不能为零"}
        else require(minor>0){"转账金额必须大于零"}
    }
}
