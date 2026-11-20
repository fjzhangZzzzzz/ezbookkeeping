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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
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

private val drawerSlide = tween<Float>(durationMillis = 240, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BooksApp(vm: BooksModel, updates: UpdateModel) {
    val backStack = rememberNavBackStack(AppPage.LEDGER)
    val authenticated = vm.token != null
    val hasPrivatePages = !authenticated && backStack.any { it != AppPage.LEDGER && it != AppPage.ABOUT }
    val activeStack: List<NavKey> = if (hasPrivatePages) listOf(AppPage.LEDGER) else backStack
    val current = activeStack.last()
    val context = LocalContext.current
    val feedback = remember { SnackbarHostState() }
    val dialogStrategy = remember { DialogSceneStrategy<NavKey>() }

    fun push(destination: NavKey) {
        if (backStack.last() != destination) backStack.add(destination)
    }

    fun pop(destination: NavKey) {
        if (backStack.size <= 1 || backStack.last() != destination) return
        backStack.removeLastOrNull()
        if (destination == AppPage.CALENDAR && vm.token != null) vm.changeLedgerDay(null)
        if (destination is FullscreenChartPage) vm.fullscreenChart = null
    }

    fun openChart(destination: FullscreenChartPage, snapshot: ReportSnapshot) {
        vm.fullscreenChart = destination to snapshot
        push(destination)
    }

    LaunchedEffect(vm.error,vm.notice,vm.showEditor){if(!vm.showEditor){val message=vm.error.ifBlank{vm.notice};if(message.isNotBlank()){if(vm.error.isBlank())android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show() else feedback.showSnackbar(message,duration=SnackbarDuration.Long);vm.dismissFeedback(message)}}}
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val moveDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val scope=rememberCoroutineScope()
    @Suppress("DEPRECATION") // DrawerState.open/close do not accept an animation spec.
    fun moveDrawer(target: DrawerValue, onSettled: () -> Unit = {}) {
        scope.launch {
            drawer.animateTo(target, drawerSlide)
            onSettled()
        }
    }
    var wasAuthenticated by remember { mutableStateOf(authenticated) }
    LaunchedEffect(authenticated, hasPrivatePages, vm.showEditor) {
        if (authenticated != wasAuthenticated || hasPrivatePages) {
            while (backStack.size > 1) backStack.removeLastOrNull()
            drawer.snapTo(DrawerValue.Closed)
        }
        wasAuthenticated = authenticated
        if (authenticated && vm.showEditor) {
            if (EntryPage !in backStack) push(EntryPage)
        } else if (EntryPage in backStack) {
            while (EntryPage in backStack) backStack.removeLastOrNull()
        }
    }
    ReportOrientation(current is FullscreenChartPage)
    LaunchedEffect(vm.browserUrl) { vm.browserUrl?.let { url ->
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(url)) }.onFailure { vm.browserFailed() }
        vm.browserUrl = null
    } }
    BackHandler(enabled=!drawer.isOpen&&drawer.targetValue==DrawerValue.Open){moveDrawer(DrawerValue.Closed)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val drawerWidth = (maxWidth * 0.70f).coerceAtMost(360.dp)
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.roundToPx() }
    ModalNavigationDrawer(
        drawerState=drawer,
        gesturesEnabled=authenticated&&current==AppPage.LEDGER,
        scrimColor=Color.Black.copy(alpha=if(ledgerColors.dark) 0.48f else 0.32f),
        drawerContent={
        // Keep both drag anchors distinct while the user signs in or out.
        ModalDrawerSheet(drawerState=drawer,modifier=Modifier.width(drawerWidth),drawerContainerColor=ledgerColors.surface,drawerShape=androidx.compose.ui.graphics.RectangleShape) {
          if(vm.token!=null) {
            Text("家庭账本",Modifier.padding(start=24.dp,top=28.dp,bottom=8.dp),style=MaterialTheme.typography.headlineSmall)
            Text(vm.nickname,Modifier.padding(start=24.dp,bottom=20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            data class Destination(val title:String,val icon:androidx.compose.ui.graphics.vector.ImageVector,val target:AppPage)
            val destinations=listOf(
                Destination("搜索",Icons.Outlined.Search,AppPage.SEARCH),
                Destination("家庭",Icons.Outlined.People,AppPage.FAMILY),
                Destination("设置",Icons.Outlined.Settings,AppPage.SETTINGS),
            )
            destinations.forEach { item -> NavigationDrawerItem(label={Text(item.title)},icon={Icon(item.icon,null)},selected=current==item.target,onClick={moveDrawer(DrawerValue.Closed){if(vm.token!=null&&current==AppPage.LEDGER)push(item.target)}},modifier=Modifier.padding(horizontal=12.dp).testTag("nav-${item.title}")) }
          }
        }
    }) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        val offset = drawer.currentOffset
        if (authenticated && !offset.isNaN()) {
            translationX = (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) * moveDirection
        } else {
            translationX = 0f
        }
    }.background(ledgerColors.background)){
        NavDisplay(
            backStack = activeStack,
            modifier = Modifier.fillMaxSize(),
            sceneStrategies = listOf(dialogStrategy),
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            onBack = {
                when (current) {
                    EntryPage -> if (!vm.busy && !vm.transactionNeedsConfirmation) vm.closeTransaction()
                    else -> pop(current)
                }
            },
            entryProvider = entryProvider<NavKey> {
                entry<AppPage> { page ->
                    when (page) {
                        AppPage.REPORT -> LedgerReport(
                            vm, { pop(page) }, ::openChart,
                            showCategorySheet = activeStack.none { it is CategoryStatisticsPage },
                            openStatistics = ::push,
                        )
                        AppPage.FAMILY -> FamilyReportPage(vm, { pop(page) }, ::openChart)
                        AppPage.CALENDAR -> LedgerCalendar(vm) { pop(page) }
                        else -> BooksScaffold(vm, page, feedback, { pop(page) }, { moveDrawer(DrawerValue.Open) }, ::push) {
                            when (page) {
                                AppPage.ABOUT -> UpdatePage(updates)
                                AppPage.LEDGER -> if (authenticated) TransactionsPage(vm) else LoginPage(vm)
                                AppPage.SEARCH -> LedgerSearch(vm) { item, close -> LedgerTransactionDetails(vm, item, close) }
                                AppPage.SETTINGS -> SettingsPage(vm) { destination ->
                                    push(destination)
                                    if (destination == AppPage.SESSIONS) vm.getSessions()
                                }
                                AppPage.ACCOUNTS -> AccountsPage(vm)
                                AppPage.CATALOG -> CatalogPage(vm)
                                AppPage.SESSIONS -> SessionsPage(vm)
                                AppPage.EXTERNAL_AUTH -> ExternalAuthPage(vm)
                                else -> Unit
                            }
                        }
                    }
                }
                entry<EntryPage>(metadata = fullScreenDialog) {
                    if (authenticated && vm.showEditor) TransactionEditor(vm) { push(EntryCatalogPage(it)) }
                }
                entry<EntryCatalogPage>(metadata = fullScreenDialog) { page ->
                    EntryCatalogContent(vm, page.section) { pop(page) }
                }
                entry<CategoryStatisticsPage>(metadata = fullScreenDialog) { page ->
                    ReportCategoryStatistics(vm, page) { pop(page) }
                }
                entry<FullscreenChartPage> { page ->
                    FullscreenReportChart(vm, page) { pop(page) }
                }
            },
        )
    }
    }
    }
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BooksScaffold(
    vm: BooksModel,
    page: AppPage,
    feedback: SnackbarHostState,
    back: () -> Unit,
    openDrawer: () -> Unit,
    navigate: (AppPage) -> Unit,
    content: @Composable () -> Unit,
) {
    val home = page == AppPage.LEDGER && vm.token != null
    var monthPicker by rememberSaveable { mutableStateOf(false) }
    val title = when (page) {
        AppPage.SEARCH -> "搜索"
        AppPage.SETTINGS -> "设置"
        AppPage.ACCOUNTS -> "账户"
        AppPage.CATALOG -> "分类与标签"
        AppPage.SESSIONS -> "登录设备"
        AppPage.EXTERNAL_AUTH -> "第三方登录"
        AppPage.ABOUT -> "关于与更新"
        else -> "家庭账本"
    }
    Box(Modifier.fillMaxSize().background(ledgerColors.background)) {
        if (home) {
            val header = Modifier.fillMaxWidth().height(240.dp)
            Image(
                androidx.compose.ui.res.painterResource(R.drawable.ledger_landscape), null, header,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            val shade = ledgerColors.background
            Box(header.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                shade.copy(alpha = if (ledgerColors.dark) .35f else .78f),
                shade.copy(alpha = if (ledgerColors.dark) .72f else .94f),
            ))))
        }
        Scaffold(
            containerColor = if (home) Color.Transparent else ledgerColors.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            snackbarHost = { SnackbarHost(feedback) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    expandedHeight = 56.dp,
                    title = {
                        if (home) Row(Modifier.clickable { monthPicker = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.ledgerMonth, fontSize = 18.sp)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                        } else Text(title)
                    },
                    navigationIcon = {
                        if (page != AppPage.LEDGER) IconButton(onClick = back) {
                            Icon(Icons.Outlined.ArrowBack, "返回")
                        } else if (home) IconButton(onClick = openDrawer) {
                            Icon(Icons.Outlined.Menu, "打开菜单")
                        }
                    },
                    actions = {
                        if (home) {
                            IconButton(onClick = { navigate(AppPage.CALENDAR) }) { Icon(Icons.Outlined.CalendarMonth, "日历视图") }
                            IconButton(onClick = { navigate(AppPage.REPORT) }) { Icon(Icons.Outlined.BarChart, "月报表") }
                        } else if (vm.token == null && page != AppPage.ABOUT) {
                            IconButton(onClick = { navigate(AppPage.ABOUT) }) { Icon(Icons.Outlined.Info, "关于") }
                        }
                    },
                )
            },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                if (home) FloatingActionButton(
                    onClick = { vm.newTransaction() },
                    modifier = Modifier.size(56.dp).testTag("add-transaction"),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Icon(Icons.Outlined.Add, "记一笔", Modifier.size(28.dp)) }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.token != null && vm.backgroundRefreshFailed) LedgerRefreshRetry(vm)
                content()
            }
        }
    }
    if (monthPicker) LedgerMonthPicker(vm.ledgerMonth, { monthPicker = false }) {
        vm.changeLedgerMonth(it)
        monthPicker = false
    }
}

