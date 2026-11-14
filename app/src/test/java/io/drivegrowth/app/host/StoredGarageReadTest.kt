package io.drivegrowth.app.host

import io.drivegrowth.app.ui.garage.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.content.VehicleGameData
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.HeldVehicle
import io.drivegrowth.core.garage.*
import io.drivegrowth.core.progression.GachaRules
import io.drivegrowth.core.progression.VehicleActivity
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class StoredGarageReadTest {
    private val mapper=GarageHostMapper()
    private fun <T> ok(result:ContractResult<T>):T {
        assertTrue(result.toString(),result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    @Test fun storedReadMapsSeparateInstancesAndRealActivityAccidentsCapacityAndGamePrice() {
        val reads=GarageReadsFixture()
        val snapshot=ok(readStoredHostSnapshot(reads.content,InputSource.SIMULATION,reads))
        val shown=(mapper.garage(snapshot) as GarageScreenState.Content).data
        assertEquals(listOf(reads.first,reads.second),shown.vehicles.map{it.vehicle})
        assertEquals(listOf(VehicleActivity.RENTED,VehicleActivity.NEEDS_REPAIR),shown.vehicles.map{it.activity})
        assertEquals(listOf("1","0"),shown.vehicles.map{it.accidentLabel})
        assertTrue(shown.vehicles.all{it.modelPriceLabel=="8000 게임머니"})
        assertEquals("보유 2 / 5칸",shown.capacityLabel)
        assertTrue(shown.sourceLabel.contains("모의 원장"))
        assertTrue(shown.vehicles.all{v->v.actions.values.all{!it.enabled}})
    }
    @Test fun heldLookupPreservesRecordsAndKeepsEmptyAndFailureSeparate() {
        val fixture = GarageReadsFixture()
        val car = fixture.first.copy(ownedVehicleId = OwnedVehicleId("held-a"),
            acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY)
        val deadline = EpochMillis(86_400_000)
        val held = HeldVehicle(RequestId("held-a"), fixture.source, car, car.acquisitionCost, EpochMillis(0), deadline)
        val raw = mutableListOf(held, held.copy(requestId = RequestId("held-b"),
            vehicle = car.copy(ownedVehicleId = OwnedVehicleId("held-b"))))
        fun snapshot(result: ContractResult<List<HeldVehicle>>): HostSnapshot {
            val reads = object : StoredHostReads by fixture {
                override fun heldVehicles() = result
            }
            return ok(readStoredHostSnapshot(fixture.content, fixture.source, reads))
        }
        fun garage(value: HostSnapshot) = (mapper.garage(value) as GarageScreenState.Content).data

        val saved = snapshot(ContractResult.Success(raw))
        raw.clear()
        val shown = garage(saved)
        assertEquals(listOf("a", "b"), shown.vehicles.map { it.vehicle.ownedVehicleId.value })
        val rows = ok(shown.heldVehicles)
        assertEquals(listOf("held-a", "held-b"), rows.map { it.vehicle.ownedVehicleId.value })
        assertEquals(listOf("1번", "2번"), rows.map { it.instanceLabel })
        assertTrue(rows.all { it.modelName == "시험 차량" && it.deadline == deadline && it.vehicle.rarity == Rarity.RARE })
        assertThrows(UnsupportedOperationException::class.java) { (ok(saved.heldVehicles) as MutableList).clear() }
        assertTrue(ok(garage(snapshot(ContractResult.Success(emptyList()))).heldVehicles).isEmpty())
        val failure = ContractResult.Failure(ContractError.STORAGE_FAILURE)
        assertEquals(failure, garage(snapshot(failure)).heldVehicles)
        val foreign = held.copy(source = InputSource.VEHICLE)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            garage(snapshot(ContractResult.Success(listOf(held, foreign)))).heldVehicles)
    }

    @Test fun storedHistoryKeepsDisposedOwnershipAndExplicitDiscoveriesWithoutPromotingCurrentOwned() {
        val reads=GarageReadsFixture()
        val snapshot=ok(readStoredHostSnapshot(reads.content,InputSource.SIMULATION,reads))
        val view=mapper.collection(snapshot,snapshot.collectionHistory) as CollectionScreenState.Content
        assertEquals(CollectionState.OWNED,view.entries.single{it.entry.key==CollectionKey(reads.model,Rarity.LEGEND)}.entry.state)
        assertEquals(CollectionState.DISCOVERED,view.entries.single{it.entry.key==CollectionKey(reads.model,Rarity.ADVANCED)}.entry.state)
    }
    @Test fun activityFailureAndAccidentMissingStayUnknownAndKeepTheirErrors() {
        val reads=GarageReadsFixture()
        reads.activityResults[reads.first.ownedVehicleId]=ContractResult.Failure(ContractError.PERMISSION_DENIED)
        reads.accidentResults[reads.first.ownedVehicleId]=ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val snapshot=ok(readStoredHostSnapshot(reads.content,InputSource.SIMULATION,reads))
        val shown=(mapper.garage(snapshot) as GarageScreenState.Content).data.vehicles.first()
        assertNull(shown.activity);assertNull(shown.accidentLabel)
        assertTrue(checkNotNull(shown.informationNote).contains("PERMISSION_DENIED"))
        assertTrue(checkNotNull(shown.informationNote).contains("DATA_UNAVAILABLE"))
        val details=ok(snapshot.garageState).vehicles.first()
        assertEquals(ContractResult.Failure(ContractError.PERMISSION_DENIED),details.activity)
    }
    @Test fun historyFailureDoesNotBecomeEmptySuccessfulCollection() {
        val reads=GarageReadsFixture();reads.historyFailure=ContractError.STORAGE_FAILURE
        val snapshot=ok(readStoredHostSnapshot(reads.content,InputSource.SIMULATION,reads))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),snapshot.collectionHistory)
        assertTrue(mapper.collection(snapshot,snapshot.collectionHistory) is CollectionScreenState.Error)
    }
    @Test fun wrongSourceAndRevisionChangedDuringSequentialQueriesAreRejected() {
        val wrong=GarageReadsFixture();wrong.source=InputSource.VEHICLE
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),readStoredHostSnapshot(wrong.content,InputSource.SIMULATION,wrong))
        val changed=GarageReadsFixture();changed.changeAtSecondOwnedRead=true
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),readStoredHostSnapshot(changed.content,InputSource.SIMULATION,changed))
    }
    @Test fun mapperRejectsForeignSourceVersionAndOldInstanceDetails() {
        val f=GarageReadsFixture()
        val row=StoredVehicleState(f.first,ContractResult.Success(VehicleActivity.IDLE),ContractResult.Success(0))
        fun snapshot(state:StoredGarageState)=HostSnapshot(f.content,InputSource.SIMULATION,GameMoney(0),listOf(f.first),emptyList(),garageState=ContractResult.Success(state))
        val states=listOf(
            StoredGarageState(InputSource.VEHICLE,f.content.catalog.contentVersion,f.content.ruleVersion,listOf(row),ContractResult.Success(5)),
            StoredGarageState(InputSource.SIMULATION,ContentVersion("other"),f.content.ruleVersion,listOf(row),ContractResult.Success(5)),
            StoredGarageState(InputSource.SIMULATION,f.content.catalog.contentVersion,f.content.ruleVersion,listOf(row.copy(vehicle=f.first.copy(revision=Revision(0)))),ContractResult.Success(5)))
        for(state in states) assertTrue(mapper.garage(snapshot(state)) is GarageScreenState.Error)
        val foreign=CollectionHistory(emptySet(),emptySet(),InputSource.VEHICLE,f.content.catalog.contentVersion,f.content.ruleVersion)
        assertTrue(mapper.collection(snapshot(states.last()),ContractResult.Success(foreign)) is CollectionScreenState.Error)
    }
    @Test fun corruptCapacityIsUnknownWithErrorAndNeverRenderedAsZero() {
        val fixture=GarageReadsFixture()
        val reads=object:StoredHostReads by fixture {override fun capacity()=ContractResult.Success(-1)}
        val snapshot=ok(readStoredHostSnapshot(fixture.content,InputSource.SIMULATION,reads))
        val shown=(mapper.garage(snapshot) as GarageScreenState.Content).data
        assertNull(shown.capacityLabel)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ok(snapshot.garageState).capacity)
        assertTrue(shown.vehicles.first().informationNote.orEmpty().contains("STORAGE_FAILURE"))
    }
    @Test fun missingCapacityAndModelPriceRemainUnknownWhileKnownZeroAccidentsAreShown() {
        val fixture=GarageReadsFixture()
        val reads=object:StoredHostReads by fixture {override fun capacity():ContractResult<Int> = ContractResult.Failure(ContractError.PERMISSION_DENIED)}
        val snapshot=ok(readStoredHostSnapshot(fixture.content.copy(vehicles=emptyList()),InputSource.SIMULATION,reads))
        val shown=(mapper.garage(snapshot) as GarageScreenState.Content).data
        assertNull(shown.capacityLabel)
        assertTrue(shown.vehicles.all{it.modelPriceLabel==null})
        assertEquals("0",shown.vehicles.last().accidentLabel)
        assertTrue(checkNotNull(shown.vehicles.first().informationNote).contains("PERMISSION_DENIED"))
    }
    @Test fun storedResultCollectionsCannotBeChangedAfterWorkerDelivery() {
        val fixture=GarageReadsFixture();val snapshot=ok(readStoredHostSnapshot(fixture.content,InputSource.SIMULATION,fixture))
        assertThrows(UnsupportedOperationException::class.java){(snapshot.ownedVehicles as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java){(ok(snapshot.garageState).vehicles as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java){(ok(snapshot.collectionHistory).everOwned as MutableSet).clear()}
    }
    @Test fun queryGenerationDropsOldStoredDetailsAndHiddenResultWithoutReopening() {
        class Tasks:Executor {
            val work=ArrayDeque<Runnable>()
            override fun execute(command:Runnable){work.addLast(command)}
            fun runNext(){work.removeFirst().run()}
        }
        val fixture=GarageReadsFixture();val worker=Tasks();val main=Tasks();val seen=mutableListOf<HostQueryState>()
        val host=HostQueryController(worker,main,{readStoredHostSnapshot(fixture.content,InputSource.SIMULATION,fixture)},seen::add)
        host.setControlsEnabled(true);worker.runNext();host.refresh();main.runNext()
        assertFalse(seen.any{it is HostQueryState.Content})
        fixture.activityResults[fixture.first.ownedVehicleId]=ContractResult.Success(VehicleActivity.IDLE)
        worker.runNext();main.runNext()
        val snapshot=(seen.last() as HostQueryState.Content).snapshot
        assertEquals(VehicleActivity.IDLE,(mapper.garage(snapshot) as GarageScreenState.Content).data.vehicles.first().activity)
        host.refresh();worker.runNext();host.setControlsEnabled(false);main.runNext()
        assertEquals(HostQueryState.Restricted,seen.last());assertEquals(1,seen.count{it is HostQueryState.Content})
        host.release();assertTrue(worker.work.isEmpty())
    }

    @Test fun negativeAccidentCountIsAStoredErrorAndNotASuccessfulNumber() {
        val fixture=GarageReadsFixture()
        fixture.accidentResults[fixture.first.ownedVehicleId]=ContractResult.Success(-1)
        val snapshot=ok(readStoredHostSnapshot(fixture.content,InputSource.SIMULATION,fixture))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ok(snapshot.garageState).vehicles.first().accidents)
        val shown=(mapper.garage(snapshot) as GarageScreenState.Content).data.vehicles.first()
        assertNull(shown.accidentLabel)
        assertTrue(shown.informationNote.orEmpty().contains("STORAGE_FAILURE"))
    }

}

