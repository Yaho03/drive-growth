package io.drivegrowth.app.ui.delivery

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.VehicleId

internal class DeliveryArtwork(context: Context, private val sprites: VehicleSprites) {
    private val assets = context.assets
    val truck by lazy { load("pixel/delivery/truck.png") }
    val town by lazy { load("pixel/town.png", 2) }
    val highway by lazy { load("pixel/delivery/highway.png") }
    val destination by lazy { load("pixel/delivery/destination.png", 2) }

    fun vehicle(id: VehicleId?) = id?.let(sprites::bitmap)

    private fun load(name: String, sample: Int = 1): Bitmap? =
        assets.open(name).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

/** 장식용 그림만 그린다. 시간 진행과 건너뛰기에서 거래나 도착 입력을 보내지 않는다. */
@SuppressLint("ViewConstructor")
internal class DeliveryMotionView(
    context: Context,
    private val artwork: DeliveryArtwork,
    private val presentationAllowed: () -> Boolean,
    private val onStarted: (DeliverySceneKey, DeliveryPresentationCue) -> Unit,
) : View(context) {
    private val paint = Paint()
    private val stamp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffe8483f.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val polygon = Path()
    private val visibleBounds = Rect()
    private val imageBounds = RectF()
    private var scene: DeliveryScene? = null
    private var highway: DeliveryHighway? = null
    private var elapsed = 0L
    private var lastFrame: Long? = null
    private var started = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun showScene(value: DeliveryScene) {
        if (scene?.key != value.key) resetClock()
        scene = value
        highway = null
        invalidate()
    }

    fun showHighway(value: DeliveryHighway) {
        if (highway?.details != value.details) resetClock()
        highway = value
        scene = null
        invalidate()
    }

    fun clear() {
        resetClock()
        scene = null
        highway = null
    }

    private fun resetClock() { elapsed = 0L; lastFrame = null; started = false }

    override fun onDetachedFromWindow() {
        lastFrame = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate() else lastFrame = null
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) invalidate() else lastFrame = null
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = scene
        val road = highway
        if (current == null && road == null || width == 0 || height == 0) return
        val duration = current?.kind?.durationMillis ?: 6000L
        val visible = presentationAllowed() && isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            hasWindowFocus() && getLocalVisibleRect(visibleBounds)
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        // 고속도로는 주차 허용 화면의 장식이며 관측 거리와 독립적으로 움직인다.
        val running = visible && motionEnabled && (current != null && elapsed < duration || road != null)
        if (!motionEnabled && road != null) elapsed = 0
        if (running) {
            val now = SystemClock.uptimeMillis()
            elapsed += lastFrame?.let { now - it } ?: 0L
            lastFrame = now
            if (current != null && !started) {
                started = true
                onStarted(current.key, current.kind.cue)
            }
        } else {
            lastFrame = null
            if (!motionEnabled && current != null) elapsed = duration
        }
        val scale = minOf(width / 480f, height / 200f)
        canvas.save()
        canvas.drawColor(PixelStyle.inset)
        canvas.translate((width - 480 * scale) / 2f, 0f)
        canvas.scale(scale, scale)
        val h = height / scale
        canvas.clipRect(0f, 0f, 480f, h)
        if (current != null) drawScene(canvas, current, (elapsed.toFloat() / duration).coerceAtMost(1f), h)
        if (road != null) drawHighway(canvas, road, if (motionEnabled) elapsed else 0, h)
        canvas.restore()
        if (running && (current == null || elapsed < duration)) postInvalidateDelayed(32)
    }

