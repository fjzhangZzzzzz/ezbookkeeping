package top.fjzhang233.books

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EntrySignedAmountTest {
    @Test fun negativeIncomeAndExpenseSurviveMemoOnlyPayloadRebuild(){
        for(type in listOf(2,3)){
            val original=JSONObject().put("id","100").put("type",type).put("sourceAmount",-123).put("comment","before")
            val draft=money(original.optString("sourceAmount"))
            val edited=JSONObject(original.toString()).put("comment","after").put("sourceAmount",entryMinor(draft,type))
            assertEquals(-123L,edited.getLong("sourceAmount"));assertEquals("after",edited.getString("comment"));assertEquals("100",edited.getString("id"))
            assertEquals(-250L,entryMinor("1−3.5",type));assertEquals(250L,entryMinor("3−0.5",type))
        }
    }
    @Test fun transfersRejectNegativeSourceAndDestinationIndependently(){
        assertEquals(250L,entryMinor("3−0.5",4));assertEquals(301L,entryMinor("3.01",4))
        for(value in listOf("−0.01","-1","1−2")){
            assertThrows(IllegalArgumentException::class.java){entryMinor(value,4)}
            assertThrows(IllegalArgumentException::class.java){entryMinor(value)}
        }
    }
    @Test fun zeroAndIncompleteAmountsRemainRejectedForEveryEntryType(){
        for(type in listOf(2,3,4))for(value in listOf("0","-0.00","1−1","","1+","−"))
            assertThrows(IllegalArgumentException::class.java){entryMinor(value,type)}
    }
    @Test fun signedIncomeExpenseUseExactInt64Bounds(){
        for(type in listOf(2,3)){
            assertEquals(Long.MIN_VALUE,entryMinor("-92233720368547758.08",type))
            assertEquals(Long.MAX_VALUE,entryMinor("92233720368547758.07",type))
            assertThrows(ArithmeticException::class.java){entryMinor("-92233720368547758.09",type)}
            assertThrows(ArithmeticException::class.java){entryMinor("92233720368547758.08",type)}
        }
    }
}
