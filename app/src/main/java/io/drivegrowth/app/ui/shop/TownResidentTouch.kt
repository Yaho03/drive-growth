package io.drivegrowth.app.ui.shop

import kotlin.math.hypot

/** 한 터치가 잡은 주민만 바뀐다. 취소된 입력에는 놓기나 인사 반응을 만들지 않는다. */
internal class TownResidentTouch {
    var pointer = -1
        private set
    private var resident: TownPopulation.Resident? = null
    private var downX = 0f
    private var downY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    val isHeld get() = resident?.held == true

    fun begin(pointerId: Int, target: TownPopulation.Resident, x: Float, y: Float) {
        if (resident != null) return
        pointer = pointerId
        resident = target
        downX = x
        downY = y
        offsetX = target.point.x - x
        offsetY = target.point.y - y
        target.pressed = true
        target.reaction = 0f
        target.drop = 0f
    }

    fun hold() {
        resident?.held = true
    }

    fun moved(x: Float, y: Float, slop: Float) = hypot(x - downX, y - downY) > slop

    fun drag(x: Float, y: Float, move: (TownPopulation.Resident, Float, Float) -> Unit) {
        resident?.takeIf { it.held }?.let { move(it, x + offsetX, y + offsetY) }
    }

    fun release(pointerId: Int) {
        val target = resident ?: return
        if (pointerId != pointer) return
        val wasHeld = target.held
        cancel()
        if (wasHeld) target.drop = .24f else target.reaction = .8f
    }

    fun cancel() {
        resident?.pressed = false
        resident?.held = false
        resident = null
        pointer = -1
    }
}
