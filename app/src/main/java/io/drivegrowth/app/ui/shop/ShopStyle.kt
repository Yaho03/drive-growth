package io.drivegrowth.app.ui.shop

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.PixelStyle

/** #229의 공통 색과 기존 차량 화면의 Native 조작 크기를 사용한다. */
internal class ShopStyle(private val context: Context) {
    val base = PixelStyle.background
    val panel = PixelStyle.panel
    val inset = PixelStyle.inset
    val ink = PixelStyle.text
    val muted = PixelStyle.muted
    val blue = PixelStyle.primary
    val accent = PixelStyle.gold
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun shape(fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(PixelStyle.panelRadiusDp).toFloat() }
    fun text(value: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    fun card() = column().apply {
        background = shape(panel); setPadding(dp(PixelStyle.panelPaddingDp), dp(16), dp(PixelStyle.panelPaddingDp), dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 24f; isAllCaps = false; isEnabled = enabled
        setTextColor(if (enabled) ink else muted)
        background = shape(if (primary && enabled) blue else inset)
        minHeight = dp(64); minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { if (isEnabled) action() }
    }
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
