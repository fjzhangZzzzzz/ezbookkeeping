package top.fjzhang233.books

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOrderingTest {
    private fun category(id: String, parent: String, type: Int = 2, order: Int, hidden: Boolean = false) =
        JSONObject().put("id", id).put("parentId", parent).put("type", type)
            .put("displayOrder", order).put("hidden", hidden)

    @Test fun displayOrderSortsParentsAndChildrenWithoutMixingTypes() {
        val rows = listOf(
            category("c2", "p2", order = 1), category("p2", "0", order = 2),
            category("p1", "0", order = 1), category("c1", "p1", order = 1),
            category("income", "0", type = 1, order = 1)
        )
        assertEquals(listOf("income", "p1", "c1", "p2", "c2"), sortCategoriesForDisplay(rows).map { it.id() })
    }

    @Test fun siblingMoveDoesNotCrossParentOrHiddenBoundaries() {
        val rows = listOf(
            category("p", "0", order = 1), category("a", "p", order = 1),
            category("hidden", "p", order = 2, hidden = true), category("b", "p", order = 3),
            category("other", "0", order = 2)
        )
        assertEquals(listOf("p", "b", "hidden", "a", "other"), moveCategorySibling(rows, "a", 1, false).map { it.id() })
        assertEquals(listOf("p", "a", "b", "hidden", "other"), moveCategorySibling(rows, "b", -1, true).map { it.id() })
        assertEquals(rows.map { it.id() }, moveCategorySibling(rows, "other", 1, false).map { it.id() })
    }

    @Test fun payloadContainsCompleteSiblingSnapshotAndRevision() {
        val rows = listOf(category("p", "0", order = 1), category("b", "p", order = 1), category("a", "p", order = 2))
        val payload = categoryDisplayOrderPayload(rows, "7")
        assertEquals("7", payload.getString("expectedRevision"))
        val orders = payload.getJSONArray("newDisplayOrders")
        assertEquals(3, orders.length())
        assertEquals("b", orders.getJSONObject(1).getString("id"))
        assertEquals(1, orders.getJSONObject(1).getInt("displayOrder"))
        assertEquals("a", orders.getJSONObject(2).getString("id"))
        assertEquals(2, orders.getJSONObject(2).getInt("displayOrder"))
    }
}
