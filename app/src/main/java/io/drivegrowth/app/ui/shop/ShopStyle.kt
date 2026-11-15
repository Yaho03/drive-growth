package io.drivegrowth.app.ui.shop

import io.drivegrowth.app.ui.presentation.setGameButtonClick
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.MapPanelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle

internal fun String.displayShopSign() = when (this) {
    "기본 간판 · 시연" -> "기본 간판"
    "간판 색상 · 시연" -> "간판 색상"
    else -> this
}

/** #229의 공통 색과 기존 차량 화면의 Native 조작 크기를 사용한다. */
internal class ShopStyle(private val context: Context) {
    private val mapPanel = MapPanelStyle(context)
    val base = CleanUi.CARD_SOFT
    val page = CleanUi.CARD_SOFT
    val ink get() = mapPanel.ink
    val muted get() = mapPanel.muted
    val accent get() = mapPanel.accent
    val scrim = CleanUi.SCRIM
    fun surface() = mapPanel.card()
    fun infoSurface() = mapPanel.chip()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun shape(fill: Int) = ReferenceHudStyle(context).panel(fill)
    fun text(value: CharSequence, size: Int = 16, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    fun card() = column().apply {
        background = surface(); setPadding(dp(PixelStyle.panelPaddingDp), dp(16), dp(PixelStyle.panelPaddingDp), dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) =
        mapPanel.styleButton(Button(context).apply {
            text = label
            isAllCaps = false
            isEnabled = enabled
            setGameButtonClick(action = action)
        }, primary)
    fun add(parent: LinearLayout, child: View, gap: Int = PixelStyle.gapDp) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }
    fun scroll(body: View, restoreY: Int) = object : ScrollView(context) {
        private var restoreOnLayout = true
        init { isFillViewport = true; addView(body) }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (restoreOnLayout) { restoreOnLayout = false; scrollTo(0, restoreY) }
        }
    }
}
