package io.drivegrowth.app.ui.garage

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.core.progression.Rarity

/** ui-v3의 배경·카드·버튼 색과 모서리를 공유한다. 크기는 09 명세의 초기 기준이다. */
internal class GarageStyle(val context: Context) {
    val base = Color.rgb(14, 16, 20)
    val panel = Color.rgb(26, 29, 35)
    val inset = Color.rgb(40, 44, 52)
    val ink = Color.rgb(242, 243, 245)
    val muted = Color.rgb(166, 172, 183)
    val blue = Color.rgb(48, 102, 255)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun text(value: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    fun shape(fill: Int, selected: Boolean = false) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(24).toFloat()
        if (selected) setStroke(dp(2), ink)
    }
    fun card(selected: Boolean = false) = column().apply {
        background = shape(panel, selected); setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        text = label; textSize = 24f; isAllCaps = false
        setTextColor(if (enabled) ink else muted)
        background = shape(if (primary && enabled) blue else inset)
        minHeight = dp(64); minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(16), dp(12)); isEnabled = enabled
        setOnClickListener { if (isEnabled) onClick() }
    }
    fun add(parent: LinearLayout, child: View, gap: Int = 12) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }
    fun scroll(child: View, restoreY: Int = 0) = object : ScrollView(context) {
        private var restoreOnLayout = true
        init { isFillViewport = true; clipToPadding = false; addView(child) }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (restoreOnLayout) { restoreOnLayout = false; scrollTo(0, restoreY) }
        }
    }
    fun rarity(value: Rarity?) = context.getString(when (value) {
        Rarity.COMMON -> R.string.garage_rarity_common
        Rarity.ADVANCED -> R.string.garage_rarity_advanced
        Rarity.RARE -> R.string.garage_rarity_rare
        Rarity.LEGEND -> R.string.garage_rarity_legend
        null -> R.string.garage_rarity_missing
    })
    fun rarityColor(value: Rarity?) = when (value) {
        Rarity.COMMON, null -> muted
        Rarity.ADVANCED -> Color.rgb(137, 186, 255)
        Rarity.RARE -> Color.rgb(196, 169, 255)
        Rarity.LEGEND -> Color.rgb(245, 207, 119)
    }
}
