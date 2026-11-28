package top.fjzhang233.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

@Composable internal fun AccountsPage(vm: BooksModel, back: () -> Unit, feedback: SnackbarHostState) {
    var editingId by rememberSaveable { mutableStateOf("") }; var open by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<JSONObject?>(null) }
    val editing=vm.accounts.firstOrNull { it.id()==editingId }
    val orderedAccounts = sortAccountsForDisplay(vm.accounts)
    val visible=orderedAccounts.filter { !it.optBoolean("hidden") }
    val hidden=orderedAccounts.filter { it.optBoolean("hidden") }
    val baseline = visible.map { it.id() }
    var originalOrder by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var draft by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var activeGroup by remember { mutableStateOf<String?>(null) }
    val byId = visible.associateBy { it.id() }
    val order = draft ?: baseline
    val groups = order.mapNotNull(byId::get).groupBy(::accountDisplayGroupKey)
    val sorting = draft != null
    val writable = !vm.busy && !vm.entityBlocked
    val canMove = writable && (!sorting || originalOrder == baseline)
    val listState = rememberLazyListState()
    fun move(source: String, target: String): Boolean {
        val item = byId[source] ?: return false
        val next = byId[target] ?: return false
        if (!canMove || source == target || accountDisplayGroupKey(item) != accountDisplayGroupKey(next)) return false
        val changed = order.toMutableList()
        val dest = changed.indexOf(target)
        if (dest < 0 || !changed.remove(source)) return false
        changed.add(dest, source)
        originalOrder = if (changed == baseline) null else originalOrder ?: baseline
        draft = changed.takeUnless { it == baseline }
        return true
    }
    val reorder = rememberReorderableLazyListState(listState) { from, to -> move(from.key.toString(), to.key.toString()) }
    fun leave() { if (sorting) discard = true else back() }
    BackHandler(enabled = sorting) { discard = true }
    BooksScaffold(vm, AppPage.ACCOUNTS, feedback, ::leave, {}, {}, actions = {
        IconButton(onClick = { editingId = ""; open = true }, enabled = writable && !sorting) {
            Icon(Icons.Outlined.Add, "新建账户")
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("accounts-list"), contentPadding = PaddingValues(16.dp)) {
                item { EntityWriteWarning(vm) }
                if (vm.accountRefreshFailed || vm.accountRefreshPending) item { AccountRefreshNotice(vm) }
                if (vm.accounts.isEmpty()) item { EmptyState("创建你的账户", "例如现金、银行卡。只管理自己的账户。") }
                items(order.mapNotNull(byId::get), key = { it.id() }) { item ->
                    val id = item.id()
                    val group = accountDisplayGroupKey(item)
                    val siblings = groups.getValue(group).map { it.id() }
                    val sortable = canMove && siblings.size > 1
                    var menu by remember { mutableStateOf(false) }
                    ReorderableItem(reorder, key = id, enabled = sortable && (activeGroup == null || activeGroup == group)) { dragging ->
                        Surface(color = ledgerColors.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            topStart = if (id == siblings.first()) 12.dp else 0.dp, topEnd = if (id == siblings.first()) 12.dp else 0.dp,
                            bottomStart = if (id == siblings.last()) 12.dp else 0.dp, bottomEnd = if (id == siblings.last()) 12.dp else 0.dp),
                            shadowElevation = if (dragging) 4.dp else 0.dp) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).heightIn(min = 56.dp).testTag("account-row-$id").clickable(enabled = writable && !sorting) {
                                    editingId = id; open = true
                                }, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (id == vm.defaultAccountId) Text("默认", Modifier.testTag("account-default-$id"), style = MaterialTheme.typography.labelSmall, color = ledgerColors.accent)
                                    }
                                    Text(item.optString("currency") + " " + money(item.optString("balance")), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (sortable) {
                                    val name = item.optString("name")
                                    val index = siblings.indexOf(id)
                                    Box(Modifier.size(48.dp).longPressDraggableHandle(onDragStarted = { activeGroup = group }, onDragStopped = { activeGroup = null })
                                        .testTag("account-sort-handle-$id").semantics {
                                            contentDescription = "长按拖动排序 $name"
                                            customActions = buildList {
                                                siblings.getOrNull(index - 1)?.let { add(CustomAccessibilityAction("上移") { move(id, it) }) }
                                                siblings.getOrNull(index + 1)?.let { add(CustomAccessibilityAction("下移") { move(id, it) }) }
                                            }
                                        }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.DragHandle, null, Modifier.size(20.dp), tint = ledgerColors.muted) }
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = writable && !sorting, modifier = Modifier.testTag("account-menu-$id")) { Icon(Icons.Outlined.MoreVert, "账户选项") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        if (id != vm.defaultAccountId) DropdownMenuItem(text = { Text("设为默认") }, modifier = Modifier.testTag("account-set-default-$id"),
                                            onClick = { menu = false; vm.setDefaultAccount(id) })
                                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, modifier = Modifier.testTag("account-remove-$id"),
                                            onClick = { menu = false; deleteTarget = item })
                                    }
                                }
                            }
                        }
                    }
                    if (id != siblings.last()) LedgerTransactionDivider()
                    else Spacer(Modifier.height(8.dp))
                }
                if(hidden.isNotEmpty()){
                    item { TextButton(onClick={showHidden=!showHidden},enabled=!vm.busy){Text(if(showHidden)"隐藏归档账户" else "显示归档账户（${hidden.size}）")} }
                    if(showHidden) items(hidden,key={"hidden-"+it.id()}) { item ->
                        Card(Modifier.fillMaxWidth().testTag("account-row-${item.id()}")) {
                            ListItem(headlineContent={Text(item.optString("name"))},supportingContent={Text(item.optString("currency")+" · 已归档")},trailingContent={TextButton(onClick={vm.hideAccount(item.id(),false)},enabled=writable && !sorting,modifier=Modifier.testTag("account-restore-${item.id()}")){Text("恢复")}})
                        }
                    }
                }
            }
            if (sorting) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { discard = true }, enabled = !vm.busy) { Text("取消") }
                Button(onClick = { vm.saveAccountOrder(originalOrder.orEmpty(), order) { draft = null; originalOrder = null } },
                    enabled = canMove) { Text("保存排序") }
                if (originalOrder != baseline) Text("账户已更新，请取消后重新排序")
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存排序？") },
        confirmButton = { TextButton(onClick = { draft = null; originalOrder = null; discard = false }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续调整") } })
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

@Composable internal fun AccountRefreshNotice(vm: BooksModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.accountRefreshPending) {
            Text("账户余额更新中，暂显示上次结果", Modifier.weight(1f), color = ledgerColors.muted)
        } else {
            Text("已保存，账户列表刷新失败", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryAccountRefresh, enabled = !vm.busy) { Text("重试刷新账户") }
        }
    }
}

