package io.drivegrowth.app.host

import android.app.Activity

/** release에는 시연 구현·런처·입력 생성 경로가 없다. */
internal object HostSimulationProvider {
    fun create(activity: Activity): HostSimulation? = null
}
