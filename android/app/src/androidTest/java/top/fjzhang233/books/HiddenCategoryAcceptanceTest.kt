package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Only creates and removes its own explicitly marked objects on the local QA backend. */
class HiddenCategoryAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun preserveHiddenOriginalAndAllowExplicitVisibleReplacement()=runBlocking {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.API_BASE_URL in setOf("http://127.0.0.1:18090","http://127.0.0.1:18092"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val config=File(context.filesDir,"qa-config.json"); assumeTrue(config.isFile)
        val credentials=JSONObject(config.readText()); val store=TokenStore(context); val previous=store.read()
        var token:String?=null; val api=BooksApi(BuildConfig.API_BASE_URL){token}
        val categories=mutableListOf<String>(); val temporarilyHidden=mutableListOf<String>(); var account=""; var transaction=""; var copied=""; var transfer=""; var account2=""; var scenario:ActivityScenario<MainActivity>?=null
        var stage="setup"
        fun model(action:(BooksModel)->Unit){scenario!!.onActivity{action(ViewModelProvider(it)[BooksModel::class.java])}}
        fun ready(){compose.waitUntil(45000){var done=false;model{done=!it.busy};done}}
        fun waitTag(value:String){compose.waitUntil(45000){compose.onAllNodesWithTag(value,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
        suspend fun revision():String=(api.call("/api/v1/transaction/categories/revision.json") as JSONObject).getString("revision")
        suspend fun createCategory(name:String,parent:String,type:Int=2):String {
            val req=JSONObject().put("name",name).put("type",type).put("parentId",parent).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString()).put("expectedRevision",revision())
            return (api.call("/api/v1/transaction/categories/add.json",req) as JSONObject).id().also{categories.add(it)}
        }
        suspend fun hide(id:String,value:Boolean){api.call("/api/v1/transaction/categories/hide.json",JSONObject().put("id",id).put("hidden",value).put("expectedRevision",revision()))}
        fun reload(){model{it.reload()};ready()}
        fun closeAndWait(){
            compose.onNodeWithContentDescription("取消记账").performClick()
            compose.waitUntil(45000){var closed=false;model{closed=!it.showEditor&&!it.busy};closed && compose.onAllNodesWithTag("entry-key-保存",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        }
        suspend fun edit(note:String,selected:String?=null){
            model{it.openTransaction(transaction)};compose.waitUntil(45000){var done=false;model{done=!it.busy&&it.showEditor};done}
            waitTag("entry-hidden-original-category")
            compose.onNodeWithText("收入",substring=false).performClick();compose.onNodeWithText("支出",substring=false).performClick();waitTag("entry-hidden-original-category")
            if(selected!=null){
                val selectedParent=api.categories().first{it.id()==selected}.id("parentId");compose.onNodeWithTag("entry-category-$selectedParent").performClick();waitTag("entry-category-$selected");compose.onNodeWithTag("entry-categories").performScrollToNode(hasTestTag("entry-category-$selected"));compose.onNodeWithTag("entry-category-$selected").performClick()
                hide(selected,true);reload();waitTag("entry-copy-category-required")
                hide(selected,false);reload();waitTag("entry-copy-category-required")
                compose.onNodeWithTag("entry-category-$selectedParent").performClick();waitTag("entry-category-$selected");compose.onNodeWithTag("entry-categories").performScrollToNode(hasTestTag("entry-category-$selected"));compose.onNodeWithTag("entry-category-$selected").performClick()
                compose.onNodeWithText("收入",substring=false).performClick();compose.onNodeWithText("支出",substring=false).performClick()
                compose.onNodeWithTag("entry-category-$selectedParent").performClick();waitTag("entry-category-$selected");compose.onNodeWithTag("entry-categories").performScrollToNode(hasTestTag("entry-category-$selected"));compose.onNodeWithTag("entry-category-$selected").assertIsSelected()
            }
            compose.onNodeWithTag("entry-comment").performTextReplacement(note)
            compose.onNodeWithTag("entry-comment").performImeAction();compose.waitForIdle()
            compose.onNodeWithTag("entry-key-保存").performClick();compose.waitUntil(45000){var done=false;model{done=!it.busy&&!it.showEditor};done}
            model{check(it.error.isBlank())}
        }
        try {
            token=api.login(credentials.getString("username"),credentials.getString("password"),"").getString("token");store.write(token!!)
            val suffix=UUID.randomUUID().toString().take(12);val parent=createCategory("QA隐藏父-$suffix","0");val child=createCategory("QA隐藏子-$suffix",parent)
            val otherParent=createCategory("QA可选父-$suffix","0");val otherChild=createCategory("QA可选子-$suffix",otherParent)
            account=(api.call("/api/v1/accounts/add.json",JSONObject().put("name","QA隐藏-$suffix").put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
            val body=JSONObject().put("type",3).put("categoryId",child).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("comment","QA隐藏初始-$suffix").put("hideAmount",false).put("pictureIds",JSONArray()).put("tagIds",JSONArray()).put("clientSessionId",UUID.randomUUID().toString())
            transaction=(api.call("/api/v1/transactions/add.json",body) as JSONObject).id()
            hide(child,true);scenario=ActivityScenario.launch(MainActivity::class.java);waitTag("ledger-list");ready()
            stage="internal-explicit-selection";val system=api.categories().filter{it.optBoolean("familyInternalTransfer") && it.id("parentId")!="0" && !it.optBoolean("hidden")}
            val internalExpense=system.first{it.optInt("type")==2};val internalIncome=system.first{it.optInt("type")==1}
            model{it.newTransaction()};waitTag("entry-categories")
            compose.onNodeWithTag("entry-category-${internalExpense.id("parentId")}").performClick();compose.onNodeWithTag("entry-categories").performScrollToNode(hasTestTag("entry-category-${internalExpense.id()}"));compose.onNodeWithTag("entry-category-${internalExpense.id()}").assertIsNotSelected().performClick().assertIsSelected()
            compose.onNodeWithText("收入",substring=false).performClick();compose.onNodeWithTag("entry-category-${internalIncome.id("parentId")}").performClick();compose.onNodeWithTag("entry-categories").performScrollToNode(hasTestTag("entry-category-${internalIncome.id()}"));compose.onNodeWithTag("entry-category-${internalIncome.id()}").assertIsNotSelected().performClick().assertIsSelected()
            closeAndWait()
            stage="hidden-child-memo";edit("QA隐藏子编辑-$suffix");assertEquals(child,api.transaction(transaction).id("categoryId"))
            stage="hidden-copy-requires-choice";model{it.copyTransaction(transaction)};compose.waitUntil(45000){var done=false;model{done=!it.busy&&it.showEditor};done}
            waitTag("entry-copy-category-required");compose.onNodeWithText("收入",substring=false).performClick();compose.onNodeWithText("支出",substring=false).performClick();waitTag("entry-copy-category-required");compose.onNodeWithTag("entry-key-保存").performClick();compose.onNodeWithText("请选择账户和分类").assertIsDisplayed()
            val otherChildParent=api.categories().first{it.id()==otherChild}.id("parentId");compose.onNodeWithTag("entry-category-$otherChildParent").performClick();compose.onNodeWithTag("entry-categories").performScrollToNode(hasTestTag("entry-category-$otherChild"));compose.onNodeWithTag("entry-category-$otherChild").performClick()
            compose.onNodeWithTag("entry-comment").performTextReplacement("QA隐藏复制-$suffix");compose.onNodeWithTag("entry-comment").performImeAction();compose.onNodeWithTag("entry-key-保存").performClick()
            compose.waitUntil(45000){var done=false;model{done=!it.busy&&!it.showEditor};done}
            val copies=(api.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1") as JSONObject).array("items").filter{it.optString("comment")=="QA隐藏复制-$suffix"}
            assertEquals(1,copies.size);copied=copies.single().id();assertEquals(otherChild,copies.single().id("categoryId"));api.deleteTransaction(copied);copied=""
            hide(child,false);hide(parent,true);reload()
            stage="hidden-parent-memo";edit("QA隐藏父编辑-$suffix");assertEquals(child,api.transaction(transaction).id("categoryId"))
            stage="hidden-parent-explicit-reselect";edit("QA主动重选-$suffix",otherChild);assertEquals(otherChild,api.transaction(transaction).id("categoryId"))
            stage="transfer-hidden-copy"
            val transferParent=createCategory("QA转账父-$suffix","0",3);val transferChild=createCategory("QA转账子-$suffix",transferParent,3)
            val replacementParent=createCategory("QA转账可选父-$suffix","0",3);val replacementChild=createCategory("QA转账可选子-$suffix",replacementParent,3)
            account2=(api.call("/api/v1/accounts/add.json",JSONObject().put("name","QA转账-$suffix").put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
            val transferBody=JSONObject().put("type",4).put("categoryId",transferChild).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",1).put("destinationAccountId",account2).put("destinationAmount",1).put("comment","QA转账初始-$suffix").put("hideAmount",false).put("pictureIds",JSONArray()).put("tagIds",JSONArray()).put("clientSessionId",UUID.randomUUID().toString())
            transfer=(api.call("/api/v1/transactions/add.json",transferBody) as JSONObject).id();hide(transferChild,true);reload()
            model{it.copyTransaction(transfer)};compose.waitUntil(45000){var done=false;model{done=!it.busy&&it.showEditor};done}
            waitTag("entry-copy-category-required");compose.onNodeWithText("支出",substring=false).performClick();compose.onNodeWithText("转账",substring=false).performClick();waitTag("entry-copy-category-required")
            compose.onNodeWithTag("entry-key-保存").performClick();compose.onNodeWithText("请选择账户和分类").assertIsDisplayed()
            compose.onNodeWithTag("entry-transfer-category").performClick();compose.onNodeWithTag("entry-transfer-category-$replacementChild").performClick()
            compose.onNodeWithTag("entry-comment").performTextReplacement("QA转账复制-$suffix");compose.onNodeWithTag("entry-comment").performImeAction();compose.onNodeWithTag("entry-key-保存").performClick()
            compose.waitUntil(45000){var done=false;model{done=!it.busy&&!it.showEditor};done}
            val transferCopies=(api.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1") as JSONObject).array("items").filter{it.optString("comment")=="QA转账复制-$suffix"}
            assertEquals(1,transferCopies.size);copied=transferCopies.single().id();assertEquals(replacementChild,transferCopies.single().id("categoryId"));api.deleteTransaction(copied);copied=""
            hide(transferChild,false);hide(transferParent,true);reload();model{it.copyTransaction(transfer)};compose.waitUntil(45000){var done=false;model{done=!it.busy&&it.showEditor};done};waitTag("entry-copy-category-required")
            compose.onNodeWithTag("entry-transfer-category").performClick();compose.onNodeWithTag("entry-transfer-category-$replacementChild").performClick();closeAndWait()
            stage="new-transfer-ui"
            model{it.newTransaction()};stage="new-wait-editor";waitTag("entry-key-保存");compose.onNodeWithText("转账",substring=false).performClick()
            compose.onNodeWithTag("entry-transfer-category").performClick();compose.onNodeWithTag("entry-transfer-category-$replacementChild").performClick()
            stage="new-source";compose.onNodeWithTag("entry-account").performClick();waitTag("entry-account-grid");compose.onNodeWithTag("entry-account-grid").performScrollToNode(hasTestTag("entry-account-$account"));compose.onNodeWithTag("entry-account-$account").performClick()
            stage="new-destination";compose.onNodeWithText("转入账户").performClick();waitTag("entry-account-grid");compose.onNodeWithTag("entry-account-grid").performScrollToNode(hasTestTag("entry-account-$account2"));compose.onNodeWithTag("entry-account-$account2").performClick()
            compose.onNodeWithTag("entry-key-1").performClick();compose.onNodeWithTag("entry-comment").performTextReplacement("QA新建转账-$suffix");compose.onNodeWithTag("entry-comment").performImeAction();compose.onNodeWithTag("entry-key-保存").performClick()
            stage="new-saved";compose.waitUntil(45000){var done=false;model{done=!it.busy&&!it.showEditor};done}
            val newTransfers=(api.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1") as JSONObject).array("items").filter{it.optString("comment")=="QA新建转账-$suffix"}
            assertEquals(1,newTransfers.size);copied=newTransfers.single().id();assertEquals(replacementChild,newTransfers.single().id("categoryId"));api.deleteTransaction(copied);copied=""
            stage="no-transfer-category"
            val visibleTransferParents=api.categories().filter{it.optInt("type")==3 && it.id("parentId")=="0" && !it.optBoolean("hidden")}.map{it.id()}
            for(id in visibleTransferParents){hide(id,true);temporarilyHidden.add(id)}
            reload();model{it.newTransaction()};waitTag("entry-key-保存");compose.onNodeWithText("转账",substring=false).performClick();waitTag("entry-transfer-category-empty")
            compose.onNodeWithTag("entry-key-保存").performClick();compose.onNodeWithText("请选择账户和分类").assertIsDisplayed();closeAndWait()
            for(id in temporarilyHidden.toList()){hide(id,false);temporarilyHidden.remove(id)}
            context.openFileOutput("qa-hidden-category-result.json",android.content.Context.MODE_PRIVATE).use{it.write(JSONObject().put("hiddenChildMemoPreserved",true).put("hiddenParentMemoPreserved",true).put("explicitReselectChangedCategory",true).put("familyInternalExpenseAndIncomeExplicitlySelectable",true).put("concurrentlyHiddenChoiceRequiresReselect",true).toString().toByteArray());it.fd.sync()}
        } catch(e:Throwable) {
            var state="unavailable";if(scenario!=null)runCatching{model{state="busy=${it.busy} editor=${it.showEditor} confirmation=${it.transactionNeedsConfirmation} errorPresent=${it.error.isNotBlank()} tokenPresent=${it.token!=null}"}}
            throw AssertionError("hidden-category stage=$stage class=${e.javaClass.simpleName} apiCode=${(e as? ApiFailure)?.code ?: 0} $state",e)
        }
        finally {
            scenario?.close()
            if(token!=null){
                for(id in temporarilyHidden.toList()){runCatching{hide(id,false)}}
                if(copied.isNotEmpty())api.deleteTransaction(copied)
                if(transfer.isNotEmpty())api.deleteTransaction(transfer)
                if(transaction.isNotEmpty())api.deleteTransaction(transaction)
                for(id in categories.asReversed()){runCatching{hide(id,false)};api.call("/api/v1/transaction/categories/delete.json",JSONObject().put("id",id).put("expectedRevision",revision()))}
                if(account2.isNotEmpty())api.call("/api/v1/accounts/delete.json",JSONObject().put("id",account2))
                if(account.isNotEmpty())api.call("/api/v1/accounts/delete.json",JSONObject().put("id",account))
                runCatching{api.call("/api/logout.json")}
            }
            if(previous==null)store.clear() else store.write(previous)
        }
    }
}
