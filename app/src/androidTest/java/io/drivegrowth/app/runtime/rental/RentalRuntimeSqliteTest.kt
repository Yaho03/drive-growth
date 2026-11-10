package io.drivegrowth.app.runtime.rental

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 실제 SQLite·고정 SIMULATION 입력. 제품 고객/실차·프로세스 종료 시험이 아니다. */
@Suppress("DEPRECATION")
class RentalRuntimeSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private val source=InputSource.SIMULATION
    private val car=OwnedVehicle(OwnedVehicleId("runtime-car"),VehicleId("v"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
    private val catalog=VehicleCatalogSnapshot(ContentVersion("runtime-fixture"),listOf(VehicleDefinition(car.vehicleId,"Vehicle")))
    private var now=0L
    private fun open()=SqliteGameChangeStore(context,source,name)
    private fun runtime()=SqliteRentalRuntimeSource(source,::open,{clock(now)})
    private fun clock(t:Long)=RentalClock("test-boot",EpochMillis(t),t)
    private fun <T> ok(r:ContractResult<T>):T { assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value }
    private fun visit(id:String="visit",roll:Int=99,rounding:RoundingMode?=RoundingMode.DOWN)=RentalVisit(source,RentalRequest(RequestId(id),car.vehicleId,1),catalog,
        mapOf(car.vehicleId to RentalCategory.SMALL),mapOf(car.vehicleId to GameMoney(100)),
        RentalTerms(RuleVersion("runtime-r1"),SimulatedCustomer(30,36),roll,EpochMillis(0),15,GameMoney(0),rounding))
    private fun tick(from:Long,to:Long,parked:Long?=null):ContractResult<RentalRuntimeSnapshot>{
        now=to;return runtime().advance(RentalTick(source,clock(from),clock(to),Observation.Available(parked?.let{RentalMotion.Parked(EpochMillis(it))}?:RentalMotion.Driving)))
    }
    override fun setUp(){super.setUp();name="rental-runtime-"+System.nanoTime()+".db";now=0
        open().use{ok(it.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,source,RuleVersion("r"),MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)),VehicleWrite.Add(car))))}}
    override fun tearDown(){context.deleteDatabase(name);super.tearDown()}
    fun testActualVisitAccountSettleReturnReopenAndFirstGiftCause(){
        val original=visit();assertEquals(car.ownedVehicleId,ok(runtime().visit(ContractResult.Success(original))))
        ok(tick(0,3600000));val first=ok(runtime().read()).firstCompleted!!
        assertEquals(RequestId("visit"),first.rentalId);assertEquals(GameMoney(1120),open().use{ok(it.balance())})
        assertEquals(first,ok(runtime().read()).firstCompleted)
        assertEquals(car.ownedVehicleId,ok(runtime().visit(ContractResult.Success(original))))
        ok(tick(3600000,4500000));open().use{assertEquals(VehicleActivity.IDLE,ok(it.rental.activity(car.ownedVehicleId)));assertEquals(1,ok(it.listUnviewedSettlements()).count{s->s.transaction.kind==TransactionKind.RENTAL})}
        assertEquals(first,ok(runtime().read()).firstCompleted)
    }
    fun testOriginalParkingSincePersistsAcrossNewRuntimeAndDuplicateTick(){
        ok(runtime().visit(ContractResult.Success(visit())))
        ok(tick(0,1800000,0));assertEquals(EpochMillis(0),ok(runtime().read()).pending.single().parkedSince)
        ok(tick(1800000,3600000,0));val first=ok(runtime().read()).firstCompleted!!
        assertEquals(GameMoney(78),first.receipt.settlement.transaction.money!!.amount)
        ok(tick(1800000,3600000,0));assertEquals(first,ok(runtime().read()).firstCompleted)
    }
    fun testUnobservedGapAndNewParkingBaselineNeverPay(){
        ok(runtime().visit(ContractResult.Success(visit())));ok(tick(0,1000,0));now=3600000
        val missing=runtime().advance(RentalTick(source,clock(1000),clock(now),Observation.Missing(MissingReason.NO_DATA)))
        assertEquals(ContractError.DATA_UNAVAILABLE,ok(missing).deferred[RequestId("visit")])
        assertEquals(1000L,ok(runtime().read()).pending.single().cursor.value)
        assertEquals(ContractError.STATE_CONFLICT,ok(tick(1000,2000,1000)).deferred[RequestId("visit")])
        assertNull(ok(runtime().read()).firstCompleted);open().use{assertEquals(GameMoney(1000),ok(it.balance()))}
    }
    fun testEmptyAssignmentAndChangedWholeVisitReplaysWithoutNewVehicle(){
        open().use{ok(it.rental.setResting(car.ownedVehicleId,true))};val c=visit();assertNull(ok(runtime().visit(ContractResult.Success(c))))
        open().use{ok(it.rental.setResting(car.ownedVehicleId,false))};assertNull(ok(runtime().visit(ContractResult.Success(c))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),runtime().visit(ContractResult.Success(visit(roll=0))))
        assertTrue(ok(runtime().read()).pending.isEmpty())
    }
    fun testSettleFailureKeepsAccountedCursorAndRetriesOriginalRental(){
        ok(runtime().visit(ContractResult.Success(visit())))
        val failing=SqliteRentalRuntimeSource(source,{open().also{store->var commits=0;store.beforeCommit={commits++;if(commits==3)throw SQLiteException("settlement commit")}}},{clock(now)})
        now=3600000;assertEquals(ContractError.STORAGE_FAILURE,ok(failing.advance(RentalTick(source,clock(0),clock(now),Observation.Available(RentalMotion.Driving)))).deferred[RequestId("visit")])
        assertEquals(3600000L,ok(runtime().read()).pending.single().cursor.value)
        open().use{assertEquals(GameMoney(1000),ok(it.balance()))};ok(tick(0,3600000));assertNotNull(ok(runtime().read()).firstCompleted)
    }
    fun testPendingRejectsRawFractionalParkingTimeWithoutChangingBytes(){
        ok(runtime().visit(ContractResult.Success(visit())));ok(tick(0,1000,0))
        val file=context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE rentals SET parked_since=0.5")}
        val bytes=file.readBytes();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),runtime().read());assertTrue(bytes.contentEquals(file.readBytes()))
    }
    fun testTwoSourcesSameVisitAndTickChargeOnlyOnce(){
        val pool=Executors.newFixedThreadPool(2)
        fun race(action:()->ContractResult<*>):List<ContractResult<*>> {val gate=CountDownLatch(1);val jobs=(1..2).map{pool.submit(Callable{gate.await();action()})};gate.countDown();return jobs.map{it.get(10,TimeUnit.SECONDS)}}
        try {val c=visit();race{runtime().visit(ContractResult.Success(c))}.forEach{ok(it)};now=3600000
            race{runtime().advance(RentalTick(source,clock(0),clock(now),Observation.Available(RentalMotion.Driving)))}.forEach{ok(it)}
            open().use{assertEquals(GameMoney(1120),ok(it.balance()));assertEquals(1,ok(it.listUnviewedSettlements()).count{s->s.transaction.kind==TransactionKind.RENTAL})}
        }finally{pool.shutdownNow()}
    }
    fun testMissingRoundingKeepsConfirmedTimeWithoutMakingUpMoneyPolicy(){
        ok(runtime().visit(ContractResult.Success(visit(rounding=null))))
        ok(tick(0,1000));val result=ok(tick(1000,3600000,1000))
        assertEquals(ContractError.POLICY_UNRESOLVED,result.deferred[RequestId("visit")]);assertNull(result.firstCompleted)
        open().use{assertEquals(GameMoney(1000),ok(it.balance()))}
    }
    fun testAccidentReturnIsRepairNeededAndOriginalHistoryRemains(){
        ok(runtime().visit(ContractResult.Success(visit(roll=0))));ok(tick(0,3600000));val original=ok(runtime().read()).firstCompleted!!
        assertTrue(original.receipt.accident);ok(tick(3600000,4500000))
        open().use{assertEquals(VehicleActivity.NEEDS_REPAIR,ok(it.rental.activity(car.ownedVehicleId)));assertEquals(1,ok(it.rental.accidentCount(car.ownedVehicleId)))}
        assertEquals(original,ok(runtime().read()).firstCompleted)
    }

}
