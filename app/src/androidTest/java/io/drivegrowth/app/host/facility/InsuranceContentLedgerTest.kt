package io.drivegrowth.app.host.facility

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.data.rentalVisitDefinition
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.util.UUID

/** 고정 고객·시간 입력으로 중앙 보험 제안과 실제 SIMULATION SQLite 정산을 연결한다. */
@Suppress("DEPRECATION")
class InsuranceContentLedgerTest : InstrumentationTestCase() {
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    fun testCentralInsuranceUsesVisitThresholdAndChangesAccidentPayout() {
        val context = instrumentation.targetContext
        assertEquals("io.drivegrowth.app.insurance44", context.packageName)
        val prefix = "insurance-content-${UUID.randomUUID()}"
        val names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        val mode = InputSource.SIMULATION
        try {
            val games = GameSessionFactory(context, names, mode)
            val host = FactoryFacilityHostSource(context, games, CheckpointFacilityDurablePort())
            val definition = ok(AssetTrialDefinitionProvider(context.assets).load())
            val rental = ok(definition.rentalVisitDefinition())
            val model = VehicleId("hyundai_casper")
            assertEquals(GameMoney(100), rental.rates.getValue(model))

            val bareCar = OwnedVehicle(OwnedVehicleId("bare"), model, 1, Revision(0),
                VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.GACHA)
            // 같은 차종·희귀도·레벨의 새 개체로 과거 사고에 따른 대여료 차이를 배제한다.
            val insuredCar = bareCar.copy(ownedVehicleId = OwnedVehicleId("insured"))
            val terms = RentalTerms(rental.rules, SimulatedCustomer(30, 36), roll = 0,
                start = EpochMillis(0), returnMinutes = rental.returns.getValue(1),
                fuelBonus = rental.fuelBonus, rounding = rental.rounding, regularCustomer = false)
            fun request(visit: Int) = RentalRequest(RequestId("insurance-visit-$visit"), model, 1)
            fun atVisit(visit: Int) = terms.copy(start = EpochMillis((visit - 1) * rental.intervalMillis))

            val firstRequest = request(1)
            val hundredth = request(100)
            val bareReceipt = ok(games.open()).use { game ->
                assertEquals(mode, game.identity.source)
                val store = game.store
                val ledger = store.rental
                val catalog = ok(game.content).catalog
                ok(store.commitGameChange(GameChange(RequestId("insurance-fixture-fund"), TransactionKind.GARAGE,
                    mode, rental.rules, MoneyChange(MoneyDirection.CREDIT, GameMoney(2_000)), VehicleWrite.Add(bareCar))))
                assertEquals(bareCar.ownedVehicleId,
                    ok(ledger.assign(firstRequest, catalog, rental.categories, rental.rates, terms)))
                val end = EpochMillis(3_600_000)
                assertEquals(end, ok(ledger.account(firstRequest.id, terms.start, end, parkedSince = null)))
                val receipt = ok(ledger.settle(firstRequest.id))
                assertEquals(VehicleActivity.NEEDS_REPAIR,
                    ok(ledger.finishReturn(firstRequest.id, EpochMillis(end.value + terms.returnMinutes * 60_000L))))
                // 수리 필요 차량만 남은 상태에서 실제 배정 API가 미배정 방문을 저장한다.
                for (visit in 2..99) {
                    assertNull(ok(ledger.assign(request(visit), catalog, rental.categories, rental.rates, atVisit(visit))))
                }
                assertEquals(1, ok(ledger.completedRentalCount()))
                receipt
            }
            assertTrue(bareReceipt.accident)
            // 중앙 기본료 100 × 차종 일치 120% × 사고 요율 50%.
            assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(60)), bareReceipt.settlement.transaction.money)

