package io.drivegrowth.app.ui.garage

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.MapPanelStyle
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.core.contract.Rarity

/** 공용 레이아웃 도구. #229 팔레트는 차량 화면이 명시적으로 선택한다. */
internal class GarageStyle(
    val context: Context,
    private val pixelPanel: Boolean,
    private val creamActions: Boolean = false,
    private val creamPanel: Boolean = false,
) {
    constructor(context: Context) : this(context, pixelPanel = false)

    private val hud = ReferenceHudStyle(context)
    private val map = MapPanelStyle(context)
    fun sourceText(label: String): String? =
        label.takeUnless { it.startsWith("모의 원장") || it.startsWith("차량 원장") }

    val base = if (creamPanel) CleanUi.CARD_SOFT else if (pixelPanel) PixelStyle.background else Color.rgb(14, 16, 20)
    val panel = if (creamPanel) CleanUi.CARD else if (pixelPanel) PixelStyle.panel else Color.rgb(26, 29, 35)
    val inset = if (creamPanel) CleanUi.CHIP else if (pixelPanel) PixelStyle.inset else Color.rgb(40, 44, 52)
    val ink = if (creamPanel) map.ink else if (pixelPanel) PixelStyle.text else Color.rgb(242, 243, 245)
    val muted = if (creamPanel) map.muted else if (pixelPanel) PixelStyle.muted else Color.rgb(166, 172, 183)
    val blue = if (creamPanel) map.accent else if (pixelPanel) PixelStyle.primary else Color.rgb(48, 102, 255)
    fun heading(value: CharSequence, icon: ReferenceHudIcon) = text(value, 23, bold = true).apply {
        background = hud.signSurface()
        setCompoundDrawablesRelative(hud.icon(icon, 27), null, null, null)
        compoundDrawablePadding = dp(10)
        setPadding(dp(13), dp(8), dp(13), dp(9))
    }

    fun experienceTrack(percent: Int) = map.experienceTrack(percent)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun text(value: CharSequence, size: Int = 20, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    fun shape(fill: Int, selected: Boolean = false): Drawable {
        if (creamPanel) {
            if (selected) return hud.signSurface(focused = true)
            if (fill == panel) return hud.signSurface()
            if (fill == inset) return hud.detailSurface(active = false)
            return GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1).coerceAtLeast(1), CleanUi.LINE)
            }
        }
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(if (pixelPanel) PixelStyle.panelRadiusDp else 24).toFloat()
            if (pixelPanel) setStroke(dp(if (selected) 2 else 1).coerceAtLeast(1),
                if (selected) PixelStyle.focus else PixelStyle.outline)
            else if (selected) setStroke(dp(2), ink)
        }
    }
    fun card(selected: Boolean = false) = column().apply {
        background = shape(panel, selected)
        val horizontal = dp(if (pixelPanel) PixelStyle.panelPaddingDp else 20)
        setPadding(horizontal, dp(16), horizontal, dp(16))
    }
    fun button(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        text = label; textSize = if (pixelPanel) 20f else 24f; isAllCaps = false
        val cream = creamActions && primary && enabled
        if (creamPanel) {
            isEnabled = enabled
            map.styleButton(this, primary)
            setOnClickListener { if (isEnabled) onClick() }
            return@apply
        }
        setTextColor(if (cream) hud.signInk else if (enabled) ink else muted)
        val fill = if (primary && enabled) blue else inset
        if (pixelPanel) {
            setTypeface(typeface, Typeface.BOLD)
            background = if (cream) hud.signButton() else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(fill, selected = true))
                addState(intArrayOf(android.R.attr.state_pressed), shape(fill, selected = true))
                addState(intArrayOf(), shape(fill))
            }
        } else background = shape(fill)
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
    fun progressTrack(value: Int, total: Int = 100, color: Int = blue) =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total
            progress = value
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(if (creamPanel) 0xffdbe5f0.toInt() else PixelStyle.outline)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    fun raritySurface(value: Rarity) = shape(when (value) {
        Rarity.COMMON -> inset
        Rarity.ADVANCED -> 0xffe1f0ff.toInt()
        Rarity.RARE -> 0xfffde4f0.toInt()
        Rarity.LEGEND -> 0xfffff1c7.toInt()
    })

    fun rarityChip(value: Rarity) = text(rarity(value), 14, rarityColor(value), bold = true).apply {
        background = if (creamPanel) raritySurface(value) else GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(inset)
            val stroke = dp(2).coerceAtLeast(1)
            if (value == Rarity.COMMON) setStroke(stroke, rarityColor(value), dp(4).toFloat(), dp(3).toFloat())
            else setStroke(stroke, rarityColor(value))
        }
        setPadding(dp(if (creamPanel) 7 else 10), dp(4), dp(if (creamPanel) 7 else 10), dp(4))
    }

    fun rarity(value: Rarity) = context.getString(when (value) {
        Rarity.COMMON -> R.string.garage_rarity_common
        Rarity.ADVANCED -> R.string.garage_rarity_advanced
        Rarity.RARE -> R.string.garage_rarity_rare
        Rarity.LEGEND -> R.string.garage_rarity_legend
    })
    fun rarityColor(value: Rarity) = if (creamPanel) when (value) {
        Rarity.COMMON -> muted
        Rarity.ADVANCED -> 0xff1f6fb8.toInt()
        Rarity.RARE -> 0xffc0357a.toInt()
        Rarity.LEGEND -> 0xffa86a00.toInt()
    } else if (pixelPanel) PixelStyle.rarityColor(value) else when (value) {
        Rarity.COMMON -> muted
        Rarity.ADVANCED -> Color.rgb(137, 186, 255)
        Rarity.RARE -> Color.rgb(196, 169, 255)
        Rarity.LEGEND -> Color.rgb(245, 207, 119)
    }
}
