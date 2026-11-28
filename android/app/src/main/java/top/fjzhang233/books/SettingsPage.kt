package top.fjzhang233.books

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.CurrencyYuan
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun SettingsPage(vm: BooksModel, navigate: (AppPage) -> Unit) {
    var logoutConfirm by rememberSaveable { mutableStateOf(false) }
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var themeError by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showPerformance by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val themeMode = rememberThemeMode()

    LazyColumn(
        modifier = Modifier.testTag("settings-page"),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = ledgerColors.selection,
                ) {
                    Icon(Icons.Outlined.People, "家庭信息", Modifier.padding(12.dp), tint = ledgerColors.accent)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(vm.nickname, style = MaterialTheme.typography.titleLarge)
                    Text("家庭账本 · 每人独立记账，共同看见收支", style = MaterialTheme.typography.bodyMedium, color = ledgerColors.muted)
                }
            }
        }
        item {
            SettingsSection("外观") {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    title = "主题",
                    supporting = themeMode.label,
                    onClick = { themePicker = true },
                    trailing = { Icon(Icons.Outlined.ChevronRight, "选择主题", tint = ledgerColors.muted) },
                    testTag = "settings-theme",
                )
            }
        }
        item {
            SettingsSection("记账") {
                SettingsRow(
                    icon = Icons.Outlined.CurrencyYuan,
                    title = "默认货币",
                    trailing = { Text("人民币（$DEFAULT_LEDGER_CURRENCY）", color = ledgerColors.muted) },
                    testTag = "settings-default-currency",
                )
                SettingsDivider()
                SettingsRow(Icons.Outlined.AccountBalanceWallet, "账户", onClick = { navigate(AppPage.ACCOUNTS) }, trailing = { SettingsChevron("账户") }, testTag = "settings-accounts")
                SettingsDivider()
                SettingsRow(Icons.Outlined.Category, "分类与标签", onClick = { navigate(AppPage.CATALOG) }, trailing = { SettingsChevron("分类与标签") }, testTag = "settings-catalog")
            }
        }
        item {
            SettingsSection("登录与安全") {
                SettingsRow(Icons.Outlined.Devices, "登录设备", supporting = "管理已登录的设备和会话", onClick = { navigate(AppPage.SESSIONS) }, trailing = { SettingsChevron("登录设备") }, testTag = "settings-sessions")
                SettingsDivider()
                SettingsRow(
                    Icons.Outlined.Link,
                    "第三方登录",
                    supporting = if (vm.externalLinked) "已关联 Pocket ID" else "未关联第三方登录",
                    onClick = { navigate(AppPage.EXTERNAL_AUTH) },
                    trailing = { SettingsChevron("第三方登录") },
                    testTag = "settings-external-auth",
                )
            }
        }
        item {
            SettingsSection("应用") {
                SettingsRow(
                    Icons.Outlined.Speed,
                    "秒开模式",
                    supporting = "冷启动时直接打开记账页面",
                    onClick = { if (!vm.busy) vm.updateInstantEntry(!vm.instantEntry) },
                    trailing = { Switch(checked = vm.instantEntry, onCheckedChange = vm::updateInstantEntry, enabled = !vm.busy) },
                    testTag = "settings-instant-entry",
                )
                SettingsDivider()
                SettingsRow(Icons.Outlined.Info, "关于与更新", onClick = { navigate(AppPage.ABOUT) }, trailing = { SettingsChevron("关于与更新") }, testTag = "settings-about")
            }
        }
        item {
            SettingsSection("高级设置") {
                SettingsRow(
                    Icons.Outlined.Tune,
                    "高级设置",
                    supporting = if (advancedExpanded) "已展开" else "诊断与排障",
                    onClick = { advancedExpanded = !advancedExpanded },
                    trailing = { Icon(if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "展开高级设置", tint = ledgerColors.muted) },
                    testTag = "settings-advanced",
                )
                if (advancedExpanded) {
                    SettingsDivider()
                    SettingsRow(
                        Icons.Outlined.Speed,
                        "性能诊断",
                        supporting = "仅保存在本机，可查看摘要、导出或清理",
                        onClick = { showPerformance = true },
                        trailing = { SettingsChevron("性能诊断") },
                        testTag = "settings-performance",
                    )
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = ledgerColors.surface,
            ) {
                SettingsRow(
                    Icons.AutoMirrored.Outlined.Logout,
                    "退出登录",
                    onClick = { logoutConfirm = true },
                    titleColor = MaterialTheme.colorScheme.error,
                    iconTint = MaterialTheme.colorScheme.error,
                    testTag = "settings-logout",
                )
            }
        }
    }

    if (logoutConfirm) {
        AlertDialog(
            onDismissRequest = { logoutConfirm = false },
            title = { Text("退出此设备？") },
            text = { Text("退出会撤销此设备当前会话。其他设备可在登录设备页管理。") },
            confirmButton = { TextButton(onClick = { logoutConfirm = false; vm.logout() }, enabled = !vm.busy) { Text("退出") } },
            dismissButton = { TextButton(onClick = { logoutConfirm = false }) { Text("取消") } },
        )
    }
    if (themePicker) {
        ThemePickerSheet(
            selected = themeMode,
            error = themeError,
            onDismiss = { themePicker = false; themeError = false },
            save = { mode ->
                themeError = !saveThemeMode(context, mode)
                !themeError
            },
        )
    }
    if (showPerformance) PerformanceDiagnosticsDialog(vm, context) { showPerformance = false }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = ledgerColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = ledgerColors.surface) {
            Column { content() }
        }
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = ledgerColors.muted,
    testTag: String = "",
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .then(if (testTag.isBlank()) Modifier else Modifier.testTag(testTag))
        .padding(horizontal = 16.dp, vertical = 12.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = iconTint)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ledgerColors.muted) }
        }
        trailing?.invoke()
    }
}

