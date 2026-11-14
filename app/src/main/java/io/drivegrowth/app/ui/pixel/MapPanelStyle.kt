package io.drivegrowth.app.ui.pixel

import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.Button

/** 지도 위의 확인창과 차량 상세가 HUD와 같은 색·모서리를 사용한다. */
internal class MapPanelStyle(private val context: Context) {
    private val signs = ReferenceHudStyle(context)
    val ink get() = signs.signInk
    val muted = 0xff5a4c37.toInt()
    val accent = 0xff396b28.toInt()

    fun card() = signs.signSurface()

    fun chip(active: Boolean = false) = signs.detailSurface(active)

    fun experienceTrack(percent: Int) = object : View(context) {
        private val paint = Paint()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = signs.dp(3).toFloat()
            val trackWidth = width - inset * 2
            val trackHeight = height - inset * 2
            if (trackWidth <= 0f || trackHeight <= 0f) return
            paint.color = 0xff704822.toInt()
            canvas.drawRect(inset, inset, inset + trackWidth, inset + trackHeight, paint)
            val gap = signs.dp(1).coerceAtLeast(1).toFloat()
            val cell = (trackWidth - gap * 11) / 10
            if (cell <= 0f) return
            val top = inset + gap
            val bottom = inset + trackHeight - gap
            for (index in 0 until 10) {
                val left = inset + gap + index * (cell + gap)
                paint.color = 0xffe5c78b.toInt()
                canvas.drawRect(left, top, left + cell, bottom, paint)
                val portion = ((percent - index * 10) / 10f).coerceIn(0f, 1f)
                if (portion > 0f) {
                    paint.color = accent
                    canvas.drawRect(left, top, left + cell * portion, bottom, paint)
                }
            }
        }
    }.apply { background = chip() }

    fun styleButton(button: Button, primary: Boolean) = button.apply {
        backgroundTintList = null
        background = signs.signButton(primary)
        setTextColor(if (isEnabled) ink else muted)
        minHeight = signs.dp(48)
        minimumHeight = minHeight
        minWidth = 0
        minimumWidth = 0
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(signs.dp(8), signs.dp(6), signs.dp(8), signs.dp(6))
    }
}
