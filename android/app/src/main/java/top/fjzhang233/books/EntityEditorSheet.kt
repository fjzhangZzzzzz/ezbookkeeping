package top.fjzhang233.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun EntityEditorSheet(
    locked: Boolean,
    modified: Boolean,
    dismiss: () -> Unit,
    title: String,
    content: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val mayClose by rememberUpdatedState {
        if (!locked && modified) confirmDiscard = true
        !locked && !modified
    }
    val requestClose = { if (mayClose()) dismiss() }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || mayClose() })
    ModalBottomSheet(onDismissRequest = requestClose, sheetState = state,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        containerColor = ledgerColors.surface, dragHandle = null) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = requestClose, enabled = !locked, modifier = Modifier.align(Alignment.CenterStart)) { Text("取消") }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.align(Alignment.CenterEnd)) { confirmButton() }
            }
            content()
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存修改？") },
        confirmButton = { TextButton(onClick = dismiss, enabled = !locked) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
}
