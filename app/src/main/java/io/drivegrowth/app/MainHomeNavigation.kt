package io.drivegrowth.app

import android.content.Context
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.ui.garage.GarageStyle

/** 지도에서 기존 기능 화면으로 들어가는 주요 메뉴다. */
internal class MainHomeNavigation(context: Context, select: (HostPage) -> Unit) : LinearLayout(context) {
    init {
        val style = GarageStyle(context, pixelPanel = true)
        tag = "host:destination"
        setBackgroundColor(style.panel)
        setPadding(style.dp(6), style.dp(4), style.dp(6), style.dp(4))
        for ((page, label) in listOf(
            HostPage.HOME to "가게",
            HostPage.GARAGE to "차고",
            HostPage.GACHA to "가챠",
            HostPage.DELIVERY to "탁송",
            HostPage.RESULTS to "기록",
        )) {
            val selected = page == HostPage.HOME
            val button = style.button(label, primary = selected) { select(page) }.apply {
                tag = "host:page:${page.name}"
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
