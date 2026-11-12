package io.drivegrowth.app.ui.pixel

import io.drivegrowth.core.contract.Rarity

/** #229 시안의 공용 화면 색과 기본 간격. */
internal object PixelStyle {
    val background = 0xff151a28.toInt()
    val panel = 0xff121622.toInt()
    val inset = 0xff1d2335.toInt()
    val outline = 0xff2c3349.toInt()
    val text = 0xffffffff.toInt()
    val muted = 0xffaab3c5.toInt()
    val primary = 0xff2f6fe8.toInt()
    val focus = 0xff9cc2ff.toInt()
    val gold = 0xfffcdc64.toInt()

    const val gapDp = 12
    const val panelPaddingDp = 18
    const val panelRadiusDp = 22

    fun rarityColor(rarity: Rarity): Int = when (rarity) {
        Rarity.COMMON -> 0xffd6dce6.toInt()
        Rarity.ADVANCED -> 0xff68a0f0.toInt()
        Rarity.RARE -> 0xfff08caa.toInt()
        Rarity.LEGEND -> gold
    }
}
