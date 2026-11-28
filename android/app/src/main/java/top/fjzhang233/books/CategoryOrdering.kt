package top.fjzhang233.books

import org.json.JSONArray
import org.json.JSONObject

private val categoryTypeOrder = listOf(1, 2, 3)

/** Keeps the server's type/parent/display-order contract explicit on every client entry point. */
internal fun sortCategoriesForDisplay(categories: List<JSONObject>): List<JSONObject> {
    val knownTypes = (categoryTypeOrder + categories.map { it.optInt("type", Int.MAX_VALUE) })
        .distinct()
        .filter { type -> categories.any { it.optInt("type", Int.MAX_VALUE) == type } }
    val result = mutableListOf<JSONObject>()
    knownTypes.forEach { type ->
        val typed = categories.filter { it.optInt("type", Int.MAX_VALUE) == type }
        val parents = typed.filter { it.id("parentId") == "0" }.sortedWith(categoryOrderComparator)
        parents.forEach { parent ->
            result += parent
            result += typed.filter { it.id("parentId") == parent.id() }.sortedWith(categoryOrderComparator)
        }
        result += typed.filter { candidate ->
            candidate.id("parentId") != "0" && parents.none { it.id() == candidate.id("parentId") }
        }.sortedWith(compareBy({ it.id("parentId") }, { it.optInt("displayOrder", Int.MAX_VALUE) }, { it.id() }))
    }
    result += categories.filter { candidate -> result.none { it.id() == candidate.id() } }
    return result
}

private val categoryOrderComparator = compareBy<JSONObject>({ it.optInt("displayOrder", Int.MAX_VALUE) }, { it.id() })

/** Moves a category among visible, non-system siblings without changing its parent or type. */
internal fun moveCategorySibling(
    categories: List<JSONObject>,
    categoryId: String,
    direction: Int,
    includeHidden: Boolean,
): List<JSONObject> {
    require(direction != 0)
    val selected = categories.firstOrNull { it.id() == categoryId } ?: return categories
    if (selected.optBoolean("familyInternalTransfer") || (!includeHidden && selected.optBoolean("hidden"))) return categories
    val type = selected.optInt("type", Int.MAX_VALUE)
    val parentId = selected.id("parentId")
    val siblingSlots = categories.indices.filter { index ->
        val item = categories[index]
        item.optInt("type", Int.MAX_VALUE) == type && item.id("parentId") == parentId &&
            !item.optBoolean("familyInternalTransfer") && (includeHidden || !item.optBoolean("hidden"))
    }
    val selectedSlot = siblingSlots.indexOfFirst { categories[it].id() == categoryId }
    val targetSlot = selectedSlot + direction
    if (selectedSlot < 0 || targetSlot !in siblingSlots.indices) return categories
    val ids = siblingSlots.map { categories[it].id() }.toMutableList()
    val moved = ids.removeAt(selectedSlot)
    ids.add(targetSlot, moved)
    val result = categories.toMutableList()
    siblingSlots.forEachIndexed { index, slot ->
        result[slot] = categories.first { it.id() == ids[index] }
    }
    return result
}

/** Creates a complete deterministic snapshot for the existing category move endpoint. */
internal fun categoryDisplayOrderPayload(categories: List<JSONObject>, expectedRevision: String): JSONObject {
    require(expectedRevision.isNotBlank()) { "分类版本无效，请刷新后重试" }
    val rows = JSONArray()
    categories.groupBy { it.optInt("type", Int.MAX_VALUE) to it.id("parentId") }
        .values
        .forEach { group ->
            group.forEachIndexed { index, item ->
                rows.put(JSONObject().put("id", item.id()).put("displayOrder", index + 1))
            }
        }
    require(rows.length() > 0) { "没有可保存的分类顺序" }
    return JSONObject().put("expectedRevision", expectedRevision).put("newDisplayOrders", rows)
}
