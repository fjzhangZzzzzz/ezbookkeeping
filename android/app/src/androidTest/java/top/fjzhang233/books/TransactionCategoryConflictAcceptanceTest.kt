package top.fjzhang233.books

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class TransactionCategoryConflictAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun staleCategoryCannotSaveUntilRefreshedAndConfirmed()=runBlocking {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.API_BASE_URL=="http://127.0.0.1:18092")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,"qa-config.json");assumeTrue(file.isFile)
        val login=JSONObject(file.readText());val store=TokenStore(context);val previous=store.read()
        var token:String?=null;val api=BooksApi(BuildConfig.API_BASE_URL){token};val ids=mutableListOf<String>()
        var account="";var transaction="";var scenario:ActivityScenario<MainActivity>?=null;var stage="setup"
        suspend fun revision()=(api.call("/api/v1/transaction/categories/revision.json") as JSONObject).getString("revision")
        suspend fun create(name:String,parent:String):String {
            val body=JSONObject().put("name",name).put("type",2).put("parentId",parent).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString()).put("expectedRevision",revision())
            return (api.call("/api/v1/transaction/categories/add.json",body) as JSONObject).id().also{ids.add(it)}
        }
        fun model(action:(BooksModel)->Unit){scenario!!.onActivity{action(ViewModelProvider(it)[BooksModel::class.java])}}
        fun ready(){compose.waitUntil(45000){var ok=false;model{ok=!it.busy};ok};compose.waitForIdle()}
        fun waitText(text:String){compose.waitUntil(45000){compose.onAllNodesWithText(text,substring=true,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
        try {
            token=api.login(login.getString("username"),login.getString("password"),"").getString("token");store.write(token!!)
            val suffix=UUID.randomUUID().toString().take(8);val parent=create("QA交易父-$suffix","0");val child=create("QA交易子-$suffix",parent)
            account=(api.call("/api/v1/accounts/add.json",JSONObject().put("name","QA交易账户-$suffix").put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
            scenario=ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(45000){var done=false;model{done=!it.busy && it.accounts.any{a->a.id()==account} && it.categoryRevision!=null};done}
            model{it.newTransaction()};compose.waitUntil(45000){compose.onAllNodesWithTag("entry-categories").fetchSemanticsNodes().isNotEmpty()}
            val categoryGrid=compose.onNodeWithTag("entry-category-grid")
            categoryGrid.performScrollToNode(hasTestTag("entry-category-$parent"))
            compose.onNodeWithTag("entry-category-$parent").performClick()
            categoryGrid.performScrollToNode(hasTestTag("entry-category-$child"))
            compose.onNodeWithTag("entry-category-$child").performClick()
            compose.onNodeWithTag("entry-account").performClick()
            compose.onNodeWithTag("entry-account-grid").performScrollToNode(hasTestTag("entry-account-$account"))
            compose.onNodeWithTag("entry-account-$account").performClick()
            compose.onNodeWithTag("entry-key-1").performClick()
            compose.onNodeWithTag("entry-comment").performTextReplacement("QA分类竞态-$suffix")
            compose.onNodeWithTag("entry-comment").performImeAction();compose.waitForIdle()
            stage="remote-change"
            val row=api.categories().first{it.id()==child}
            api.call("/api/v1/transaction/categories/modify.json",JSONObject().put("id",child).put("name","QA已变更-$suffix").put("type",2).put("parentId",parent).put("icon",row.optString("icon")).put("iconType",0).put("color",row.optString("color")).put("comment",row.optString("comment")).put("hidden",false).put("expectedRevision",revision()))
            stage="submit-conflict";compose.onNodeWithTag("entry-key-保存").performClick();ready();waitText("分类已刷新，请核对所选分类后继续")
            assertTrue((api.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1") as JSONObject).array("items").none{it.optString("comment")=="QA分类竞态-$suffix"})
            model{assertFalse(it.transactionNeedsConfirmation);assertTrue(it.showEditor)}
            compose.onNodeWithText("确认",substring=false).performClick();waitText("所选分类现在为")
            compose.onNodeWithText("确认",substring=false).performClick();compose.waitForIdle()
            stage="submit-reviewed";compose.onNodeWithTag("entry-key-保存").performClick()
            compose.waitUntil(45000){var done=false;model{done=!it.busy&&!it.showEditor};done}
            val rows=(api.call("/api/v1/transactions/list.json?account_ids=$account&count=30&page=1") as JSONObject).array("items").filter{it.optString("comment")=="QA分类竞态-$suffix"}
            assertEquals(1,rows.size);transaction=rows.single().id();assertEquals(child,rows.single().id("categoryId"))
        } catch(error:Throwable){throw AssertionError("transaction-category stage=$stage class=${error.javaClass.simpleName}",error)}
        finally {
            scenario?.close()
            if(token!=null){if(transaction.isNotEmpty())runCatching{api.deleteTransaction(transaction)};for(id in ids.asReversed())runCatching{api.call("/api/v1/transaction/categories/delete.json",JSONObject().put("id",id).put("expectedRevision",revision()))};if(account.isNotEmpty())runCatching{api.call("/api/v1/accounts/delete.json",JSONObject().put("id",account))}}
            if(previous==null)store.clear() else store.write(previous)
        }
    }
}
