package io.drivegrowth.app.ui.shop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.drivegrowth.app.R
import io.drivegrowth.core.rental.Facility
import kotlin.math.sin

/** 기존 마을 그림의 해당 시설만 잘라 공사 단계에서 드러낸다. 좌표는 #229 원본 기준이다. */
internal class FacilityInstallationScene(context: Context, private val town: Bitmap, facility: Facility) {
    private val paint = Paint().apply { isFilterBitmap = false }
    private val region = checkNotNull(ShopMapTarget.FacilityLot(facility).focusRegion())
    private val silhouette = when (facility) {
        Facility.FUEL_STATION -> polygon(105f, 83f, 104f, 59f, 249f, 0f, 506f, 0f, 506f, 62f,
            474f, 74f, 474f, 220f, 338f, 268f, 188f, 231f, 102f, 207f)
        Facility.INSURANCE -> polygon(232f, 370f, 384f, 292f, 553f, 361f, 553f, 508f,
            422f, 576f, 203f, 499f, 235f, 461f)
        Facility.CAR_WASH -> polygon(1174f, 456f, 1364f, 374f, 1527f, 449f, 1527f, 600f,
            1358f, 704f, 1151f, 603f)
    }
    private val fullTown = RectF(0f, 0f, 1536f, 1024f)
    private val actor = context.assets.open("pixel/prototype-actors/${if (facility == Facility.FUEL_STATION) "fuel" else "mechanic"}.png").use {
        requireNotNull(BitmapFactory.decodeStream(it))
    }
    private val staffBounds = RectF()
    private val operating = context.getString(R.string.shop_menu_installed)

    fun draw(canvas: Canvas, progress: Float) {
        val x = (region.left + region.right) / 2f
        val ground = region.bottom - 42f
        val rise = ((progress - .25f) / .46f).coerceIn(0f, 1f)
        if (rise < 1f) {
            val saved = canvas.save()
            canvas.clipPath(silhouette)
            paint.color = 0xff929398.toInt()
            canvas.drawPaint(paint)
            canvas.clipRect(region.left, region.top, region.right, ground + 62f)
            if (rise > 0f) {
                val height = (region.bottom - region.top) * (1f - rise) * (1f - rise)
                canvas.translate(0f, height)
                canvas.clipPath(silhouette)
                paint.alpha = 255
                canvas.drawBitmap(town, null, fullTown, paint)
            }
            canvas.restoreToCount(saved)
        }
        if (progress < .75f) {
            val alpha = ((1f - ((progress - .50f) /.25f).coerceIn(0f, 1f)) * 255).toInt()
            cone(canvas, x - 114f, ground, alpha)
            cone(canvas, x + 94f, ground, alpha)
            repeat(7) {
                val drift = (it - 3) * 29f
                val dustX = x + drift * (.4f + progress)
                val dustY = ground - it % 3 * 13f - progress * 46f
                paint.color = 0xffefd9a6.toInt()
                paint.alpha = alpha * 2 / 3
                canvas.drawRect(dustX - 9f, dustY - 8f, dustX + 9f, dustY + 8f, paint)
            }
        }
        if (progress >= .64f) {
            val appear = ((progress - .64f) / .17f).coerceIn(0f, 1f)
            val staffX = x + 95f - (1f - appear) * 24f
            val y = ground - 9f * sin(appear * Math.PI).toFloat()
            val halfWidth = 21f * actor.width / actor.height
            staffBounds.set(staffX - halfWidth, y - 42f, staffX + halfWidth, y)
            paint.alpha = (appear * 255).toInt()
            canvas.drawBitmap(actor, null, staffBounds, paint)
            paint.color = 0xfff8e6b9.toInt()
            canvas.drawRect(x - 57f, ground - 92f, x + 57f, ground - 61f, paint)
            paint.color = 0xff146a35.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 23f
            canvas.drawText(operating, x, ground - 68f, paint)
        }
        paint.alpha = 255
    }

    private fun cone(canvas: Canvas, x: Float, ground: Float, alpha: Int) {
        paint.color = 0xff6a4738.toInt()
        paint.alpha = alpha
        canvas.drawRect(x - 13f, ground - 6f, x + 13f, ground, paint)
        paint.color = 0xffee7d36.toInt()
        paint.alpha = alpha
        canvas.drawRect(x - 8f, ground - 16f, x + 8f, ground - 6f, paint)
        canvas.drawRect(x - 5f, ground - 29f, x + 5f, ground - 16f, paint)
        paint.color = 0xffffefcb.toInt()
        paint.alpha = alpha
        canvas.drawRect(x - 7f, ground - 14f, x + 7f, ground - 10f, paint)
    }

    private fun polygon(vararg corners: Float) = Path().apply {
        moveTo(corners[0], corners[1])
        for (index in 2 until corners.size step 2) lineTo(corners[index], corners[index + 1])
        close()
    }
}
