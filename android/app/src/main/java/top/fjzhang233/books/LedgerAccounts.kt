package top.fjzhang233.books

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

@Composable internal fun AccountsPage(vm: BooksModel) {
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
