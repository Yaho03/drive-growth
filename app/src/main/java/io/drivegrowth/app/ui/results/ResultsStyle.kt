package io.drivegrowth.app.ui.results

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.MapPanelStyle

/** 정산의 목록과 상세에서 공용 픽셀 팔레트와 여백을 사용한다. */
internal class ResultsStyle(private val context: Context) {
    private val hud = ReferenceHudStyle(context)
    private val panels = MapPanelStyle(context)
    val base = CleanUi.CARD_SOFT
    val ink get() = hud.signInk
    val muted get() = panels.muted
    val accent get() = panels.accent

    fun surface(selected: Boolean = false) = hud.detailSurface(selected)
    fun icon(icon: ReferenceHudIcon) = hud.icon(icon, 28)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun text(value: CharSequence, size: Int = 18, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }
    fun card(selected: Boolean = false, inset: Boolean = false, paddingDp: Int = 12) = column().apply {
        background = if (selected || inset) surface(selected) else hud.signSurface()
        val padding = dp(paddingDp)
        setPadding(padding, padding, padding, padding)
    }
    fun chip(value: String) = text(value, 16, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(32)
        background = surface()
        setPadding(dp(12), dp(2), dp(12), dp(2))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 20f; isAllCaps = false; isEnabled = enabled
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(hud.featureButtonText(primary))
        backgroundTintList = null
        background = hud.signButton(primary)
        minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { if (isEnabled) action() }
    }
    fun add(parent: LinearLayout, child: View, gap: Int = PixelStyle.gapDp) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }
    fun scroll(body: View, restoreY: Int) = object : ScrollView(context) {
        private var restoreOnLayout = true
        init { addView(body) }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (restoreOnLayout) { restoreOnLayout = false; scrollTo(0, restoreY) }
        }
    }
}
