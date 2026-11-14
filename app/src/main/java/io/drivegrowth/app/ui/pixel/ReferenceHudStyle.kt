package io.drivegrowth.app.ui.pixel

import android.R.attr.state_enabled
import android.R.attr.state_focused
import android.R.attr.state_pressed
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable

/** 지도 HUD, 기능 창과 조작 버튼의 공통 표면. */
internal class ReferenceHudStyle(private val context: Context) {
    val signInk = CleanUi.INK

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun signSurface(focused: Boolean = false): Drawable = panel(CleanUi.CARD, focused)

    fun detailSurface(active: Boolean, fill: Int? = null): Drawable =
        panel(fill ?: if (active) CleanUi.SEA_DEEP else CleanUi.CHIP, active, step = 2)

    fun featureHeading(): Drawable = panel(CleanUi.PANEL_TOP)

    fun signButton(primary: Boolean = false): Drawable = StateListDrawable().apply {
        val fill = if (primary) CleanUi.PRIMARY else CleanUi.CHIP
        addState(intArrayOf(-state_enabled), panel(CleanUi.DISABLED, line = CleanUi.DISABLED_LINE))
        addState(intArrayOf(state_pressed), panel(if (primary) CleanUi.PRIMARY_PRESSED else CleanUi.SEA_DEEP, focused = true))
        addState(intArrayOf(state_focused), panel(fill, focused = true))
        addState(intArrayOf(), panel(fill))
    }

    fun featureButtonText(primary: Boolean) = ColorStateList(
        arrayOf(intArrayOf(-state_enabled), intArrayOf()),
        intArrayOf(CleanUi.MUTED, if (primary) CleanUi.ON_PRIMARY else CleanUi.INK),
    )

    fun panel(fill: Int, focused: Boolean = false, step: Int = 3, line: Int = CleanUi.LINE): Drawable =
        PixelSignDrawable(dp(step), fill, if (focused) CleanUi.SEA_LIGHT else line,
            dp(if (focused) 2 else 1).coerceAtLeast(1),
            raised = fill == CleanUi.CARD || fill == CleanUi.PANEL_TOP || fill == CleanUi.CHIP ||
                fill == CleanUi.SEA_DEEP || fill == CleanUi.PRIMARY || fill == CleanUi.PRIMARY_PRESSED || fill == CleanUi.DISABLED)

    fun icon(icon: ReferenceHudIcon, size: Int): Drawable = ReferenceHudDrawable(icon).apply {
        setBounds(0, 0, dp(size), dp(size))
    }
}

internal object CleanUi {
    const val INK = 0xfff2f6ff.toInt()
    const val MUTED = 0xffbdcce4.toInt()
    const val SEA = 0xff8ebcf7.toInt()
    const val SEA_LIGHT = 0xffbfdcff.toInt()
    const val SEA_DEEP = 0xff19294c.toInt()
    const val SUN = 0xffffd45a.toInt()
    const val SUN_DEEP = 0xffe9af4c.toInt()
    const val PRIMARY = 0xff2865db.toInt()
    const val PRIMARY_PRESSED = 0xff1b4fb3.toInt()
    const val ON_PRIMARY = 0xfff7faff.toInt()
    const val CARD = 0xff182545.toInt()
    const val CARD_SOFT = 0xff111b33.toInt()
    const val PANEL_TOP = 0xff233657.toInt()
    const val CHIP = 0xff2a3b5e.toInt()
    const val LINE = SEA
    const val DISABLED = 0xff414b65.toInt()
    const val DISABLED_LINE = 0xff667897.toInt()
    const val TRACK = 0xff475677.toInt()
    const val SCRIM = 0x99101931.toInt()
}

/** 좌표가 정해진 작은 색 블록으로 그려 배율이 달라도 아이콘 모양을 유지한다. */
internal enum class ReferenceHudIcon(private val pixels: List<String>, val accent: Int = 0xff8fcaff.toInt()) {
    GARAGE(listOf(
        ".......11.......", "......1551......", ".....155551.....", "...1155555511...", "..155555555551..",
        ".15511111111551.", ".11144444444111.", "..144111111441..", "..141222222141..", "..141233332141..",
        "..112333333211..", "..121555555121..", "..122222222221..", "...1133113311...", "..111111111111..", "................",
    ), 0xff2886d4.toInt()),
    COLLECTION(listOf(
        "................", "..11111..11111..", ".14444411444441.", ".14666411444441.", ".14444411411141.",
        ".14666411412141.", ".14444411422241.", ".14666411433341.", ".14444411422241.", ".14666411411141.",
        ".14444411444441.", ".15444411444451.", "..155551155551..", "...1111111111...", "................", "................",
    ), 0xff267fca.toInt()),
    GACHA(listOf(
        ".....111111.....", "....13333331....", "...1333333331...", "..133322553331..", "..133525255331..",
        "..133333333331..", "..133522253331..", "...1111111111...", "....12222221....", "....12233221....",
        "....12232221....", "....11111111....", "....12244221....", "....11111111....", "...1555555551...", "...1111111111...",
    ), 0xffb05adc.toInt()),
    TRUCK(listOf(
        "................", "................", "....11111.......", "...1233321......", "..123333321.....",
        ".12222222221....", ".12422222421....", ".111111111111...", ".1555555555151..", ".15555555551531.",
        ".155555555515331", ".155555555515551", ".111111111111111", "..131.....131...", "...1.......1....", "................",
    ), 0xff35a9ee.toInt()),
    SETTLEMENT(listOf(
        "................", "...111111111....", "...133333331....", "...136666331....", "...133333331....",
        "...136666331....", "...133333331....", "...13666611111..", "..1333331444441.", "..1366631422241.",
        "..1333331444441.", ".133333331111111", ".133333314444441", ".111111114222241", "........11111111", "................",
    ), 0xffe3a130.toInt()),
    RECORD(listOf(
        "................", "...1111111111...", "..134444444431..", "..134444411131..", "..134444122231..",
        "..134441221231..", "..134122114431..", "..134111444431..", "..134444422431..", "..134442422431..",
        "..134242422431..", "..134242422431..", "..134111111431..", "..134444444431..", "...1111111111...", "................",
    ), 0xff35a96a.toInt()),
    COIN(listOf("....1111....", "..11444411..", ".1443333441.", ".1432222341.", "143224223341", "143224223341",
        "143224223341", "143224223341", ".1432222341.", ".1443333441.", "..11444411..", "....1111...."), 0xffffae33.toInt()),
    SETTINGS(listOf("....1441....", ".11.1441.11.", ".1414444141.", "..14444441..", "11444..44411", "1444....4441",
        "1444....4441", "11444..44411", "..14444441..", ".1414444141.", ".11.1441.11.", "....1441...."));

