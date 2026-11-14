package io.drivegrowth.app.ui.presentation

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.drivegrowth.app.ui.gacha.GachaEvent
import io.drivegrowth.app.ui.gacha.GachaOrigin
import io.drivegrowth.app.ui.gacha.GachaScreen
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.app.host.ranking.RankingState
import io.drivegrowth.app.ui.gacha.GachaPreviewStates
import io.drivegrowth.app.ui.gacha.GachaView
import io.drivegrowth.app.ui.garage.GaragePreviewStates
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.app.ui.ranking.RankingView
import io.drivegrowth.app.ui.results.ResultsPreviewStates
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.shop.ShopPreviewStates
import io.drivegrowth.app.ui.shop.ShopScreenState
import io.drivegrowth.app.ui.shop.ShopView

@Suppress("DEPRECATION")
class StablePageRenderDeviceTest : InstrumentationTestCase() {
    fun testSameSupplyKeepsViewsButRestrictionReplacesThem() {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val context = instrumentation.targetContext
                val garage = GarageView(context) {}
                val results = ResultsView(context) {}
                val ranking = RankingView(context) {}
                val gacha = GachaView(context, onEvent = {})
                val result = GachaPreviewStates.result()
                retainsChildren(garage) { garage.render(GaragePreviewStates.normal, it) }
                retainsChildren(results) { results.render(ResultsPreviewStates.normal, it) }
                retainsChildren(ranking) { ranking.render(RankingState.Loading, it) }
                retainsChildren(gacha) { gacha.render(result, it) }
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    fun testShopRefreshRetainsOnlyTheMapWhileRealFailureAndRestrictionReplaceIt() {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val view = ShopView(instrumentation.targetContext) { fail("조회 중 거래가 요청됐다") }
                val supply = ShopPreviewStates.normal()
                var settingsOpened = 0
                view.updateMapHud(onSettings = { settingsOpened++ })
                view.render(supply, true)
                val previousSettings = checkNotNull(view.findViewWithTag<View>("shop:map-settings"))
                view.render(ShopScreenState.Loading, true)
                val loading = checkNotNull(view.findViewWithTag<ViewGroup>("shop:loading-map"))
                assertNull(view.findViewWithTag<View>("shop:map-screen"))
                assertNull(view.findViewWithTag<View>("shop:map-settings"))
                assertNull(view.findViewWithTag<View>("shop:map-facility:INSURANCE"))
                for (vehicle in supply.vehicles) {
                    assertNull(view.findViewWithTag<View>("shop:map-vehicle:${vehicle.id.value}"))
                }
                assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, loading.descendantFocusability)
                val scene = checkNotNull(loading.findViewWithTag<View>("shop:scene"))
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, scene.importantForAccessibility)
                view.measure(View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 860, 420)
                assertTrue(scene.width > 0)
                val actions = listOf(MotionEvent.ACTION_DOWN to 80f, MotionEvent.ACTION_MOVE to 280f,
                    MotionEvent.ACTION_UP to 280f)
                for ((step, input) in actions.withIndex()) {
                    val (action, x) = input
                    val event = MotionEvent.obtain(0L, step * 200L, action, x, 90f, 0)
                    try {
                        assertFalse("조회 중 지도는 드래그·주민 입력을 받지 않는다", scene.dispatchTouchEvent(event))
                        assertTrue("조회 중 표면이 배경 터치를 막는다", loading.dispatchTouchEvent(event))
                    } finally {
                        event.recycle()
                    }
                }
                previousSettings.performClick()
                assertEquals(0, settingsOpened)
                view.render(supply, true)
                assertNull(view.findViewWithTag<View>("shop:loading-map"))
                val returnedSettings = checkNotNull(view.findViewWithTag<View>("shop:map-settings"))
                assertNotSame(previousSettings, returnedSettings)
                assertTrue(returnedSettings.performClick())
                assertEquals(1, settingsOpened)
                view.render(ShopScreenState.Loading, true)
                assertNotNull(view.findViewWithTag<View>("shop:loading-map"))
                view.render(ShopScreenState.Failure("시험 조회 실패"), true)
                assertNull(view.findViewWithTag<View>("shop:loading-map"))
                view.render(supply, true)
                view.render(ShopScreenState.Loading, false)
                assertNull(view.findViewWithTag<View>("shop:loading-map"))
                view.render(ShopScreenState.Loading, true)
                assertNull(view.findViewWithTag<View>("shop:loading-map"))
                view.render(supply, true)
                assertNotNull(view.findViewWithTag<View>("shop:map-screen"))
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    fun testSameGachaReceiptSwitchesToStaticAndLaysOutAfterReattach() {
        val activity = instrumentation.startActivitySync(Intent(
            instrumentation.targetContext, CollectionScrollTestActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var enabled = true
        val events = mutableListOf<GachaEvent>()
        lateinit var view: GachaView
        lateinit var holder: FrameLayout
        val supplied = GachaPreviewStates.result()
        val result = (supplied.screen as GachaScreen.Result).value.copy(origin = GachaOrigin.STORED)
        val frame = supplied.copy(screen = GachaScreen.Result(result))
        try {
            onUi {
                view = GachaView(activity, onEvent = events::add).apply { animationsEnabled = { enabled } }
                holder = FrameLayout(activity)
                activity.setContentView(holder)
                place(holder, view, 720)
                view.render(GachaPreviewStates.ready(), true)
                assertTrue(view.findViewWithTag<View>("gacha:option:paid").performClick())
                assertTrue(view.findViewWithTag<View>("gacha:confirm").performClick())
                view.render(frame, true)
                assertNotNull(view.findViewWithTag<View>("gacha:reveal-scene"))
                enabled = false
                view.render(frame, true)
                assertNull(view.findViewWithTag<View>("gacha:reveal-scene"))
                assertNotNull(view.findViewWithTag<View>("gacha:garage"))
                holder.removeView(view)
                assertFalse(view.isAttachedToWindow)
                place(holder, view, 320)
                view.render(frame, true)
            }
            instrumentation.waitForIdleSync()
            onUi {
                val button = checkNotNull(view.findViewWithTag<View>("gacha:garage"))
                assertTrue(view.isAttachedToWindow && view.isLaidOut && button.width > 0 && button.height > 0)
                assertNull(view.findViewWithTag<View>("gacha:reveal-scene"))
                assertEquals(1, events.filterIsInstance<GachaEvent.Draw>().size)
            }
        } finally {
            onUi { activity.finish() }
        }
    }

    private fun place(holder: FrameLayout, view: View, widthDp: Int) {
        val density = holder.resources.displayMetrics.density
        holder.addView(view, FrameLayout.LayoutParams((widthDp * density).toInt(), (440 * density).toInt()))
    }

    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun retainsChildren(view: ViewGroup, render: (Boolean) -> Unit) {
        render(true)
        assertTrue(view.childCount > 0)
        val before = (0 until view.childCount).map(view::getChildAt)
        render(true)
        assertEquals(before.size, view.childCount)
        before.forEachIndexed { index, child -> assertSame(child, view.getChildAt(index)) }
        render(false)
        assertNotSame(before.first(), view.getChildAt(0))
    }
}
