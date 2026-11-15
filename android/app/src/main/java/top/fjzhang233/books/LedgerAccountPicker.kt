package top.fjzhang233.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** A manual-account starting point, not an integration with the named institution. */
internal data class AccountPreset(val key:String,val label:String,val category:Int,val icon:String,val color:String)
internal data class AccountPresetGroup(val title:String,val presets:List<AccountPreset>)

/** Categories are the actual Go AccountCategory values; glyphs are already bundled account icons. */
internal val accountPresetGroups=listOf(
    AccountPresetGroup("资金账户",listOf(
        AccountPreset("cash","现金",1,"10","665CF5"),
        AccountPreset("wechat","微信",4,"8302","00AD52"),
        AccountPreset("wechat-fund","微信零钱通",7,"700","D49900"),
        AccountPreset("alipay","支付宝",4,"8300","1677D5"),
        AccountPreset("yuebao","余额宝",7,"700","DE4B20"),
        AccountPreset("yulibao","余利宝",7,"700","2186CC"),
        AccountPreset("shared-wallet","小荷包",4,"1","D78320"),
        AccountPreset("union-wallet","云闪付",4,"100","CB3442"),
        AccountPreset("bank","银行卡",2,"100","2485D4"),
        AccountPreset("housing-fund","公积金",8,"910","7B43D5"),
        AccountPreset("qq-wallet","QQ钱包",4,"8301","009CBD"),
        AccountPreset("jd-finance","京东金融",4,"1","C9343C"),
        AccountPreset("medical-fund","医保",8,"540","6A66CF"),
        AccountPreset("digital-cny","数字人民币",4,"1003","CD382B"),
        AccountPreset("huawei-wallet","华为钱包",4,"1","CE574F"),
        AccountPreset("pdd-wallet","多多钱包",4,"1","CC4933"),
        AccountPreset("paypal","PayPal",4,"8000","296994"),
        AccountPreset("other-funds","其它",4,"1","7B43D5")
    )),
    AccountPresetGroup("信用卡账户",listOf(
        AccountPreset("credit-card","信用卡",3,"100","D96D3E"),
        AccountPreset("huabei","花呗",5,"500","2585CA"),
        AccountPreset("jiebei","借呗",5,"500","247DD0"),
        AccountPreset("jd-credit","京东白条",5,"100","C9343C"),
        AccountPreset("meituan-credit","美团月付",5,"100","A57B00"),
        AccountPreset("douyin-credit","抖音月付",5,"100","159F9F"),
        AccountPreset("wechat-credit","微信分付",5,"100","52A978"),
        AccountPreset("other-credit","其它信用卡",3,"100","7443C8")
    )),
    AccountPresetGroup("充值账户",listOf(
        AccountPreset("phone-prepaid","话费",4,"20","D56C27"),
        AccountPreset("utilities-prepaid","水电",4,"1","368E95"),
        AccountPreset("meal-prepaid","饭卡",4,"100","D27B19"),
        AccountPreset("deposit","押金",6,"600","2577C3"),
        AccountPreset("transport-prepaid","公交卡",4,"100","526E75"),
        AccountPreset("membership-prepaid","会员卡",4,"100","D4663A"),
        AccountPreset("fuel-prepaid","加油卡",4,"100","7C43C3"),
        AccountPreset("fuel-wallet","石化钱包",4,"1","C5362C"),
        AccountPreset("apple-prepaid","Apple",4,"100","1F82D3"),
        AccountPreset("other-prepaid","其它充值卡",4,"100","327ECB")
    )),
    AccountPresetGroup("投资理财账户",listOf(
        AccountPreset("stocks","股票",7,"800","D27B19"),
        AccountPreset("funds","基金",7,"801","D27B19"),
        AccountPreset("gold","黄金",7,"700","A57B00"),
        AccountPreset("forex","外汇",7,"990","2577C3"),
        AccountPreset("futures","期货",7,"800","D16F25"),
        AccountPreset("bonds","债券",7,"700","2585CA"),
        AccountPreset("fixed-income","固定收益",7,"701","6A66CF"),
        AccountPreset("crypto","加密货币",7,"1500","C77D12"),
        AccountPreset("other-investment","其它理财",7,"800","327ECB")
    )),
    AccountPresetGroup("债务",listOf(
        AccountPreset("lend","借出",6,"600","D45B65"),
        AccountPreset("borrow","借入",5,"601","229B6B")
    ))
)
internal fun accountPreset(key:String)=accountPresetGroups.asSequence().flatMap{it.presets.asSequence()}.firstOrNull{it.key==key}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LedgerAccountPicker(accounts:List<JSONObject>,selectedId:String,destination:Boolean=false,dismiss:()->Unit,choose:(String)->Unit,create:()->Unit,enabled:Boolean=true,createEnabled:Boolean=enabled){
    val visible=accounts.filter{!it.optBoolean("hidden")}
    val rows=(visible.size+1+4)/5
    val height=(56+rows*88+64).coerceIn(208,440).dp
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),shape=RoundedCornerShape(topStart=8.dp,topEnd=8.dp),containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface,dragHandle=null){
        Column(Modifier.fillMaxWidth().height(height).testTag("entry-account-picker")){
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start=12.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text(if(destination)"选择转入账户" else "选择一个账户",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.Bold)
                IconButton(onClick=create,enabled=createEnabled,modifier=Modifier.size(48.dp).testTag("entry-account-create")){
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Add,"新建账户",Modifier.size(26.dp),tint=MaterialTheme.colorScheme.surface)}
                }
            }
            LazyVerticalGrid(columns=GridCells.Fixed(5),modifier=Modifier.fillMaxWidth().weight(1f).testTag("entry-account-grid"),contentPadding=PaddingValues(horizontal=4.dp,vertical=8.dp)){
                item(key="none"){AccountChoiceTile("不选择账户",selectedId.isEmpty(),"entry-account-none",null,enabled){choose("")}}
                items(visible,key={it.id()}){item->AccountChoiceTile(item.optString("name"),selectedId==item.id(),"entry-account-${item.id()}",item,enabled){choose(item.id())}}
            }
        }
    }
}

