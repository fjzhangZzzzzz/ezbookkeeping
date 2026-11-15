package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EntryCalculatorTest {
    private fun type(keys:List<String>,initial:String="")=keys.fold(initial){value,key->entryKey(value,key)}
    @Test fun secondOperatorCommitsTheCapturedReferenceSequence(){
        val first=type(listOf("1","+","2"));assertEquals("1+2",first)
        val committed=entryKey(first,"−");assertEquals("3−",committed)
        val final=type(listOf("0",".","5"),committed);assertEquals("3−0.5",final);assertEquals(250L,entryMinor(final))
        assertEquals("2.5+",entryKey(final,"+"));assertEquals("3",entryKey("3","="))
    }
    @Test fun decimalChainsAreExactAndDoNotAccumulateExpressions(){
        val first=type(listOf("0",".","1","+","0",".","2","−"));assertEquals("0.3−",first)
        val next=type(listOf("0",".","0","1","+"),first);assertEquals("0.29+",next)
        assertEquals(30L,entryMinor(type(listOf("0",".","0","1"),next)))
        assertEquals("6−",entryKey("1+2+3","−"))
    }
    @Test fun intermediateNegativeAndZeroRemainUsableButFinalAmountMustBePositive(){
        val negative=type(listOf("1","−","2","+"));assertEquals("−1+",negative)
        assertEquals(200L,entryMinor(type(listOf("3"),negative)))
        val zero=type(listOf("1","−","1","+"));assertEquals("0+",zero);assertEquals(1L,entryMinor(type(listOf("0",".","0","1"),zero)))
        for(value in listOf("0","1−1","1−2","−1"))assertThrows(IllegalArgumentException::class.java){entryMinor(value)}
    }
    @Test fun replacementPrecisionBackspaceAndLengthGuardsArePreserved(){
        assertEquals("1−",entryKey("1+","−"));assertEquals("1+",entryKey("1−","+"))
        assertEquals("0.01",entryKey("0.01","9"));assertEquals("0.",entryKey("0.0","⌫"));assertEquals("3",entryKey("3−","⌫"))
        assertEquals("1+0.",entryKey("1+","."));assertEquals("",entryKey("","−"))
        val full="1".repeat(30);assertEquals(full,entryKey(full,"2"));assertEquals(full,entryKey(full,"+"));assertEquals(full.dropLast(1),entryKey(full,"⌫"))
    }
    @Test fun extremeIntermediateValuesRemainExactUntilFinalInt64Validation(){
        assertEquals(Long.MAX_VALUE,entryMinor("92233720368547758.07"))
        assertThrows(ArithmeticException::class.java){entryMinor("92233720368547758.08")}
        val large=entryKey("92233720368547758.07+0.01","−");assertEquals("92233720368547758.08−",large)
        assertEquals(Long.MAX_VALUE,entryMinor(large+"0.01"))
    }
    @Test fun incompleteAndMalformedExpressionsNeverReachSavedMoney(){
        for(value in listOf("","1+","1−","1++2","1..2","1e2","1+0.001","NaN"))assertThrows(IllegalArgumentException::class.java){entryMinor(value)}
        assertEquals("1+0..2",entryKey("1+0..2","+"))
    }
    @Test fun transferAmountsAreCapturedIndependentlyAndPersistExactlyAcrossRetry()=runBlocking{
        class Memory:TransactionRequestStorage{var saved:String?=null;override fun read()=saved;override fun write(value:String){saved=value};override fun clear(){saved=null}}
        val memory=Memory();val sent=mutableListOf<String>();var fail=true
        fun submission()=TransactionSubmission("https://books.fjzhang233.top",{SubmissionIdentity("1",setOf("10","11"))},{memory},{_,body->sent+=body.toString();if(fail)throw IOException("synthetic interruption");JSONObject().put("id","100")})
        val source=type(listOf("1","+","2","−","0",".","5"));val destination=type(listOf("5","−","2","+","0",".","0","1"))
        val body=JSONObject().put("type",4).put("sourceAccountId","10").put("destinationAccountId","11").put("sourceAmount",entryMinor(source)).put("destinationAmount",entryMinor(destination)).put("clientSessionId","qa-calculator-transfer")
        var first=submission();try{first.submit("/api/v1/transactions/add.json",body,false)}catch(_:IOException){}
        assertTrue(first.needsConfirmation);body.put("sourceAmount",999).put("destinationAmount",999)
        first=submission();val restored=first.restore()!!;assertEquals(250L,restored.getLong("sourceAmount"));assertEquals(301L,restored.getLong("destinationAmount"));fail=false;first.retry()
        assertEquals(sent[0],sent[1]);assertNull(memory.saved);assertFalse(first.needsConfirmation)
    }
}
