package io.drivegrowth.app.ui.delivery

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.pixel.PixelStyle

internal class DeliveryStyle(private val context: Context) {
    private val layout = GarageStyle(context)
    val base get() = PixelStyle.background
    val ink get() = PixelStyle.text
    val muted get() = PixelStyle.muted

    fun dp(value: Int) = layout.dp(value)
    fun column() = layout.column()
    fun row() = layout.row()
    fun scroll(child: View, restoreY: Int) = layout.scroll(child, restoreY)
    fun add(parent: LinearLayout, child: View, gap: Int = PixelStyle.gapDp) = layout.add(parent, child, gap)

    fun text(value: CharSequence, size: Int = 16, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(1), 0, dp(1))
    }

    fun panel(padding: Int = PixelStyle.panelPaddingDp, fill: Int = PixelStyle.panel, border: Int = PixelStyle.outline) =
        column().apply {
            background = surface(fill, border, PixelStyle.panelRadiusDp)
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        }

    fun button(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        isAllCaps = false
        setTextColor(if (enabled) ink else muted)
        val fill = if (primary && enabled) PixelStyle.primary else PixelStyle.inset
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused), surface(fill, PixelStyle.focus, 14, 2))
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), surface(fill, PixelStyle.focus, 14, 2))
            addState(intArrayOf(), surface(fill, PixelStyle.outline, 14))
        }
        minHeight = dp(52)
        minimumHeight = dp(52)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isEnabled = enabled
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun surface(fill: Int, border: Int, radius: Int, stroke: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(stroke), border)
    }
}
