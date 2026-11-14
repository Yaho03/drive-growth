package io.drivegrowth.app.simulation

import io.drivegrowth.app.mainGameControls
import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.host.HostPage
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.drivegrowth.app.R
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryOffer
import java.time.Instant
import java.time.ZoneId

/** 시연 런처의 실제 기존 UI/공개 명령/SQLite. 입력·도착은 명시 SIMULATION, SDK 실행 시험이 아니다. */
@Suppress("DEPRECATION")
class UnifiedSimulationActivityTest : ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
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
    fun testOneStartRunsExistingUiDrivingRentalDeliveryAndReopenedOriginalResults() {
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
        awaitUi(first,"기존 화면 선택") { first.window.decorView.mainGameControls()?.isEnabled == true }
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
        instrumentation.runOnMainSync { first.window.decorView.showSimulationControls(first) }
        click(first,"simulation:start")
        awaitUi(first,"시나리오 입력 종료와 모의 주차") {
            context.getSharedPreferences("unified-simulation-v1",0).getInt("stage",0)==65 &&
                first.window.decorView.mainGameControls()?.isEnabled == true
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
        val monitor=instrumentation.addMonitor(UnifiedSimulationActivity::class.java.name,null,false)
        var next:UnifiedSimulationActivity?=null
        try {
            instrumentation.runOnMainSync { first.recreate() }
            next=instrumentation.waitForMonitorWithTimeout(monitor,15_000) as? UnifiedSimulationActivity
            assertNotNull(next)
            awaitUi(next!!,"재생성 후 모의 상태/기존 UI") { next!!.window.decorView.mainGameControls()?.isEnabled == true }
            read { game ->
                assertEquals(initial,game.identity);assertEquals(saved.first,ok(game.store.balance()))
                assertEquals(saved.second,ok(game.store.listUnviewedSettlements()))
                assertEquals(saved.third,ok(game.store.readFirstRentalGift(initial.profileId)))
            }
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
            assertFalse(context.getDatabasePath("game-ledger-simulation.db").exists())
        } finally { instrumentation.removeMonitor(monitor);next?.let { a->instrumentation.runOnMainSync{a.finish()} } }
    }
}
