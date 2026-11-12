package io.drivegrowth.app

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
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
    private var selectedPage: HostPage? = null
    private var revealSelection = false
    private val buttons = destinations.associate { (page, label) ->
        page to control(context, label) { select(page) }.apply { tag = "host:page:${page.name}" }
    }
    private val pages = object : HorizontalScrollView(context) {
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (changed || revealSelection) {
                revealSelection = false
                buttons[selectedPage]?.let { button ->
                    button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                }
            }
        }
    }.apply {
        tag = "host:pages"
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
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
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(PixelStyle.panel)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        addView(control(context, "뒤로", back).apply { tag = "host:back" })
        addView(pages, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        addView(control(context, "새로고침", refresh).apply { tag = "host:refresh" })
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
