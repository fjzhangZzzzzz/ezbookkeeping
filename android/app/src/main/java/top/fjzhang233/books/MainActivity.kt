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
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.sp
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

private val pictureSlots=Semaphore(2)

class MainActivity : ComponentActivity() {
    private val model by viewModels<BooksModel>()
    private val updates by viewModels<UpdateModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        intent?.data?.let { model.onCallback(it); intent.data = null }
        setContent { FamilyTheme { BooksApp(model, updates) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(model::onCallback); intent.data = null
    }
    override fun onResume() { super.onResume(); model.resume(); updates.resume() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BooksApp(vm: BooksModel, updates: UpdateModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsPage by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val feedback=remember{SnackbarHostState()}
    var monthPicker by rememberSaveable{mutableStateOf(false)}
    var calendar by rememberSaveable{mutableStateOf(false)}
    var report by rememberSaveable{mutableStateOf(false)}
    var search by rememberSaveable{mutableStateOf(false)}
    LaunchedEffect(vm.error,vm.notice,vm.showEditor){if(!vm.showEditor){val message=vm.error.ifBlank{vm.notice};if(message.isNotBlank()){if(vm.error.isBlank())android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show() else feedback.showSnackbar(message,duration=SnackbarDuration.Long);vm.dismissFeedback(message)}}}
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    var wasAuthenticated by remember{mutableStateOf(vm.token!=null)}
    LaunchedEffect(vm.token!=null){val authenticated=vm.token!=null;if(wasAuthenticated&&!authenticated){settingsPage="";tab=0;drawer.close()};wasAuthenticated=authenticated}
    LaunchedEffect(vm.browserUrl) { vm.browserUrl?.let { url ->
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(url)) }.onFailure { vm.browserFailed() }
        vm.browserUrl = null
    } }
    BackHandler(enabled = settingsPage.isNotEmpty()) { settingsPage = "" }
    BackHandler(enabled=drawer.currentValue!=DrawerValue.Closed||drawer.targetValue!=DrawerValue.Closed){scope.launch{drawer.close()}}
    ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=vm.token!=null,drawerContent={
        if(vm.token!=null) ModalDrawerSheet(modifier=Modifier.width(288.dp),drawerContainerColor=ledgerColors.surface,drawerShape=androidx.compose.ui.graphics.RectangleShape) {
            Text("家庭账本",Modifier.padding(start=24.dp,top=28.dp,bottom=8.dp),style=MaterialTheme.typography.headlineSmall)
            Text(vm.nickname,Modifier.padding(start=24.dp,bottom=20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            data class Destination(val title:String,val icon:androidx.compose.ui.graphics.vector.ImageVector,val target:Int,val page:String="")
            val destinations=listOf(Destination("流水",Icons.Outlined.ReceiptLong,0),Destination("搜索账单",Icons.Outlined.Search,0,"search"),Destination("账户",Icons.Outlined.AccountBalanceWallet,1),Destination("家庭",Icons.Outlined.People,2),Destination("分类与标签",Icons.Outlined.Category,3,"catalog"),Destination("登录设备",Icons.Outlined.Devices,3,"sessions"),Destination("我的",Icons.Outlined.Settings,3),Destination("关于与更新",Icons.Outlined.Info,3,"about"),Destination("刷新数据",Icons.Outlined.Refresh,0,"refresh"))
            destinations.forEach { item -> NavigationDrawerItem(label={Text(item.title)},icon={Icon(item.icon,null)},selected=tab==item.target&&settingsPage==item.page,onClick={if(item.page=="search")search=true else if(item.page=="refresh")vm.reload()else{tab=item.target;settingsPage=item.page;if(item.page=="sessions")vm.getSessions()};scope.launch{drawer.close()}},modifier=Modifier.padding(horizontal=12.dp).testTag("nav-${item.title}")) }
        }
    }) {
    val home=vm.token!=null&&tab==0&&settingsPage.isEmpty()
    Box(Modifier.fillMaxSize().background(ledgerColors.background)){
    if(home){Image(androidx.compose.ui.res.painterResource(R.drawable.ledger_landscape),null,Modifier.fillMaxWidth().height(240.dp),contentScale=androidx.compose.ui.layout.ContentScale.Crop);Box(Modifier.fillMaxWidth().height(240.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ledgerColors.background.copy(alpha=if(ledgerColors.dark).35f else .78f),ledgerColors.background.copy(alpha=if(ledgerColors.dark).72f else .94f)))))}
    Scaffold(containerColor=if(home)Color.Transparent else ledgerColors.background,contentColor=MaterialTheme.colorScheme.onSurface,snackbarHost={SnackbarHost(feedback)},
        topBar = { CenterAlignedTopAppBar(colors=TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor=Color.Transparent),expandedHeight=56.dp,title={if(vm.token!=null&&tab==0&&settingsPage.isEmpty()) Row(Modifier.clickable{monthPicker=true},verticalAlignment=Alignment.CenterVertically){Text(vm.ledgerMonth,fontSize=18.sp);Icon(Icons.Outlined.ExpandMore,null,Modifier.size(16.dp))} else Text(if(settingsPage=="about") "关于与更新" else if(vm.token==null) "家庭账本" else when(settingsPage){"catalog"->"分类与标签";"sessions"->"登录设备";else->listOf("我的流水","我的账户","家庭总览","我的")[tab]})},
            navigationIcon={if(settingsPage.isNotEmpty())IconButton(onClick={settingsPage=""}){Icon(Icons.Outlined.ArrowBack,"返回")}else if(vm.token!=null) IconButton(onClick={scope.launch{drawer.open()}}){Icon(Icons.Outlined.Menu,"打开菜单")}},
            actions={if(vm.token!=null&&tab==0&&settingsPage.isEmpty()){IconButton(onClick={calendar=true}){Icon(Icons.Outlined.CalendarMonth,"日历视图")};IconButton(onClick={report=true}){Icon(Icons.Outlined.BarChart,"月报表")}}else if(vm.token==null)IconButton(onClick={settingsPage="about"}){Icon(Icons.Outlined.Info,"关于与更新")}}) },
        floatingActionButtonPosition=FabPosition.Center,
        floatingActionButton = { if (vm.token != null && tab == 0 && settingsPage.isEmpty()) FloatingActionButton(onClick={vm.newTransaction()},modifier=Modifier.size(56.dp).testTag("add-transaction"),shape=androidx.compose.foundation.shape.CircleShape,containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Outlined.Add,"记一笔",Modifier.size(28.dp)) } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())


            if (updates.release != null && settingsPage != "about") TextButton(onClick = { settingsPage = "about" }, modifier = Modifier.fillMaxWidth()) { Text("发现新版本 ${updates.release!!.versionName} · 查看更新") }
            if (settingsPage == "about") UpdatePage(updates)
            else if (vm.token == null) LoginPage(vm)
            else when (settingsPage) {
                "catalog" -> CatalogPage(vm)
                "sessions" -> SessionsPage(vm)
                "about" -> UpdatePage(updates)
                else -> when(tab) {
                    0 -> TransactionsPage(vm)
                    1 -> AccountsPage(vm)
                    2 -> FamilyPage(vm)
                    else -> SettingsPage(vm) { settingsPage = it; if (it == "sessions") vm.getSessions() }
                }
            }
        }
    }
    }
    }
    if(calendar)LedgerCalendar(vm,{calendar=false;if(vm.token!=null)vm.changeLedgerDay(null)},{monthPicker=true})
    if(report)LedgerReport(vm){report=false}
    if(search && vm.token!=null)LedgerSearch(vm,{search=false}){item,close->LedgerTransactionDetails(vm,item,close)}
    if(monthPicker) LedgerMonthPicker(vm.ledgerMonth,{monthPicker=false}){vm.changeLedgerMonth(it);monthPicker=false}
    if (vm.showEditor && vm.token != null) TransactionEditor(vm)
    if(vm.entityReviewOpen && vm.token!=null)PendingEntityDialog(vm)
    if (vm.twoFactorToken != null) {
        var passcode by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = vm::cancelTwoFactor, title = { Text("二步验证") }, text = { Column { OutlinedTextField(passcode, { passcode = it }, label = { Text("验证器验证码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); if(vm.error.isNotBlank()) Text(vm.error, color=MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { vm.finishTwoFactor(passcode) }, enabled = !vm.busy && passcode.isNotBlank()) { Text("验证并登录") } }, dismissButton = { TextButton(onClick = vm::cancelTwoFactor) { Text("取消") } })
    }
    if (vm.oidcNeedsPassword) {
        var password by remember { mutableStateOf("") }; var passcode by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = vm::dismissOidc, title = { Text("确认本地账号") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("输入现有本地密码，将 Pocket ID 关联到同一个账户。")
            OutlinedTextField(password, { password = it }, Modifier.semantics { contentType=ContentType.Password }, label = { Text("本地密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
            OutlinedTextField(passcode, { passcode = it }, label = { Text("二步验证码（如已启用）") }, singleLine = true)
            if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { vm.finishOidc(password, passcode); password = "" }, enabled = !vm.busy && password.isNotEmpty()) { Text("确认关联") } }, dismissButton = { TextButton(onClick = vm::dismissOidc) { Text("取消") } })
    }
}

@Composable internal fun StatusMessage(text: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun LoginPage(vm: BooksModel) {
    var user by rememberSaveable { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var passcode by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }; val focus=LocalFocusManager.current
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(12.dp)); Text("把日子，记清楚。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("独立账户 · 一起看见家庭收支", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().semantics { contentType=ContentType.Username }, label = { Text("用户名或邮箱") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)}))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().semantics { contentType=ContentType.Password }, label = { Text("密码") }, singleLine = true, visualTransformation = if(visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon={IconButton(onClick={visible=!visible}){Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible) "隐藏密码" else "显示密码")}}, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();if(user.isNotBlank()&&password.isNotBlank()&&!vm.busy){vm.login(user,password,"");password=""}}))
        Button(onClick = { vm.login(user, password, passcode); password = ""; passcode = "" }, enabled = !vm.busy && user.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (vm.busy) "正在登录…" else "登录") }
        OutlinedButton(onClick = { vm.beginOidc() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("使用 Pocket ID 登录") }
        if(vm.browserPending) TextButton(onClick=vm::cancelBrowser, enabled=!vm.busy) { Text("取消 Pocket ID 登录") }
        Text("仅允许管理员预先开通的账号。密码和 Pocket ID 对应同一份账本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("服务：${BuildConfig.API_BASE_URL}", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun transactionLabel(item: JSONObject): String = if(item.optInt("type")==1) "余额调整" else if(item.optInt("type")==4) "账户转账" else item.optJSONObject("category")?.optString("name")?.takeIf { it.isNotBlank() } ?: item.optString("categoryName").ifBlank { "未分类" }
private fun displayTime(time: Long): String = runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(time)) }.getOrDefault("")

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TransactionsPage(vm: BooksModel) {
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    val daily=vm.dailyAmounts.associateBy{it.optString("date")}
    val amounts=vm.dailyAmounts.flatMap{it.array("amounts")}
    fun total(field:String)=amounts.filter{it.optString("currency")=="CNY"}.fold(java.math.BigInteger.ZERO){sum,item->sum+item.optString(field,"0").toBigInteger()}
    val income=total("incomeAmount");val expense=total("expenseAmount");val hasForeign=amounts.any{it.optString("currency")!="CNY"}
    LazyColumn(Modifier.fillMaxSize().testTag("ledger-list"),contentPadding=PaddingValues(bottom=100.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth().heightIn(min=132.dp).padding(horizontal=16.dp,vertical=18.dp)) {
                Text("月支出 · CNY",fontSize=14.sp,color=ledgerColors.text)
                Text(money(expense.toString()),color=ledgerColors.text,fontSize=28.sp,lineHeight=34.sp,modifier=Modifier.padding(top=2.dp,bottom=8.dp).testTag("ledger-month-expense"))
                Text("月收入 ${money(income.toString())}    本月结余 ${money((income-expense).toString())}",color=ledgerColors.text,fontSize=14.sp)
                if(hasForeign) Text("外币未计入 CNY 汇总",Modifier.padding(top=6.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(vm.ledgerDay==null)item{WeeklySpendingCard(vm.weeklyAmounts)}
        if(vm.ledgerDay!=null)item{TextButton(onClick={vm.changeLedgerDay(null)},modifier=Modifier.fillMaxWidth()){Text("${vm.ledgerDay} · 返回全月")}}
        if(vm.transactions.isEmpty()&&!vm.busy) item{EmptyState("还没有流水","点击「记一笔」，记录第一笔收支。")}
        vm.transactions.groupBy{Instant.ofEpochSecond(it.optLong("time")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()}.forEach{(date,rows)->
            item(key="day-$date") {
                Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(8.dp),color=ledgerColors.surface) {
                    Column {
                        val cny=daily[date]?.array("amounts")?.firstOrNull{it.optString("currency")=="CNY"}
                        Row(Modifier.fillMaxWidth().heightIn(min=40.dp).padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text(date.substring(5).replace('-','.')+if(date==java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())" 今天" else "",fontSize=14.sp,fontWeight=FontWeight.Bold)
                            Text("收:¥${money(cny?.optString("incomeAmount")?:"0")} 支:¥${money(cny?.optString("expenseAmount")?:"0")}",fontSize=12.sp,fontWeight=FontWeight.Bold)
                        }
                        rows.forEachIndexed{index,item->
                            if(index>0) HorizontalDivider(Modifier.padding(horizontal=12.dp),color=ledgerColors.divider)
                            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("transaction-${item.id()}").clickable(enabled=!vm.busy){selected=item}.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(4.dp).background(when(item.optInt("type")){2->ledgerColors.income;3->ledgerColors.expense;else->ledgerColors.muted},androidx.compose.foundation.shape.CircleShape))
                                Column(Modifier.weight(1f).padding(start=12.dp,end=8.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically){Text(transactionLabel(item),fontSize=14.sp);Text(" · "+(item.optJSONObject("sourceAccount")?.optString("name")?:""),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=10.sp,color=ledgerColors.muted)}
                                    if(item.optString("comment").isNotBlank()||item.array("pictures").isNotEmpty())Text(item.optString("comment")+if(item.array("pictures").isNotEmpty())" · 图片" else "",maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp,color=ledgerColors.muted)
                                }
                                val value=item.optString("sourceAmount")
                                Row(verticalAlignment=Alignment.CenterVertically){Text(transactionSignedAmount(item.optInt("type"),value),fontSize=16.sp,color=when(item.optInt("type")){2->ledgerColors.income;3->ledgerColors.expense;else->MaterialTheme.colorScheme.onSurface});Text(item.optJSONObject("sourceAccount")?.optString("currency")?:"",Modifier.padding(start=4.dp),fontSize=10.sp,color=ledgerColors.muted)}
                            }
                        }
                    }
                }
            }
        }
        if(vm.hasMoreTransactions) item{TextButton(onClick=vm::loadMore,enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("加载更多")}}
    }
    selected?.let{item->LedgerTransactionDetails(vm,item){selected=null}}
}
@Composable private fun LedgerMonthPicker(value:String,dismiss:()->Unit,select:(String)->Unit){
    val current=java.time.YearMonth.parse(value);var year by rememberSaveable{mutableIntStateOf(current.year)}
    AlertDialog(onDismissRequest=dismiss,containerColor=ledgerColors.surface,shape=androidx.compose.foundation.shape.RoundedCornerShape(4.dp),text={Row(Modifier.fillMaxWidth().height(300.dp)){
        LazyColumn(Modifier.width(96.dp),state=androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex=0)){items((current.year+3 downTo current.year-20).toList()){item->TextButton(onClick={year=item},modifier=Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.CircleShape,colors=ButtonDefaults.textButtonColors(containerColor=if(item==year)MaterialTheme.colorScheme.primary else Color.Transparent,contentColor=if(item==year)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)){Text("${item}年")}}}
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),Modifier.weight(1f)){items(12){index->TextButton(onClick={select(java.time.YearMonth.of(year,index+1).toString())}){Text("${index+1}月",color=if(year==current.year&&index+1==current.monthValue)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}}}
    }},confirmButton={})
}

@Composable private fun AccountsPage(vm: BooksModel) {
    var editingId by rememberSaveable { mutableStateOf("") }; var open by rememberSaveable { mutableStateOf(false) }
    val editing=vm.accounts.firstOrNull { it.id()==editingId }
    LazyColumn(Modifier.testTag("accounts-list"),contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { EntityWriteWarning(vm) };item { Button(onClick = { editingId = ""; open = true }, enabled = !vm.busy&&!vm.entityBlocked) { Icon(Icons.Outlined.Add, null); Text("新建账户") } }
        if (vm.accounts.isEmpty()) item { EmptyState("创建你的账户", "例如现金、银行卡。只管理自己的账户。") }
        items(vm.accounts, key = { it.id() }) { item ->
            Card(Modifier.fillMaxWidth().clickable(enabled=!vm.busy&&!vm.entityBlocked) { editingId = item.id(); open = true }) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(item.optString("name"), style = MaterialTheme.typography.titleMedium); Text(item.optString("currency") + if (item.optBoolean("hidden")) " · 已隐藏" else "", style = MaterialTheme.typography.bodySmall) }
                    Text(item.optString("currency")+" "+money(item.optString("balance")), style = MaterialTheme.typography.headlineSmall); Icon(Icons.Outlined.ChevronRight, "编辑账户")
                }
            }
        }
    }
    if (open && (editingId.isEmpty() || editing!=null)) AccountEditor(vm, editing, close={ open = false })
}

@Composable private fun FamilyPage(vm: BooksModel) {
    var month by rememberSaveable(vm.month) { mutableStateOf(vm.month) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    LazyColumn(Modifier.testTag("family-list"),contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(month, { month = it }, Modifier.weight(1f), label = { Text("月份 YYYY-MM") }, singleLine = true)
            Button(onClick = { vm.changeMonth(month) }, enabled = !vm.busy) { Text("查看") }
        } }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("全家本月收支 · CNY", style = MaterialTheme.typography.titleMedium)
                val total = vm.family.optJSONObject("totals") ?: JSONObject()
                Text("支出 ¥${money(total.optString("expenseMinor", "0"))}", style = MaterialTheme.typography.headlineMedium)
                Text("收入 ¥${money(total.optString("incomeMinor", "0"))}    结余 ¥${money(total.optString("netMinor", "0"))}")
                Text("按 Asia/Shanghai 统计 · 外币不计入", style = MaterialTheme.typography.bodySmall)
                if (vm.family.optBoolean("hasExcludedForeignCurrency")) Text("本月有外币交易已排除，请到各成员账户查看。", color = MaterialTheme.colorScheme.error)
            }
        } }
        items(vm.family.array("members"), key = { "member" + it.id() }) { item ->
            ListItem(headlineContent = { Text(item.optString("nickname")) }, supportingContent = { Text("收入 ¥${money(item.optString("incomeMinor"))} · 支出 ¥${money(item.optString("expenseMinor"))}") })
        }
        item { Text("家庭账户余额", style = MaterialTheme.typography.titleMedium) }
        items(vm.family.array("accounts"), key = { "account" + it.id() }) { item ->
            ListItem(modifier=Modifier.testTag("family-account-${item.id()}"),headlineContent = { Text(item.optString("name")) }, supportingContent = { Text(item.optString("ownerName")) }, trailingContent = { Text("${item.optString("currency")} ${money(item.optString("balanceMinor"))}") })
        }
        item { Text("家庭流水 · 共享查看", style = MaterialTheme.typography.titleMedium) }
        if (vm.familyItems.isEmpty()) item { EmptyState("本月还没有家庭流水", "活跃成员的流水将在这里汇总。") }
        items(vm.familyItems, key = { "transaction" + it.id() }) { item ->
            Card(Modifier.fillMaxWidth().clickable { selected = item }) {
                ListItem(headlineContent = { Text(item.optString("categoryName")) }, supportingContent = { Text(item.optString("ownerName") + " · " + item.optString("comment").ifBlank { item.optString("accountName") }) }, trailingContent = { Text("${item.optString("currency")} ${money(item.optString("amountMinor"))}") })
            }
        }
        if (vm.familyItems.isNotEmpty()) item { TextButton(onClick = vm::moreFamily, enabled = !vm.busy) { Text("更多家庭流水") } }
    }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.optString("categoryName")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.optString("ownerName") + " · " + displayTime(item.optLong("time")))
            Text("${item.optString("currency")} ${money(item.optString("amountMinor"))}")
            Text(item.optString("comment")); Text("家庭共享仅查看；编辑自己的流水请前往「流水」。", style = MaterialTheme.typography.bodySmall)
            item.array("pictures").forEach { Picture(vm, "/api/v1/family/pictures/${it.id()}.${it.optString("extension")}") }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
}

