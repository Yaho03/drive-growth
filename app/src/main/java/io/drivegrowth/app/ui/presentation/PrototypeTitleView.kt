package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Shader
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.shop.TownActors
import io.drivegrowth.app.ui.shop.townSlots
import io.drivegrowth.core.contract.VehicleId
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 주차 상태에서만 시작 화면과 장식용 움직임을 제공한다. */
@SuppressLint("ViewConstructor")
internal class PrototypeTitleView(
    context: Context,
    private val onStarting: () -> Unit = {},
    private val uptimeMs: () -> Long = SystemClock::uptimeMillis,
    private val onStart: () -> Unit,
) : FrameLayout(context) {
    private var presentationAllowed = false
    private var startedAt = 0L
    private var exitAt: Long? = null
    private var startDispatched = false
    private val landscape = TitleLandscape(context)
    private val titleFont = Typeface.createFromAsset(context.assets, "pixel/title/BlackHanSans-Regular.ttf")
    private val badge = label("●  RENT-A-CAR", 0xfffcdc64.toInt()).apply {
        tag = "presentation:title-badge"
        background = pill(0xff1c1e2c.toInt(), 0xfffcdc64.toInt())
    }
    private val gold = TitleWord(context, "르르르", titleFont, intArrayOf(
        0xfffff7c8.toInt(), 0xfffcdc64.toInt(), 0xfff6a83c.toInt(),
    )).apply { tag = "presentation:title-first" }
    private val sky = TitleWord(context, "렌터카", titleFont, intArrayOf(
        Color.WHITE, 0xffcfe4ff.toInt(), 0xff68a0f0.toInt(),
    )).apply { tag = "presentation:title-second" }
    private val ribbon = label("달린 만큼 커지는 우리 동네 렌터카", Color.WHITE).apply {
        tag = "presentation:title-ribbon"
        background = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val path = Path()

            override fun draw(canvas: Canvas) {
                val edge = bounds.height() * .34f
                val left = bounds.left.toFloat()
                val right = bounds.right.toFloat()
                val top = bounds.top.toFloat()
                val bottom = bounds.bottom.toFloat()
                val middle = bounds.exactCenterY()
                path.reset()
                path.moveTo(left, top)
                path.lineTo(right, top)
                path.lineTo(right - edge, middle)
                path.lineTo(right, bottom)
                path.lineTo(left, bottom)
                path.lineTo(left + edge, middle)
                path.close()
                paint.color = 0xff2050a8.toInt()
                canvas.drawPath(path, paint)
                canvas.save()
                canvas.clipRect(left, top, right, bottom - bounds.height() * .13f)
                paint.color = 0xff2f6fe8.toInt()
                canvas.drawPath(path, paint)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Suppress("DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }
    private val start = Button(context).apply {
        tag = "presentation:start"
        text = SpannableString("화면을 눌러 시작  ▶").apply {
            setSpan(ForegroundColorSpan(0xfffcdc64.toInt()), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        contentDescription = "게임 시작"
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        backgroundTintList = null
        background = pill(0xe6121622.toInt(), 0x66ffffff)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { performClickFromTitle() }
    }
    private val sparks = TitleSparks(context)
    private val flash = View(context).apply {
        setBackgroundColor(Color.WHITE)
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        tag = "presentation:title"
        setBackgroundColor(PixelStyle.background)
        clipChildren = false
        addView(landscape)
        addView(badge)
        addView(gold)
        addView(sky)
        addView(ribbon)
        addView(sparks)
        addView(start)
        addView(flash)
        setOnClickListener { performClickFromTitle() }
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setPresentationInput(false)
    }

    private fun performClickFromTitle() {
        if (!canAcceptStart() || exitAt != null || startDispatched) return
        onStarting()
        if (!canAnimateScene()) {
            dispatchStart()
            return
        }
        exitAt = uptimeMs()
        setPresentationInput(false)
        updateMotion()
    }

    private fun dispatchStart() {
        if (!canAcceptStart() || startDispatched) return
        startDispatched = true
        setPresentationInput(false)
        onStart()
    }

    fun setPresentationAllowed(allowed: Boolean) {
        if (presentationAllowed == allowed) return
        presentationAllowed = allowed
        if (!allowed) {
            startDispatched = false
            resetExit()
        }
        updateMotion()
    }

    private fun setPresentationInput(allowed: Boolean) {
        isClickable = allowed
        start.isEnabled = allowed
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val scale = layoutScale(width, height)
        val logoWidth = (560 * scale).roundToInt()
        measureAt(landscape, width, height)
        measureAt(flash, width, height)
        measureAt(sparks, (650 * scale).roundToInt(), (300 * scale).roundToInt())
        fitLabel(badge, 15 * scale)
        measureAt(badge, (171 * scale).roundToInt(), (32 * scale).roundToInt())
        fitLabel(gold, 136 * scale)
        measureAt(gold, logoWidth, (140 * scale).roundToInt())
        fitLabel(sky, 136 * scale)
        measureAt(sky, logoWidth, (140 * scale).roundToInt())
        fitLabel(ribbon, 24 * scale)
        measureAt(ribbon, (437 * scale).roundToInt(), (49 * scale).roundToInt())
        val startSp = maxOf(16f, 28 * scale / resources.displayMetrics.density)
        if (start.textSize != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, startSp, resources.displayMetrics)) {
            start.setTextSize(TypedValue.COMPLEX_UNIT_SP, startSp)
        }
        val startWidth = maxOf((292 * scale).roundToInt(),
            ceil(start.paint.measureText(start.text.toString())).toInt() + dp(32)).coerceAtMost(width)
        start.measure(MeasureSpec.makeMeasureSpec(startWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val startHeight = maxOf(dp(48), (60 * scale).roundToInt(), start.measuredHeight + dp(12))
        measureAt(start, startWidth, startHeight.coerceAtMost(height))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val scale = layoutScale(width, height)
        val stageX = (width - 1280 * scale) / 2
        val stageY = (height - 720 * scale) / 2
        val inset = minOf(dp(16), width / 12)
        val logoX = maxOf(inset, (stageX + 172 * scale).roundToInt())
        val logoY = maxOf(dp(4), (stageY + 58 * scale).roundToInt())
        place(landscape, 0, 0)
        place(flash, 0, 0)
        place(sparks, logoX - (30 * scale).roundToInt(), logoY - (9 * scale).roundToInt())
        place(badge, logoX + (10 * scale).roundToInt(), logoY)
        val firstY = badge.bottom + (6 * scale).roundToInt()
        place(gold, logoX, firstY)
        place(sky, logoX + (64 * scale).roundToInt(), firstY + (126 * scale).roundToInt())
        place(ribbon, logoX + (40 * scale).roundToInt(), sky.bottom + (18 * scale).roundToInt())
        val bottomGap = (stageY + 74 * scale).roundToInt()
        val roomBelowRibbon = (height - ribbon.bottom).coerceAtLeast(0)
        val startTop = height - minOf(roomBelowRibbon, bottomGap + start.measuredHeight)
        place(start, (width - start.measuredWidth) / 2, startTop.coerceAtMost(height - start.measuredHeight))
    }

    private fun layoutScale(width: Int, height: Int) = minOf(maxOf(width / 1280f, height / 720f), width / 960f)

    private fun measureAt(view: View, width: Int, height: Int) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    private fun place(view: View, left: Int, top: Int) {
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
    }

    private fun fitLabel(view: TextView, pixels: Float) {
        if (view.textSize != pixels) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
    }

    private fun pill(fill: Int, outline: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(2), outline)
        cornerRadius = dp(60).toFloat()
    }

    private fun label(value: String, color: Int) = TextView(context).apply {
        text = value
        gravity = Gravity.CENTER
        setSingleLine()
        includeFontPadding = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animateScene)
        startedAt = 0L
        resetExit()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateMotion()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateMotion()
    }

    private val animateScene = object : Runnable {
        override fun run() {
            if (!canAnimateScene()) return
            val now = uptimeMs()
            val exit = exitAt
            if (exit != null && now - exit >= 480L) {
                resetExit()
                dispatchStart()
                return
            }
            val time = (now - startedAt) / 1_000f
            landscape.elapsed = time
            val logoOffset = ((kotlin.math.cos(time * 2 * Math.PI / 3.2) - 1) * 3 * layoutScale(width, height)).toFloat()
            badge.translationY = logoOffset
            gold.translationY = logoOffset
            sky.translationY = logoOffset
            ribbon.translationY = logoOffset
            gold.elapsed = time
            sky.elapsed = time
            sparks.elapsed = time
            start.alpha = .78f + .22f * kotlin.math.cos(time * 2 * Math.PI.toFloat() / 1.4f)
            exitAt?.let { begin ->
                val ms = now - begin
                alpha = 1f - (ms / 450f).coerceIn(0f, 1f)
                flash.alpha = 1f - (ms / 140f).coerceIn(0f, 1f)
                landscape.zoom = (ms / 480f).coerceIn(0f, 1f) * .14f
            }
            landscape.invalidate()
            postDelayed(this, 33L)
        }
    }

    private fun resetExit() {
        exitAt = null
        flash.alpha = 0f
        landscape.zoom = 0f
        alpha = 1f
    }

    private fun canAcceptStart() = presentationAllowed && isAttachedToWindow && isShown &&
        hasWindowFocus() && windowVisibility == VISIBLE

    private fun canAnimateScene() = canAcceptStart() && ValueAnimator.areAnimatorsEnabled()

    private fun updateMotion() {
        removeCallbacks(animateScene)
        setPresentationInput(canAcceptStart() && exitAt == null && !startDispatched)
        if (canAnimateScene()) {
            startedAt = uptimeMs() - (landscape.elapsed * 1_000f).toLong()
            post(animateScene)
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            if (canAcceptStart() && exitAt != null) {
                resetExit()
                dispatchStart()
            }
            landscape.elapsed = 0f
            landscape.invalidate()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

}

private class TitleWord(context: Context, word: String, font: Typeface, private val colors: IntArray) : TextView(context) {
    private var gradient: LinearGradient? = null
    private var sheen: LinearGradient? = null
    private val sheenPosition = android.graphics.Matrix()
    var elapsed = 0f
        set(value) { field = value; invalidate() }

    init {
        text = word
        typeface = font
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setSingleLine()
        includeFontPadding = false
        rotation = -4f
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pivotX = 0f
        val metrics = paint.fontMetrics
        gradient = LinearGradient(0f, baseline + metrics.ascent, 0f, baseline + metrics.descent,
            colors, floatArrayOf(.08f, .46f, .92f), Shader.TileMode.CLAMP)
        sheen = LinearGradient(-textSize * .38f, 0f, textSize * .38f, textSize * .19f,
            intArrayOf(Color.TRANSPARENT, 0xe6ffffff.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val outline = 0xff1c1e2c.toInt()
        val text = text.toString()
        val y = baseline.toFloat()
        paint.shader = null
        paint.color = outline
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = textSize * .16f
        canvas.drawText(text, 0f, y + textSize * .09f, paint)
        canvas.drawText(text, 0f, y, paint)
        paint.style = Paint.Style.FILL
        paint.shader = gradient
        canvas.drawText(text, 0f, y, paint)
        if (elapsed >= 1.2f) {
            val phase = (elapsed - 1.2f) % 4.2f / (4.2f * .35f)
            if (phase < 1f) {
                val span = paint.measureText(text) + textSize
                sheenPosition.setTranslate(span * phase - textSize * .5f, y - textSize * .6f)
                sheen?.setLocalMatrix(sheenPosition)
                paint.shader = sheen
                canvas.drawText(text, 0f, y, paint)
            }
        }
        paint.shader = null
    }
}

private class TitleSparks(context: Context) : View(context) {
    private val paint = Paint().apply { color = 0xfffff7c8.toInt() }
    private val star = Path().apply {
        moveTo(0f, -11f); lineTo(2.6f, -2.6f); lineTo(11f, 0f); lineTo(2.6f, 2.6f)
        lineTo(0f, 11f); lineTo(-2.6f, 2.6f); lineTo(-11f, 0f); lineTo(-2.6f, -2.6f); close()
    }
    private val points = arrayOf(floatArrayOf(500f, 30f, 0f), floatArrayOf(-26f, 140f, .7f),
        floatArrayOf(540f, 236f, 1.2f), floatArrayOf(300f, -6f, .35f))
    var elapsed = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val scale = width / 650f
        for ((x, y, delay) in points) {
            if (elapsed < delay) continue
            val amount = .5f - .5f * kotlin.math.cos((elapsed - delay) * 2 * Math.PI.toFloat() / 1.8f)
            val size = scale * (.2f + .8f * amount)
            paint.alpha = (amount * 255).toInt()
            canvas.save()
            canvas.translate((x + 41) * scale, (y + 20) * scale)
            canvas.scale(size, size)
            canvas.drawPath(star, paint)
            canvas.restore()
        }
    }
}

private class TitleLandscape(context: Context) : View(context) {
    var elapsed = 0f
    var zoom = 0f
    private val town by lazy(LazyThreadSafetyMode.NONE) {
        try {
            context.assets.open("pixel/town.png").use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: IOException) {
            null
        }
    }
    private val vehicles by lazy(LazyThreadSafetyMode.NONE) {
        val sprites = VehicleSprites(context)
        listOf("hyundai_casper", "hyundai_avante", "hyundai_tucson").map { sprites.bitmap(VehicleId(it)) }
    }
    private val actors by lazy(LazyThreadSafetyMode.NONE) { TownActors(context) }
    private val scene = RectF(0f, 0f, 1536f, 1024f)
    private val area = RectF()
    private val paint = Paint().apply { isFilterBitmap = false }
    private val shade = Paint()
    private val logoShade = RadialGradient(0f, 0f, 1f, 0x9e0c1020.toInt(), Color.TRANSPARENT,
        Shader.TileMode.CLAMP)
    private val roadShade = LinearGradient(0f, 533f, 0f, 720f, Color.TRANSPARENT, 0x8c0c1020.toInt(),
        Shader.TileMode.CLAMP)
    private val cloud = Path().apply {
        moveTo(4f, 9f); rLineTo(2f, 0f); rLineTo(0f, -2f); rLineTo(3f, 0f)
        rLineTo(0f, -2f); rLineTo(2f, 0f); rLineTo(0f, -1f); rLineTo(3f, 0f)
        rLineTo(0f, -1f); rLineTo(6f, 0f); rLineTo(0f, 1f); rLineTo(2f, 0f)
        rLineTo(0f, 1f); rLineTo(1f, 0f); rLineTo(0f, 1f); rLineTo(2f, 0f)
        rLineTo(0f, -1f); rLineTo(5f, 0f); rLineTo(0f, 1f); rLineTo(2f, 0f)
        rLineTo(0f, 2f); rLineTo(1f, 0f); rLineTo(0f, 2f); rLineTo(2f, 0f)
        rLineTo(0f, 2f); rLineTo(1f, 0f); rLineTo(0f, 2f); lineTo(2f, 14f)
        rLineTo(0f, -3f); rLineTo(2f, 0f); close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val background = town ?: return
        canvas.drawColor(0xff86c05a.toInt())
        val scale = minOf(maxOf(width / 1280f, height / 720f), width / 960f)
        val pan = (.5f - .5f * kotlin.math.cos(elapsed * Math.PI.toFloat() / 18f))
        canvas.save()
        canvas.translate((width - 1280 * scale) / 2 - 26 * scale * pan,
            (height - 720 * scale) / 2 - (110 + 10 * pan) * scale)
        canvas.scale(scale * 1280 / scene.width(), scale * 1280 / scene.width())
        canvas.scale(1 + .04f * pan + zoom, 1 + .04f * pan + zoom, 768f, 635f)
        canvas.drawBitmap(background, null, scene, paint)
        vehicles.forEachIndexed { index, bitmap ->
            val (x, y) = townSlots[index]
            drawVehicle(canvas, bitmap, x.toFloat(), y - 7f, flip = true)
        }
        actors.draw(canvas, elapsed, fuelInstalled = true, washInstalled = true)
        val firstX = (320f + elapsed * 150f) % 840f - 120f
        val secondX = (540f + elapsed * 190f) % 840f - 120f
        canvas.save()
        canvas.clipRect(scene)
        drawVehicle(canvas, vehicles[0], firstX, 715f + firstX / 2f + kotlin.math.sin(elapsed * 12.5f + firstX), flip = false)
        drawVehicle(canvas, vehicles[1], secondX, 793f + secondX / 2f + kotlin.math.sin(elapsed * 12.5f + secondX), flip = false)
        canvas.restore()
        canvas.restore()

        canvas.save()
        canvas.translate((width - 1280 * scale) / 2, (height - 720 * scale) / 2)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, 1280f, 720f)
        drawCloud(canvas, 70f, 0f, 48f)
        drawCloud(canvas, 250f, 18f, 60f)
        drawCloud(canvas, 150f, 34f, 54f)
        shade.shader = logoShade
        canvas.save()
        canvas.translate(330f, 190f)
        canvas.scale(446.4f, 288f)
        canvas.drawCircle(0f, 0f, 1f, shade)
        canvas.restore()
        shade.shader = roadShade
        canvas.drawRect(0f, 0f, 1280f, 720f, shade)
        canvas.restore()
    }

    private fun drawVehicle(canvas: Canvas, bitmap: Bitmap?, x: Float, y: Float, flip: Boolean) {
        if (bitmap == null) return
        area.set(x - bitmap.width * .9f, y - bitmap.height * .9f, x + bitmap.width * .9f, y + bitmap.height * .9f)
        canvas.save()
        if (flip) canvas.scale(-1f, 1f, x, y)
        canvas.drawBitmap(bitmap, null, area, paint)
        canvas.restore()
    }

    private fun drawCloud(canvas: Canvas, y: Float, offset: Float, duration: Float) {
        val x = -360f + (elapsed + offset) % duration / duration * 1860f
        drawCloudShape(canvas, x + 70f, y + 170f, 0x1f1c1e2c)
        drawCloudShape(canvas, x, y, 0xebffffff.toInt())
    }

    private fun drawCloudShape(canvas: Canvas, x: Float, y: Float, color: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(6.5f, 6.5f)
        paint.color = color
        canvas.drawPath(cloud, paint)
        paint.color = Color.BLACK
        canvas.restore()
    }
}
