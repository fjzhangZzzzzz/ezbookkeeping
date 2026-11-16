package top.fjzhang233.books

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EntryCategoriesTest {
    private fun category(id:String,parent:String,hidden:Boolean=false,type:Int=2)=JSONObject().put("id",id).put("parentId",parent).put("hidden",hidden).put("type",type)
    @Test fun newSelectionsExcludeHiddenChildAndChildrenOfHiddenParents() {
        val all=listOf(category("1","0"),category("2","1"),category("3","1",true),category("4","0",true),category("5","4"),category("6","missing"))
        assertEquals(listOf("2"),entryVisibleCategories(all,3).map{it.id()})
    }
    @Test fun hiddenOriginalIsPreservedOnlyForSameTransactionTypeAndSelection() {
        val original=JSONObject().put("categoryId","3").put("type",3)
        assertTrue(entryPreservesOriginalCategory(original,"3",3))
        assertFalse(entryPreservesOriginalCategory(original,"2",3))
        assertFalse(entryPreservesOriginalCategory(original,"3",2))
        assertFalse(entryPreservesOriginalCategory(null,"3",3))
        assertFalse(entryPreservesOriginalCategory(original,"0",3))
    }
    @Test fun selectingAnotherVisibleCategoryIsAnExplicitChange() {
        val original=JSONObject().put("categoryId","3").put("type",3)
        val all=listOf(category("1","0"),category("2","1"),category("3","1",true))
        assertEquals("2",entryVisibleCategories(all,3).single().id())
        assertFalse(entryPreservesOriginalCategory(original,"2",3))
    }
    @Test fun internalFamilyIncomeAndExpenseAreSelectableButNeverDefault() {
        for ((txType, categoryType) in listOf(3 to 2, 2 to 1)) {
            val parent=category("10","0",type=categoryType)
            val internal=category("11","10",type=categoryType).put("familyInternalTransfer",true)
            val ordinary=category("12","10",type=categoryType)
            val visible=entryVisibleCategories(listOf(parent,internal,ordinary),txType)
            assertEquals(listOf("11","12"),visible.map{it.id()})
            assertEquals("12",entryDefaultCategory(visible))
            assertEquals("",entryDefaultCategory(listOf(internal)))
        }
    }

    @Test fun categoryRowsKeepFiveColumnsForInlineExpansion() {
        val parents = (1..11).map { category("p$it", "0") }
        val rows = entryCategoryRows(parents)
        assertEquals(listOf(5, 5, 1), rows.map { it.size })
        assertEquals("p6", rows[1].first().id())
        assertEquals("p11", rows[2].single().id())
    }
}