@Composable internal fun Picture(vm: BooksModel, path: String) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }; var failed by remember(path) { mutableStateOf(false) }
    // Decode after the authenticated fetch; never put Bearer tokens in image URLs.
    LaunchedEffect(path, failed) { if (bitmap == null && !failed) try {
        pictureSlots.withPermit {
        val bytes = vm.api.picture(path)
        bitmap = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong()*bounds.outHeight <= 50_000_000)
            var sample=1; while(bounds.outWidth/sample > 1024 || bounds.outHeight/sample > 1024) sample*=2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e:Exception) { vm.imageFailed(e); failed=true } }
    Column(Modifier.fillMaxWidth().testTag("transaction-picture")) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "交易图片附件", Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 280.dp))
        else Text(if (failed) "图片加载失败，请重新打开重试" else "正在加载图片…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SettingsPage(vm: BooksModel, navigate: (String) -> Unit) {
    var logoutConfirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeSettings()
        Text("你好，${vm.nickname}", style = MaterialTheme.typography.headlineSmall)
        Text("每人独立记账，全家共同看见。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("catalog" to "分类与标签", "sessions" to "登录设备与会话", "about" to "关于与应用更新").forEach { (key, title) ->
            OutlinedCard(onClick = { navigate(key) }, modifier = Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(title) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }) }
        }
        Text(if(vm.externalLinked) "Pocket ID 已关联" else "Pocket ID 尚未关联")
        OutlinedButton(onClick = { vm.beginOidc(true) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if(vm.externalLinked) "已关联 · 网页安全设置可管理" else "关联 Pocket ID") }
        OutlinedButton(onClick = { logoutConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("退出登录") }
    }
    if (logoutConfirm) AlertDialog(onDismissRequest = { logoutConfirm = false }, title = { Text("退出此设备？") }, text = { Text("退出会撤销此设备当前会话。其他设备可在登录设备页管理。") },
        confirmButton = { TextButton(onClick = { logoutConfirm = false; vm.logout() }, enabled = !vm.busy) { Text("退出") } }, dismissButton = { TextButton(onClick = { logoutConfirm = false }) { Text("取消") } })
}

