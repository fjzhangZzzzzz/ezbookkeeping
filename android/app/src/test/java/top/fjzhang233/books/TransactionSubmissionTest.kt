package top.fjzhang233.books

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TransactionSubmissionTest {
    private class Memory:TransactionRequestStorage {var value:String?=null;override fun read()=value;override fun write(value:String){this.value=value};override fun clear(){value=null}}
    private class PendingIndex:PendingTransactionIndex {
        val values=linkedSetOf<String>()
        override fun hasAny()=values.isNotEmpty()
        override fun contains(uid:String)=uid in values
        override fun add(uid:String){values+=uid}
        override fun remove(uid:String){values-=uid}
    }
    private val origin="https://books.fjzhang233.top"
    private fun body()=JSONObject("""{"type":3,"sourceAccountId":"10","sourceAmount":1,"clientSessionId":"synthetic-original","pictureIds":["50"],"comment":"synthetic-original"}""")
    @Test fun durableOriginalSurvivesReconstructionAndCannotBeEditedOrCleanedBeforeAck()=runBlocking {
        val stores=mutableMapOf<String,Memory>();val writes=mutableListOf<String>();var fail=true
        val identity=SubmissionIdentity("1",setOf("10"))
        fun create()=TransactionSubmission(origin,{identity},{stores.getOrPut(it){Memory()}},{_,payload->check(stores.getValue("1").value!=null);writes+=payload.toString();if(fail)throw IOException("synthetic disconnect");JSONObject("{\"id\":\"100\"}")})
        var submission=create();val input=body();var failed=false
        try{submission.submit("/api/v1/transactions/add.json",input,false)}catch(_:IOException){failed=true};assertTrue(failed);assertTrue(submission.needsConfirmation)
        input.put("comment","must-not-send").put("pictureIds",org.json.JSONArray())
        var blocked=false;try{submission.requireEditable()}catch(_:IllegalArgumentException){blocked=true};assertTrue(blocked)
        submission=create();val restored=submission.restore();assertEquals("synthetic-original",restored!!.getString("comment"));assertTrue(submission.needsConfirmation)
        fail=false;submission.retry();assertEquals(writes[0],writes[1]);assertNull(stores.getValue("1").value);assertFalse(submission.needsConfirmation)
    }
    @Test fun differentUserNeverReadsOrDisplaysOriginalUsersRequest()=runBlocking {
        val stores=mutableMapOf<String,Memory>();val reads=mutableListOf<String>();var identity=SubmissionIdentity("1",setOf("10"));var sent=0
        fun create()=TransactionSubmission(origin,{identity},{uid->reads+=uid;stores.getOrPut(uid){Memory()}},{_,_->sent++;throw IOException("synthetic disconnect")})
        try{create().submit("/api/v1/transactions/add.json",body(),false)}catch(_:IOException){}
        val original=stores.getValue("1").value;reads.clear();identity=SubmissionIdentity("2",setOf("20"))
        val other=create();assertNull(other.restore());assertFalse(other.needsConfirmation);assertEquals(listOf("2"),reads);assertEquals(original,stores.getValue("1").value);assertEquals(1,sent)
    }
    @Test fun originMismatchRemovedAccountAndNetworkFailureAllPreserveOpaqueStoredRequest()=runBlocking {
        val store=Memory();var identity=SubmissionIdentity("1",setOf("10"));var networkFails=false;var sent=0
        fun create(base:String=origin)=TransactionSubmission(base,{if(networkFails)throw IOException("synthetic identity failure");identity},{store},{_,_->sent++;throw IOException("synthetic disconnect")})
        try{create().submit("/api/v1/transactions/add.json",body(),false)}catch(_:IOException){}
        val original=store.value
        for(mode in 0..2){
            identity=if(mode==1)SubmissionIdentity("1",emptySet()) else SubmissionIdentity("1",setOf("10"));networkFails=mode==2
            val restored=create(if(mode==0)"https://different-origin.invalid" else origin)
            var failed=false;try{restored.restore()}catch(_:Exception){failed=true}
            assertTrue(failed);assertFalse(restored.needsConfirmation);assertEquals(original,store.value);assertEquals(1,sent)
        }
    }
    @Test fun serverFailureAfterPossibleCommitRetainsOriginalForRetry()=runBlocking {
        for(error in listOf(ApiFailure(202000,"attachment token failure",httpStatus=500),ApiFailure(202000,"unproven rejection",httpStatus=400))){
            val store=Memory();val identity=SubmissionIdentity("1",setOf("10"));var sent=0;val payload=body().put("expectedCategoryRevision","7")
            val submission=TransactionSubmission(origin,{identity},{store},{_,_->sent++;throw error})
            try{submission.submit("/api/v1/transactions/add.json",payload,false)}catch(_:ApiFailure){}
            assertTrue(submission.needsConfirmation);assertNotNull(store.value);assertEquals(1,sent)
            val captured=JSONObject(JSONObject(checkNotNull(store.value)).getString("body"));assertEquals("synthetic-original",captured.getString("comment"));assertEquals("7",captured.getString("expectedCategoryRevision"))
            try{submission.requireEditable();fail("uncertain response must remain locked")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun initialCategoryConflictIsKnownRejected()=runBlocking {
        val store=Memory();val identity=SubmissionIdentity("1",setOf("10"))
        val submission=TransactionSubmission(origin,{identity},{store},{_,_->throw ApiFailure(206013,"conflict",httpStatus=409)})
        try{submission.submit("/api/v1/transactions/add.json",body(),false)}catch(_:ApiFailure){}
        assertFalse(submission.needsConfirmation);assertNull(store.value)
    }
    @Test fun retryCategoryConflictIsKnownRejectedAndUnfreezesOriginalDraft()=runBlocking {
        val store=Memory();val identity=SubmissionIdentity("1",setOf("10"));var attempts=0
        val submission=TransactionSubmission(origin,{identity},{store},{_,_->attempts++;if(attempts==1)throw IOException("unknown") else throw ApiFailure(206013,"conflict",httpStatus=409)})
        try{submission.submit("/api/v1/transactions/add.json",body().put("expectedCategoryRevision","7"),false)}catch(_:IOException){}
        assertTrue(submission.needsConfirmation);assertNotNull(store.value)
        try{submission.retry()}catch(_:ApiFailure){}
        assertFalse(submission.needsConfirmation);assertNull(store.value);assertEquals(2,attempts)
    }
    @Test fun identityRequiresUniqueGlobalAccountJoinAndOneOwner(){
        val own=listOf(JSONObject("{\"id\":\"10\"}"),JSONObject("{\"id\":\"11\"}"))
        val family=listOf(JSONObject("{\"id\":\"10\",\"ownerId\":\"1\"}"),JSONObject("{\"id\":\"11\",\"ownerId\":\"1\"}"),JSONObject("{\"id\":\"20\",\"ownerId\":\"2\"}"))
        assertEquals("1",submissionIdentity(own,family).uid)
        for(invalid in listOf(family.drop(1),family+family[0],family.toMutableList().also{it[1]=JSONObject("{\"id\":\"11\",\"ownerId\":\"2\"}")})){
            var rejected=false;try{submissionIdentity(own,invalid)}catch(_:IllegalArgumentException){rejected=true};assertTrue(rejected)
        }
    }
    @Test fun storageReadOrWriteFailureNeverSendsOrErasesRequest()=runBlocking {
        var sends=0;var clears=0
        val identity=SubmissionIdentity("1",setOf("10"))
        val brokenRead=object:TransactionRequestStorage{override fun read():String?{throw java.security.GeneralSecurityException("synthetic corrupt cipher")};override fun write(value:String){};override fun clear(){clears++}}
        var failed=false
        val reading=TransactionSubmission(origin,{identity},{brokenRead},{_,_->sends++;JSONObject("""{"id":"1"}""")})
        try{reading.restore()}catch(_:java.security.GeneralSecurityException){failed=true}
        assertTrue(failed);assertFalse(reading.needsConfirmation);assertEquals(0,sends);assertEquals(0,clears)
        val brokenWrite=object:TransactionRequestStorage{override fun read():String?=null;override fun write(value:String){throw java.io.IOException("synthetic write failure")};override fun clear(){clears++}}
        val writing=TransactionSubmission(origin,{identity},{brokenWrite},{_,_->sends++;JSONObject("""{"id":"1"}""")})
        failed=false;try{writing.submit("/api/v1/transactions/add.json",body(),false)}catch(_:java.io.IOException){failed=true}
        assertTrue(failed);assertEquals(0,sends);assertEquals(0,clears)
    }

    @Test fun prepareFallbackPublishesVerifiedOriginalContentNotNewEditorsValues()=runBlocking {
        val store=Memory();val identity=SubmissionIdentity("1",setOf("10"),mapOf("10" to SubmissionAccount("原账户","CNY")))
        var sends=0
        fun create()=TransactionSubmission(origin,{identity},{store},{_,_->sends++;throw IOException("synthetic disconnect")})
        try{create().submit("/api/v1/transactions/add.json",body(),false)}catch(_:IOException){}
        val newEditor=body().put("sourceAmount",999).put("comment","new-editor-must-not-be-confirmed")
        val restored=create();var blocked=false;try{restored.prepare(newEditor)}catch(_:IllegalArgumentException){blocked=true}
        assertTrue(blocked);assertTrue(restored.needsConfirmation);assertEquals(1,sends)
        val visible=restored.confirmationView()!!
        assertEquals("synthetic-original",visible.getString("comment"));assertEquals(1L,visible.getLong("sourceAmount"))
        assertEquals("原账户",visible.getJSONObject("sourceAccount").getString("name"));assertEquals("CNY",visible.getJSONObject("sourceAccount").getString("currency"))
        assertEquals(1,visible.getJSONArray("pictureIds").length());assertEquals(999L,newEditor.getLong("sourceAmount"))
        visible.put("sourceAmount",9999);assertEquals(1L,restored.confirmationView()!!.getLong("sourceAmount"))
    }

    @Test fun repeatedIdentityFailuresKeepPendingLockedAndHiddenUntilVerified()=runBlocking {
        val store=Memory();val originalIdentity=SubmissionIdentity("1",setOf("10"),mapOf("10" to SubmissionAccount("原账户","CNY")))
        var identity=originalIdentity;var offline=false;var sends=0
        val submission=TransactionSubmission(origin,{if(offline)throw IOException("synthetic offline");identity},{store},{_,_->sends++;throw IOException("synthetic disconnect")})
        try{submission.submit("/api/v1/transactions/add.json",body(),false)}catch(_:IOException){}
        val encrypted=store.value;assertTrue(submission.needsConfirmation)
        offline=true
        try{submission.retry()}catch(_:IOException){}
        repeat(2){
            try{submission.restore()}catch(_:IOException){}
            assertTrue(submission.needsConfirmation);assertNull(submission.confirmationView())
            var cleanupBlocked=false;try{submission.requireEditable()}catch(_:IllegalArgumentException){cleanupBlocked=true}
            assertTrue(cleanupBlocked);assertEquals(encrypted,store.value);assertEquals(1,sends)
        }
        offline=false;submission.restore()
        assertTrue(submission.needsConfirmation);assertNotNull(submission.confirmationView());assertEquals(encrypted,store.value)
        submission.detachForLogout();assertFalse(submission.needsConfirmation);assertNull(submission.confirmationView());assertEquals(encrypted,store.value)
    }
    @Test fun verifiedOtherUidWithoutPendingReleasesOldLockWithoutReadingOrShowingOldBody()=runBlocking {
        val original=Memory();val other=Memory();var identity=SubmissionIdentity("1",setOf("10"),mapOf("10" to SubmissionAccount("原账户","CNY")))
        val selected=mutableListOf<String>()
        val submission=TransactionSubmission(origin,{identity},{uid->selected+=uid;if(uid=="1")original else other},{_,_->throw IOException("synthetic disconnect")})
        try{submission.submit("/api/v1/transactions/add.json",body(),false)}catch(_:IOException){}
        val value=original.value
        identity=SubmissionIdentity("2",setOf("20"),mapOf("20" to SubmissionAccount("另一账户","CNY")));selected.clear()
        assertNull(submission.restore());assertEquals(listOf("2"),selected);assertNull(submission.confirmationView());assertFalse(submission.needsConfirmation)
        submission.requireEditable();assertEquals(value,original.value)
    }

    @Test fun emptyPendingIndexAvoidsIdentityNetworkCallOnNormalStartup()=runBlocking {
        val index=PendingIndex();var identified=0
        val submission=TransactionSubmission(origin,{identified++;SubmissionIdentity("1",setOf("10"))},{Memory()},{_,_->JSONObject() },index)
        assertNull(submission.restore());assertEquals(0,identified);assertFalse(submission.hasPendingCandidate())
    }

    @Test fun pendingForAnotherUserDoesNotReadOrDisplayOriginalBody()=runBlocking {
        val index=PendingIndex();val original=Memory();var identity=SubmissionIdentity("1",setOf("10"));var reads=0
        val submission=TransactionSubmission(origin,{identity},{uid->if(uid=="1")reads++;original},{_,_->throw IOException("synthetic disconnect")},index)
        try{submission.submit("/api/v1/transactions/add.json",body(),false)}catch(_:IOException){}
        assertTrue(index.contains("1"));identity=SubmissionIdentity("2",setOf("20"));reads=0
        assertNull(submission.restore());assertEquals(0,reads);assertTrue(index.contains("1"));assertFalse(submission.needsConfirmation)
        assertEquals("synthetic-original",JSONObject(JSONObject(checkNotNull(original.value)).getString("body")).getString("comment"))
    }

    @Test fun stalePendingIndexIsRemovedWhenEncryptedBodyIsMissing()=runBlocking {
        val index=PendingIndex();index.add("1");var identified=0
        val submission=TransactionSubmission(origin,{identified++;SubmissionIdentity("1",setOf("10"))},{Memory()},{_,_->JSONObject() },index)
        assertNull(submission.restore());assertEquals(1,identified);assertFalse(index.hasAny());assertFalse(submission.needsConfirmation)
    }

    @Test fun preparedIdentityIsReusedForTheSameSaveAndClearedOnDetach()=runBlocking {
        val index=PendingIndex();val store=Memory();var identified=0
        val submission=TransactionSubmission(origin,{identified++;SubmissionIdentity("1",setOf("10"))},{store},{_,_->JSONObject("{\"id\":\"100\"}")},index)
        submission.prepare(body());assertEquals(1,identified);submission.submit("/api/v1/transactions/add.json",body(),false);assertEquals(1,identified)
        submission.detachForLogout();submission.prepare(body());assertEquals(2,identified)
    }

}
