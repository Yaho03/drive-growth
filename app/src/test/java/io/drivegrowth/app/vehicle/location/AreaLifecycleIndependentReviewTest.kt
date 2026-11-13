package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.listener.*
import ai.pleos.playground.location.fused.model.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.RestPlace
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** 추가 소유권/스레드 반례. 외부 경계·SDK 대체 fixture이며 currentinside/실차 근거가 아니다. */
class AreaLifecycleIndependentReviewTest {
    private fun area()=PleosAreaDefinition("review-fixture",RestPlace.SERVICE_AREA,ContentVersion("review-v1"),
        "https://example.test/fixture","fixture-only","2026-11-10",37.0,127.0,25f)
    private class Client: LocationSdkClient,LocationAreaSdkClient {
        lateinit var proximity:ProximityAlertListener
        var failRelease=false;val removed=mutableListOf<String>();var releases=0
        override fun initialize()=Unit
        override fun isSupported()=true
        override fun register(listener:FusedLocationListener)=Unit
        override fun unregister(listener:FusedLocationListener)=Unit
        override fun addArea(request:ProximityAlertRequest,listener:ProximityAlertListener){proximity=listener}
        override fun removeArea(alertId:String){removed+=alertId}
        override fun release(){releases++;if(failRelease)throw IllegalStateException("explicit fixture release")}
    }
    @Test fun callbackThreadCapturesReceiptAndOnlyOwnerQueueMutatesArea() {
        val ownerThread=Thread.currentThread();val client=Client();val jobs=ConcurrentLinkedQueue<() -> Unit>()
        val values=mutableListOf<PleosAreaObservation>();val deliveredThreads=mutableListOf<Thread>();val clockThreads=mutableListOf<Thread>()
        var elapsed=100L;var utc=1000L
        val owner=ForegroundPleosLocation({client},{true},{jobs.add(it);Unit},{check(Thread.currentThread()===ownerThread)},
            {clockThreads+=Thread.currentThread();elapsed},{utc},{})
        fun drain(){while(true)(jobs.poll()?:return).invoke()}
        owner.start(area()){values+=it;deliveredThreads+=Thread.currentThread()};drain();values.clear();clockThreads.clear();deliveredThreads.clear()
        val callbackThread=Thread{
            client.proximity.onRegistered("id");client.proximity.onTransition(ProximityAlertEvent("id",ProximityTransition.ENTER))
        }.also{it.start();it.join()}
        assertTrue(values.isEmpty());assertEquals(2,clockThreads.size);assertTrue(clockThreads.all{it===callbackThread})
        elapsed=999;utc=9999;drain()
        assertEquals(2,values.size);assertTrue(deliveredThreads.all{it===ownerThread})
        assertEquals(Observation.Available(PleosAreaReceipt(100,EpochMillis(1000))),values.last().receipt)
        assertTrue(values.last().occurredAt is Observation.Missing);assertTrue(values.last().initialInside is Observation.Missing)
        owner.close()
    }
    @Test fun failedCloseDropsLateAreaEventsAndRetainsSameIdForCleanupRetry() {
        var client=Client();var creates=0;val jobs=ArrayDeque<() -> Unit>();val values=mutableListOf<PleosAreaObservation>()
        val owner=ForegroundPleosLocation({creates++;client},{true},{jobs+=it},{},{1},{2},{})
        fun drain(){while(jobs.isNotEmpty())jobs.removeFirst().invoke()}
        owner.start(area(),values::add);drain();val old=client
        old.proximity.onRegistered("original-id");drain();values.clear()
        old.proximity.onTransition(ProximityAlertEvent("original-id",ProximityTransition.ENTER))
        old.failRelease=true;assertThrows(IllegalStateException::class.java){owner.close()}
        old.proximity.onRegistered("late-id");old.proximity.onTransition(ProximityAlertEvent("original-id",ProximityTransition.EXIT));drain()
        assertTrue(values.isEmpty());client=Client();assertThrows(IllegalStateException::class.java){owner.start(area(),values::add)}
        assertEquals(1,creates);assertEquals(listOf("original-id","original-id"),old.removed)
        old.failRelease=false;owner.start(area(),values::add);drain()
        assertEquals(2,creates);assertEquals(3,old.releases);assertEquals(3,old.removed.count{it=="original-id"})
        assertEquals(1,values.size);assertTrue(values.single().transition is Observation.Missing);owner.close()
    }
}
