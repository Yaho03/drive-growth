package io.drivegrowth.app.ui.shop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TownLampForegroundTest {
    private val asphalt = 0xff60637f.toInt()
    private val outline = 0xff1b1d2b.toInt()
    private val pole = 0xff62668a.toInt() // 아스팔트와 비슷한 밝기의 기둥 면

    /** 10×12 상자 가운데에 윤곽선으로 둘러싼 기둥(x 3..6, y 1..10)을 그린다. */
    private fun scene(gapAt: Pair<Int, Int>? = null): IntArray {
        val width = 10
        val pixels = IntArray(width * 12) { asphalt }
        for (y in 1..10) for (x in 3..6) {
            val edge = x == 3 || x == 6 || y == 1 || y == 10
            pixels[y * width + x] = if (edge) outline else pole
        }
        gapAt?.let { (x, y) -> pixels[y * width + x] = asphalt }
        return pixels
    }

    @Test fun keepsOutlinedPoleEvenWhenItsColourMatchesAsphalt() {
        val mask = TownLampForeground.mask(scene(), 10, 12)
        for (y in 1..10) for (x in 3..6) assertTrue("기둥 ($x,$y)", mask[y * 10 + x])
        for (y in 0 until 12) for (x in listOf(0, 1, 2, 7, 8, 9)) assertFalse("아스팔트 ($x,$y)", mask[y * 10 + x])
        assertFalse(mask[0 * 10 + 4])
        assertFalse(mask[11 * 10 + 4])
    }

    @Test fun fillsRowsWhereAGapInTheOutlineLetsTheOutsideLeakIn() {
        // 윗변 윤곽선에 틈을 내면 바깥 채우기가 기둥 위쪽 안으로 새어 들어온다.
        val mask = TownLampForeground.mask(scene(gapAt = 4 to 1), 10, 12)
        for (y in 2..10) for (x in 3..6) assertTrue("기둥 ($x,$y)", mask[y * 10 + x])
        for (x in listOf(0, 1, 2, 7, 8, 9)) assertFalse(mask[5 * 10 + x])
    }
}
