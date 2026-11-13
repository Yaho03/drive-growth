package io.drivegrowth.app.host.delivery

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.garage.GaragePreparation
import io.drivegrowth.app.host.garage.StoredGarageCommandSource
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleAction
import java.time.LocalDate
import java.util.UUID

/** 실제 same-DB 연결 시험 준비. 기기 실행 전에는 컴파일 결과로만 기록한다. */
@Suppress("DEPRECATION")
class DeliveryHostQueriesSqliteTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var names:Map<InputSource,String>
    private lateinit var games:GameSessionFactory
    private lateinit var queries:FactoryDeliveryHostQueries
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return (value as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="delivery-host-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"};games=GameSessionFactory(context,names,InputSource.SIMULATION)
        val onboarding=StoredOnboardingHostSource(games);val first=ok(onboarding.read());ok(onboarding.choose(first.identity,FirstGrant.CANDIDATES.first()))
        // 활성 의뢰 없는 격리 시험만 사용한다. 제품 changed/paid 권위를 대체하는 callback이 아니다.
        queries=FactoryDeliveryHostQueries(games){ContractResult.Success(null)}
    }
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    fun testReadsActualSameProfileMoneyAndShopAndRejectsForeignIdentity(){
        val identity=ok(queries.identity());val state=ok(queries.read(identity));assertEquals(GameMoney(1000),state.balance);assertEquals(1,state.shopLevel);assertNull(state.terms)
        assertTrue(queries.read(identity.copy(profileId=ProfileId("other"))) is ContractResult.Failure)
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testSavedUnshownCandidatesDoNotDiscoverAndExplicitShownPurchaseKeysPersist(){
        val identity=ok(queries.identity());val state=ok(queries.read(identity));val vehicle=state.content.catalog.vehicles.first().vehicleId
        val list=DeliveryList(0,identity.source,LocalDate.of(2026,11,10),state.content.catalog.contentVersion,RuleVersion("delivery-test"),listOf(
            DeliveryOffer.Income(DeliveryOfferId("offer-0"),vehicle,GameMoney(5)),
            DeliveryOffer.Purchase(DeliveryOfferId("offer-1"),vehicle,Rarity.RARE,GameMoney(10))),2)
        ok(queries.saveList(identity,list));ok(games.open()).use{assertTrue(ok(it.store.discoveredVehicles()).isEmpty())}
        val proposed=DeliveryProposed(DestinationKey("shown"),EstimatedRouteDistance(1000,identity.source),list,list.offers)
        ok(queries.discover(identity,proposed));ok(games.open()).use{assertEquals(setOf(CollectionKey(vehicle,Rarity.RARE)),ok(it.store.discoveredVehicles()))}
        assertEquals(list,ok(queries.lists(identity,list.createdOn)).single())
    }

    fun testSaleOfOneSameModelVehicleUpdatesOwnershipRentalAndDeliverySlotsAfterReopen() {
        val deliveryQueries = FactoryDeliveryHostQueries(games) { it.terms() }
        val identity = ok(deliveryQueries.identity())
        val (kept, sold) = ok(games.open()).use { session ->
            val store = session.store
            val content = ok(session.content)
            val first = ok(store.listOwnedVehicles()).single()
            val duplicate = first.copy(
                ownedVehicleId = OwnedVehicleId("duplicate-for-sale"),
                rarity = Rarity.RARE,
                acquisitionCost = GameMoney(500),
                acquisitionSource = VehicleAcquisitionSource.GACHA,
            )
            ok(store.commitGameChange(GameChange(
                RequestId("add-duplicate-for-sale"), TransactionKind.GARAGE, identity.source, content.ruleVersion,
                vehicle = VehicleWrite.Add(duplicate),
            )))
            assertEquals(setOf(first, duplicate), ok(store.listOwnedVehicles()).toSet())
            first to duplicate
        }
        val beforeSale = ok(deliveryQueries.read(identity))
        assertEquals(1, beforeSale.freeSlots)

        val garageCommands = StoredGarageCommandSource(context, sessions = games)
        val request = GarageEvent.RequestAction(sold.ownedVehicleId, sold.revision, VehicleAction.SELL)
        val prepared = ok(garageCommands.prepare(request, RequestId("sell-duplicate"))) as GaragePreparation.Ready
        ok(garageCommands.commit(prepared.pending.command))

        val reopened = GameSessionFactory(context, names, InputSource.SIMULATION)
        ok(reopened.open()).use { session ->
            assertEquals(identity, session.identity)
            val store = session.store
            val history = ok(store.listVehicleHistory())
            assertEquals(2, history.size)
            assertEquals(VehicleLifecycle.SOLD, history.single { it.ownedVehicleId == sold.ownedVehicleId }.lifecycle)
            val expectedIds = listOf(kept.ownedVehicleId)
            assertEquals(expectedIds, ok(store.listOwnedVehicles()).map { it.ownedVehicleId })
            val garage = Garage(ok(session.content).catalog)
            val available = ok(garage.availableForRental(history, store.rental::activity))
            assertEquals(expectedIds, available.map { it.ownedVehicleId })
        }
        val reopenedQueries = FactoryDeliveryHostQueries(reopened) { it.terms() }
        assertEquals(beforeSale.freeSlots + 1, ok(reopenedQueries.read(identity)).freeSlots)
    }
}
