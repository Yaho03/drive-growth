package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.core.contract.Rarity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class RewardCelebrationKind(val durationMillis: Long) {
    GACHA(2100),
    FIRST_GRANT(1750),
    SETTLEMENT(1400),
    EXPANSION(2050),
}

private const val GACHA_WAIT_MS = 2_000L

/** 공개 입력 뒤 원본 타임라인의 등급별 예고부터 재생한다. */
internal fun gachaRevealStart(rarity: Rarity): Float = when (rarity) {
    Rarity.RARE, Rarity.LEGEND -> .9f
    else -> 2.82f
}

/** 확정 화면 위에 겹치는 장식이다. 클릭과 접근성 탐색은 기존 화면이 받는다. */
@SuppressLint("ViewConstructor")
internal class RewardCelebrationView(context: Context) : View(context) {
    private val sceneText = ReferenceSceneTypography(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rays = Path()
    private val vehicleBounds = RectF()
    private var vehicle: Bitmap? = null
    private val visibleBounds = Rect()
    private val main = Handler(context.mainLooper)
    private val displayedKeys = mutableSetOf<String>()
    private var key: String? = null
    private var kind = RewardCelebrationKind.GACHA
    private var accent = PixelStyle.gold
    private var startedAt: Long? = null
    private var waitingSince: Long? = null
    private var pausedAt: Long? = null
    private var previewTime = 0f
    private var gachaRarity = Rarity.COMMON
    var canOpenGacha = false
        private set
    var onGachaReady: (() -> Unit)? = null
    var onGachaOpened: (() -> Unit)? = null
    var onReferenceFrame: ((Float) -> Unit)? = null
    var onGachaAudio: ((Rarity, Boolean) -> Unit)? = null
    var onGachaAudioCancelled: (() -> Unit)? = null
    private var rebuilding = false
    var waitingForReveal = false
        private set
    private var onRevealFinished: ((Boolean) -> Unit)? = null
    var onStarted: ((RewardCelebrationKind) -> Unit)? = null
    var onFirstGrantFrame: ((Long?) -> Unit)? = null
    var playFirstGrantAudio: (() -> (() -> Unit)?)? = null
    private var cancelFirstGrantPlayback: (() -> Unit)? = null
    private val advanceShutter = object : Runnable {
        override fun run() {
            if (!waitingForReveal || !isAttachedToWindow || !hasWindowFocus()) return
            if (!ValueAnimator.areAnimatorsEnabled()) {
                finishGacha()
                return
            }
            if (!canShowEffect()) {
                pauseShutter()
                main.postDelayed(this, 32L)
                return
            }
            val now = SystemClock.uptimeMillis()
            pausedAt?.let { paused -> waitingSince = waitingSince?.plus(now - paused) }
            pausedAt = null
            val since = waitingSince ?: now.also {
                waitingSince = it
                onGachaAudio?.invoke(Rarity.COMMON, false)
            }
            val elapsed = now - since
            if (!canOpenGacha && elapsed >= GACHA_WAIT_MS) {
                canOpenGacha = true
                onGachaReady?.invoke()
            }
            previewTime = (elapsed / 1000f).coerceAtMost(GACHA_WAIT_MS / 1000f)
            invalidate()
            if (waitingForReveal) main.postDelayed(this, 32L)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun render(
        key: String?, kind: RewardCelebrationKind, accent: Int = PixelStyle.gold, vehicle: Bitmap? = null,
    ) {
        if (this.key != key || this.kind != kind) cancelNonGachaFrame()
        this.key = key
        this.kind = kind
        this.accent = accent
        this.vehicle = vehicle
        invalidate()
    }

    fun holdGacha(
        key: String, accent: Int, vehicle: Bitmap?, rarity: Rarity = Rarity.COMMON, onFinished: (Boolean) -> Unit,
    ): Boolean {
        if (onRevealFinished == null && key in displayedKeys) return false
        if (this.key != key || onRevealFinished == null) {
            waitingForReveal = true
            waitingSince = null
            pausedAt = null
            previewTime = 0f
            canOpenGacha = false
            gachaRarity = rarity
        }
        render(key, RewardCelebrationKind.GACHA, accent, vehicle)
        onRevealFinished = onFinished
        resumeShutter()
        return true
    }

    fun openGacha(): Boolean {
        if (!waitingForReveal || !canOpenGacha || !canShowEffect() || !ValueAnimator.areAnimatorsEnabled()) return false
        val result = key ?: return false
        if (!displayedKeys.add(result)) return false
        waitingForReveal = false
        canOpenGacha = false
        main.removeCallbacks(advanceShutter)
        startedAt = SystemClock.uptimeMillis()
        onStarted?.invoke(RewardCelebrationKind.GACHA)
        onGachaAudio?.invoke(gachaRarity, true)
        onGachaOpened?.invoke()
        invalidate()
        return true
    }

    fun finishGacha() = finishGacha(stopAudio = true)

    private fun finishGacha(stopAudio: Boolean, completed: Boolean = false) {
        val finished = onRevealFinished ?: return
        if (stopAudio) onGachaAudioCancelled?.invoke()
        key?.let(displayedKeys::add)
        onRevealFinished = null
        waitingForReveal = false
        canOpenGacha = false
        waitingSince = null
        pausedAt = null
        previewTime = 0f
        main.removeCallbacks(advanceShutter)
        startedAt = null
        key = null
        main.post { finished(completed) }
    }

    private fun canShowEffect() =
        isAttachedToWindow && isShown && hasWindowFocus() && windowVisibility == VISIBLE &&
            width > 0 && height > 0 && getLocalVisibleRect(visibleBounds)

    fun rebuildContent(rebuild: () -> Unit) {
        rebuilding = true
        try {
            rebuild()
        } finally {
            rebuilding = false
        }
    }

    fun over(content: View, fillHeight: Boolean = false, stage: Boolean = false): View {
        if (key == null && !stage) return content
        if (key != null) rebuildContent { (parent as? ViewGroup)?.removeView(this) }
        if (kind == RewardCelebrationKind.SETTLEMENT) {
            // 확정 금액과 잔액의 줄바꿈·글꼴 크기에 관계없이 별도 영역에만 그린다.
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                val stripHeight = (64 * resources.displayMetrics.density).toInt()
                addView(this@RewardCelebrationView, LinearLayout.LayoutParams(MATCH_PARENT, stripHeight))
            }
        }
        // 무대는 결과가 재표시되거나 시스템 애니메이션이 꺼져도 그대로 남는다.
        val frame = if (stage) object : FrameLayout(context) {
            private val stageAccent = accent
            init { setWillNotDraw(false) }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                drawStage(canvas, width.toFloat(), height.toFloat(), stageAccent)
            }
        } else FrameLayout(context)
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, if (fillHeight) MATCH_PARENT else WRAP_CONTENT))
        if (key != null) frame.addView(this, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return frame
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeShutter()
    }

