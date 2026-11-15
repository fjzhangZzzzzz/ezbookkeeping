package top.fjzhang233.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable internal fun EntityWriteWarning(vm:BooksModel){
    if(vm.entityBlocked)Column(Modifier.fillMaxWidth().padding(12.dp).testTag("entity-write-warning")){
        Text("有一笔资料保存结果待核实，当前不能新建或修改账户、分类和标签。",color=MaterialTheme.colorScheme.error)
        TextButton(onClick=vm::reviewEntityWrite,enabled=!vm.busy){Text("核对待保存资料")}
    }
}
@Composable internal fun PendingEntityDialog(vm:BooksModel){
    PendingEntityContent(vm.entityPendingView,vm.busy,vm.error,vm::reviewEntityWrite,vm::hideEntityReview,vm::abandonEntityAfterReview)
}
@Composable internal fun PendingEntityContent(view:EntityPendingView?,busy:Boolean,error:String,recheck:()->Unit,viewList:()->Unit,abandon:()->Unit){
    var confirmAbandon by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),
        title={Text("资料保存结果待核实")},
        text={Column(Modifier.verticalScroll(rememberScrollState()).testTag("entity-pending-original")){
            Text("可能已经保存，也可能尚未发送。为避免重复，客户端不会重新提交这份请求。请先核对实际列表或网页。")
            if(view==null)Text("当前无法安全核对原账号，原内容暂不显示。身份核实前不能结束意图或新建资料。") else {
                val label=when{view.path.contains("/accounts/")->"账户";view.path.contains("/categories/")->"分类";view.path.contains("/tags/groups/")->"标签组";else->"标签"}
                Text("原操作：${if(view.path.endsWith("/add.json"))"新建" else "修改"}$label")
                Text("原名称："+view.body.optString("name"))
                if(label=="账户")Text("账户类型："+when(view.body.optInt("category")){1->"现金";2->"银行卡";3->"信用卡";4->"虚拟账户";5->"负债";6->"应收款";7->"投资";8->"储蓄";9->"定期存款";else->"请在实际列表核对"})
                if(label=="账户"&&view.body.has("balance"))Text("初始余额：${money(view.body.optString("balance"))} ${view.body.optString("currency")}")
                if(label=="分类")Text("用途："+when(view.body.optInt("type")){1->"收入";2->"支出";3->"转账";else->"请在实际列表核对"})
                if(label=="分类")Text("所属大类："+view.details.optString("parentName","请在实际列表核对原设置"))
                if(label=="标签")Text("所属标签组："+view.details.optString("groupName","请在实际列表核对原设置"))
                if(view.body.optString("comment").isNotBlank())Text("备注："+view.body.optString("comment"))
                TextButton(onClick={confirmAbandon=true},enabled=!busy,modifier=Modifier.testTag("entity-abandon-intent")){Text("我已核对，不再提交此请求")}
            }
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick=recheck,enabled=!busy,modifier=Modifier.testTag("entity-recheck")){Text(if(busy)"正在核对…"else"重新读取实际结果")}},
        dismissButton={TextButton(onClick=viewList,enabled=!busy,modifier=Modifier.testTag("entity-view-list")){Text("先查看实际列表")}})
    if(confirmAbandon)AlertDialog(onDismissRequest={if(!busy)confirmAbandon=false},title={Text("结束本次保存意图？")},
        text={Text("请先核对实际列表。本操作仅删除本设备的待核实意图，不会撤销服务器可能已完成的更改；结束后无法恢复该本地意图，也不会重发原请求。")},
        confirmButton={TextButton(onClick={confirmAbandon=false;abandon()},enabled=!busy,modifier=Modifier.testTag("entity-confirm-abandon")){Text("已核对，结束意图")}},
        dismissButton={TextButton(onClick={confirmAbandon=false},enabled=!busy){Text("继续保留")}})
}
