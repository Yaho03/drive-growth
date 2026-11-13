package io.drivegrowth.app

import android.content.Context
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.ui.garage.GarageStyle

/** 게임의 공통 하단 메뉴다. */
internal class MainHomeNavigation(
    context: Context,
    page: HostPage,
    select: (HostPage) -> Unit,
) : LinearLayout(context) {
    init {
        val style = GarageStyle(context, pixelPanel = true)
        tag = "host:destination"
        setBackgroundColor(style.panel)
        setPadding(style.dp(6), style.dp(4), style.dp(6), style.dp(4))
        val section = when (page) {
            HostPage.COLLECTION -> HostPage.GARAGE
            HostPage.PROGRESSION, HostPage.RANKING -> HostPage.RESULTS
            else -> page
        }
        for ((target, label) in listOf(
            HostPage.HOME to "가게",
            HostPage.GARAGE to "차고",
            HostPage.DELIVERY to "탁송",
            HostPage.GACHA to "뽑기",
            HostPage.RESULTS to "기록",
        )) {
            val selected = target == section
            val button = style.button(label, primary = selected) { select(target) }.apply {
                tag = "host:page:${target.name}"
                isSelected = selected
                compact(style)
            }
            addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = style.dp(4) })
        }
    }
}

internal fun Button.compact(style: GarageStyle) {
    textSize = 16f
    setSingleLine()
    ellipsize = TextUtils.TruncateAt.END
    minWidth = 0
    minimumWidth = 0
    minHeight = style.dp(48)
    minimumHeight = style.dp(48)
    setPadding(style.dp(4), 0, style.dp(4), 0)
}
