package top.fjzhang233.books

import java.math.BigDecimal
import java.math.BigInteger

/** Display cash direction once: an expense negates its signed, exact minor-unit amount. */
internal fun transactionSignedAmount(type:Int,minor:String,compact:Boolean=false):String = runCatching {
    require(Regex("-?[0-9]+").matches(minor))
    val stored=BigInteger(minor)
    val directed=if(type==3)stored.negate()else stored
    val prefix=when { directed.signum()<0 -> if(compact)"-" else "−";directed.signum()>0&&type in 1..3 -> "+";else -> "" }
    val amount=BigDecimal(directed.abs(),2)
    prefix+(if(compact)amount.stripTrailingZeros()else amount).toPlainString()
}.getOrDefault("—")