@Composable
internal fun LedgerRefreshRetry(vm: BooksModel) {
    Row(
        Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp),
        verticalAlignment=Alignment.CenterVertically,
    ) {
        Text("账本数据刷新失败", Modifier.weight(1f), color=ledgerColors.muted, style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=vm::retryBackgroundRefresh, enabled=!vm.busy) { Text("重试刷新") }
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
                        val cny=daily[date]?.array("amounts")?.firstOrNull{it.optString("currency")==DEFAULT_LEDGER_CURRENCY}
                        val dayIncome=cny?.optString("incomeAmount")?.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                        val dayExpense=cny?.optString("expenseAmount")?.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                        Row(Modifier.fillMaxWidth().heightIn(min=40.dp).padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text(date.substring(5).replace('-','.')+if(date==java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())" 今天" else "",fontSize=14.sp,fontWeight=FontWeight.Bold)
                            Text(ledgerDaySummary(dayIncome,dayExpense),fontSize=12.sp,fontWeight=FontWeight.Bold)
                        }
                        rows.forEachIndexed{index,item->
                            if(index>0) LedgerTransactionDivider()
                            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("transaction-${item.id()}").clickable(enabled=!vm.busy){selected=item}.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(4.dp).background(when(item.optInt("type")){2->ledgerColors.income;3->ledgerColors.expense;else->ledgerColors.muted},androidx.compose.foundation.shape.CircleShape))
                                Column(Modifier.weight(1f).padding(start=12.dp,end=8.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically){Text(transactionLabel(item),fontSize=14.sp);Text(" · "+(item.optJSONObject("sourceAccount")?.optString("name")?:""),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=10.sp,color=ledgerColors.muted)}
                                    if(item.optString("comment").isNotBlank()||item.array("pictures").isNotEmpty())Text(item.optString("comment")+if(item.array("pictures").isNotEmpty())" · 图片" else "",maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp,color=ledgerColors.muted)
                                }
                                Text(ledgerTransactionAmount(item),fontSize=16.sp,color=when(item.optInt("type")){2->ledgerColors.income;3->ledgerColors.expense;else->MaterialTheme.colorScheme.onSurface})
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
@Composable internal fun LedgerMonthPicker(value:String,dismiss:()->Unit,select:(String)->Unit){
    val current=java.time.YearMonth.parse(value);var year by rememberSaveable{mutableIntStateOf(current.year)}
    AlertDialog(onDismissRequest=dismiss,containerColor=ledgerColors.surface,shape=androidx.compose.foundation.shape.RoundedCornerShape(4.dp),text={Row(Modifier.fillMaxWidth().height(300.dp)){
        LazyColumn(Modifier.width(96.dp),state=androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex=0)){items((current.year+3 downTo current.year-20).toList()){item->TextButton(onClick={year=item},modifier=Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.CircleShape,colors=ButtonDefaults.textButtonColors(containerColor=if(item==year)MaterialTheme.colorScheme.primary else Color.Transparent,contentColor=if(item==year)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)){Text("${item}年")}}}
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),Modifier.weight(1f)){items(12){index->TextButton(onClick={select(java.time.YearMonth.of(year,index+1).toString())}){Text("${index+1}月",color=if(year==current.year&&index+1==current.monthValue)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}}}
    }},confirmButton={})
}

