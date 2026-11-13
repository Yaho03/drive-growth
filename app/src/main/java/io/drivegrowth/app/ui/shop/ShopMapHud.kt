package io.drivegrowth.app.ui.shop

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
        tag = "shop:map-hud"
        orientation = VERTICAL
        gravity = Gravity.START
        val unknown = context.getString(R.string.shop_unknown)
        addView(style.text(
            "${content.name} · ${context.getString(R.string.shop_level, content.level?.toString() ?: unknown)}",
            19, bold = true,
        ))
        addView(style.text(
            context.getString(R.string.shop_capacity, content.ownedCount?.toString() ?: unknown,
                content.capacity?.toString() ?: unknown) + " · " + context.getString(R.string.shop_balance, balance),
            16, style.accent,
        ))
        if (operatingStatus != null) addView(style.text(operatingStatus, 16))
        val actions = style.row()
        if (onResults != null) {
            val label = unviewedCount?.let { "미확인 정산 ${it}건" } ?: "정산 확인"
            actions.addView(action(label, "shop:map-results", onResults))
        }
        if (onSettings != null) actions.addView(action("설정", "shop:map-settings", onSettings))
        if (actions.childCount > 0) addView(actions)
        background = style.shape(style.panel)
        setPadding(style.dp(12), style.dp(4), style.dp(12), style.dp(4))
    }

    private fun action(label: String, key: String, onClick: () -> Unit): Button =
        style.button(label, action = onClick).apply {
            tag = key
            textSize = 16f
            minHeight = style.dp(48)
            minimumHeight = style.dp(48)
            setPadding(style.dp(12), 0, style.dp(12), 0)
        }
}
