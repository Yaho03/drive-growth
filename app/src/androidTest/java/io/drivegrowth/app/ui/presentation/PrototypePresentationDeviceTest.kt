package io.drivegrowth.app.ui.presentation

import android.app.Dialog
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.content.Intent
import android.content.res.Configuration
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
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
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        host = instrumentation.startActivitySync(Intent(context,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as CollectionScrollTestActivity
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
        }
        awaitTitleWindow(title)
        if (ValueAnimator.areAnimatorsEnabled()) {
            onUi {
                title.setPresentationAllowed(true)
                title.performClick()
                assertEquals("화면 전환이 끝나기 전에는 시작하지 않는다", 0, starts)
                title.setPresentationAllowed(false)
            }
            SystemClock.sleep(540L)
            onUi { assertEquals("전환 중 허용이 철회되면 시작하지 않는다", 0, starts) }
        }
        awaitTitleWindow(title)
        onUi {
            val button = title.findViewWithTag<Button>("presentation:start")
            title.setPresentationAllowed(true)
            assertTrue("활성 창의 시작 버튼이 입력을 받아야 한다", button.isEnabled)
            button.performClick()
            title.performClick()
        }
        val deadline = SystemClock.uptimeMillis() + 2000L
        while (onUi { starts } == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30L)
        onUi {
            assertEquals("같은 시작 전환은 한 번만 반영된다", 1, starts)
            title.setPresentationAllowed(false)
            title.findViewWithTag<Button>("presentation:start").performClick()
            title.performClick()
            assertEquals("시작 버튼과 타이틀 배경 모두 제한 후 동작하지 않아야 한다", 1, starts)
        }
    }

    fun testAcceptedTitleTapFinishesAfterTransientWindowFocusLoss() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        var starts = 0
        var animationTime = 1L
        val title = onUi { PrototypeTitleView(host, uptimeMs = { animationTime }) { starts++ }
            .also { host.setContentView(it) } }
        awaitTitleWindow(title)
        val interruption = onUi {
            title.setPresentationAllowed(true)
            assertTrue(title.findViewWithTag<Button>("presentation:start").performClick())
            Dialog(host).apply {
                setContentView(TextView(host).apply { text = "창 포커스 전환" })
                show()
            }
        }
        try {
            val deadline = SystemClock.uptimeMillis() + 2000L
            while (onUi { title.hasWindowFocus() } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20L)
            onUi {
                assertFalse("별도 창에서 포커스를 가져가야 한다", title.hasWindowFocus())
                animationTime += 500L
            }
            instrumentation.waitForIdleSync()
            onUi { assertEquals("포커스가 없으면 시작 콜백을 보류한다", 0, starts) }
        } finally {
            onUi { interruption.dismiss() }
        }
        awaitTitleWindow(title)
        val deadline = SystemClock.uptimeMillis() + 2000L
        while (onUi { starts } == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30L)
        onUi { assertEquals("처음 접수한 시작을 복귀 뒤 한 번만 완료한다", 1, starts) }
    }

    private fun awaitTitleWindow(title: PrototypeTitleView) {
        val deadline = SystemClock.uptimeMillis() + 2000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (onUi { title.isAttachedToWindow && title.isShown && title.hasWindowFocus() }) return
            SystemClock.sleep(30L)
        }
        fail("시작 화면이 활성 창을 받지 못했습니다")
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
                for ((line, expected) in listOf(first to "르르르", second to "렌터카")) {
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

    fun testStartInstructionHonorsEnlargedFontInShortWindow() = onUi {
        val largeContext = host.createConfigurationContext(Configuration(host.resources.configuration).apply {
            fontScale = 2f
        })
        fun startAtSize(context: android.content.Context): Button {
            val density = context.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val height = (210 * density).toInt()
            val title = PrototypeTitleView(context) {}
            title.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            title.layout(0, 0, width, height)
            val button = title.findViewWithTag<Button>("presentation:start")
            val textLayout = checkNotNull(button.layout)
            val ribbon = title.findViewWithTag<View>("presentation:title-ribbon")
            assertTrue(button.top >= ribbon.bottom && button.bottom <= height)
            assertTrue(textLayout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            assertTrue((0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
            assertTrue(button.textSize >= TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics))
            return button
        }
        assertTrue(startAtSize(largeContext).textSize > startAtSize(host).textSize)
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
