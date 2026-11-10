package io.drivegrowth.app.runtime.rental

import io.drivegrowth.app.data.RentalLedger
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.math.RoundingMode

class RentalProcessorTest {
    private val id = RequestId("visit")
    private val car = OwnedVehicleId("car")
    private val vehicle = VehicleId("v")
    private fun clock(ms: Long, boot: String = "boot") = RentalClock(boot, EpochMillis(ms), ms)
    private fun tick(from: Long = 0, to: Long = 3600000, motion: Observation<RentalMotion> = Observation.Available(RentalMotion.Driving)) =
        RentalTick(InputSource.SIMULATION, clock(from), clock(to), motion)
    private class Ledger : RentalRuntimeLedger {
        val calls = mutableListOf<String>()
        var rows = listOf(RentalLedger.Pending(RequestId("visit"), OwnedVehicleId("car"), VehicleActivity.RENTED,
            EpochMillis(0), EpochMillis(3600000), EpochMillis(4500000)))
        var accountFailure: ContractError? = null
        var settleFailure: ContractError? = null
        var assigned: OwnedVehicleId? = OwnedVehicleId("car")
        var received: RentalVisit? = null
        override fun assign(visit: RentalVisit): ContractResult<OwnedVehicleId?> { calls += "assign"; received = visit; return ContractResult.Success(assigned) }
        override fun pending(): ContractResult<List<RentalLedger.Pending>> { calls += "pending"; return ContractResult.Success(rows) }
        override fun account(id: RequestId, from: EpochMillis, to: EpochMillis, parkedSince: EpochMillis?): ContractResult<EpochMillis> {
            calls += "account:${from.value}:${to.value}:${parkedSince?.value}"
            accountFailure?.let { return ContractResult.Failure(it) }
            val end = minOf(to.value, rows.single().end.value)
            rows = rows.map { it.copy(cursor = EpochMillis(end)) }; return ContractResult.Success(EpochMillis(end))
        }
        override fun settle(id: RequestId): ContractResult<RentalReceipt> { calls += "settle"; return ContractResult.Failure(settleFailure ?: ContractError.POLICY_UNRESOLVED) }
        override fun finishReturn(id: RequestId, now: EpochMillis): ContractResult<VehicleActivity> { calls += "return"; rows = emptyList(); return ContractResult.Success(VehicleActivity.IDLE) }
        override fun firstCompleted(): ContractResult<CompletedRental?> { calls += "completed"; return ContractResult.Success(null) }
    }
    private fun <T> ok(value: ContractResult<T>) = (value as ContractResult.Success).value
    @Test fun accountsConfirmedIntervalThenAttemptsSettlementAndRequeries() {
        val db = Ledger(); val result = ok(RentalProcessor(InputSource.SIMULATION,db).advance(tick(),clock(3600000)))
        assertTrue(db.calls.contains("account:0:3600000:null")); assertTrue(db.calls.contains("settle"))
        assertEquals(ContractError.POLICY_UNRESOLVED,result.deferred[id]); assertEquals(3600000L,result.pending.single().cursor.value)
        assertEquals(2,db.calls.count { it == "pending" })
    }
    @Test fun gapDoesNotBecomeDrivingOrSettlement() {
        val db=Ledger();val result=ok(RentalProcessor(InputSource.SIMULATION,db).advance(tick(motion=Observation.Missing(MissingReason.NO_DATA)),clock(3600000)))
        assertEquals(ContractError.DATA_UNAVAILABLE,result.deferred[id]);assertFalse(db.calls.any{it.startsWith("account")||it=="settle"})
    }
    @Test fun intervalStartingAfterCursorDefers() {
        val db=Ledger();val r=ok(RentalProcessor(InputSource.SIMULATION,db).advance(tick(100,200),clock(200)))
        assertEquals(ContractError.DATA_UNAVAILABLE,r.deferred[id]);assertFalse(db.calls.any{it.startsWith("account")})
    }
    @Test fun accountFailureNeverSettles() {
        val db=Ledger();db.accountFailure=ContractError.STORAGE_FAILURE
        val r=ok(RentalProcessor(InputSource.SIMULATION,db).advance(tick(),clock(3600000)))
        assertEquals(ContractError.STORAGE_FAILURE,r.deferred[id]);assertFalse(db.calls.contains("settle"))
    }
    @Test fun originalParkingTimeIsPassedUnchanged() {
        val db=Ledger(); db.rows=db.rows.map{it.copy(cursor=EpochMillis(1800000))}
        ok(RentalProcessor(InputSource.SIMULATION,db).advance(tick(1800000,2000000,Observation.Available(RentalMotion.Parked(EpochMillis(0)))),clock(2000000)))
        assertTrue(db.calls.contains("account:1800000:2000000:0"))
    }
    @Test fun returningWaitsForDeadlineThenFinishesWithoutMoney() {
        val db=Ledger();db.rows=db.rows.map{it.copy(phase=VehicleActivity.RETURNING,cursor=it.end)}
        val p=RentalProcessor(InputSource.SIMULATION,db)
        ok(p.advance(tick(4000000,4499999),clock(4499999)));assertFalse(db.calls.contains("return"))
        ok(p.advance(tick(4499999,4500000),clock(4500000)));assertTrue(db.calls.contains("return"));assertFalse(db.calls.contains("settle"));assertFalse(db.calls.any{it.startsWith("account")})
    }
    @Test fun invalidSourceFutureRollbackBootAndClockJumpRejectBeforeLedger() {
        for(t in listOf(tick().copy(source=InputSource.VEHICLE),tick(500,100),tick().copy(to=clock(3600000,"other")),
            tick().copy(to=RentalClock("boot",EpochMillis(3600001),3600000)),tick(to=3600001))) {
            val db=Ledger();assertTrue(RentalProcessor(InputSource.SIMULATION,db).advance(t,clock(3600000)) is ContractResult.Failure);assertTrue(db.calls.isEmpty())
        }
    }
    @Test fun alreadyAccountedDuplicateDoesNotAccountAgain() {
        val db=Ledger();db.rows=db.rows.map{it.copy(cursor=EpochMillis(100))}
        ok(RentalProcessor(InputSource.SIMULATION,db).advance(tick(0,100),clock(100)));assertFalse(db.calls.any{it.startsWith("account")})
    }
    @Test fun fixedVisitIsForwardedAndMapsAreCopied() {
        val db=Ledger();val categories=mutableMapOf(vehicle to RentalCategory.SMALL);val rates=mutableMapOf(vehicle to GameMoney(100))
        val v=RentalVisit(InputSource.SIMULATION,RentalRequest(id,vehicle,1),VehicleCatalogSnapshot(ContentVersion("c"),listOf(VehicleDefinition(vehicle,"V"))),categories,rates,
            RentalTerms(RuleVersion("r"),SimulatedCustomer(30,36),9,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN))
        categories.clear();rates.clear();val p=RentalProcessor(InputSource.SIMULATION,db)
        assertEquals(car,ok(p.visit(ContractResult.Success(v))));assertSame(v,db.received);assertEquals(GameMoney(100),v.hourlyRates[vehicle]);assertEquals(RentalCategory.SMALL,v.categories[vehicle])
        db.assigned=null;assertNull(ok(p.visit(ContractResult.Success(v))))
    }
    @Test fun missingDefinitionDoesNotAssign() {
        val db=Ledger();val p=RentalProcessor(InputSource.SIMULATION,db)
        for(e in listOf(ContractError.DATA_UNAVAILABLE,ContractError.POLICY_UNRESOLVED,ContractError.PERMISSION_DENIED))
            assertEquals(ContractResult.Failure(e),p.visit(ContractResult.Failure(e)))
        assertTrue(db.calls.isEmpty())
    }
    @Test fun persistedParkingBaselineCannotBeReplacedWithoutDrivingEvidence() {
        val db=Ledger();db.rows=db.rows.map{it.copy(cursor=EpochMillis(100),parkedSince=EpochMillis(0))}
        val r=ok(RentalProcessor(InputSource.SIMULATION,db).advance(tick(100,200,Observation.Available(RentalMotion.Parked(EpochMillis(100)))),clock(200)))
        assertEquals(ContractError.STATE_CONFLICT,r.deferred[id]);assertFalse(db.calls.any{it.startsWith("account")})
    }
    @Test fun negativeOrFutureParkAndWallClockChangeDuringWorkerAreRejected() {
        for(t in listOf(tick(0,100,Observation.Available(RentalMotion.Parked(EpochMillis(-1)))),
            tick(0,100,Observation.Available(RentalMotion.Parked(EpochMillis(1)))))) {
            val db=Ledger();assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),RentalProcessor(InputSource.SIMULATION,db).advance(t,clock(100)));assertTrue(db.calls.isEmpty())
        }
        val db=Ledger();assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),RentalProcessor(InputSource.SIMULATION,db).advance(tick(0,100),RentalClock("boot",EpochMillis(201),200)));assertTrue(db.calls.isEmpty())
    }

}