@Composable private fun AccountChoiceTile(label:String,selected:Boolean,tag:String,item:JSONObject?,enabled:Boolean,click:()->Unit){
    val color=if(item==null)MaterialTheme.colorScheme.secondary else presetColor(item.optString("color"),MaterialTheme.colorScheme.primary)
    Column(Modifier.fillMaxWidth().heightIn(min=88.dp).testTag(tag).semantics{this.selected=selected;contentDescription=if(item==null)label else "$label，${item.optString("currency")}"}.clickable(enabled=enabled,onClick=click).padding(horizontal=3.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.size(32.dp).clip(CircleShape).background(if(selected&&item!=null)MaterialTheme.colorScheme.primaryContainer else color),contentAlignment=Alignment.Center){
            if(item==null)Icon(Icons.Outlined.AccountBalanceWallet,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSecondary)
            else BuiltinIcon(item,Modifier.size(26.dp),if(selected)MaterialTheme.colorScheme.onPrimaryContainer else contrastInk(color),account=true)
        }
        Spacer(Modifier.height(4.dp));Text(label,fontSize=13.sp,lineHeight=17.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(selected&&item!=null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LedgerAssetPresetPicker(dismiss:()->Unit,choose:(AccountPreset)->Unit,enabled:Boolean=true){
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),shape=RoundedCornerShape(topStart=8.dp,topEnd=8.dp),containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface,dragHandle=null){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.765f).testTag("entry-asset-picker")){
            Text("选择资产分类",Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=14.dp,vertical=16.dp),fontSize=16.sp,fontWeight=FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("entry-asset-groups"),contentPadding=PaddingValues(bottom=16.dp)){
                accountPresetGroups.forEach{group->
                    item(key="heading-${group.title}"){
                        Spacer(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest))
                        Text(group.title,Modifier.padding(start=16.dp,top=14.dp,bottom=12.dp),fontSize=15.sp,fontWeight=FontWeight.Bold)
                    }
                    group.presets.chunked(4).forEachIndexed{index,row->item(key="${group.title}-$index"){
                        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp)){
                            row.forEach{preset->
                                Column(Modifier.weight(1f).heightIn(min=80.dp).testTag("entry-asset-${preset.key}").clickable(enabled=enabled){choose(preset)}.padding(horizontal=3.dp,vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                    val iconColor=presetColor(preset.color,MaterialTheme.colorScheme.primary)
                                    Box(Modifier.size(30.dp).clip(CircleShape).background(iconColor),contentAlignment=Alignment.Center){BuiltinIcon(JSONObject().put("icon",preset.icon),Modifier.size(25.dp),contrastInk(iconColor),account=true)}
                                    Spacer(Modifier.height(5.dp));Text(preset.label,fontSize=13.sp,lineHeight=17.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            repeat(4-row.size){Spacer(Modifier.weight(1f))}
                        }
                    }}
                }
            }
        }
    }
}

private fun presetColor(value:String,fallback:Color):Color=runCatching{Color(android.graphics.Color.parseColor("#"+value.removePrefix("#")))}.getOrDefault(fallback)

private fun contrastInk(background:Color)=if(background.luminance()>0.179f)Color.Black else Color.White
