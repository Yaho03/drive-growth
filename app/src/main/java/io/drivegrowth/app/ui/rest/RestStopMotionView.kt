package io.drivegrowth.app.ui.rest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle

/** #295의 담요판 타임라인. 장면 시간은 게임 상태와 무관하다. */
internal class RestStopMotionView(
    context: Context,
    private val onPartChanged: (Part?) -> Unit = {},
    onClose: (() -> Unit)? = null,
) : FrameLayout(context) {
    enum class Part(val asset: String) { INTRO("intro"), LOOP("loop"), STILL("still") }

    private var drawable: AnimatedImageDrawable? = null
    private var part: Part? = null
    private var introConsumed = false
    private val style = ReferenceHudStyle(context)
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = style.signSurface()
        setPadding(dp(12), dp(6), dp(8), dp(6))
    }
    private val message = TextView(context).apply {
        textSize = 15f
        setTextColor(style.signInk)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val skip = button(R.string.rest_scene_skip, "rest-stop:skip") {
        introConsumed = true
        play(Part.LOOP)
        revealCard(animate = false)
    }
    private val reveal = Runnable { revealCard(animate = true) }

    init {
        tag = "rest-stop:scene"
        setWillNotDraw(false)
        setBackgroundColor(0xff04050a.toInt())
        card.addView(message, LinearLayout.LayoutParams(0, -2, 1f))
        onClose?.let { action ->
            card.addView(button(R.string.rest_scene_close, "rest-stop:close", action).apply {
                nextFocusUpId = skip.id
                nextFocusLeftId = id
                nextFocusRightId = id
                skip.nextFocusDownId = id
            })
        }
        addView(card, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(12)
        })
        addView(skip, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(12)
            topMargin = dp(12)
        })
        contentDescription = context.getString(R.string.rest_scene_description)
    }

    fun bind(description: CharSequence) { message.text = description }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPlayback()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) syncPlayback()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isAttachedToWindow) syncPlayback()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (isAttachedToWindow) syncPlayback()
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    private fun syncPlayback() {
        if (!isShown || windowVisibility != View.VISIBLE || !hasWindowFocus()) {
            stopPlayback()
            return
        }
        val next = when {
            !ValueAnimator.areAnimatorsEnabled() -> Part.STILL
            part == Part.INTRO -> Part.INTRO
            introConsumed -> Part.LOOP
            else -> Part.INTRO
        }
        play(next)
    }

    private fun play(next: Part) {
        if (part == next) return
        releaseDrawable()
        part = next
        if (next == Part.INTRO) introConsumed = true
        val image = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, "pixel/rest/$VARIANT-${next.asset}.webp"))
        }.getOrNull() as? AnimatedImageDrawable
        drawable = image
        val intro = next == Part.INTRO && image != null
        skip.visibility = if (intro) VISIBLE else GONE
        if (intro && card.childCount > 1) skip.requestFocus()
        if (intro) {
            card.visibility = INVISIBLE
            postDelayed(reveal, CARD_AT_MS)
        } else revealCard(animate = false)
        image?.apply {
            setBounds(0, 0, 340, 237)
            callback = this@RestStopMotionView
            repeatCount = if (intro) 0 else AnimatedImageDrawable.REPEAT_INFINITE
            if (intro) registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(d: Drawable) {
                    // #295의 5초 카드 진입을 6초 루프 전환에서 끊지 않는다.
                    post { if (drawable === d && part == Part.INTRO) play(Part.LOOP) }
                }
            })
            start()
        }
        onPartChanged(if (image == null) null else next)
        invalidate()
    }

    private fun revealCard(animate: Boolean) {
        card.animate().cancel()
        card.visibility = VISIBLE
        if (card.childCount > 1 && !skip.isShown) card.getChildAt(1).requestFocus()
        card.alpha = if (animate) 0f else 1f
        card.translationY = if (animate) height * (64f / 948f) else 0f
        if (animate) card.animate().alpha(1f).translationY(0f).setDuration(800).start()
    }

    private fun releaseDrawable() {
        removeCallbacks(reveal)
        drawable?.let {
            it.clearAnimationCallbacks()
            it.stop()
            it.callback = null
        }
        drawable = null
    }

    private fun stopPlayback() {
        if (part == null) return
        part = null
        releaseDrawable()
        card.animate().cancel()
        onPartChanged(null)
    }

    override fun onDraw(canvas: Canvas) {
        val image = drawable ?: return
        val scale = minOf(width / 340f, height / 237f)
        canvas.save()
        canvas.translate((width - 340 * scale) / 2, (height - 237 * scale) / 2)
        canvas.scale(scale, scale)
        image.draw(canvas)
        canvas.restore()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    private fun button(label: Int, key: String, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId()
        tag = key
        text = context.getString(label)
        textSize = 15f
        isAllCaps = false
        minHeight = dp(64)
        minimumHeight = minHeight
        minWidth = dp(52)
        setTextColor(style.signInk)
        backgroundTintList = null
        background = style.signButton()
        setOnClickListener { if (isShown && hasWindowFocus()) action() }
    }

    private fun dp(value: Int) = style.dp(value)

    private companion object {
        /** 2026-11-14 사용자 결정으로 르르르판을 쓴다. 출품 사용권(#29)이 거절되면 "blanket"(담요를 머리끝까지 덮은 대체판)으로 바꾼다. */
        const val VARIANT = "rururu"
        const val CARD_AT_MS = 5000L
    }
}