@Composable private fun AccountsPage(vm: BooksModel) {
    var editingId by rememberSaveable { mutableStateOf("") }; var open by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<JSONObject?>(null) }
    val editing=vm.accounts.firstOrNull { it.id()==editingId }
    val orderedAccounts = sortAccountsForDisplay(vm.accounts)
    val visible=orderedAccounts.filter { !it.optBoolean("hidden") }
    val hidden=orderedAccounts.filter { it.optBoolean("hidden") }
    LazyColumn(Modifier.testTag("accounts-list"),contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { EntityWriteWarning(vm) };item { Button(onClick = { editingId = ""; open = true }, enabled = !vm.busy&&!vm.entityBlocked) { Icon(Icons.Outlined.Add, null); Text("新建账户") } }
        if (vm.accounts.isEmpty()) item { EmptyState("创建你的账户", "例如现金、银行卡。只管理自己的账户。") }
        item { Text("新记账会自动使用默认账户；点击账户按钮仍可切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(visible, key = { it.id() }) { item ->
            Card(Modifier.fillMaxWidth().clickable(enabled=!vm.busy&&!vm.entityBlocked) { editingId = item.id(); open = true }) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.optString("name"), style = MaterialTheme.typography.titleMedium)
                            if (item.id() == vm.defaultAccountId) AssistChip(onClick = {}, enabled = false, label = { Text("默认") }, modifier = Modifier.heightIn(min = 28.dp))
                        }
                        Text(item.optString("currency"), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(item.optString("currency")+" "+money(item.optString("balance")), style = MaterialTheme.typography.headlineSmall); Icon(Icons.Outlined.ChevronRight, "编辑账户")
                }
                Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=10.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(item.id()!=vm.defaultAccountId) TextButton(onClick={vm.setDefaultAccount(item.id())},enabled=!vm.busy&&!vm.entityBlocked){Text("设为默认")}
                    val siblings = visible.filter { accountDisplayGroupKey(it) == accountDisplayGroupKey(item) }
                    val position = siblings.indexOfFirst { it.id() == item.id() }
                    TextButton(onClick={vm.moveAccount(item.id(),-1)},enabled=!vm.busy&&!vm.entityBlocked&&position>0){Text("上移")}
                    TextButton(onClick={vm.moveAccount(item.id(),1)},enabled=!vm.busy&&!vm.entityBlocked&&position>=0&&position<siblings.lastIndex){Text("下移")}
                    TextButton(onClick={deleteTarget=item},enabled=!vm.busy&&!vm.entityBlocked){Text("删除",color=MaterialTheme.colorScheme.error)}
                }
            }
        }
        if(hidden.isNotEmpty()){
            item { TextButton(onClick={showHidden=!showHidden},enabled=!vm.busy){Text(if(showHidden)"隐藏归档账户" else "显示归档账户（${hidden.size}）")} }
            if(showHidden) items(hidden,key={"hidden-"+it.id()}) { item ->
                Card(Modifier.fillMaxWidth()) {
                    ListItem(headlineContent={Text(item.optString("name"))},supportingContent={Text(item.optString("currency")+" · 已归档")},trailingContent={TextButton(onClick={vm.hideAccount(item.id(),false)},enabled=!vm.busy&&!vm.entityBlocked){Text("恢复")}})
                }
            }
        }
    }
    if (open && (editingId.isEmpty() || editing!=null)) AccountEditor(vm, editing, close={ open = false })
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除账户") },
            text = { Text("系统会检查账户引用：无历史记录且余额为 0 时永久删除；否则自动归档并保留历史数据。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteOrArchiveAccount(item.id()) { deleteTarget = null } }, enabled = !vm.busy && !vm.entityBlocked) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
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

@Composable internal fun Choice(label: String, values: List<Pair<String, String>>, value: String, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(label + "：" + (values.firstOrNull { it.first == value }?.second ?: "请选择"), Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null)
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = { LazyColumn(Modifier.heightIn(max = 320.dp).testTag("choice-list")) {
        items(values) { pair -> ListItem(headlineContent = { Text(pair.second) }, leadingContent = { RadioButton(pair.first == value, null) }, modifier = Modifier.clickable(enabled=enabled) { if(enabled){choose(pair.first);expanded=false} }.heightIn(min = 48.dp)) }
    } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } })
}


