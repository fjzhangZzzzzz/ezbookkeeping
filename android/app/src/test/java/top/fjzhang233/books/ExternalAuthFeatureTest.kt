package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ExternalAuthFeatureTest {
    private suspend fun response(http:Int,body:String,action:suspend (BooksApi)->Unit){MockWebServer().use{server->server.start(java.net.InetAddress.getByName("127.0.0.1"),0);server.enqueue(MockResponse().setResponseCode(http).setBody(body));action(BooksApi(server.url("/").toString().trimEnd('/')){"synthetic-session"})}}
    @Test fun actualBackendApiNotFoundCode100001MeansDisabledOnlyAtHttp404()=runBlocking<Unit>{response(404,"""{"success":false,"errorCode":100001}"""){assertFalse(it.externalAuthLinked())}}
    @Test fun apiNotFoundCodeCannotMaskHttpServerFailure()=runBlocking<Unit>{response(503,"""{"success":false,"errorCode":100001}"""){api->val error=runCatching{api.externalAuthLinked()}.exceptionOrNull();assertTrue(error is ApiFailure&&error.httpStatus==503&&error.code==100001)}}
    @Test fun onlyActualRoute404MeansDisabled()=runBlocking<Unit>{response(404,"404 page not found"){assertFalse(it.externalAuthLinked())}}
    @Test fun server500With404ApplicationCodeIsNotDisabled()=runBlocking<Unit>{response(503,"""{"success":false,"errorCode":404}"""){api->val error=runCatching{api.externalAuthLinked()}.exceptionOrNull();assertTrue(error is ApiFailure&&error.httpStatus==503)}}
    @Test fun authorizationErrorIsNotDisabledEvenWhenHttp404()=runBlocking<Unit>{response(404,"""{"success":false,"errorCode":202002}"""){api->val error=runCatching{api.externalAuthLinked()}.exceptionOrNull();assertTrue(error is ApiFailure&&error.code==202002)}}
    @Test fun currentSession401StillExpiresSession()=runBlocking<Unit>{response(401,"""{"success":false,"errorCode":202002}"""){api->val error=runCatching{api.externalAuthLinked()}.exceptionOrNull();assertTrue(error is ApiFailure&&error.sessionExpired)}}
    @Test fun normalResponsePreservesActualLinkedState()=runBlocking<Unit>{response(200,"""{"success":true,"result":[{"externalAuthType":"oidc","linked":true}]}"""){assertTrue(it.externalAuthLinked())};response(200,"""{"success":true,"result":[]}"""){assertFalse(it.externalAuthLinked())}}
    @Test fun transportFailureIsNeverTreatedAsDisabled()=runBlocking<Unit>{
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val api=BooksApi(server.url("/").toString().trimEnd('/')){"synthetic-session"};server.shutdown()
        assertTrue(runCatching{api.externalAuthLinked()}.exceptionOrNull() is java.io.IOException)
    }

}
