package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun revokedImageRequestIsASessionExpiry() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            val api=BooksApi("http://127.0.0.1:${server.port}"){"synthetic-session"}
            val error=runCatching { api.picture("/api/v1/family/pictures/123.png") }.exceptionOrNull() as ApiFailure
            assertTrue(error.sessionExpired)
        } finally { server.shutdown() }
    }
    @Test fun committedMutationClosesFormEvenWhenReadFails() = runBlocking {
        var writes=0;var closed=false
        val refreshed=commitThenRefresh({writes++},{closed=true},{throw java.io.IOException("synthetic read unavailable")})
        assertFalse(refreshed);assertTrue(closed);assertEquals(1,writes)
        var closedFailedWrite=false
        runCatching { commitThenRefresh({throw java.io.IOException("synthetic unknown write result")},{closedFailedWrite=true},{}) }
        assertFalse(closedFailedWrite)
    }
    @Test fun moneyUsesExactMinorUnits() {
        assertEquals(1L,parseMinor("0.01")); assertEquals(1234567890123L,parseMinor("12345678901.23"))
        assertEquals("-0.01",money("-1")); assertEquals("1.20",money("120"))
        for(invalid in listOf("0","-1","1.001","NaN","1e2","1,00")) assertThrows(IllegalArgumentException::class.java) { parseMinor(invalid) }
        assertThrows(ArithmeticException::class.java) { parseMinor("999999999999999999999") }
    }
    @Test fun wrongPasswordDoesNotRevokeExistingSessionButNormal401Does() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val api=BooksApi("http://127.0.0.1:${server.port}") { "synthetic-existing-token" }
            val rejection=MockResponse().setResponseCode(401).setBody("{\"success\":false,\"errorCode\":201001}")
            server.enqueue(rejection)
            val login=runCatching { api.login("synthetic","synthetic-not-a-secret","") }.exceptionOrNull() as ApiFailure
            assertFalse(login.sessionExpired); assertEquals(201001,login.code)
            assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(rejection)
            val callback=runCatching { api.call("/api/oauth2/authorize.json",JSONObject(),true,"synthetic-callback") }.exceptionOrNull() as ApiFailure
            assertFalse(callback.sessionExpired); server.takeRequest()
            server.enqueue(rejection)
            val revoked=runCatching { api.call("/api/v1/accounts/list.json") }.exceptionOrNull() as ApiFailure
            assertTrue(revoked.sessionExpired)
            val request=server.takeRequest(); assertTrue(request.getHeader("Authorization")!=null)
            assertEquals("480",request.getHeader("X-Timezone-Offset"))
            assertEquals("Asia/Shanghai",request.getHeader("X-Timezone-Name"))
        } finally { server.shutdown() }
    }
    @Test fun native404IsUpgradeMessage() = runBlocking {
        val server=MockWebServer(); server.start()
        try { server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
            val error=runCatching { BooksApi("http://127.0.0.1:${server.port}"){null}.call("/api/native/oauth2/start.json",JSONObject(),false) }.exceptionOrNull() as ApiFailure
            assertFalse(error.sessionExpired); assertTrue(error.message.orEmpty().contains("服务端"))
        } finally { server.shutdown() }
    }
    private fun metadata()=JSONObject().put("schemaVersion",1).put("channel","stable").put("packageId",UpdatePolicy.PACKAGE).put("versionName","0.2.0").put("versionCode",2).put("minSdk",26).put("apkAssetName","ezbookkeeping-android-0.2.0.apk").put("apkSha256","a".repeat(64)).put("signingCertificateSha256",UpdatePolicy.CERTIFICATE).put("sourceCommit","b".repeat(40))
    @Test fun updaterRejectsWrongPackageChannelDowngradeRepoAndSigner() {
        val tag="android-v0.2.0";val url="https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/$tag/ezbookkeeping-android-0.2.0.apk"
        val release=UpdatePolicy.parse(metadata(),tag,url,1,35)
        assertEquals(2,release.versionCode.toInt())
        assertThrows(IllegalArgumentException::class.java){UpdatePolicy.parse(metadata().put("packageId","bad.package"),tag,url,1,35)}
        assertThrows(IllegalArgumentException::class.java){UpdatePolicy.parse(metadata().put("channel","beta"),tag,url,1,35)}
        assertThrows(IllegalArgumentException::class.java){UpdatePolicy.parse(metadata(),tag,url,2,35)}
        assertThrows(IllegalArgumentException::class.java){UpdatePolicy.parse(metadata(),tag,url.replace("github.com","example.com"),1,35)}
        assertThrows(IllegalArgumentException::class.java){UpdatePolicy.parse(metadata().put("minSdk",36),tag,url,1,35)}
        assertThrows(IllegalArgumentException::class.java){UpdatePolicy.verifyIdentity(UpdatePolicy.PACKAGE,2,26,setOf("wrong"),setOf(UpdatePolicy.CERTIFICATE),release,35)}
        assertThrows(IllegalArgumentException::class.java){UpdatePolicy.verifyIdentity("wrong.package",2,26,setOf(UpdatePolicy.CERTIFICATE),setOf(UpdatePolicy.CERTIFICATE),release,35)}
        UpdatePolicy.verifyIdentity(UpdatePolicy.PACKAGE,2,26,setOf(UpdatePolicy.CERTIFICATE),setOf(UpdatePolicy.CERTIFICATE),release,35)
    }
    @org.junit.Test fun nativeKeypadAndShanghaiMonthBounds() {
        var input="";listOf("0",".","0","1").forEach{input=entryKey(input,it)}
        org.junit.Assert.assertEquals("0.01",input);org.junit.Assert.assertEquals(1L,entryMinor(input))
        org.junit.Assert.assertEquals("0.01",entryKey(input,"9"))
        org.junit.Assert.assertEquals(101L,entryMinor("1+0.01"));org.junit.Assert.assertEquals(99L,entryMinor("1−0.01"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java){entryMinor("1−2")}
        val bounds=BooksApi.monthBounds("2026-11")
        org.junit.Assert.assertEquals(java.time.Instant.parse("2026-10-31T16:00:00Z").epochSecond,bounds.first)
        org.junit.Assert.assertEquals(java.time.Instant.parse("2026-11-30T15:59:59Z").epochSecond,bounds.second)
    }
    @Test fun dayPaginationAndStatisticsUseTheSameShanghaiBoundary() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val api=BooksApi("http://127.0.0.1:${server.port}"){"synthetic-session"}
            server.enqueue(MockResponse().setBody("{\"success\":true,\"result\":{\"items\":[]}}"))
            api.transactions(2,"2026-11","2026-11-01")
            val request=server.takeRequest();val start=java.time.Instant.parse("2026-10-31T16:00:00Z").epochSecond;val end=java.time.Instant.parse("2026-11-01T15:59:59Z").epochSecond
            assertEquals((start*1000).toString(),request.requestUrl!!.queryParameter("min_time"));assertEquals((end*1000+999).toString(),request.requestUrl!!.queryParameter("max_time"));assertEquals("2",request.requestUrl!!.queryParameter("page"));assertEquals("30",request.requestUrl!!.queryParameter("count"));assertNull(request.requestUrl!!.queryParameter("trim_account"))
            server.enqueue(MockResponse().setBody("{\"success\":true,\"result\":[]}"));api.dailyRange(start,end)
            val statistics=server.takeRequest();assertEquals(start.toString(),statistics.requestUrl!!.queryParameter("start_time"));assertEquals(end.toString(),statistics.requestUrl!!.queryParameter("end_time"));assertEquals("false",statistics.requestUrl!!.queryParameter("use_transaction_timezone"));assertEquals("Asia/Shanghai",statistics.getHeader("X-Timezone-Name"))
        }finally{server.shutdown()}
    }
}
