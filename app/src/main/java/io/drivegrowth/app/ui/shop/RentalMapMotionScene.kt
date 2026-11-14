package io.drivegrowth.app.ui.shop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.drivegrowth.app.runtime.rental.RentalMapDirection
import kotlin.math.sin

/** 기존 주차 위치와 차종·손님 그림으로 그리는 2초 지도 장식. */
internal class RentalMapMotionScene(private val effect: RentalMapEffect, private val car: Bitmap, private val guest: Bitmap) {
    private val paint = Paint().apply { isFilterBitmap = false }
    private val destination = RectF()

    fun draw(canvas: Canvas, time: Float) {
        val (slotX, slotY) = effect.slot
        val x = slotX.toFloat()
        val y = slotY.toFloat()
        val leaving = effect.event.direction == RentalMapDirection.DEPARTURE
        val movement = if (leaving) ((time - .30f) / .70f).coerceIn(0f, 1f) else (time / .72f).coerceIn(0f, 1f)
        val path = if (leaving) movement else 1f - movement
        val alongAccess = (path / .42f).coerceIn(0f, 1f)
        val alongRoad = ((path - .42f) / .58f).coerceIn(0f, 1f)
        val leftRow = slotX < 800
        val laneOffset = if (leftRow) 92f else 66f
        val carX = x - 29f * alongAccess + 112f * alongRoad
        val carY = y + laneOffset * alongAccess + 49f * alongRoad
        val alpha = if (leaving) ((1f - path) / .22f).coerceAtMost(1f) else ((1f - path) / .18f).coerceAtMost(1f)
        sprite(canvas, car, carX, carY - 7f, car.width * 1.8f, alpha, flip = true)
        if (leaving && time < .30f || !leaving && time > .72f) {
            val walk = if (leaving) 1f - time / .30f else (time - .72f) / .28f
            val bob = 2f * sin(walk * 19f)
            sprite(canvas, guest, x + 24f + 39f * walk, y + 16f + 19f * walk + bob, 26f,
                ((1f - walk) * 4f).coerceIn(0f, 1f), flip = leaving)
        }
    }

    private fun sprite(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, width: Float, alpha: Float, flip: Boolean) {
        val height = width * bitmap.height / bitmap.width
        destination.set(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val layer = canvas.save()
        if (flip) canvas.scale(-1f, 1f, x, y)
        canvas.drawBitmap(bitmap, null, destination, paint)
        canvas.restoreToCount(layer)
    }
}
