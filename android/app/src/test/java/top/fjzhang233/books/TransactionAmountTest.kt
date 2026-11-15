package top.fjzhang233.books

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionAmountTest {
    @Test fun signedIncomeExpenseAndBalanceHaveExactlyOneDirection(){
        assertEquals("+0.01",transactionSignedAmount(2,"1"));assertEquals("−0.01",transactionSignedAmount(2,"-1"))
        assertEquals("−0.01",transactionSignedAmount(3,"1"));assertEquals("+0.01",transactionSignedAmount(3,"-1"))
        assertEquals("+0.01",transactionSignedAmount(1,"1"));assertEquals("−0.01",transactionSignedAmount(1,"-1"))
        assertEquals("1.00",transactionSignedAmount(4,"100"));assertEquals("−1.00",transactionSignedAmount(4,"-100"))
    }
    @Test fun zeroHasNoMisleadingDirectionAndKeepsMinorPrecision(){
        for(type in 1..4){assertEquals("0.00",transactionSignedAmount(type,"0"));assertEquals("0.00",transactionSignedAmount(type,"-0"))}
        assertEquals("+12345678901.23",transactionSignedAmount(2,"1234567890123"))
        assertEquals("−12345678901.23",transactionSignedAmount(3,"1234567890123"))
    }
    @Test fun minimumInt64ExpenseNegationDoesNotOverflow(){
        assertEquals("+92233720368547758.08",transactionSignedAmount(3,"-9223372036854775808"))
        assertEquals("−92233720368547758.08",transactionSignedAmount(2,"-9223372036854775808"))
        assertEquals("−92233720368547758.07",transactionSignedAmount(3,"9223372036854775807"))
    }
    @Test fun malformedServerAmountIsUnknownRatherThanInventedZero(){
        for(minor in listOf("","NaN","1e2","0.01"," 1","+1","−1"))assertEquals("—",transactionSignedAmount(3,minor))
    }
    @Test fun compactCalendarKeepsExactShortValuesAndSingleAsciiDirection(){
        assertEquals("+0.1",transactionSignedAmount(2,"10",compact=true));assertEquals("-0.1",transactionSignedAmount(3,"10",compact=true))
        assertEquals("-0.01",transactionSignedAmount(2,"-1",compact=true));assertEquals("+0.01",transactionSignedAmount(3,"-1",compact=true))
        assertEquals("0",transactionSignedAmount(1,"0",compact=true))
    }
}