/** 저장 계약만 공급하는 읽기 probe. 실제 SQLite 근거와 별도로 기록한다. */
internal class GarageReadsFixture:StoredHostReads {
    val model=VehicleId("fixture-model")
    val content=GameContentSnapshot(VehicleCatalogSnapshot(ContentVersion("fixture-content"),listOf(VehicleDefinition(model,"시험 차량"))),
        RuleVersion("fixture-rule"),true,listOf(VehicleGameData(model,GameMoney(8000),1)),GachaRules())
    val first=OwnedVehicle(OwnedVehicleId("a"),model,4,Revision(3),VehicleLifecycle.OWNED,Rarity.RARE,20,GameMoney(500),VehicleAcquisitionSource.GACHA)
    val second=first.copy(ownedVehicleId=OwnedVehicleId("b"),rarity=Rarity.COMMON,revision=Revision(8))
    val sold=first.copy(ownedVehicleId=OwnedVehicleId("sold"),rarity=Rarity.LEGEND,lifecycle=VehicleLifecycle.SOLD)
    override var source=InputSource.SIMULATION
    var historyFailure:ContractError?=null
    var changeAtSecondOwnedRead=false
    private var ownedReads=0
    val activityResults=mutableMapOf(first.ownedVehicleId to ContractResult.Success(VehicleActivity.RENTED) as ContractResult<VehicleActivity>,
        second.ownedVehicleId to ContractResult.Success(VehicleActivity.NEEDS_REPAIR))
    val accidentResults=mutableMapOf(first.ownedVehicleId to ContractResult.Success(1) as ContractResult<Int>,second.ownedVehicleId to ContractResult.Success(0))
    override fun balance()=ContractResult.Success(GameMoney(700))
    override fun owned():ContractResult<List<OwnedVehicle>> {
        ownedReads++
        return ContractResult.Success(listOf(if(changeAtSecondOwnedRead&&ownedReads>1) first.copy(revision=Revision(4)) else first,second))
    }
    override fun settlements()=ContractResult.Success(emptyList<Settlement>())
    override fun activity(id:OwnedVehicleId)=activityResults.getValue(id)
    override fun accidents(id:OwnedVehicleId)=accidentResults.getValue(id)
    override fun heldVehicles() = ContractResult.Success(emptyList<HeldVehicle>())
    override fun capacity()=ContractResult.Success(5)
    override fun listVehicleHistory():ContractResult<List<OwnedVehicle>> = historyFailure?.let{ContractResult.Failure(it)}?:ContractResult.Success(listOf(first,second,sold))
    override fun discoveredVehicles()=ContractResult.Success(setOf(CollectionKey(model,Rarity.ADVANCED)))
    override fun recordDiscoveries(catalog:VehicleCatalogSnapshot,keys:Set<CollectionKey>):ContractResult<Unit> = error("조회 중 쓰기 금지")
}
