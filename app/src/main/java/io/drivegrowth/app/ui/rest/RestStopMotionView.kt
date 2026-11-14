package io.drivegrowth.app.ui.rest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.ImageDecoder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.R

/**
 * 7차 휴게소 연출: 비 오는 밤 휴게소 → 트럭 운전석 → 담요 덮고 자는 사장님.
 * 그림은 보드의 rest-stop-scene.js를 시간별로 그려 묶은 assets/pixel/rest 폴더의 WebP이고, 340×237 원본을 픽셀 그대로 키운다.
 * 첫 재생(6초) 뒤 10초 반복. 움직임 줄이기면 숨쉬기만 있는 정지 장면과 카드를 바로 보여 준다. 정보 카드는 네이티브 글자로 그린다.
 */
internal class RestStopMotionView(context: Context, private val onClose: (() -> Unit)? = null) : FrameLayout(context) {
    enum class State { RESTING, PENDING, UNKNOWN }

    private var drawable: AnimatedImageDrawable? = null
    private var state: State? = null
    private var scale = 1f

    private val chip = TextView(context).apply { gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false }
    private val body = TextView(context).apply { setTextColor(INK) }
    private val close = Button(context).apply {
        text = context.getString(R.string.rest_scene_close); isAllCaps = false; setTextColor(INK); minWidth = 0; minHeight = 0
        setOnClickListener { onClose?.invoke() }
    }
    private val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(chip); addView(body) }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        if (onClose != null) addView(close)
    }
    private val skip = Button(context).apply {
        text = context.getString(R.string.rest_scene_skip); isAllCaps = false; setTextColor(0xffc8ccf4.toInt()); minWidth = 0; minHeight = 0
        setOnClickListener { toLoop() }
    }
    private val cardIn = Runnable { showCard(animate = true) }

    init {
        setWillNotDraw(false)
        setBackgroundColor(0xff04050a.toInt())
        contentDescription = context.getString(R.string.rest_scene_description)
        addView(card); addView(skip)
    }

    /** 같은 상태로 다시 부르면 처음부터 다시 틀지 않는다. */
    fun play(next: State) {
        if (state == next) return
        state = next
        bindCard()
        if (isAttachedToWindow) start()
    }

    private fun start() {
        removeCallbacks(cardIn)
        if (!ValueAnimator.areAnimatorsEnabled()) { load("still", once = false); skip.visibility = GONE; showCard(animate = false); return }
        load("intro", once = true)
        skip.visibility = VISIBLE
        card.alpha = 0f
        postDelayed(cardIn, CARD_AT_MS)
    }

    private fun toLoop() {
        removeCallbacks(cardIn)
        load("loop", once = false)
        skip.visibility = GONE
        if (card.alpha < 1f) showCard(animate = false)
    }

    private fun showCard(animate: Boolean) {
        card.animate().cancel()
        if (!animate) { card.alpha = 1f; card.translationY = 0f; return }
        card.translationY = 64 * scale
        card.animate().alpha(1f).translationY(0f).setDuration(800).start()
    }

    private fun load(part: String, once: Boolean) {
        drawable?.let { it.stop(); it.callback = null; it.clearAnimationCallbacks() }
        drawable = try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, "pixel/rest/$VARIANT-$part.webp")) as? AnimatedImageDrawable
        } catch (e: Exception) { null }   // 그림을 못 읽으면 검은 바탕에 카드만 보인다
        drawable?.apply {
            setBounds(0, 0, WIDTH, HEIGHT)
            callback = this@RestStopMotionView
            repeatCount = if (once) 0 else AnimatedImageDrawable.REPEAT_INFINITE
            if (once) registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(d: Drawable?) { post { if (d === drawable) toLoop() } }
            })
            start()
        }
        invalidate()
    }

    private fun bindCard() {
        val s = state ?: return
        val warm = s == State.RESTING
        chip.visibility = if (s == State.UNKNOWN) GONE else VISIBLE
        chip.text = context.getString(if (warm) R.string.rest_scene_chip_resting else R.string.rest_scene_chip_pending)
        chip.setTextColor(if (warm) 0xff1c1e2c.toInt() else 0xffc8ccf4.toInt())
        chip.background = GradientDrawable().apply { setColor(if (warm) AMBER else 0xff2f3566.toInt()); cornerRadius = 999f }
        body.setTextColor(if (s == State.UNKNOWN) 0xffc8ccf4.toInt() else INK)
        body.text = when (s) {
            State.RESTING -> SpannableString(context.getString(R.string.rest_scene_bonus)).also { text ->
                Regex("[\\d.]+배").find(text)?.let { text.setSpan(ForegroundColorSpan(AMBER), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            State.PENDING -> context.getString(R.string.rest_scene_pending)
            State.UNKNOWN -> context.getString(R.string.rest_scene_unknown)
        }
        applyScale()
    }

    /** 보드의 1360×948 배치(카드 폭 780, 아래 40)를 지금 크기에 맞춘다. */
    private fun applyScale() {
        val k = scale
        fun px(v: Float) = (v * k).toInt()
        card.background = GradientDrawable().apply {
            setColor(0xf00b0d1e.toInt()); cornerRadius = 24 * k; setStroke(px(4f).coerceAtLeast(1), 0xff2a2f5c.toInt())
        }
        card.setPadding(px(30f), px(24f), px(28f), px(24f))
        card.layoutParams = LayoutParams(px(780f), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(40f) }
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20 * k)
        chip.setPadding(px(14f), px(6f), px(14f), px(6f))
        chip.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(14f) }
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (state == State.UNKNOWN) 24 else if (state == State.PENDING) 26 else 28) * k)
        body.setLineSpacing(0f, 1.3f)
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * k)
        close.setPadding(px(28f), px(14f), px(28f), px(14f))
        close.background = GradientDrawable().apply { setColor(0xff262b54.toInt()); cornerRadius = 14 * k; setStroke(px(3f).coerceAtLeast(1), 0xff3a4070.toInt()) }
        close.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(28f) }
        skip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18 * k)
        skip.setPadding(px(18f), px(10f), px(18f), px(10f))
        skip.background = GradientDrawable().apply { setColor(0xd90b0d1e.toInt()); cornerRadius = 12 * k; setStroke(px(3f).coerceAtLeast(1), 0xff3a4070.toInt()) }
        skip.layoutParams = LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = px(28f); marginEnd = px(28f) }
        chip.typeface = Typeface.DEFAULT_BOLD
    }

    /** 가로세로 1360:948을 지킨다. 높이가 정해져 있으면 그 안에 맞춘다. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = w * STAGE_H / STAGE_W
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && h > maxH) { h = maxH; w = h * STAGE_W / STAGE_H }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = w / STAGE_W.toFloat()
        post { applyScale() }
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable ?: return   // 화면 캔버스에 바로 키워 그린다(픽셀 그대로 커지고, 프레임도 이 그리기로 넘어간다)
        canvas.save(); canvas.scale(width / WIDTH.toFloat(), height / HEIGHT.toFloat()); d.draw(canvas); canvas.restore()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state != null) start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(cardIn)
        drawable?.stop()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val WIDTH = 340
        private const val HEIGHT = 237
        private const val STAGE_W = 1360
        private const val STAGE_H = 948
        private const val CARD_AT_MS = 5000L
        private const val INK = 0xffeef0ff.toInt()
        private const val AMBER = 0xfff2b25c.toInt()
        /** 르르르 출품 사용권이 허가되지 않으면(#29) "blanket"으로 바꾼다. 담요를 머리끝까지 덮은 대체판이다. */
        const val VARIANT = "rururu"
    }
}
