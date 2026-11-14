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
    private val focus: ShopFocusRegion?,
    onClose: () -> Unit,
) : FrameLayout(context) {
    constructor(context: Context, content: View, scrollY: Int, canClose: Boolean, onClose: () -> Unit) :
        this(context, content, scrollY, canClose, null, onClose)

    private val style = ShopStyle(context)
    val scroll: ScrollView = style.scroll(content, scrollY).apply {
        tag = "shop:controls-scroll"
        isFillViewport = false
    }
    private val card = style.column().apply {
        background = style.surface()
        setPadding(style.dp(12), style.dp(10), style.dp(12), style.dp(10))
        isClickable = true
        addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        addView(style.button("닫기", canClose, action = onClose).apply {
            tag = "shop:map-sheet-close"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(8) })
    }

    init {
        tag = "shop:map-sheet"
        isClickable = true
        setBackgroundColor(style.scrim)
        addView(card, LayoutParams(-1, -2, Gravity.CENTER))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val gap = style.dp(12)
        val fit = focus?.let { ShopFocusLayout.fit(width, height, resources.displayMetrics.density) }
        val panelWidth = fit?.panelWidth ?: minOf(style.dp(420), (width - gap * 2).coerceAtLeast(0))
        val maxHeight = fit?.maxPanelHeight ?: minOf(style.dp(540), (height - gap * 2).coerceAtLeast(0))
        card.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, if (fit?.side == true) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        (card.layoutParams as LayoutParams).apply {
            this.width = panelWidth
            this.height = card.measuredHeight
            if (fit != null) {
                gravity = if (fit.side) Gravity.CENTER_VERTICAL or (if (focus.panelOnLeft) Gravity.LEFT else Gravity.RIGHT)
                else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(gap, gap, gap, gap)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
