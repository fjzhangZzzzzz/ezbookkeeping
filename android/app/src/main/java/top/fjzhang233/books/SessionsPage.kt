package top.fjzhang233.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val androidVersion = Regex("""Android (\d+(?:\.\d+)*)""")
private val iosVersion = Regex("""OS (\d+(?:_\d+)*)""")
private val clientTokens = listOf(
    "FamilyBooks/" to "家庭账本", "PowerShell/" to "PowerShell",
    "EdgiOS/" to "Edge", "EdgA/" to "Edge", "Edg/" to "Edge", "OPR/" to "Opera",
    "SamsungBrowser/" to "Samsung Internet", "Firefox/" to "Firefox", "FxiOS/" to "Firefox",
    "Chrome/" to "Chrome", "CriOS/" to "Chrome", "Safari/" to "Safari", "curl/" to "curl",
)

internal fun sessionDeviceLabel(agent: String): String {
    val client = clientTokens.firstOrNull { agent.contains(it.first, ignoreCase = true) }?.second
    val system = when {
        "Android" in agent -> listOfNotNull("Android", androidVersion.find(agent)?.groupValues?.get(1)).joinToString(" ")
        "iPad" in agent || "iPhone" in agent || "iPod" in agent -> {
            val version = iosVersion.find(agent)?.groupValues?.get(1)?.replace('_', '.')
            listOfNotNull(if ("iPad" in agent) "iPadOS" else "iOS", version).joinToString(" ")
        }
        "Windows" in agent -> "Windows"
        "Macintosh" in agent -> "macOS"
        "CrOS" in agent -> "ChromeOS"
        "Linux" in agent -> "Linux"
        else -> null
    }
    return listOfNotNull(client, system).joinToString(" · ").ifEmpty { "未知客户端" }
}

private val sessionTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsPage(vm: BooksModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var revokeId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmOthers by rememberSaveable { mutableStateOf(false) }
    val selected = vm.sessions.firstOrNull { it.optString("tokenId") == selectedId }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = { confirmOthers = true }, enabled = !vm.busy) { Text("撤销其他所有会话") }
        }
        items(vm.sessions, key = { it.optString("tokenId") }) { item ->
            val current = item.optBoolean("isCurrent")
            val label = sessionDeviceLabel(item.optString("userAgent"))
            val lastSeen = item.optLong("lastSeen")
            val time = lastSeen.takeIf { it > 0 }?.let {
                runCatching { sessionTime.format(Instant.ofEpochSecond(it)) }.getOrNull()
            } ?: "未知"
            Surface(shape = MaterialTheme.shapes.medium, color = ledgerColors.surface) {
                SettingsRow(Icons.Outlined.Devices, label,
                    supporting = (if (current) "此设备 · " else "") + "最近活动 " + time,
                    onClick = { selectedId = item.optString("tokenId") },
                    trailing = { SettingsChevron("查看设备详情") },
                    testTag = if (current) "session-current" else "session-other")
            }
        }
    }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedId = null }, containerColor = ledgerColors.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设备详情", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { selectedId = null }) { Text("关闭") }
                }
                Text(sessionDeviceLabel(selected.optString("userAgent")), style = MaterialTheme.typography.titleMedium)
                Text("客户端标识", style = MaterialTheme.typography.labelMedium, color = ledgerColors.muted)
                Text(selected.optString("userAgent").ifBlank { "未提供" }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { revokeId = selectedId }, enabled = !vm.busy,
                    modifier = Modifier.testTag(if (selected.optBoolean("isCurrent")) "revoke-current-session" else "revoke-other-session")) {
                    Text("撤销此会话", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmOthers || (selected != null && revokeId == selectedId)) {
        val cancel = { revokeId = null; confirmOthers = false }
        AlertDialog(onDismissRequest = cancel, title = { Text("确认撤销会话？") },
            text = { Text(when {
                confirmOthers -> "其他设备均需重新登录，此设备保持登录。"
                selected?.optBoolean("isCurrent") == true -> "此设备将退出登录。"
                else -> "${sessionDeviceLabel(selected?.optString("userAgent").orEmpty())} 需要重新登录。"
            }) },
            confirmButton = { TextButton(enabled = !vm.busy, onClick = {
                if (confirmOthers) vm.revokeOthers()
                else if (selected != null) vm.revokeSession(selected.optString("tokenId"), selected.optBoolean("isCurrent"))
                cancel()
                selectedId = null
            }) { Text("确认撤销") } },
            dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
    }
}
