package top.fjzhang233.books

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EntityWriteStateTest {
    private val origin="http://127.0.0.1:18096"
    private val path="/api/v1/accounts/add.json"
    private fun body()=JSONObject().put("name","原账户").put("balance","1").put("currency","CNY").put("clientSessionId","synthetic-original-request")
    private class Memory:TransactionRequestStorage {
        var value:String?=null;var failRead=false;var failWrite=false;var failClear=false
        override fun read():String?{if(failRead)throw IOException("synthetic read failure");return value}
        override fun write(value:String){if(failWrite)throw IOException("synthetic write failure");this.value=value}
        override fun clear(){if(failClear)throw IOException("synthetic clear failure");value=null}
    }
    private suspend fun rejected(block:suspend ()->Unit){var failed=false;try{block()}catch(_:Exception){failed=true};assertTrue(failed)}
    @Test fun unknownFreezesOriginalAndNeverResendsEvenWhenEditorChanges()=runBlocking<Unit>{
        val store=Memory();var sends=0;val input=body();val state=EntityWriteState(origin,store,{"1"},{_,_->sends++;throw IOException("synthetic disconnect")})
        rejected{state.submit(path,input)};val saved=store.value;input.put("name","新账户不得提交").put("balance","999")
        assertTrue(state.blocked);assertFalse(state.inFlight);assertEquals("原账户",state.view()!!.body.getString("name"));assertEquals("1",state.view()!!.body.getString("balance"))
        rejected{state.submit(path,input)};rejected{state.requireNewWrite()};state.refresh();state.refresh()
        assertEquals(1,sends);assertEquals(saved,store.value);assertEquals("原账户",state.view()!!.body.getString("name"))
    }
    @Test fun repeatedIdentityFailuresKeepLockAndHideOldBody()=runBlocking<Unit>{
        val store=Memory();var offline=false;var sends=0
        val state=EntityWriteState(origin,store,{if(offline)throw IOException("synthetic network");"1"},{_,_->sends++;throw IOException("synthetic disconnect")})
        rejected{state.submit(path,body())};val saved=store.value;offline=true
        repeat(2){rejected{state.refresh()};assertTrue(state.blocked);assertNull(state.view());assertEquals(saved,store.value);rejected{state.requireNewWrite()}}
        offline=false;state.refresh();assertNotNull(state.view());assertEquals(1,sends)
    }
    @Test fun newProcessRestoresOnlyAfterOriginalUidVerifiedAndNeverPosts()=runBlocking<Unit>{
        val store=Memory();var uid="1";var sends=0
        rejected{EntityWriteState(origin,store,{uid},{_,_->sends++;throw IOException("synthetic unknown")}).submit(path,body())}
        val saved=store.value;uid="2";val restored=EntityWriteState(origin,store,{uid},{_,_->sends++;JSONObject().put("id","8")})
        assertTrue(restored.blocked);assertNull(restored.view());rejected{restored.refresh()};rejected{restored.abandonAfterExplicitReview()};assertEquals(saved,store.value);assertNull(restored.view())
        uid="1";restored.refresh();assertEquals("原账户",restored.view()!!.body.getString("name"));assertEquals(1,sends)
        restored.detachForLogout();assertNull(restored.view());assertTrue(restored.blocked);assertEquals(saved,store.value)
        restored.abandonAfterExplicitReview();assertFalse(restored.blocked);assertNull(store.value);assertEquals(1,sends)
    }
    @Test fun persistenceFailurePreventsHttpAndReadFailureIsNotEmpty()=runBlocking<Unit>{
        val store=Memory();var sends=0;val state=EntityWriteState(origin,store,{"1"},{_,_->sends++;true});store.failWrite=true
        rejected{state.submit(path,body())};assertEquals(0,sends);assertTrue(state.blocked)
        val unreadable=Memory().apply{failRead=true};val restored=EntityWriteState(origin,unreadable,{"1"},{_,_->sends++;true})
        assertTrue(restored.blocked);rejected{restored.refresh()};rejected{restored.submit(path,body())};assertEquals(0,sends)
    }
    @Test fun acknowledgementMustClearDurablyBeforeUnlockAndDoesNotRetry()=runBlocking<Unit>{
        val store=Memory();var sends=0;val state=EntityWriteState(origin,store,{"1"},{_,_->sends++;JSONObject().put("id","8")});store.failClear=true
        rejected{state.submit(path,body())};assertTrue(state.blocked);assertNotNull(store.value);rejected{state.submit(path,body())};assertEquals(1,sends)
        store.failClear=false;state.abandonAfterExplicitReview();assertFalse(state.blocked);assertNull(store.value);assertEquals(1,sends)
    }
    @Test fun busyProtectsOnePayloadAndSuccessfulAckAllowsOnlyFreshExplicitWrite()=runBlocking<Unit>{
        val store=Memory();val gate=CompletableDeferred<Unit>();val identityGate=CompletableDeferred<Unit>();var sends=0;var wire=""
        val state=EntityWriteState(origin,store,{identityGate.await();"1"},{_,frozen->sends++;wire=frozen.toString();gate.await();JSONObject().put("id","9")})
        val input=body();val first=launch{state.submit(path,input)};yield();assertTrue(state.inFlight);input.put("name","禁止改写")
        rejected{state.submit(path,input)};identityGate.complete(Unit);yield();rejected{state.abandonAfterExplicitReview()};assertEquals("原账户",JSONObject(wire).getString("name"))
        gate.complete(Unit);first.join();assertFalse(state.blocked);assertNull(store.value);assertEquals(1,sends)
    }
    @Test fun unexpectedOriginMalformedStoreAndUnlistedRouteFailClosed()=runBlocking<Unit>{
        val store=Memory();rejected{EntityWriteState(origin,store,{"1"},{_,_->throw IOException("synthetic")}).submit(path,body())}
        val other=EntityWriteState("https://different-origin.invalid",store,{"1"},{_,_->true});assertTrue(other.blocked);rejected{other.refresh()};assertNull(other.view())
        val empty=EntityWriteState(origin,Memory(),{"1"},{_,_->error("must not send")});rejected{empty.submit("/api/v1/transactions/add.json",body())}
    }
    @Test fun categoryConflictIsKnownRejectedButNetworkFailureRemainsPending()=runBlocking<Unit>{
        val path="/api/v1/transaction/categories/modify.json"
        val store=Memory();var conflict=true
        val state=EntityWriteState(origin,store,{"1"},{_,_->if(conflict)throw ApiFailure(206013,"conflict",httpStatus=409) else throw IOException("unknown")})
        rejected{state.submit(path,body())};assertFalse(state.blocked);assertNull(store.value)
        conflict=false;rejected{state.submit(path,body())};assertTrue(state.blocked);assertNotNull(store.value)
    }
    @Test fun referencedCategoryDeletionIsKnownRejected()=runBlocking<Unit>{
        val store=Memory();val state=EntityWriteState(origin,store,{"1"},{_,_->throw ApiFailure(206006,"in use",httpStatus=400)})
        rejected{state.submit("/api/v1/transaction/categories/delete.json",body())};assertFalse(state.blocked);assertNull(store.value)
    }
    @Test fun protectedCurrentTokenContractIsStrictAndCrossChecksAccountOwners(){
        fun token(id:String="1:1700000000:7",type:Int=1)=JSONObject().put("tokenId",id).put("tokenType",type).put("isCurrent",true)
        assertEquals("1",entityAuthenticatedUid(listOf(token()),emptyList(),emptyList()))
        val own=listOf(JSONObject().put("id","10"));val family=listOf(JSONObject().put("id","10").put("ownerId","1"))
        assertEquals("1",entityAuthenticatedUid(listOf(token()),own,family))
        for(tokens in listOf(emptyList(),listOf(token(),token()),listOf(token("opaque")),listOf(token(type=2)),listOf(token("9223372036854775808:1:1")),listOf(token().put("isCurrent","true")),listOf(token().put("tokenType","1")))){
            assertTrue(runCatching{entityAuthenticatedUid(tokens,own,family)}.isFailure)
        }
        assertTrue(runCatching{entityAuthenticatedUid(listOf(token()),own,listOf(JSONObject().put("id","10").put("ownerId","2")))}.isFailure)
    }
}
