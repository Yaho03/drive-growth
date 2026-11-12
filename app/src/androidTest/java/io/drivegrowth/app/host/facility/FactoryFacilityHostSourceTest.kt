package io.drivegrowth.app.host.facility

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointResult
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.data.TrialDefinition
import io.drivegrowth.app.data.rentalVisitDefinition
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import io.drivegrowth.app.ui.shop.ShopCommand
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.RentalReceipt
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.util.UUID

/** 같은 factory의 조회/미지원 경계만 검사한다. 실제 typed 금융/설정 저장은 E 공급 뒤 별도 검증한다. */
@Suppress("DEPRECATION")
class FactoryFacilityHostSourceTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var names:Map<InputSource,String>
    private lateinit var games:GameSessionFactory
    private lateinit var source:FactoryFacilityHostSource
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="facility-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"};games=GameSessionFactory(context,names,InputSource.SIMULATION)
        val onboarding=StoredOnboardingHostSource(games);val identity=ok(onboarding.read()).identity;ok(onboarding.choose(identity,FirstGrant.CANDIDATES.first()));source=FactoryFacilityHostSource(context,games)
    }
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    fun testActualFactoryReadsStoredFacilitiesAndCentralOffersWithoutAnotherMode() {
        val facts=ok(source.read());assertEquals(InputSource.SIMULATION,facts.identity.source);assertEquals(GameMoney(1000),facts.balance)
        assertEquals(1,facts.vehicles.size);assertEquals(Facility.entries.toSet(),facts.offers.keys);assertTrue(facts.shop.facilities.isEmpty());assertTrue(facts.resting.isEmpty())
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testFuelDisplayAndLedgerFollowOneTrialDefinitionThroughAboveThresholdPurchases() {
        ok(games.open()).use { game ->
            val store = game.store
            val central = ok(store.trialDefinitions.load())
            val catalog = ok(game.content).catalog
            // Lv4·보유 6대는 초과 해금 시험의 준비 상태다. 구매·대여·정산은 제품 경로를 사용한다.
            ok(store.atomic { db ->
                db.execSQL("UPDATE shop SET level=4,capacity=10 WHERE id=1")
                ContractResult.Success(Unit)
            })
            val original = ok(store.listOwnedVehicles()).single()
            for (number in 2..6) {
                val added = original.copy(ownedVehicleId = OwnedVehicleId("facility-fixture-$number"),
                    acquisitionSource = VehicleAcquisitionSource.GACHA)
                ok(store.commitGameChange(GameChange(RequestId("facility-fixture-add-$number"),
                    TransactionKind.GARAGE, game.identity.source, central.rules,
                    MoneyChange(MoneyDirection.CREDIT, GameMoney(1_000)), VehicleWrite.Add(added))))
            }

            val before = settle(game, central, "before-facilities", 0)
            assertEquals(GameMoney(0), before.facilityBonus)
            assertFalse(before.washReview)
            val initial = ok(source.read(game))
            assertEquals(6, initial.shop.progress.ownedVehicles)
            assertEquals(4, initial.shop.progress.shopLevel)
            assertEquals(central.version, initial.definitionVersion)
            assertEquals(GameMoney(10), initial.fuelBonusPerRental)
            assertEquals("대여당 10 게임머니 추가 수익", option(initial, Facility.FUEL_STATION).effect)
            buyOnce(game, initial, Facility.FUEL_STATION, GameMoney(500))
            val fuelOnly = settle(game, central, "central-fuel", 7_200_000)
            assertEquals(GameMoney(10), fuelOnly.facilityBonus)
            assertFalse(fuelOnly.washReview)
            val afterFuel = ok(source.read(game))
            assertEquals("기존 설치 기록 · 대여당 10 게임머니 추가 수익", option(afterFuel, Facility.FUEL_STATION).effect)

            // 같은 APK 자산의 보너스와 버전만 복사본에서 바꿔 하드코딩을 검출한다.
            val originalText = context.assets.open("content/trial-definitions.csv").bufferedReader().use { it.readText() }
            val changedVersion = ContentVersion("facility-bonus-fixture-v2")
            val changedRule = RuleVersion("facility-bonus-rule-v2")
            val changedText = originalText.replaceFirst("definition_version,${central.version.value}",
                "definition_version,${changedVersion.value}")
                .replaceFirst("rule_version,${central.rules.value}", "rule_version,${changedRule.value}")
                .replaceFirst("per_rental,10", "per_rental,17")
            val changedSource = AssetTrialDefinitionProvider({ changedText },
                { ContractResult.Success(catalog.vehicles.map { it.vehicleId }.toSet()) })
            store.trialDefinitions = changedSource
            val changed = ok(changedSource.load())
            val changedHost = FactoryFacilityHostSource(context, games, definitions = changedSource)
            val changedFacts = ok(changedHost.read(game))
            assertEquals(changedVersion, changedFacts.definitionVersion)
            assertEquals(GameMoney(17), changedFacts.fuelBonusPerRental)
            val fuel = option(changedFacts, Facility.FUEL_STATION)
            assertEquals(changedRule, fuel.ruleVersion)
            assertTrue(fuel.trial)
            assertEquals("기존 설치 기록 · 대여당 17 게임머니 추가 수익", fuel.effect)
            buyOnce(game, changedFacts, Facility.CAR_WASH, GameMoney(1_000))

            val after = settle(game, changed, "changed-fuel-and-wash", 14_400_000)
            assertEquals(GameMoney(17), after.facilityBonus)
            assertTrue(after.washReview)
            val baseFare = checkNotNull(before.settlement.transaction.money).amount.units
            assertEquals(GameMoney(baseFare + 10), fuelOnly.settlement.transaction.money?.amount)
            assertEquals(GameMoney(baseFare + 17), after.settlement.transaction.money?.amount)
            assertEquals(fuelOnly, ok(store.rental.settle(RequestId("central-fuel"))))
        }
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }

    private fun option(facts: FacilityFacts, facility: Facility) =
        FacilityHostMapper.options(facts, true).single { it.facility == facility }.option

    private fun buyOnce(game: GameSession, facts: FacilityFacts, facility: Facility, price: GameMoney) {
        val store = game.store
        val profile = game.identity.profileId
        val offer = (option(facts, facility).command as ShopCommand.BuyFacility).offer
        assertEquals(price, offer.price)
        val command = HostCheckpointCommand.FacilityPurchase(RequestId("facility-purchase-$facility"),
            game.identity.source, facts.definitionVersion, offer)
        val balance = ok(store.balance())
        ok(store.hostCommands.save(profile, command))
        val receipt = ok(store.hostCommands.execute(profile, command)) as HostCheckpointResult.FacilityPurchase
        assertEquals(MoneyChange(MoneyDirection.DEBIT, price), receipt.value.transaction.money)
        assertEquals(GameMoney(balance.units - price.units), receipt.value.balance)
        ok(store.hostCommands.finish(profile, command))
        val settlements = ok(store.listUnviewedSettlements())
        ok(store.hostCommands.save(profile, command))
        assertEquals(receipt, ok(store.hostCommands.execute(profile, command)))
        ok(store.hostCommands.finish(profile, command))
        val duplicate = command.copy(requestId = RequestId("facility-duplicate-$facility"))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.hostCommands.save(profile, duplicate))
        assertEquals(receipt.value.balance, ok(store.balance()))
        assertEquals(settlements, ok(store.listUnviewedSettlements()))
        assertNull(ok(store.hostCommands.load(profile)))
    }

    private fun settle(game: GameSession, definition: TrialDefinition, requestId: String, start: Long): RentalReceipt {
        val rules = ok(definition.rentalVisitDefinition())
        val id = RequestId(requestId)
        val model = FirstGrant.CANDIDATES.first()
        val terms = RentalTerms(rules.rules, SimulatedCustomer(30, 48), 99, EpochMillis(start),
            rules.returns.getValue(1), rules.fuelBonus, rules.rounding, false)
        val ledger = game.store.rental
        assertNotNull(ok(ledger.assign(RentalRequest(id, model, 1), ok(game.content).catalog,
            rules.categories, rules.rates, terms)))
        ok(ledger.account(id, terms.start, EpochMillis(start + 3_600_000), null))
        return ok(ledger.settle(id))
    }

    fun testMissingTypedJournalNeverCallsDirectPurchaseOrRestingWrite() {
        val before=ok(source.read());assertEquals(FacilityPending.Unsupported,ok(source.pending()))
        val buy=FacilityCommand.Buy(before.identity,RequestId("facility-buy"),before.definitionVersion,before.offers.getValue(Facility.FUEL_STATION))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.save(buy));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.execute(buy))
        val car=before.vehicles.single();val rest=FacilityCommand.Rest(before.identity,RequestId("rest"),VehicleRef(car.ownedVehicleId,car.revision),false,true)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.save(rest));assertEquals(before,ok(source.read()))
    }
}
