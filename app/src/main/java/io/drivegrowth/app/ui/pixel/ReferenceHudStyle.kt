package io.drivegrowth.app.ui.pixel

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * 지도 HUD·탐색 도크·창에 쓰는 공통 표면. 그림(마을·차·사람)은 픽셀 그대로 두고, 창과 버튼은 깔끔한 카드로 그린다.
 * 흰 카드 + 옅은 그림자 + 남색 글씨 + 바다색 강조 + 노란 주 버튼. 이름(sign…)은 기존 호출부를 바꾸지 않으려고 유지한다.
 */
internal class ReferenceHudStyle(private val context: Context) {
    val outline = CleanUi.LINE
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun surface(radius: Int = 14, selected: Boolean = false, focused: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (selected) intArrayOf(CleanUi.SUN, CleanUi.SUN_DEEP) else intArrayOf(0xe61a3358.toInt(), 0xe6112645.toInt()),
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(if (focused) 2 else 1), if (focused) CleanUi.SEA else if (selected) 0xffffe08a.toInt() else outline)
    }

    fun buttonSurface(selected: Boolean = false, outlined: Boolean = true) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), surface(selected = selected, focused = true))
        addState(intArrayOf(android.R.attr.state_pressed), surface(selected = true))
        addState(intArrayOf(), if (selected || outlined) surface(selected = selected)
            else GradientDrawable().apply { setColor(0x00000000) })
    }

    /** 카드 위 글씨 색(짙은 남색). */
    val signInk = CleanUi.INK

    fun signSurface(focused: Boolean = false): Drawable = CleanCard(context, CleanCard.Kind.PANEL, focused = focused)

    fun detailSurface(active: Boolean): Drawable = CleanCard(context, if (active) CleanCard.Kind.CHIP_ACTIVE else CleanCard.Kind.CHIP)

    fun signButton(primary: Boolean = false): Drawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), CleanCard(context, CleanCard.Kind.DISABLED))
        addState(intArrayOf(android.R.attr.state_pressed), CleanCard(context, if (primary) CleanCard.Kind.PRIMARY else CleanCard.Kind.BUTTON, pressed = true))
        addState(intArrayOf(android.R.attr.state_focused), CleanCard(context, if (primary) CleanCard.Kind.PRIMARY else CleanCard.Kind.BUTTON, focused = true))
        addState(intArrayOf(), CleanCard(context, if (primary) CleanCard.Kind.PRIMARY else CleanCard.Kind.BUTTON))
    }

    fun icon(icon: ReferenceHudIcon, size: Int): Drawable = ReferenceHudDrawable(icon).apply {
        setBounds(0, 0, dp(size), dp(size))
    }
}

/** 깔끔한 화면의 공통 색. */
internal object CleanUi {
    /** 판 위 글씨(흰색)와 보조 글씨. */
    const val INK = 0xfff4f8ff.toInt()
    const val MUTED = 0xffa6bcd6.toInt()
    /** 하늘색 강조(수치·선택 테두리·진행 막대). */
    const val SEA = 0xff2cc6ef.toInt()
    const val SEA_LIGHT = 0xff7ae4ff.toInt()
    const val SEA_DEEP = 0xff12305a.toInt()
    /** 노란 주 버튼과 그 위 글씨. */
    const val SUN = 0xffffc52e.toInt()
    const val SUN_DEEP = 0xfff09a0a.toInt()
    const val ON_PRIMARY = 0xff1a2338.toInt()
    /** 반투명 남색 판. */
    const val CARD = 0xeb13284a.toInt()
    const val CARD_SOFT = 0xff0d1d36.toInt()
    const val CHIP = 0x2effffff
    const val LINE = 0x59ffffff
}

