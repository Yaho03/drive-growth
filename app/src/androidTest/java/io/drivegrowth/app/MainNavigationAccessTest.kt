package io.drivegrowth.app

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import io.drivegrowth.app.host.HostNavigation
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
class MainNavigationAccessTest : InstrumentationTestCase() {
    fun testOldNavigationCannotActOnAnotherPageOrAfterParkingReturns() {
        assertTrue(instrumentation.targetContext.packageName.startsWith("io.drivegrowth.verify."))
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val main = instrumentation.startActivitySync(intent, options) as MainActivity
        val task = FutureTask {
            assertFalse("제어 입력은 실행 중인 Activity에만 공급합니다", main.isDestroyed)
            val root = main.window.decorView
            val interaction = field<ForegroundInteraction>(main, "interaction")
            val controller = field<GarageCommandHost>(main, "management").controller
            val onboarding = field<HostSession>(main, "session").onboarding
            val navigation = field<HostNavigation>(main, "navigation")
            val update = MainActivity::class.java.getDeclaredMethod("updateAccess").apply { isAccessible = true }
            val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            val openHomeSettings = MainActivity::class.java.getDeclaredMethod("openHomeSettings").apply { isAccessible = true }
            MainActivity::class.java.getDeclaredField("recoveredManagement").apply { isAccessible = true }
                .setBoolean(main, true)
            val queuedRefresh = onboarding.javaClass.getDeclaredField("refreshRequested").apply { isAccessible = true }
            fun gate(gear: Gear) {
                // 한 UI 작업 안에서는 실제 SDK 콜백이 끼어들지 않는다. 각 입력 경계에 관측을 공급한다.
                interaction.reset()
                val now = SystemClock.elapsedRealtime()
                val sampleAges = if (gear == Gear.PARK) listOf(3200L, 1600L, 0L) else listOf(0L)
                for (age in sampleAges) interaction.accept(VehicleObservation(InputSource.VEHICLE, now - age,
                    EpochMillis(now - age), Observation.Available(if (gear == Gear.PARK) 0.0 else 5.0),
                    Observation.Available(gear), Observation.Available(gear != Gear.PARK)))
                update.invoke(main)
            }
            fun mainButton(page: HostPage) = checkNotNull(root.mainPage(page))
            fun settingsButton(tag: String) = checkNotNull(root.findViewWithTag<Button>(tag))
            fun assertIgnored(stale: List<Button>) {
                assertFalse(field<Boolean>(controller, "busy"))
                assertTrue(field<Boolean>(onboarding, "inFlight"))
                gate(Gear.PARK)
                assertEquals("입력 전 실제 화면은 차고여야 합니다", HostPage.GARAGE, navigation.page)
                assertTrue(root.isMainPageShown(HostPage.GARAGE))
                assertNull(root.mainNavigation())
                queuedRefresh.setBoolean(onboarding, false)
                for (button in stale) {
                    gate(Gear.PARK)
                    assertFalse("${button.tag}는 이전 화면에서 분리된 버튼이어야 합니다", button.isAttachedToWindow)
                    assertTrue(button.performClick())
                    assertEquals("${button.tag}가 현재 페이지를 바꾸면 안 됩니다", HostPage.GARAGE, navigation.page)
                    assertNull(root.mainNavigation())
                    assertTrue(root.isMainPageShown(HostPage.GARAGE))
                    assertFalse("이전 메뉴가 확정 요청을 해소하면 안 됩니다", field<Boolean>(controller, "busy"))
                    assertFalse("이전 새로고침이 조회를 예약하면 안 됩니다", queuedRefresh.getBoolean(onboarding))
                    assertTrue(controller.state is GarageCommandState.Confirmed)
                }
            }

            MainActivity::class.java.getDeclaredField("active").apply { isAccessible = true }.setBoolean(main, true)
            MainActivity::class.java.getDeclaredField("titlePending").apply { isAccessible = true }.setBoolean(main, false)
            gate(Gear.PARK)
            render.invoke(main)
            val home = checkNotNull(root.mainNavigation())
            assertEquals(
                listOf(HostPage.GARAGE, HostPage.COLLECTION, HostPage.GACHA, HostPage.DELIVERY,
                    HostPage.RESULTS, HostPage.RANKING),
                (home as android.view.ViewGroup).let { bar ->
                    (0 until bar.childCount).map { HostPage.valueOf(bar.getChildAt(it).tag.toString().substringAfterLast(':')) }
                },
            )
            for (page in listOf(HostPage.COLLECTION, HostPage.RANKING, HostPage.RESULTS, HostPage.GARAGE,
                HostPage.DELIVERY, HostPage.GACHA)) {
                gate(Gear.PARK)
                val visibleBack = root.findViewWithTag<View>("host:back")
                assertFalse(visibleBack?.isShown == true)
                root.openMainPage(page)
                assertTrue(root.isMainPageShown(page))
                assertNull(root.mainNavigation())
                main.onBackPressed()
                assertTrue(root.isMainPageShown(HostPage.HOME))
            }

            val homeScroll = checkNotNull(root.mainNavigation()).parent as android.widget.HorizontalScrollView
            val width = (320 * main.resources.displayMetrics.density).toInt()
            homeScroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            homeScroll.layout(0, 0, width, homeScroll.measuredHeight)
            val ranking = mainButton(HostPage.RANKING)
            assertTrue("320dp 창에서 기록 버튼 전체가 보입니다", ranking.right <= homeScroll.width)
            assertTrue(ranking.performClick())
            assertTrue(root.isMainPageShown(HostPage.RANKING))
            main.onBackPressed()
            assertTrue(root.isMainPageShown(HostPage.HOME))

            gate(Gear.PARK)
            openHomeSettings.invoke(main)
            val refreshFromSettings = settingsButton("host:refresh")
            val progressionFromSettings = settingsButton("host:page:PROGRESSION")
            assertTrue(progressionFromSettings.performClick())
            assertEquals(HostPage.PROGRESSION, navigation.page)
            assertNull(root.findViewWithTag<View>("host:settings-panel"))
            main.onBackPressed()
            assertTrue(root.isMainPageShown(HostPage.HOME))

            gate(Gear.PARK)
            val beforeRestriction = mainButton(HostPage.COLLECTION)
            gate(Gear.DRIVE)
            assertNull(root.mainNavigation())
            gate(Gear.PARK)
            render.invoke(main)
            val active = mainButton(HostPage.COLLECTION)
            assertNotSame(active, beforeRestriction)
            render.invoke(main)
            assertSame(active, mainButton(HostPage.COLLECTION))
            assertTrue(active.performClick())
            assertTrue(root.isMainPageShown(HostPage.COLLECTION))
            main.onBackPressed()
            assertTrue(root.isMainPageShown(HostPage.HOME))

            gate(Gear.PARK)
            openHomeSettings.invoke(main)
            assertTrue(settingsButton("host:refresh").performClick())
            assertTrue("현재 새로고침은 진행 중 조회 뒤에 다시 조회합니다", queuedRefresh.getBoolean(onboarding))
            assertNull("새로고침이 설정을 닫아야 합니다", root.findViewWithTag<View>("host:settings-panel"))
            assertTrue(root.isMainPageShown(HostPage.HOME))

            val beforePageChange = mainButton(HostPage.GACHA)
            root.openMainPage(HostPage.GARAGE)
            gate(Gear.PARK)
            controller.javaClass.getDeclaredMethod("show", GarageCommandState::class.java)
                .apply { isAccessible = true }.invoke(controller, confirmedFixture())
            // 이 fixture는 디스크에 없는 결과다. 이전 메뉴가 결과를 해소하지 않는지만 검사한다.
            assertIgnored(listOf(beforePageChange, beforeRestriction, refreshFromSettings, progressionFromSettings))
        }
        try {
            instrumentation.runOnMainSync(task)
            task.get()
        } finally {
            instrumentation.runOnMainSync { if (!main.isDestroyed) main.finish() }
        }
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
