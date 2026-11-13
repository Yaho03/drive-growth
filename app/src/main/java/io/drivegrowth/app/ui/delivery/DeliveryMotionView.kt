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
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.VehicleId

private val TRAFFIC_IDS = listOf("hyundai_casper", "hyundai_casper_electric", "hyundai_venue", "hyundai_kona", "hyundai_avante",
    "hyundai_sonata", "hyundai_grandeur", "hyundai_ioniq5", "hyundai_ioniq6", "hyundai_ioniq9", "hyundai_nexo", "hyundai_tucson",
    "hyundai_santafe", "hyundai_palisade", "hyundai_staria_tourer", "hyundai_staria_lounge", "genesis_g80", "genesis_g90", "genesis_gv70", "genesis_gv80")

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
    private val destinationSign = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xfffff8e8.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textSize = 12.5f
    }
    private val bannerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PixelStyle.primary }
    private val bannerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = 17f; textAlign = Paint.Align.CENTER
    }
    private val polygon = Path()
    private class TrafficCar(var x: Float, var v: Float, val id: String, val seed: Float)
    private val traffic = mutableListOf<TrafficCar>()
    private val random = java.util.Random()
    private var nextCar = 0L
    private var lastTraffic = 0L
    private val visibleBounds = Rect()
    private val imageBounds = RectF()
    private var scene: DeliveryScene? = null
    private var highway: DeliveryHighway? = null
    private var elapsed = 0L
    private var lastFrame: Long? = null
    private var started = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        tag = "delivery:motion-art"
        background = GradientDrawable().apply {
            setColor(PixelStyle.inset)
            cornerRadius = 12 * resources.displayMetrics.density
        }
        clipToOutline = true
    }

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

    /**
     * HTML 시제품의 탁송 장면: 트럭이 마을 왼쪽 아래 도로(2:1 사선)를 따라 ↘로 들어와 서고, 차를 싣거나 내린 뒤 떠난다.
     * 좌표는 마을 그림(1536×1024) 기준이고 화면 폭 480에 맞춰 줄인다. 차선 가운데는 y = 774 + x/2.
     */
    private fun drawScene(c: Canvas, scene: DeliveryScene, p: Float, h: Float) {
        val departure = scene.kind == DeliverySceneKind.DEPARTURE
        val income = scene.kind == DeliverySceneKind.INCOME_ARRIVAL
        val background = if (income) artwork.destination else artwork.town
        c.drawColor(PixelStyle.inset)
        val sc = 480f / 1536f
        val top = (h - 1024f * sc) * 0.65f
        if (background != null) {
            imageBounds.set(0f, top, 480f, top + 1024f * sc)
            paint.alpha = 255
            c.drawBitmap(background, null, imageBounds, paint)
            if (income) {
                c.save()
                c.translate(742f * sc, top + 322f * sc)
                c.skew(0f, .5f)
                c.drawText(context.getString(io.drivegrowth.app.R.string.delivery_scene_destination_sign),
                    0f, 0f, destinationSign)
                c.restore()
            }
        }
        fun vx(x: Float) = x * sc
        fun vy(y: Float) = top + y * sc
        fun lane(x: Float) = 774f + x / 2f
        fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
        val stop = 430f
        val truckTown = if (departure) when {
            p < 0.26f -> mix(-300f, stop, ease(phase(p, 0f, 0.26f)))
            p > 0.72f -> mix(stop, 1100f, phase(p, 0.72f, 1f).let { it * it })
            else -> stop
        } else mix(-300f, stop, ease(phase(p, 0f, 0.36f)))
        // 정차할 때 앞으로 살짝 쏠렸다 돌아온다
        val lean = if (p in 0.24f..0.34f) -2f * kotlin.math.sin(Math.PI.toFloat() * phase(p, 0.24f, 0.34f)) else 0f
        val truckX = vx(truckTown); val truckBottom = vy(lane(truckTown)) + 14f + lean
        val bedX = truckX - 22f; val bedBottom = truckBottom - 22f
        val transition = phase(p, if (departure) 0.36f else 0.43f, if (departure) 0.68f else 0.75f)
        val onBed = if (departure) transition else 1f - transition
        val sprite = artwork.vehicle(scene.details.vehicleId)
        // 땅 위 자리: 출발은 경사판 앞 도로, 구매 도착은 첫 주차 칸, 수익 도착은 건물 앞
        val groundX: Float; val groundBottom: Float
        when (scene.kind) {
            DeliverySceneKind.VEHICLE_ARRIVAL -> { groundX = vx(253f); groundBottom = vy(635f) + 14f }
            else -> { val gx = stop - 190f; groundX = vx(gx); groundBottom = vy(lane(gx)) + 12f }
        }
        if (onBed < 1f) {
            if (scene.kind == DeliverySceneKind.HELD && p >= 0.75f) {
                box(c, groundX, groundBottom)
            } else {
                if (scene.kind == DeliverySceneKind.VEHICLE_ARRIVAL && p >= 0.75f) {
                    val color = scene.details.rarity?.let(PixelStyle::rarityColor) ?: PixelStyle.focus
                    val a = (255 * (1f - phase(p, 0.85f, 1f)) + 80).toInt().coerceAtMost(255)
                    rect(c, groundX - 30, groundBottom - 3, groundX + 30, groundBottom + 1, (a shl 24) or (color and 0xffffff))
                }
                if (onBed == 0f) image(c, sprite, groundX, groundBottom, 70f)
            }
        }
        if (transition > 0 && transition < 1) {   // 경사판
            polygon(c, PixelStyle.muted, truckX - 58, truckBottom - 19, truckX - 94, truckBottom - 2,
                truckX - 80, truckBottom + 3, truckX - 43, truckBottom - 16)
        }
        image(c, artwork.truck, truckX, truckBottom, 146f)
        if (onBed > 0f) {
            val hop = if (transition > 0f && transition < 1f) 4f * kotlin.math.sin(Math.PI.toFloat() * transition) else 0f
            image(c, sprite, mix(groundX, bedX, onBed), mix(groundBottom, bedBottom, onBed) - hop, 70f)
        }
        // 먼지: 정차와 경사판이 닿을 때
        for (index in 0..5) {
            val t0 = if (index < 3) 0.24f else if (departure) 0.36f else 0.43f
            val life = phase(p, t0, t0 + 0.18f)
            if (life <= 0f || life >= 1f) continue
            val side = if (index % 2 == 0) -1f else 1f
            val x = (if (index < 3) truckX + 50f else truckX - 80f) + side * life * (10f + index * 4f)
            val y = truckBottom - 4f - life * (8f + index * 2f)
            rect(c, x, y, x + 4f, y + 4f, (((1f - life) * 200).toInt() shl 24) or 0xf0ead8)
        }
        if (departure && p > 0.74f) banner(c, "탁송 출발! · ${scene.details.destinationName ?: "목적지"}", phase(p, 0.74f, 0.86f))
        if (income && p > 0.55f) {
            val stampY = h * 0.21f
            c.save()
            c.rotate(-6f, 240f, stampY)
            val grow = 1f + 0.6f * (1f - phase(p, 0.55f, 0.65f))
            c.scale(grow, grow, 240f, stampY)
            rect(c, 170f, stampY - 16, 310f, stampY + 16, 0xfffff8e8.toInt())
            c.drawText(context.getString(io.drivegrowth.app.R.string.delivery_scene_income_arrival), 240f, stampY + 6, stamp)
            c.restore()
        }
        if (income && p > 0.7f) {
            for (index in 0..3) {
                val lift = phase(p, 0.7f + index * 0.04f, 1f)
                val x = groundX + index * 16 + lift * 120
                val y = groundBottom - 50 - lift * (90 + index * 7)
                rect(c, x, y, x + 12, y + 12, PixelStyle.gold)
                rect(c, x + 2, y + 2, x + 5, y + 10, 0xffb67b24.toInt())
            }
        }
        if (scene.kind == DeliverySceneKind.VEHICLE_ARRIVAL && p > 0.8f) banner(c, "구매 탁송 도착 · ${scene.details.vehicleName ?: ""}", phase(p, 0.8f, 0.9f))
    }

    /** HTML의 가운데 띠("탁송 출발! · 학교 18km") */
    private fun banner(c: Canvas, label: String, pop: Float) {
        val scale = 0.6f + 0.4f * pop + 0.12f * kotlin.math.sin(Math.PI.toFloat() * pop)
        c.save()
        c.scale(scale, scale, 240f, 34f)
        val w = bannerText.measureText(label) + 36f
        bannerFill.alpha = (255 * pop).toInt()
        c.drawRoundRect(240f - w / 2, 16f, 240f + w / 2, 52f, 10f, 10f, bannerFill)
        bannerText.alpha = (255 * pop).toInt()
        c.drawText(label, 240f, 40f, bannerText)
        c.restore()
    }

    private fun drawHighway(c: Canvas, highway: DeliveryHighway, elapsed: Long, h: Float) {
        c.drawColor(0xff72a449.toInt())
        val background = artwork.highway
        val artScale = 0.52f
        val offset = (elapsed * 0.24f % 366) * artScale
        if (background != null) {
            val top = (h - 220) / 2f - offset / 2f
            imageBounds.set(-offset, top, background.width * artScale - offset, top + background.height * artScale)
            paint.alpha = 255
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
        drawTraffic(c, elapsed, truckX, bottom)   // 아래 차선이 화면 앞쪽이라 트럭 위에 그린다
    }

    /**
     * HTML처럼 옆 차선(먼 쪽)에 차종·빠르기가 매번 다른 차가 지나간다. 빠른 차는 뒤에서 와서 앞지르고, 느린 차는 앞에서 나타나 뒤로 처진다.
     * 앞차에 가까워지면 앞차 빠르기로 따라가고, 달리는 동안 1~2픽셀 떨며 뒤로 작은 먼지를 남긴다. 트럭은 위 차선, 옆 차는 아래 차선(24 아래), 기울기 1/2.
     */
    private fun drawTraffic(c: Canvas, elapsed: Long, truckX: Float, truckBottom: Float) {
        val dt = if (lastTraffic == 0L || elapsed < lastTraffic) 0f else ((elapsed - lastTraffic) / 1000f).coerceAtMost(0.1f)
        lastTraffic = elapsed
        if (elapsed >= nextCar) {
            val fast = random.nextFloat() < 0.6f
            val v = if (fast) 30f + random.nextFloat() * 70f else -(20f + random.nextFloat() * 45f)
            val x = if (fast) -80f else 560f
            if (traffic.none { kotlin.math.abs(it.x - x) < 110f }) traffic += TrafficCar(x, v, TRAFFIC_IDS[random.nextInt(TRAFFIC_IDS.size)], random.nextFloat() * 10f)
            nextCar = elapsed + 1200 + random.nextInt(2600)
        }
        traffic.sortBy { it.x }
        traffic.forEachIndexed { index, car -> traffic.getOrNull(index + 1)?.let { ahead -> if (ahead.x - car.x < 90f && car.v > ahead.v) car.v = ahead.v } }
        traffic.forEach { it.x += it.v * dt }
        traffic.removeAll { it.x < -120f || it.x > 620f }
        for (car in traffic) {
            val bottom = truckBottom + 24f + (car.x - truckX) / 2f
            val bob = if (((elapsed / 90 + car.seed).toInt()) % 2 == 0) 1f else 0f
            for (i in 0..2) {   // 먼지
                val life = ((elapsed / 600f + i / 3f + car.seed) % 1f)
                val dx = car.x - 30f - life * 26f
                rect(c, dx, bottom - 6f - life * 12f, dx + 4f, bottom - 2f - life * 12f, (((1f - life) * 170).toInt() shl 24) or 0xf0ead8)
            }
            image(c, artwork.vehicle(VehicleId(car.id)), car.x, bottom - bob, 60f)
        }
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
        paint.alpha = 255   // 반투명 먼지를 그린 뒤에도 그림은 불투명하게
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
