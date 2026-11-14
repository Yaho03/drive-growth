package io.drivegrowth.app.ui.pixel

import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.view.View
import android.widget.Button

/** 지도와 기능 창의 버튼 크기를 유지하면서 화면에 맞는 팔레트를 사용한다. */
internal class MapPanelStyle(
    private val context: Context,
    private val featureWindow: Boolean = false,
) {
    private val signs = ReferenceHudStyle(context, featureWindow)
    val ink get() = signs.signInk
    val muted = if (featureWindow) CleanUi.MUTED else 0xff5a4c37.toInt()
    val accent = if (featureWindow) CleanUi.SEA else 0xff396b28.toInt()

    fun card() = signs.signSurface()

    fun chip(active: Boolean = false) = signs.detailSurface(active)

    fun experienceTrack(percent: Int): View =
        if (featureWindow) featureTrack(percent) else pixelTrack(percent)

    private fun pixelTrack(percent: Int) = object : View(context) {
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

    private fun featureTrack(percent: Int) = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val inset = signs.dp(3).toFloat()
        private val fraction = percent.coerceIn(0, 100) / 100f
        private var fill: Shader? = null

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            fill = if (w > inset * 2) LinearGradient(inset, 0f, w - inset, 0f,
                CleanUi.SEA_LIGHT, CleanUi.SEA, Shader.TileMode.CLAMP) else null
        }

        override fun onDraw(canvas: Canvas) {
            val right = width - inset
            val bottom = height - inset
            if (right <= inset || bottom <= inset) return
            val radius = (bottom - inset) / 2
            paint.color = CleanUi.CHIP
            canvas.drawRoundRect(inset, inset, right, bottom, radius, radius, paint)
            if (fraction <= 0f) return
            // 트랙 모양을 유지하면서 실제 값까지만 채운다.
            val saved = canvas.save()
            canvas.clipRect(inset, inset, inset + (right - inset) * fraction, bottom)
            paint.shader = fill
            canvas.drawRoundRect(inset, inset, right, bottom, radius, radius, paint)
            paint.shader = null
            canvas.restoreToCount(saved)
        }
    }

    fun styleButton(button: Button, primary: Boolean) = button.apply {
        backgroundTintList = null
        background = signs.signButton(primary)
        if (featureWindow) setTextColor(signs.featureButtonText(primary))
        else setTextColor(if (isEnabled) ink else muted)
        minHeight = signs.dp(48)
        minimumHeight = minHeight
        minWidth = 0
        minimumWidth = 0
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(signs.dp(8), signs.dp(6), signs.dp(8), signs.dp(6))
    }
}
