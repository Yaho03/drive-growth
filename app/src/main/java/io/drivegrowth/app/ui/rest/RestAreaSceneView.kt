package io.drivegrowth.app.ui.rest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.driving.RestOutcome
import io.drivegrowth.core.driving.RestPlace

/** PR #292의 장면 2·3. 애니메이션 시간은 그림에만 쓰고 읽기 전용 표시는 받은 값으로 갱신한다. */
internal class RestAreaSceneView(context: Context) : LinearLayout(context) {
    private val style = ReferenceHudStyle(context)
    private val artwork = RestAreaArtwork(context).apply {
        background = rounded(Color.BLACK, 12)
        clipToOutline = true
    }
    private val title = label(24, style.signInk).apply {
        setTypeface(typeface, Typeface.BOLD)
        setAccessibilityHeading(true)
    }
    private val status = label(16, style.signInk).apply {
        background = style.detailSurface(active = false)
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }
    private val detail = label(16, CleanUi.MUTED)
    private val target = label(16, CleanUi.MUTED)
    private val progress = RestTimeTrack(context).apply {
        max = 1000
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val timeRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(16, CleanUi.MUTED).apply { text = "도착" })
        addView(progress, LayoutParams(0, dp(18), 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        addView(target)
    }
    private val info = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(title, LayoutParams(-1, -2))
        addView(status, LayoutParams(-2, -2).apply { topMargin = dp(5) })
        addView(timeRow, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(5) })
    }
    private var rendered: RestAreaScene? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = style.signSurface()
        addView(artwork, LayoutParams(-2, -2))
        addView(info, LayoutParams(-1, -2))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) dp(1100)
            else (limit - dp(32)).coerceIn(0, dp(1100))
        val innerWidth = (width - paddingStart - paddingEnd).coerceAtLeast(0)
        val threshold = (dp(760) * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
        val wide = width > threshold && artwork.visibility != GONE
        orientation = if (wide) HORIZONTAL else VERTICAL
        val art = artwork.layoutParams as LayoutParams
        art.width = minOf(dp(if (wide) 264 else 208), innerWidth)
        art.height = art.width / 2
        art.gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        art.rightMargin = if (wide && layoutDirection == View.LAYOUT_DIRECTION_LTR) dp(12) else 0
        art.leftMargin = if (wide && layoutDirection == View.LAYOUT_DIRECTION_RTL) dp(12) else 0
        val copy = info.layoutParams as LayoutParams
        copy.width = if (wide) 0 else -1
        copy.weight = if (wide) 1f else 0f
        copy.topMargin = if (!wide && artwork.visibility != GONE) dp(8) else 0
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    /** 새 정차의 최초 전경 표시에서만 animateArrival을 전달한다. 재생성은 현재 스냅샷을 다시 공급한다. */
    fun render(scene: RestAreaScene?, presentationAllowed: Boolean, animateArrival: Boolean = false) {
        val next = scene.takeIf { presentationAllowed }
        if (next == rendered) return
        val previous = rendered as? RestAreaScene.Parked
        val parked = next as? RestAreaScene.Parked
        rendered = next
        visibility = if (next == null) GONE else VISIBLE
        val playArrival = animateArrival && (previous?.stopId != parked?.stopId || previous?.source != parked?.source)
        artwork.render(parked, playArrival)
        if (next == null) return

        val place = if (parked?.place == RestPlace.DROWSY_SHELTER) "졸음쉼터" else "휴게소"
        title.text = when {
            parked == null -> "휴식 장소 확인 대기"
            parked.ready -> "휴식 보너스 준비됨"
            parked.phase == RestAreaScene.Phase.ARRIVED -> "$place 도착 · P"
            else -> "$place · 쉬는 중"
        }
        val times = parked?.progress
        val statusText = when (parked?.outcome) {
            RestOutcome.ACCEPTED -> "출발하면 보너스 판정이 이어져요"
            RestOutcome.DRIVING_TOO_SHORT -> "직전 운전 시간이 부족해요"
            RestOutcome.PARKING_TOO_SHORT -> "주차 시간이 부족해요"
            RestOutcome.NOT_REST_PLACE -> "인정 장소를 확인하지 못했어요"
            RestOutcome.ALREADY_HANDLED -> "이미 처리한 정차예요"
            RestOutcome.UNCONFIRMED, null -> "휴식 인정 확인 대기"
        }
        status.text = statusText
        detail.visibility = if (times == null) GONE else VISIBLE
        detail.text = times?.let { "쉰 시간 ${duration(it.elapsedMs)} / ${duration(it.requiredMs)}" }
        target.text = times?.let { duration(it.requiredMs) }
        timeRow.visibility = if (times == null) GONE else VISIBLE
        progress.progress = ((times?.fraction ?: 0f) * progress.max).toInt()
    }

    private fun label(size: Int, color: Int) = TextView(context).apply {
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun duration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return "${seconds / 60}분 ${seconds % 60}초"
    }
}

