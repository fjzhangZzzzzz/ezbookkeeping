package top.fjzhang233.books

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerOptimisticUpdateTest {
    private fun row(id:String,time:Long)=JSONObject().put("id",id).put("time",time)

    @Test fun acknowledgedRowIsInsertedAtTheCorrectPositionForTheVisibleMonth(){
        val saved=row("20",BooksApi.dayBounds("2026-11-18").first).put("comment","new")
        val rows=mergeAcknowledgedLedgerRow(listOf(row("19",BooksApi.dayBounds("2026-11-17").first)),saved,"2026-11",null)
        assertEquals(listOf("20","19"),rows.map{it.id()})
        assertEquals("new",rows.first().getString("comment"))
    }

    @Test fun acknowledgedRowIsRemovedWhenItMovesOutsideTheVisibleDay(){
        val saved=row("20",BooksApi.dayBounds("2026-11-18").first)
        val rows=mergeAcknowledgedLedgerRow(listOf(row("20",BooksApi.dayBounds("2026-11-17").first)),saved,"2026-11","2026-11-17")
        assertTrue(rows.isEmpty())
    }

    @Test fun acknowledgedRowReplacesExistingIdWithoutDuplicates(){
        val saved=row("20",BooksApi.dayBounds("2026-11-18").first).put("comment","updated")
        val rows=mergeAcknowledgedLedgerRow(listOf(row("20",1),row("19",2)),saved,"2026-11",null)
        assertEquals(listOf("20","19"),rows.map{it.id()})
        assertEquals("updated",rows.first().getString("comment"))
        assertEquals(1,rows.count{it.id()=="20"})
    }

    @Test fun acknowledgedRowIsIgnoredForAnotherMonth(){
        val saved=row("20",BooksApi.dayBounds("2026-10-18").first)
        val rows=mergeAcknowledgedLedgerRow(listOf(row("19",2)),saved,"2026-11",null)
        assertEquals(listOf("19"),rows.map{it.id()})
        assertFalse(rows.any{it.id()=="20"})
    }
}
