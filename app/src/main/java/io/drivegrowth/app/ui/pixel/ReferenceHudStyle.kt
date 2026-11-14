package io.drivegrowth.app.ui.pixel

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/** 지도 HUD와 탐색 도크에 사용하는 시안의 공통 색·외곽선. */
internal class ReferenceHudStyle(private val context: Context) {
    val outline = 0xffc7e7fa.toInt()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun surface(radius: Int = 12, selected: Boolean = false, focused: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (selected) intArrayOf(0xff26b7ff.toInt(), 0xff0076ed.toInt())
        else intArrayOf(0xff26334f.toInt(), 0xff101b31.toInt()),
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(if (focused) 2 else 1), if (selected || focused) 0xff88d8ff.toInt() else outline)
    }

    fun buttonSurface(selected: Boolean = false, outlined: Boolean = true) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), surface(selected = selected, focused = true))
        addState(intArrayOf(android.R.attr.state_pressed), surface(selected = true))
        addState(intArrayOf(), if (selected || outlined) surface(selected = selected)
            else GradientDrawable().apply { setColor(0x00101b31) })
    }

    val signInk = 0xff163619.toInt()

    fun signSurface(focused: Boolean = false): Drawable = PixelSignDrawable(dp(3).coerceAtLeast(1), focused)

    fun detailSurface(active: Boolean, fill: Int? = null): Drawable =
        PixelSignDrawable(dp(2).coerceAtLeast(1), focused = false, primary = active, detail = true, detailFill = fill)

    fun signButton(primary: Boolean = false): Drawable = StateListDrawable().apply {
        val step = dp(3).coerceAtLeast(1)
        addState(intArrayOf(-android.R.attr.state_enabled), PixelSignDrawable(step, false, disabled = true))
        addState(intArrayOf(android.R.attr.state_focused), signSurface(focused = true))
        addState(intArrayOf(android.R.attr.state_pressed), signSurface(focused = true))
        addState(intArrayOf(), PixelSignDrawable(step, false, primary = primary))
    }

    fun icon(icon: ReferenceHudIcon, size: Int): Drawable = ReferenceHudDrawable(icon).apply {
        setBounds(0, 0, dp(size), dp(size))
    }
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
                    '1' -> 0xff0b1a32.toInt()
                    '2' -> accent
                    '3' -> 0xffeef8ff.toInt()
                    '4' -> when (this) {
                        COIN, GACHA, SETTLEMENT -> 0xffffdb6a.toInt()
                        TRUCK, RECORD -> 0xffffeaa1.toInt()
                        GARAGE, COLLECTION -> 0xfff5dab0.toInt()
                        SETTINGS -> 0xff254623.toInt()
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
    private val focused: Boolean,
    private val primary: Boolean = false,
    private val disabled: Boolean = false,
    private val detail: Boolean = false,
    private val detailFill: Int? = null,
) : Drawable() {
    private val paint = Paint()
    private val shape = android.graphics.Path()

    override fun draw(canvas: Canvas) {
        val l = bounds.left.toFloat()
        val t = bounds.top.toFloat()
        val r = bounds.right.toFloat()
        val b = bounds.bottom.toFloat()
        val p = step.toFloat()
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
            canvas.drawPath(shape, paint)
        }
        plate(0f, b, if (detail) 0xffb68654.toInt() else 0xcc183119.toInt())
        plate(0f, b - p, when {
            detail -> 0xff916340.toInt()
            focused || primary -> 0xff2c772f.toInt()
            disabled -> 0xff8a7969.toInt()
            else -> 0xff704822.toInt()
        })
        plate(p, b, if (primary && !detail) 0xff87a94c.toInt() else 0xffdaa975.toInt())
        plate(2 * p, b, when {
            detail -> detailFill ?: if (primary) 0xffe4edbf.toInt() else 0xfff7e1ac.toInt()
            focused -> 0xffffd985.toInt()
            primary -> 0xffe0f1b6.toInt()
            disabled -> 0xffe6ded0.toInt()
            else -> 0xffffedbd.toInt()
        })
        paint.color = 0xfffdf6df.toInt()
        canvas.drawRect(l + 4 * p, t + 2 * p, r - 4 * p, t + 3 * p, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
