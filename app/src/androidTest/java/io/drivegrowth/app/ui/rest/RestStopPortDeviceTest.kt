package io.drivegrowth.app.ui.rest

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.test.InstrumentationTestCase
import android.view.View
import io.drivegrowth.app.ui.progress.BuffPhase
import io.drivegrowth.app.ui.progress.ProgressScreenState
import io.drivegrowth.app.ui.progress.ProgressView
import io.drivegrowth.app.ui.progress.RestPresentation
import io.drivegrowth.core.driving.RestOutcome

@Suppress("DEPRECATION")
class RestStopPortDeviceTest : InstrumentationTestCase() {
    fun testPackagedRestAnimationAndEffectTracksAreReadable() {
        val assets = instrumentation.targetContext.assets
        for (part in RestStopMotionView.Part.entries) {
            val seconds = if (part == RestStopMotionView.Part.INTRO) 6 else 10
            val base = "pixel/rest/blanket-${part.asset}"
            val image = RestStopMotionView.imageAsset(part)
            assertEquals("pixel/rest/rururu-${part.asset}.webp", image)
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, image))
            assertTrue("$part 움직임 프레임", drawable is AnimatedImageDrawable)
            assertEquals(340, drawable.intrinsicWidth)
            assertEquals(237, drawable.intrinsicHeight)
            val sound = assets.open("$base-sound.pcm").use { it.readBytes() }
            assertEquals(seconds * 22_050 * 4, sound.size)
            assertTrue(sound.any { it != 0.toByte() })
        }
    }

    fun testOnlySuppliedCurrentRestConditionsShowAnimation() {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val view = ProgressView(instrumentation.targetContext) { fail("휴식 장면이 게임 요청을 보냈다") }
                val cases = listOf(
                    RestPresentation(RestOutcome.ACCEPTED, BuffPhase.NEXT_DEPARTURE) to true,
                    RestPresentation(RestOutcome.PARKING_TOO_SHORT, BuffPhase.INACTIVE) to true,
                    RestPresentation(null, BuffPhase.ACTIVE) to false,
                    RestPresentation(null, BuffPhase.UNCONFIRMED) to false,
                    RestPresentation(null, BuffPhase.UNSUPPORTED) to false,
                    null to false,
                )
                for ((index, case) in cases.withIndex()) {
                    val (rest, visible) = case
                    val content = ProgressScreenState.Content(index.toLong(), emptyList(), "모의 입력", rest = rest)
                    assertTrue(view.render(content, true))
                    assertEquals(visible, view.findViewWithTag<View>("rest-stop:scene") != null)
                    assertTrue(view.render(content, false))
                    assertNull(view.findViewWithTag<View>("rest-stop:scene"))
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }
}
