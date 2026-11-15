package io.drivegrowth.app.ui.delivery

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.ReferenceSceneArt
import io.drivegrowth.app.ui.presentation.ReferenceSceneTypography
import io.drivegrowth.core.contract.VehicleId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** #294 원본 1536×1024의 하역선 중앙을 장면 좌표로 옮긴다. */
private object IncomeArrivalArt {
    private const val zoom = 1.05f
    private const val cropLeft = 50f
    private const val cropTop = 95f
    const val laneSlope = .255f

    fun placeBackground(bounds: RectF) {
        bounds.set(-cropLeft * zoom, -cropTop * zoom, (1536f - cropLeft) * zoom, (1024f - cropTop) * zoom)
    }

    fun truckX(originalX: Float) = originalX + 434f

    fun groundY(stageX: Float) =
        (522f - cropTop) * zoom + (stageX - (585f - cropLeft) * zoom) * laneSlope
}

internal class DeliveryArtwork(context: Context, private val sprites: VehicleSprites) {
    private val assets = context.assets
    val truck by lazy {
        listOf("chassis", "wheels0", "wheels1", "bed", "rail", "cab", "stripe", "beacon0", "beacon1", "strap")
            .map { name -> checkNotNull(load("pixel/delivery/truck-layers/$name.png")) }
    }
    val town by lazy { load("pixel/town.png", 2) }
    val highway by lazy { load("pixel/delivery/highway.png") }
    val destination by lazy { load("pixel/delivery/destination.png", 2) }
    val passingCars by lazy {
        listOf(
            "hyundai_casper", "hyundai_casper_electric", "hyundai_venue", "hyundai_kona", "hyundai_avante",
            "hyundai_sonata", "hyundai_grandeur", "hyundai_ioniq5", "hyundai_ioniq6", "hyundai_ioniq9",
            "hyundai_nexo", "hyundai_tucson", "hyundai_santafe", "hyundai_palisade", "hyundai_staria_tourer",
            "hyundai_staria_lounge", "genesis_g80", "genesis_g90", "genesis_gv70", "genesis_gv80",
        ).mapNotNull { vehicle(VehicleId(it)) }
    }

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
    private val sceneArt = ReferenceSceneArt(context)
    private val sceneText = ReferenceSceneTypography(context)
    private val paint = Paint()
    private val stamp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffe8483f.toInt()
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val departureText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xfffff6dc.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 40f
        typeface = sceneText.notoBlack
    }
    private val polygon = Path()
    private val truckMatrix = Matrix()
    private val tiltValues = FloatArray(9)
    private var strapColor = 0
    private var strapTint: PorterDuffColorFilter? = null
    private var traffic: DeliveryTraffic? = null
    private var departureLabel = ""
    private val visibleBounds = Rect()
    private val imageBounds = RectF()
    private var scene: DeliveryScene? = null
    private var highway: DeliveryHighway? = null
    private var elapsed = 0L
    private var lastFrame: Long? = null
    private var started = false
    var onSceneFrame: ((Float) -> Unit)? = null

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
        if (scene != value) {
            val destination = value.details.destinationName
            val distance = value.details.estimatedMeters
            val label = when {
                value.kind == DeliverySceneKind.VEHICLE_ARRIVAL -> context.getString(R.string.delivery_scene_vehicle_arrival)
                value.kind == DeliverySceneKind.HELD -> context.getString(R.string.delivery_scene_held)
                destination != null && distance != null ->
                    context.getString(R.string.delivery_departure_banner_route, destination, deliveryDistance(distance))
                destination != null -> context.getString(R.string.delivery_departure_banner_destination, destination)
                else -> context.getString(R.string.delivery_departure_banner)
            }
            departureLabel = TextUtils.ellipsize(label, departureText, 1080f, TextUtils.TruncateAt.END).toString()
        }
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
        onSceneFrame = null
        scene = null
        highway = null
    }

    private fun resetClock() {
        elapsed = 0L
        lastFrame = null
        started = false
        traffic?.reset()
    }

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (highway == null || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, resolveSize(width * 736 / 1304, heightMeasureSpec))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val current = scene
        val road = highway
        if (current == null && road == null || width == 0 || height == 0) return
        if (!presentationAllowed()) {
            lastFrame = null
            return
        }
        val duration = current?.kind?.durationMillis ?: 6000L
        val visible = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            hasWindowFocus() && getLocalVisibleRect(visibleBounds)
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        // 고속도로는 주차 허용 화면의 장식이며 관측 거리와 독립적으로 움직인다.
        val running = visible && motionEnabled && (current != null && elapsed < duration || road != null)
        if (!motionEnabled && road != null && elapsed != 0L) resetClock()
        if (running) {
            val now = SystemClock.uptimeMillis()
            val frameMillis = lastFrame?.let { now - it } ?: 0L
            if (road != null && frameMillis > 0L) {
                val trafficStep = frameMillis.coerceAtMost(100L)
                val lane = traffic ?: DeliveryTraffic(artwork.passingCars.size).also { traffic = it }
                lane.advance(trafficStep)
                elapsed += trafficStep
            } else {
                elapsed += frameMillis
            }
            lastFrame = now
        } else {
            lastFrame = null
            if (!motionEnabled && current != null) elapsed = duration
        }
        if (visible && current != null && !started && (running || !motionEnabled)) {
            started = true
            onStarted(current.key, current.kind.cue)
        }
        val stageWidth = if (road == null) STAGE_WIDTH else 1304f
        val stageHeight = if (road == null) STAGE_HEIGHT else 736f
        val scale = minOf(width / stageWidth, height / stageHeight)
        canvas.save()
        canvas.drawColor(if (road != null) 0xff72a449.toInt() else PixelStyle.inset)
        canvas.translate((width - stageWidth * scale) / 2f, (height - stageHeight * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, STAGE_WIDTH, STAGE_HEIGHT)
        if (current != null) {
            val time = (elapsed.coerceAtMost(duration)) / 1000f
            drawScene(canvas, current, time)
            onSceneFrame?.invoke(time)
        }
        if (road != null) {
            canvas.save()
            canvas.clipRect(0f, 0f, 1304f, 736f)
            drawHighway(canvas, road, if (motionEnabled) elapsed else 0)
            canvas.restore()
        }
        canvas.restore()
        if (running && (current == null || elapsed < duration)) postInvalidateDelayed(32)
    }

    private fun drawScene(c: Canvas, scene: DeliveryScene, t: Float) {
        val departure = scene.kind == DeliverySceneKind.DEPARTURE
        val income = scene.kind == DeliverySceneKind.INCOME_ARRIVAL
        val held = scene.kind == DeliverySceneKind.HELD
        paint.alpha = 255
        val background = if (income) artwork.destination else artwork.town
        if (background != null) {
            if (income) IncomeArrivalArt.placeBackground(imageBounds)
            else imageBounds.set(0f, -265f * TOWN_ZOOM, 1536f * TOWN_ZOOM, (1024f - 265f) * TOWN_ZOOM)
            c.drawBitmap(background, null, imageBounds, paint)
        }
        val truckPosition = if (departure && t >= 1.9f) {
            mix(15f, 80f, easeIn(phase(t, 1.9f, 2.5f)))
        } else mix(if (income) -34f else -24f, 15f, easeOut(phase(t, 0f, if (departure) .6f else .4f)))
        val slide = -4f * if (departure) {
            easeOut(phase(t, .60f, .82f)) - inOut(phase(t, 1.7f, 1.9f))
        } else easeOut(phase(t, .40f, .58f)) - inOut(phase(t, 1.20f, 1.45f))
        val tilt = .1203f * if (departure) {
            easeIn(phase(t, .76f, 1f)) - easeOut(phase(t, 1.6f, 1.75f))
        } else easeIn(phase(t, .50f, .66f)) - easeOut(phase(t, 1.12f, 1.25f))
        val truckX = truckPosition * TOWN_UNIT
        val art = artwork.vehicle(scene.details.vehicleId)
        val groundX = 6.8f * TOWN_UNIT
        val groundY = townGround(groundX)
        val rear = when {
            departure && t >= 1f -> mix(-8.2f, 1f, inOut(phase(t, 1f, 1.5f)))
            !departure && t < 1.05f -> mix(1f, -8.2f, inOut(phase(t, .66f, 1.05f)))
            else -> -8.2f
        }
        val onBed = rear >= -4f
        if (!income && !onBed && (!held || t < 1.05f)) {
            val ground = when {
                departure && t < 1f -> mix(-26f * TOWN_UNIT, groundX, easeOut(phase(t, .2f, 1f)))
                departure || t < 1.05f -> (truckPosition + slide + rear + 4f) * TOWN_UNIT
                else -> groundX
            }
            if (scene.kind == DeliverySceneKind.VEHICLE_ARRIVAL && t >= 1.4f) {
                drawArrivalRing(c, groundX, groundY + 10f, t, scene)
            }
            car(c, art, ground, townGround(ground), 2f * TOWN_ZOOM)
        }
        val leaningSince = if (departure) .5f else .32f
        val lean = -.07f * sin(PI.toFloat() * phase(t, leaningSince, leaningSince + .22f)) +
            .03f * sin(PI.toFloat() * phase(t, leaningSince + .22f, leaningSince + .40f))
        val rolling = if (departure) t < .55f || t > 1.9f else t < .36f
        val blinking = if (departure) t >= 1.9f else t < .40f || t > 1.45f
        val strap = if (departure) phase(t, 1.75f, 1.90f) else if (t < .60f) 1f else 0f
        val color = scene.details.rarity?.let(PixelStyle::rarityColor) ?: 0xffd6dce6.toInt()
        val hop = if (departure) {
            .5f * sin(PI.toFloat() * phase(t, 1.10f, 1.22f)) +
                .7f * sin(PI.toFloat() * phase(t, 1.50f, 1.62f))
        } else 0f
        val truckOriginX = truckX + 2.5f * TOWN_UNIT
        val truckOriginY = (715f - 265f) * TOWN_ZOOM + truckX / 2f - 1.25f * TOWN_UNIT
        c.save()
        if (income) {
            val destinationX = IncomeArrivalArt.truckX(truckOriginX)
            // 차체 원점에서 떨어진 앞·뒤 바퀴가 하역선에 닿도록 높이를 맞춘다.
            val destinationY = IncomeArrivalArt.groundY(destinationX) - 116f
            c.translate(destinationX, destinationY)
            c.skew(0f, IncomeArrivalArt.laneSlope - .5f)
            c.translate(-truckOriginX, -truckOriginY)
        }
        truck(c, truckOriginX, truckOriginY,
            3f * TOWN_ZOOM, slide, tilt, lean, if (rolling) (t / .09f).toInt() % 2 else 0,
            if (blinking) (t / .6f).toInt() % 2 else 0, art.takeIf { onBed }, rear, hop, strap, color)
        c.restore()
        if (held && t >= 1.05f && t < 1.45f) {
            val hold = inOut(phase(t, 1.1f, 1.45f))
            car(c, art, mix(groundX, 150f, hold), mix(townGround(groundX), 52f, hold) -
                160f * sin(PI.toFloat() * hold), mix(2f * TOWN_ZOOM, .25f, hold))
        }
        if (income && t >= 1.10f) {
            val drop = easeIn(phase(t, 1.1f, 1.26f))
            val scale = mix(1.8f, 1f, drop) * (1f - .08f * sin(PI.toFloat() * phase(t, 1.26f, 1.4f)))
            c.save()
            c.translate(1050f, 155f)
            c.rotate(-7f)
            c.scale(scale, scale)
            val alpha = (255f * phase(t, 1.1f, 1.16f)).roundToInt()
            fun seal(color: Int, inset: Float) {
                paint.color = color
                paint.alpha = alpha
                c.drawRoundRect(-190f + inset, -52f + inset, 190f - inset, 52f - inset,
                    16f - inset / 2f, 16f - inset / 2f, paint)
            }
            seal(0xffe8483f.toInt(), 0f)
            seal(0xfffff8e8.toInt(), 6f)
            seal(0xffe8483f.toInt(), 10f)
            seal(0xfffff8e8.toInt(), 13f)
            stamp.textSize = 50f
            stamp.typeface = sceneText.notoBlack
            stamp.alpha = alpha
            c.drawText(context.getString(R.string.delivery_scene_income_arrival), 0f, 17f, stamp)
            c.restore()
            val unloadX = IncomeArrivalArt.truckX(truckOriginX) - 46f
            drawIncomeCoins(c, t, unloadX, IncomeArrivalArt.groundY(unloadX) - 60f)
        }
        if (departure) {
            drawDust(c, t, .5f, 8, 27f * TOWN_UNIT, townGround(27f * TOWN_UNIT), 60f, 3)
            drawDust(c, t, 1f, 12, 11f * TOWN_UNIT, townGround(11f * TOWN_UNIT) + 20f, 90f, 7)
            if (scene.details.purchase && t > 1.88f && t < 2.3f) for (i in 0..3) {
                val alpha = sin(PI.toFloat() * phase(t, 1.9f + i * .05f, 2.2f + i * .05f))
                val x = 20f * TOWN_UNIT - 90f + i * 56f
                val y = townGround(20f * TOWN_UNIT) - 65f - (i % 3) * 16f
                val ink = (color and 0x00ffffff) or ((255f * alpha).roundToInt() shl 24)
                rect(c, x - 6f, y - 2f, x + 6f, y + 2f, ink)
                rect(c, x - 2f, y - 6f, x + 2f, y + 6f, ink)
            }
        } else {
            val dustX = 11f * TOWN_UNIT
            if (income) {
                val bayX = IncomeArrivalArt.truckX(dustX)
                drawDust(c, t, .66f, 10, bayX, IncomeArrivalArt.groundY(bayX), 80f, 13)
            } else {
                drawDust(c, t, .66f, 10, dustX, townGround(dustX) + 20f, 80f, 13)
            }
            if (!income && !held) {
                drawDust(c, t, 1.4f, 10, groundX, groundY, 70f, 11)
                drawArrivalBurst(c, t, groundX, groundY - 60f, color)
            }
        }
        if (departure && t >= 1.95f) drawDepartureBanner(c, phase(t, 1.95f, 2.25f), phase(t, 1.95f, 2.03f))
        if (!departure && !income) {
            val start = if (held) 1.55f else 1.5f
            if (t >= start) drawDepartureBanner(c, phase(t, start, start + .3f), phase(t, start, start + .08f))
        }
    }

    private fun drawDepartureBanner(c: Canvas, progress: Float, opacity: Float) {
        val t = progress - 1f
        val enter = 1f + 2.70158f * t * t * t + 1.70158f * t * t
        val halfWidth = departureText.measureText(departureLabel) / 2f + 36f
        c.save()
        c.translate(STAGE_WIDTH / 2, 149f)
        c.scale(enter, enter)
        paint.color = if (scene?.kind == DeliverySceneKind.DEPARTURE) 0xff2f6fe8.toInt() else 0xf0121622.toInt()
        paint.alpha = (paint.alpha * opacity).roundToInt()
        departureText.alpha = (255f * opacity).roundToInt()
        c.drawRoundRect(-halfWidth, -39f, halfWidth, 39f, 20f, 20f, paint)
        c.drawText(departureLabel, 0f, 15f, departureText)
        c.restore()
    }

    private fun referenceSample(index: Int, salt: Int): Float {
        val value = sin(index * 127.1 + salt * 311.7) * 43758.5453
        return (value - floor(value)).toFloat()
    }

    private fun drawDust(
        c: Canvas, time: Float, start: Float, count: Int, x: Float, y: Float, spread: Float, seed: Int,
    ) {
        if (time < start || time > start + .7f) return
        val progress = easeOut(phase(time, start, start + .6f))
        val alpha = (255f * (1f - phase(time, start + .3f, start + .65f))).roundToInt()
        for (i in 0 until count) {
            val sign = if (i % 2 == 0) -1f else 1f
            val size = 8f + (referenceSample(i, seed + 2) * 2f).toInt() * 4f
            val left = ((x + sign * progress * (20f + referenceSample(i, seed) * spread) - size / 2) / 4f).roundToInt() * 4f
            val top = ((y - progress * (6f + referenceSample(i, seed + 1) * 34f) - size / 2) / 4f).roundToInt() * 4f
            val color = if (i % 3 == 0) 0x00b5b5c3 else 0x00c3cce6
            rect(c, left, top, left + size, top + size, (alpha shl 24) or color)
        }
    }

    private fun drawArrivalRing(c: Canvas, x: Float, y: Float, t: Float, scene: DeliveryScene) {
        val visibility = phase(t, 1.4f, 1.46f) * (1f - phase(t, 2f, 2.4f))
        val color = scene.details.rarity?.let(PixelStyle::rarityColor) ?: PixelStyle.focus
        paint.color = color
        paint.alpha = (255 * visibility).toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        c.save()
        c.rotate(-29f, x, y)
        val pulse = 1f + .06f * sin((t - 1.4f) * 6f)
        c.scale(pulse, pulse, x, y)
        c.drawOval(x - 54f, y - 14f, x + 54f, y + 14f, paint)
        c.restore()
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawArrivalBurst(c: Canvas, t: Float, x: Float, y: Float, color: Int) {
        if (t < 1.42f || t > 2.42f) return
        val travel = easeOut(phase(t, 1.42f, 2.12f))
        val opacity = 1f - phase(t, 1.82f, 2.37f)
        for (index in 0 until 12) {
            val angle = index * (2.0 * PI / 12) + referenceSample(index, 21) * .4
            val d = 120f * (.55f + .45f * referenceSample(index, 22)) * travel
            val px = x + cos(angle).toFloat() * d
            val py = y + sin(angle).toFloat() * d * .72f
            val size = if (index % 3 == 0) 8f else 4f
            val rgb = if (index % 2 == 0) color and 0xffffff else 0xffffff
            val ink = rgb or ((255f * opacity).roundToInt() shl 24)
            if (index % 2 == 0) rect(c, px - size, py - size, px + size, py + size, ink)
            else {
                rect(c, px - size * 1.5f, py - size / 2, px + size * 1.5f, py + size / 2, ink)
                rect(c, px - size / 2, py - size * 1.5f, px + size / 2, py + size * 1.5f, ink)
            }
        }
    }

    private fun drawIncomeCoins(c: Canvas, t: Float, startX: Float, startY: Float) {
        for (index in 0..5) {
            val l = (t - (1.25f + index * .05f)) / .45f
            if (l !in 0f..1f) continue
            val e = easeOut(l)
            val x0 = startX + (index - 2.5f) * 18f
            val x = bezier(x0, x0 + 160f, 1200f, e)
            val y = bezier(startY, startY - 300f - index * 14f, 52f, e)
            val spin = abs(cos(l * 9f + index))
            val halfWidth = 21f * when { spin > .66f -> 1f; spin > .33f -> .6f; else -> .25f }
            val left = ((x - 20f) / 4f).roundToInt() * 4f
            val top = ((y - 20f) / 4f).roundToInt() * 4f
            paint.alpha = 255
            imageBounds.set(left + 21f - halfWidth, top, left + 21f + halfWidth, top + 42f)
            c.drawBitmap(sceneArt.coin, null, imageBounds, paint)
        }
    }

    private fun drawHighway(c: Canvas, highway: DeliveryHighway, elapsed: Long) {
        paint.alpha = 255
        val t = elapsed / 1000f
        val offset = (t * 240f % 366f) * 1.4f
        artwork.highway?.let { background ->
            imageBounds.set(-offset, -offset / 2, 1342f * 1.4f - offset, 760f * 1.4f - offset / 2)
            c.drawBitmap(background, null, imageBounds, paint)
        }
        if (elapsed > 0) traffic?.cars?.forEach { car ->
            val x = car.x
            val y = x / 2 + 11f * 1.4f + 2f
            val bob = ((t * 11f + car.id * 7f).toInt() % 2) * 2f
            for (index in 0..2) {
                val puff = (t * 2.2f + index / 3f + car.id * .17f) % 1f
                val px = ((x - 58f - puff * 110f) / 3f).roundToInt() * 3f
                val py = ((y + 14f - puff * 65f) / 3f).roundToInt() * 3f
                val alpha = (178.5f * (1f - puff)).roundToInt()
                val size = 9f + index % 2 * 3f
                rect(c, px, py, px + size, py + size, (alpha shl 24) or 0x00f0ead8)
            }
            val passing = artwork.passingCars[car.sprite]
            car(c, passing, x, y - bob, 2.5f)
        }
        val truckUnit = 5.6f * 3.8f
        val wheel = if (elapsed > 0) (elapsed / 100 % 2).toInt() else 0
        val beacon = if (elapsed > 0) (elapsed / 600 % 2).toInt() else 0
        val color = highway.details.rarity?.let(PixelStyle::rarityColor) ?: 0xffd6dce6.toInt()
        truck(c, 600f - 4.3f * truckUnit, 300f + 84f * 1.4f - 4.65f * truckUnit, 3.8f,
            0f, 0f, 0f, wheel, beacon, artwork.vehicle(highway.details.vehicleId), 1f, 0f, 1f, color)
    }

    private fun truck(
        canvas: Canvas, originX: Float, originY: Float, scale: Float, slide: Float, tilt: Float,
        lean: Float, wheel: Int, beacon: Int, vehicle: Bitmap?, rear: Float, hop: Float, strap: Float, color: Int,
    ) {
        val layers = artwork.truck
        canvas.save()
        canvas.translate(originX - 52f * scale, originY - 16f * scale)
        canvas.scale(scale, scale)
        transformTruck(canvas, lean, 52f + 11.6f * 5.6f, 16f + 5.8f * 5.6f)
        imageBounds.set(0f, 0f, 132f, 72f)
        paint.alpha = 255
        canvas.drawBitmap(layers[0], null, imageBounds, paint)
        canvas.drawBitmap(layers[1 + wheel], null, imageBounds, paint)
        canvas.save()
        canvas.translate(slide * 5.6f, slide * 2.8f)
        transformTruck(canvas, tilt, 108f, 16f + 3.8f * 5.6f)
        canvas.drawBitmap(layers[3], null, imageBounds, paint)
        val x = 52f + (rear + 1.5f) * 5.6f
        val y = 16f + ((rear + 6.5f) / 2f - 1.6f - hop) * 5.6f
        car(canvas, vehicle, x, y, 2f / 3f)
        imageBounds.set(0f, 0f, 132f, 72f)
        canvas.drawBitmap(layers[4], null, imageBounds, paint)
        if (vehicle != null && strap > 0f) {
            if (strapTint == null || strapColor != color) {
                strapColor = color
                strapTint = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
            paint.colorFilter = strapTint
            paint.alpha = (255f * phase(strap, 0f, .3f)).toInt()
            canvas.drawBitmap(layers[9], null, imageBounds, paint)
            paint.colorFilter = null
            paint.alpha = 255
        }
        canvas.restore()
        for (part in 5..6) canvas.drawBitmap(layers[part], null, imageBounds, paint)
        canvas.drawBitmap(layers[7 + beacon], null, imageBounds, paint)
        canvas.restore()
    }

    private fun transformTruck(canvas: Canvas, angle: Float, x: Float, y: Float) {
        if (angle == 0f) return
        val cosine = cos(angle)
        val sine = sin(angle)
        tiltValues[0] = cosine - sine / 2f
        tiltValues[1] = sine
        tiltValues[3] = -1.25f * sine
        tiltValues[4] = cosine + sine / 2f
        tiltValues[8] = 1f
        truckMatrix.setValues(tiltValues)
        canvas.translate(x, y)
        canvas.concat(truckMatrix)
        canvas.translate(-x, -y)
    }

    private fun car(canvas: Canvas, bitmap: Bitmap?, x: Float, y: Float, scale: Float, mirror: Boolean = false) {
        if (bitmap == null) return
        val halfWidth = bitmap.width * scale / 2f
        val top = y - bitmap.height * scale * .6f
        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, x, y)
        paint.alpha = 255
        imageBounds.set(x - halfWidth, top, x + halfWidth, top + bitmap.height * scale)
        canvas.drawBitmap(bitmap, null, imageBounds, paint)
        canvas.restore()
    }

    private fun townGround(x: Float) = (774f - 265f) * TOWN_ZOOM + x / 2
    private fun easeIn(p: Float) = p * p
    private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)
    private fun inOut(p: Float) = if (p < .5f) 2f * p * p else 1f - 2f * (1f - p) * (1f - p)
    private fun bezier(a: Float, b: Float, c: Float, p: Float) = mix(mix(a, b, p), mix(b, c, p), p)

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

    private companion object {
        const val STAGE_WIDTH = 1360f
        const val STAGE_HEIGHT = 948f
        const val TOWN_ZOOM = 1.3f
        const val TOWN_UNIT = 5.6f * 3f * TOWN_ZOOM
    }
}
