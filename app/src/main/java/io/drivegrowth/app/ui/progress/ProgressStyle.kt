package io.drivegrowth.app.ui.progress

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.PixelStyle

/** #229의 공용 색과 패널로 진행·기록 화면을 표시한다. */
internal class ProgressStyle(private val context: Context) {
    val base = PixelStyle.background
    val inset = PixelStyle.inset
    val ink = PixelStyle.text
    val muted = PixelStyle.muted
    val green = 0xff34d399.toInt()
    val amber = PixelStyle.gold

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun shape(color: Int, outline: Int = PixelStyle.outline) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(PixelStyle.panelRadiusDp).toFloat()
        setStroke(dp(1).coerceAtLeast(1), outline)
    }
    fun text(label: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = label; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    fun card(outline: Int = PixelStyle.outline) = column().apply {
        background = shape(PixelStyle.panel, outline)
        val padding = dp(PixelStyle.panelPaddingDp)
        setPadding(padding, dp(16), padding, dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 22f; isAllCaps = false; isEnabled = enabled
        setTextColor(if (enabled) ink else muted)
        background = shape(if (primary && enabled) PixelStyle.primary else inset,
            if (primary && enabled) PixelStyle.focus else PixelStyle.outline)
        minHeight = dp(64); minimumHeight = dp(64); setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { if (isEnabled) action() }
    }
    fun add(parent: LinearLayout, child: android.view.View, gap: Int = PixelStyle.gapDp) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }
}