@Composable internal fun AccountEditor(vm: BooksModel, original: JSONObject?, close: () -> Unit, submit: (String,JSONObject,()->Unit)->Unit = vm::saveEntity, preset:AccountPreset?=null) {
    var name by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("name") ?: preset?.label ?: "") }
    var balance by rememberSaveable(original?.id()) { mutableStateOf("0.00") }; var category by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("category") ?: preset?.category?.toString() ?: "1") }; var comment by rememberSaveable(original?.id()) { mutableStateOf(original?.optString("comment") ?: "") }
    var localError by rememberSaveable(original?.id()) { mutableStateOf("") }
    val locked=vm.busy||vm.entityBlocked
    LaunchedEffect(vm.entityAwaitingReview,vm.busy){if(vm.entityAwaitingReview&&!vm.busy)close()}
    val requestId = rememberSaveable(original?.id()) { UUID.randomUUID().toString() }
    val balanceTime = rememberSaveable(original?.id()) { Instant.now().epochSecond }
    val modified = name != (original?.optString("name") ?: preset?.label.orEmpty()) ||
        category != (original?.optString("category") ?: preset?.category?.toString() ?: "1") ||
        comment != original?.optString("comment").orEmpty() || balance != "0.00"
    EntityEditorSheet(locked = locked, modified = modified, dismiss = close, title = if (original == null) "新建账户" else "编辑账户资料", content = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = name, onValueChange = { if(!vm.busy&&!vm.entityBlocked&&it.length <= 64) name = it }, label = { Text("账户名称") }, singleLine = true,enabled=!locked)
        Choice("账户类型", listOf("1" to "现金", "2" to "银行卡", "3" to "信用卡", "4" to "虚拟账户", "5" to "负债", "6" to "应收款", "7" to "投资理财", "8" to "储蓄", "9" to "定期存款"), category,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)category = it }
        if (original == null) { Text("币种：CNY", style = MaterialTheme.typography.bodyMedium); OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = balance, onValueChange = { if(!vm.busy&&!vm.entityBlocked)balance = it }, label = { Text(initialAccountBalanceLabel(category.toIntOrNull()?:1,"CNY")) }, supportingText={if(isLiabilityAccountCategory(category.toIntOrNull()?:1))Text("填写当前欠款，金额不能为负数")}, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,enabled=!locked) }
        else { Text("币种：${original.optString("currency","CNY")}", style = MaterialTheme.typography.bodyMedium); Text("编辑资料不会调整余额。余额随记账更新。", style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = comment, onValueChange = { if(!vm.busy&&!vm.entityBlocked&&it.length <= 255) comment = it }, label = { Text("备注") },enabled=!locked)
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
    }, enabled = !locked && name.isNotBlank()) { Text("保存账户") } })
}
