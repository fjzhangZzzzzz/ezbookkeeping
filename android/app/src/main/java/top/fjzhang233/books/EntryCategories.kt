package top.fjzhang233.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Only a visible child of a visible parent may be newly selected. */
internal fun entryVisibleCategories(categories: List<JSONObject>, transactionType: Int): List<JSONObject> {
    val type = when (transactionType) { 2 -> 1; 4 -> 3; else -> 2 }
    val visibleParents = categories.filter { it.id("parentId") == "0" && !it.optBoolean("hidden") && it.optInt("type") == type }.map { it.id() }.toSet()
    return categories.filter { !it.optBoolean("hidden") && it.id("parentId") in visibleParents && it.optInt("type") == type }
}

/** An existing transaction can keep its original category even if it is no longer selectable. */
internal fun entryPreservesOriginalCategory(original: JSONObject?, selectedId: String, transactionType: Int): Boolean =
    original != null && selectedId.isNotBlank() && selectedId != "0" &&
        original.id("categoryId") == selectedId && original.optInt("type") == transactionType

/** Protected internal transfer categories require an explicit choice. */
internal fun entryDefaultCategory(visibleCategories: List<JSONObject>): String =
    visibleCategories.firstOrNull { !it.optBoolean("familyInternalTransfer") }?.id().orEmpty()

/**
 * 钱迹式记账分类选择器：父分类五列网格，当前父分类的子分类单独成面板。
 * 选择器只负责展示和选择，分类编辑仍由记账页的管理入口处理。
 */
@Composable
internal fun EntryCategoryPicker(
    categories: List<JSONObject>,
    transactionType: Int,
    selectedId: String,
    enabled: Boolean,
    onSelect: (JSONObject) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = entryVisibleCategories(categories, transactionType)
    val parentById = remember(categories, transactionType) {
        categories.filter { it.id("parentId") == "0" && !it.optBoolean("hidden") }
            .filter { parent ->
                val mappedType = when (transactionType) { 2 -> 1; 4 -> 3; else -> 2 }
                parent.optInt("type") == mappedType && visible.any { it.id("parentId") == parent.id() }
            }
    }
    val childrenByParent = remember(visible) { visible.groupBy { it.id("parentId") } }
    val selectedParent = categories.firstOrNull { it.id() == selectedId }?.id("parentId")
        ?.takeIf { id -> parentById.any { it.id() == id } }
    var expandedParentId by rememberSaveable(transactionType) { mutableStateOf(selectedParent ?: parentById.firstOrNull()?.id().orEmpty()) }
    LaunchedEffect(selectedParent, parentById) {
        if (!selectedParent.isNullOrBlank() && selectedParent != expandedParentId) expandedParentId = selectedParent
        else if (expandedParentId.isBlank() || parentById.none { it.id() == expandedParentId }) expandedParentId = parentById.firstOrNull()?.id().orEmpty()
    }
    val expandedChildren = childrenByParent[expandedParentId].orEmpty()
    Column(modifier.fillMaxWidth().background(ledgerColors.surface)) {
        if (parentById.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxWidth().height((((parentById.size + 4) / 5) * 76).dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(parentById, key = { it.id() }) { parent ->
                    val selected = parent.id() == expandedParentId
                    EntryCategoryTile(parent, selected, enabled) {
                        expandedParentId = parent.id()
                    }
                }
            }
        }
        if (expandedChildren.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) { Spacer(Modifier.size(1.dp)) }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (MaterialTheme.colorScheme.surfaceVariant == Color.Transparent) ledgerColors.controlSurface else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxWidth().height((((expandedChildren.size.coerceAtLeast(1) + 4) / 5 * 76)).dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(expandedChildren, key = { it.id() }) { child ->
                        EntryCategoryTile(child, child.id() == selectedId, enabled) { onSelect(child) }
                    }
                }
            }
        }
        if (parentById.isEmpty() && visible.isEmpty()) {
            Text("尚无可用分类，请先在菜单中维护分类。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EntryCategoryTile(item: JSONObject, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(68.dp).testTag("entry-category-${item.id()}").semantics { this.selected = selected }
            .clickable(enabled = enabled, onClick = onClick).padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (selected) ledgerColors.selection else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            BuiltinIcon(item, Modifier.size(26.dp), tint = if (selected) MaterialTheme.colorScheme.primary else ledgerColors.muted)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            item.optString("name"), maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp, lineHeight = 16.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (selected && item.id("parentId") == "0") {
            val triangleColor = MaterialTheme.colorScheme.primary
            Box(
                Modifier.size(16.dp, 6.dp).drawBehind {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, triangleColor)
                }
            )
        }
    }
}
