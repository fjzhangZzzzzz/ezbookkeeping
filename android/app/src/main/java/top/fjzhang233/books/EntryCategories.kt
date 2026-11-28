package top.fjzhang233.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Only a visible child of a visible parent may be newly selected. */
internal fun entryVisibleCategories(categories: List<JSONObject>, transactionType: Int): List<JSONObject> {
    val type = when (transactionType) { 2 -> 1; 4 -> 3; else -> 2 }
    val ordered = sortCategoriesForDisplay(categories)
    val visibleParents = ordered.filter { it.id("parentId") == "0" && !it.optBoolean("hidden") && it.optInt("type") == type }.map { it.id() }.toSet()
    return ordered.filter { !it.optBoolean("hidden") && it.id("parentId") in visibleParents && it.optInt("type") == type }
}

/** An existing transaction can keep its original category even if it is no longer selectable. */
internal fun entryPreservesOriginalCategory(original: JSONObject?, selectedId: String, transactionType: Int): Boolean =
    original != null && selectedId.isNotBlank() && selectedId != "0" &&
        original.id("categoryId") == selectedId && original.optInt("type") == transactionType

/** Protected internal transfer categories require an explicit choice. */
internal fun entryDefaultCategory(visibleCategories: List<JSONObject>): String =
    visibleCategories.firstOrNull { !it.optBoolean("familyInternalTransfer") }?.id().orEmpty()

private const val CATEGORY_COLUMNS = 5

// 钱迹的父类与子类复用同一个分类单元：32dp 图标槽、2dp 标签间距、上下 8dp 内边距。
// 角标只是槽位右下角的覆盖层，不参与单元高度计算。
private val categoryTileVerticalPadding = 8.dp
private val categoryIconSlotSize = 32.dp
private val categoryIconSize = 24.dp
private val categoryLabelSpacing = 2.dp
private val categoryMoreBadgeSize = 12.dp

/** Stable row grouping used by both the entry picker and category management previews. */
internal fun entryCategoryRows(items: List<JSONObject>, columns: Int = CATEGORY_COLUMNS): List<List<JSONObject>> =
    if (columns <= 0) emptyList() else items.chunked(columns)

