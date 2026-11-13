package io.drivegrowth.app

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.widget.Button
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.host.garage.GarageCommand
import io.drivegrowth.app.host.garage.GarageCommandHost
import io.drivegrowth.app.host.garage.GarageCommandState
import io.drivegrowth.app.host.garage.GaragePending
import io.drivegrowth.app.host.garage.GarageReceipt
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.vehicle.ForegroundInteraction
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleAction
import java.util.concurrent.FutureTask

/** Main의 실제 View와 조작 gate에 시험용 관측·확정 상태를 공급한다. */
@Suppress("DEPRECATION")
class MainNavigationAccessTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testOldNavigationCannotActOnAnotherPageOrAfterParkingReturns() {
        assertTrue(instrumentation.targetContext.packageName.startsWith("io.drivegrowth.verify."))
        val main = activity
        val task = FutureTask {
            val root = main.window.decorView
            val interaction = field<ForegroundInteraction>(main, "interaction")
            val controller = field<GarageCommandHost>(main, "management").controller
            val onboarding = field<HostSession>(main, "session").onboarding
            val update = MainActivity::class.java.getDeclaredMethod("updateAccess").apply { isAccessible = true }
            val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            MainActivity::class.java.getDeclaredField("recoveredManagement").apply { isAccessible = true }
                .setBoolean(main, true)
            val queuedRefresh = onboarding.javaClass.getDeclaredField("refreshRequested").apply { isAccessible = true }
            fun gate(gear: Gear) {
                interaction.reset()
                val now = SystemClock.elapsedRealtime()
                val sampleAges = if (gear == Gear.PARK) listOf(3200L, 1600L, 0L) else listOf(0L)
                for (age in sampleAges) interaction.accept(VehicleObservation(InputSource.VEHICLE, now - age,
                    EpochMillis(now - age), Observation.Available(if (gear == Gear.PARK) 0.0 else 5.0),
                    Observation.Available(gear), Observation.Available(gear != Gear.PARK)))
                update.invoke(main)
            }
            fun captureButtons() = listOf(
                checkNotNull(root.findViewWithTag<Button>("host:back")),
                checkNotNull(root.findViewWithTag<Button>("host:refresh")),
                checkNotNull(root.mainPage(HostPage.COLLECTION)),
            )
            fun assertIgnored(stale: List<Button>) {
                assertFalse(field<Boolean>(controller, "busy"))
                assertTrue(field<Boolean>(onboarding, "inFlight"))
                queuedRefresh.setBoolean(onboarding, false)
                stale.forEach { assertTrue(it.performClick()) }
                assertFalse("이전 Back이 확정 요청을 해소하면 안 됩니다", field<Boolean>(controller, "busy"))
                assertFalse("이전 새로고침이 조회를 예약하면 안 됩니다", queuedRefresh.getBoolean(onboarding))
                assertTrue("이전 페이지 버튼이 현재 차고를 바꾸면 안 됩니다", root.mainPage(HostPage.GARAGE)?.isSelected == true)
                assertTrue(controller.state is GarageCommandState.Confirmed)
            }

            gate(Gear.PARK)
            render.invoke(main)
            val home = checkNotNull(root.mainNavigation())
            assertEquals(
                listOf(HostPage.HOME, HostPage.GARAGE, HostPage.GACHA, HostPage.RESULTS, HostPage.DELIVERY).toSet(),
                HostPage.entries.filter { home.mainPage(it) != null }.toSet(),
            )
            root.openMainPage(HostPage.GARAGE)
            val collectionTab = checkNotNull(root.findViewWithTag<Button>("host:section:COLLECTION"))
            assertTrue(collectionTab.performClick())
            assertTrue(root.mainPage(HostPage.COLLECTION)?.isSelected == true)
            root.openMainPage(HostPage.HOME)
            root.openMainPage(HostPage.RESULTS)
            for (page in listOf(HostPage.PROGRESSION, HostPage.RANKING, HostPage.RESULTS)) {
                val tab = checkNotNull(root.findViewWithTag<Button>("host:section:${page.name}"))
                assertTrue(tab.performClick())
                assertTrue(root.mainPage(page)?.isSelected == true)
            }
            root.openMainPage(HostPage.HOME)
            root.openMainPage(HostPage.COLLECTION)
            val beforePageChange = captureButtons()
            root.openMainPage(HostPage.GARAGE)
            controller.javaClass.getDeclaredMethod("show", GarageCommandState::class.java)
                .apply { isAccessible = true }.invoke(controller, confirmedFixture())
            assertIgnored(beforePageChange)

            val beforeRestriction = captureButtons()
            gate(Gear.DRIVE)
            assertNull(root.mainNavigation())
            gate(Gear.PARK)
            render.invoke(main)
            val active = captureButtons()
            active.zip(beforeRestriction).forEach { (current, previous) -> assertNotSame(current, previous) }
            render.invoke(main)
            assertSame(active[0], root.findViewWithTag<Button>("host:back"))
            assertIgnored(beforeRestriction)

            assertTrue(active[1].performClick())
            assertTrue("현재 새로고침은 진행 중 조회 뒤에 다시 조회합니다", queuedRefresh.getBoolean(onboarding))
            assertTrue(active[2].performClick())
            assertTrue(root.mainPage(HostPage.COLLECTION)?.isSelected == true)
            assertTrue(root.findViewWithTag<Button>("host:back").performClick())
            assertTrue(root.mainPage(HostPage.HOME)?.isSelected == true)
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }

    private fun confirmedFixture(): GarageCommandState.Confirmed {
        val id = OwnedVehicleId("navigation-test-car")
        val change = GameChange(RequestId("navigation-test-command"), TransactionKind.GARAGE, InputSource.VEHICLE,
            RuleVersion("test"), vehicle = VehicleWrite.Update(id, Revision(0), 2, VehicleLifecycle.OWNED, 0))
        val request = GarageEvent.RequestAction(id, Revision(0), VehicleAction.LEVEL_UP)
        val pending = GaragePending(request, ContentVersion("test"), GarageCommand.Single(change))
        val vehicle = OwnedVehicle(id, VehicleId("test"), 2, Revision(1), VehicleLifecycle.OWNED,
            Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
        val transaction = Transaction(TransactionId("navigation-test-tx"), change.requestId, change.kind,
            change.source, change.ruleVersion, change.money)
        return GarageCommandState.Confirmed(pending, GarageReceipt.Single(
            Settlement(SettlementId("navigation-test-settlement"), transaction, GameMoney(0), vehicle, EpochMillis(1))))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
}
