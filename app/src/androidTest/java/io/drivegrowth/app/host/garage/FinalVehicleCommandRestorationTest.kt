package io.drivegrowth.app.host.garage

import io.drivegrowth.app.exposeMainPage
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
import io.drivegrowth.app.data.HostCheckpointJson
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.data.LedgerJson
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.GachaPull
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.RentalCategory
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import org.json.JSONArray
import org.json.JSONObject

/** 네 셀렉터를 별도 프로세스에서 순서대로 실행한다. 첫 단계의 재화·차량과 수리용 사고는 공개 저장 API로 준비한다. */
@Suppress("DEPRECATION")
class FinalVehicleCommandRestorationTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val db get() = context.getDatabasePath("unified-simulation.db")
    private val marker get() = context.getSharedPreferences("final-vehicle-command-verification", Context.MODE_PRIVATE)
    private val target = OwnedVehicleId("initial-grant")
    private val casper = VehicleId("hyundai_casper")
    private val avante = VehicleId("hyundai_avante")
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to db.name), InputSource.SIMULATION)
    }
    private val root get() = activity.window.decorView

    fun testFullGarageBlocksPaidGachaThenCommitLevelUp() {
        requireIsolated()
        assertFalse("새 계측 전용 설치에서 시작해야 합니다", db.exists())
        assertFalse(marker.contains("pid"))
        val timeline = SimulationTimeline()
        val preferences = context.getSharedPreferences("unified-simulation-v1", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("stage"))
        assertTrue(preferences.edit().putInt("stage", timeline.stage).putLong("elapsed", timeline.elapsedMs).commit())
        val onboarding = StoredOnboardingHostSource(games)
        ok(onboarding.choose(ok(onboarding.read()).identity, casper))
        read { game ->
            val content = ok(game.content)
            ok(game.store.commitGameChange(GameChange(
                RequestId("final-vehicle-fixture-credit"), TransactionKind.GARAGE, game.identity.source,
                content.ruleVersion, MoneyChange(MoneyDirection.CREDIT, GameMoney(2_000)),
            )))
            fixtureGacha(game, "material", casper, Rarity.ADVANCED)
            fixtureGacha(game, "filler", avante, Rarity.COMMON)
        }

        val full = snapshot()
        assertEquals(3, full.capacity)
        assertEquals(3, full.vehicles.count { it.lifecycle == VehicleLifecycle.OWNED })
        assertTrue("잔액 부족과 구분할 수 있어야 합니다", full.balance.units >= 500)
        selectPage(HostPage.GACHA)
        assertFullGachaUi()
        click { it.tag == "host:refresh" }
        assertFullGachaUi()
        val rejected = read {
            GachaPull(ok(it.content)).prepare(it.store, RequestId("final-full-probe"), GachaTicketUse.PURCHASED)
        }
        assertTrue(rejected is ContractResult.Failure && rejected.error == ContractError.CAPACITY_EXCEEDED)
        assertEquals(full, snapshot())
        Log.i(TAG, "CAPACITY_BLOCKED pid=${Process.myPid()} owned=3 capacity=3 balance=${full.balance.units}")
        commitManagement(VehicleAction.LEVEL_UP, 20)
    }

    fun testColdLevelUpThenCommitMaterial() {
        restore(VehicleAction.LEVEL_UP)
        commitManagement(VehicleAction.USE_MATERIAL, 0)
    }

    fun testColdMaterialThenCommitRepair() {
        restore(VehicleAction.USE_MATERIAL)
        prepareDamagedCar()
        click { it.tag == "host:refresh" }
        commitManagement(VehicleAction.REPAIR, 50)
    }

    fun testColdRepairKeepsOriginalReceipt() {
        restore(VehicleAction.REPAIR)
    }

    private fun assertFullGachaUi() = awaitUi("가챠 만차 안내와 유료 선택 차단") {
        val paid = root.findViewWithTag<Button>("gacha:option:paid")
        paid?.isShown == true && !paid.isEnabled &&
            context.getString(R.string.gacha_status_space) in labels() &&
            context.getString(R.string.gacha_space_value, "3", "3") in labels() &&
            root.findViewWithTag<Button>("gacha:confirm") == null
    }

    private fun commitManagement(action: VehicleAction, debit: Long) {
        openVehicle(action)
        val before = snapshot()
        val original = before.vehicles.single { it.ownedVehicleId == target }
        assertNull(before.checkpoint)
        click { it.tag == action }
        val material = if (action == VehicleAction.USE_MATERIAL) {
            val expected = before.vehicles.single { it.ownedVehicleId != target && it.vehicleId == casper }
            // 아반떼는 같은 차종이 아니므로 후보 버튼 한 개만 있어야 한다.
            awaitLabel("같은 차종의 다른 보유·대기 차량을 선택해 주세요")
            click { candidate ->
                candidate.text.toString() != "취소" && commandButtons().count { it.text.toString() != "취소" } == 1 &&
                    candidate in commandButtons()
            }
            expected
        } else null
        val quote = when (action) {
            VehicleAction.LEVEL_UP -> "차감 20 게임머니"
            VehicleAction.USE_MATERIAL -> "비용 0 게임머니"
            VehicleAction.REPAIR -> "수리비 50 게임머니"
            else -> error("검증 범위 밖 차량 관리 행동")
        }
        awaitLabel(quote, prefix = true)
        assertEquals(before, snapshot())
        clickCommand("확정")
        awaitLabel("당시 확정 잔액 ${before.balance.units - debit} 게임머니")

        val after = snapshot()
        val saved = checkNotNull(after.checkpoint)
        val pending = checkNotNull(ok(CheckpointGarageCommandAdapter(context, sessions = games).load()))
        assertEquals(HostCheckpointPhase.STARTED, saved.phase)
        assertEquals(before.profile, saved.profileId)
        assertEquals(action, pending.request.action)
        assertEquals(target, pending.request.id)
        assertEquals(original.revision, pending.request.revision)
        val receipt = after.settlements.single { it.transaction.requestId == pending.command.requestId }
        assertEquals(before.settlements + receipt, after.settlements)
        assertEquals(if (debit == 0L) null else MoneyChange(MoneyDirection.DEBIT, GameMoney(debit)), receipt.transaction.money)
        assertEquals(GameMoney(before.balance.units - debit), after.balance)
        assertEquals(after.balance, receipt.balance)

        val revision = Revision(original.revision.value + 1)
        val changed = when (action) {
            VehicleAction.LEVEL_UP -> original.copy(level = 2, revision = revision)
            VehicleAction.USE_MATERIAL -> original.copy(level = 3, levelProgressPercent = 50, revision = revision)
            VehicleAction.REPAIR -> original.copy(revision = revision)
            else -> error("검증 범위 밖 차량 관리 행동")
        }
        val consumed = material?.copy(lifecycle = VehicleLifecycle.CONSUMED, revision = Revision(material.revision.value + 1))
        assertEquals(before.vehicles.map {
            when (it.ownedVehicleId) {
                target -> changed
                material?.ownedVehicleId -> checkNotNull(consumed)
                else -> it
            }
        }, after.vehicles)
        assertEquals(VehicleActivity.IDLE, after.targetActivity)
        assertEquals(before.targetAccidents, after.targetAccidents)
        assertEquals(before.gacha, after.gacha)
        assertEquals(before.discovered, after.discovered)
        assertEquals(before.capacity, after.capacity)
        assertTrue(marker.edit()
            .putInt("pid", Process.myPid())
            .putString("action", action.name)
            .putString("request", pending.command.requestId.value)
            .putString("receipt", receipt.settlementId.value)
            .putString("snapshot", after.digest())
            .commit())
        report("STARTED", action, after, receipt)
    }

    private fun restore(action: VehicleAction) {
        requireIsolated()
        assertTrue("직전 셀렉터가 만든 시연 DB가 필요합니다", db.exists())
        val priorPid = marker.getInt("pid", -1)
        assertTrue("이전 프로세스 ID가 필요합니다", priorPid > 0)
        assertTrue("같은 프로세스에서 복원했습니다", priorPid != Process.myPid())
        assertEquals(action.name, marker.getString("action", null))

        val before = snapshot()
        assertEquals(marker.getString("snapshot", null), before.digest())
        val saved = checkNotNull(before.checkpoint)
        val pending = checkNotNull(ok(CheckpointGarageCommandAdapter(context, sessions = games).load()))
        assertEquals(HostCheckpointPhase.STARTED, saved.phase)
        assertEquals(action, pending.request.action)
        assertEquals(marker.getString("request", null), pending.command.requestId.value)
        val receipt = before.settlements.single { it.transaction.requestId == pending.command.requestId }
        assertEquals(marker.getString("receipt", null), receipt.settlementId.value)

        val reviewRoute = "보관한 차량 관리 요청 확인"
        val recoverButton = "보관 요청 처리 · 결과 확인"
        val view = root
        awaitUi("원요청을 복원할 수 있는 Main 화면") {
            buttons(view).any { it.text.toString() == reviewRoute || it.text.toString() == recoverButton }
        }
        if (onUi { buttons(view).any { it.text.toString() == reviewRoute } }) {
            click { it.text.toString() == reviewRoute }
        }
        awaitLabel("보관한 요청입니다.", prefix = true)
        assertEquals(before, snapshot())
        clickCommand(recoverButton)
        awaitLabel("당시 확정 잔액 ${receipt.balance.units} 게임머니")
        assertEquals(before, snapshot())
        clickCommand("결과 확인")
        awaitStored("확정 결과를 확인한 뒤 공용 슬롯만 해제") { it.checkpoint == null }
        val expected = before.copy(checkpoint = null)
        assertEquals(expected, snapshot())

        openVehicle(VehicleAction.LEVEL_UP)
        val car = expected.vehicles.single { it.ownedVehicleId == target }
        awaitLabel("Lv.${car.level}")
        awaitLabel("레벨 진행량 · ${car.levelProgressPercent}%")
        assertEquals(expected, snapshot())
        report("RESTORED_FROM_$priorPid", action, expected, receipt)
    }

    private fun prepareDamagedCar() = read { game ->
        val content = ok(game.content)
        val rental = RequestId("final-vehicle-fixture-accident")
        val start = EpochMillis(SimulationTimeline().clock.epoch.value + 3_600_000)
        val assigned = ok(game.store.rental.assign(
            RentalRequest(rental, casper, 1), content.catalog,
            mapOf(casper to RentalCategory.SMALL, avante to RentalCategory.SEDAN),
            mapOf(casper to GameMoney(100)),
            RentalTerms(content.ruleVersion, SimulatedCustomer(30, 48), 0,
                start, 15, GameMoney(0), RoundingMode.DOWN, false),
        ))
        assertEquals(target, assigned)
        ok(game.store.rental.account(rental, start, EpochMillis(start.value + 3_600_000), null))
        assertTrue(ok(game.store.rental.settle(rental)).accident)
        assertEquals(VehicleActivity.NEEDS_REPAIR,
            ok(game.store.rental.finishReturn(rental, EpochMillis(start.value + 4_500_000))))
        assertEquals(1, ok(game.store.rental.accidentCount(target)))
        // 첫 대여가 만든 선물은 준비 중 확정하여 이후 Main의 비동기 지급과 겹치지 않게 한다.
        val gift = ok(FirstGrant(content).prepareOpeningGift(game.store, game.identity.profileId, game.identity.source))
        ok(game.store.commitFirstRentalGift(gift))
    }

    private fun fixtureGacha(game: GameSession, label: String, model: VehicleId, rarity: Rarity) {
        val pull = GachaPull(ok(game.content))
        val command = (0 until 2_000).asSequence().map {
            ok(pull.prepare(game.store, RequestId("final-vehicle-fixture-$label-$it"), GachaTicketUse.PURCHASED))
        }.first { it.award.vehicle.vehicleId == model && it.award.vehicle.rarity == rarity }
        val actual = checkNotNull(ok(game.store.commitGacha(command)).settlement.changedVehicle)
        assertEquals(command.award.vehicle, actual)
    }

    private data class Snapshot(
        val profile: ProfileId,
        val balance: GameMoney,
        val vehicles: List<OwnedVehicle>,
        val settlements: List<Settlement>,
        val checkpoint: HostCommandCheckpoint?,
        val gacha: GachaProgress,
        val discovered: Set<CollectionKey>,
        val capacity: Int,
        val targetActivity: VehicleActivity,
        val targetAccidents: Int,
    ) {
        fun digest(): String {
            val value = JSONObject()
                .put("profile", profile.value)
                .put("balance", balance.units)
                .put("vehicles", JSONArray(vehicles.map { LedgerJson.vehicle(it) }))
                .put("settlements", JSONArray(settlements.map { LedgerJson.settlement(it) }))
                .put("phase", checkpoint?.phase?.name)
                .put("command", checkpoint?.let { HostCheckpointJson.encode(it.profileId, it.command) })
                .put("gachaRevision", gacha.revision.value)
                .put("gachaCounter", gacha.drawsSinceGuarantee)
                .put("freeTickets", gacha.freeTickets)
                .put("discovered", JSONArray(discovered.map { "${it.vehicleId.value}/${it.rarity.name}" }.sorted()))
                .put("capacity", capacity)
                .put("activity", targetActivity.name)
                .put("accidents", targetAccidents)
            return MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun snapshot(): Snapshot = read { game ->
        val store = game.store
        val profile = game.identity.profileId
        Snapshot(profile, ok(store.balance()), ok(store.listVehicleHistory()).sortedBy { it.ownedVehicleId.value },
            ok(store.listUnviewedSettlements()), ok(store.hostCommands.load(profile)), ok(store.readGachaState()).progress,
            ok(store.discoveredVehicles()), ok(store.rental.shop()).capacity,
            ok(store.rental.activity(target)), ok(store.rental.accidentCount(target)))
    }

    private fun requireIsolated() {
        assertEquals("계측 전용 패키지에서만 실행합니다", "io.drivegrowth.verify.finalvehicle28", context.packageName)
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
    }

    private fun report(phase: String, action: VehicleAction, snapshot: Snapshot, receipt: Settlement) {
        Log.i(TAG, "$phase pid=${Process.myPid()} profile=${snapshot.profile.value} action=$action " +
            "request=${receipt.transaction.requestId.value} settlement=${receipt.settlementId.value} " +
            "balance=${snapshot.balance.units} digest=${snapshot.digest()}")
    }

    private fun selectPage(page: HostPage) {
        val view = root
        awaitUi("Main의 $page 라우트") {
            val button = view.exposeMainPage(page) ?: return@awaitUi false
            if (!button.isShown || !button.isEnabled) return@awaitUi false
            if (button.isSelected) return@awaitUi true
            assertTrue(button.performClick())
            false
        }
    }

    private fun openVehicle(action: VehicleAction) {
        selectPage(HostPage.GARAGE)
        awaitUi("차고의 대상 개체와 $action") {
            val garage = views(root).filterIsInstance<GarageView>().singleOrNull() ?: return@awaitUi false
            if (garage.selectedVehicleId == target) {
                return@awaitUi garage.findViewWithTag<Button>(action)?.isEnabled == true
            }
            val button = garage.findViewWithTag<Button>(target) ?: return@awaitUi false
            if (!button.isShown || !button.isEnabled) return@awaitUi false
            assertTrue(button.performClick())
            false
        }
    }

    private fun clickCommand(label: String) = click {
        it.text.toString() == label && it in commandButtons()
    }

    private fun click(predicate: (Button) -> Boolean) {
        val view = root
        awaitUi("현재 화면의 활성 버튼 입력") {
            val button = views(view).filterIsInstance<Button>()
                .firstOrNull { it.isShown && it.isAttachedToWindow && it.isEnabled && predicate(it) }
                ?: return@awaitUi false
            assertTrue(button.performClick())
            true
        }
    }

    private fun commandButtons() = views(root).filterIsInstance<GarageCommandView>().singleOrNull()
        ?.let { views(it).filterIsInstance<Button>().filter { button -> button.isShown }.toList() }.orEmpty()

    private fun buttons(view: View) = views(view).filterIsInstance<Button>().filter { it.isShown && it.isEnabled }
    private fun labels() = views(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
    private fun awaitLabel(text: String, prefix: Boolean = false) {
        val view = root
        awaitUi(text) {
            views(view).filterIsInstance<TextView>().any {
                it.isShown && if (prefix) it.text.toString().startsWith(text) else it.text.toString() == text
            }
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun awaitUi(label: String, condition: () -> Boolean) = await(label) { onUi(condition) }
    private fun awaitStored(label: String, condition: (Snapshot) -> Boolean) = await(label) { condition(snapshot()) }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        do {
            if (condition()) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < end)
        fail(label)
    }

    private fun <T> onUi(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun <T> read(action: (GameSession) -> T): T = ok(games.open()).use(action)
    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }

    private companion object { const val TAG = "FinalVehicleRestore" }
}
