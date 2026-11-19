package top.fjzhang233.books

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONObject

internal const val DEFAULT_LEDGER_CURRENCY = "CNY"

internal fun ledgerAmountWithCurrency(amount: String, currency: String): String =
    if (currency.isBlank() || currency == DEFAULT_LEDGER_CURRENCY) amount else "$amount $currency"

internal fun ledgerTransactionAmount(row: JSONObject): String = ledgerAmountWithCurrency(
    transactionSignedAmount(row.optInt("type"), row.optString("sourceAmount")),
    row.optJSONObject("sourceAccount")?.optString("currency").orEmpty(),
)

internal fun ledgerDaySummary(income: BigInteger, expense: BigInteger): String = buildString {
    if (income.signum() != 0) append("收:¥${money(income.toString())} ")
    append("支:¥${money(expense.toString())}")
}

/** Display cash direction once: an expense negates its signed, exact minor-unit amount. */
internal fun transactionSignedAmount(type:Int,minor:String,compact:Boolean=false):String = runCatching {
    require(Regex("-?[0-9]+").matches(minor))
    val stored=BigInteger(minor)
    val directed=if(type==3)stored.negate()else stored
    val prefix=when { directed.signum()<0 -> if(compact)"-" else "−";directed.signum()>0&&type in 1..3 -> "+";else -> "" }
    val amount=BigDecimal(directed.abs(),2)
    prefix+(if(compact)amount.stripTrailingZeros()else amount).toPlainString()
}.getOrDefault("—")
