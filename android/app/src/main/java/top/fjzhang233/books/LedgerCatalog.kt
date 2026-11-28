package top.fjzhang233.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun typeName(type: Int) = when (type) { 1 -> "收入"; 2 -> "支出"; else -> "转账" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CatalogPage(vm: BooksModel, initialSection: Int = 0, back: () -> Unit = {}, backLabel: String = "返回", feedback: SnackbarHostState = remember { SnackbarHostState() }) {
    var section by rememberSaveable(initialSection) { mutableIntStateOf(initialSection) }
    val label = listOf("分类", "标签", "标签组")[section]
    var open by rememberSaveable { mutableStateOf(false) }
    var editingSnapshot by rememberSaveable { mutableStateOf("") }
    var parentId by rememberSaveable { mutableStateOf("0") }
    var newType by rememberSaveable { mutableStateOf("2") }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var revision by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val sorting = order != null
    val baseline = remember(vm.categories) { sortCategoriesForDisplay(vm.categories) }
    val draft = remember(order, baseline) {
        val byId = baseline.associateBy { it.id() }
        order?.mapNotNull(byId::get) ?: baseline
    }
    val writable = !vm.busy && !vm.entityBlocked && (section != 0 || vm.categoryRevision != null)
    val editingAllowed = writable && !sorting
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun move(id: String, offset: Int) {
        if (!writable) return
        val moved = moveCategorySibling(draft, id, offset, showHidden)
        if (moved !== draft) {
            if (!sorting) revision = vm.categoryRevision
            order = moved.map { it.id() }.takeUnless { ids -> ids == baseline.map { it.id() } }
        }
    }
    fun create(parent: JSONObject? = null) {
        editingSnapshot = ""
        parentId = parent?.id() ?: "0"
        newType = parent?.optString("type") ?: "2"
        open = true
    }
    fun edit(item: JSONObject) { editingSnapshot = item.toString(); open = true }
    fun leave() { if (sorting) discard = true else back() }
    BackHandler(enabled = sorting && !open) { discard = true }
    LaunchedEffect(Unit) { vm.refreshCategoriesIfStale() }
    Scaffold(containerColor = ledgerColors.background, snackbarHost = { SnackbarHost(feedback) },
        topBar = {
            CenterAlignedTopAppBar(title = { Text("分类与标签") }, expandedHeight = 56.dp,
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.Outlined.ArrowBack, backLabel) } },
                actions = {
                    IconButton(onClick = { create() }, enabled = editingAllowed) { Icon(Icons.Outlined.Add, "新建$label") }
                    if (section == 0) Box {
                        IconButton(onClick = { menu = true }, enabled = !sorting) { Icon(Icons.Outlined.MoreVert, "分类选项") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("刷新分类") }, onClick = { menu = false; vm.refreshCategories() }, enabled = !vm.busy)
                            DropdownMenuItem(text = { Text(if (showHidden) "隐藏已停用" else "显示已停用") }, onClick = { menu = false; showHidden = !showHidden })
                        }
                    }
                })
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            EntityWriteWarning(vm)
            if (vm.categoryRefreshFailed) TextButton(onClick = vm::refreshCategories) { Text("已保存，重试刷新分类") }
            TabRow(selectedTabIndex = section) {
                listOf("分类", "标签", "标签组").forEachIndexed { index, text ->
                    LedgerControl(Modifier.fillMaxWidth(), selected = section == index, enabled = !vm.busy && !sorting,
                        onClick = { section = index }) { interactions ->
                        Text(text, Modifier.ledgerControlFeedback(interactions).padding(vertical = 6.dp), style = MaterialTheme.typography.titleSmall,
                            color = if (section == index) ledgerColors.accent else ledgerColors.muted)
                    }
                }
            }
            if (section == 0 && vm.categoryRevision == null) Text("分类管理需要升级服务端；浏览和记账仍可使用", Modifier.padding(16.dp))
            val visible = draft.filter { !it.optBoolean("familyInternalTransfer") && (showHidden || !it.optBoolean("hidden")) }
            val childrenByParent = visible.groupBy { it.id("parentId") }
            val groups = childrenByParent["0"].orEmpty().groupBy { it.optInt("type") }
            var activeGroup by remember { mutableStateOf<Pair<Int, String>?>(null) }
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                val source = visible.firstOrNull { it.id() == from.key } ?: return@rememberReorderableLazyListState
                val siblings = visible.filter { it.optInt("type") == source.optInt("type") && it.id("parentId") == source.id("parentId") }
                val target = siblings.indexOfFirst { it.id() == to.key }
                val offset = target - siblings.indexOf(source)
                if (target >= 0 && offset != 0) move(source.id(), offset)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                if (section == 0) {
                    if (groups.isEmpty()) item { EmptyState("暂无分类", "点击右上角加号新建分类。") }
                    groups.forEach { (type, parents) ->
                        item(key = "type-$type") { Text(typeName(type), Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = ledgerColors.muted) }
                        parents.forEachIndexed { index, parent ->
                            val id = parent.id()
                            val children = childrenByParent[id].orEmpty()
                            val isExpanded = id in expanded
                            val last = index == parents.lastIndex
                            val shape = RoundedCornerShape(
                                topStart = if (index == 0) 12.dp else 0.dp, topEnd = if (index == 0) 12.dp else 0.dp,
                                bottomStart = if (last && !isExpanded) 12.dp else 0.dp, bottomEnd = if (last && !isExpanded) 12.dp else 0.dp)
                            item(key = id) {
                                CategoryReorderRow(reorder, parent, isExpanded, !sorting, writable && parents.size > 1,
                                    activeGroup, { activeGroup = it }, shape, ::move) {
                                    expanded = if (isExpanded) expanded - id else expanded + id
                                }
                            }
                            if (isExpanded) {
                                items(children, key = { it.id() }) { child ->
                                    CategoryReorderRow(reorder, child, null, editingAllowed, writable && children.size > 1,
                                        activeGroup, { activeGroup = it }, RoundedCornerShape(0.dp), ::move) { edit(child) }
                                }
                                item(key = "actions-$id") {
                                    Row(Modifier.animateItem().fillMaxWidth()
                                        .clip(RoundedCornerShape(bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp))
                                        .background(ledgerColors.surface).padding(start = 24.dp).testTag("catalog-actions-$id"),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { create(parent) }, enabled = editingAllowed && !parent.optBoolean("hidden")) {
                                            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("添加子分类")
                                        }
                                        TextButton(onClick = { edit(parent) }, enabled = editingAllowed) { Text("编辑大类") }
                                    }
                                }
                            }
                            if (!last) item(key = "divider-$id") { LedgerTransactionDivider(Modifier.fillMaxWidth().background(ledgerColors.surface).padding(start = 28.dp, end = 12.dp)) }
                        }
                    }
                } else {
                    val items = if (section == 1) vm.tags else vm.tagGroups
                    if (items.isEmpty()) item { EmptyState("暂无$label", "点击右上角加号新建。") }
                    items(items, key = { it.id() }) { item ->
                        ListItem(headlineContent = { Text(item.optString("name")) }, trailingContent = {
                            IconButton(onClick = { edit(item) }, enabled = editingAllowed) { Icon(Icons.Outlined.Edit, "编辑" + item.optString("name")) }
                        })
                    }
                }
            }
            if (sorting) Row(Modifier.fillMaxWidth().background(ledgerColors.surface).padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { discard = true }, enabled = !vm.busy) { Text("取消") }
                if (revision != vm.categoryRevision || vm.error.contains("冲突") || vm.error.contains("版本")) {
                    TextButton(onClick = { vm.act { vm.refreshCategoriesNow(); order = null } }, enabled = writable) { Text("刷新后重试") }
                } else Button(onClick = {
                    revision?.let { vm.saveEntity("/api/v1/transaction/categories/move.json", categoryDisplayOrderPayload(draft, it)) { order = null } }
                }, enabled = writable) { Text("保存排序") }
            }
        }
    }
    if (open) CatalogEditor(vm, section, remember(editingSnapshot) { editingSnapshot.takeIf(String::isNotEmpty)?.let(::JSONObject) },
        initialType = newType, initialParentId = parentId, close = { open = false })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存排序？") },
        confirmButton = { TextButton(onClick = { order = null; discard = false }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续调整") } })
}

@Composable private fun LazyItemScope.CategoryReorderRow(
    reorder: ReorderableLazyListState, item: JSONObject, expanded: Boolean?, enabled: Boolean, sortable: Boolean,
    activeGroup: Pair<Int, String>?, setActiveGroup: (Pair<Int, String>?) -> Unit, shape: RoundedCornerShape,
    move: (String, Int) -> Unit, click: () -> Unit,
) {
    val group = item.optInt("type") to item.id("parentId")
    ReorderableItem(reorder, key = item.id(), enabled = sortable && (activeGroup == null || group == activeGroup)) { dragging ->
        Surface(shape = shape, color = ledgerColors.surface, shadowElevation = if (dragging) 4.dp else 0.dp) {
            val handle = Modifier.longPressDraggableHandle(enabled = sortable,
                onDragStarted = { setActiveGroup(group) }, onDragStopped = { setActiveGroup(null) })
            CatalogRow(item, expanded, enabled, handle, sortable, move, click)
        }
    }
}

@Composable private fun CatalogRow(item: JSONObject, expanded: Boolean?, enabled: Boolean, modifier: Modifier,
    sortable: Boolean, move: (String, Int) -> Unit, click: () -> Unit) {
    val id = item.id()
    val name = item.optString("name")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (expanded == null) 32.dp else 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("catalog-row-$id").clickable(enabled = enabled, onClick = click)
            .padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryIconSlot(item, selected = false, showMoreBadge = false)
            Text(name, Modifier.padding(horizontal = 8.dp).weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (expanded == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
            if (expanded != null) Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                if (expanded) "收起 $name" else "展开 $name", Modifier.size(16.dp), tint = ledgerColors.muted)
            if (item.optBoolean("hidden")) Text("已停用", style = MaterialTheme.typography.labelSmall, color = ledgerColors.muted)
        }
        if (sortable) {
            Box(Modifier.size(48.dp).then(modifier)
                .testTag("catalog-sort-handle-$id").semantics {
                    contentDescription = "长按拖动排序 $name"
                    customActions = listOf(CustomAccessibilityAction("上移") { move(id, -1); true }, CustomAccessibilityAction("下移") { move(id, 1); true })
                }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DragHandle, null, Modifier.size(20.dp), tint = ledgerColors.muted)
            }
        }
    }
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
    val creatingChild = section == 0 && original == null && initialParentId != "0"
    val title = when {
        creatingChild -> "新建子分类"
        section == 0 && original == null -> "新建一级分类"
        else -> (if (original == null) "新建" else "编辑") + label
    }
    val modified = name != original?.optString("name").orEmpty() || type != (original?.optString("type") ?: initialType) ||
        icon != (original?.optString("icon")?.ifBlank { "1" } ?: "1") || parent != (original?.id("parentId") ?: initialParentId) ||
        group != (original?.id("groupId") ?: "0")
    EntityEditorSheet(locked = locked, modified = modified, dismiss = close, title = title, content = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (creatingChild) Text(typeName(type.toInt()) + " › " + (vm.categories.firstOrNull { it.id() == initialParentId }?.optString("name") ?: "原大类不可用"),
            color = ledgerColors.muted, style = MaterialTheme.typography.bodyMedium)
        if (creatingChild && !parentValid) Text("所属大类已不可用，请重新确认归属", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(name, { if(!vm.busy&&!vm.entityBlocked&&it.length <= maxNameLength) name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("${label}名称") }, singleLine = true,enabled=!locked,
            supportingText = if (section == 0) ({ Text("分类名称最多 4 个字（${name.length}/4）") }) else null)
        if (section == 0) {
            if (original == null && !creatingChild) Choice("用途", listOf("2" to "支出", "1" to "收入", "3" to "转账"), type, enabled = !locked) { type = it }
            if (original != null && original.id("parentId") != "0") Choice("所属大类", vm.categories.filter { it.id("parentId") == "0" && it.optString("type") == type && !it.optBoolean("hidden") }.map { it.id() to it.optString("name") }, parent, enabled = !locked) { parent = it }
            if (original != null && original.id("parentId") == "0") Text("一级分类不能调整为二级分类")
            Text("分类图标", style = MaterialTheme.typography.titleSmall, color = ledgerColors.text)
            CatalogIconPicker(icon = icon, enabled = !locked) { icon = it }
        }
        if (section == 1) Choice("标签组", listOf("0" to "不分组") + vm.tagGroups.map { it.id() to it.optString("name") }, group,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)group = it }
        if(section==0) {
            if (vm.error.isNotBlank() || openedRevision != vm.categoryRevision) TextButton(onClick={vm.act { vm.refreshCategoriesNow(); openedRevision=vm.categoryRevision.orEmpty(); impactCount=null }},enabled=!locked) { Text("刷新分类并重新评审草稿") }
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
    }, enabled = !locked && name.trim().isNotBlank() && name.trim().length <= maxNameLength && (section!=0||openedRevision.isNotBlank()) && parentValid) { Text("保存${label}") } })
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
