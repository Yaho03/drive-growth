package io.drivegrowth.app.ui.delivery

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle

internal class DeliveryStyle(private val context: Context, private val creamPanel: Boolean = false) {
    private val layout = GarageStyle(context, pixelPanel = true, creamPanel = creamPanel)
    val base get() = layout.base
    val ink get() = layout.ink
    val muted get() = layout.muted
    val inset get() = layout.inset
    val accent get() = layout.blue
    val moneyInk get() = if (creamPanel) 0xff70450d.toInt() else PixelStyle.gold

    fun heading(label: CharSequence) = layout.heading(label, ReferenceHudIcon.TRUCK)

    private val hud = ReferenceHudStyle(context)

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

    fun insetSurface() = layout.shape(inset)

    fun panel(padding: Int = PixelStyle.panelPaddingDp, fill: Int = layout.panel, selected: Boolean = false) =
        column().apply {
            background = layout.shape(fill, selected)
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        }

    fun chip(label: String, fill: Int, color: Int) = text(label, 15, color, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(32)
        setPadding(dp(12), dp(2), dp(12), dp(2))
        background = if (creamPanel) this@DeliveryStyle.layout.shape(fill) else surface(fill, fill, 16)
    }

    fun button(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit): Button {
        if (creamPanel) return layout.button(label, enabled, primary, onClick)
        return Button(context).apply {
            text = label
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setTextColor(if (primary && enabled) hud.signInk else if (enabled) ink else muted)
            val fill = PixelStyle.outline
            background = if (primary && enabled) hud.signButton() else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused), surface(fill, PixelStyle.focus, 14, 2))
                addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), surface(fill, PixelStyle.focus, 14, 2))
                addState(intArrayOf(), surface(fill, fill, 14))
            }
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            alpha = if (enabled) 1f else .55f
            isEnabled = enabled
            setOnClickListener { if (isEnabled) onClick() }
        }
    }

    private fun surface(fill: Int, border: Int, radius: Int, stroke: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(stroke), border)
    }
}