@Composable private fun SessionsPage(vm: BooksModel) {
    var revoke by remember { mutableStateOf<JSONObject?>(null) }; var others by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("丢失设备后，可撤销它的会话。撤销后该设备需重新登录。") }
        item { OutlinedButton(onClick = { others = true }, enabled = !vm.busy) { Text("撤销其他所有会话") } }
        items(vm.sessions, key = { it.optString("tokenId") }) { item ->
            Card { ListItem(headlineContent = { Text(if (item.optBoolean("isCurrent")) "此设备" else "其他设备") }, supportingContent = { Text(item.optString("userAgent").take(80) + "\n最近活动：" + displayTime(item.optLong("lastSeen"))) }, trailingContent = { TextButton(onClick = { revoke = item }, enabled = !vm.busy,modifier=Modifier.testTag(if(item.optBoolean("isCurrent"))"revoke-current-session"else"revoke-other-session")) { Text("撤销") } }) }
        }
    }
    if (revoke != null || others) AlertDialog(onDismissRequest = { revoke = null; others = false }, title = { Text("确认撤销会话？") }, text = { Text("被撤销的会话将不能继续访问账本。") }, confirmButton = { TextButton(onClick = {
        if (others) vm.revokeOthers() else revoke?.let { vm.revokeSession(it.optString("tokenId"), it.optBoolean("isCurrent")) }; revoke = null; others = false
    }) { Text("确认撤销") } }, dismissButton = { TextButton(onClick = { revoke = null; others = false }) { Text("取消") } })
}

