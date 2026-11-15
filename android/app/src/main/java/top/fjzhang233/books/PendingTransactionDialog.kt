package top.fjzhang233.books

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Always renders the immutable, identity-verified old request, never the editor underneath it. */
@Composable internal fun PendingTransactionDialog(original:JSONObject?,busy:Boolean,error:String,refresh:()->Unit,confirm:()->Unit){
    AlertDialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),
        title={Text("核对上次保存的记录")},
        text={Column(Modifier.verticalScroll(rememberScrollState()).testTag("pending-original-content")){
            Text("上次保存的结果尚未确认。请核对以下原内容，避免重复记账。")
            if(original==null)Text("暂不能安全核对原账户，请重新核对后再确认。") else {
                Text("类型："+when(original.optInt("type")){2->"收入";3->"支出";4->"转账";else->"余额调整"})
                val source=original.getJSONObject("sourceAccount")
                Text("金额：${money(original.optString("sourceAmount"))} ${source.getString("currency")}")
                Text("${if(original.optInt("type")==4)"转出账户" else "账户"}：${source.getString("name")}")
                if(original.optInt("type")==4){val destination=original.getJSONObject("destinationAccount");Text("转入账户：${destination.getString("name")}");Text("转入金额：${money(original.optString("destinationAmount"))} ${destination.getString("currency")}")}
                Text("时间："+DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(original.optLong("time"))))
                Text("图片：${original.optJSONArray("pictureIds")?.length()?:0} 张 · 标签：${original.optJSONArray("tagIds")?.length()?:0} 个")
                if(original.optString("comment").isNotBlank())Text("备注："+original.getString("comment"))
            }
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick=if(original==null)refresh else confirm,enabled=!busy,modifier=Modifier.testTag(if(original==null)"refresh-pending-identity" else "confirm-pending-transaction")){Text(if(busy)"正在核对…" else if(original==null)"重新核对账户" else "确认这笔原记录")}})
}
