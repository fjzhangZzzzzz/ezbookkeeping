package top.fjzhang233.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun LedgerTransactionRow(
    transaction: JSONObject,
    first: Boolean,
    modifier: Modifier = Modifier,
    dateLabel: String? = null,
    showAccount: Boolean = false,
    enabled: Boolean = true,
    open: () -> Unit,
) {
    val type = transaction.optInt("type")
    val account = if (showAccount) transaction.optJSONObject("sourceAccount")?.optString("name").orEmpty() else ""
    val description = listOfNotNull(
        dateLabel,
        transaction.optString("comment").takeIf(String::isNotBlank),
        "图片".takeIf { transaction.array("pictures").isNotEmpty() },
    ).joinToString(" · ")
    CompactTransactionRow(transactionLabel(transaction), account, description, ledgerTransactionAmount(transaction), type, first, modifier, enabled, open)
}

@Composable internal fun CompactTransactionRow(
    title: String, account: String, description: String, amount: String, type: Int, first: Boolean,
    modifier: Modifier = Modifier, enabled: Boolean = true, open: () -> Unit,
) {
    val palette = ledgerColors
    val amountColor = when (type) { 2 -> palette.income; 3 -> palette.expense; else -> palette.text }
    val minHeight = if (description.isEmpty()) 48.dp else 54.dp

    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        if (!first) LedgerTransactionDivider()
        Row(
            modifier.fillMaxWidth().heightIn(min = minHeight)
                .clickable(enabled = enabled, onClick = open).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(4.dp).background(if (type in 2..3) amountColor else palette.muted, CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title,fontSize=14.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if (account.isNotEmpty()) {
                        Text(" · $account",Modifier.weight(1f),fontSize=10.sp,lineHeight=14.sp,
                            color=palette.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                if (description.isNotEmpty()) {
                    Text(description,fontSize=11.sp,lineHeight=15.sp,color=palette.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            Text(amount,fontSize=16.sp,lineHeight=20.sp,color=amountColor)
        }
    }
}
