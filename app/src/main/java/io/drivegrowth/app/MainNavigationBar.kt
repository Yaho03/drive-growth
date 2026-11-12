package io.drivegrowth.app

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.ui.pixel.PixelStyle

/** 기존 화면을 여는 하단 탐색. 선택된 페이지는 Activity가 공급한다. */
internal class MainNavigationBar(
    context: Context,
    destinations: List<Pair<HostPage, String>>,
    select: (HostPage) -> Unit,
    back: () -> Unit,
    refresh: () -> Unit,
) : LinearLayout(context) {
    var selectedPage: HostPage? = null
        private set
    private var revealSelection = false
    private var pageWidthSpec: Int? = null
    private val buttons = destinations.associate { (page, label) ->
        page to control(context, label) { select(page) }.apply { tag = "host:page:${page.name}" }
    }
    private val pages = object : HorizontalScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val buttonSpec = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) null else {
                val viewportWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                MeasureSpec.makeMeasureSpec(viewportWidth, MeasureSpec.AT_MOST)
            }
            if (pageWidthSpec != buttonSpec) {
                pageWidthSpec = buttonSpec
                getChildAt(0).forceLayout()
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (changed || revealSelection) {
                revealSelection = false
                buttons[selectedPage]?.let { button ->
                    val bounds = Rect()
                    button.getDrawingRect(bounds)
                    offsetDescendantRectToMyCoords(button, bounds)
                    val minScrollX = bounds.right - width + paddingRight
                    val maxScrollX = bounds.left - paddingLeft
                    scrollTo(scrollX.coerceIn(minScrollX.coerceAtMost(maxScrollX), maxScrollX), scrollY)
                }
            }
        }
    }.apply {
        tag = "host:pages"
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(object : LinearLayout(context) {
            override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
                parentHeightMeasureSpec: Int, heightUsed: Int) {
                // 목록은 가로로 스크롤하되 버튼 하나는 현재 보이는 폭 안에 들어가게 한다.
                val buttonSpec = pageWidthSpec?.takeIf { MeasureSpec.getMode(parentWidthMeasureSpec) == MeasureSpec.UNSPECIFIED }
                super.measureChildWithMargins(child, buttonSpec ?: parentWidthMeasureSpec,
                    if (buttonSpec == null) widthUsed else 0, parentHeightMeasureSpec, heightUsed)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            buttons.values.forEach { button ->
                addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                })
            }
        })
    }

    init {
        tag = "host:destination"
        orientation = HORIZONTAL
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(PixelStyle.panel)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        addView(control(context, "뒤로", back).apply { tag = "host:back" },
            LayoutParams(dp(64), LayoutParams.WRAP_CONTENT))
        addView(pages, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        addView(control(context, "새로고침", refresh).apply { tag = "host:refresh" },
            LayoutParams(dp(96), LayoutParams.WRAP_CONTENT))
    }

    fun showSelected(page: HostPage) {
        if (selectedPage == page) return
        selectedPage = page
        buttons.forEach { (target, button) -> button.isSelected = target == page }
        revealSelection = true
        pages.requestLayout()
    }

    private fun control(context: Context, label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(PixelStyle.text)
        minWidth = dp(48)
        minHeight = dp(48)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_selected), shape(PixelStyle.primary, focused = true))
            addState(intArrayOf(android.R.attr.state_focused), shape(PixelStyle.inset, focused = true))
            addState(intArrayOf(android.R.attr.state_selected), shape(PixelStyle.primary))
            addState(intArrayOf(), shape(PixelStyle.inset))
        }
        setOnClickListener { action() }
    }

    private fun shape(color: Int, focused: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        if (focused) setStroke(dp(2), PixelStyle.focus)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
