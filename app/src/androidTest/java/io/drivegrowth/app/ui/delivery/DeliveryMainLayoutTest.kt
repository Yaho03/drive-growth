package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.host.HostPage
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.garage.CollectionKey
import java.time.Instant
import java.time.ZoneOffset

/** 기본 Pleos 창의 실제 Main 배치와 모의 탁송 후보를 확인한다. 새 격리 Debug 설치가 필요하다. */
@Suppress("DEPRECATION")
class DeliveryMainLayoutTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {

    fun testPurchaseCardFieldsAndDetailsAreReadableInDefaultMainWindow() {
        val context = instrumentation.targetContext
        assertTrue(context.packageName != "io.drivegrowth.app")
        assertFalse(context.getDatabasePath("unified-simulation.db").exists())
        val simulation = context.getSharedPreferences("unified-simulation-v1", 0)
        assertFalse(simulation.contains("stage"))

        val main = activity
        val root = main.window.decorView
        await("기본 앱 창 배치") { root.isLaidOut }
        onUi {
            assertEquals("기본 화면 밀도", 120, main.resources.displayMetrics.densityDpi)
            assertEquals("기본 앱 창 너비", 702, root.width)
            assertEquals("기본 앱 창 높이", 438, root.height)
        }
        for (tag in listOf(R.string.onboarding_start, VehicleId("hyundai_casper"), R.string.onboarding_review,
            R.string.onboarding_confirm, R.string.onboarding_to_shop)) {
            await("초기 지급 버튼 $tag") { root.findViewWithTag<Button>(tag)?.isEnabled == true }
            onUi { assertTrue(checkNotNull(root.findViewWithTag<Button>(tag)).performClick()) }
        }

        await("Main 화면 탐색") { root.mainNavigation()?.isEnabled == true }
        onUi {
            root.openMainPage(HostPage.DELIVERY)
        }
        // Main의 최초 조회가 Destination을 그린 뒤에만 확인한다. 조회 중에는 refresh가 합쳐질 수 있다.
        await("시연 목적지 확인 전 Main의 최초 탁송 조회 완료") {
            if (laidOutButton(root, "delivery:destination")?.isEnabled != true) return@await false
            val confirm = laidOutButton(root, "simulation:destination")?.takeIf { it.isEnabled } ?: return@await false
            assertTrue("시연 목적지 확인 버튼 전체 표시", fullyVisible(confirm))
            assertFalse("확인 전 시연 목적지", simulation.getBoolean("destinationConfirmed", false))
            val revision = simulation.getLong("destinationRevision", 0)
            assertTrue("시연 목적지 확인 callback 호출", confirm.performClick())
            assertTrue("Main 목적지 확인 callback의 저장 결과", simulation.getBoolean("destinationConfirmed", false))
            assertEquals("시연 목적지 확인 세대", revision + 1, simulation.getLong("destinationRevision", -1))
            Log.i("DeliveryMainLayoutTest", "destinationConfirmed=true revision=${revision + 1}")
            true
        }

        await("구매 탭 열기") {
            val tab = laidOutButton(root, "delivery:tab:purchase") ?: return@await false
            tab.isSelected || tab.performClick()
        }
        await("시연 목적지 확인 저장 뒤 구매 후보 표시") {
            views(root).filterIsInstance<TextView>().any { it.text.toString().startsWith("구매 결제 예정") }
        }
        val factory = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        val offer = firstPurchase(factory)
        val expectedName = ok(factory.open()).use { game ->
            ok(game.content).catalog.vehicles.single { it.vehicleId == offer.vehicleId }.displayName
        }
        val expectedFields = listOf(expectedName, GarageStyle(main).rarity(offer.rarity),
            main.getString(R.string.delivery_money, main.getString(R.string.delivery_purchase_payment), offer.price.units))
        // 후보의 첫 표시 뒤에는 발견 저장을 위해 화면이 Loading을 한 번 더 거친다.
        awaitDiscovery(factory, offer)
        val detailsTag = "delivery:offer:${offer.id.value}"
        await("현재 구매 카드의 배치") {
            if (laidOutButton(root, detailsTag) == null) return@await false
            val card = root.findViewWithTag<View>("delivery:offer-card:${offer.id.value}") ?: return@await false
            val labels = views(card).filterIsInstance<TextView>().toList()
            if (!isLaidOutIn(root, card) || labels.any { !isLaidOutIn(root, it) }) return@await false
            val fields = expectedFields.map { field -> labels.single { it.text.toString() == field } }
            val scroll = scrollIntoView(fields.first())
            assertTrue("구매 카드 3필드 동시 가시성: 뷰포트 ${scroll.height}px, " +
                "차종·희귀도·가격이 온전히 보여야 한다", fields.all(::fullyVisible))
            true
        }

        touchDetail(root, detailsTag)
        await("탭한 구매 후보의 상세와 확인 버튼") {
            val close = laidOutButton(root, "delivery:back") ?: return@await false
            val confirm = laidOutButton(root, "delivery:confirm") ?: return@await false
            val parent = close.parent as ViewGroup
            assertSame("확인 버튼은 현재 상세 본문에 있어야 한다", parent, confirm.parent)
            val card = root.findViewWithTag<View>("delivery:review-vehicle") ?: return@await false
            val facts = root.findViewWithTag<View>("delivery:review-facts") ?: return@await false
            if (!isLaidOutIn(root, card) || !isLaidOutIn(root, facts)) return@await false
            val labels = views(card).filterIsInstance<TextView>()
            val fields = expectedFields.map { field -> labels.single { it.text.toString() == field } }
            assertTrue("기본 가로 창에서 확정 조건과 버튼이 함께 보여야 한다",
                (fields + listOf(facts, close, confirm)).all(::fullyVisible))
            main.onBackPressed()
            true
        }
        await("상세 Back 뒤 구매 목록") { laidOutButton(root, detailsTag) != null }
    }