    private fun drawScene(c: Canvas, scene: DeliveryScene, p: Float, h: Float) {
        val departure = scene.kind == DeliverySceneKind.DEPARTURE
        val income = scene.kind == DeliverySceneKind.INCOME_ARRIVAL
        val background = if (income) artwork.destination else artwork.town
        c.drawColor(PixelStyle.inset)
        if (background != null) {
            val height = background.height * 480f / background.width
            imageBounds.set(0f, (h - height) * 0.65f, 480f, (h - height) * 0.65f + height)
            c.drawBitmap(background, null, imageBounds, paint)
        }
        val ground = h * 0.83f
        rect(c, 0f, ground - 27, 480f, h, 0xca20283b.toInt())
        val truckX = if (departure) when {
            p < 0.26f -> mix(-130f, 236f, phase(p, 0f, 0.26f))
            p > 0.72f -> mix(236f, 570f, phase(p, 0.72f, 1f))
            else -> 236f
        } else mix(-130f, 236f, phase(p, 0f, 0.36f))
        val transition = phase(p, if (departure) 0.36f else 0.43f, if (departure) 0.68f else 0.75f)
        val onBed = if (departure) transition else 1f - transition
        val sprite = artwork.vehicle(scene.details.vehicleId)
        if (onBed < 1f) {
            val targetX = if (scene.kind == DeliverySceneKind.VEHICLE_ARRIVAL) 86f else 133f
            if (scene.kind == DeliverySceneKind.HELD && p >= 0.75f) {
                box(c, 87f, ground)
            } else {
                val slot = scene.kind == DeliverySceneKind.VEHICLE_ARRIVAL && p >= 0.75f
                if (slot) {
                    val color = scene.details.rarity?.let(PixelStyle::rarityColor) ?: PixelStyle.focus
                    rect(c, targetX - 44, ground - 4, targetX + 44, ground + 1, color)
                }
                if (onBed == 0f) image(c, sprite, targetX, ground - 4, 70f)
            }
        }
        if (transition > 0 && transition < 1) {
            polygon(c, PixelStyle.muted, truckX - 58, ground - 19, truckX - 94, ground - 2,
                truckX - 80, ground + 3, truckX - 43, ground - 16)
        }
        image(c, artwork.truck, truckX, ground + 4, 146f)
        if (onBed > 0f) image(c, sprite, mix(133f, truckX - 23, onBed), mix(ground - 4, ground - 23, onBed), 70f)
        if (income && p > 0.55f) {
            val stampY = h * 0.21f
            c.save()
            c.rotate(-6f, 240f, stampY)
            rect(c, 170f, stampY - 16, 310f, stampY + 16, 0xfffff8e8.toInt())
            c.drawText(context.getString(io.drivegrowth.app.R.string.delivery_scene_income_arrival), 240f, stampY + 6, stamp)
            c.restore()
        }
        if (income && p > 0.7f) {
            for (index in 0..3) {
                val lift = phase(p, 0.7f + index * 0.04f, 1f)
                val x = 130f + index * 26 + lift * 62
                val y = ground - 36 - lift * (38 + index * 7)
                rect(c, x, y, x + 12, y + 12, PixelStyle.gold)
                rect(c, x + 2, y + 2, x + 5, y + 10, 0xffb67b24.toInt())
            }
        }
    }

    private fun drawHighway(c: Canvas, highway: DeliveryHighway, elapsed: Long, h: Float) {
        c.drawColor(0xff72a449.toInt())
        val background = artwork.highway
        val artScale = 0.52f
        val offset = (elapsed * 0.24f % 366) * artScale
        if (background != null) {
            val top = (h - 220) / 2f - offset / 2f
            imageBounds.set(-offset, top, background.width * artScale - offset, top + background.height * artScale)
            c.drawBitmap(background, null, imageBounds, paint)
        }
        val truckX = 221f
        val shake = if (elapsed > 0 && elapsed / 110 % 2 == 0L) 1.5f else 0f
        val bottom = h * 0.68f - shake
        if (elapsed > 0) for (index in 0..2) {
            val trail = (elapsed / 650f + index / 3f) % 1f
            val x = truckX - 67f - trail * 40f
            val y = bottom - 3f - trail * 22f
            rect(c, x, y, x + 5f, y + 5f, 0xfff0ead8.toInt())
        }
        image(c, artwork.truck, truckX, bottom, 158f)
        image(c, artwork.vehicle(highway.details.vehicleId), truckX - 25, bottom - 29f, 75f)
    }

    private fun box(c: Canvas, x: Float, y: Float) {
        rect(c, x - 23, y - 30, x + 23, y + 3, 0xff88613c.toInt())
        rect(c, x - 24, y - 30, x + 24, y - 23, 0xffc39a63.toInt())
        rect(c, x - 4, y - 30, x + 4, y - 18, PixelStyle.gold)
    }

    private fun image(c: Canvas, bitmap: Bitmap?, x: Float, bottom: Float, width: Float) {
        if (bitmap == null) return
        val height = bitmap.height * width / bitmap.width
        imageBounds.set(x - width / 2, bottom - height, x + width / 2, bottom)
        c.drawBitmap(bitmap, null, imageBounds, paint)
    }

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        c.drawRect(l, t, r, b, paint)
    }

    private fun polygon(c: Canvas, color: Int, vararg points: Float) {
        polygon.reset()
        polygon.moveTo(points[0], points[1])
        for (index in 2 until points.size step 2) polygon.lineTo(points[index], points[index + 1])
        polygon.close()
        paint.color = color
        c.drawPath(polygon, paint)
    }

    private fun phase(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)
    private fun mix(a: Float, b: Float, p: Float) = a + (b - a) * p
}
