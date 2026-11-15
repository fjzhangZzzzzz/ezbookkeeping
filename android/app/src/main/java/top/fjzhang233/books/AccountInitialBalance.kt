package top.fjzhang233.books

import java.math.BigDecimal

internal fun isLiabilityAccountCategory(category:Int)=category==3||category==5
internal fun initialAccountBalanceLabel(category:Int,currency:String)=if(isLiabilityAccountCategory(category))"欠款余额（$currency）" else "初始余额（$currency）"

/** Web account.isLiability / flip-negative contract: human debt is positive, stored debt is negative. */
internal fun initialAccountBalanceMinor(value:String,category:Int):Long {
    require(category in 1..9){"请选择有效账户类型"}
    val normalized=value.trim().replace('−','-')
    require(Regex("[+-]?[0-9]+(?:\\.[0-9]{0,2})?").matches(normalized)){"请输入有效余额，最多两位小数"}
    val input=BigDecimal(normalized)
    val stored=if(isLiabilityAccountCategory(category)){
        require(input.signum()>=0){"欠款余额不能为负数"}
        input.negate()
    }else input
    return stored.movePointRight(2).longValueExact()
}
