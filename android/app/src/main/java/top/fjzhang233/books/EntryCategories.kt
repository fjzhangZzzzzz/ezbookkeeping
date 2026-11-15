package top.fjzhang233.books

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
