package top.fjzhang233.books

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses only the dedicated local QA database and deletes its own synthetic categories. */
class SharedCategoryManagementAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun hideRestoreAndConflictKeepDraft()=runBlocking {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.API_BASE_URL=="http://127.0.0.1:18092")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,"qa-config.json");assumeTrue(file.isFile)
        val login=JSONObject(file.readText());val store=TokenStore(context);val previous=store.read()
        var token:String?=null;val api=BooksApi(BuildConfig.API_BASE_URL){token}
        val ids=mutableListOf<String>();var account="";var transaction="";var scenario:ActivityScenario<MainActivity>?=null;var stage="setup"
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
            val suffix=UUID.randomUUID().toString().take(8);val parentName="QA管理父-$suffix";val childName="QA管理子-$suffix"
            val draftName = "稿${suffix.take(3)}"
            val parent=create(parentName,"0");val child=create(childName,parent)
            account=(api.call("/api/v1/accounts/add.json",JSONObject().put("name","QA管理账户-$suffix").put("category",1).put("type",1).put("currency","CNY").put("balance","0").put("balanceTime",System.currentTimeMillis()/1000).put("icon","1").put("iconType",0).put("color","006B5C").put("comment","").put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
            transaction=(api.call("/api/v1/transactions/add.json",JSONObject().put("type",3).put("categoryId",child).put("time",System.currentTimeMillis()/1000).put("utcOffset",480).put("sourceAccountId",account).put("sourceAmount",1).put("destinationAccountId","0").put("destinationAmount",0).put("comment","QA管理交易-$suffix").put("hideAmount",false).put("pictureIds",JSONArray()).put("tagIds",JSONArray()).put("clientSessionId",UUID.randomUUID().toString())) as JSONObject).id()
            scenario=ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(45000){var done=false;model{done=!it.busy && it.categories.isNotEmpty() && it.categoryRevision!=null};done}
            scenario!!.onActivity { activity -> val vm=ViewModelProvider(activity)[BooksModel::class.java]; activity.setContent { FamilyTheme { CatalogPage(vm) } } }

            val parentTag = "catalog-parent-$parent"
            val parentCard = hasAnyAncestor(hasTestTag(parentTag))
            fun scrollToParent() {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(parentTag))
            }
            fun openParent() {
                scrollToParent()
                compose.onNode(hasContentDescription("编辑$parentName") and parentCard).performClick()
            }
            fun openChild() {
                scrollToParent()
                val expand = hasContentDescription("展开 $parentName") and parentCard
                if (compose.onAllNodes(expand).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNode(expand).performClick()
                }
                compose.onNode(hasTestTag("entry-category-$child") and parentCard).performScrollTo().performClick()
            }
            ready();stage="delete-referenced"
            openChild()
            compose.onNodeWithText("删除分类").performScrollTo().performClick();waitText("此分类涉及活跃家庭成员的 1 笔历史交易")
            compose.onNodeWithText("确认",substring=false).performClick();ready();waitText("该分类仍被交易或模板使用，不能删除")
            model{assertFalse(it.entityBlocked)};assertTrue(api.categories().any{it.id()==child})
            compose.onNodeWithText("取消",substring=false).performClick()
            stage="hide-parent"
            openParent()
            compose.onNodeWithText("隐藏分类").performScrollTo().performClick();waitText("此分类涉及活跃家庭成员的 1 笔历史交易")
            compose.onNodeWithText("确认",substring=false).performClick();ready()
            assertTrue(api.categories().first{it.id()==parent}.optBoolean("hidden"));assertFalse(api.categories().first{it.id()==child}.optBoolean("hidden"))
            stage="restore-parent"
            compose.onNodeWithText("显示已停用", substring = false).performScrollTo().performClick()
            openParent()
            compose.onNodeWithText("恢复分类").performScrollTo().performClick();waitText("此分类涉及活跃家庭成员的 1 笔历史交易")
            compose.onNodeWithText("确认",substring=false).performClick();ready()
            assertFalse(api.categories().first{it.id()==parent}.optBoolean("hidden"));assertFalse(api.categories().first{it.id()==child}.optBoolean("hidden"))
            stage="restore-preserves-child-hidden"
            api.call("/api/v1/transaction/categories/hide.json",JSONObject().put("id",child).put("hidden",true).put("expectedRevision",revision()))
            model{it.reload()};ready()
            openParent()
            compose.onNodeWithText("隐藏分类").performScrollTo().performClick();waitText("此分类涉及活跃家庭成员的 1 笔历史交易")
            compose.onNodeWithText("确认",substring=false).performClick();ready()
            openParent()
            compose.onNodeWithText("恢复分类").performScrollTo().performClick();waitText("此分类涉及活跃家庭成员的 1 笔历史交易")
            compose.onNodeWithText("确认",substring=false).performClick();ready()
            assertFalse(api.categories().first{it.id()==parent}.optBoolean("hidden"));assertTrue(api.categories().first{it.id()==child}.optBoolean("hidden"))
            api.call("/api/v1/transaction/categories/hide.json",JSONObject().put("id",child).put("hidden",false).put("expectedRevision",revision()))
            model{it.reload()};ready()
            stage="conflict";openChild()
            compose.onNode(hasSetTextAction()).performTextReplacement(draftName)
            val row=api.categories().first{it.id()==child}
            val changed=JSONObject().put("id",child).put("name","QA远端变更-$suffix").put("type",2).put("parentId",parent).put("icon",row.optString("icon")).put("iconType",0).put("color",row.optString("color")).put("comment",row.optString("comment")).put("hidden",false).put("expectedRevision",revision())
            api.call("/api/v1/transaction/categories/modify.json",changed)
            compose.onNodeWithText("保存分类").performClick();ready();waitText("分类已在其他设备变更")
            compose.onNodeWithText(draftName).assertExists()
            compose.onNodeWithText("刷新分类并重新评审草稿").performScrollTo().performClick();ready()
            compose.onNodeWithText(draftName).assertExists()
            compose.onNodeWithText("保存分类").performClick();ready();waitText("此分类涉及活跃家庭成员的 1 笔历史交易")
            // A different device changes the revision after impact review but before confirmation.
            val parentRow=api.categories().first{it.id()==parent}
            api.call("/api/v1/transaction/categories/modify.json",JSONObject().put("id",parent).put("name","QA并发父-$suffix").put("type",2).put("parentId","0").put("icon",parentRow.optString("icon")).put("iconType",0).put("color",parentRow.optString("color")).put("comment",parentRow.optString("comment")).put("hidden",false).put("expectedRevision",revision()))
            compose.onNodeWithText("确认",substring=false).performClick();ready();waitText("分类已在其他设备变更，请刷新并重新评审后再保存")
            compose.onNodeWithText(draftName).assertExists()
            model{assertFalse(it.entityBlocked)}
            compose.onNodeWithText("刷新分类并重新评审草稿").performScrollTo().performClick();ready()
            compose.onNodeWithText("保存分类").performClick();ready();waitText("此分类涉及活跃家庭成员的 1 笔历史交易")
            compose.onNodeWithText("确认",substring=false).performClick();ready()
            assertEquals(draftName,api.categories().first{it.id()==child}.optString("name"))
        } catch(error:Throwable){throw AssertionError("shared-category stage=$stage class=${error.javaClass.simpleName}",error)}
        finally {
            scenario?.close()
            if(token!=null) {
                if(transaction.isNotEmpty()) runCatching{api.deleteTransaction(transaction)}
                for(id in ids.asReversed()) runCatching{api.call("/api/v1/transaction/categories/delete.json",JSONObject().put("id",id).put("expectedRevision",revision()))}
                if(account.isNotEmpty()) runCatching{api.call("/api/v1/accounts/delete.json",JSONObject().put("id",account))}
            }
            if(previous==null)store.clear() else store.write(previous)
        }
    }
}
