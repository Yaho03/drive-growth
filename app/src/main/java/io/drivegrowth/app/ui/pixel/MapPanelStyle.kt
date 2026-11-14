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
    val muted = CleanUi.MUTED
    val accent = CleanUi.SEA

    fun card() = signs.signSurface()

    fun chip(active: Boolean = false) = signs.detailSurface(active)

    fun experienceTrack(percent: Int) = object : View(context) {
        private val paint = Paint()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 둥근 막대 하나: 옅은 바탕 위에 바다색이 차오른다
            val inset = signs.dp(3).toFloat()
            val trackWidth = width - inset * 2
            val trackHeight = height - inset * 2
            if (trackWidth <= 0f || trackHeight <= 0f) return
            val radius = trackHeight / 2
            paint.isAntiAlias = true
            paint.color = 0x33ffffff
            canvas.drawRoundRect(inset, inset, inset + trackWidth, inset + trackHeight, radius, radius, paint)
            val filled = trackWidth * percent.coerceIn(0, 100) / 100f
            if (filled > 0f) {
                paint.shader = android.graphics.LinearGradient(inset, 0f, inset + trackWidth, 0f, CleanUi.SEA_LIGHT, accent, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(inset, inset, inset + maxOf(filled, trackHeight), inset + trackHeight, radius, radius, paint)
                paint.shader = null
            }
        }
    }

    fun styleButton(button: Button, primary: Boolean) = button.apply {
        backgroundTintList = null
        background = signs.signButton(primary)
        setTextColor(if (!isEnabled) muted else if (primary) CleanUi.ON_PRIMARY else ink)
        minHeight = signs.dp(52)
        minimumHeight = minHeight
        minWidth = 0
        minimumWidth = 0
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(signs.dp(8), signs.dp(6), signs.dp(8), signs.dp(6))
    }
}
