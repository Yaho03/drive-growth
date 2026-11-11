package io.drivegrowth.app.host

import android.app.Activity
import io.drivegrowth.app.simulation.UnifiedSimulation
import io.drivegrowth.app.simulation.UnifiedSimulationActivity

internal object HostSimulationProvider {
    fun create(activity: Activity): HostSimulation? =
        if (activity is UnifiedSimulationActivity) UnifiedSimulation(activity.applicationContext) else null
}
