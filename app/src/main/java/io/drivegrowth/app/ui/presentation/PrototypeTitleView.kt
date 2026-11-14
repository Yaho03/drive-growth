package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.VehicleId
import java.io.IOException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 8차 Claude Design 「르르르 렌터카 시작화면」을 옮긴 영화 포스터식 시작 화면. 주차 상태에서만 움직이고 누를 수 있다.
 * 0.8초 어둠 → 0.8초 점등 → 1.2초 로고 → 0.6초 부제 → 0.6초 시작 → 12초 반복. 움직임 줄이기면 완성된 포스터만 보여 준다.
 * 시간표는 보드의 OM_SCENES와 같다. 화면 좌표는 보드의 1360×948 기준이다.
 */
@SuppressLint("ViewConstructor")
internal class PrototypeTitleView(
    context: Context,
    private val onStarting: () -> Unit = {},
    private val uptimeMs: () -> Long = SystemClock::uptimeMillis,
    private val animationsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
    private val onStart: () -> Unit,
) : FrameLayout(context) {
    private var presentationAllowed = false
    private var startedAt: Long? = null
    private var elapsedMs = 0L
    private var exitAt: Long? = null
    private var startDispatched = false
    private var ribbonReveal = 1f
    private val ribbonClip = Rect()
    private val scene = PosterScene(context)
    private val overlay = PosterOverlay(context)
    private val titleFont = Typeface.createFromAsset(context.assets, "pixel/title/BlackHanSans-Regular.ttf")
    private val badge = TextView(context).apply {
        tag = "presentation:title-badge"
        text = SpannableString("■  RENT-A-CAR").apply {
            setSpan(ForegroundColorSpan(0xffffd65e.toInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        gravity = Gravity.CENTER
        setSingleLine()
        includeFontPadding = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = .12f
        setTextColor(Color.WHITE)
        background = BadgePlate()
    }
    private val gold = TitleWord(context, "르르르", titleFont, GOLD, 0x8cff963c.toInt()).apply { tag = "presentation:title-first" }
    private val sky = TitleWord(context, "렌터카", titleFont, CHROME, 0x735adcff).apply { tag = "presentation:title-second" }
    private val ribbon = TextView(context).apply {
        tag = "presentation:title-ribbon"
        text = "달린 만큼 커지는 우리 동네 렌터카"
        gravity = Gravity.CENTER
        setSingleLine()
        includeFontPadding = false
        typeface = titleFont
        letterSpacing = .14f
        setTextColor(0xffffe6b8.toInt())
        background = RibbonLines()
    }
    private val start = Button(context).apply {
        tag = "presentation:start"
        text = "화면을 눌러 시작  ▶"
        contentDescription = "게임 시작"
        isAllCaps = false
        typeface = titleFont
        letterSpacing = .08f
        val style = ReferenceHudStyle(context)
        setTextColor(style.featureButtonText(primary = true))
        backgroundTintList = null
        background = style.signButton(primary = true)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { acceptStart() }
    }
    private val titleWords = listOf(gold, sky)

    init {
        tag = "presentation:title"
        setBackgroundColor(DARK)
        clipChildren = false
        addView(scene)
        addView(badge)
        addView(gold)
        addView(sky)
        addView(ribbon)
        addView(start)
        addView(overlay)
        setOnClickListener { skipIntro() }
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setPresentationInput(false)
        applyTime(LOOP, calm = true)
    }

    private fun skipIntro() {
        if (!canAcceptStart() || exitAt != null || startDispatched || !animationsEnabled()) return
        if (elapsedMs >= INTRO_MS) return
        elapsedMs = INTRO_MS
        startedAt = uptimeMs() - elapsedMs
        applyTime(LOOP, calm = false)
    }

    private fun acceptStart() {
        if (!start.isEnabled || !canAcceptStart() || exitAt != null || startDispatched) return
        val now = uptimeMs()
        exitAt = now
        elapsedMs = maxOf(elapsedMs, INTRO_MS)
        startedAt = now - elapsedMs
        setPresentationInput(false)
        onStarting()
        updateMotion()
    }

    private fun dispatchStart() {
        if (!canAcceptStart() || exitAt == null || startDispatched) return
        exitAt = null
        startDispatched = true
        setPresentationInput(false)
        onStart()
    }

    fun setPresentationAllowed(allowed: Boolean) {
        if (presentationAllowed == allowed) return
        presentationAllowed = allowed
        if (!allowed) {
            startDispatched = false
            exitAt = null
        }
        updateMotion()
    }

    private fun setPresentationInput(allowed: Boolean) {
        isClickable = allowed
        start.isEnabled = allowed && start.visibility == VISIBLE
    }

    /** 보드 1360×948을 화면 안에 다 들어오게 줄인 배율. 배경은 따로 화면을 꽉 채운다. */
    private fun stageScale(width: Int, height: Int) = min(width / STAGE_W, height / STAGE_H)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val k = stageScale(width, height)
        measureAt(scene, width, height)
        measureAt(overlay, width, height)
        fitLabel(badge, 20 * k)
        measureAt(badge, (250 * k).roundToInt().coerceAtLeast(1), (40 * k).roundToInt().coerceAtLeast(1))
        for (word in titleWords) {
            fitLabel(word, 176 * k)
            val textWidth = ceil(word.paint.measureText(word.text.toString())).toInt()
            measureAt(word, textWidth + (40 * k).roundToInt() + 2, (220 * k).roundToInt().coerceAtLeast(1))
        }
        fitLabel(ribbon, 28 * k)
        val ribbonWidth = ceil(ribbon.paint.measureText(ribbon.text.toString())).toInt() + (240 * k).roundToInt()
        measureAt(ribbon, ribbonWidth.coerceAtMost(width), (48 * k).roundToInt().coerceAtLeast(1))
        val startSp = max(20f, 38 * k / resources.displayMetrics.density)
        if (start.textSize != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, startSp, resources.displayMetrics)) {
            start.setTextSize(TypedValue.COMPLEX_UNIT_SP, startSp)
        }
        val startWidth = max((360 * k).roundToInt(),
            ceil(start.paint.measureText(start.text.toString())).toInt() + dp(32)).coerceAtMost(width)
        start.measure(MeasureSpec.makeMeasureSpec(startWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val startHeight = max(dp(56), start.measuredHeight + dp(12))
        measureAt(start, startWidth, startHeight.coerceAtMost(height))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val k = stageScale(width, height)
        val oy = (height - STAGE_H * k) / 2
        fun sy(y: Float) = (oy + y * k).roundToInt()
        place(scene, 0, 0)
        place(overlay, 0, 0)
        place(badge, (width - badge.measuredWidth) / 2, sy(300f))
        // 두 낱말을 가운데에 나란히 두고, 뒤 낱말을 살짝 내려 포스터처럼 엇갈리게 한다
        val gap = (36 * k).roundToInt()
        val rowWidth = gold.measuredWidth + gap + sky.measuredWidth
        val rowLeft = (width - rowWidth) / 2
        val firstTop = max(badge.bottom, sy(352f))
        place(gold, rowLeft, firstTop)
        place(sky, rowLeft + gold.measuredWidth + gap, firstTop + (22 * k).roundToInt().coerceAtLeast(1))
        place(ribbon, (width - ribbon.measuredWidth) / 2, sky.bottom + (12 * k).roundToInt().coerceAtLeast(1))
        updateRibbonClip()
        val latestStart = height - start.measuredHeight
        val earliestStart = minOf(ribbon.bottom + 1, latestStart)
        val startTop = sy(868f).coerceIn(earliestStart, latestStart)
        place(start, (width - start.measuredWidth) / 2, startTop)
    }

    private fun measureAt(view: View, width: Int, height: Int) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    private fun place(view: View, left: Int, top: Int) {
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
    }

    private fun fitLabel(view: TextView, pixels: Float) {
        val size = pixels.coerceAtLeast(1f)
        if (view.textSize != size) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animateScene)
        startedAt = null
        elapsedMs = 0L
        exitAt = null
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
            if (!canAcceptStart()) {
                updateMotion()
                return
            }
            if (!animationsEnabled()) {
                updateStaticPoster()
                dispatchStart()
                return
            }
            val now = uptimeMs()
            val exit = exitAt
            if (exit != null && now - exit >= EXIT_MS) {
                dispatchStart()
                return
            }
            elapsedMs = now - (startedAt ?: now)
            applyTime(elapsedMs / 1_000f, calm = false, exitMs = exit?.let { now - it })
            postDelayed(this, 33L)
        }
    }

    /** 한 시각의 장면·로고·부제·버튼 상태를 정한다. 반복 구간은 LOOP 뒤 12초마다 같은 모습이다. */
    private fun applyTime(time: Float, calm: Boolean, exitMs: Long? = null) {
        val k = stageScale(width.coerceAtLeast(1), height.coerceAtLeast(1))
        val press = exitMs?.let { (it / EXIT_MS.toFloat()).coerceIn(0f, 1f) } ?: 0f
        scene.time = time
        scene.calm = calm
        scene.press = press
        overlay.time = time
        overlay.calm = calm
        overlay.press = press
        val land = LOGO + .32f
        val slam = if (calm) 1f else ((time - LOGO) / .32f).coerceIn(0f, 1f)
        val logoScale = when {
            calm -> 1f
            time < land -> lerp(2.4f, 1f, slam * slam)
            else -> 1f + max(0f, .05f * sin(((time - land) / .3f).coerceIn(0f, 1f) * PI.toFloat()))
        }
        val logoAlpha = if (calm) 1f else if (time >= LOGO) step(((time - LOGO) / .12f).coerceIn(0f, 1f), 3) else 0f
        val shine = when {
            calm -> -1f
            time >= LOGO + .6f && time < LOOP -> (time - LOGO - .6f) / .7f
            time >= LOOP -> ((time - LOOP) % 4.5f).let { if (it < 1.2f) it / 1.2f else -1f }
            else -> -1f
        }
        val exit = glide(press / .45f)
        val lift = -exit * 120 * k
        // 두 낱말은 각자의 가운데를 기준으로 커졌다 줄어든다
        for ((index, word) in titleWords.withIndex()) {
            word.pivotX = word.width / 2f
            word.pivotY = word.height * .6f
            word.scaleX = logoScale
            word.scaleY = logoScale
            word.alpha = logoAlpha * (1 - exit)
            word.translationY = lift
            word.shine = if (shine >= 0) (shine - .15f * index).coerceIn(0f, 1f) else -1f
        }
        val tag = if (calm) 1f else pop(time, SUB, SUB + .25f)
        badge.scaleX = tag
        badge.scaleY = tag
        badge.alpha = (if (calm || time >= SUB) 1f else 0f) * (1 - exit)
        badge.translationY = lift
        val reveal = if (calm) 1f else step(enter(time, SUB + .1f, SUB + .55f), 12)
        if (ribbonReveal != reveal) {
            ribbonReveal = reveal
            updateRibbonClip()
        }
        ribbon.alpha = 1 - exit
        ribbon.translationY = lift
        val ctaIn = if (calm) 1f else enter(time, GO + .1f, GO + .6f)
        val breathe = if (!calm && time > GO + .6f) .6f + .4f * (.5f + .5f * cos((time - GO - .6f) / 2.6f * 2 * PI.toFloat())) else 1f
        start.translationY = min((1 - ctaIn) * 50 * k, (height - start.bottom).coerceAtLeast(0).toFloat())
        val ctaAlpha = ctaIn * breathe * (1 - exit)
        start.alpha = ctaAlpha
        val ctaVisibility = if (ctaAlpha > 0f) VISIBLE else INVISIBLE
        if (start.visibility != ctaVisibility) {
            start.visibility = ctaVisibility
            start.isEnabled = isClickable && ctaVisibility == VISIBLE
        }
        scene.invalidate()
        overlay.invalidate()
    }

    private fun updateRibbonClip() {
        val hidden = ((1 - ribbonReveal) * ribbon.width / 2f).roundToInt()
        ribbonClip.set(hidden, 0, ribbon.width - hidden, ribbon.height)
        ribbon.clipBounds = if (ribbonReveal == 1f) null else ribbonClip
    }

    private fun canAcceptStart() = presentationAllowed && isAttachedToWindow && isShown &&
        hasWindowFocus() && windowVisibility == VISIBLE

    private fun updateMotion() {
        removeCallbacks(animateScene)
        val allowed = canAcceptStart()
        setPresentationInput(allowed && exitAt == null && !startDispatched)
        if (allowed && !startDispatched && animationsEnabled()) {
            if (startedAt == null) {
                startedAt = uptimeMs() - elapsedMs
                if (exitAt == null) applyTime(elapsedMs / 1_000f, calm = false)
            }
            post(animateScene)
        } else {
            updateStaticPoster()
            // 창 포커스 콜백에서 호스트가 입력 허용 상태를 갱신한 뒤 완료한다.
            if (allowed && exitAt != null) post(animateScene)
        }
    }

    private fun updateStaticPoster() {
        startedAt = null
        if (!scene.calm) applyTime(LOOP, calm = true)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /** 보드의 빨간 RENT-A-CAR 배지: 흰 테두리를 두른 사각 판. */
    private class BadgePlate : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val line = max(1f, b.height() * .08f)
            paint.color = Color.WHITE
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
            paint.color = 0xffc8281e.toInt()
            canvas.drawRect(b.left + line, b.top + line, b.right - line, b.bottom - line, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** 부제 양옆의 금빛 선. */
    private class RibbonLines : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val length = b.width() * .14f
            val y = b.exactCenterY()
            val thick = max(1f, b.height() * .09f)
            paint.shader = LinearGradient(b.left.toFloat(), 0f, b.left + length, 0f, Color.TRANSPARENT, 0xffffb060.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(b.left.toFloat(), y - thick / 2, b.left + length, y + thick / 2, paint)
            paint.shader = LinearGradient(b.right - length, 0f, b.right.toFloat(), 0f, 0xffffb060.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(b.right - length, y - thick / 2, b.right.toFloat(), y + thick / 2, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val STAGE_W = 1360f
        const val STAGE_H = 948f
        const val LIT = .8f
        const val LOGO = 1.6f
        const val SUB = 2.8f
        const val GO = 3.4f
        private const val INTRO_MS = 4_000L
        const val LOOP = INTRO_MS / 1_000f
        const val LOOP_LENGTH = 12f
        const val EXIT_MS = 480L
        val DARK = 0xff0a0710.toInt()
        /** 르르르 출품 사용권(#29)이 확인되면 true로 바꿔 하늘과 앞줄의 주인공을 르르르로 그린다. */
        const val RURURU_HERO = true

        /** 금빛·크롬 글자의 가로 띠. 보드의 linear-gradient 색 띠를 그대로 옮겼다. */
        val GOLD = intArrayOf(0xfffff6cc.toInt(), 0xffffd65e.toInt(), 0xffc8781e.toInt(), 0xfffff0b0.toInt(), 0xfff0a830.toInt(), 0xff9a4a12.toInt()) to
            floatArrayOf(0f, .14f, .36f, .5f, .58f, .78f)
        val CHROME = intArrayOf(Color.WHITE, 0xffd8dde6.toInt(), 0xff8a93a6.toInt(), 0xfff4f7ff.toInt(), 0xffb8c0d0.toInt(), 0xff6a7288.toInt(), 0xff9aa3b8.toInt()) to
            floatArrayOf(0f, .14f, .34f, .5f, .58f, .76f, .9f)
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun step(v: Float, n: Int) = (v * n).roundToInt() / n.toFloat()
private fun enter(t: Float, s: Float, e: Float): Float { val u = ((t - s) / (e - s)).coerceIn(0f, 1f); return 1 - (1 - u).pow(3) }
private fun glide(t: Float): Float { val u = t.coerceIn(0f, 1f); return -(cos(PI.toFloat() * u) - 1) / 2 }
private fun pop(t: Float, s: Float, e: Float): Float {
    val u = ((t - s) / (e - s)).coerceIn(0f, 1f)
    val c1 = 1.70158f
    return 1 + (c1 + 1) * (u - 1).pow(3) + c1 * (u - 1).pow(2)
}
private fun rnd(i: Int): Float { val x = sin(i * 127.1 + 311.7) * 43758.5453; return (x - floor(x)).toFloat() }

/** 굵은 외곽선·아래 그림자·빛 번짐 위에 띠 그라데이션을 칠한 제목 낱말. shine이 0~1이면 흰 빛이 훑고 지나간다. */
private class TitleWord(
    context: Context,
    word: String,
    font: Typeface,
    private val bands: Pair<IntArray, FloatArray>,
    private val glow: Int,
) : TextView(context) {
    private var fill: LinearGradient? = null
    private val sheenMatrix = Matrix()
    private var sheen: LinearGradient? = null
    var shine = -1f
        set(value) { if (field != value) { field = value; invalidate() } }

    init {
        text = word
        typeface = font
        gravity = Gravity.START or Gravity.CENTER_VERTICAL   // 가운데 정렬하면 한 줄 TextView가 가로 스크롤을 줘서 직접 그린 글자가 밀린다
        setSingleLine()
        includeFontPadding = false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val metrics = paint.fontMetrics
        // 띠마다 같은 색을 두 번 넣어 경계가 또렷한 금속 띠가 되게 한다
        val (colors, stops) = bands
        val c = IntArray(colors.size * 2)
        val p = FloatArray(colors.size * 2)
        for (i in colors.indices) {
            c[i * 2] = colors[i]; c[i * 2 + 1] = colors[i]
            p[i * 2] = stops[i]; p[i * 2 + 1] = if (i + 1 < stops.size) stops[i + 1] else 1f
        }
        fill = LinearGradient(0f, baseline + metrics.ascent * .92f, 0f, baseline + metrics.descent * .6f, c, p, Shader.TileMode.CLAMP)
        sheen = LinearGradient(0f, 0f, textSize * .5f, textSize * .25f,
            intArrayOf(Color.TRANSPARENT, 0xf2ffffff.toInt(), Color.TRANSPARENT), floatArrayOf(.3f, .5f, .7f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val text = text.toString()
        val x = (width - paint.measureText(text)) / 2
        val y = baseline.toFloat()
        val o = max(3f, textSize / 40f)
        paint.shader = null
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = o * 2
        paint.color = 0xff1a1020.toInt()
        paint.setShadowLayer(textSize * .3f, 0f, 0f, glow)
        canvas.drawText(text, x, y + o * 3, paint)
        paint.clearShadowLayer()
        canvas.drawText(text, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xff2a2234.toInt()
        canvas.drawText(text, x, y + o * 2, paint)
        paint.color = Color.WHITE
        paint.shader = fill
        canvas.drawText(text, x, y, paint)
        if (shine in 0f..1f) {
            val span = paint.measureText(text) + textSize
            sheenMatrix.setTranslate(x - textSize * .5f + span * shine, y - textSize)
            sheen?.setLocalMatrix(sheenMatrix)
            paint.shader = sheen
            canvas.drawText(text, x, y, paint)
        }
        paint.shader = null
    }
}

/** 하늘·빛줄기·거대한 주인공·연기·마을·차와 전조등·앞줄 인물·불씨·비네트. 보드의 Piece를 Canvas로 옮겼다. */
private class PosterScene(context: Context) : View(context) {
    var time = PrototypeTitleView.LOOP
    var calm = true
    var press = 0f
    private val assets = context.assets
    private fun load(path: String): Bitmap? = try {
        assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) { null }
    private val town by lazy(LazyThreadSafetyMode.NONE) { load("pixel/town.png") }
    private val people by lazy(LazyThreadSafetyMode.NONE) {
        listOf("mechanic", "guest", "fuel", "idle3", "idle4", "idle5").associateWith { load("pixel/prototype-actors/$it.png") }
    }
    private val sprites = VehicleSprites(context)
    private val pixel = Paint().apply { isFilterBitmap = false }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val rect = RectF()
    private val wedge = Path()
    private val skyGradient = LinearGradient(0f, 0f, 0f, PrototypeTitleView.STAGE_H,
        intArrayOf(0xff05141a.toInt(), 0xff0b2a33.toInt(), 0xff1d3a3c.toInt(), 0xff5a3a2c.toInt(), 0xff2a1a20.toInt(), 0xff0a0710.toInt()),
        floatArrayOf(0f, .3f, .48f, .62f, .8f, 1f), Shader.TileMode.CLAMP)
    private val backlight = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xf2ffc86e.toInt(), 0x8cff8232.toInt(), 0x2ec84628, 0),
        floatArrayOf(0f, .35f, .65f, 1f), Shader.TileMode.CLAMP)
    private val raysGradient = RadialGradient(680f, 600f, 630f,
        intArrayOf(0x1affd28c, 0x1affd28c, 0), floatArrayOf(0f, .29f, 1f), Shader.TileMode.CLAMP)
    private val cloudGradient = RadialGradient(0f, 0f, 1f, 0xf208181e.toInt(), 0, Shader.TileMode.CLAMP)
    private val townGlow = RadialGradient(0f, 0f, 1f, 0x73ff8c3c, 0, Shader.TileMode.CLAMP)
    private val headlight = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xbffff0be.toInt(), 0x40ffc878, 0), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    private val vignette = RadialGradient(0f, 0f, 1f, intArrayOf(0, 0, 0xbf000000.toInt()),
        floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
    private val heroTint = PorterDuffColorFilter(0x8cffa050.toInt(), PorterDuff.Mode.SRC_IN)
    private val carTint = PorterDuffColorFilter(0x99ffaa50.toInt(), PorterDuff.Mode.SRC_IN)
    private val warmTint = PorterDuffColorFilter(0xe6ffaa50.toInt(), PorterDuff.Mode.SRC_IN)
    private val coolTint = PorterDuffColorFilter(0xff50d2ff.toInt(), PorterDuff.Mode.SRC_IN)

    override fun onDraw(canvas: Canvas) {
        val w = PrototypeTitleView.STAGE_W
        val h = PrototypeTitleView.STAGE_H
        val t = time
        val a = max(0f, t - PrototypeTitleView.LOOP)
        val lit = PrototypeTitleView.LIT
        val logo = PrototypeTitleView.LOGO
        val ignite = if (calm) 1f else step(enter(t, lit, lit + .7f), 8)
        val pulse = .9f + .1f * sin(t * 1.6f)
        // 배경은 화면을 꽉 채운다(cover). 보드 좌표 그대로 그린 뒤 확대한다
        val cover = max(width / w, height / h)
        val pz = (press / .6f).coerceIn(0f, 1f).pow(2.2f)
        val push = if (calm) 1f else 1 + .04f * glide(t / (PrototypeTitleView.GO + .6f)) + .006f * sin(a / 12f * PI.toFloat() * 2)
        val zoom = push + pz * if (calm) .2f else 1.1f
        val land = logo + .32f
        val shakeStep = if (calm) -1 else floor((t - land) / .025f).toInt()
        val shake = if (shakeStep in 0..3) floatArrayOf(6f, -4f, -6f, 4f, 4f, 3f, -2f, -2f).let { it[shakeStep * 2] to it[shakeStep * 2 + 1] } else 0f to 0f
        canvas.drawColor(PrototypeTitleView.DARK)
        canvas.save()
        canvas.translate((width - w * cover) / 2, (height - h * cover) / 2)
        canvas.scale(cover, cover)
        canvas.translate(shake.first, shake.second)
        canvas.scale(zoom, zoom, 680f, 560f)

        // 하늘
        soft.shader = skyGradient
        canvas.drawRect(-200f, -200f, w + 200f, h + 200f, soft)
        // 뒤 빛과 빛줄기
        soft.shader = backlight
        soft.alpha = (255 * ignite * pulse).roundToInt().coerceIn(0, 255)
        canvas.save(); canvas.translate(680f, 600f); canvas.scale(700f, 420f); canvas.drawCircle(0f, 0f, 1f, soft); canvas.restore()
        soft.alpha = 255
        soft.shader = raysGradient
        soft.alpha = (255 * if (calm) .4f else ignite * .9f).roundToInt()
        val rays = t * 3
        for (i in 0 until 24) {
            val from = Math.toRadians((rays + i * 15.0)).toFloat()
            val to = Math.toRadians((rays + i * 15.0 + 4)).toFloat()
            wedge.rewind(); wedge.moveTo(680f, 600f)
            wedge.lineTo(680f + 900 * sin(from), 600f - 900 * cos(from))
            wedge.lineTo(680f + 900 * sin(to), 600f - 900 * cos(to)); wedge.close()
            canvas.drawPath(wedge, soft)
        }
        soft.alpha = 255

        // 하늘의 거대한 주인공
        val rise = if (calm) 1f else enter(t, 0f, logo + .4f)
        val giant = if (PrototypeTitleView.RURURU_HERO) people["idle${3 + (floor(t * 2.2f).toInt() % 3)}"] else people["mechanic"]
        giant?.let { bmp ->
            val s = (if (PrototypeTitleView.RURURU_HERO) 15f else 13.5f) * (1 + .008f * sin(t * 1.3f))
            val gy = 640f + (1 - rise) * 120 + if (calm) 0f else sin(t * .7f) * 4
            val gw = bmp.width * s; val gh = bmp.height * s
            pixel.alpha = (step(rise, 6) * .92f * 255).roundToInt()
            pixel.colorFilter = heroTint
            rect.set(680 - gw / 2 + 10, gy - gh, 680 + gw / 2 + 10, gy); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.colorFilter = ColorMatrixColorFilter(tone(lerp(.3f, .9f, ignite), .8f, 0f))
            rect.offset(-10f, 0f); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.colorFilter = null; pixel.alpha = 255
        }
        soft.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x5905141a, 0, 0), floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(-200f, -200f, w + 200f, h + 200f, soft)
        // 연기 구름
        soft.shader = cloudGradient
        SMOKE.forEachIndexed { i, (x, y, r, o) ->
            val cx = x * w + sin(t * .12f + i) * 40 - (t * (4 + i)) % 80 + 40
            soft.alpha = (o * 255).roundToInt()
            canvas.save(); canvas.translate(cx, y * h); canvas.scale(r, r / 2); canvas.drawCircle(0f, 0f, 1f, soft); canvas.restore()
        }
        soft.alpha = 255

        // 마을: 어둡고 따뜻한 색으로, 위쪽은 하늘에 녹아들게
        town?.let { bmp ->
            val top = 400f
            canvas.saveLayer(-200f, top, w + 200f, top + 1024f, null)
            pixel.colorFilter = ColorMatrixColorFilter(tone(lerp(.1f, .3f, ignite), 1.6f, .55f))
            rect.set((w - 1536f) / 2, top, (w + 1536f) / 2, top + 1024f); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.colorFilter = null
            mask.shader = LinearGradient(0f, top, 0f, top + 225f, 0, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(-200f, top, w + 200f, top + 1024f, mask)
            canvas.restore()
        }
        screen.shader = townGlow
        screen.alpha = (255 * ignite * pulse).roundToInt().coerceIn(0, 255)
        canvas.save(); canvas.translate(w / 2, 460f + 104f); canvas.scale(w * .6f / .7f, 260f / .7f); canvas.drawCircle(0f, 0f, 1f, screen); canvas.restore()
        screen.alpha = 255

        // 차와 전조등
        CAR_POSITIONS.forEachIndexed { i, (s, x, y, flip) ->
            val id = CARS[(i + floor(a / PrototypeTitleView.LOOP_LENGTH).toInt()) % CARS.size]
            val bmp = sprites.bitmap(VehicleId(id)) ?: return@forEachIndexed
            val cw = bmp.width * s; val ch = bmp.height * s
            canvas.save()
            if (flip > 0) canvas.scale(-1f, 1f, x, y)
            pixel.colorFilter = carTint
            pixel.alpha = (255 * ignite).roundToInt()
            rect.set(x - cw / 2 + 6, y - ch, x + cw / 2 + 6, y); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.alpha = 255
            pixel.colorFilter = ColorMatrixColorFilter(tone(lerp(.15f, .7f, ignite), 1f, 0f))
            rect.offset(-6f, 0f); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.colorFilter = null
            canvas.restore()
            val on = if (t >= lit + .3f + i * .12f || calm) 1f else 0f
            val bx = if (flip > 0) x - cw * .42f else x + cw * .42f
            screen.shader = headlight
            screen.alpha = (255 * on * if (calm) .8f else .85f + .15f * sin(t * 5 + i)).roundToInt().coerceIn(0, 255)
            canvas.save(); canvas.translate(bx, y - ch * .35f); canvas.scale(160f, 50f); canvas.drawCircle(0f, 0f, 1f, screen); canvas.restore()
            screen.alpha = 255
        }
        soft.shader = LinearGradient(0f, h - 360f, 0f, h, intArrayOf(0, 0xd90a0710.toInt(), 0xd90a0710.toInt()), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(-200f, h - 360f, w + 200f, h + 200f, soft)

        // 앞줄 인물: 아래에서 올라와 살짝 들썩인다. 양옆 테두리에 주황·하늘빛
        for ((key, scale, x, y, d) in CAST) {
            var s = scale
            val bmp = when {
                key != "hero" -> people[key]
                PrototypeTitleView.RURURU_HERO -> people["idle${3 + (floor(t * 3.3f).toInt() % 3)}"]
                else -> people["mechanic"].also { s *= 1.1f }
            } ?: continue
            val up = if (calm) 0f else (1 - enter(t, lit + d, lit + d + .8f)) * 160
            val bob = if (calm || key == "hero" && PrototypeTitleView.RURURU_HERO) 0f else if (floor(t * 2 + d * 10).toInt() % 2 == 1) -s * .5f else 0f
            val bw = bmp.width * s; val bh = bmp.height * s
            val by = y + up + bob
            canvas.save()
            if (x > w / 2) canvas.scale(-1f, 1f, x, by)
            pixel.alpha = (255 * ignite).roundToInt()
            pixel.colorFilter = warmTint
            rect.set(x - bw / 2 + s * .6f, by - bh, x + bw / 2 + s * .6f, by); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.alpha = (180 * ignite).roundToInt()
            pixel.colorFilter = coolTint
            rect.offset(-s * 1.2f, 0f); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.alpha = 255
            pixel.colorFilter = ColorMatrixColorFilter(tone(lerp(.15f, .95f, ignite), 1f, 0f))
            rect.offset(s * .6f, 0f); canvas.drawBitmap(bmp, null, rect, pixel)
            pixel.colorFilter = null
            canvas.restore()
        }
        // 불씨
        if (!calm) for (i in 0 until 46) {
            val sp = 40 + rnd(i) * 90; val span = h + 100
            val y = h + 40 - ((t * sp + rnd(i + 3) * span) % span)
            val x = rnd(i + 7) * w + sin(t * (.6f + rnd(i) * .8f) + i) * 26
            val s = if (rnd(i + 11) < .25f) 6f else if (rnd(i + 13) < .6f) 4f else 3f
            val o = step((.5f + .5f * sin(t * 3 + i * 2.1f)) * ((h - y) / 300f).coerceIn(0f, 1f) * ignite, 3)
            if (o <= 0f) continue
            val color = when (i % 3) { 0 -> 0xffffe39a.toInt(); 1 -> 0xffff9a3a.toInt(); else -> 0xffff5a2a.toInt() }
            soft.shader = null
            soft.color = color; soft.alpha = (o * 90).roundToInt()
            canvas.drawRect(x - s, y - s, x + s * 2, y + s * 2, soft)
            soft.alpha = (o * 255).roundToInt()
            canvas.drawRect(x, y, x + s, y + s, soft)
        }
        soft.alpha = 255
        // 비네트
        soft.shader = vignette
        canvas.save(); canvas.translate(w / 2, h / 2); canvas.scale(w * .6f * 1.2f, h * .5f * 1.8f); canvas.drawCircle(0f, 0f, 1f, soft); canvas.restore()
        soft.shader = null
        canvas.restore()
    }

    /** 밝기 b, 채도 sat, 세피아 섞기 sepia(0~1)를 합친 색 행렬. */
    private fun tone(b: Float, sat: Float, sepia: Float): ColorMatrix = ColorMatrix().apply {
        if (sepia > 0f) {
            val sep = ColorMatrix(floatArrayOf(
                .393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            val id = ColorMatrix()
            val mix = FloatArray(20) { id.array[it] * (1 - sepia) + sep.array[it] * sepia }
            set(mix)
        }
        postConcat(ColorMatrix().apply { setSaturation(sat) })
        postConcat(ColorMatrix().apply { setScale(b, b, b, 1f) })
    }

    private data class Actor(val key: String, val scale: Float, val x: Float, val y: Float, val delay: Float)

    companion object {
        val CARS = listOf("hyundai_sonata", "hyundai_ioniq5", "genesis_g80", "hyundai_palisade", "hyundai_staria_tourer", "hyundai_kona", "genesis_gv80", "hyundai_ioniq6")
        private val SMOKE = arrayOf(floatArrayOf(.15f, .16f, 520f, .55f), floatArrayOf(.82f, .12f, 600f, .5f),
            floatArrayOf(.5f, .02f, 700f, .45f), floatArrayOf(.05f, .45f, 420f, .45f), floatArrayOf(.95f, .42f, 460f, .45f))
        private val CAR_POSITIONS = arrayOf(floatArrayOf(4.2f, 120f, 760f, 0f), floatArrayOf(4.2f, 1240f, 760f, 1f),
            floatArrayOf(3.4f, 300f, 690f, 0f), floatArrayOf(3.4f, 1060f, 690f, 1f))
        private val CAST = arrayOf(Actor("mechanic", 6.5f, 380f, 880f, .12f), Actor("hero", 6.6f, 680f, 900f, .25f),
            Actor("guest", 6.5f, 960f, 880f, .12f), Actor("fuel", 6f, 1120f, 860f, 0f), Actor("guest", 5.2f, 220f, 830f, .3f))
    }
}

/** 처음 어둠에서 밝아짐, 시네마 띠, 누른 뒤의 따뜻한 번쩍임(0.12초)과 어두워짐. 글자 위에 그린다. */
private class PosterOverlay(context: Context) : View(context) {
    var time = PrototypeTitleView.LOOP
    var calm = true
    var press = 0f
    private val paint = Paint()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val t = time
        val k = min(width / PrototypeTitleView.STAGE_W, height / PrototypeTitleView.STAGE_H)
        val bars = if (calm) 0f else 1 - glide((t - PrototypeTitleView.GO) / .5f)
        if (bars > 0f) {
            paint.color = Color.BLACK
            canvas.drawRect(0f, 0f, width.toFloat(), 80 * k * bars, paint)
            canvas.drawRect(0f, height - 80 * k * bars, width.toFloat(), height.toFloat(), paint)
        }
        val fade = if (calm) 1f else step((t / (PrototypeTitleView.LIT - .05f)).coerceIn(0f, 1f), 6)
        if (fade < 1f) { paint.color = Color.BLACK; paint.alpha = ((1 - fade) * 255).roundToInt(); canvas.drawPaint(paint) }
        val warm = step(((press - .2f) / .4f).coerceIn(0f, 1f), 5) * .7f
        val flash = press >= .6f && press < .6f + .12f * 1000 / PrototypeTitleView.EXIT_MS
        val glow = max(warm, if (flash) .92f else 0f)
        if (glow > 0f) { paint.color = 0xffffe6b0.toInt(); paint.alpha = (glow * 255).roundToInt(); canvas.drawPaint(paint) }
        val dark = ((press - .74f) / .2f).coerceIn(0f, 1f)
        if (dark > 0f) { paint.color = 0xff120a18.toInt(); paint.alpha = (dark * 255).roundToInt(); canvas.drawPaint(paint) }
        paint.alpha = 255
    }
}