    private fun firstPurchase(factory: GameSessionFactory): DeliveryOffer.Purchase {
        val timeline = SimulationTimeline()
        val day = Instant.ofEpochMilli(timeline.clock.epoch.value).atOffset(ZoneOffset.UTC).toLocalDate()
        return ok(factory.open()).use { game ->
            ok(game.store.delivery.lists(day)).flatMap { it.offers }.filterIsInstance<DeliveryOffer.Purchase>().first()
        }
    }

    private fun awaitDiscovery(factory: GameSessionFactory, offer: DeliveryOffer.Purchase) {
        val key = CollectionKey(offer.vehicleId, offer.rarity)
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ok(factory.open()).use { key in ok(it.store.discoveredVehicles()) }) return
            SystemClock.sleep(50)
        }
        fail("표시한 구매 후보의 발견 저장")
    }

    private fun fullyVisible(view: View): Boolean {
        val bounds = Rect()
        return view.getGlobalVisibleRect(bounds) && bounds.height() == view.height && bounds.width() == view.width
    }

    private fun scrollIntoView(view: View): ScrollView {
        val scroll = generateSequence(view.parent) { it.parent }.filterIsInstance<ScrollView>().first()
        val target = IntArray(2)
        val viewport = IntArray(2)
        view.getLocationOnScreen(target)
        scroll.getLocationOnScreen(viewport)
        scroll.scrollTo(0, scroll.scrollY + target[1] - viewport[1])
        return scroll
    }

    private data class TouchTarget(val viewIdentity: Int, val x: Int, val y: Int, val scrollY: Int)

    private fun touchDetail(root: View, tag: String) {
        val automation = checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        var previous: TouchTarget? = null
        val received = mutableListOf<Int>()
        await("상세 버튼의 위치와 스크롤이 안정됨: $tag") {
            val button = laidOutButton(root, tag)?.takeIf { it.isEnabled && it.isClickable }
            if (button == null || !fullyVisible(button)) {
                button?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
                previous = null
                return@await false
            }
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            val scroll = generateSequence(button.parent) { it.parent }.filterIsInstance<ScrollView>().single()
            val current = TouchTarget(System.identityHashCode(button), location[0] + button.width / 2,
                location[1] + button.height / 2, scroll.scrollY)
            val stable = previous == current
            previous = current
            if (!stable) return@await false

            val hit = Rect()
            button.getHitRect(hit)
            assertTrue("상세 버튼 중심이 부모의 hit rect 안에 있어야 한다",
                hit.contains(button.left + button.width / 2, button.top + button.height / 2))
            button.setOnTouchListener { _, event ->
                received.add(event.actionMasked)
                false
            }
            Log.i("DeliveryMainLayoutTest", "detailTarget tag=$tag $current hit=$hit")
            true
        }

        val target = checkNotNull(previous)
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input tap ${target.x} ${target.y}"),
        ).bufferedReader().use { it.readText() }
        instrumentation.waitForIdleSync()
        onUi {
            val button = root.findViewWithTag<Button>(tag)
            val currentIdentity = button?.let { System.identityHashCode(it) }
            if (currentIdentity == target.viewIdentity) button?.setOnTouchListener(null)
            Log.i("DeliveryMainLayoutTest", "detailTouch tag=$tag actions=$received " +
                "targetIdentity=${target.viewIdentity} currentIdentity=$currentIdentity output=$output")
            assertEquals("상세 버튼에 시스템 DOWN·UP 전달: $tag, 대상 교체=${currentIdentity != target.viewIdentity}",
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), received)
        }
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun laidOutButton(root: View, tag: String): Button? =
        root.findViewWithTag<Button>(tag)?.takeIf { isLaidOutIn(root, it) }

    private fun isLaidOutIn(root: View, view: View): Boolean =
        view.isAttachedToWindow && view.rootView === root && view.isShown &&
            generateSequence(view) { it.parent as? View }.all { it.isLaidOut && !it.isLayoutRequested }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onUi(predicate)) return
            SystemClock.sleep(50)
        }
        fail(label)
    }

    private fun <T> onUi(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