            val locked = ok(host.read())
            assertEquals(99, locked.shop.progress.servedCustomers)
            assertEquals(emptySet<Facility>(), locked.shop.facilities)
            val offer = locked.offers.getValue(Facility.INSURANCE)
            assertEquals(definition.version, locked.definitionVersion)
            assertEquals(definition.facilityOffers.getValue(Facility.INSURANCE), offer)
            assertEquals(Facility.INSURANCE, offer.facility)
            assertEquals(definition.rules, offer.ruleVersion)
            assertTrue(offer.trial)
            assertEquals(GameMoney(1_500), offer.price)
            val command = FacilityCommand.Buy(locked.identity, RequestId("insurance-purchase"), locked.definitionVersion, offer)
            val before = ok(games.open()).use { ok(it.store.listUnviewedSettlements()) }
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), host.save(command))
            assertEquals(FacilityPending.Empty, ok(host.pending()))
            assertEquals(locked, ok(host.read()))
            assertEquals(before, ok(games.open()).use { ok(it.store.listUnviewedSettlements()) })

            ok(games.open()).use { game ->
                val ledger = game.store.rental
                val catalog = ok(game.content).catalog
                repeat(2) {
                    assertNull(ok(ledger.assign(hundredth, catalog, rental.categories, rental.rates, atVisit(100))))
                }
                assertEquals(1, ok(ledger.completedRentalCount()))
            }
            val unlocked = ok(host.read())
            assertEquals(100, unlocked.shop.progress.servedCustomers)
            assertEquals(locked.balance, unlocked.balance)
            assertEquals(offer, unlocked.offers.getValue(Facility.INSURANCE))
            ok(host.save(command))
            val purchase = ok(host.execute(command)) as FacilityReceipt.Purchased
            ok(host.finish(command))
            assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(1_500)), purchase.settlement.transaction.money)
            assertEquals(GameMoney(unlocked.balance.units - 1_500), purchase.settlement.balance)
            assertEquals(setOf(Facility.INSURANCE), ok(host.read()).shop.facilities)

            val insuredRequest = request(101)
            val insuredTerms = atVisit(101)
            val insuredReceipt = ok(games.open()).use { game ->
                val store = game.store
                val ledger = store.rental
                ok(store.commitGameChange(GameChange(RequestId("insurance-fixture-car"), TransactionKind.GARAGE,
                    mode, rental.rules, vehicle = VehicleWrite.Add(insuredCar))))
                assertEquals(insuredCar.ownedVehicleId,
                    ok(ledger.assign(insuredRequest, ok(game.content).catalog, rental.categories, rental.rates, insuredTerms)))
                val end = EpochMillis(insuredTerms.start.value + 3_600_000)
                assertEquals(end, ok(ledger.account(insuredRequest.id, insuredTerms.start, end, parkedSince = null)))
                ok(ledger.settle(insuredRequest.id))
            }
            assertEquals(bareReceipt.accident, insuredReceipt.accident)
            assertEquals(bareReceipt.matchReview, insuredReceipt.matchReview)
            assertEquals(bareReceipt.washReview, insuredReceipt.washReview)
            assertEquals(bareReceipt.facilityBonus, insuredReceipt.facilityBonus)
            assertEquals(bareReceipt.regularCustomer, insuredReceipt.regularCustomer)
            // 고객·난수·시간은 동일하다. 사고 요율 75%를 적용한 실제 입금은 90이다.
            assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(90)), insuredReceipt.settlement.transaction.money)
            assertEquals(GameMoney(purchase.settlement.balance.units + 90), insuredReceipt.settlement.balance)

            val reopenedGames = GameSessionFactory(context, names, mode)
            val reopened = FactoryFacilityHostSource(context, reopenedGames, CheckpointFacilityDurablePort())
            val current = ok(reopened.read())
            assertEquals(insuredReceipt.settlement.balance, current.balance)
            val saved = ok(reopenedGames.open()).use { game ->
                val ledger = game.store.rental
                assertEquals(bareReceipt, ok(ledger.completedRental(firstRequest.id))?.receipt)
                assertEquals(insuredReceipt, ok(ledger.completedRental(insuredRequest.id))?.receipt)
                ok(game.store.listUnviewedSettlements())
            }
            assertEquals(listOf(purchase.settlement), saved.filter { it.transaction.kind == TransactionKind.FACILITY })
            ok(reopened.save(command))
            assertEquals(purchase, ok(reopened.execute(command)))
            ok(reopened.finish(command))
            assertEquals(FacilityPending.Empty, ok(reopened.pending()))
            assertEquals(current, ok(reopened.read()))
            assertEquals(saved, ok(reopenedGames.open()).use { ok(it.store.listUnviewedSettlements()) })
            assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
        } finally {
            names.values.forEach(context::deleteDatabase)
        }
    }
}
