package io.drivegrowth.app.ui.progress

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** ui-v3의 색·둥근 카드. 이미지·캐릭터 자산을 추가하지 않는다. */
internal class ProgressStyle(private val context: Context) {
    val base = Color.rgb(14, 16, 20)
    val panel = Color.rgb(26, 29, 35)
    val inset = Color.rgb(40, 44, 52)
    val ink = Color.rgb(242, 243, 245)
    val muted = Color.rgb(166, 172, 183)
    val blue = Color.rgb(48, 102, 255)
    val green = Color.rgb(52, 211, 153)
    val amber = Color.rgb(245, 207, 119)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(24).toFloat() }
    fun text(label: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = label; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    fun card() = column().apply {
        background = shape(panel); setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 22f; isAllCaps = false; isEnabled = enabled
        setTextColor(if (enabled) ink else muted); background = shape(if (primary && enabled) blue else inset)
        minHeight = dp(64); minimumHeight = dp(64); setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { if (isEnabled) action() }
    }
    fun add(parent: LinearLayout, child: android.view.View, gap: Int = 12) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }
}
