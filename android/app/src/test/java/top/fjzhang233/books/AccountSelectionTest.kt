package top.fjzhang233.books

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountSelectionTest {
    @Test fun accountOrderMovesOnlyVisibleSiblingsAndRetainsArchivedSlots() {
        fun account(id: String, group: Int, hidden: Boolean = false) =
            JSONObject().put("id", id).put("category", group).put("hidden", hidden)
        val accounts = listOf(account("a", 1), account("h", 1, true), account("b", 1), account("c", 2))
        assertEquals(listOf("b" to 1, "h" to 2, "a" to 3), accountOrderChanges(accounts, listOf("b", "a", "c")))
        assertThrows(IllegalArgumentException::class.java) { accountOrderChanges(accounts, listOf("c", "b", "a")) }
        assertThrows(IllegalArgumentException::class.java) { accountOrderChanges(accounts, listOf("a", "a", "c")) }
    }

    private fun account(id: String, order: Int, hidden: Boolean = false) = JSONObject()
        .put("id", id).put("displayOrder", order).put("hidden", hidden)

    @Test fun serverDefaultWinsOverDisplayOrder() {
        val accounts = listOf(account("2", 0), account("1", 1))
        assertEquals("1", choosePreferredAccountId(accounts, "1"))
    }

    @Test fun hiddenDefaultFallsBackToFirstVisible() {
        val accounts = listOf(account("2", 0, hidden = true), account("1", 1))
        assertEquals("1", choosePreferredAccountId(accounts, "2"))
    }

    @Test fun noVisibleAccountReturnsEmptySelection() {
        assertEquals("", choosePreferredAccountId(listOf(account("2", 0, hidden = true)), "2"))
    }

    @Test fun deleteResultPreservesServerAction() {
        assertEquals("deleted", accountDeleteAction(JSONObject().put("id", "1").put("action", "deleted")))
        assertEquals("archived", accountDeleteAction(JSONObject().put("id", "1").put("action", "archived")))
    }
}
