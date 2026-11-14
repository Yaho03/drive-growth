package io.drivegrowth.app.ui.progress

import io.drivegrowth.app.ui.pixel.CleanUi
import android.content.Context
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.MapPanelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon

/** 진행과 기록 화면에 공용 도트 패널을 적용한다. */
internal class ProgressStyle(private val context: Context) {
    private val hud = ReferenceHudStyle(context)
    private val panels = MapPanelStyle(context)
    val base = CleanUi.CARD_SOFT
    val ink get() = hud.signInk
    val muted get() = panels.muted
    val green get() = panels.accent
    val amber = 0xffb5650a.toInt()

    fun icon(icon: ReferenceHudIcon) = hud.icon(icon, 28)
    fun surface(selected: Boolean = false) = hud.detailSurface(selected)
    fun track(percent: Int) = panels.experienceTrack(percent)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun text(label: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = label; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    fun card(selected: Boolean = false) = column().apply {
        background = if (selected) surface(true) else hud.signSurface()
        val padding = dp(PixelStyle.panelPaddingDp)
        setPadding(padding, dp(16), padding, dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 22f; isAllCaps = false; isEnabled = enabled
        setTextColor(if (!enabled) muted else if (primary) android.graphics.Color.WHITE else ink)
        backgroundTintList = null
        background = hud.signButton(primary)
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(64); minimumHeight = dp(64); setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { if (isEnabled) action() }
    }
    fun add(parent: LinearLayout, child: android.view.View, gap: Int = PixelStyle.gapDp) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }
}
