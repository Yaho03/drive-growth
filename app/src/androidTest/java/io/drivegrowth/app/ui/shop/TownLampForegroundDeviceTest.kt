package io.drivegrowth.app.ui.shop

import android.graphics.BitmapFactory
import android.graphics.Color
import android.test.InstrumentationTestCase

class TownLampForegroundDeviceTest : InstrumentationTestCase() {
    fun testGrowthBackgroundsKeepTheThirdLampRoofWithoutCapturingAsphalt() {
        val assets = instrumentation.targetContext.assets
        for (art in listOf(TownGrowthArt.LEVEL_1, TownGrowthArt.LEVEL_2, TownGrowthArt.LEVEL_3)) {
            val image = assets.open(art.assetPath).use { checkNotNull(BitmapFactory.decodeStream(it)) }
            val pieces = try {
                TownLampForeground.extract(image, art)
            } finally {
                image.recycle()
            }
            try {
                val lamp = pieces.single { it.left == 1188f && it.top == 886f }.bitmap
                assertTrue("$art 가로등 윗면", Color.alpha(lamp.getPixel(20, 31)) > 0)
                assertEquals("$art 윗면 밖 아스팔트", 0, Color.alpha(lamp.getPixel(14, 31)))
            } finally {
                pieces.forEach { it.bitmap.recycle() }
            }
        }
    }
}
