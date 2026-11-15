package io.drivegrowth.app.ui.shop

import io.drivegrowth.app.ui.presentation.setGameButtonClick
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import java.text.NumberFormat
import java.util.Locale

internal class ShopMapHud(
    context: Context,
    content: ShopScreenState.Content,
    operatingStatus: String?,
    onSettings: (() -> Unit)?,
) : FrameLayout(context) {
    private val style = ReferenceHudStyle(context)
    private val settingsLabel = context.getString(R.string.shop_map_settings)
    private val operatingUnknown = context.getString(R.string.shop_map_checking_operation)
    private val balance = content.balance?.let { NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units) }
    private val status = signColumn().apply {
        addView(label(operatingStatus ?: operatingUnknown, 17f).apply {
            maxLines = 2
            val marker = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (operatingStatus == null || operatingStatus == operatingUnknown)
                    CleanUi.SUN else 0xff6bdd91.toInt())
                setStroke(style.dp(1), style.signInk)
                setBounds(0, 0, style.dp(10), style.dp(10))
            }
            setCompoundDrawablesRelative(marker, null, null, null)
            compoundDrawablePadding = style.dp(4)
        })
        addView(label("가게 Lv${content.level ?: "—"}", 16f).apply {
            contentDescription = context.getString(R.string.shop_level, content.level?.toString() ?: unknown())
        })
        addView(label("P ${content.ownedCount ?: "—"} / ${content.capacity ?: "—"}", 15f).apply {
            contentDescription = context.getString(R.string.shop_capacity,
                content.ownedCount?.toString() ?: unknown(), content.capacity?.toString() ?: unknown())
        })
    }
    private val account = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP or Gravity.END
        addView(label(balance ?: "—", 19f).apply {
            tag = "shop:map-balance"
            contentDescription = balance?.let { context.getString(R.string.shop_money, it) } ?: unknown()
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setCompoundDrawablesRelative(style.icon(ReferenceHudIcon.COIN, 16), null, null, null)
            compoundDrawablePadding = style.dp(3)
            setPadding(style.dp(8), 0, style.dp(8), 0)
            background = style.signSurface()
        }, LinearLayout.LayoutParams(-2, style.dp(56)))
        if (onSettings != null) {
            addView(Button(context).apply {
                tag = "shop:map-settings"
                contentDescription = settingsLabel
                backgroundTintList = null
                background = style.signButton()
                gravity = Gravity.CENTER
                minWidth = 0
                minHeight = 0
                foreground = InsetDrawable(style.icon(ReferenceHudIcon.SETTINGS, 26), style.dp(13))
                foregroundGravity = Gravity.FILL
                setGameButtonClick(action = onSettings)
            }, LinearLayout.LayoutParams(style.dp(56), style.dp(56)).apply { marginStart = style.dp(4) })
        }
    }

    init {
        tag = "shop:map-hud"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(status, hudParams(Gravity.START))
        addView(account, hudParams(Gravity.END))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val inner = (MeasureSpec.getSize(widthMeasureSpec) - style.dp(24)).coerceAtLeast(0)
        val statusWidth = (inner * 0.46f).toInt().coerceAtMost(style.dp(210))
        val accountWidth = (inner - statusWidth).coerceAtMost(style.dp(246))
        val statusTextWidth = (statusWidth - status.paddingStart - status.paddingEnd).coerceAtLeast(0)
        for (index in 0 until status.childCount) {
            val text = status.getChildAt(index) as TextView
            if (text.maxWidth != statusTextWidth) text.maxWidth = statusTextWidth
        }
        val settingsSpace = if (account.childCount > 1) style.dp(60) else 0
        val balanceTextWidth = (accountWidth - settingsSpace).coerceAtLeast(0)
        val balanceLabel = account.getChildAt(0) as TextView
        if (balanceLabel.maxWidth != balanceTextWidth) balanceLabel.maxWidth = balanceTextWidth
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun hudParams(edge: Int) = LayoutParams(-2, -2, Gravity.TOP or edge).apply {
        topMargin = style.dp(8)
        marginStart = style.dp(8)
        marginEnd = style.dp(8)
    }

    private fun signColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = style.signSurface()
        setPadding(style.dp(10), style.dp(6), style.dp(10), style.dp(7))
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(style.signInk)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun unknown() = context.getString(R.string.shop_unknown)
}
