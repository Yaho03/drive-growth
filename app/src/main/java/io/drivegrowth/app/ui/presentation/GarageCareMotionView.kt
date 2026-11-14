package io.drivegrowth.app.ui.presentation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import io.drivegrowth.app.ui.garage.GarageCareEffect
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.progression.VehicleAction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** 차량 관리 결과의 그림만 그린다. 글과 버튼은 GarageCommandView에 유지한다. */
internal class GarageCareMotionView(context: Context) : View(context) {
    private val art = ReferenceSceneArt(context)
    private val cars = VehicleSprites(context)
    private val paint = Paint().apply { isFilterBitmap = false; typeface = Typeface.DEFAULT_BOLD }
    private val bounds = RectF()
    private val visibleBounds = Rect()
    private var animator: ValueAnimator? = null
    private var effect: GarageCareEffect? = null
    private var frame = 0f

    init {
        tag = "garage:care-motion"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun play(value: GarageCareEffect, eligible: () -> Boolean) {
        stop()
        if (!eligible()) return
        effect = value
        frame = 0f
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (value.outcome.action) {
                VehicleAction.LEVEL_UP -> 900L
                VehicleAction.USE_MATERIAL -> 1_400L
                VehicleAction.REPAIR -> 800L
                VehicleAction.SELL, VehicleAction.SCRAP -> 1_000L
            }
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!eligible()) stop()
                else {
                    frame = it.animatedValue as Float
                    invalidate()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    effect = null
                    visibility = INVISIBLE
                }
            })
        }
        animator?.start()
    }

    fun stop() {
        animator?.removeAllUpdateListeners()
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        effect = null
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = effect ?: return
        if (!getLocalVisibleRect(visibleBounds)) return
        val scale = minOf(width / 224f, height / 90f)
        val save = canvas.save()
        canvas.translate((width - scale * 224f) / 2f, (height - scale * 90f) / 2f)
        canvas.scale(scale, scale)
        val vehicle = cars.bitmap(value.outcome.vehicle.vehicleId)
        when (value.outcome.action) {
            VehicleAction.LEVEL_UP -> canvas.growth(value, vehicle, frame)
            VehicleAction.USE_MATERIAL -> {
                val transfer = part(0.16f, 0.63f)
                if (transfer < 1f) {
                    val material = value.outcome.material?.let { cars.bitmap(it.vehicleId) }
                    canvas.car(material, 42f, 45f, alpha = 1f - transfer)
                    repeat(9) { index ->
                        val t = (transfer * 1.6f - index * 0.06f).coerceIn(0f, 1f)
                        canvas.spark(47f + 92f * t, 44f + (index % 3 - 1) * 13f * arc(t), GOLD, arc(t))
                    }
                    canvas.car(vehicle, 142f, 42f)
                } else {
                    canvas.growth(value, vehicle, part(0.63f, 1f), center = 142f)
                    val after = value.outcome.vehicle
                    val increase = (after.level - value.before.level) * 100 + after.levelProgressPercent - value.before.levelProgressPercent
                    if (increase > 0) canvas.label("+$increase%", 51f, 42f)
                }
            }
            VehicleAction.REPAIR -> canvas.repair(vehicle)
            VehicleAction.SELL, VehicleAction.SCRAP -> canvas.disposal(value, vehicle)
        }
        canvas.restoreToCount(save)
    }

    private fun Canvas.growth(value: GarageCareEffect, vehicle: Bitmap?, t: Float, center: Float = 112f) {
        val after = value.outcome.vehicle
        val bump = arc(t)
        if (after.level > value.before.level) box(center - 14, 42 - 33 * t, center + 14, 55f, GOLD, bump * 0.35f)
        car(vehicle, center, 52 - 11 * bump)
        repeat(5) { spark(center - 37 + it * 18, 39 - it % 2 * 14 - 11 * t, GOLD, bump) }
        val start = value.before.levelProgressPercent
        val points = (after.level - value.before.level) * 100 + after.levelProgressPercent
        val played = (start + (points - start) * t).toInt()
        val level = (value.before.level + played / 100).coerceAtMost(after.level)
        label("Lv.$level", center, 66f)
        box(center - 46, 73f, center + 46, 81f, INK, 0.35f)
        box(center - 44, 75f, center - 44 + 88f * (played % 100) / 100f, 79f, GOLD)
    }

    private fun Canvas.repair(vehicle: Bitmap?) {
        car(vehicle, 112f, 49f)
        val stroke = if (frame < 0.5f) frame * 2f else (frame - 0.5f) * 2f
        val state = save()
        translate(126f, 14 + 11 * arc(stroke))
        rotate(22 - 26 * arc(stroke))
        box(-4f, 1f, 4f, 22f, INK)
        box(-9f, -5f, 9f, 6f, INK)
        box(-4f, -6f, 4f, 1f, CREAM)
        restoreToCount(state)
        repeat(4) { spark(79f - 8 * frame + it * 6, 34f - it * 3 - 12 * frame, INK, (1 - frame) * 0.45f) }
        val ready = frame >= 0.62f
        if (ready) {
            val glint = arc(part(0.62f, 1f))
            spark(87f, 72f, GOLD, glint)
            spark(137f, 75f, GOLD, glint)
        }
        label(if (ready) "대기" else "수리 필요", 112f, 80f)
    }

    private fun Canvas.disposal(value: GarageCareEffect, vehicle: Bitmap?) {
        if (value.outcome.action == VehicleAction.SELL) {
            val exit = part(0f, 0.65f)
            car(vehicle, 98 + 142 * exit, 49f, alpha = 1f - exit)
        } else if (frame < 0.44f) {
            car(vehicle, 98f, 54f, squash = 1f - 0.83f * part(0f, 0.44f))
        } else {
            val spread = part(0.44f, 0.88f)
            repeat(12) { i -> spark(99 + (i - 5) * 11 * spread, 58f - i % 4 * 8 * arc(spread),
                if (i % 2 == 0) INK else GOLD, 1f - spread) }
        }
        if (value.outcome.credit == null) return
        val fly = part(0.42f, 1f)
        if (fly == 0f) return
        sprite(art.coin, 192f, 22f, 12f, 1f, 1f)
        repeat(if (value.outcome.action == VehicleAction.SCRAP) 2 else 4) { i ->
            val t = (fly * 1.35f - i * 0.13f).coerceIn(0f, 1f)
            if (t in 0.001f..0.999f) sprite(art.coin, 99 + 93 * t, 60 - 38 * t - 13 * arc(t), 16f, 1f, 1f - t * 0.6f)
        }
    }

    private fun part(start: Float, end: Float) = ((frame - start) / (end - start)).coerceIn(0f, 1f)
    private fun arc(t: Float) = abs(sin(PI * t).toFloat())

    private fun Canvas.car(bitmap: Bitmap?, x: Float, y: Float, squash: Float = 1f, alpha: Float = 1f) {
        bitmap?.let { sprite(it, x, y, 46f, squash, alpha) }
    }

    private fun Canvas.sprite(bitmap: Bitmap, x: Float, y: Float, w: Float, squash: Float, alpha: Float) {
        val h = w * bitmap.height / bitmap.width * squash
        bounds.set(x - w / 2, y - h, x + w / 2, y)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        drawBitmap(bitmap, null, bounds, paint)
    }

    private fun Canvas.spark(x: Float, y: Float, color: Int, alpha: Float) = box(x - 2, y - 2, x + 2, y + 2, color, alpha)

    private fun Canvas.box(left: Float, top: Float, right: Float, bottom: Float, color: Int, alpha: Float = 1f) {
        paint.color = color
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        drawRect(left, top, right, bottom, paint)
    }

    private fun Canvas.label(text: String, x: Float, baseline: Float) {
        paint.color = INK
        paint.textSize = 14f
        paint.textAlign = Paint.Align.CENTER
        paint.isAntiAlias = true
        drawText(text, x, baseline, paint)
        paint.isAntiAlias = false
    }

    private companion object {
        const val INK = 0xff543525.toInt()
        const val GOLD = 0xffdf912e.toInt()
        const val CREAM = 0xffffedbd.toInt()
    }
}
