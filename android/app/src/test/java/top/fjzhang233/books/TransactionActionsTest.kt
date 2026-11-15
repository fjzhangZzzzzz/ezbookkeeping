package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TransactionActionsTest {
    @Test fun copyPreservesBusinessFieldsButNeverIdentityOrExistingAttachmentLinks(){
        val original=JSONObject("""{"id":"101","clientSessionId":"old-request","type":4,"time":100,"utcOffset":0,"categoryId":"8","sourceAccountId":"1","sourceAmount":123,"destinationAccountId":"2","destinationAmount":99,"hideAmount":true,"comment":"虚构备注","tagIds":["3","4"],"pictures":[{"pictureId":"55","originalUrl":"/synthetic.png"}]}""")
        val before=original.toString();val copied=transactionCopyDraft(original,200)
        assertEquals(before,original.toString());assertFalse(copied.has("id"));assertFalse(copied.has("clientSessionId"));assertFalse(copied.has("pictures"))
        assertEquals(200L,copied.getLong("time"));assertEquals(480,copied.getInt("utcOffset"))
        assertEquals(4,copied.getInt("type"));assertEquals(123L,copied.getLong("sourceAmount"));assertEquals(99L,copied.getLong("destinationAmount"))
        assertEquals("1",copied.getString("sourceAccountId"));assertEquals("2",copied.getString("destinationAccountId"));assertTrue(copied.getBoolean("hideAmount"))
        copied.getJSONArray("tagIds").put("5");assertEquals(2,original.getJSONArray("tagIds").length())
    }
    @Test(expected=IllegalArgumentException::class) fun balanceAdjustmentCannotBeCopiedAsOrdinaryEntry(){transactionCopyDraft(JSONObject("{\"type\":1}"),200)}
    @Test fun nativeDeleteAndUnusedPictureCleanupUseExactExistingContracts()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            repeat(2){server.enqueue(MockResponse().setBody("{\"success\":true,\"result\":true}"))}
            val api=BooksApi("http://127.0.0.1:${server.port}"){"synthetic-only"}
            api.deleteTransaction("101");api.removeUnusedPicture("202")
            val deleted=server.takeRequest();assertEquals("POST",deleted.method);assertEquals("/api/v1/transactions/delete.json",deleted.path);assertEquals("101",JSONObject(deleted.body.readUtf8()).getString("id"))
            val unused=server.takeRequest();assertEquals("/api/v1/transaction/pictures/remove_unused.json",unused.path);assertEquals("202",JSONObject(unused.body.readUtf8()).getString("id"))
        }finally{server.shutdown()}
    }
    @Test fun onlyExactTransactionNotFoundConvergesAndTransferDeletesCanonicalId()=runBlocking {
        val server=MockWebServer();server.start()
        try{
            val api=BooksApi("http://127.0.0.1:${server.port}"){"synthetic-only"}
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"errorCode":205001}"""))
            assertTrue(deleteTransactionOrAlreadyAbsent(api,"101"));server.takeRequest()
            for(code in listOf(205002,202002,100001)){
                server.enqueue(MockResponse().setResponseCode(if(code==202002)401 else 400).setBody("""{"success":false,"errorCode":$code}"""))
                var failed:Int?=null;try{deleteTransactionOrAlreadyAbsent(api,"101")}catch(error:ApiFailure){failed=error.code}
                assertEquals(code,failed);server.takeRequest()
            }
            server.enqueue(MockResponse().setBody("""{"success":true,"result":{"id":"202","type":4,"editable":true}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"result":true}"""))
            assertFalse(deleteTransactionOrAlreadyAbsent(api,"201"));server.takeRequest()
            assertEquals("202",JSONObject(server.takeRequest().body.readUtf8()).getString("id"))
        }finally{server.shutdown()}
    }

}