@Composable private fun Choice(label: String, values: List<Pair<String, String>>, value: String, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(label + "：" + (values.firstOrNull { it.first == value }?.second ?: "请选择"), Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null)
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = { LazyColumn(Modifier.heightIn(max = 320.dp).testTag("choice-list")) {
        items(values) { pair -> ListItem(headlineContent = { Text(pair.second) }, leadingContent = { RadioButton(pair.first == value, null) }, modifier = Modifier.clickable(enabled=enabled) { if(enabled){choose(pair.first);expanded=false} }.heightIn(min = 48.dp)) }
    } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } })
}


@Composable internal fun AccountEditor(vm: BooksModel, original: JSONObject?, close: () -> Unit, submit: (String,JSONObject,()->Unit)->Unit = vm::saveEntity, preset:AccountPreset?=null) {
    var name by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("name") ?: preset?.label ?: "") }; var currency by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("currency") ?: "CNY") }
    var balance by rememberSaveable(original?.id()) { mutableStateOf("0.00") }; var category by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("category") ?: preset?.category?.toString() ?: "1") }; var comment by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("comment") ?: "") }
    var localError by rememberSaveable(original?.id()) { mutableStateOf("") }
    val locked=vm.busy||vm.entityBlocked
    LaunchedEffect(vm.entityAwaitingReview,vm.busy){if(vm.entityAwaitingReview&&!vm.busy)close()}
    val requestId = rememberSaveable(original?.id()) { UUID.randomUUID().toString() }
    val balanceTime = rememberSaveable(original?.id()) { Instant.now().epochSecond }
    AlertDialog(onDismissRequest = {if(!vm.busy&&!vm.entityBlocked)close()}, title = { Text(if (original == null) "新建账户" else "编辑账户资料") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { if(!vm.busy&&!vm.entityBlocked&&it.length <= 64) name = it }, label = { Text("账户名称") }, singleLine = true,enabled=!locked)
        Choice("账户类型", listOf("1" to "现金", "2" to "银行卡", "3" to "信用卡", "4" to "虚拟账户", "5" to "负债", "6" to "应收款", "7" to "投资理财", "8" to "储蓄", "9" to "定期存款"), category,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)category = it }
        if (original == null) { OutlinedTextField(currency, { if(!vm.busy&&!vm.entityBlocked)currency = it.uppercase().take(3) }, label = { Text("币种代码") }, singleLine = true,enabled=!locked); OutlinedTextField(balance, { if(!vm.busy&&!vm.entityBlocked)balance = it }, label = { Text(initialAccountBalanceLabel(category.toIntOrNull()?:1,currency)) }, supportingText={if(isLiabilityAccountCategory(category.toIntOrNull()?:1))Text("填写当前欠款，金额不能为负数")}, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,enabled=!locked) }
        else Text("编辑资料不会调整余额。余额随记账更新。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(comment, { if(!vm.busy&&!vm.entityBlocked&&it.length <= 255) comment = it }, label = { Text("备注") },enabled=!locked)
        if (localError.isNotEmpty()) Text(localError, color = MaterialTheme.colorScheme.error)
        if (vm.error.isNotEmpty()) Text(vm.error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = {
        if(vm.busy||vm.entityBlocked)return@TextButton
        runCatching {
            require(name.isNotBlank()) { "请填写名称" }
            val body = JSONObject().put("name", name.trim()).put("category", category.toInt()).put("icon", original?.optString("icon") ?: preset?.icon ?: "1").put("iconType", 0).put("color", original?.optString("color") ?: preset?.color ?: "006B5C").put("comment", comment)
            if (original == null) { require(Regex("[A-Z]{3}").matches(currency)){"请输入有效的三位币种代码"}; val minor = initialAccountBalanceMinor(balance,category.toInt()); body.put("type", 1).put("currency", currency).put("balance", minor.toString()).put("balanceTime", balanceTime).put("clientSessionId", requestId) }
            else { body.put("id", original.id()).put("hidden", original.optBoolean("hidden")); if(category=="3"){ if(original.has("creditCardStatementDate")) body.put("creditCardStatementDate",original.get("creditCardStatementDate")); original.optString("creditCardLimit").toLongOrNull()?.takeIf{limit->limit>0}?.let{limit->body.put("creditCardLimit",limit.toString())} } }
            body
        }.onSuccess { submit("/api/v1/accounts/${if (original == null) "add" else "modify"}.json", it, close) }.onFailure { localError = if(it is ArithmeticException) "余额超出可用范围" else it.message?:"请检查名称、币种和余额格式" }
    }, enabled = !locked) { Text("保存账户") } }, dismissButton = { TextButton(onClick = {if(!vm.busy&&!vm.entityBlocked)close()}, enabled = !locked) { Text("取消") } })
}