/**
 * 钱迹式记账分类选择器：二级分类面板插入到选中一级分类所在行之后。
 * 一级分类只负责展开，真正可记账的仍是二级分类。
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
    val ordered = sortCategoriesForDisplay(categories)
    val visible = entryVisibleCategories(ordered, transactionType)
    val parentById = remember(categories, transactionType) {
        ordered.filter { it.id("parentId") == "0" && !it.optBoolean("hidden") }
            .filter { parent ->
                val mappedType = when (transactionType) { 2 -> 1; 4 -> 3; else -> 2 }
                parent.optInt("type") == mappedType && visible.any { it.id("parentId") == parent.id() }
            }
    }
    val childrenByParent = remember(visible) { visible.groupBy { it.id("parentId") } }
    val selectedParent = ordered.firstOrNull { it.id() == selectedId }?.id("parentId")
        ?.takeIf { id -> parentById.any { it.id() == id } }
    var expandedParentId by rememberSaveable(transactionType) { mutableStateOf(selectedParent ?: parentById.firstOrNull()?.id().orEmpty()) }
    LaunchedEffect(selectedParent, parentById) {
        if (!selectedParent.isNullOrBlank() && selectedParent != expandedParentId) expandedParentId = selectedParent
        else if (expandedParentId.isBlank() || parentById.none { it.id() == expandedParentId }) expandedParentId = parentById.firstOrNull()?.id().orEmpty()
    }
    val rows = entryCategoryRows(parentById)
    LazyColumn(
        modifier = modifier.fillMaxSize().background(ledgerColors.surface).testTag("entry-category-grid"),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (rows.isEmpty()) {
            item { Text("尚无可用分类，请先在菜单中维护分类。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
        } else {
            rows.forEachIndexed { rowIndex, row ->
                item(key = "entry-parent-row-$rowIndex") {
                    CategoryRow(row, expandedParentId, enabled, onExpand = { expandedParentId = it })
                }
                val expandedColumn = row.indexOfFirst { it.id() == expandedParentId }
                if (expandedColumn >= 0) {
                    val parentId = row[expandedColumn].id()
                    item(key = "entry-children-$parentId") {
                        CategoryChildrenPanel(
                            children = childrenByParent[parentId].orEmpty(),
                            selectedId = selectedId,
                            enabled = enabled,
                            notchColumn = expandedColumn,
                            onSelect = onSelect
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryRow(
    row: List<JSONObject>,
    expandedParentId: String,
    enabled: Boolean,
    onExpand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        row.forEach { parent ->
            CategoryTile(
                item = parent,
                selected = parent.id() == expandedParentId,
                enabled = enabled,
                showMoreBadge = true,
                onClick = { onExpand(parent.id()) },
                modifier = Modifier.weight(1f)
            )
        }
        repeat(CATEGORY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
internal fun CategoryChildrenPanel(
    children: List<JSONObject>,
    selectedId: String,
    enabled: Boolean,
    notchColumn: Int,
    onSelect: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,

) {
    val rows = entryCategoryRows(children)
    val panelColor = if (MaterialTheme.colorScheme.surfaceVariant == Color.Transparent) ledgerColors.controlSurface else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .drawBehind { drawCategoryPanelBackground(this, notchColumn, panelColor) }
    ) {
        Column(
            // The triangle is panel chrome; each child tile keeps the same 8dp/32dp/2dp rhythm as a parent.
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    row.forEach { child ->
                        Box(Modifier.weight(1f)) {
                            CategoryTile(
                                item = child,
                                selected = child.id() == selectedId,
                                enabled = enabled,
                                onClick = { onSelect(child) },
                                modifier = Modifier.fillMaxWidth()
                            )

                        }
                    }
                    repeat(CATEGORY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

        }
    }
}

private fun drawCategoryPanelBackground(scope: androidx.compose.ui.graphics.drawscope.DrawScope, notchColumn: Int, color: Color) {
    val radius = with(scope) { 16.dp.toPx() }
    val triangleDepth = if (notchColumn >= 0) with(scope) { 10.dp.toPx() } else 0f
    scope.drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(0f, triangleDepth),
        size = androidx.compose.ui.geometry.Size(scope.size.width, scope.size.height - triangleDepth),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    )
    if (notchColumn >= 0) {
        val columnWidth = scope.size.width / CATEGORY_COLUMNS
        val center = columnWidth * (notchColumn + 0.5f)
        val half = with(scope) { 18.dp.toPx() }
        val path = Path().apply {
            moveTo(center - half, triangleDepth)
            lineTo(center, 0f)
            lineTo(center + half, triangleDepth)
            close()
        }
        scope.drawPath(path, color)
    }
}

@Composable
internal fun CategoryTile(
    item: JSONObject,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showMoreBadge: Boolean = false
) {
    LedgerControl(
        modifier.fillMaxWidth().padding(vertical = categoryTileVerticalPadding).testTag("entry-category-${item.id()}"),
        selected = selected, enabled = enabled, role = Role.Button, onClick = onClick,
    ) { interactions ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                CategoryIconSlot(item, selected, showMoreBadge)
                Spacer(Modifier.matchParentSize().ledgerControlFeedback(interactions))
            }
            Text(
                item.optString("name"),
                modifier = Modifier.padding(top = categoryLabelSpacing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    item.optBoolean("hidden") -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
internal fun CategoryIconSlot(item: JSONObject, selected: Boolean, showMoreBadge: Boolean) {
    Box(Modifier.size(categoryIconSlotSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(categoryIconSlotSize).background(if (selected) ledgerColors.selection else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            BuiltinIcon(
                item,
                Modifier.size(categoryIconSize),
                tint = if (selected) MaterialTheme.colorScheme.primary else ledgerColors.muted
            )
        }
        if (showMoreBadge) {
            Box(
                Modifier.size(categoryMoreBadgeSize).align(Alignment.BottomEnd).background(
                    if (selected) MaterialTheme.colorScheme.primary else ledgerColors.muted,
                    CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.5.dp)) {
                    repeat(3) { Box(Modifier.size(1.5.dp).background(Color.White, CircleShape)) }
                }
            }
        }
    }
}