    val gridSize get() = pixels.size

    fun draw(canvas: Canvas, paint: Paint) {
        for ((y, row) in pixels.withIndex()) {
            for ((x, tone) in row.withIndex()) {
                paint.color = when (tone) {
                    '1' -> CleanUi.CARD_SOFT
                    '2' -> accent
                    '3' -> 0xffeef8ff.toInt()
                    '4' -> when (this) {
                        COIN, GACHA, SETTLEMENT -> 0xffffdb6a.toInt()
                        TRUCK, RECORD -> 0xffffeaa1.toInt()
                        GARAGE, COLLECTION -> 0xfff5dab0.toInt()
                        SETTINGS -> CleanUi.INK
                    }
                    '5' -> 0xffed5352.toInt()
                    '6' -> if (this == TRUCK) 0xff88c44e.toInt() else 0xffbc906b.toInt()
                    else -> continue
                }
                canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
            }
        }
    }
}

private class ReferenceHudDrawable(private val icon: ReferenceHudIcon) : Drawable() {
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val size = (minOf(bounds.width(), bounds.height()) / icon.gridSize).coerceAtLeast(1).toFloat()
        val radius = icon.gridSize * size / 2
        canvas.save()
        canvas.translate(bounds.exactCenterX() - radius, bounds.exactCenterY() - radius)
        canvas.scale(size, size)
        icon.draw(canvas, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 정수 크기의 모서리와 테두리를 사용하고 바탕 투명도를 유지한다. */
private class PixelSignDrawable(
    private val step: Int,
    private val fill: Int,
    private val line: Int,
    private val stroke: Int,
    private val raised: Boolean,
) : Drawable() {
    private val paint = Paint()
    private val shape = Path()
    private var opacity = 255
    private var sheen: Shader? = null

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        sheen = if (raised && bounds.height() >= 18 * stroke) {
            LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
                intArrayOf(0x22b1d9ff, 0x00b1d9ff, 0x0004091b, 0x3004091b),
                floatArrayOf(0f, .34f, .63f, 1f),
                Shader.TileMode.CLAMP)
        } else null
    }

    override fun draw(canvas: Canvas) {
        val l = bounds.left.toFloat()
        val t = bounds.top.toFloat()
        val r = bounds.right.toFloat()
        val b = bounds.bottom.toFloat()
        val p = minOf(step, (minOf(bounds.width(), bounds.height()) - 2 * stroke) / 4).coerceAtLeast(0).toFloat()
        fun plate(inset: Float, bottom: Float, color: Int) {
            val x = l + inset
            val y = t + inset
            val end = r - inset
            val floor = bottom - inset
            if (end - x < 3 * p || floor - y < 3 * p) return
            shape.rewind()
            shape.moveTo(x + 2 * p, y)
            shape.lineTo(end - 2 * p, y)
            shape.lineTo(end - 2 * p, y + p)
            shape.lineTo(end - p, y + p)
            shape.lineTo(end - p, y + 2 * p)
            shape.lineTo(end, y + 2 * p)
            shape.lineTo(end, floor - 2 * p)
            shape.lineTo(end - p, floor - 2 * p)
            shape.lineTo(end - p, floor - p)
            shape.lineTo(end - 2 * p, floor - p)
            shape.lineTo(end - 2 * p, floor)
            shape.lineTo(x + 2 * p, floor)
            shape.lineTo(x + 2 * p, floor - p)
            shape.lineTo(x + p, floor - p)
            shape.lineTo(x + p, floor - 2 * p)
            shape.lineTo(x, floor - 2 * p)
            shape.lineTo(x, y + 2 * p)
            shape.lineTo(x + p, y + 2 * p)
            shape.lineTo(x + p, y + p)
            shape.lineTo(x + 2 * p, y + p)
            shape.close()
            paint.color = color
            paint.alpha = (color ushr 24) * opacity / 255
            canvas.drawPath(shape, paint)
        }
        val unit = stroke.toFloat()
        if (sheen == null) {
            plate(0f, b, line)
            plate(unit, b, fill)
            return
        }
        val faceBottom = b - unit
        plate(0f, b, 0xff09142d.toInt())
        plate(0f, faceBottom, line)
        plate(unit, faceBottom, if (fill == CleanUi.DISABLED) CleanUi.DISABLED_LINE else CleanUi.SEA_LIGHT)
        plate(2 * unit, faceBottom, CleanUi.SEA_DEEP)
        plate(3 * unit, faceBottom, fill)
        paint.shader = sheen
        plate(3 * unit, faceBottom, -1)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
