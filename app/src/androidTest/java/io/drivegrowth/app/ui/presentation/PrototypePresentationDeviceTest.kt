package io.drivegrowth.app.ui.presentation

import android.content.Intent
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.garage.VehicleArtwork
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.CollectionState

/** 새 표현의 제한·수명·작은 창을 실제 View에서 확인한다. 원장이나 차량 신호는 변경하지 않는다. */
@Suppress("DEPRECATION")
class PrototypePresentationDeviceTest : InstrumentationTestCase() {
    private lateinit var host: CollectionScrollTestActivity

    override fun setUp() {
        super.setUp()
        host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CollectionScrollTestActivity
    }

    override fun tearDown() {
        if (::host.isInitialized) onUi { host.finish() }
        super.tearDown()
    }

    fun testTitleCannotStartBeforePermissionOrAfterRevocation() {
        var starts = 0
        val title = onUi { PrototypeTitleView(host) { starts++ }.also { host.setContentView(it) } }
        onUi {
            val button = title.findViewWithTag<Button>("presentation:start")
            button.performClick()
            title.performClick()
            assertEquals(0, starts)
            title.setPresentationAllowed(true)
            button.performClick()
            title.performClick()
            assertEquals(2, starts)
            title.setPresentationAllowed(false)
            button.performClick()
            title.performClick()
            assertEquals("시작 버튼과 타이틀 배경 모두 제한 후 동작하지 않아야 한다", 2, starts)
        }
    }

    fun testTitleStartRemainsInsideNarrowAndLandscapeWindows() {
        onUi {
            val title = PrototypeTitleView(host) {}
            title.setPresentationAllowed(true)
            val density = host.resources.displayMetrics.density
            for ((widthDp, heightDp) in listOf(320 to 210, 320 to 320, 936 to 430, 840 to 1076, 1360 to 840)) {
                val w = (widthDp * density).toInt()
                val h = (heightDp * density).toInt()
                title.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                title.layout(0, 0, w, h)
                val button = title.findViewWithTag<Button>("presentation:start")
                assertTrue(button.width > 0 && button.height > 0)
                assertTrue(button.left >= 0 && button.right <= w)
                assertTrue(button.top >= 0 && button.bottom <= h)
                val badge = title.findViewWithTag<TextView>("presentation:title-badge")
                val first = title.findViewWithTag<TextView>("presentation:title-first")
                val second = title.findViewWithTag<TextView>("presentation:title-second")
                val ribbon = title.findViewWithTag<TextView>("presentation:title-ribbon")
                assertTrue(badge.bottom <= first.top)
                assertTrue(first.left < second.left && first.top < second.top)
                assertTrue(second.bottom < ribbon.top && ribbon.bottom < button.top)
                assertEquals("화면을 눌러 시작  ▶", button.text.toString())
                for ((line, expected) in listOf(first to "드라이브", second to "키우기")) {
                    assertEquals(expected, line.text.toString())
                    val glyphs = Rect().also { line.paint.getTextBounds(expected, 0, expected.length, it) }
                    val top = line.baseline + glyphs.top
                    val bottom = line.baseline + glyphs.bottom
                    assertTrue("제목 글자 영역이 잘립니다 ($widthDp × $heightDp dp)",
                        !glyphs.isEmpty && top >= 0 && bottom <= line.height &&
                            glyphs.right <= line.width && line.left >= 0 && line.right <= w)
                }
            }
        }
    }

    fun testTitleDetachAndReattachRetainsRestriction() {
        onUi {
            var starts = 0
            val parent = FrameLayout(host)
            host.setContentView(parent)
            val title = PrototypeTitleView(host) { starts++ }
            parent.addView(title)
            title.setPresentationAllowed(true)
            title.setPresentationAllowed(false)
            parent.removeView(title)
            parent.addView(title)
            title.findViewWithTag<Button>("presentation:start").performClick()
            assertEquals(0, starts)
        }
    }

    fun testVehicleCardOmitsRingAndDiscoveredCollectionKeepsIt() {
        onUi {
            val art = VehicleArtwork(host, GarageStyle(host, pixelPanel = true))
            val id = VehicleId("hyundai_casper")
            assertTrue(art.hasSprite(id))
            assertEquals(1, art.view(id, Rarity.COMMON, 88).childCount)
            assertEquals(2, art.view(id, Rarity.COMMON, 88, CollectionState.DISCOVERED).childCount)
        }
    }

    private fun <T> onUi(block: () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
}
