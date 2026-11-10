package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.ranking.*
import io.drivegrowth.core.content.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.rental.*
import java.time.*
import java.math.*

/** 실제 SQLite 읽기 전용 표적. 명시 SIMULATION/게임 fixture이며 실제 운전 기록이 아니다. */
@Suppress("DEPRECATION")
class RankingReadSourceTest:InstrumentationTestCase(){
    private val context get()=instrumentation.targetContext
    private val origin=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private lateinit var content:GameContentSnapshot
    private val vehicle=OwnedVehicle(OwnedVehicleId("initial"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
    private val paid=vehicle.copy(ownedVehicleId=OwnedVehicleId("paid"),rarity=Rarity.LEGEND,acquisitionCost=GameMoney(500),acquisitionSource=VehicleAcquisitionSource.GACHA)
    private val at=EpochMillis(Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())
    private val monday=LocalDate.parse("2026-11-09")
    private fun open()=SqliteGameChangeStore(context,origin,name)
    private fun reader(quest:((SQLiteDatabase)->ContractResult<Set<RequestId>>)?=null)=RankingReadSource(store,origin,content,questCauses=quest,departureZone={Observation.Available(ZoneId.of("Asia/Seoul"))})
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun amount(s:RankingSnapshot,m:RankingMetric)=(s.metrics.getValue(m) as RankingValue.Available).amount
    private fun snapshot()=ok(reader().read(at))
    private fun add(id:String,car:OwnedVehicle,money:MoneyChange?=null)=ok(store.commitGameChange(GameChange(RequestId(id),TransactionKind.GARAGE,origin,content.ruleVersion,money,VehicleWrite.Add(car))))
    override fun setUp(){super.setUp();name="ranking-"+System.nanoTime()+".db";content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot;store=open()
        add("initial-grant",vehicle,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)));add("paid-car",paid)}
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    fun testCurrentOwnedHistoricalCollectionAndHighestUseDifferentFacts(){
        val before=snapshot();assertEquals(BigDecimal(2),amount(before,RankingMetric.OWNED));assertEquals(BigDecimal(2),amount(before,RankingMetric.COLLECTION));assertEquals(0,BigDecimal(1300).compareTo(amount(before,RankingMetric.HIGHEST_VALUE)))
        ok(store.recordDiscoveries(content.catalog,setOf(io.drivegrowth.core.garage.CollectionKey(vehicle.vehicleId,Rarity.RARE))))
        ok(store.commitGameChange(GameChange(RequestId("sale"),TransactionKind.GARAGE,origin,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(300)),VehicleWrite.Update(paid.ownedVehicleId,Revision(0),1,VehicleLifecycle.SOLD,0))))
        val after=snapshot();assertEquals(BigDecimal.ONE,amount(after,RankingMetric.OWNED));assertEquals(BigDecimal(2),amount(after,RankingMetric.COLLECTION));assertEquals(0,BigDecimal(1000).compareTo(amount(after,RankingMetric.HIGHEST_VALUE)));assertEquals(BigDecimal(300),amount(after,RankingMetric.EARNED))
    }
    fun testFullIncomeIncludesViewedDrivingRentalAndDisposalAndIgnoresSpendingInitial(){
        val drive=ok(store.driving.settle(DrivingIncomeRules(content.ruleVersion,500000,GameMoney(10)),ConfirmedDrive("trip",monday,ObservedDistance(1000,origin))))
        ok(store.markViewed(drive.settlement!!.settlementId))
        val rental=RequestId("rental");ok(store.rental.assign(RentalRequest(rental,vehicle.vehicleId,1),content.catalog,mapOf(vehicle.vehicleId to RentalCategory.SMALL),mapOf(vehicle.vehicleId to GameMoney(100)),RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)))
        ok(store.rental.account(rental,EpochMillis(0),EpochMillis(3600000),null));val rent=ok(store.rental.settle(rental));ok(store.markViewed(rent.settlement.settlementId))
        ok(store.commitGameChange(GameChange(RequestId("scrap"),TransactionKind.GARAGE,origin,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(25)),VehicleWrite.Update(paid.ownedVehicleId,Revision(0),1,VehicleLifecycle.SCRAPPED,0))))
        ok(store.commitGameChange(GameChange(RequestId("spend"),TransactionKind.GARAGE,origin,content.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(100)))))
        val s=snapshot();assertEquals(BigDecimal(155),amount(s,RankingMetric.EARNED));assertEquals(BigDecimal(1000),amount(s,RankingMetric.WEEKLY_DISTANCE))
    }
    fun testDailyCapAndWeekBoundaryUseRewardedDistanceNotRawTripDistance(){
        val rules=DrivingIncomeRules(content.ruleVersion,500000,GameMoney(1))
        ok(store.driving.settle(rules,ConfirmedDrive("over",monday,ObservedDistance(600000,origin))))
        ok(store.driving.settle(rules,ConfirmedDrive("again",monday,ObservedDistance(10000,origin))))
        ok(store.driving.settle(rules,ConfirmedDrive("prior",monday.minusDays(1),ObservedDistance(1000,origin))))
        assertEquals(BigDecimal(500000),amount(snapshot(),RankingMetric.WEEKLY_DISTANCE))
    }
    fun testUnknownCreditIsMissingUntilActualQuestCauseIsSupplied(){
        val id=RequestId("quest-real-cause")
        ok(store.commitGameChange(GameChange(id,TransactionKind.GARAGE,origin,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(70)))))
        assertTrue(snapshot().metrics[RankingMetric.EARNED] is RankingValue.Missing)
        val second=RequestId("quest-other-kind")
        ok(store.commitGameChange(GameChange(second,TransactionKind.FACILITY,origin,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(30)))))
        val s=ok(reader{ContractResult.Success(setOf(id,second))}.read(at));assertEquals(BigDecimal(100),amount(s,RankingMetric.EARNED))
        assertTrue(snapshot().metrics[RankingMetric.EARNED] is RankingValue.Missing)
    }
    fun testRawNumericDamageIsFailureAndReadDoesNotRepairOrWrite(){
        ok(store.driving.settle(DrivingIncomeRules(content.ruleVersion,500000,GameMoney(1)),ConfirmedDrive("d",monday,ObservedDistance(1000,origin))))
        store.close();val file=context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE driving_days SET rewarded=1.5")}
        val bytes=file.readBytes();store=open();assertTrue(snapshot().metrics[RankingMetric.WEEKLY_DISTANCE] is RankingValue.Failure);store.close();assertTrue(bytes.contentEquals(file.readBytes()));store=open()
    }
    fun testSuccessfulSnapshotAndReopenAreReadOnlyAndMissingPolicyDoesNotBecomeZero(){
        val first=snapshot();store.close();val file=context.getDatabasePath(name);val bytes=file.readBytes();store=open();assertEquals(first,ok(reader().read(at)));store.close();assertTrue(bytes.contentEquals(file.readBytes()));store=open()
        val s=ok(RankingReadSource(store,origin,content,RankingDefinitions()).read(at));assertTrue(s.metrics[RankingMetric.EARNED] is RankingValue.Missing);assertTrue(s.metrics[RankingMetric.WEEKLY_DISTANCE] is RankingValue.Missing);assertTrue(s.metrics[RankingMetric.COLLECTION] is RankingValue.Missing)
    }
    fun testPurchaseRefundIsExcludedAndCompletedIncomeIsIncludedAfterViewed(){
        val purchase=DeliveryOffer.Purchase(DeliveryOfferId("offer-0"),vehicle.vehicleId,Rarity.COMMON,GameMoney(100))
        val income=DeliveryOffer.Income(DeliveryOfferId("offer-1"),vehicle.vehicleId,GameMoney(40))
        val list=DeliveryList(1,origin,monday,content.catalog.contentVersion,content.ruleVersion,listOf(purchase,income),2)
        val proposed=DeliveryProposed(DestinationKey("destination"),EstimatedRouteDistance(10000,origin),list,list.offers)
        ok(store.delivery.saveList(list));ok(store.delivery.select(proposed,purchase.id,RequestId("purchase")))
        val refund=ok(store.delivery.abandon(DeliveryChangeRules(content.ruleVersion,10),RequestId("purchase")))
        ok(store.markViewed(refund.settlement!!.settlementId));assertTrue(snapshot().metrics[RankingMetric.EARNED] is RankingValue.Empty)
        ok(store.delivery.select(proposed,income.id,RequestId("income")));val route=RouteId("route")
        ok(store.delivery.beginRoute(RequestId("income"),route,EpochMillis(1000)))
        val result=ok(store.delivery.settleArrival(DeliverySettlementRules(content.ruleVersion,86400000,86400000,5),RequestId("income"),
            Observation.Available(DeliveryArrivalEvidence(route,origin,EpochMillis(2000))),EpochMillis(3000),{l,id->ContractResult.Success(l.copy(offers=l.offers.map{if(it.id==id)DeliveryOffer.Income(DeliveryOfferId("offer-2"),vehicle.vehicleId,GameMoney(50))else it},nextOfferNumber=3))}))
        ok(store.markViewed(result.settlement!!.settlementId));assertEquals(BigDecimal(40),amount(snapshot(),RankingMetric.EARNED))
    }

    fun testLegacyDateWithoutDepartureZoneIsMissingNotConvertedOrZero(){
        ok(store.driving.settle(DrivingIncomeRules(content.ruleVersion,500000,GameMoney(10)),ConfirmedDrive("legacy",monday,ObservedDistance(1000,origin))))
        val unproven=ok(RankingReadSource(store,origin,content).read(at))
        assertTrue(unproven.metrics[RankingMetric.WEEKLY_DISTANCE] is RankingValue.Missing)
        assertEquals(BigDecimal(10),amount(unproven,RankingMetric.EARNED))
    }

    fun testHistoricalRuleIsValidatedWithoutRepricingIncomeAndRankingStillCapsAt500Km(){
        ok(store.driving.settle(DrivingIncomeRules(content.ruleVersion,1000000,GameMoney(1)),ConfirmedDrive("old-rule",monday,ObservedDistance(600000,origin))))
        val s=snapshot();assertEquals(BigDecimal(600),amount(s,RankingMetric.EARNED));assertEquals(BigDecimal(500000),amount(s,RankingMetric.WEEKLY_DISTANCE))
        assertEquals(600000L,ok(store.driving.day(monday))!!.rewardedMeters)
    }

}
