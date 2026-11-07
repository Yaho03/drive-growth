package io.drivegrowth.app.ui.results

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 기존 Garage와 ui-v3의 카드·색·여백을 따른다. 새로운 자산은 사용하지 않는다. */
internal class ResultsStyle(private val context: Context) {
    val base = Color.rgb(14, 16, 20)
    val panel = Color.rgb(26, 29, 35)
    val inset = Color.rgb(40, 44, 52)
    val ink = Color.rgb(242, 243, 245)
    val muted = Color.rgb(166, 172, 183)
    val blue = Color.rgb(48, 102, 255)
    val accent = Color.rgb(245, 207, 119)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun shape(fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(24).toFloat() }
    fun text(value: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    fun card() = column().apply {
        background = shape(panel); setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 24f; isAllCaps = false; isEnabled = enabled
        setTextColor(if (enabled) ink else muted)
        background = shape(if (primary && enabled) blue else inset)
        minHeight = dp(64); minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { if (isEnabled) action() }
    }
    fun add(parent: LinearLayout, child: View, gap: Int = 12) {
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
