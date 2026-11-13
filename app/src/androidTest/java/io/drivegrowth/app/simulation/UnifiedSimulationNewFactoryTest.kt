package io.drivegrowth.app.simulation

import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.host.HostPage
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryOffer
import java.time.Instant
import java.time.ZoneId

/** 새 simulator·HostSession·factory의 실제 원결과 복원 경계. 명시 SIMULATION이며 OS process kill 시험이 아니다. */
@Suppress("DEPRECATION")
class UnifiedSimulationNewFactoryTest : ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private fun <T> ok(result: ContractResult<T>): T { assertTrue(result.toString(), result is ContractResult.Success); return (result as ContractResult.Success).value }
    private fun tree(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { tree(v.getChildAt(it)) } else emptyList()
    private fun awaitUi(a: UnifiedSimulationActivity, label: String, check: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+90_000;var passed=false
        while (!passed && SystemClock.uptimeMillis()<end) {
            instrumentation.runOnMainSync { passed=check() }
            if (!passed) SystemClock.sleep(50)
        }
        assertTrue(label,passed)
    }
    private fun click(a: UnifiedSimulationActivity, tag: Any) {
        awaitUi(a,"활성 버튼 $tag") { a.window.decorView.findViewWithTag<Button>(tag)?.let { it.isShown && it.isEnabled } == true }
        instrumentation.runOnMainSync { assertTrue(a.window.decorView.findViewWithTag<Button>(tag)!!.performClick()) }
    }
    private fun factory(): GameSessionFactory {
        val clock={ val p=context.getSharedPreferences("unified-simulation-v1",0);SimulationTimeline(p.getLong("elapsed",0),p.getInt("stage",0)).clock.epoch.value }
        return GameSessionFactory(context,mapOf(InputSource.SIMULATION to "unified-simulation.db"),InputSource.SIMULATION,{
            when (val r=AssetTrialDefinitionProvider(context.assets).load()) {
                is ContractResult.Failure->r
                is ContractResult.Success->r.value.questDefinitions(ZoneId.of("UTC"))
            }
        },clock)
    }
    private fun <T> read(action:(GameSession)->T):T = ok(factory().open()).use(action)
    private fun awaitStored(label:String,check:()->Boolean) {
        val end=SystemClock.uptimeMillis()+90_000
        while (SystemClock.uptimeMillis()<end) { if(check())return;SystemClock.sleep(50) }
        fail(label)
    }
    private fun business(game: GameSession) = ok(game.store.atomic { db ->
        val state = ok(RentalProductStorage.read(db, game.identity))
        val rows = listOf("visits", "rentals").associateWith { table ->
            db.rawQuery("SELECT * FROM $table ORDER BY id", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    if (cursor.isNull(column)) null else cursor.getString(column)
                }) }
            }
        }
        // 정상 onStop의 inactive marker는 별도다. 방문/대여 전체 행과 schedule/pending을 비교한다.
        ContractResult.Success(Triple(state.cursor, state.pending, rows))
    })
    fun testSuccessfulTerminalSurvivesNewSimulatorHostAndFactoryWithoutNewBusinessOrSlotCleanup() {
        assertTrue(context.packageName != "io.drivegrowth.app")
        for (name in listOf("unified-simulation.db","game-ledger-vehicle.db","game-ledger-simulation.db"))
            assertFalse("fresh 격리 앱이 필요하며 DB를 지우지 않는다: $name",context.getDatabasePath(name).exists())
        assertFalse(context.getSharedPreferences("unified-simulation-v1",0).contains("stage"))
        val first=activity
        click(first,R.string.onboarding_start);click(first,VehicleId("hyundai_casper"))
        click(first,R.string.onboarding_review);click(first,R.string.onboarding_confirm)
        click(first,R.string.onboarding_to_shop)
        val initial=read { game ->
            assertEquals(InputSource.SIMULATION,game.identity.source)
            assertEquals(GameMoney(1000),ok(game.store.balance()))
            val state=ok(game.store.readInitialization(game.identity.profileId))
            val original=(state.status as ProfileInitializationStatus.Existing).original!!
            assertEquals(SimulationTimeline().clock.epoch,original.grant.confirmedAt)
            game.identity
        }
        awaitUi(first,"기존 화면 선택") { first.window.decorView.mainNavigation()?.isEnabled == true }
        instrumentation.runOnMainSync {
            first.window.decorView.openMainPage(HostPage.DELIVERY)
        }
        click(first,"simulation:destination")
        var offerTag:String?=null
        awaitUi(first,"기존 수익 의뢰 선택") {
            offerTag=tree(first.window.decorView).filterIsInstance<Button>().mapNotNull { it.tag as? String }.firstOrNull { it.startsWith("delivery:offer:") }
            offerTag!=null
        }
        val income=read { game ->
            val day=Instant.ofEpochMilli(SimulationTimeline().clock.epoch.value).atZone(ZoneId.of("UTC")).toLocalDate()
            ok(game.store.delivery.lists(day)).flatMap{it.offers}.filterIsInstance<DeliveryOffer.Income>().first()
        }
        click(first,"delivery:offer:${income.id.value}");click(first,"delivery:confirm");click(first,"delivery:acknowledge")
        val route=read { game ->checkNotNull(ok(game.store.delivery.inProgress())).also { assertEquals(initial.source,it.active.proposed.estimated.source) } }
        instrumentation.runOnMainSync { first.window.decorView.showSimulationControls() }
        click(first,"simulation:start")
        awaitUi(first,"시나리오 입력 종료와 모의 주차") {
            context.getSharedPreferences("unified-simulation-v1",0).getInt("stage",0)==65 &&
                first.window.decorView.mainNavigation()?.isEnabled == true
        }
        awaitStored("기존 원장의 세 정산과 첫 보상") { read { game ->
            val results=ok(game.store.listUnviewedSettlements())
            setOf(TransactionKind.DRIVING,TransactionKind.RENTAL,TransactionKind.DELIVERY).all { kind->results.any{it.transaction.kind==kind} } &&
                ok(game.store.readFirstRentalGift(initial.profileId)).granted!=null
        } }
        val saved=read { game ->
            assertEquals(initial,game.identity)
            val results=ok(game.store.listUnviewedSettlements())
            for(kind in listOf(TransactionKind.DRIVING,TransactionKind.RENTAL,TransactionKind.DELIVERY))
                assertEquals(kind.name,1,results.count{it.transaction.kind==kind})
            val gift=ok(game.store.readFirstRentalGift(initial.profileId));assertEquals(1L,gift.progress.freeTickets)
            assertTrue(ok(game.store.balance()).units>1000)
            assertNull(ok(game.store.delivery.inProgress()))
            assertEquals(route.route.value,context.getSharedPreferences("unified-simulation-v1",0).getString("arrivalRoute",null))
            assertEquals(listOf(VehicleId("hyundai_casper")),ok(game.store.listOwnedVehicles()).map{it.vehicleId})
            Triple(ok(game.store.balance()),results,gift)
        }
        val terminal = SimulationTimeline(
            context.getSharedPreferences("unified-simulation-v1", 0).getLong("elapsed", -1),
            context.getSharedPreferences("unified-simulation-v1", 0).getInt("stage", -1),
        )
        val oldHost = host(first)
        val pending = read { game -> checkNotNull(ok(game.store.hostCommands.load(initial.profileId))) }
        assertEquals("사용자가 아직 확인하지 않은 원래 슬롯", HostCheckpointPhase.STARTED, pending.phase)
        val command = pending.command as? HostCheckpointCommand.Delivery
        assertNotNull("실제 탁송 원명령이어야 한다", command)
        val arrival = command!!.operation as? DeliveryCheckpointOperation.Arrival
        assertNotNull("도착 입력이어야 한다", arrival)
        val original = read { game ->
            val value = checkNotNull(ok(game.store.delivery.arrivalResult(arrival!!.requestId)))
            assertNotNull(value.settlement)
            assertEquals(TransactionKind.DELIVERY, value.settlement!!.transaction.kind)
            assertTrue(saved.second.contains(value.settlement))
            assertTrue(ok(game.store.atomic { db ->
                ContractResult.Success(DeliveryCheckpointStorage.hasOriginal(db, command))
            }))
            value
        }
        val originalBusiness = read(::business)
        assertNull("확정 terminal의 영업 pending은 비어 있어야 한다", originalBusiness.second)
        Log.i("DriveGrowthNewFactory", "before_close identity=$initial clock=${terminal.clock} slot=$pending original=$original balance=${saved.first}")
        instrumentation.runOnMainSync { first.finish() }
        instrumentation.waitForIdleSync()
        awaitStored("이전 Activity 종료") { first.isDestroyed }
        assertTrue(first.isFinishing)
        val fresh = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UnifiedSimulationActivity
        try {
            val freshHost = host(fresh)
            assertNotSame("새 HostSession", oldHost, freshHost)
            assertNotSame("새 simulator", oldHost.simulation, freshHost.simulation)
            assertNotSame("새 factory", oldHost.games, freshHost.games)
            val simulator = checkNotNull(freshHost.simulation)
            awaitUi(fresh, "새 owner의 입력 처리 응답") { simulator.controlsAllowed || simulator.canRetry }
            Log.i("DriveGrowthNewFactory", "after_reopen allowed=${simulator.controlsAllowed} retry=${simulator.canRetry} status=${simulator.status}")
            // UI 허용 단언 전에 먼저 원래 결과/정상 확인 대기 슬롯의 보존을 검사한다.
            read { game ->
                assertEquals(initial, game.identity)
                assertEquals(saved.first, ok(game.store.balance()))
                assertEquals("방문/대여 행과 schedule/pending 보존", originalBusiness, business(game))
                assertEquals(saved.second, ok(game.store.listUnviewedSettlements()))
                assertEquals(saved.third, ok(game.store.readFirstRentalGift(initial.profileId)))
                assertEquals(pending, ok(game.store.hostCommands.load(initial.profileId)))
                assertEquals(original, ok(game.store.delivery.arrivalResult(arrival!!.requestId)))
                assertTrue(ok(game.store.atomic { db ->
                    ContractResult.Success(DeliveryCheckpointStorage.hasOriginal(db, command))
                }))
                assertEquals(listOf(VehicleId("hyundai_casper")), ok(game.store.listOwnedVehicles()).map { it.vehicleId })
                assertNull(ok(game.store.delivery.inProgress()))
            }
            assertEquals(ContractResult.Success(terminal.clock), simulator.clock())
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
            assertFalse(context.getDatabasePath("game-ledger-simulation.db").exists())
            Log.i("DriveGrowthNewFactory", "original_pending_and_business_preserved before_controls_assertion")
            assertFalse("확정 입력 재연결이 실패로 바뀌지 않아야 한다: ${simulator.status}", simulator.canRetry)
            assertTrue("확정 결과를 UI에서 확인할 수 있어야 한다: ${simulator.status}", simulator.controlsAllowed)
            awaitUi(fresh, "새 owner의 기존 화면 조작") {
                fresh.window.decorView.mainNavigation()?.isEnabled == true
            }
            instrumentation.runOnMainSync {
                fresh.window.decorView.openMainPage(HostPage.DELIVERY)
            }
            awaitUi(fresh, "원래 도착 결과의 사용자 확인 버튼") {
                fresh.window.decorView.findViewWithTag<Button>("delivery:acknowledge")?.let { it.isShown && it.isEnabled } == true
            }
            read { game ->
                assertEquals(saved.first, ok(game.store.balance()))
                assertEquals("방문/대여 행과 schedule/pending 보존", originalBusiness, business(game))
                assertEquals(saved.second, ok(game.store.listUnviewedSettlements()))
                assertEquals(pending, ok(game.store.hostCommands.load(initial.profileId)))
            }
            Log.i("DriveGrowthNewFactory", "fresh_owner_original_visible_and_no_new_settlement_or_slot_cleanup")
        } finally { instrumentation.runOnMainSync { fresh.finish() } }
    }

    private fun host(activity: UnifiedSimulationActivity): HostSession = MainActivity::class.java
        .getDeclaredField("session").apply { isAccessible = true }.get(activity) as HostSession
}
