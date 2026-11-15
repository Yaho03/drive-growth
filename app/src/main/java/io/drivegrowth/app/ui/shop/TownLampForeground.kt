package io.drivegrowth.app.ui.shop

import android.graphics.Bitmap

/**
 * 마을 배경 그림에 함께 그려진 아래 연석의 가로등을 오려 내 주민 위에 다시 그린다.
 *
 * 걸을 수 있는 영역은 모두 가로등 받침(연석)보다 위쪽, 즉 화면상 뒤쪽이다. 그래서 주민을 그린 뒤
 * 가로등을 덮어 그리면 주민이 가로등 뒤로 지나가는 것처럼 보인다.
 */
internal object TownLampForeground {
    /** 배경 1536×1024 좌표의 가로등 상자(left, top, right, bottom). 다섯 배경 그림에서 위치가 같다. */
    private val boxes = listOf(
        intArrayOf(328, 716, 382, 836),
        intArrayOf(608, 852, 662, 974),
        intArrayOf(1188, 886, 1250, 1024),
    )
    private const val TOWN_WIDTH = 1536
    private const val TOWN_HEIGHT = 1024

    /** 이보다 어두운 픽셀은 가로등 윤곽선으로 보고 바깥 채우기를 막는다. */
    private const val OUTLINE_LUMINANCE = 70

    class Piece(val bitmap: Bitmap, val left: Float, val top: Float)

    fun extract(background: Bitmap): List<Piece> {
        if (background.width != TOWN_WIDTH || background.height != TOWN_HEIGHT) return emptyList()
        return boxes.mapNotNull { (left, top, right, bottom) ->
            val width = right - left
            val height = bottom - top
            val pixels = IntArray(width * height)
            background.getPixels(pixels, 0, width, left, top, width, height)
            val mask = mask(pixels, width, height)
            if (mask.none { it }) return@mapNotNull null
            for (index in pixels.indices) if (!mask[index]) pixels[index] = 0
            Piece(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), left.toFloat(), top.toFloat())
        }
    }

    /**
     * 상자 가장자리에서 시작해 어두운 윤곽선을 넘지 않게 바깥을 채운다. 남은 영역 중 상자 가운데 줄과
     * 이어진 덩어리를 가로등으로 보고, 줄마다 왼쪽~오른쪽 끝 사이를 메워 윤곽선 틈으로 샌 구멍을 막는다.
     */
    internal fun mask(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val size = width * height
        val outside = BooleanArray(size)
        val queue = IntArray(size)
        var head = 0
        var tail = 0
        fun pushOutside(index: Int) {
            if (outside[index] || luminance(pixels[index]) < OUTLINE_LUMINANCE) return
            outside[index] = true
            queue[tail++] = index
        }
        for (x in 0 until width) { pushOutside(x); pushOutside((height - 1) * width + x) }
        for (y in 0 until height) { pushOutside(y * width); pushOutside(y * width + width - 1) }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) pushOutside(index - 1)
            if (x < width - 1) pushOutside(index + 1)
            if (y > 0) pushOutside(index - width)
            if (y < height - 1) pushOutside(index + width)
        }

        val keep = BooleanArray(size)
        head = 0
        tail = 0
        fun pushKeep(index: Int) {
            if (keep[index] || outside[index]) return
            keep[index] = true
            queue[tail++] = index
        }
        val middle = height / 2 * width
        for (x in 0 until width) pushKeep(middle + x)
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) pushKeep(index - 1)
            if (x < width - 1) pushKeep(index + 1)
            if (y > 0) pushKeep(index - width)
            if (y < height - 1) pushKeep(index + width)
        }

        for (y in 0 until height) {
            val row = y * width
            var first = -1
            var last = -1
            for (x in 0 until width) if (keep[row + x]) { if (first < 0) first = x; last = x }
            if (first >= 0) for (x in first..last) keep[row + x] = true
        }
        return keep
    }

    private fun luminance(color: Int): Int {
        val r = color shr 16 and 0xff
        val g = color shr 8 and 0xff
        val b = color and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