@Composable internal fun CatalogPage(vm: BooksModel, initialSection:Int=0) {
    require(initialSection in 0..2)
    var section by rememberSaveable(initialSection) { mutableIntStateOf(initialSection) }
    var editingId by rememberSaveable { mutableStateOf("") }; var open by rememberSaveable { mutableStateOf(false) }
    var editingSnapshot by rememberSaveable { mutableStateOf("") }
    var newParentId by rememberSaveable { mutableStateOf("0") }
    var newType by rememberSaveable { mutableStateOf("2") }
    var expanded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val currentItems=when(section){0->vm.categories;1->vm.tags;else->vm.tagGroups}
    val editing=currentItems.firstOrNull{it.id()==editingId} ?: editingSnapshot.takeIf{editingId.isNotEmpty() && it.isNotEmpty()}?.let{JSONObject(it)}
    LaunchedEffect(Unit) { vm.refreshCategories() }
    Column {
        EntityWriteWarning(vm)
        TabRow(selectedTabIndex = section) { listOf("分类", "标签", "标签组").forEachIndexed { index, text -> Tab(section == index, { section = index }, enabled=!vm.busy, text = { Text(text) }) } }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("全家共享分类、标签及标签组。仅使用内置图标。", style = MaterialTheme.typography.bodySmall) }
            if(section==0) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick={vm.refreshCategories()},enabled=!vm.busy) { Text("刷新分类") }; TextButton(onClick={ showHidden = !showHidden }) { Text(if(showHidden) "隐藏已停用" else "显示已停用") } }; if(vm.categoryRevision==null) Text("分类管理需要升级服务端；浏览和记账仍可使用",style=MaterialTheme.typography.bodySmall) }
            item { Button(onClick = { editingId = ""; editingSnapshot=""; newParentId="0"; newType="2"; open = true }, enabled = !vm.busy&&!vm.entityBlocked&&(section!=0||vm.categoryRevision!=null)) { Icon(Icons.Outlined.Add, null); Text("新建${listOf("分类", "标签", "标签组")[section]}") } }
            val list = when(section) { 0 -> vm.categories; 1 -> vm.tags; else -> vm.tagGroups }
            if (list.isEmpty()) item { EmptyState("暂无${listOf("分类", "标签", "标签组")[section]}", "点击新建，与家人共享整理方式。") }
            if (section == 0) {
                val visible = list.filter { !it.optBoolean("familyInternalTransfer") && (showHidden || !it.optBoolean("hidden")) }
                val parents = visible.filter { it.id("parentId") == "0" }
                items(parents, key = { it.id() }) { parentItem ->
                    val children = visible.filter { it.id("parentId") == parentItem.id() }
                    val isExpanded = parentItem.id() in expanded
                    Card(Modifier.fillMaxWidth().testTag("catalog-parent-${parentItem.id()}"), colors = CardDefaults.cardColors(containerColor = ledgerColors.controlSurface), shape = MaterialTheme.shapes.medium) {
                        ListItem(
                            headlineContent = { Text(parentItem.optString("name"), style = MaterialTheme.typography.titleMedium, color = if(parentItem.optBoolean("hidden")) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f) else MaterialTheme.colorScheme.onSurface) },
                            supportingContent = { Text(when(parentItem.optInt("type")) { 1 -> "收入"; 2 -> "支出"; else -> "转账" } + " · ${children.size} 个子分类") },
                            leadingContent = { Box(Modifier.size(42.dp).clip(CircleShape).background(ledgerColors.selection), contentAlignment = Alignment.Center) { BuiltinIcon(parentItem, Modifier.size(28.dp), tint = ledgerColors.accent) } },
                            trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { editingId = parentItem.id(); editingSnapshot=parentItem.toString(); open = true }, enabled=!vm.busy&&!vm.entityBlocked&&vm.categoryRevision!=null) { Icon(Icons.Outlined.Edit, "编辑${parentItem.optString("name")}") }
                                IconButton(onClick = { expanded = if (isExpanded) expanded - parentItem.id() else expanded + parentItem.id() }) { Icon(if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (isExpanded) "收起 ${parentItem.optString("name")}" else "展开 ${parentItem.optString("name")}") }
                            }},
                            modifier = Modifier.clickable { expanded = if (isExpanded) expanded - parentItem.id() else expanded + parentItem.id() }
                        )
                        AnimatedVisibility(isExpanded) {
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                                val childRows = ((children.size + 1 + 3) / 4).coerceAtLeast(1)
                                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                                    modifier = Modifier.fillMaxWidth().height((childRows * 74 + (childRows - 1) * 8).dp),
                                    userScrollEnabled = false,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(children, key = { it.id() }) { child ->
                                        Column(
                                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                                .background(ledgerColors.surface)
                                                .clickable(enabled=!vm.busy&&!vm.entityBlocked&&vm.categoryRevision!=null) { editingId = child.id(); editingSnapshot=child.toString(); open = true }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(Modifier.size(32.dp).clip(CircleShape).background(ledgerColors.field), contentAlignment = Alignment.Center) { BuiltinIcon(child, Modifier.size(24.dp), tint = ledgerColors.muted) }
                                            Text(child.optString("name"), modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = if(child.optBoolean("hidden")) ledgerColors.muted else ledgerColors.text)
                                        }
                                    }
                                    item {
                                        TextButton(onClick = { editingId = ""; editingSnapshot=""; newParentId=parentItem.id(); newType=parentItem.optString("type", "2"); open = true }, enabled=!vm.busy&&!vm.entityBlocked&&vm.categoryRevision!=null&&!parentItem.optBoolean("hidden"), modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp)) { Icon(Icons.Outlined.Add, null); Text("添加") }
                                    }
                                }
                            }
                        }
                    }
                }
            } else items(list.filter { !it.optBoolean("familyInternalTransfer") }, key = { it.id() }) { item ->
                ListItem(headlineContent = { Text(item.optString("name")) }, trailingContent = { IconButton(onClick = { editingId = item.id(); editingSnapshot=item.toString(); open = true },enabled=!vm.busy&&!vm.entityBlocked) { Icon(Icons.Outlined.Edit, "编辑${item.optString("name")}") } })
            }
        }
    }
    if (open && (editingId.isEmpty() || editing!=null)) CatalogEditor(vm, section, editing, initialType=newType, initialParentId=newParentId, close={ open = false })
}

