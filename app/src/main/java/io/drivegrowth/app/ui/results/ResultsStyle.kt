package io.drivegrowth.app.ui.results

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.PixelStyle

/** 정산의 목록과 상세에서 공용 픽셀 팔레트와 여백을 사용한다. */
internal class ResultsStyle(private val context: Context) {
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
    fun shape(fill: Int, highlighted: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(PixelStyle.panelRadiusDp).toFloat()
        setStroke(dp(if (highlighted) 2 else 1), if (highlighted) PixelStyle.focus else PixelStyle.outline)
    }
    fun text(value: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    fun card(selected: Boolean = false) = column().apply {
        background = shape(panel, selected)
        val padding = dp(PixelStyle.panelPaddingDp)
        setPadding(padding, padding, padding, padding)
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 24f; isAllCaps = false; isEnabled = enabled
        setTextColor(if (enabled) ink else muted)
        val fill = if (primary && enabled) blue else inset
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, highlighted = true))
            addState(intArrayOf(), shape(fill))
        }
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
