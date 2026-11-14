package io.drivegrowth.app.ui.pixel

import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.Button

/** 지도와 기능 창의 버튼 크기를 유지하면서 화면에 맞는 팔레트를 사용한다. */
internal class MapPanelStyle(
    private val context: Context,
) {
    private val signs = ReferenceHudStyle(context)
    val ink get() = signs.signInk
    val muted = CleanUi.MUTED
    val accent = CleanUi.SEA

    fun card() = signs.signSurface()

    fun chip(active: Boolean = false) = signs.detailSurface(active)

    fun segmentedExperienceTrack(percent: Int) = object : View(context) {
        private val paint = Paint()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = signs.dp(3).toFloat()
            val trackWidth = width - inset * 2
            val trackHeight = height - inset * 2
            if (trackWidth <= 0f || trackHeight <= 0f) return
            paint.color = CleanUi.CARD_SOFT
            canvas.drawRect(inset, inset, inset + trackWidth, inset + trackHeight, paint)
            val gap = signs.dp(1).coerceAtLeast(1).toFloat()
            val cell = (trackWidth - gap * 11) / 10
            if (cell <= 0f) return
            val top = inset + gap
            val bottom = inset + trackHeight - gap
            for (index in 0 until 10) {
                val left = inset + gap + index * (cell + gap)
                paint.color = CleanUi.TRACK
                canvas.drawRect(left, top, left + cell, bottom, paint)
                val portion = ((percent - index * 10) / 10f).coerceIn(0f, 1f)
                if (portion > 0f) {
                    paint.color = CleanUi.SEA_LIGHT
                    canvas.drawRect(left, top, left + cell * portion, bottom, paint)
                }
            }
        }
    }.apply { background = chip() }

    fun experienceTrack(percent: Int) = object : View(context) {
        private val inset = signs.dp(3).toFloat()
        private val fraction = percent.coerceIn(0, 100) / 100f
        private val track = signs.panel(CleanUi.TRACK, step = 1, line = CleanUi.TRACK)
        private val fill = signs.panel(CleanUi.SEA_LIGHT, step = 1, line = CleanUi.SEA_LIGHT)

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val padding = inset.toInt()
            track.setBounds(padding, padding, w - padding, h - padding)
            fill.bounds = track.bounds
        }

        override fun onDraw(canvas: Canvas) {
            if (track.bounds.isEmpty) return
            track.draw(canvas)
            if (fraction <= 0f) return
            val saved = canvas.save()
            val bounds = track.bounds
            canvas.clipRect(inset, inset, inset + bounds.width() * fraction, bounds.bottom.toFloat())
            fill.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }

    fun styleButton(button: Button, primary: Boolean) = button.apply {
        backgroundTintList = null
        background = signs.signButton(primary)
        setTextColor(signs.featureButtonText(primary))
        minHeight = signs.dp(56)
        minimumHeight = minHeight
        minWidth = 0
        minimumWidth = 0
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(signs.dp(12), signs.dp(8), signs.dp(12), signs.dp(8))
    }
}