@Composable internal fun CatalogEditor(vm: BooksModel, section: Int, original: JSONObject?, initialType: String = "2", initialParentId: String = "0", close: () -> Unit, submit: (String,JSONObject,()->Unit)->Unit = vm::saveEntity) {
    var name by rememberSaveable(section, original?.id()) { mutableStateOf(original?.optString("name") ?: "") }; var type by rememberSaveable(section, original?.id(), initialType) { mutableStateOf(original?.optString("type") ?: initialType) }
    var icon by rememberSaveable(section, original?.id(), initialParentId) { mutableStateOf(original?.optString("icon")?.takeIf { it.isNotBlank() } ?: "1") }
    var parent by rememberSaveable(section, original?.id(), initialParentId) { mutableStateOf(original?.id("parentId") ?: initialParentId) }; var group by rememberSaveable(section, original?.id()) { mutableStateOf(original?.id("groupId") ?: "0") }
    val locked=vm.busy||vm.entityBlocked
    LaunchedEffect(vm.entityAwaitingReview,vm.busy){if(vm.entityAwaitingReview&&!vm.busy)close()}
    val requestId = rememberSaveable(section, original?.id()) { UUID.randomUUID().toString() }
    var openedRevision by rememberSaveable(section, original?.id()) { mutableStateOf(vm.categoryRevision ?: "") }
    var impactCount by rememberSaveable(section, original?.id()) { mutableStateOf<String?>(null) }
    var categoryAction by rememberSaveable(section, original?.id()) { mutableStateOf("modify") }
    val parentValid = section != 0 || parent == "0" || vm.categories.any { it.id() == parent && it.id("parentId") == "0" && it.optString("type") == type && !it.optBoolean("hidden") }
    fun reviewCategoryAction(action:String) {
        if(original==null || openedRevision.isBlank()) return
        vm.act {
            val impact=vm.api.categoryImpact(original.id())
            require(impact.optString("revision")==openedRevision) { "分类已在其他设备变更，请保留草稿并刷新后重新评审" }
            categoryAction=action; impactCount=impact.getString("transactionCount")
        }
    }
    val label = listOf("分类", "标签", "标签组")[section]
    AlertDialog(onDismissRequest = {if(!vm.busy&&!vm.entityBlocked)close()}, title = { Text((if (original == null) "新建" else "编辑") + label) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { if(!vm.busy&&!vm.entityBlocked&&it.length <= 64) name = it }, label = { Text("${label}名称") }, singleLine = true,enabled=!locked)
        if (section == 0) {
            if (original == null) Choice("用途", listOf("2" to "支出", "1" to "收入", "3" to "转账"), type,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked){type = it; parent = "0"} }
            if(original==null || original.id("parentId")!="0") Choice("所属大类", (if(original==null) listOf("0" to "作为大类") else emptyList()) + vm.categories.filter { it.id("parentId") == "0" && it.optString("type") == type && it.id() != original?.id() && !it.optBoolean("hidden") }.map { it.id() to it.optString("name") }, parent,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)parent = it }
            else Text("一级分类不能调整为二级分类")
            Text("分类图标", style = MaterialTheme.typography.titleSmall, color = ledgerColors.text)
            CatalogIconPicker(icon = icon, enabled = !locked) { icon = it }
        }
        if (section == 1) Choice("标签组", listOf("0" to "不分组") + vm.tagGroups.map { it.id() to it.optString("name") }, group,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)group = it }
        if(section==0) {
            TextButton(onClick={vm.act { vm.refreshCategoriesNow(); openedRevision=vm.categoryRevision.orEmpty(); impactCount=null }},enabled=!locked) { Text("刷新分类并重新评审草稿") }
            if(original!=null) Row {
                TextButton(onClick={reviewCategoryAction("hide")},enabled=!locked&&openedRevision.isNotBlank()) { Text(if(original.optBoolean("hidden")) "恢复分类" else "隐藏分类") }
                TextButton(onClick={reviewCategoryAction("delete")},enabled=!locked&&openedRevision.isNotBlank()) { Text("删除分类") }
            }
        }
        if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = {
        if(vm.busy||vm.entityBlocked)return@TextButton
        val body = JSONObject().put("name", name.trim()); if (original != null) body.put("id", original.id())
        if (section == 0) { body.put("type", type.toInt()).put("parentId", parent).put("icon", icon).put("iconType", 0).put("color", original?.optString("color") ?: "006B5C").put("comment", original?.optString("comment") ?: "").put("hidden", original?.optBoolean("hidden") ?: false); if (original == null) body.put("clientSessionId", requestId) }
        if (section == 1) body.put("groupId", group)
        val path = when(section) { 0 -> "categories"; 1 -> "tags"; else -> "tags/groups" }
        if(section==0) {
            val revision=openedRevision
            if(revision.isBlank()) return@TextButton
            body.put("expectedRevision",revision)
            if(original!=null) {
                reviewCategoryAction("modify")
            } else submit("/api/v1/transaction/$path/add.json", body, close)
        } else submit("/api/v1/transaction/$path/${if(original == null) "add" else "modify"}.json", body, close)
    }, enabled = !locked && name.isNotBlank() && (section!=0||openedRevision.isNotBlank()) && parentValid) { Text("保存${label}") } }, dismissButton = { TextButton(onClick = {if(!vm.busy&&!vm.entityBlocked)close()},enabled=!locked) { Text("取消") } })
    impactCount?.let { count -> AlertDialog(onDismissRequest={impactCount=null},title={Text("确认共享分类变更")},text={Text("此分类涉及活跃家庭成员的 $count 笔历史交易。删除仅允许没有引用的分类；隐藏后保留历史记录。")},confirmButton={TextButton(onClick={
        val item=original!!
        val body=JSONObject().put("id",item.id()).put("expectedRevision",openedRevision)
        when(categoryAction) {
            "hide" -> body.put("hidden",!item.optBoolean("hidden"))
            "modify" -> body.put("name",name.trim()).put("type",type.toInt()).put("parentId",parent).put("icon",icon).put("iconType",0).put("color",item.optString("color")).put("comment",item.optString("comment")).put("hidden",item.optBoolean("hidden"))
        }
        val action=categoryAction;impactCount=null;submit("/api/v1/transaction/categories/$action.json",body,close)
    },enabled=!locked){Text("确认")}},dismissButton={TextButton(onClick={impactCount=null}){Text("取消")}}) }
}

@Composable
private fun CatalogIconPicker(icon: String, enabled: Boolean, choose: (String) -> Unit) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("catalog-icon-picker"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = true
    ) {
        items(builtinCategoryIconIds, key = { it }) { id ->
            val selected = id == icon
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (selected) ledgerColors.selection else ledgerColors.field)
                    .clickable(enabled = enabled) { choose(id) }
                    .semantics { contentDescription = "分类图标 $id" },
                contentAlignment = Alignment.Center
            ) { BuiltinIcon(JSONObject().put("icon", id), Modifier.size(26.dp), tint = if (selected) ledgerColors.accent else ledgerColors.muted) }
        }
    }
}
