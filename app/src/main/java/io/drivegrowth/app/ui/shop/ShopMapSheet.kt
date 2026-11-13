package io.drivegrowth.app.ui.shop

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** 설정·시설 등에서 기존 조회 및 명령 위젯을 지도 위에 표시한다. */
@SuppressLint("ViewConstructor")
internal class ShopMapSheet(
    context: Context,
    content: View,
    scrollY: Int,
    canClose: Boolean,
    onClose: () -> Unit,
) : FrameLayout(context) {
    private val style = ShopStyle(context)
    val scroll: ScrollView = style.scroll(content, scrollY).apply { tag = "shop:controls-scroll" }
    private val card = style.column().apply {
        background = style.shape(style.panel)
        setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
        isClickable = true
        addView(style.button("닫기", canClose, action = onClose).apply {
            tag = "shop:map-sheet-close"
            textSize = 16f
            minHeight = style.dp(48)
            minimumHeight = style.dp(48)
            setPadding(style.dp(12), 0, style.dp(12), 0)
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    init {
        tag = "shop:map-sheet"
        addView(card, LayoutParams(-1, -1))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val wide = width >= style.dp(720)
        val gap = style.dp(8)
        (card.layoutParams as LayoutParams).apply {
            this.width = if (wide) minOf(style.dp(520), (width * .48f).toInt()) else (width - gap * 2).coerceAtLeast(0)
            this.height = if (wide) (height - gap * 2).coerceAtLeast(0) else (height * .80f).toInt()
            gravity = (if (wide) Gravity.CENTER_VERTICAL else Gravity.BOTTOM) or Gravity.END
            marginEnd = gap
            bottomMargin = if (wide) 0 else gap
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
