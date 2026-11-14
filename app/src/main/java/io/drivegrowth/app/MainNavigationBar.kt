package io.drivegrowth.app

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle

/** HOME 지도 위에 공용 HUD 메뉴 여섯 개를 배치한다. */
internal class MainNavigationBar(
    context: Context,
    select: (HostPage) -> Unit,
) : LinearLayout(context) {
    private val style = ReferenceHudStyle(context)
    private val buttons: List<Button>

    init {
        tag = "host:destination"
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        minimumWidth = style.dp(312)
        val items = listOf(
            Triple(HostPage.GARAGE, "차고", ReferenceHudIcon.GARAGE),
            Triple(HostPage.COLLECTION, "도감", ReferenceHudIcon.COLLECTION),
            Triple(HostPage.GACHA, "뽑기", ReferenceHudIcon.GACHA),
            Triple(HostPage.DELIVERY, "탁송", ReferenceHudIcon.TRUCK),
            Triple(HostPage.RESULTS, "정산", ReferenceHudIcon.SETTLEMENT),
            Triple(HostPage.RANKING, "기록", ReferenceHudIcon.RECORD),
        )
        buttons = items.map { (target, label, icon) ->
            Button(context).apply {
                tag = "host:page:${target.name}"
                text = label
                isAllCaps = false
                gravity = Gravity.CENTER
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(style.featureButtonText(primary = false))
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setCompoundDrawablesRelative(null, style.icon(icon, 30), null, null)
                compoundDrawablePadding = style.dp(1)
                minWidth = 0
                minimumWidth = 0
                minHeight = style.dp(64)
                minimumHeight = minHeight
                backgroundTintList = null
                background = style.signButton()
                setPadding(style.dp(2), style.dp(6), style.dp(2), style.dp(6))
                setOnClickListener { if (isEnabled) select(target) }
            }.also { button ->
                addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = style.dp(2)
                    marginEnd = style.dp(2)
                })
            }
        }
    }

    fun showUnviewedSettlements(count: Int?) {
        val results = buttons.first { it.tag == "host:page:${HostPage.RESULTS.name}" }
        val description = count?.takeIf { it > 0 }?.let { "정산 · 읽지 않은 기록 ${it}건" } ?: "정산"
        if (results.contentDescription != description) results.contentDescription = description
        val label = if (count != null && count > 0) "정산 •" else "정산"
        if (results.text != label) results.text = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wide = MeasureSpec.getSize(widthMeasureSpec) >= style.dp(600)
        for (button in buttons) {
            val pixels = style.dp(if (wide) 48 else 34)
            button.compoundDrawablesRelative[1]?.let { draw ->
                if (draw.bounds.width() != pixels) {
                    draw.setBounds(0, 0, pixels, pixels)
                    button.setCompoundDrawablesRelative(null, draw, null, null)
                }
            }
            val labelSize = if (wide) 22f else 17f
            if (button.textSize != android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, labelSize, resources.displayMetrics,
                )) button.textSize = labelSize
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
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