/** 둥근 모서리·옅은 그림자·얇은 테두리의 카드. 버튼·칩·창이 같은 모양을 쓴다. */
private class CleanCard(
    context: Context,
    private val kind: Kind,
    private val focused: Boolean = false,
    private val pressed: Boolean = false,
) : Drawable() {
    enum class Kind { PANEL, CHIP, CHIP_ACTIVE, BUTTON, PRIMARY, DISABLED }

    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val radius = density * when (kind) { Kind.PANEL -> 12f; Kind.CHIP, Kind.CHIP_ACTIVE -> 8f; else -> 10f }
        val lift = if (kind == Kind.PANEL || kind == Kind.PRIMARY || kind == Kind.BUTTON) density * (if (pressed) 1f else 2.5f) else 0f
        box.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom - lift)
        if (lift > 0f) {   // 아래로 떨어지는 옅은 그림자
            paint.shader = null
            paint.color = if (kind == Kind.PRIMARY) 0x66a35f00 else 0x40000814
            canvas.drawRoundRect(box.left, box.top + lift, box.right, box.bottom + lift, radius, radius, paint)
        }
        val (top, bottom) = when (kind) {
            Kind.PANEL -> 0xeb1a3459.toInt() to 0xeb10233f.toInt()
            Kind.CHIP -> 0x24ffffff to 0x14ffffff
            Kind.CHIP_ACTIVE -> 0x592cc6ef to 0x402cc6ef
            Kind.BUTTON -> if (pressed) 0xff0f2442.toInt() to 0xff0f2442.toInt() else 0xff27497a.toInt() to 0xff1b365e.toInt()
            Kind.PRIMARY -> if (pressed) CleanUi.SUN_DEEP to CleanUi.SUN_DEEP else 0xffffd85a.toInt() to CleanUi.SUN
            Kind.DISABLED -> 0x99223654.toInt() to 0x991a2b46.toInt()
        }
        paint.color = 0xff000000.toInt()   // 그림자 색의 투명도가 본 판에 남지 않게
        paint.alpha = alpha
        paint.shader = LinearGradient(0f, box.top, 0f, box.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.shader = null
        val stroke = when {
            focused -> CleanUi.SEA
            kind == Kind.PRIMARY -> 0xffffe08a.toInt()
            kind == Kind.CHIP_ACTIVE -> CleanUi.SEA
            kind == Kind.DISABLED -> 0x33ffffff
            else -> CleanUi.LINE
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * if (focused) 2f else 1f
        paint.color = stroke
        val half = paint.strokeWidth / 2
        canvas.drawRoundRect(box.left + half, box.top + half, box.right - half, box.bottom - half, radius, radius, paint)
        paint.style = Paint.Style.FILL
        if (kind == Kind.PANEL) {
            paint.color = CleanUi.SEA
            canvas.drawRoundRect(box.left + radius, box.top - density * .5f, box.left + radius + density * 28, box.top + density * 2.5f, density, density, paint)
        }
        if (kind == Kind.PRIMARY && !pressed) {   // 위쪽 얇은 빛
            paint.color = 0x80ffffff.toInt()
            canvas.drawRoundRect(box.left + density * 6, box.top + density * 2, box.right - density * 6, box.top + density * 4, density, density, paint)
        }
    }

    private var alpha = 255
    override fun getPadding(padding: android.graphics.Rect): Boolean = false
    override fun setAlpha(alpha: Int) { this.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 원형 배지 위에 흰 선 그림을 얹은 아이콘. 24 단위 격자로 그린다. */
internal enum class ReferenceHudIcon(val accent: Int) {
    GARAGE(0xff2f86d6.toInt()),
    COLLECTION(0xff2a9d8f.toInt()),
    GACHA(0xffa25ddc.toInt()),
    TRUCK(0xff1f7ac4.toInt()),
    SETTLEMENT(0xffe0951e.toInt()),
    RECORD(0xff2e9e5b.toInt()),
    COIN(0xfff2b01e.toInt()),
    SETTINGS(0xff5b6b80.toInt());

    fun draw(canvas: Canvas, paint: Paint, path: Path) {
        paint.style = Paint.Style.FILL
        paint.color = accent
        canvas.drawCircle(12f, 12f, 12f, paint)
        paint.color = 0x33ffffff
        canvas.drawCircle(12f, 9.5f, 9f, paint)
        paint.color = 0xffffffff.toInt()
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        path.rewind()
        when (this) {
            GARAGE -> {
                path.moveTo(5f, 11f); path.lineTo(12f, 5.5f); path.lineTo(19f, 11f); path.lineTo(19f, 18f); path.lineTo(5f, 18f); path.close()
                canvas.drawPath(path, paint)
                paint.color = accent; canvas.drawRect(8.5f, 12.5f, 15.5f, 18f, paint)
                paint.color = 0xffffffff.toInt(); canvas.drawRect(8.5f, 14.5f, 15.5f, 15.3f, paint); canvas.drawRect(8.5f, 16.4f, 15.5f, 17.2f, paint)
            }
            COLLECTION -> {
                canvas.drawRoundRect(5.5f, 6f, 11.6f, 18f, 1.2f, 1.2f, paint)
                canvas.drawRoundRect(12.4f, 6f, 18.5f, 18f, 1.2f, 1.2f, paint)
                paint.color = accent
                canvas.drawRect(7.2f, 9f, 10f, 9.8f, paint); canvas.drawRect(7.2f, 11.4f, 10f, 12.2f, paint)
                canvas.drawRect(14f, 9f, 16.8f, 9.8f, paint); canvas.drawRect(14f, 11.4f, 16.8f, 12.2f, paint)
            }
            GACHA -> {
                canvas.drawCircle(12f, 11.5f, 6.5f, paint)
                paint.color = accent; canvas.drawRect(5.5f, 11f, 18.5f, 12.2f, paint)
                paint.color = 0xffffd65a.toInt(); canvas.drawCircle(12f, 11.6f, 2f, paint)
                paint.color = 0xffffffff.toInt(); canvas.drawRoundRect(9f, 18.2f, 15f, 19.6f, .7f, .7f, paint)
            }
            TRUCK -> {
                canvas.drawRoundRect(4.5f, 8f, 13.5f, 15.5f, 1.2f, 1.2f, paint)
                path.moveTo(13.5f, 10f); path.lineTo(17.2f, 10f); path.lineTo(19.5f, 12.8f); path.lineTo(19.5f, 15.5f); path.lineTo(13.5f, 15.5f); path.close()
                canvas.drawPath(path, paint)
                paint.color = accent; canvas.drawRect(14.6f, 11f, 16.8f, 12.6f, paint)
                paint.color = 0xffffffff.toInt(); canvas.drawCircle(8f, 16.5f, 1.9f, paint); canvas.drawCircle(16.5f, 16.5f, 1.9f, paint)
                paint.color = accent; canvas.drawCircle(8f, 16.5f, .8f, paint); canvas.drawCircle(16.5f, 16.5f, .8f, paint)
            }
            SETTLEMENT -> {
                path.moveTo(6.5f, 5.5f); path.lineTo(17.5f, 5.5f); path.lineTo(17.5f, 18.5f)
                path.lineTo(15.7f, 17.3f); path.lineTo(13.9f, 18.5f); path.lineTo(12f, 17.3f); path.lineTo(10.1f, 18.5f); path.lineTo(8.3f, 17.3f); path.lineTo(6.5f, 18.5f); path.close()
                canvas.drawPath(path, paint)
                paint.color = accent
                canvas.drawRect(8.6f, 8.5f, 15.4f, 9.4f, paint); canvas.drawRect(8.6f, 11f, 15.4f, 11.9f, paint); canvas.drawRect(8.6f, 13.5f, 12.8f, 14.4f, paint)
            }
            RECORD -> {
                canvas.drawRoundRect(6f, 12.5f, 9f, 18f, .8f, .8f, paint)
                canvas.drawRoundRect(10.5f, 9f, 13.5f, 18f, .8f, .8f, paint)
                canvas.drawRoundRect(15f, 6f, 18f, 18f, .8f, .8f, paint)
            }
            COIN -> {
                paint.color = 0xffffe08a.toInt(); canvas.drawCircle(12f, 12f, 8f, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.4f; paint.color = 0xffc98300.toInt(); canvas.drawCircle(12f, 12f, 5.4f, paint)
                paint.style = Paint.Style.FILL; canvas.drawRect(11.3f, 8.8f, 12.7f, 15.2f, paint)
            }
            SETTINGS -> {
                for (i in 0 until 8) {
                    canvas.save(); canvas.rotate(i * 45f, 12f, 12f); canvas.drawRoundRect(10.6f, 4.6f, 13.4f, 8f, .6f, .6f, paint); canvas.restore()
                }
                canvas.drawCircle(12f, 12f, 5.6f, paint)
                paint.color = accent; canvas.drawCircle(12f, 12f, 2.3f, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }
}

private class ReferenceHudDrawable(private val icon: ReferenceHudIcon) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        canvas.save()
        canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2)
        canvas.scale(size / 24f, size / 24f)
        icon.draw(canvas, paint, path)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
