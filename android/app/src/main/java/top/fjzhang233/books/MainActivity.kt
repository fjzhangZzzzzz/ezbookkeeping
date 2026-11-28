package top.fjzhang233.books

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
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
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

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
            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            onBack = {
                when (current) {
                    EntryPage -> if (!vm.busy && !vm.transactionNeedsConfirmation) vm.closeTransaction()
                    else -> pop(current)
                }
            },
            entryProvider = entryProvider<NavKey> {
                entry<AppPage> { page ->
                    val showTransactionSheets = activeStack.none { it is CategoryStatisticsPage }
                    when (page) {
                        AppPage.REPORT -> LedgerReport(
                            vm, { pop(page) }, ::openChart,
                            showReportSheets = showTransactionSheets,
                            openStatistics = ::push,
                        )
                        AppPage.FAMILY -> FamilyReportPage(vm, { pop(page) }, ::openChart, ::push, activeStack.none { it is FamilyStatisticsPage })
                        AppPage.CALENDAR -> LedgerCalendar(vm, showTransactionSheets, ::push) { pop(page) }
                        AppPage.ACCOUNTS -> AccountsPage(vm, { pop(page) }, feedback)
                        AppPage.CATALOG -> CatalogPage(vm, back = { pop(page) }, feedback = feedback)
                        else -> BooksScaffold(vm, page, feedback, { pop(page) }, { moveDrawer(DrawerValue.Open) }, ::push) {
                            when (page) {
                                AppPage.ABOUT -> UpdatePage(updates)
                                AppPage.LEDGER -> if (authenticated) TransactionsPage(vm, showTransactionSheets, ::push) else LoginPage(vm)
                                AppPage.SEARCH -> LedgerSearch(vm) { item, close ->
                                    if (showTransactionSheets) LedgerTransactionDetails(vm, item, ::push, dismiss = close)
                                }
                                AppPage.SETTINGS -> SettingsPage(vm) { destination ->
                                    push(destination)
                                    if (destination == AppPage.SESSIONS) vm.getSessions()
                                }

                                AppPage.SESSIONS -> SessionsPage(vm)
                                AppPage.EXTERNAL_AUTH -> ExternalAuthPage(vm)
                                else -> Unit
                            }
                        }
                    }
                }
                entry<EntryPage>(metadata = fullScreenDialog) {
                    LedgerWindowAppearance()
                    if (authenticated && vm.showEditor) TransactionEditor(vm) { push(EntryCatalogPage(it)) }
                }
                entry<EntryCatalogPage>(metadata = fullScreenDialog) { page ->
                    LedgerWindowAppearance()
                    EntryCatalogContent(vm, page.section) { pop(page) }
                }
                entry<CategoryStatisticsPage>(metadata = fullScreenDialog) { page ->
                    LedgerWindowAppearance()
                    ReportCategoryStatistics(vm, page) { pop(page) }
                }
                entry<FamilyStatisticsPage>(metadata = fullScreenDialog) { page ->
                    LedgerWindowAppearance()
                    FamilyCategoryStatistics(vm, page) { pop(page) }
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
internal fun BooksScaffold(
    vm: BooksModel,
    page: AppPage,
    feedback: SnackbarHostState,
    back: () -> Unit,
    openDrawer: () -> Unit,
    navigate: (AppPage) -> Unit,
    actions: @Composable () -> Unit = {},
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
                        if (home) LedgerMonthControl(vm.ledgerMonth, Modifier.testTag("ledger-month-picker")) { monthPicker = true } else Text(title)
                    },
                    navigationIcon = {
                        if (page != AppPage.LEDGER) IconButton(onClick = back) {
                            Icon(Icons.Outlined.ArrowBack, "返回")
                        } else if (home) IconButton(onClick = openDrawer) {
                            Icon(Icons.Outlined.Menu, "打开菜单")
                        }
                    },
                    actions = {
                        actions()
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
@Composable internal fun EmptyState(title: String, body: String) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TransactionsPage(vm: BooksModel, showTransactionSheets: Boolean, openStatistics: (CategoryStatisticsPage) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
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
                            LedgerTransactionRow(item,index==0,Modifier.testTag("transaction-${item.id()}"),
                                showAccount=true,enabled=!vm.busy){selectedId=item.id()}
                        }
                    }
                }
            }
        }
        if(vm.hasMoreTransactions) item{TextButton(onClick=vm::loadMore,enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("加载更多")}}
    }
    if (showTransactionSheets) vm.transactions.selectedTransaction(selectedId)?.let { item ->
        LedgerTransactionDetails(vm, item, openStatistics) { selectedId = null }
    }
}
@Composable internal fun LedgerMonthControl(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, open: () -> Unit) {
    LedgerControl(modifier, enabled = enabled, onClick = open) { interactions ->
        Row(Modifier.ledgerControlFocus(interactions).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 18.sp)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
    }
}

@Composable internal fun LedgerMonthPicker(value:String,dismiss:()->Unit,select:(String)->Unit){
    val current=java.time.YearMonth.parse(value);var year by rememberSaveable{mutableIntStateOf(current.year)}
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("ledger-month-dialog"),containerColor=ledgerColors.surface,shape=androidx.compose.foundation.shape.RoundedCornerShape(4.dp),text={Row(Modifier.fillMaxWidth().height(300.dp)){
        LazyColumn(Modifier.width(96.dp),state=androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex=0)){items((current.year+3 downTo current.year-20).toList()){item->TextButton(onClick={year=item},modifier=Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.CircleShape,colors=ButtonDefaults.textButtonColors(containerColor=if(item==year)MaterialTheme.colorScheme.primary else Color.Transparent,contentColor=if(item==year)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)){Text("${item}年")}}}
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),Modifier.weight(1f)){items(12){index->TextButton(onClick={select(java.time.YearMonth.of(year,index+1).toString())}){Text("${index+1}月",color=if(year==current.year&&index+1==current.monthValue)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}}}
    }},confirmButton={})
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

@Composable internal fun Choice(label: String, values: List<Pair<String, String>>, value: String, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(label + "：" + (values.firstOrNull { it.first == value }?.second ?: "请选择"), Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null)
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = { LazyColumn(Modifier.heightIn(max = 320.dp).testTag("choice-list")) {
        items(values) { pair -> ListItem(headlineContent = { Text(pair.second) }, leadingContent = { RadioButton(pair.first == value, null) }, modifier = Modifier.clickable(enabled=enabled) { if(enabled){choose(pair.first);expanded=false} }.heightIn(min = 48.dp)) }
    } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } })
}
