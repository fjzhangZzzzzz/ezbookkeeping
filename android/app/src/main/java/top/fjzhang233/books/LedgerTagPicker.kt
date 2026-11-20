package top.fjzhang233.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

/** Compact tag chooser measured against the native reference; selection commits with Save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedgerTagPicker(
    tags: List<JSONObject>,
    selected: List<String>,
    onSelected: (List<String>) -> Unit,
    dismiss: () -> Unit,
    manage: () -> Unit,
) {
    var pending by remember(selected) { mutableStateOf(selected.distinct().take(10)) }
    val visibleTags = tags.filterNot { it.optBoolean("hidden") }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.832f).testTag("entry-tags-picker"),
            shape = RoundedCornerShape(4.dp),
            color = ledgerColors.surface,
            contentColor = ledgerColors.text,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("标签", modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    TagPickerAction("管理", "entry-tags-manage") { onSelected(pending); dismiss(); manage() }
                    Spacer(Modifier.width(8.dp))
                    TagPickerAction("保存", "entry-tags-save") { onSelected(pending); dismiss() }
                }
                LedgerTransactionDivider(Modifier.padding(horizontal = 16.dp))
                if (visibleTags.isEmpty()) {
                    // The reference empty chooser has only a 12dp footer, not a large empty-state panel.
                    Spacer(Modifier.height(11.dp))
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            visibleTags.forEach { tag ->
                                val id = tag.id()
                                val checked = id in pending
                                val available = checked || pending.size < 10
                                val interactions = remember { MutableInteractionSource() }
                                val shape = RoundedCornerShape(16.dp)
                                Box(
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).toggleable(
                                        value = checked,
                                        interactionSource = interactions,
                                        indication = null,
                                        enabled = available,
                                        role = Role.Checkbox,
                                        onValueChange = { next -> pending = if (next) (pending + id).distinct().take(10) else pending - id },
                                    ).testTag("entry-tags-option-$id"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        modifier = Modifier.ledgerControlFeedback(interactions, shape),
                                        shape = shape,
                                        color = if (checked) ledgerColors.selection else ledgerColors.field,
                                        contentColor = if (checked) ledgerColors.accent else if (available) ledgerColors.text else ledgerColors.muted,
                                        border = BorderStroke(1.dp, if (checked) ledgerColors.accent else ledgerColors.divider),
                                    ) {
                                        Text(tag.optString("name"), Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        if (pending.size >= 10) Text("最多选择 10 个标签", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = ledgerColors.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun TagPickerAction(label: String, tag: String, action: () -> Unit) {
    LedgerControl(Modifier.width(58.dp).testTag(tag), onClick = action) { interactions ->
        val shape = RoundedCornerShape(4.dp)
        Surface(modifier = Modifier.ledgerControlFeedback(interactions, shape), shape = shape,
            color = Color.Transparent, border = BorderStroke(1.dp, ledgerColors.divider)) {
            Box(Modifier.width(58.dp).height(26.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp) }
        }
    }
}
