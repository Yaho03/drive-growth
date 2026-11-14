package io.drivegrowth.app.ui.presentation

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/** 그림과 실제 Android 조작을 원본의 같은 1360×948 좌표로 배치한다. */
internal class ReferenceSceneFrame(context: Context) : FrameLayout(context) {
    private val stage = FrameLayout(context).also {
        it.clipChildren = false
        it.clipToPadding = false
        it.pivotX = 0f
        it.pivotY = 0f
        addView(it, LayoutParams(WIDTH, HEIGHT))
    }

    fun place(view: View, x: Int, y: Int, width: Int, height: Int) {
        stage.addView(view, LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y
        })
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val scale = min(width.toFloat() / WIDTH, height.toFloat() / HEIGHT)
        val x = ((width - WIDTH * scale) / 2).toInt()
        val y = ((height - HEIGHT * scale) / 2).toInt()
        stage.layout(x, y, x + WIDTH, y + HEIGHT)
        stage.scaleX = scale
        stage.scaleY = scale
    }

    companion object {
        const val WIDTH = 1360
        const val HEIGHT = 948
    }
}