@Composable internal fun SettingsChevron(description: String) { Icon(Icons.Outlined.ChevronRight, description, tint = ledgerColors.muted) }
@Composable internal fun SettingsDivider() { LedgerTransactionDivider(Modifier.padding(horizontal = 16.dp)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePickerSheet(selected: ThemeMode, error: Boolean, onDismiss: () -> Unit, save: (ThemeMode) -> Boolean) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close() {
        scope.launch { sheetState.hide(); onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ledgerColors.surface,
        contentColor = ledgerColors.text,
    ) {
        Column(Modifier.fillMaxWidth().testTag("theme-sheet").verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("主题", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = ::close) { Text("取消") }
            }
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingsDivider()
                    val checked = selected == mode
                    val icon = when (mode) {
                        ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                        ThemeMode.LIGHT -> Icons.Outlined.LightMode
                        ThemeMode.DARK -> Icons.Outlined.DarkMode
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = checked, role = Role.RadioButton) { if (save(mode)) close() }
                            .testTag("theme-${mode.key}").padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, Modifier.size(24.dp), tint = if (checked) ledgerColors.accent else ledgerColors.muted)
                        Text(mode.label, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = checked, onClick = null)
                    }
                }
            }
            if (error) Text(
                "主题设置未能保存，请重试",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class DiagnosticMetric(val name: String, val count: Int, val average: Long, val maximum: Long)

private fun summarizeDiagnostics(raw: String): List<DiagnosticMetric> {
    val events = raw.lineSequence().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.toList()
    return events.groupBy { it.optString("name") }.map { (name, values) ->
        val durations = values.map { it.optLong("durationMs").coerceAtLeast(0) }
        DiagnosticMetric(name, values.size, durations.average().toLong(), durations.maxOrNull() ?: 0)
    }.sortedWith(compareBy({ it.name }))
}

@Composable
private fun PerformanceDiagnosticsDialog(vm: BooksModel, context: Context, dismiss: () -> Unit) {
    val raw = vm.performanceDiagnosticsText()
    val metrics = remember(raw) { summarizeDiagnostics(raw) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("性能诊断") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅保存在本机，不上传账本内容、金额、关键词或登录凭据。", style = MaterialTheme.typography.bodySmall)
                if (metrics.isEmpty()) Text("暂无性能记录", color = ledgerColors.muted)
                else metrics.forEach { metric -> Text("${metric.name}\n${metric.count} 次 · 平均 ${metric.average}ms · 最大 ${metric.maximum}ms", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = dismiss) { Text("关闭") }
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, raw), "导出性能诊断")) }) { Text("导出") }
            }
        },
        dismissButton = { TextButton(onClick = { vm.clearPerformanceDiagnostics(); dismiss() }) { Text("清理") } },
    )
}

@Composable
internal fun ExternalAuthPage(vm: BooksModel) {
    var unlinkConfirm by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var unlinkStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.busy, vm.externalLinked, vm.error) {
        if (unlinkStarted && !vm.busy) {
            if (!vm.externalLinked && vm.error.isBlank()) {
                unlinkConfirm = false
                password = ""
            }
            unlinkStarted = false
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.testTag("external-auth-page")) {
        item {
            SettingsSection("当前状态") {
                if (vm.externalLinked) {
                    SettingsRow(Icons.Outlined.Link, "Pocket ID", supporting = "已关联，可用于登录此账本", trailing = { Text("已关联", color = MaterialTheme.colorScheme.primary) }, testTag = "external-auth-linked")
                    SettingsDivider()
                    SettingsRow(Icons.AutoMirrored.Outlined.Logout, "解除关联", supporting = "解除后仍可使用本地密码登录", onClick = { unlinkConfirm = true }, titleColor = MaterialTheme.colorScheme.error, iconTint = MaterialTheme.colorScheme.error, testTag = "external-auth-unlink")
                } else {
                    SettingsRow(Icons.Outlined.Link, "关联 Pocket ID", supporting = "未关联，关联后可用于登录此账本", onClick = { vm.beginOidc(true) }, trailing = { SettingsChevron("关联 Pocket ID") }, testTag = "external-auth-link")
                }
            }
        }
        item { Text("关联和解绑都会经过身份验证；解绑操作不会删除账本或流水。", style = MaterialTheme.typography.bodySmall, color = ledgerColors.muted) }
    }
    if (unlinkConfirm) {
        AlertDialog(
            onDismissRequest = { if (!vm.busy) { unlinkConfirm = false; password = ""; unlinkStarted = false } },
            title = { Text("解除 Pocket ID 关联") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入当前本地密码确认。")
                    androidx.compose.material3.OutlinedTextField(password, { password = it }, label = { Text("本地密码") }, singleLine = true, enabled = !vm.busy, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { unlinkStarted = true; vm.unlinkExternalAuth(password) }, enabled = !vm.busy && password.isNotBlank()) { Text("解除关联") } },
            dismissButton = { TextButton(onClick = { unlinkConfirm = false; password = ""; unlinkStarted = false }, enabled = !vm.busy) { Text("取消") } },
        )
    }
}
