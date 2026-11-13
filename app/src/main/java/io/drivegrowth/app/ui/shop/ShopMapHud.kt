package io.drivegrowth.app.ui.shop

import io.drivegrowth.app.ui.pixel.PlayerText
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import io.drivegrowth.app.R

/** 확정 조회로 받은 가게 상태와 지도 밖 화면의 진입점을 표시한다. */
@SuppressLint("ViewConstructor")
internal class ShopMapHud(
    context: Context,
    content: ShopScreenState.Content,
    balance: String,
    operatingStatus: String?,
    unviewedCount: Int?,
    onResults: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) : LinearLayout(context) {
    private val style = ShopStyle(context)

    init {
        // HTML 가게 지도처럼 지도 위에 작은 칩들을 띄운다: 왼쪽 [● 가게·레벨] [대여 현황], 오른쪽 [P 보유/칸] [정산] [설정]
        tag = "shop:map-hud"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val unknown = context.getString(R.string.shop_unknown)
        addView(chip("● ${PlayerText.shopName(content.name)} · Lv${content.level?.toString() ?: unknown}", 0xff7ee2a6.toInt()))
        if (operatingStatus != null) addView(chip(operatingStatus, style.ink), LayoutParams(-2, -2).apply { marginStart = style.dp(6) })
        addView(android.view.View(context), LayoutParams(0, 1, 1f))
        addView(chip("P ${content.ownedCount?.toString() ?: unknown} / ${content.capacity?.toString() ?: unknown}", style.ink).apply {
            contentDescription = context.getString(R.string.shop_capacity, content.ownedCount?.toString() ?: unknown,
                content.capacity?.toString() ?: unknown) + " · " + context.getString(R.string.shop_balance, balance)
        })
        if (onResults != null) {
            val label = unviewedCount?.let { "정산 ${it}건" } ?: "정산"
            addView(action(label, "shop:map-results", onResults), LayoutParams(-2, -2).apply { marginStart = style.dp(6) })
        }
        if (onSettings != null) addView(action("설정", "shop:map-settings", onSettings), LayoutParams(-2, -2).apply { marginStart = style.dp(6) })
    }

    private fun chip(label: String, color: Int) = style.text(label, 16, color, bold = true).apply {
        background = android.graphics.drawable.GradientDrawable().apply { setColor(0xe6121622.toInt()); cornerRadius = style.dp(14).toFloat() }
        setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8))
    }

    private fun action(label: String, key: String, onClick: () -> Unit): Button =
        style.button(label, action = onClick).apply {
            tag = key
            textSize = 16f
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xe6121622.toInt()); cornerRadius = style.dp(14).toFloat() }
            minHeight = style.dp(48)
            minimumHeight = style.dp(48)
            setPadding(style.dp(12), 0, style.dp(12), 0)
        }
}
