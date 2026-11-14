package io.drivegrowth.app.host.facility

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.graphics.Rect
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.data.AssetShopExpansionProvider
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCheckpointResult
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.data.rentalVisitDefinition
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.shop.ShopSceneView
import io.drivegrowth.app.ui.shop.FacilityControlsView
import io.drivegrowth.app.ui.shop.ShopCommand
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer

/** cold 격리 앱의 실제 SDK gate/UI. DB는 공개 지급 뒤 읽기만 하며 신호/신규근거/재화를 주입하지 않는다. */
@Suppress("DEPRECATION")
class FacilityHomeActivityTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    private fun menu(a: MainActivity) = a.window.decorView.mainNavigation()
    private fun await(a: MainActivity, label: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        var success = false
        var gate = false
        while (!success && SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync {
                gate = menu(a)?.let { it.isShown && it.isEnabled } == true
                success = check()
            }
            if (!success) SystemClock.sleep(50)
        }
        if (!success && !gate) throw IllegalStateException("ENVIRONMENT_GATE_TIMEOUT: $label; 실제 SDK 허용 메뉴 미관측")
        assertTrue("ASSERTION_STATE: $label", success)
    }
    private fun click(a: MainActivity, tag: Any) {
        await(a, "활성 UI 버튼 $tag") {
            menu(a)?.isEnabled == true && a.window.decorView.findViewWithTag<Button>(tag)?.let { it.isShown && it.isEnabled } == true
        }
        instrumentation.runOnMainSync {
            val button = a.window.decorView.findViewWithTag<Button>(tag)!!
            button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            assertTrue(button.performClick())
        }
    }
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun <T> read(action: (GameSession) -> T) = ok(GameSessionFactory(instrumentation.targetContext).open()).use(action)

    fun testActualHomeRestingRequestSurvivesRecreationAndChangesNoMoneyOrVehicle() {
        val context = instrumentation.targetContext
        assertTrue("제품 패키지 실행 금지", context.packageName != "io.drivegrowth.app")
        for (name in listOf("game-ledger-vehicle.db", "game-ledger-simulation.db"))
            assertFalse("새 격리 package가 필요하며 기존 DB를 지우면 안 된다: $name", context.getDatabasePath(name).exists())
        val first = activity
        await(first, "실제 NEW 안내") { tree(first.window.decorView).any { it is OnboardingView } }
        click(first, R.string.onboarding_start)
        click(first, VehicleId("hyundai_casper"))
        click(first, R.string.onboarding_review)
        click(first, R.string.onboarding_confirm)
        await(first, "공개 첫 지급 확정") {
            first.window.decorView.findViewWithTag<Button>(R.string.onboarding_to_shop)?.isEnabled == true
        }
        val initial = read { game ->
            assertEquals(InputSource.VEHICLE, game.identity.source)
            assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
            game.identity to ok(game.store.listOwnedVehicles()).single()
        }
        click(first, R.string.onboarding_to_shop)
        await(first, "실제 HOME의 중앙 주유소 보너스") {
            tree(first.window.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == context.getString(R.string.shop_effect, "대여당 10 게임머니 추가 수익")
            }
        }
        val tag = "facility:rest:${initial.second.ownedVehicleId.value}"
        await(first, "실제 HOME 시설·쉬기 화면과 현재 OFF") {
            tree(first.window.decorView).any { it is FacilityControlsView } &&
                first.window.decorView.findViewWithTag<Button>(tag)?.let { it.isEnabled && it.text.toString() == "영업 쉬기 켜기" } == true
        }
        click(first, tag)
        await(first, "원래 비금융 ON 결과") { first.window.decorView.findViewWithTag<View>("facility:receipt")?.isShown == true }
        val original = read { game ->
            val saved = ok(game.store.hostCommands.load(game.identity.profileId))!!
            assertTrue(saved.command is HostCheckpointCommand.VehicleResting)
            assertTrue(ok(game.store.rental.restingVehicles()).contains(initial.second.ownedVehicleId))
            assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
            assertEquals(listOf(initial.second), ok(game.store.listOwnedVehicles()))
            saved
        }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var recreated: MainActivity? = null
        try {
            instrumentation.runOnMainSync { first.recreate() }
            recreated = instrumentation.waitForMonitorWithTimeout(monitor, 15000) as? MainActivity
            assertNotNull(recreated)
            val next = recreated!!
            assertNotSame(first, next)
            await(next, "같은 원래 ON 결과 복원") {
                next.window.decorView.findViewWithTag<View>("facility:receipt")?.isShown == true
            }
            read { game -> assertEquals(initial.first, game.identity); assertEquals(original, ok(game.store.hostCommands.load(game.identity.profileId))) }
            click(next, "facility:ack")
            await(next, "현재 ON 재조회") { next.window.decorView.findViewWithTag<Button>(tag)?.let { it.isEnabled && it.text.toString() == "영업 쉬기 끄기" } == true }
            click(next, tag)
            await(next, "원래 OFF 결과") { next.window.decorView.findViewWithTag<View>("facility:receipt")?.isShown == true }
            click(next, "facility:ack")
            await(next, "현재 OFF 재조회") { next.window.decorView.findViewWithTag<Button>(tag)?.let { it.isEnabled && it.text.toString() == "영업 쉬기 켜기" } == true }
            read { game ->
                assertEquals(initial.first, game.identity)
                assertTrue(ok(game.store.rental.restingVehicles()).isEmpty())
                assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
                assertEquals(listOf(initial.second), ok(game.store.listOwnedVehicles()))
                assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
                val counts = ok(game.store.read { db ->
                    listOf("facility_command_results", "settlements").map { table ->
                        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
                    }
                })
                assertEquals(listOf(2L, 1L), counts)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            recreated?.let { next -> instrumentation.runOnMainSync { next.finish() } }
        }
    }

    private data class FuelPurchaseState(
        val identity: GameIdentity,
        val shop: ShopState,
        val balance: GameMoney,
        val vehicles: List<OwnedVehicle>,
        val settlements: List<Settlement>,
        val resultCount: Long,
    )

    private fun fuelPurchaseState(game: GameSession): FuelPurchaseState {
        val store = game.store
        val resultCount = ok(store.read { db ->
            db.rawQuery("SELECT COUNT(*) FROM facility_command_results", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
        })
        return FuelPurchaseState(game.identity, ok(store.rental.shop()), ok(store.balance()),
            ok(store.listOwnedVehicles()), ok(store.listUnviewedSettlements()), resultCount)
    }

    private fun prepareFiveOwnedVehicles(games: GameSessionFactory) {
        val onboarding = StoredOnboardingHostSource(games)
        val firstModel = FirstGrant.CANDIDATES.first()
        val identity = ok(onboarding.read()).identity
        ok(onboarding.choose(identity, firstModel))
        ok(games.open()).use { game ->
            val store = game.store
            assertEquals(InputSource.SIMULATION, game.identity.source)
            val definition = ok(store.trialDefinitions.load())
            val rental = ok(definition.rentalVisitDefinition())
            val catalog = ok(game.content).catalog
            val first = ok(store.listOwnedVehicles()).single()
            // 완료 대여와 확장은 5칸을 준비하는 격리 입력이다. 주유소 명령은 화면에서만 제출한다.
            repeat(3) { index ->
                val start = index * 4_500_000L
                val id = RequestId("fuel-main-fixture-rental-$index")
                val terms = RentalTerms(rental.rules, SimulatedCustomer(30, 48), 99, EpochMillis(start),
                    15, rental.fuelBonus, rental.rounding, false)
                assertEquals(first.ownedVehicleId, ok(store.rental.assign(RentalRequest(id, firstModel, 1),
                    catalog, rental.categories, rental.rates, terms)))
                ok(store.rental.account(id, terms.start, EpochMillis(start + 3_600_000L), null))
                ok(store.rental.settle(id))
                ok(store.rental.finishReturn(id, EpochMillis(start + 4_500_000L)))
            }
            val expansion = ok(AssetShopExpansionProvider(instrumentation.targetContext.assets).load()).offers.first()
            ok(store.rental.expand(RequestId("fuel-main-fixture-expand"), expansion))
            for (number in 2..5) {
                val vehicle = first.copy(ownedVehicleId = OwnedVehicleId("fuel-main-fixture-$number"),
                    revision = Revision(0), acquisitionSource = VehicleAcquisitionSource.GACHA)
                ok(store.commitGameChange(GameChange(RequestId("fuel-main-fixture-add-$number"),
                    TransactionKind.GARAGE, game.identity.source, definition.rules, vehicle = VehicleWrite.Add(vehicle))))
            }
        }
        // 최초 완료 영업의 개업 선물을 준비 단계에서 확정해 미리보기와 별개로 처리한다.
        val gift = ok(onboarding.claimGift(identity))
        assertEquals(FirstGrant.GIFT_REQUEST_ID, gift.command.requestId)
    }

    fun testIsolatedSimulationHomeFuelPurchaseAndMotion() {
        val context = instrumentation.targetContext
        assertTrue(context.packageName.startsWith("io.drivegrowth.verify.prototype."))
        val name = "unified-simulation.db"
        val prefs = context.getSharedPreferences("unified-simulation-v1", 0)
        assertFalse("새 모의 프로필 필요", context.getDatabasePath(name).exists())
        assertFalse(prefs.contains("stage"))
        val timeline = SimulationTimeline()
        assertTrue(prefs.edit().putInt("stage", timeline.stage).putLong("elapsed", timeline.elapsedMs).commit())
        val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to name), InputSource.SIMULATION)
        prepareFiveOwnedVehicles(games)
        val before = ok(games.open()).use(::fuelPurchaseState)
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as UnifiedSimulationActivity
        try {
            val decor = host.window.decorView
            val fuelLot = "shop:map-facility:FUEL_STATION"
            var lotClick: Result<Boolean>? = null
            await(host, "현재 가게 지도에서 주유소 부지 열기") {
                if (!host.hasWindowFocus()) return@await false
                val lot = decor.findViewWithTag<Button>(fuelLot)
                lot?.takeIf { it.isAttachedToWindow && it.isShown && it.isLaidOut && it.isEnabled }
                    ?.let { runCatching { it.performClick() }.also { result -> lotClick = result }; true } == true
            }
            assertTrue(checkNotNull(lotClick).getOrThrow())
            var confirmClicks: Result<Boolean>? = null
            await(host, "현재 주유소 건설 요청과 중복 입력") {
                val confirm = decor.findViewWithTag<Button>("shop:map-confirm")
                    ?.takeIf { it.isShown && it.isEnabled && it.isAttachedToWindow && it.isLaidOut }
                    ?: return@await false
                confirmClicks = runCatching {
                    val accepted = confirm.performClick()
                    confirm.performClick()
                    accepted
                }
                true
            }
            assertTrue(checkNotNull(confirmClicks).getOrThrow())
            await(host, "확정된 주유소 설치와 실제 지도 연출") {
                decor.findViewWithTag<View>("facility:receipt")?.isShown == true &&
                    decor.findViewWithTag<ShopSceneView>("shop:scene")?.showingFacilityInstallation == true
            }
            val after = ok(games.open()).use(::fuelPurchaseState)
            assertEquals(before.shop.facilities + Facility.FUEL_STATION, after.shop.facilities)
            assertEquals(before.balance.units - 500, after.balance.units)
            assertEquals(before.vehicles, after.vehicles)
            assertEquals(before.resultCount + 1, after.resultCount)
            assertEquals(before.settlements.size + 1, after.settlements.size)
        } finally {
            instrumentation.runOnMainSync { host.finish() }
        }
    }

    fun testActualHomeFuelPurchaseDuplicateConfirmAndRecreation() {
        val context = instrumentation.targetContext
        assertTrue("제품 패키지 실행 금지", context.packageName != "io.drivegrowth.app")
        for (name in listOf("game-ledger-vehicle.db", "game-ledger-simulation.db", "unified-simulation.db"))
            assertFalse("새 격리 package가 필요하다: $name", context.getDatabasePath(name).exists())
        prepareFiveOwnedVehicles(GameSessionFactory(context, InputSource.SIMULATION))
        val (definition, before) = read { game -> ok(game.store.trialDefinitions.load()) to fuelPurchaseState(game) }
        val offer = definition.facilityOffers.getValue(Facility.FUEL_STATION)
        assertEquals(GameMoney(500), offer.price)
        assertEquals(GameMoney(10), definition.fuelBonusPerRental)
        assertEquals(5, before.shop.capacity)
        assertEquals(5, before.shop.progress.ownedVehicles)
        assertTrue(before.shop.facilities.isEmpty())
        assertEquals(0L, before.resultCount)
        assertTrue("주유소 구입 가능 잔액", before.balance.units >= offer.price.units)

        val first = activity
        val previewTag = "shop:preview:facility-FUEL_STATION"
        val effect = "대여당 10 게임머니 추가 수익"
        fun showsEffect(a: MainActivity, value: String) = tree(a.window.decorView).filterIsInstance<TextView>().any {
            it.isShown && it.text.toString() == context.getString(R.string.shop_effect, value)
        }
        fun hasReceipt(a: MainActivity) = a.window.decorView.findViewWithTag<View>("facility:receipt")?.isShown == true
        await(first, "실제 HOME 주유소 5대 해금과 중앙 보너스") {
            first.window.decorView.findViewWithTag<Button>(previewTag)?.let { it.isShown && it.isEnabled } == true &&
                showsEffect(first, effect)
        }
        click(first, previewTag)
        await(first, "실제 ShopView의 중앙 주유소 제안 선택") {
            val shop = tree(first.window.decorView).filterIsInstance<ShopView>().singleOrNull()
            (shop?.uiState?.selection?.option?.command as? ShopCommand.BuyFacility)?.offer == offer &&
                first.window.decorView.findViewWithTag<Button>("shop:confirm")?.isEnabled == true
        }
        read { game ->
            assertEquals(before, fuelPurchaseState(game))
            assertNull(ok(game.store.hostCommands.load(before.identity.profileId)))
        }
        instrumentation.runOnMainSync {
            val confirm = first.window.decorView.findViewWithTag<Button>("shop:confirm")!!
            confirm.requestRectangleOnScreen(Rect(0, 0, confirm.width, confirm.height), true)
            assertTrue(confirm.performClick())
            assertTrue(confirm.performClick()) // 같은 UI turn에서 이전 버튼의 중복 콜백도 보낸다.
        }
        await(first, "주유소 구매 원결과") { hasReceipt(first) }
        val (slot, purchased) = read { game ->
            val commands = game.store.hostCommands
            val pending = checkNotNull(ok(commands.load(before.identity.profileId)))
            assertEquals(HostCheckpointPhase.STARTED, pending.phase)
            val command = pending.command as HostCheckpointCommand.FacilityPurchase
            assertEquals(InputSource.SIMULATION, command.source)
            assertEquals(definition.version, command.definitionVersion)
            assertEquals(offer, command.offer)
            val receipt = (ok(commands.facilityResult(before.identity.profileId, command))
                as HostCheckpointResult.FacilityPurchase).value
            val expectedBalance = GameMoney(before.balance.units - offer.price.units)
            assertEquals(command.requestId, receipt.transaction.requestId)
            assertEquals(MoneyChange(MoneyDirection.DEBIT, offer.price), receipt.transaction.money)
            assertEquals(expectedBalance, receipt.balance)
            val expected = before.copy(shop = before.shop.copy(facilities = setOf(Facility.FUEL_STATION)),
                balance = expectedBalance, settlements = before.settlements + receipt, resultCount = 1L)
            assertEquals(expected, fuelPurchaseState(game))
            pending to receipt
        }

        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var recreated: MainActivity? = null
        try {
            val beforeRecreation = read(::fuelPurchaseState)
            instrumentation.runOnMainSync { first.recreate() }
            recreated = instrumentation.waitForMonitorWithTimeout(monitor, 15000) as? MainActivity
            val next = checkNotNull(recreated)
            assertNotSame(first, next)
            await(next, "재생성 후 같은 주유소 원결과") { hasReceipt(next) }
            read { game ->
                assertEquals(slot, ok(game.store.hostCommands.load(before.identity.profileId)))
                assertEquals(HostCheckpointResult.FacilityPurchase(purchased),
                    ok(game.store.hostCommands.facilityResult(before.identity.profileId, slot.command)))
                assertEquals(beforeRecreation, fuelPurchaseState(game))
            }
            click(next, "facility:ack")
            await(next, "확인 후 설치 표시와 재구매 선택 제거") {
                !hasReceipt(next) && showsEffect(next, "기존 설치 기록 · $effect") &&
                    next.window.decorView.findViewWithTag<Button>(previewTag) == null
            }
            read { game ->
                assertNull(ok(game.store.hostCommands.load(before.identity.profileId)))
                assertEquals(HostCheckpointResult.FacilityPurchase(purchased),
                    ok(game.store.hostCommands.facilityResult(before.identity.profileId, slot.command)))
                assertEquals(beforeRecreation, fuelPurchaseState(game))
            }
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        } finally {
            instrumentation.removeMonitor(monitor)
            recreated?.let { next -> instrumentation.runOnMainSync { next.finish() } }
        }
    }
}