/** ProgressBar의 값 계약을 유지하면서 시안의 레일과 끝 눈금을 그린다. */
private class RestTimeTrack(context: Context) :
    ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {
    private val railPaint = Paint()

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val halfHeight = 5f * density
        val center = height / 2f
        val left = density
        val right = width - density
        if (right <= left) return
        railPaint.color = CleanUi.CHIP
        canvas.drawRect(left, center - halfHeight, right, center + halfHeight, railPaint)
        val fill = (right - left) * progress / max
        if (fill > 0f) {
            railPaint.color = CleanUi.SEA
            canvas.drawRect(left, center - halfHeight, left + fill, center + halfHeight, railPaint)
        }
        railPaint.color = CleanUi.SEA_LIGHT
        canvas.drawRect(right - density, 0f, right + density, height.toFloat(), railPaint)
    }
}

private class RestAreaArtwork(context: Context) : View(context) {
    private val paint = Paint()
    private val spriteBounds = RectF()
    private val sprites = VehicleSprites(context)
    private val sleep by lazy {
        context.assets.open("pixel/rest/ruru-sleep.png").use { BitmapFactory.decodeStream(it) }
    }
    private val window = Path().apply {
        moveTo(13f, 6f); lineTo(100f, 6f); lineTo(117f, 55f); lineTo(10f, 55f); lineTo(10f, 9f); close()
    }
    private var parked: RestAreaScene.Parked? = null
    private var car: Bitmap? = null
    private var motion: ValueAnimator? = null
    private var parkingMotion = false
    private var animationFrame = 1f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun render(value: RestAreaScene.Parked?, animateArrival: Boolean) {
        val old = parked
        parked = value
        visibility = if (value == null) GONE else VISIBLE
        if (old?.vehicleId != value?.vehicleId) {
            car = value?.vehicleId?.let(sprites::bitmap)
            invalidate()
        }
        if (value?.phase == old?.phase && value?.stopId == old?.stopId && value?.source == old?.source) return
        if (parkingMotion && value?.phase == RestAreaScene.Phase.RESTING &&
            value.stopId == old?.stopId && value.source == old.source
        ) return
        stopAnimation()
        if (value?.phase == RestAreaScene.Phase.ARRIVED && animateArrival && canAnimate()) {
            val animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 540 }
            parkingMotion = true
            motion = animator
            animator.addUpdateListener { frame ->
                if (!canAnimate()) stopAnimation()
                else {
                    animationFrame = frame.animatedValue as Float
                    if (frame.animatedFraction == 1f) {
                        parkingMotion = false
                        motion = null
                        syncRestAnimation()
                    }
                    invalidate()
                }
            }
            animator.start()
        } else syncRestAnimation()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) syncRestAnimation() else stopAnimation()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) syncRestAnimation() else stopAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) syncRestAnimation() else stopAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun canAnimate() =
        isAttachedToWindow && isShown && windowVisibility == VISIBLE && hasWindowFocus() && ValueAnimator.areAnimatorsEnabled()

    private fun syncRestAnimation() {
        if (parked?.phase != RestAreaScene.Phase.RESTING || motion != null || !canAnimate()) return
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2700
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { frame ->
                if (canAnimate()) {
                    animationFrame = frame.animatedValue as Float
                    invalidate()
                } else stopAnimation()
            }
            start()
        }
    }

    private fun stopAnimation() {
        val current = motion
        motion = null
        parkingMotion = false
        current?.cancel()
        animationFrame = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = parked ?: return
        val scale = minOf(width / 132f, height / 66f)
        canvas.save()
        canvas.translate((width - 132 * scale) / 2f, (height - 66 * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, 132f, 66f)
        if (state.phase == RestAreaScene.Phase.ARRIVED || parkingMotion) drawArrival(canvas) else drawRest(canvas)
        canvas.restore()
    }

    private fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, rgb: Int) {
        paint.color = rgb or 0xff000000.toInt()
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
    }

    private fun sprite(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
        spriteBounds.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, spriteBounds, paint)
    }

    private fun drawArrival(c: Canvas) {
        rect(c, 0, 0, 132, 8, 0x5d4f9a)
        rect(c, 0, 8, 132, 8, 0x8d62a8)
        rect(c, 0, 16, 132, 8, 0xd9808a)
        rect(c, 0, 24, 132, 8, 0xf3ad7a)
        rect(c, 8, 12, 116, 20, 0xe9dcc4)
        rect(c, 8, 10, 116, 3, 0xc84b4b)
        rect(c, 8, 31, 116, 1, 0xb9a98e)
        for (x in 14..119 step 13) if (x < 58 || x > 72) rect(c, x, 16, 8, 7, 0xffd36b)
        rect(c, 60, 19, 10, 13, 0x5a4a3a)
        rect(c, 44, 2, 44, 8, 0x234573)
        rect(c, 0, 32, 132, 34, 0x4b4f63)
        rect(c, 0, 32, 132, 1, 0x7d8296)
        for (x in PARKING_LINES) for (y in 0..31) rect(c, x + y / 3, 34 + y, 1, 1, 0xd9dce6)
        rect(c, 118, 8, 2, 26, 0x2a2d3a)
        rect(c, 115, 6, 8, 2, 0x2a2d3a)
        rect(c, 116, 8, 6, 1, 0xffe9a8)
        val x = (-60 + 96 * animationFrame).toInt()
        val y = (-22 + 32 * animationFrame).toInt()
        car?.let { sprite(c, it, x, y, 56, 51) } ?: drawNeutralCar(c, x + 10, y + 26)
        if (animationFrame >= 0.9f) {
            rect(c, 90, 16, 18, 18, 0xf4f6fb)
            rect(c, 91, 17, 16, 16, 0x2b6fe0)
            paint.color = Color.WHITE
            paint.textSize = 13f
            c.drawText("P", 95f, 30f, paint)
        }
    }

    private fun drawNeutralCar(c: Canvas, x: Int, y: Int) {
        rect(c, x + 6, y, 21, 9, 0xacd2dd)
        rect(c, x + 10, y + 2, 13, 7, 0x354a62)
        rect(c, x, y + 9, 37, 12, 0xbcdde0)
        rect(c, x + 5, y + 19, 7, 5, 0x252934)
        rect(c, x + 25, y + 19, 7, 5, 0x252934)
    }

    private fun drawRest(c: Canvas) {
        rect(c, 0, 0, 132, 66, 0x7cc04a)
        rect(c, 0, 0, 132, 2, 0x97d468)
        rect(c, 0, 57, 132, 9, 0x649f3a)
        c.save()
        c.clipPath(window)
        NIGHT.forEachIndexed { i, ink -> rect(c, 0, 6 + i * 9, 132, 9, ink) }
        STARS.forEachIndexed { i, (x, y) -> rect(c, x, y, 1, 1, if (i % 3 == 0) 0xfcdc64 else 0xffffff) }
        rect(c, 100, 10, 5, 5, 0xfff3c4)
        rect(c, 102, 10, 3, 2, 0x121733)
        rect(c, 0, 42, 132, 14, 0x1a1f3a)
        for (x in 4..131 step 9) rect(c, x, 46, 3, 2, 0xffd36b)
        rect(c, 40, 18, 40, 40, 0x3a3f55)
        rect(c, 42, 16, 36, 2, 0x3a3f55)
        rect(c, 42, 18, 2, 30, 0x4a5070)
        val nod = if (motion?.isRunning == true && animationFrame in 0.67f..0.81f) 2 else 0
        sleep?.let { sprite(c, it, 36, 15 + nod, 48, 44) }
        c.restore()
        paint.color = 0xffefe2ff.toInt()
        val rise = if (motion?.isRunning == true) animationFrame * 5f else 0f
        paint.textSize = 5f
        c.drawText("z", 80f, 29f - rise, paint)
        paint.textSize = 7f
        c.drawText("z", 87f, 23f - rise, paint)
        paint.textSize = 9f
        c.drawText("Z", 96f, 17f - rise, paint)
    }

    private companion object {
        val PARKING_LINES = intArrayOf(8, 34, 100, 126)
        val NIGHT = intArrayOf(0x121733, 0x161c3c, 0x1b2246, 0x212a52, 0x28305c, 0x2e3666)
        val STARS = listOf(16 to 9, 30 to 20, 52 to 8, 88 to 12, 96 to 26, 70 to 18, 40 to 30, 104 to 6, 20 to 38)
    }
}
