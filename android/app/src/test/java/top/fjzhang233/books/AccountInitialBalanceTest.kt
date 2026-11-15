package top.fjzhang233.books

import org.junit.Assert.*
import org.junit.Test

class AccountInitialBalanceTest {
    @Test fun creditAndDebtInputsUseOutstandingAmountAndNegativeStoredMinor(){
        assertEquals("欠款余额（CNY）",initialAccountBalanceLabel(3,"CNY"));assertEquals("欠款余额（USD）",initialAccountBalanceLabel(5,"USD"))
        for(type in listOf(3,5)){
            assertEquals(-1234L,initialAccountBalanceMinor("12.34",type));assertEquals(0L,initialAccountBalanceMinor("0.00",type))
            for(value in listOf("-0.01","−12.34"))assertThrows(IllegalArgumentException::class.java){initialAccountBalanceMinor(value,type)}
        }
    }
    @Test fun assetReceivableAndInvestmentRetainNormalSignedBalances(){
        for(type in listOf(1,2,4,6,7,8,9)){
            assertEquals(1234L,initialAccountBalanceMinor("12.34",type));assertEquals(-1234L,initialAccountBalanceMinor("-12.34",type));assertEquals(0L,initialAccountBalanceMinor("0",type))
            assertEquals("初始余额（CNY）",initialAccountBalanceLabel(type,"CNY"))
        }
    }
    @Test fun conversionRemainsExactAtInt64BoundsAndRejectsOverflow(){
        assertEquals(Long.MIN_VALUE,initialAccountBalanceMinor("92233720368547758.08",3));assertEquals(Long.MIN_VALUE,initialAccountBalanceMinor("-92233720368547758.08",1));assertEquals(Long.MAX_VALUE,initialAccountBalanceMinor("92233720368547758.07",1))
        assertThrows(ArithmeticException::class.java){initialAccountBalanceMinor("92233720368547758.09",3)}
        assertThrows(ArithmeticException::class.java){initialAccountBalanceMinor("92233720368547758.08",1)}
    }
    @Test fun malformedOrOverPreciseInputsNeverReachAnAccountRequest(){
        for(value in listOf("","NaN","1e2","1.001","1−2","1,000"))assertThrows(IllegalArgumentException::class.java){initialAccountBalanceMinor(value,1)}
        assertThrows(IllegalArgumentException::class.java){initialAccountBalanceMinor("1",0)}
    }
}
