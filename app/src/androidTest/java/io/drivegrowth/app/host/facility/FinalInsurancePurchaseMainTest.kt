package io.drivegrowth.app.host.facility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCheckpointResult
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.data.RentalReceipt
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.data.rentalVisitDefinition
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.FutureTask

/** 공개 거래 API로 가상 사고와 방문을 준비하고 실제 Main의 보험 구매와 같은 원장의 효과를 대조한다. */
@Suppress("DEPRECATION")
class FinalInsurancePurchaseMainTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val db get() = context.getDatabasePath("unified-simulation.db")
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to db.name), InputSource.SIMULATION)
    }
    private val casper = VehicleId("hyundai_casper")
    private val bareRequest = RequestId("final-insurance-fixture-visit-1")
    private val insuredRequest = RequestId("final-insurance-fixture-visit-101")
    private val confirmTag = "shop:map-confirm"

    fun testMainBuysCentralInsuranceAndTheSameLedgerPaysAccidentBenefit() {
        assertTrue("새 격리 패키지에서 실행합니다", context.packageName.startsWith("io.drivegrowth.verify.finalinsurance44"))
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertFalse(db.exists())
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        val timeline = SimulationTimeline()
        val preferences = context.getSharedPreferences("unified-simulation-v1", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("stage"))
        assertTrue(preferences.edit().putInt("stage", timeline.stage).putLong("elapsed", timeline.elapsedMs).commit())

        val definition = ok(AssetTrialDefinitionProvider(context.assets).load())
        val rental = ok(definition.rentalVisitDefinition())
        assertEquals("user-demo-trial-2026-11-10", definition.provenance.getValue("authorization"))
        assertEquals("unmeasured-trial", definition.provenance.getValue("balance_status"))
        assertTrue(definition.trial)
        assertEquals(GameMoney(100), rental.rates.getValue(casper))
        val expectedPrices = mapOf(Facility.FUEL_STATION to 500L, Facility.INSURANCE to 1_500L, Facility.CAR_WASH to 1_000L)
        assertEquals(expectedPrices, definition.facilityOffers.mapValues { it.value.price.units })
        for ((id, offer) in definition.facilityOffers) {
            assertEquals(id, offer.facility)
            assertEquals(definition.rules, offer.ruleVersion)
            assertTrue(offer.trial)
        }
        val insurance = definition.facilityOffers.getValue(Facility.INSURANCE)
        fun request(visit: Int) = RentalRequest(RequestId("final-insurance-fixture-visit-$visit"), casper, 1)
        fun terms(visit: Int) = RentalTerms(rental.rules, SimulatedCustomer(30, 36), roll = 0,
            start = EpochMillis((visit - 1L) * rental.intervalMillis), returnMinutes = rental.returns.getValue(1),
            fuelBonus = rental.fuelBonus, rounding = rental.rounding, regularCustomer = false)

        val onboarding = StoredOnboardingHostSource(games)
        val identity = ok(onboarding.read()).identity
        assertEquals(InputSource.SIMULATION, identity.source)
        ok(onboarding.choose(identity, casper))
        val (initialVehicle, bareReceipt) = read { game ->
            assertEquals(identity, game.identity)
            val storedDefinition = ok(game.store.trialDefinitions.load())
            assertEquals(definition.version, storedDefinition.version)
            assertEquals(definition.rules, storedDefinition.rules)
            assertEquals(definition.facilityOffers, storedDefinition.facilityOffers)
            assertEquals(definition.provenance, storedDefinition.provenance)
            assertEquals(definition.fuelBonusPerRental, storedDefinition.fuelBonusPerRental)
            val vehicle = ok(game.store.listOwnedVehicles()).single()
            ok(game.store.commitGameChange(GameChange(RequestId("final-insurance-fixture-credit"), TransactionKind.GARAGE,
                identity.source, definition.rules, MoneyChange(MoneyDirection.CREDIT, GameMoney(2_000)))))
            val ledger = game.store.rental
            val catalog = ok(game.content).catalog
            val firstTerms = terms(1)
            assertEquals(bareRequest, request(1).id)
            assertEquals(vehicle.ownedVehicleId,
                ok(ledger.assign(request(1), catalog, rental.categories, rental.rates, firstTerms)))
            val receipt = settle(game, bareRequest, firstTerms)
            val returnedAt = EpochMillis(firstTerms.start.value + 3_600_000 + firstTerms.returnMinutes * 60_000L)
            assertEquals(VehicleActivity.NEEDS_REPAIR, ok(ledger.finishReturn(bareRequest, returnedAt)))
            for (visit in 2..99) {
                assertNull(ok(ledger.assign(request(visit), catalog, rental.categories, rental.rates, terms(visit))))
            }
            assertEquals(1, ok(ledger.completedRentalCount()))
            vehicle to receipt
        }
        assertTrue(bareReceipt.accident)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(60)), bareReceipt.settlement.transaction.money)
        // 준비 대여의 개업 선물은 실제 구매 미리보기와 별개로 먼저 확정한다.
        assertEquals(FirstGrant.GIFT_REQUEST_ID, ok(onboarding.claimGift(identity)).command.requestId)

        val factory = FactoryFacilityHostSource(context, games, CheckpointFacilityDurablePort())
        val facts = ok(factory.read())
        assertEquals(identity, facts.identity)
        assertEquals(definition.version, facts.definitionVersion)
        assertEquals(definition.facilityOffers, facts.offers)
        assertEquals(definition.fuelBonusPerRental, facts.fuelBonusPerRental)
        assertEquals(99, facts.shop.progress.servedCustomers)
        assertTrue(facts.shop.facilities.isEmpty())

        val root = activity.window.decorView
        awaitUi("모의 주차의 실제 Main 가게 진입") {
            val home = root.mainPage(HostPage.HOME) ?: return@awaitUi false
            if (!home.isShown || !home.isEnabled) return@awaitUi false
            if (home.isSelected) return@awaitUi true
            assertTrue(home.performClick())
            false
        }
        val effect = context.getString(R.string.shop_effect, "사고 손실 절반 보전")
        for ((facility, price) in expectedPrices) {
            openFacility(root, facility)
            awaitUi("지도 시설 가격과 시험값 안내 $facility") {
                val labels = overlayLabels(root)
                priceLabel(price) in labels && context.getString(R.string.shop_trial) in labels
            }
            if (facility == Facility.INSURANCE) {
                val labels = onUi { overlayLabels(root) }
                assertTrue(labels.containsAll(listOf(context.getString(R.string.shop_locked), conditionLabel(99), effect)))
                assertNull(onUi { root.findViewWithTag<Button>(confirmTag) })
            }
            click(root, "shop:map-close")
        }
        val locked = snapshot()
        assertEquals(identity, locked.identity)
        assertEquals(99, locked.shop.progress.servedCustomers)
        assertTrue(locked.balance.units >= insurance.price.units)
        assertNull(locked.checkpoint)

        read { game ->
            val ledger = game.store.rental
            val catalog = ok(game.content).catalog
            repeat(2) {
                assertNull(ok(ledger.assign(request(100), catalog, rental.categories, rental.rates, terms(100))))
            }
            assertEquals(1, ok(ledger.completedRentalCount()))
        }
        click(root, "shop:map-settings")
        click(root, "host:refresh")
        openFacility(root, Facility.INSURANCE)
        awaitUi("고유 방문 100의 보험 건설 해금") {
            overlayLabels(root).containsAll(listOf(
                context.getString(R.string.shop_available), conditionLabel(100), effect,
            )) && root.findViewWithTag<Button>(confirmTag)?.let { it.isShown && it.isEnabled } == true
        }
        val before = snapshot()
        assertEquals(locked.copy(shop = locked.shop.copy(progress = locked.shop.progress.copy(servedCustomers = 100))), before)
        click(root, confirmTag)
        awaitUi("보험 원결과와 결과 확인 버튼") {
            root.findViewWithTag<View>("facility:receipt")?.isShown == true &&
                root.findViewWithTag<Button>("facility:ack")?.isEnabled == true
        }
        val purchased = snapshot()
        val checkpoint = checkNotNull(purchased.checkpoint)
        val command = checkpoint.command as HostCheckpointCommand.FacilityPurchase
        assertEquals(HostCheckpointPhase.STARTED, checkpoint.phase)
        assertEquals(identity.profileId, checkpoint.profileId)
        assertEquals(identity.source, command.source)
        assertEquals(definition.version, command.definitionVersion)
        assertEquals(insurance, command.offer)
        val original = read {
            (ok(it.store.hostCommands.facilityResult(identity.profileId, command)) as HostCheckpointResult.FacilityPurchase).value
        }
        assertEquals(command.requestId, original.transaction.requestId)
        assertEquals(InputSource.SIMULATION, original.transaction.source)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(1_500)), original.transaction.money)
        assertEquals(GameMoney(before.balance.units - 1_500), original.balance)
        assertEquals(before.copy(shop = before.shop.copy(facilities = setOf(Facility.INSURANCE)),
            balance = original.balance, settlements = before.settlements + original, checkpoint = checkpoint), purchased)
        click(root, "facility:ack")
        awaitUi("보험 결과 확인 후 확정 재조회로 지도에 돌아온다") {
            if (root.findViewWithTag<View>("facility:receipt")?.isShown == true) return@awaitUi false
            root.findViewWithTag<Button>("shop:map-sheet-close")?.takeIf { it.isShown && it.isEnabled }?.performClick()
            root.findViewWithTag<View>("shop:map-facilities")?.isShown == true
        }
        openFacility(root, Facility.INSURANCE)
        awaitUi("보험 설치 유지와 재구매 선택 제거") {
            context.getString(R.string.shop_installed) in overlayLabels(root) &&
                root.findViewWithTag<Button>(confirmTag) == null
        }
        assertEquals(purchased.copy(checkpoint = null), snapshot())

        val insuredReceipt = read { game ->
            assertEquals(identity, game.identity)
            val insured = initialVehicle.copy(ownedVehicleId = OwnedVehicleId("final-insurance-fixture-insured"),
                acquisitionSource = VehicleAcquisitionSource.GACHA)
            ok(game.store.commitGameChange(GameChange(RequestId("final-insurance-fixture-add"), TransactionKind.GARAGE,
                identity.source, definition.rules, vehicle = VehicleWrite.Add(insured))))
            val ledger = game.store.rental
            assertEquals(insuredRequest, request(101).id)
            assertEquals(insured.ownedVehicleId, ok(ledger.assign(request(101), ok(game.content).catalog,
                rental.categories, rental.rates, terms(101))))
            settle(game, insuredRequest, terms(101))
        }
        assertTrue(insuredReceipt.accident)
        assertEquals(bareReceipt.matchReview, insuredReceipt.matchReview)
        assertEquals(bareReceipt.washReview, insuredReceipt.washReview)
        assertEquals(bareReceipt.facilityBonus, insuredReceipt.facilityBonus)
        assertEquals(bareReceipt.regularCustomer, insuredReceipt.regularCustomer)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(90)), insuredReceipt.settlement.transaction.money)
        assertEquals(GameMoney(original.balance.units + 90), insuredReceipt.settlement.balance)
        read { game ->
            assertEquals(identity, game.identity)
            assertEquals(bareReceipt, ok(game.store.rental.completedRental(bareRequest))?.receipt)
            assertEquals(insuredReceipt, ok(game.store.rental.completedRental(insuredRequest))?.receipt)
            assertEquals(HostCheckpointResult.FacilityPurchase(original),
                ok(game.store.hostCommands.facilityResult(identity.profileId, command)))
            assertEquals(listOf(original), ok(game.store.listUnviewedSettlements()).filter {
                it.transaction.kind == TransactionKind.FACILITY
            })
            assertEquals(insuredReceipt.settlement.balance, ok(game.store.balance()))
            assertNull(ok(game.store.hostCommands.load(identity.profileId)))
        }
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        Log.i("FinalInsuranceMain", "pid=${Process.myPid()} source=${identity.source} profile=${identity.profileId.value} " +
            "definition=${definition.version.value} rules=${definition.rules.value} facility=${insurance.facility} " +
            "request=${command.requestId.value} settlement=${original.settlementId.value} visitors=99->100 " +
            "purchase=1500 uninsured=60 insured=90 balance=${insuredReceipt.settlement.balance.units} slot=empty")
    }

    private fun settle(game: GameSession, request: RequestId, terms: RentalTerms): RentalReceipt {
        val ledger = game.store.rental
        val end = EpochMillis(terms.start.value + 3_600_000)
        assertEquals(end, ok(ledger.account(request, terms.start, end, parkedSince = null)))
        return ok(ledger.settle(request))
    }

    private data class Snapshot(
        val identity: GameIdentity,
        val shop: ShopState,
        val balance: GameMoney,
        val vehicles: List<OwnedVehicle>,
        val settlements: List<Settlement>,
        val checkpoint: HostCommandCheckpoint?,
    )

    private fun snapshot() = read { game ->
        val store = game.store
        Snapshot(game.identity, ok(store.rental.shop()), ok(store.balance()), ok(store.listVehicleHistory()),
            ok(store.listUnviewedSettlements()), ok(store.hostCommands.load(game.identity.profileId)))
    }

    private fun conditionLabel(visits: Int) = context.getString(R.string.shop_condition, "누적 고객 $visits/100명")
    private fun priceLabel(price: Long): String {
        val money = context.getString(R.string.shop_money, NumberFormat.getIntegerInstance(Locale.KOREA).format(price))
        return context.getString(R.string.shop_price, money)
    }

    private fun openFacility(root: View, facility: Facility) {
        click(root, "shop:map-facilities")
        click(root, "shop:menu-facility:${facility.name}")
    }

    private fun overlayLabels(root: View): Set<String> {
        val overlay = root.findViewWithTag<View>("shop:map-overlay") ?: return emptySet()
        return views(overlay).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toSet()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun click(root: View, tag: String) = awaitUi("현재 활성 버튼 $tag") {
        val button = root.findViewWithTag<Button>(tag) ?: return@awaitUi false
        if (!button.isShown || !button.isAttachedToWindow || !button.isEnabled) return@awaitUi false
        assertTrue(button.performClick())
        true
    }

    private fun <T> onUi(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun awaitUi(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        do {
            val task = FutureTask<Boolean> { condition() }
            instrumentation.runOnMainSync(task)
            if (task.get()) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < end)
        fail(label)
    }

    private fun <T> read(action: (GameSession) -> T): T = ok(games.open()).use(action)
    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }
}