    override fun onDetachedFromWindow() {
        pauseShutter()
        if (!rebuilding) { finishGacha(); cancelNonGachaFrame() }
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            resumeShutter()
            invalidate()
        } else if (!rebuilding) {
            finishGacha()
            cancelNonGachaFrame()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            resumeShutter()
            invalidate()
        } else if (!rebuilding) {
            if (waitingForReveal) pauseShutter() else finishGacha()
            cancelNonGachaFrame()
        }
    }

    fun stopFirstGrantAudio() {
        val cancel = cancelFirstGrantPlayback
        cancelFirstGrantPlayback = null
        cancel?.invoke()
    }

    private fun cancelNonGachaFrame() {
        stopFirstGrantAudio()
        if (kind == RewardCelebrationKind.FIRST_GRANT && startedAt != null) {
            onFirstGrantFrame?.invoke(null)
        }
        startedAt = null
    }

    private fun pauseShutter() {
        main.removeCallbacks(advanceShutter)
        if (waitingSince != null && pausedAt == null) pausedAt = SystemClock.uptimeMillis()
    }

    private fun resumeShutter() {
        main.removeCallbacks(advanceShutter)
        if (waitingForReveal && isAttachedToWindow && hasWindowFocus()) main.post(advanceShutter)
    }

    override fun onDraw(canvas: Canvas) {
        val result = key ?: return
        if (!canShowEffect()) {
            cancelNonGachaFrame()
            return
        }
        if (onRevealFinished != null && !ValueAnimator.areAnimatorsEnabled()) {
            finishGacha()
            return
        }
        if (waitingForReveal) {
            if (onReferenceFrame != null) drawReferenceGacha(canvas, previewTime, sprite = null, rarity = Rarity.COMMON, closedPreview = true)
            else drawGachaFrame(canvas, 0f, previewTime)
            return
        }
        val now = SystemClock.uptimeMillis()
        val begin = startedAt ?: run {
            if (!displayedKeys.add(result)) return
            val animate = ValueAnimator.areAnimatorsEnabled()
            if (kind != RewardCelebrationKind.FIRST_GRANT || animate) onStarted?.invoke(kind)
            if (!animate) {
                if (kind == RewardCelebrationKind.FIRST_GRANT) onFirstGrantFrame?.invoke(null)
                return
            }
            if (kind == RewardCelebrationKind.FIRST_GRANT) {
                cancelFirstGrantPlayback = playFirstGrantAudio?.invoke()
            }
            now.also { startedAt = it }
        }
        val seconds = (now - begin) / 1000f
        val reference = onReferenceFrame
        if (reference != null && onRevealFinished != null) {
            val openAt = if (gachaRarity == Rarity.LEGEND) 3.30f else 2.90f
            val time = gachaRevealStart(gachaRarity) + seconds
            drawReferenceGacha(canvas, time)
            reference(time - openAt - 1.45f)
            if (time >= openAt + 2.35f) finishGacha(stopAudio = false, completed = true)
            else postInvalidateOnAnimation()
            return
        }
        val progress = if (onRevealFinished != null) {
            if (seconds < .63f) .28f + phase(seconds, .08f, .63f) * .24f
            else .52f + (seconds - .63f) / .90f * .48f
        } else (now - begin).toFloat() / kind.durationMillis
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        if (progress >= 1f || !motionEnabled) {
            finishGacha(stopAudio = !motionEnabled, completed = motionEnabled && progress >= 1f)
            if (!motionEnabled) cancelNonGachaFrame()
            else {
                if (kind == RewardCelebrationKind.FIRST_GRANT) onFirstGrantFrame?.invoke(null)
                startedAt = null
            }
            return
        }
        val span = minOf(width, height).toFloat()
        when (kind) {
            RewardCelebrationKind.GACHA -> drawGachaFrame(canvas, progress)
            RewardCelebrationKind.FIRST_GRANT -> {
                onFirstGrantFrame?.invoke(now - begin)
                val sprite = vehicle
                if (sprite != null) {
                    drawStage(canvas, width.toFloat(), height.toFloat(), accent)
                    val hop = if (seconds < .95f) .12f * sin(PI * phase(seconds, .75f, .95f)).toFloat()
                        else .055f * sin(PI * phase(seconds, .95f, 1.12f)).toFloat()
                    val y = height * (.52f - hop)
                    drawVehicle(canvas, sprite, width * .5f, y, minOf(width * .65f / sprite.width, height * .80f / sprite.height))
                }
                drawFirstGrantCoins(canvas, seconds, span)
                drawFirstGrantHorn(canvas, seconds, span)
                if (seconds >= 1.25f) {
                    canvas.save()
                    canvas.scale(width / 1360f, height / 948f)
                    referenceConfetti(canvas, seconds - 1.25f, 26, 33)
                    canvas.restore()
                }
            }
            RewardCelebrationKind.SETTLEMENT -> drawSettlementCoins(canvas, seconds, span)
            RewardCelebrationKind.EXPANSION -> if (seconds >= 1.75f) {
                drawBurst(canvas, width * .72f, height * .72f, phase(seconds, 1.75f, 2.05f), span)
            } else Unit
        }
        postInvalidateOnAnimation()
    }

    private fun drawStage(canvas: Canvas, w: Float, h: Float, color: Int) {
        canvas.drawColor(0xff141a33.toInt())
        ink(0xff1d2547.toInt(), 255)
        canvas.drawRect(0f, h * .62f, w, h, paint)
        ink(0xff3b4a85.toInt(), 255)
        paint.strokeWidth = resources.displayMetrics.density
        for (index in -4..8) {
            val x = w * index / 6
            canvas.drawLine(x, h * .62f, x + w * .65f, h, paint)
            canvas.drawLine(x, h * .62f, x - w * .65f, h, paint)
        }
        paint.style = Paint.Style.STROKE
        for (index in 0..1) {
            val scale = if (index == 0) 1f else .82f
            val half = w * .37f * scale
            val depth = h * .15f * scale
            val y = h * .77f
            rays.rewind()
            rays.moveTo(w / 2, y - depth)
            rays.lineTo(w / 2 + half, y)
            rays.lineTo(w / 2, y + depth)
            rays.lineTo(w / 2 - half, y)
            rays.close()
            ink(color, if (index == 0) 230 else 116)
            paint.strokeWidth = resources.displayMetrics.density * if (index == 0) 2.5f else 1f
            canvas.drawPath(rays, paint)
        }
        paint.style = Paint.Style.FILL
    }

    fun referenceResult(sprite: Bitmap, rarity: Rarity): View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            drawReferenceGacha(canvas, if (rarity == Rarity.LEGEND) 5.65f else 5.25f, sprite, rarity)
        }
    }

    internal fun drawReferenceGacha(
        canvas: Canvas, time: Float, sprite: Bitmap? = vehicle, rarity: Rarity = gachaRarity,
        closedPreview: Boolean = false,
    ) {
        val openAt = if (rarity == Rarity.LEGEND) 3.30f else 2.90f
        val revealAt = openAt + .55f
        val cardAt = revealAt + .90f
        val legend = rarity == Rarity.LEGEND
        val open = phase(time, openAt, revealAt)
        val revealed = time >= revealAt
        val local = (time - revealAt).coerceAtLeast(0f)
        val card = phase(time, cardAt, cardAt + .5f)
        val carX = 680f - 230f * easeOut(card)
        val revealColor = when (rarity) {
            Rarity.LEGEND -> 0xffffc83d.toInt()
            Rarity.RARE -> 0xffff4fa3.toInt()
            Rarity.ADVANCED -> 0xff4f8cff.toInt()
            Rarity.COMMON -> 0xfff4f5fa.toInt()
        }
        val light = when {
            time >= revealAt -> revealColor
            rarity == Rarity.LEGEND && time >= 1.85f -> 0xffffc83d.toInt()
            rarity == Rarity.LEGEND && time >= 1.15f || rarity == Rarity.RARE && time >= 1.4f -> 0xffff4fa3.toInt()
            rarity == Rarity.COMMON -> 0xfff4f5fa.toInt()
            else -> 0xff4f8cff.toInt()
        }
        val camera = 1f + .035f * easeOut(phase(time, .5f, openAt - .9f)) - .035f * easeOut(open)
        val previousAntialias = paint.isAntiAlias
        paint.isAntiAlias = false
        canvas.save()
        canvas.clipRect(0f, 0f, 1360f, 948f)
        canvas.drawColor(0xff141a33.toInt())
        canvas.save()
        referenceShake(canvas, time, revealAt, rarity)
        canvas.scale(camera, camera, 680f, 450f)
        referenceRect(canvas, 0f, 560f, 1360f, 948f, 0xff1d2547.toInt())
        ink(0xff3b4a85.toInt(), 89)
        paint.strokeWidth = 2f
        for (index in -6..6) {
            val x = 680f + index * 120f
            canvas.drawLine(x, 560f, x + 776f, 948f, paint)
            canvas.drawLine(x, 560f, x - 776f, 948f, paint)
        }
        if (!closedPreview) for (i in 0 until 26) referenceSquare(canvas, referenceRandom(i, 40) * 1360f,
            referenceRandom(i, 41) * 520f, 4f, 0xff8796c4.toInt(), .25f + referenceRandom(i, 42) * .4f)
        val firstUpgrade = if (legend) 1.15f else if (rarity == Rarity.RARE) 1.4f else -1f
        val secondUpgrade = if (legend) 1.85f else -1f
        var intensity = easeOut(phase(time, .5f, 1f)) * (.88f + .12f * sin(time * 5f))
        fun ramp(at: Float) {
            if (at < 0f) return
            if (time > at - .25f && time < at) intensity *= 1f - .55f * phase(time, at - .25f, at)
            if (time >= at) intensity += .7f * (1f - phase(time, at, at + .4f))
        }
        ramp(firstUpgrade)
        ramp(secondUpgrade)
        if (closedPreview) intensity = 0f
        intensity *= 1f + open * open * 1.4f
        if (!revealed) {
            for (index in 0..2) {
                val width = 280f + index * 40f + open * open * 60f
                val opacity = when (index) { 0 -> .45f; 1 -> .25f; else -> .12f }
                ink(light, (255f * (opacity * intensity).coerceIn(0f, .9f)).roundToInt())
                rays.reset()
                rays.moveTo(424f, 560f)
                rays.lineTo(936f, 560f)
                rays.lineTo(680f + width, 600f + index * 50f + open * open * 120f)
                rays.lineTo(680f - width, 600f + index * 50f + open * open * 120f)
                rays.close()
                canvas.drawPath(rays, paint)
            }
        }
        when (rarity) {
            Rarity.LEGEND -> {
                referenceDiamond(canvas, carX, 712f, 1f, 0xffffc83d.toInt(), 6f, if (revealed) 1f else .5f + .5f * intensity.coerceIn(0f, 1f))
                if (revealed) {
                    referenceDiamond(canvas, carX, 712f, .86f, 0xffffe38a.toInt(), 2f)
                    referenceDiamond(canvas, carX, 712f, 1.12f, 0xffd98a1c.toInt(), 2f)
                }
            }
            Rarity.RARE -> {
                referenceDiamond(canvas, carX, 712f, 1f, 0xffff4fa3.toInt(), 4f, if (revealed) 1f else .5f + .5f * intensity.coerceIn(0f, 1f))
                if (revealed) referenceDiamond(canvas, carX, 712f, .86f, 0xffff8cc6.toInt(), 2f)
            }
            else -> referenceDiamond(canvas, carX, 712f, 1f,
                if (rarity == Rarity.COMMON) 0xff56679f.toInt() else 0xff4f8cff.toInt(), 4f,
                if (revealed) 1f else .5f + .5f * intensity.coerceIn(0f, 1f))
        }
        if (!closedPreview && !revealed && time >= .5f) for (i in 0..2) {
            if (time - .5f < i * .3f) continue
            val wave = ((time - .5f) / .9f + i / 3f) % 1f
            referenceDiamond(canvas, 680f, 712f, .15f + wave * 1.1f, light, 4f,
                (1f - wave) * intensity.coerceIn(0f, 1f) * .7f)
        }
        if (revealed) {
            val spread = easeOut(phase(local, 0f, .45f))
            if (legend) for (index in 0..2) {
                val radius = 280f - 60f * index
                ink(revealColor, (255f * (.08f + .05f * index) * spread * (.85f + .15f * sin(time * 2f))).roundToInt())
                canvas.drawOval(carX - radius, 602f - radius * .9f, carX + radius, 602f + radius * .9f, paint)
            }
            val rayCount = when (rarity) { Rarity.LEGEND -> 16; Rarity.RARE -> 14; else -> 12 }
            val rayAlpha = when (rarity) { Rarity.LEGEND -> 102; Rarity.RARE -> 82; Rarity.ADVANCED -> 71; else -> 20 }
            val speed = when (rarity) { Rarity.COMMON -> 14f; Rarity.ADVANCED -> 18f; else -> 20f }
            referenceRays(canvas, carX, 602f, spread, local, rayCount, revealColor, rayAlpha, speed)
            if (legend) referenceRays(canvas, carX, 602f, spread, local, 8, 0xffffe38a.toInt(), 77, -14f)
            if (rarity == Rarity.RARE) referenceRays(canvas, carX, 602f, spread, local, 7, 0xffff8cc6.toInt(), 41, -12f)
            if (sprite != null) {
                val zoom = .2f + .8f * pop(phase(local, 0f, .5f))
                val w = sprite.width * 5f * zoom
                val h = sprite.height * 5f * zoom
                val floatY = sin((local - .5f) * 2.4f) * 8f * easeOut(phase(local, .5f, 1f))
                vehicleBounds.set(carX - w * .45f, 712f + floatY - h + 50f * zoom,
                    carX + w * .55f, 712f + floatY + 50f * zoom)
                ink(Color.WHITE, 255)
                paint.isFilterBitmap = false
                canvas.drawBitmap(sprite, null, vehicleBounds, paint)
            }
            val count = when (rarity) { Rarity.LEGEND -> 32; Rarity.RARE -> 26; Rarity.ADVANCED -> 18; else -> 10 }
            referenceBurst(canvas, local, .05f, count, carX, 602f, if (rarity == Rarity.COMMON) 200f else 330f,
                11, rarity, revealColor)
            if (legend) referenceBurst(canvas, local, .45f, 32, carX, 602f, 460f, 23, rarity, revealColor)
            for (i in 0 until 10) {
                val angle = referenceRandom(i, 60) * 2 * PI
                val d = 150f + referenceRandom(i, 61) * 120f
                val opacity = sin(local * 2.6f + i * 1.7f).coerceAtLeast(0f) * phase(local, .6f, .9f)
                referenceStar(canvas, carX + cos(angle).toFloat() * d, 602f + sin(angle).toFloat() * d * .7f,
                    4f, if (i % 2 == 0) 0xfff4f5fa.toInt() else light, opacity)
            }
        } else {
            referenceShutter(canvas, time, open * open, light, intensity.coerceIn(0f, 1f))
        }
        if (!closedPreview && time > .42f && time < 1.1f) for (i in 0 until 18) {
            val p = easeOut(phase(time, .42f, 1f))
            val x = 680f + (if (i % 2 == 0) -1f else 1f) * (280f + p * (60f + referenceRandom(i, 7) * 140f))
            val y = 556f - p * (10f + referenceRandom(i, 8) * 60f)
            referenceSquare(canvas, x, y, 8f + (referenceRandom(i, 9) * 2).toInt() * 4f,
                if (i % 3 == 0) 0xff56679f.toInt() else 0xff8796c4.toInt(), 1f - phase(time, .7f, 1.05f))
        }
        if (!closedPreview && time >= .5f && time < revealAt + .1f) {
            val count = when (rarity) { Rarity.LEGEND -> 26; Rarity.RARE -> 18; Rarity.ADVANCED -> 14; else -> 10 }
            for (i in 0 until count) {
                val drift = ((time - .5f) * .7f + referenceRandom(i, 3)) % 1f
                val x = 680f + (referenceRandom(i, 1) - .5f) * 560f + sin(drift * 6f + i) * 10f
                val opacity = intensity.coerceIn(0f, 1f) * if (drift < .1f) drift * 10f else 1f - drift
                referenceSquare(canvas, x, 600f - drift * 380f,
                    4f + (referenceRandom(i, 2) * 2f).toInt() * 4f, if (i % 3 == 0) 0xfff4f5fa.toInt() else light, opacity)
            }
        }
        canvas.restore()
        if (legend && revealed) {
            referenceConfetti(canvas, local - .1f, 44, 5)
            referenceConfetti(canvas, local - .3f, 14, 9)
        }
        if (firstUpgrade > 0f && time >= firstUpgrade && time < firstUpgrade + .25f) {
            referenceFlash(canvas, light, (if (legend) .35f else .45f) * (1f - phase(time, firstUpgrade, firstUpgrade + .25f)))
        }
        if (secondUpgrade > 0f && time >= secondUpgrade && time < secondUpgrade + .25f) {
            referenceFlash(canvas, light, .55f * (1f - phase(time, secondUpgrade, secondUpgrade + .25f)))
        }
        if (revealed && local < .13f) referenceFlash(canvas, Color.WHITE, .85f * (1f - local / .13f))
        if (legend && local >= .45f && local < .70f) {
            referenceFlash(canvas, 0xffffc83d.toInt(), .35f * (1f - phase(local, .45f, .70f)))
        }
        val ripple = phase(time, openAt - .05f, openAt + .30f)
        if (ripple > 0 && ripple < 1) referenceDiamond(canvas, 680f, 380f,
            .3f * (.3f + ripple * .9f), 0xfff4f5fa.toInt(), 4f, 1f - ripple)
        canvas.restore()
        paint.isAntiAlias = previousAntialias
    }

    private fun referenceShake(canvas: Canvas, time: Float, reveal: Float, rarity: Rarity) {
        var x = 0f
        var y = 0f
        var i = 0
        fun add(start: Float, duration: Float, strength: Float) {
            val index = i++
            val step = time - start
            if (step < 0 || step >= duration) return
            val amp = strength * (1f - step / duration)
            x += sin(step * 70f + index) * amp
            y += cos(step * 55f + index * 2) * amp
        }
        add(.42f, .22f, 8f)
        when (rarity) {
            Rarity.LEGEND -> {
                add(.90f, .25f, 2f); add(1.15f, .30f, 10f)
                add(1.60f, .25f, 2f); add(1.85f, .30f, 18f)
                add(reveal, .45f, 18f); add(reveal + .45f, .30f, 10f)
            }
            Rarity.RARE -> { add(1.15f, .25f, 2f); add(1.40f, .30f, 10f); add(reveal, .25f, 8f) }
            Rarity.ADVANCED -> add(reveal, .15f, 4f)
            Rarity.COMMON -> Unit
        }
        canvas.translate(snap(x, 2f), snap(y, 2f))
    }

    private fun referenceBurst(
        canvas: Canvas, local: Float, start: Float, count: Int, x: Float, y: Float, radius: Float,
        seed: Int, rarity: Rarity, color: Int,
    ) {
        val elapsed = local - start
        if (elapsed < 0 || elapsed > 1f) return
        val spread = easeOut(phase(elapsed, 0f, .7f))
        val opacity = 1f - phase(elapsed, .4f, .95f)
        for (i in 0 until count) {
            val angle = i * 2 * PI / count + referenceRandom(i, seed) * .4
            val distance = radius * (.55f + .45f * referenceRandom(i, seed + 1)) * spread
            val px = x + cos(angle).toFloat() * distance
            val py = y + sin(angle).toFloat() * distance * .72f
            val part = when (i % 4) {
                0 -> color
                1 -> when (rarity) { Rarity.LEGEND -> 0xffffe38a.toInt(); Rarity.RARE -> 0xffff8cc6.toInt(); else -> 0xff9cc1ff.toInt() }
                2 -> 0xfff4f5fa.toInt()
                else -> if (rarity == Rarity.LEGEND && seed == 11) 0xffff8cc6.toInt() else color
            }
            val size = if (rarity != Rarity.COMMON && i % 3 == 0) 8f else 4f
            if (i % 2 == 0) referenceSquare(canvas, px, py, size * 2f, part, opacity)
            else referenceStar(canvas, px, py, size, part, opacity)
        }
    }

    internal fun referenceConfetti(canvas: Canvas, time: Float, count: Int, seed: Int) {
        if (time < 0f) return
        for (i in 0 until count) {
            val speed = 160f + referenceRandom(i, seed) * 170f
            val y = -40f + time * speed - referenceRandom(i, seed + 1) * 700f
            if (y < -40f) continue
            val x = snap(referenceRandom(i, seed + 2) * 1360f + sin(time * 2f + i) * 18f)
            val top = snap((y + 40f) % 1028f - 40f)
            val rotate = (floor(time * 5f + referenceRandom(i, seed + 3) * 4f).toInt() % 4) * 90f
            val w = if (i % 2 == 0) 12f else 8f
            val h = if (i % 2 == 0) 8f else 12f
            val color = if (seed == 9) {
                if (i % 2 == 0) 0xffffc83d.toInt() else 0xffffe38a.toInt()
            } else when (i % 5) {
                0 -> 0xffff4fa3.toInt()
                1 -> 0xff4f8cff.toInt()
                2 -> 0xffffc83d.toInt()
                3 -> 0xfff4f5fa.toInt()
                else -> 0xffa5e86a.toInt()
            }
            canvas.save()
            canvas.rotate(rotate, x + w / 2f, top + h / 2f)
            referenceRect(canvas, x, top, x + w, top + h, color)
            canvas.restore()
        }
    }

    private fun referenceRandom(index: Int, seed: Int): Float {
        val value = sin(index * 127.1 + seed * 311.7) * 43758.5453
        return (value - floor(value)).toFloat()
    }

    private fun snap(value: Float, grid: Float = 4f) = floor(value / grid + .5f) * grid

    private fun referenceSquare(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, opacity: Float) {
        val left = snap(x - size / 2f)
        val top = snap(y - size / 2f)
        ink(color, (255f * opacity.coerceIn(0f, 1f)).roundToInt())
        canvas.drawRect(left, top, left + size, top + size, paint)
    }

    private fun referenceStar(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, opacity: Float) {
        val left = snap(x - size * 1.5f)
        val top = snap(y - size * 1.5f)
        ink(color, (255f * opacity.coerceIn(0f, 1f)).roundToInt())
        canvas.drawRect(left, top + size, left + size * 3f, top + size * 2f, paint)
        canvas.drawRect(left + size, top, left + size * 2f, top + size * 3f, paint)
    }

    private fun referenceFlash(canvas: Canvas, color: Int, opacity: Float) {
        ink(color, (255f * opacity).roundToInt())
        canvas.drawRect(0f, 0f, 1360f, 948f, paint)
    }

    private fun referenceShutter(canvas: Canvas, time: Float, opening: Float, light: Int, intensity: Float) {
        val fall = phase(time, .05f, .42f)
        val bounce = sin(PI.toFloat() * phase(time, .42f, .62f))
        canvas.save()
        canvas.translate(0f, -720f * (1f - fall * fall) - 16f * bounce)
        canvas.scale(1f, 1f - .04f * sin(PI.toFloat() * phase(time, .42f, .54f)), 680f, 560f)
        referenceRect(canvas, 396f, 146f, 964f, 564f, 0xff0b0e1f.toInt())
        referenceRect(canvas, 400f, 150f, 960f, 560f, 0xff4a4a5a.toInt())
        referenceRect(canvas, 424f, 200f, 936f, 560f, 0xff0b0e1f.toInt())
        val insetLight = (.25f + opening * 1.2f).coerceIn(0f, 1f)
        ink(light, (255f * .35f * insetLight).roundToInt())
        canvas.drawRect(424f, 200f, 936f, 560f, paint)
        ink(light, (255f * .60f * insetLight).roundToInt())
        canvas.drawRect(494f, 242f, 866f, 560f, paint)
        ink(0xfff4f5fa.toInt(), (255f * .90f * insetLight).roundToInt())
        canvas.drawRect(574f, 290f, 786f, 560f, paint)
        canvas.save()
        canvas.clipRect(424f, 200f, 936f, 560f)
        val y0 = 200f - 348f * opening
        for (i in 0..11) {
            val y = y0 + i * 29f
            referenceRect(canvas, 424f, y, 936f, y + 29f, if (i % 2 == 0) 0xff858598.toInt() else 0xff7a7a8c.toInt())
            referenceRect(canvas, 424f, y, 936f, y + 3f, 0xffb5b5c3.toInt())
            referenceRect(canvas, 424f, y + 26f, 936f, y + 29f, 0xff4a4a5a.toInt())
        }
        referenceRect(canvas, 647f, y0 + 319f, 713f, y0 + 337f, 0xff0b0e1f.toInt())
        referenceRect(canvas, 650f, y0 + 322f, 710f, y0 + 334f, 0xffb5b5c3.toInt())
        ink(light, (255f * intensity).roundToInt())
        canvas.drawRect(424f, y0 + 348f, 936f, y0 + 360f, paint)
        canvas.restore()
        referenceRect(canvas, 388f, 136f, 972f, 204f, 0xff0b0e1f.toInt())
        referenceRect(canvas, 392f, 140f, 968f, 200f, 0xff7a7a8c.toInt())
        referenceRect(canvas, 392f, 140f, 968f, 146f, 0xffb5b5c3.toInt())
        referenceRect(canvas, 392f, 192f, 968f, 200f, 0xff4a4a5a.toInt())
        for (side in 0..1) {
            val x = 404f + 540f * side
            referenceRect(canvas, x - 2f, 154f, x + 14f, 170f, 0xff0b0e1f.toInt())
            referenceRect(canvas, x, 156f, x + 12f, 168f, 0xffb5b5c3.toInt())
        }
        referenceRect(canvas, 597f, 153f, 763f, 187f, 0xff0b0e1f.toInt())
        referenceRect(canvas, 600f, 156f, 760f, 184f, 0xff2f6bff.toInt())
        ink(Color.WHITE, 255)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 16f
        paint.typeface = sceneText.plexMedium
        val antialias = paint.isAntiAlias
        paint.isAntiAlias = true
        canvas.drawText("GARAGE", 680f, 176f, paint)
        paint.typeface = null
        paint.isAntiAlias = antialias
        canvas.restore()
    }

    private fun referenceRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        ink(color, 255)
        canvas.drawRect(l, t, r, b, paint)
    }

    private fun referenceDiamond(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, stroke: Float, opacity: Float = 1f) {
        rays.reset()
        rays.moveTo(x, y - 150f * size)
        rays.lineTo(x + 300f * size, y)
        rays.lineTo(x, y + 150f * size)
        rays.lineTo(x - 300f * size, y)
        rays.close()
        ink(color, (255f * opacity).roundToInt())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawPath(rays, paint)
        paint.style = Paint.Style.FILL
    }

    private fun referenceRays(
        canvas: Canvas, x: Float, y: Float, spread: Float, time: Float, count: Int,
        color: Int, opacity: Int, speed: Float,
    ) {
        val radius = 900f * spread
        val step = 2 * PI / count
        val halfAngle = PI / count * .55
        val turn = Math.toRadians((time * speed).toDouble())
        rays.reset()
        for (i in 0 until count) {
            val a = turn + step * i
            rays.moveTo(x, y)
            rays.lineTo(x + radius * cos(a - halfAngle).toFloat(), y + radius * sin(a - halfAngle).toFloat())
            rays.lineTo(x + radius * cos(a + halfAngle).toFloat(), y + radius * sin(a + halfAngle).toFloat())
            rays.close()
        }
        ink(color, opacity)
        canvas.drawPath(rays, paint)
    }

    private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)

    private fun pop(p: Float): Float {
        val q = p - 1f
        return 1f + 2.70158f * q * q * q + 1.70158f * q * q
    }

    /** 벽시계와 무관하게 셔터와 공개 프레임을 그린다. */
    internal fun drawGachaFrame(canvas: Canvas, progress: Float, waitingSeconds: Float? = null) {
        val w = width.toFloat()
        val h = height.toFloat()
        val span = minOf(w, h)
        val openingPhase = phase(progress, .28f, .52f)
        val opening = openingPhase * openingPhase * (3f - 2f * openingPhase)
        if (opening < 1f) {
            val left = (w - minOf(w * .78f, h * .68f)) / 2
            val right = w - left
            val top = h * .12f
            val threshold = h * .63f
            val rail = span * .038f
            val fascia = h * .075f
            val doorTop = top + fascia
            val slatHeight = (threshold - doorTop) / 12
            val visibleBottom = threshold - opening * (threshold - doorTop)
            val light = if (waitingSeconds == null) accent else 0xff858598.toInt()
            canvas.save()
            drawStage(canvas, w, h, light)
            canvas.save()
            if (waitingSeconds != null && waitingSeconds < .42f) {
                val fall = phase(waitingSeconds, 0f, .42f)
                canvas.translate(0f, -(1f - fall * fall) * h)
            }
            ink(0xff101322.toInt(), 255)
            canvas.drawRect(left, top, right, threshold, paint)
            ink(light, (65 + 120 * opening).toInt())
            canvas.drawRect(left + rail, doorTop, right - rail, threshold, paint)
            ink(0xff434759.toInt(), 255)
            canvas.drawRect(left, top, left + rail, threshold, paint)
            canvas.drawRect(right - rail, top, right, threshold, paint)
            canvas.save()
            canvas.clipRect(left + rail, doorTop, right - rail, visibleBottom)
            for (index in 0 until 12) {
                val y = doorTop + index * slatHeight - opening * (threshold - doorTop)
                ink(if (index % 2 == 0) 0xff858598.toInt() else 0xff7a7a8c.toInt(), 255)
                canvas.drawRect(left + rail, y, right - rail, y + slatHeight, paint)
                ink(0xffb5b5c3.toInt(), 255)
                canvas.drawRect(left + rail, y, right - rail, y + slatHeight * .11f, paint)
                ink(0xff434759.toInt(), 255)
                canvas.drawRect(left + rail, y + slatHeight * .90f, right - rail, y + slatHeight, paint)
            }
            ink(PixelStyle.panel, 255)
            canvas.drawRect(w * .44f, visibleBottom - slatHeight * 1.05f, w * .56f, visibleBottom - slatHeight * .4f, paint)
            ink(0xffb5b5c3.toInt(), 255)
            canvas.drawRect(w * .45f, visibleBottom - slatHeight * .92f, w * .55f, visibleBottom - slatHeight * .53f, paint)
            canvas.restore()
            ink(light, 255)
            canvas.drawRect(left + rail, visibleBottom - slatHeight * .24f, right - rail, visibleBottom, paint)
            ink(0xff858598.toInt(), 255)
            canvas.drawRect(left - rail * .4f, top, right + rail * .4f, doorTop, paint)
            ink(PixelStyle.primary, 255)
            val badgeHalf = (right - left) * .15f
            canvas.drawRect(w / 2 - badgeHalf, top + fascia * .19f, w / 2 + badgeHalf, top + fascia * .81f, paint)
            ink(Color.WHITE, 255)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = fascia * .46f
            canvas.drawText("GARAGE", w / 2, top + fascia * .66f, paint)
            canvas.restore()
            canvas.restore()
            return
        }

        val reveal = ((progress - .52f) / .48f).coerceIn(0f, 1f)
        val x = w * .5f
        val y = h * .57f
        val radius = maxOf(w, h)
        val sprite = vehicle
        canvas.save()
        shakeReveal(canvas, reveal * .90f, span)
        if (sprite != null) {
            // 공개 막바지에는 정적인 원결과 무대가 자연스럽게 이어지도록 합성한다.
            val fade = ((1f - reveal) / .24f).coerceIn(0f, 1f)
            canvas.saveLayerAlpha(0f, 0f, w, h, (fade * 255).toInt())
            drawStage(canvas, w, h, accent)
        }
        canvas.save()
        if (sprite == null) canvas.clipOutRect(x - span * .29f, y - span * .30f, x + span * .29f, y + span * .24f)
        val layer = when (gachaRarity) {
            Rarity.COMMON -> RayLayer(12, 0xfff4f5fa.toInt(), .08f, 14f)
            Rarity.ADVANCED -> RayLayer(12, 0xff4f8cff.toInt(), .28f, 18f)
            Rarity.RARE -> RayLayer(14, 0xffff4fa3.toInt(), .32f, 20f)
            Rarity.LEGEND -> RayLayer(16, 0xffffc83d.toInt(), .40f, 20f)
        }
        drawRevealRays(canvas, x, y, radius, reveal, layer)
        when (gachaRarity) {
            Rarity.RARE -> drawRevealRays(canvas, x, y, radius, reveal, RayLayer(7, 0xffff8cc6.toInt(), .16f, -12f))
            Rarity.LEGEND -> drawRevealRays(canvas, x, y, radius, reveal, RayLayer(8, 0xffffe38a.toInt(), .30f, -14f))
            else -> Unit
        }
        canvas.restore()
        if (sprite != null) {
            val pop = phase(reveal, 0f, .56f)
            val revealScale = .74f + .26f * (1f - (1f - pop) * (1f - pop))
            val scale = minOf(w * .57f / sprite.width, h * .54f / sprite.height) * revealScale
            drawVehicle(canvas, sprite, x, y, scale)
        }
        drawBurst(canvas, x, y, reveal, span, when (gachaRarity) {
            Rarity.COMMON -> 10
            Rarity.ADVANCED -> 18
            Rarity.RARE -> 26
            Rarity.LEGEND -> 32
        })
        if (sprite != null) canvas.restore()
        canvas.restore()
        if (gachaRarity == Rarity.LEGEND) drawLegendFinish(canvas, x, y, reveal * .90f, span)
    }

    private fun drawLegendFinish(
        canvas: Canvas, x: Float, y: Float, time: Float, span: Float,
        frameWidth: Float = width.toFloat(), frameHeight: Float = height.toFloat(),
    ) {
        val firstFlash = 1f - phase(time, 0f, .20f)
        val encore = phase(time, .45f, .70f)
        val secondFlash = if (time >= .45f) 1f - encore else 0f
        if (time >= .45f && time <= .70f) drawBurst(canvas, x, y, encore, span * 1.35f, 32)
        for (index in 0 until 44) {
            val drift = (time - .10f - index % 7 * .027f) / .8f
            if (drift <= 0f) continue
            val px = frameWidth * fraction(index, 37) + span * .035f * sin(index + drift * 9f)
            val py = frameHeight * (drift - .08f)
            val size = span * (.010f + index % 3 * .004f)
            val color = when (index % 4) {
                0 -> 0xffffc83d.toInt()
                1 -> 0xff5ce4ad.toInt()
                2 -> 0xffff77bb.toInt()
                else -> Color.WHITE
            }
            ink(color, 255)
            canvas.drawRect(px - size, py - size, px + size, py + size, paint)
        }
        if (firstFlash > 0f || secondFlash > 0f) {
            val color = if (firstFlash > 0f) Color.WHITE else 0xffffc83d.toInt()
            ink(color, (firstFlash * 52f + secondFlash * 64f).toInt())
            canvas.drawRect(0f, 0f, frameWidth, frameHeight, paint)
        }
    }

    private fun shakeReveal(canvas: Canvas, time: Float, span: Float) {
        val strength = when (gachaRarity) {
            Rarity.COMMON -> 0f
            Rarity.ADVANCED -> 4f * (1f - phase(time, 0f, .15f))
            Rarity.RARE -> 8f * (1f - phase(time, 0f, .25f))
            Rarity.LEGEND -> if (time < .45f) 18f * (1f - phase(time, 0f, .45f))
                else 10f * (1f - phase(time, .45f, .75f))
        } * span / 948f
        canvas.translate(strength * sin(time * 117f), strength * cos(time * 94f) * .6f)
    }

    private fun drawVehicle(canvas: Canvas, sprite: Bitmap, x: Float, y: Float, scale: Float) {
        val halfWidth = sprite.width * scale / 2
        val halfHeight = sprite.height * scale / 2
        vehicleBounds.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        ink(Color.WHITE, 255)
        canvas.drawBitmap(sprite, null, vehicleBounds, paint)
    }

    private fun drawBurst(canvas: Canvas, x: Float, y: Float, progress: Float, span: Float, count: Int = 18) {
        val distance = span * (.12f + .55f * progress)
        val alpha = ((1f - progress) * 225).toInt()
        for (index in 0 until count) {
            val angle = index * (2 * PI / count)
            val spread = distance * (.5f + index % 4 * .16f)
            val px = x + cos(angle).toFloat() * spread
            val py = y + sin(angle).toFloat() * spread * .62f
            val size = span * (.011f + index % 3 * .006f)
            ink(if (index % 3 == 0) PixelStyle.text else accent, alpha)
            canvas.drawRect(px - size, py - size, px + size, py + size, paint)
        }
    }

    private data class RayLayer(val count: Int, val color: Int, val opacity: Float, val speed: Float)

    private fun drawRevealRays(canvas: Canvas, x: Float, y: Float, radius: Float, reveal: Float, layer: RayLayer) {
        val length = radius * phase(reveal, 0f, .5f)
        val step = 2 * PI / layer.count
        val turn = Math.toRadians((reveal * layer.speed).toDouble())
        rays.rewind()
        for (index in 0 until layer.count) {
            val angle = index * step + turn
            rays.moveTo(x, y)
            rays.lineTo(x + cos(angle).toFloat() * length, y + sin(angle).toFloat() * length)
            rays.lineTo(x + cos(angle + step * .42).toFloat() * length,
                y + sin(angle + step * .42).toFloat() * length)
            rays.close()
        }
        ink(layer.color, ((1f - reveal * .5f) * layer.opacity * 255).toInt())
        canvas.drawPath(rays, paint)
    }

    private fun drawFirstGrantHorn(canvas: Canvas, seconds: Float, span: Float) {
        for (index in 0..2) {
            val start = .60f + index * .08f
            val opacity = sin(PI * phase(seconds, start, start + .25f)).toFloat()
            if (opacity <= 0.001f) continue
            val x = width * (.73f + index * .085f)
            val y = height * (.43f + index * .09f)
            referenceStar(canvas, x, y, span * .035f, 0xffffe38a.toInt(), opacity)
        }
        val scale = pop(phase(seconds, .78f, 1f)) * (1f - phase(seconds, 1.2f, 1.3f))
        if (scale <= .001f) return
        val x = width * .69f
        val y = height * .3f
        canvas.save()
        canvas.scale(scale, scale, x, y)
        ink(0xfff4f5fa.toInt(), 255)
        canvas.drawRoundRect(x, y - span * .16f, x + span * .27f, y, span * .035f, span * .035f, paint)
        ink(0xff0b0e1f.toInt(), 255)
        paint.typeface = sceneText.plexMedium
        paint.textSize = span * .105f
        canvas.drawText("빵!", x + span * .046f, y - span * .046f, paint)
        canvas.restore()
    }

    private fun drawFirstGrantCoins(canvas: Canvas, seconds: Float, span: Float) {
        val horizontalScale = width / 260f
        val verticalScale = height / 360f
        for (index in 0 until 10) {
            val t = seconds - (.45f + index * .05f)
            if (t !in 0f..0.7f) continue
            val vx = (referenceRandom(index, 80) - .5f) * 380f
            val vy = -(560f + referenceRandom(index, 81) * 240f)
            val x = width * .5f + vx * t * horizontalScale
            val y = height * .72f + (vy * t + 850f * t * t) * verticalScale
            drawCoin(canvas, x, y, span * .068f, t * 14f + index,
                (255 * (1f - phase(t, .5f, .7f))).toInt())
        }
    }

    private fun drawSettlementCoins(canvas: Canvas, seconds: Float, span: Float) {
        for (index in 0 until 9) {
            val t = (seconds - (.15f + index * .04f)) / .5f
            if (t !in 0f..1f) continue
            val e = (1f - (1f - t) * (1f - t)) * .4f + t * .6f
            val r = 1f - e
            val x0 = width * (.52f + .03f * (index % 3))
            val x = r * r * x0 + 2f * r * e * (x0 - width * .14f) + e * e * width * .90f
            val y = r * r * height * .82f + 2f * r * e * height * .03f + e * e * height * .24f
            drawCoin(canvas, x, y, span * .112f, t * 9f + index, 255)
        }
    }

    private fun drawCoin(canvas: Canvas, x: Float, y: Float, radius: Float, rotation: Float, alpha: Int) {
        val spin = abs(cos(rotation))
        val halfWidth = radius * when { spin > .66f -> 1f; spin > .33f -> .6f; else -> .25f }
        ink(0xff0b0e1f.toInt(), alpha)
        canvas.drawOval(x - halfWidth * 1.2f, y - radius * 1.12f, x + halfWidth * 1.2f, y + radius * 1.12f, paint)
        ink(0xffd98a1c.toInt(), alpha)
        canvas.drawOval(x - halfWidth, y - radius, x + halfWidth, y + radius, paint)
        ink(0xffffe38a.toInt(), alpha)
        canvas.drawOval(x - halfWidth * .78f, y - radius * .78f, x + halfWidth * .78f, y + radius * .78f, paint)
        ink(0xffd98a1c.toInt(), alpha)
        canvas.drawRect(x - halfWidth * .15f, y - radius * .47f, x + halfWidth * .15f, y + radius * .47f, paint)
    }

    private fun fraction(index: Int, seed: Int) = ((index * seed + 11) % 101) / 101f
    private fun phase(value: Float, start: Float, end: Float) = ((value - start) / (end - start)).coerceIn(0f, 1f)

    private fun ink(color: Int, alpha: Int) {
        paint.color = color
        paint.alpha = alpha
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
