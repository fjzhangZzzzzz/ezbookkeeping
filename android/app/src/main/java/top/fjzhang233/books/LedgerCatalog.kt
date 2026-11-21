package top.fjzhang233.books

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.UUID

@Composable internal fun CatalogPage(vm: BooksModel, initialSection:Int=0) {
    require(initialSection in 0..2)
    var section by rememberSaveable(initialSection) { mutableIntStateOf(initialSection) }
    var editingId by rememberSaveable { mutableStateOf("") }; var open by rememberSaveable { mutableStateOf(false) }
    var editingSnapshot by rememberSaveable { mutableStateOf("") }
    var newParentId by rememberSaveable { mutableStateOf("0") }
    var newType by rememberSaveable { mutableStateOf("2") }
    var expanded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val currentItems=when(section){0->vm.categories;1->vm.tags;else->vm.tagGroups}
    val editing=currentItems.firstOrNull{it.id()==editingId} ?: editingSnapshot.takeIf{editingId.isNotEmpty() && it.isNotEmpty()}?.let{JSONObject(it)}
    LaunchedEffect(Unit) { vm.refreshCategoriesIfStale() }
    Column {
        EntityWriteWarning(vm)
        if (vm.categoryRefreshFailed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已保存，分类列表刷新失败", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::refreshCategories, enabled = !vm.busy) { Text("重试刷新分类") }
            }
        }
        TabRow(selectedTabIndex = section) {
            listOf("分类", "标签", "标签组").forEachIndexed { index, text ->
                LedgerControl(Modifier.fillMaxWidth(), selected = section == index, enabled = !vm.busy,
                    onClick = { section = index }) { interactions ->
                    Text(text, Modifier.ledgerControlFeedback(interactions).padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (section == index) LocalContentColor.current else ledgerColors.muted)
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("全家共享分类、标签及标签组。仅使用内置图标。", style = MaterialTheme.typography.bodySmall) }
            if(section==0) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick={vm.refreshCategories()},enabled=!vm.busy) { Text("刷新分类") }; TextButton(onClick={ showHidden = !showHidden }) { Text(if(showHidden) "隐藏已停用" else "显示已停用") } }; if(vm.categoryRevision==null) Text("分类管理需要升级服务端；浏览和记账仍可使用",style=MaterialTheme.typography.bodySmall) }
            item { Button(onClick = { editingId = ""; editingSnapshot=""; newParentId="0"; newType="2"; open = true }, enabled = !vm.busy&&!vm.entityBlocked&&(section!=0||vm.categoryRevision!=null)) { Icon(Icons.Outlined.Add, null); Text("新建${listOf("分类", "标签", "标签组")[section]}") } }
            val list = when(section) { 0 -> vm.categories; 1 -> vm.tags; else -> vm.tagGroups }
            if (list.isEmpty()) item { EmptyState("暂无${listOf("分类", "标签", "标签组")[section]}", "点击新建，与家人共享整理方式。") }
            if (section == 0) {
                val visible = list.filter { !it.optBoolean("familyInternalTransfer") && (showHidden || !it.optBoolean("hidden")) }
                val parents = visible.filter { it.id("parentId") == "0" }
                items(parents, key = { it.id() }) { parentItem ->
                    val children = visible.filter { it.id("parentId") == parentItem.id() }
                    val isExpanded = parentItem.id() in expanded
                    Card(Modifier.fillMaxWidth().testTag("catalog-parent-${parentItem.id()}"), colors = CardDefaults.cardColors(containerColor = ledgerColors.controlSurface), shape = MaterialTheme.shapes.medium) {
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = if (isExpanded) expanded - parentItem.id() else expanded + parentItem.id() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryIconSlot(parentItem, selected = isExpanded, showMoreBadge = true)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(parentItem.optString("name"), style = MaterialTheme.typography.titleMedium,
                                    color = if(parentItem.optBoolean("hidden")) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(when(parentItem.optInt("type")) { 1 -> "收入"; 2 -> "支出"; else -> "转账" } + " · ${children.size} 个子分类", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { editingId = parentItem.id(); editingSnapshot=parentItem.toString(); open = true }, enabled=!vm.busy&&!vm.entityBlocked&&vm.categoryRevision!=null) { Icon(Icons.Outlined.Edit, "编辑${parentItem.optString("name")}") }
                            IconButton(onClick = { expanded = if (isExpanded) expanded - parentItem.id() else expanded + parentItem.id() }) { Icon(if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (isExpanded) "收起 ${parentItem.optString("name")}" else "展开 ${parentItem.optString("name")}") }
                        }
                        AnimatedVisibility(isExpanded) {
                            CategoryChildrenPanel(
                                children = children,
                                selectedId = "",
                                enabled = !vm.busy && !vm.entityBlocked && vm.categoryRevision != null,
                                notchColumn = -1,
                                onSelect = { child ->
                                    editingId = child.id()
                                    editingSnapshot = child.toString()
                                    open = true
                                },
                                modifier = Modifier.padding(bottom = 8.dp),
                                footer = {
                                    TextButton(
                                        onClick = { editingId = ""; editingSnapshot = ""; newParentId = parentItem.id(); newType = parentItem.optString("type", "2"); open = true },
                                        enabled = !vm.busy && !vm.entityBlocked && vm.categoryRevision != null && !parentItem.optBoolean("hidden"),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    ) { Icon(Icons.Outlined.Add, null); Text("添加子分类") }
                                }
                            )
                        }
                    }
                }
            } else items(list.filter { !it.optBoolean("familyInternalTransfer") }, key = { it.id() }) { item ->
                ListItem(headlineContent = { Text(item.optString("name")) }, trailingContent = { IconButton(onClick = { editingId = item.id(); editingSnapshot=item.toString(); open = true },enabled=!vm.busy&&!vm.entityBlocked) { Icon(Icons.Outlined.Edit, "编辑${item.optString("name")}") } })
            }
        }
    }
    if (open && (editingId.isEmpty() || editing!=null)) CatalogEditor(vm, section, editing, initialType=newType, initialParentId=newParentId, close={ open = false })
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
    AlertDialog(onDismissRequest = {if(!vm.busy&&!vm.entityBlocked)close()}, title = { Text((if (original == null) "新建" else "编辑") + label) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { if(!vm.busy&&!vm.entityBlocked&&it.length <= maxNameLength) name = it }, label = { Text("${label}名称") }, singleLine = true,enabled=!locked,
            supportingText = if (section == 0) ({ Text("分类名称最多 4 个字（${name.length}/4）") }) else null)
        if (section == 0) {
            if (original == null) Choice("用途", listOf("2" to "支出", "1" to "收入", "3" to "转账"), type,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked){type = it; parent = "0"} }
            if(original==null || original.id("parentId")!="0") Choice("所属大类", (if(original==null) listOf("0" to "作为大类") else emptyList()) + vm.categories.filter { it.id("parentId") == "0" && it.optString("type") == type && it.id() != original?.id() && !it.optBoolean("hidden") }.map { it.id() to it.optString("name") }, parent,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)parent = it }
            else Text("一级分类不能调整为二级分类")
            Text("分类图标", style = MaterialTheme.typography.titleSmall, color = ledgerColors.text)
            CatalogIconPicker(icon = icon, enabled = !locked) { icon = it }
        }
        if (section == 1) Choice("标签组", listOf("0" to "不分组") + vm.tagGroups.map { it.id() to it.optString("name") }, group,enabled=!locked) { if(!vm.busy&&!vm.entityBlocked)group = it }
        if(section==0) {
            TextButton(onClick={vm.act { vm.refreshCategoriesNow(); openedRevision=vm.categoryRevision.orEmpty(); impactCount=null }},enabled=!locked) { Text("刷新分类并重新评审草稿") }
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
    }, enabled = !locked && name.trim().isNotBlank() && name.trim().length <= maxNameLength && (section!=0||openedRevision.isNotBlank()) && parentValid) { Text("保存${label}") } }, dismissButton = { TextButton(onClick = {if(!vm.busy&&!vm.entityBlocked)close()},enabled=!locked) { Text("取消") } })
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
