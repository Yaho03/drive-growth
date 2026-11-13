package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.shop.TownActors
import io.drivegrowth.app.ui.shop.townSlots
import io.drivegrowth.core.contract.VehicleId
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.sin

/** 주차 상태에서만 시작 화면과 장식용 움직임을 제공한다. */
@SuppressLint("ViewConstructor")
internal class PrototypeTitleView(context: Context, onStart: () -> Unit) : FrameLayout(context) {
    private var presentationAllowed = false
    private var startedAt = 0L
    private val landscape = TitleLandscape(context)
    private val words = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private val gold = heading("드라이브", PixelStyle.gold)
    private val sky = heading("키우기", 0xffcfe4ff.toInt())
    private val subtitle = heading("달린 만큼 커지는 우리 동네 렌터카", Color.WHITE).apply {
        setAutoSizeTextTypeUniformWithConfiguration(13, 22, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private val start = Button(context).apply {
        tag = "presentation:start"
        text = "게임 시작"
        isAllCaps = false
        isEnabled = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(PixelStyle.primary)
        minHeight = dp(52)
        minimumHeight = dp(52)
        setPadding(dp(26), 0, dp(26), 0)
        setOnClickListener { if (presentationAllowed) onStart() }
    }

    init {
        tag = "presentation:title"
        setBackgroundColor(PixelStyle.background)
        addView(landscape, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(words, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(20)
            marginEnd = dp(20)
            bottomMargin = dp(80)
        })
        words.addView(gold, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        words.addView(sky, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        words.addView(subtitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(30)).apply {
            topMargin = dp(8)
        })
        addView(start, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })
    }

    fun setPresentationAllowed(allowed: Boolean) {
        if (presentationAllowed == allowed) return
        presentationAllowed = allowed
        start.isEnabled = allowed
        updateMotion()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val metrics = resources.displayMetrics
        val size = minOf(w / (8 * metrics.scaledDensity), (h - dp(120)).coerceAtLeast(dp(32)) / (2.6f * metrics.scaledDensity))
            .coerceIn(12f, 74f)
        gold.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        sky.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animateScene)
        startedAt = 0L
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateMotion()
    }

    private val animateScene = object : Runnable {
        override fun run() {
            if (!canAnimateScene()) return
            landscape.elapsed = (SystemClock.uptimeMillis() - startedAt) / 1_000f
            landscape.invalidate()
            postDelayed(this, 33L)
        }
    }

    private fun canAnimateScene() =
        presentationAllowed && isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()

    private fun updateMotion() {
        removeCallbacks(animateScene)
        if (canAnimateScene()) {
            startedAt = SystemClock.uptimeMillis() - (landscape.elapsed * 1_000f).toLong()
            post(animateScene)
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            landscape.elapsed = 0f
            landscape.invalidate()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun heading(label: String, color: Int) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setSingleLine()
        includeFontPadding = false
        typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
        setTextColor(color)
        setShadowLayer(dp(4).toFloat(), 0f, dp(3).toFloat(), PixelStyle.panel)
    }
}

private class TitleLandscape(context: Context) : View(context) {
    var elapsed = 0f
    private val town by lazy(LazyThreadSafetyMode.NONE) {
        try {
            context.assets.open("pixel/town.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
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
    private val paint = Paint()
    private val shade = Paint()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shade.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x55121522, 0xaa121522.toInt(), 0xdd121522.toInt()),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val background = town ?: return
        val scale = maxOf(width / scene.width(), height / scene.height()) *
            (1.015f + 0.015f * sin(elapsed / 4f))
        canvas.save()
        canvas.translate((width - scene.width() * scale) / 2f, (height - scene.height() * scale) * .65f)
        canvas.scale(scale, scale)
        canvas.drawBitmap(background, null, scene, paint)
        vehicles.forEachIndexed { index, bitmap ->
            val (x, y) = townSlots[index]
            drawVehicle(canvas, bitmap, x.toFloat(), y - 7f, flip = true)
        }
        actors.draw(canvas, elapsed, fuelInstalled = true, washInstalled = true)
        val firstX = (200f + elapsed * 150f) % 920f - 100f
        val secondX = (490f + elapsed * 190f) % 920f - 100f
        drawVehicle(canvas, vehicles[0], firstX, 722f + firstX / 2f, flip = false)
        drawVehicle(canvas, vehicles[1], secondX, 800f + secondX / 2f, flip = false)
        drawCloud(canvas, 120f, 105f, 4f)
        drawCloud(canvas, 850f, 255f, 6f)
        canvas.restore()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
    }

    private fun drawVehicle(canvas: Canvas, bitmap: Bitmap?, x: Float, y: Float, flip: Boolean) {
        if (bitmap == null) return
        area.set(x - bitmap.width, y - bitmap.height, x + bitmap.width, y + bitmap.height)
        canvas.save()
        if (flip) canvas.scale(-1f, 1f, x, y)
        canvas.drawBitmap(bitmap, null, area, paint)
        canvas.restore()
    }

    private fun drawCloud(canvas: Canvas, origin: Float, y: Float, speed: Float) {
        val x = (origin + elapsed * speed) % 1_850f - 180f
        paint.color = 0xb3ffffff.toInt()
        canvas.drawRect(x, y + 20f, x + 175f, y + 45f, paint)
        canvas.drawRect(x + 22f, y + 8f, x + 144f, y + 20f, paint)
        canvas.drawRect(x + 51f, y, x + 119f, y + 8f, paint)
        paint.color = Color.BLACK
    }
}