@Composable internal fun AccountEditor(vm: BooksModel, original: JSONObject?, close: () -> Unit, submit: (String,JSONObject,()->Unit)->Unit = vm::saveEntity, preset:AccountPreset?=null) {
    var name by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("name") ?: preset?.label ?: "") }
    var balance by rememberSaveable(original?.id()) { mutableStateOf("0.00") }; var category by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("category") ?: preset?.category?.toString() ?: "1") }; var comment by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("comment") ?: "") }
    var localError by rememberSaveable(original?.id()) { mutableStateOf("") }
    val locked=vm.busy||vm.entityBlocked
    LaunchedEffect(vm.entityAwaitingReview,vm.busy){if(vm.entityAwaitingReview&&!vm.busy)close()}
    val requestId = rememberSaveable(original?.id()) { UUID.randomUUID().toString() }
    val balanceTime = rememberSaveable(original?.id()) { Instant.now().epochSecond }
    AlertDialog(onDismissRequest = {if(!vm.busy&&!vm.entityBlocked)close()}, title = { Text(if (original == null) "新建账户" else "编辑账户资料") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { if(!vm.busy&&!vm.entityBlocked&&it.length <= 64) name = it }, label = { Text("账户名称") }, singleLine = true,enabled=!locked)
        Choice("账户类型", listOf("1" to "现金", "2" to "银行卡", "3" to "信用卡", "4" to "虚拟账户", "5" to "负债", "6" to "应收款", "7" to "投资理财", "8" to "储蓄", "9" to "定期存款"), category,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)category = it }
        if (original == null) { Text("币种：CNY", style = MaterialTheme.typography.bodyMedium); OutlinedTextField(balance, { if(!vm.busy&&!vm.entityBlocked)balance = it }, label = { Text(initialAccountBalanceLabel(category.toIntOrNull()?:1,"CNY")) }, supportingText={if(isLiabilityAccountCategory(category.toIntOrNull()?:1))Text("填写当前欠款，金额不能为负数")}, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,enabled=!locked) }
        else { Text("币种：${original.optString("currency","CNY")}", style = MaterialTheme.typography.bodyMedium); Text("编辑资料不会调整余额。余额随记账更新。", style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(comment, { if(!vm.busy&&!vm.entityBlocked&&it.length <= 255) comment = it }, label = { Text("备注") },enabled=!locked)
        if (localError.isNotEmpty()) Text(localError, color = MaterialTheme.colorScheme.error)
        if (vm.error.isNotEmpty()) Text(vm.error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = {
        if(vm.busy||vm.entityBlocked)return@TextButton
        runCatching {
            require(name.isNotBlank()) { "请填写名称" }
            val body = JSONObject().put("name", name.trim()).put("category", category.toInt()).put("icon", original?.optString("icon") ?: preset?.icon ?: "1").put("iconType", 0).put("color", original?.optString("color") ?: preset?.color ?: "006B5C").put("comment", comment)
            if (original == null) { val minor = initialAccountBalanceMinor(balance,category.toInt()); body.put("type", 1).put("currency", DEFAULT_LEDGER_CURRENCY).put("balance", minor.toString()).put("balanceTime", balanceTime).put("clientSessionId", requestId) }
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
    LaunchedEffect(Unit) { vm.refreshCategoriesIfStale() }
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
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = if (isExpanded) expanded - parentItem.id() else expanded + parentItem.id() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryIconSlot(parentItem, selected = isExpanded, showMoreBadge = true)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(parentItem.optString("name"), style = MaterialTheme.typography.titleMedium,
                                    color = if(parentItem.optBoolean("hidden")) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(when(parentItem.optInt("type")) { 1 -> "收入"; 2 -> "支出"; else -> "转账" } + " · ${children.size} 个子分类", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { editingId = parentItem.id(); editingSnapshot=parentItem.toString(); open = true }, enabled=!vm.busy&&!vm.entityBlocked&&vm.categoryRevision!=null) { Icon(Icons.Outlined.Edit, "编辑${parentItem.optString("name")}") }
                            IconButton(onClick = { expanded = if (isExpanded) expanded - parentItem.id() else expanded + parentItem.id() }) { Icon(if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (isExpanded) "收起 ${parentItem.optString("name")}" else "展开 ${parentItem.optString("name")}") }
                        }
                        AnimatedVisibility(isExpanded) {
                            CategoryChildrenPanel(
                                children = children,
                                selectedId = "",
                                enabled = !vm.busy && !vm.entityBlocked && vm.categoryRevision != null,
                                notchColumn = -1,
                                onSelect = { child ->
                                    editingId = child.id()
                                    editingSnapshot = child.toString()
                                    open = true
                                },
                                modifier = Modifier.padding(bottom = 8.dp),
                                footer = {
                                    TextButton(
                                        onClick = { editingId = ""; editingSnapshot = ""; newParentId = parentItem.id(); newType = parentItem.optString("type", "2"); open = true },
                                        enabled = !vm.busy && !vm.entityBlocked && vm.categoryRevision != null && !parentItem.optBoolean("hidden"),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    ) { Icon(Icons.Outlined.Add, null); Text("添加子分类") }
                                }
                            )
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

@Composable internal fun CatalogEditor(
    vm: BooksModel,
    section: Int,
    original: JSONObject?,
    initialType: String = "2",
    initialParentId: String = "0",
    initialRevision: String? = vm.categoryRevision,
    close: () -> Unit,
    submit: (String, JSONObject, () -> Unit) -> Unit = vm::saveEntity,
) {
    var name by rememberSaveable(section, original?.id()) { mutableStateOf(original?.optString("name") ?: "") }; var type by rememberSaveable(section, original?.id(), initialType) { mutableStateOf(original?.optString("type") ?: initialType) }
    var icon by rememberSaveable(section, original?.id(), initialParentId) { mutableStateOf(original?.optString("icon")?.takeIf { it.isNotBlank() } ?: "1") }
    var parent by rememberSaveable(section, original?.id(), initialParentId) { mutableStateOf(original?.id("parentId") ?: initialParentId) }; var group by rememberSaveable(section, original?.id()) { mutableStateOf(original?.id("groupId") ?: "0") }
    val locked=vm.busy||vm.entityBlocked
    LaunchedEffect(vm.entityAwaitingReview,vm.busy){if(vm.entityAwaitingReview&&!vm.busy)close()}
    val requestId = rememberSaveable(section, original?.id()) { UUID.randomUUID().toString() }
    var openedRevision by rememberSaveable(section, original?.id()) { mutableStateOf(initialRevision.orEmpty()) }
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
    val maxNameLength = if (section == 0) 4 else 64
    AlertDialog(onDismissRequest = {if(!vm.busy&&!vm.entityBlocked)close()}, title = { Text((if (original == null) "新建" else "编辑") + label) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { if(!vm.busy&&!vm.entityBlocked&&it.length <= maxNameLength) name = it }, label = { Text("${label}名称") }, singleLine = true,enabled=!locked,
            supportingText = if (section == 0) ({ Text("分类名称最多 4 个字（${name.length}/4）") }) else null)
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
    }, enabled = !locked && name.trim().isNotBlank() && name.trim().length <= maxNameLength && (section!=0||openedRevision.isNotBlank()) && parentValid) { Text("保存${label}") } }, dismissButton = { TextButton(onClick = {if(!vm.busy&&!vm.entityBlocked)close()},enabled=!locked) { Text("取消") } })
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
        // 钱迹分类编辑页使用 4 列，给图标保留稳定的触控和视觉空间。
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("catalog-icon-picker"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = true
    ) {
        items(builtinCategoryIconIds, key = { it }) { id ->
            val selected = id == icon
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (selected) ledgerColors.selection else ledgerColors.field)
                    .clickable(enabled = enabled) { choose(id) }
                    .semantics { contentDescription = "分类图标 $id" },
                contentAlignment = Alignment.Center
            ) { BuiltinIcon(JSONObject().put("icon", id), Modifier.size(24.dp), tint = if (selected) ledgerColors.accent else ledgerColors.muted) }
        }
    }
}
