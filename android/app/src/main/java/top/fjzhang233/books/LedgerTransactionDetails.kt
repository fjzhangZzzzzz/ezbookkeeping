package top.fjzhang233.books

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One own-transaction detail surface shared by ledger, calendar, reports and search. */
@Composable internal fun LedgerTransactionDetails(vm:BooksModel,item:JSONObject,dismiss:()->Unit){
    val tagNames=item.array("tags").map{it.optString("name")}.filter{it.isNotBlank()}.ifEmpty{
        val ids=item.optJSONArray("tagIds")
        if(ids==null)emptyList() else (0 until ids.length()).mapNotNull{index->vm.tags.firstOrNull{it.id()==ids.getString(index)}?.optString("name")}
    }
    LedgerTransactionDetailContent(item,vm.busy,vm.error,tagNames,dismiss,
        edit={dismiss();vm.openTransaction(item.id())},
        duplicate={dismiss();vm.copyTransaction(item.id())},
        remove={vm.deleteTransaction(item.id(),dismiss)}){picture->
        val extension=android.net.Uri.parse(picture.optString("originalUrl")).path?.substringAfterLast('.')?.lowercase()
        if(extension in setOf("jpg","jpeg","png","webp"))Picture(vm,"/api/v1/family/pictures/${picture.id("pictureId")}.$extension")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LedgerTransactionDetailContent(
    item:JSONObject,busy:Boolean,error:String,tagNames:List<String>,dismiss:()->Unit,
    edit:()->Unit,duplicate:()->Unit,remove:()->Unit,pictureContent:@Composable (JSONObject)->Unit={}
){
    var confirmDelete by rememberSaveable(item.id()){mutableStateOf(false)}
    val context=LocalContext.current
    val editable=item.optBoolean("editable",false)
    val type=item.optInt("type")
    val red=ledgerColors.expense;val green=ledgerColors.income
    val currency=item.optJSONObject("sourceAccount")?.optString("currency").orEmpty()
    val busyState=rememberUpdatedState(busy)
    val detailSheet=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={next->next!=SheetValue.Hidden||!busyState.value})
    ModalBottomSheet(sheetState=detailSheet,onDismissRequest={if(!busy)dismiss()},containerColor=ledgerColors.background,dragHandle=null){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().testTag("transaction-details")){
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text("详情",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.Bold)
                if(type in 2..4)DetailAction("复制",!busy,"transaction-copy",duplicate)
                if(type in 2..4)DetailAction("修改",editable&&!busy,"transaction-edit",edit)
                DetailAction("删除",editable&&!busy,"transaction-delete",{confirmDelete=true},red)
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotBlank())Text(error,Modifier.fillMaxWidth().padding(12.dp).testTag("transaction-detail-error"),color=red)
            Column(Modifier.fillMaxWidth().background(ledgerColors.surface)){
                TransactionDetailLine("金额",transactionSignedAmount(type,item.optString("sourceAmount"))+" "+currency,when(type){2->green;3->red;else->ledgerColors.text})
                TransactionDetailLine("分类",transactionLabel(item))
                TransactionDetailLine(if(type==4)"转出账户" else "账户",item.optJSONObject("sourceAccount")?.optString("name").orEmpty())
                if(type==4){
                    TransactionDetailLine("转入账户",item.optJSONObject("destinationAccount")?.optString("name").orEmpty())
                    TransactionDetailLine("转入金额",money(item.optString("destinationAmount"))+" "+item.optJSONObject("destinationAccount")?.optString("currency").orEmpty())
                }
                TransactionDetailLine("时间",DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(item.optLong("time"))))
                if(tagNames.isNotEmpty())TransactionDetailLine("标签",tagNames.joinToString(" · "))
                val comment=item.optString("comment")
                if(comment.isNotBlank()){
                    val copyMemo={
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("账单备注",comment))
                        Toast.makeText(context,"备注已复制",Toast.LENGTH_SHORT).show()
                    }
                    TransactionDetailLine("备注",comment,modifier=Modifier.testTag("transaction-comment-copy")
                        .pointerInput(comment){detectTapGestures(onDoubleTap={copyMemo()},onLongPress={copyMemo()})}
                        .semantics{onClick(label="复制备注"){copyMemo();true}})
                }
                item.array("pictures").forEach{pictureContent(it)}
            }
        }
    }
    if(confirmDelete)AlertDialog(onDismissRequest={if(!busy)confirmDelete=false},title={Text("删除这笔流水？")},text={Text(if(type==4)"将同时删除这笔转账的转入、转出记录并更新两个账户余额。此操作不能在应用中撤销。" else "将删除这笔记录并更新账户余额。此操作不能在应用中撤销。")},
        confirmButton={TextButton(onClick={confirmDelete=false;remove()},enabled=!busy,modifier=Modifier.testTag("confirm-transaction-delete")){Text("确认删除",color=red)}},
        dismissButton={TextButton(onClick={confirmDelete=false},enabled=!busy){Text("取消")}})
}
@Composable private fun DetailAction(label:String,enabled:Boolean,tag:String,action:()->Unit,color:Color=ledgerColors.text){
    Box(Modifier.padding(start=4.dp).heightIn(min=48.dp).clickable(enabled=enabled,onClick=action).testTag(tag),contentAlignment=Alignment.Center){
        Box(Modifier.background(ledgerColors.surface,CircleShape).padding(horizontal=12.dp,vertical=7.dp)){Text(label,fontSize=14.sp,color=if(enabled)color else ledgerColors.outline)}
    }
}
@Composable private fun TransactionDetailLine(label:String,value:String,valueColor:Color=ledgerColors.text,modifier:Modifier=Modifier){
    Row(modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        Text(label,fontSize=14.sp,color=ledgerColors.text);Text(value,Modifier.weight(1f).padding(start=16.dp),fontSize=14.sp,color=valueColor,textAlign=TextAlign.End)
    }
    HorizontalDivider(color=ledgerColors.divider)
}
