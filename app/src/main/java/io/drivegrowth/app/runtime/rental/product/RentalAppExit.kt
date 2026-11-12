package io.drivegrowth.app.runtime.rental.product

/** 사용자 이탈 힌트는 같은 전경 주기의 가시 종료가 확인될 때 한 번만 소비한다. */
internal class RentalAppExit {
    private var leaving = false

    fun userLeaveHint() { leaving = true }
    fun clear() { leaving = false }

    fun consumeStop(isChangingConfigurations: Boolean): Boolean {
        val confirmed = leaving && !isChangingConfigurations
        clear()
        return confirmed
    }
}
