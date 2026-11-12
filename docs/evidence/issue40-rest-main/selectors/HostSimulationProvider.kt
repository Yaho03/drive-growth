package io.drivegrowth.app.host

import android.app.Activity
import io.drivegrowth.app.simulation.UnifiedSimulation
import io.drivegrowth.app.simulation.UnifiedSimulationActivity

internal object HostSimulationProvider {
    // Isolated instrumentation fixture. The regular Debug entry keeps the existing adapter.
    @Volatile internal var instrumentationFactory: ((UnifiedSimulation) -> HostSimulation)? = null

    fun create(activity: Activity): HostSimulation? =
        if (activity is UnifiedSimulationActivity) UnifiedSimulation(activity.applicationContext).let {
            instrumentationFactory?.invoke(it) ?: it
        } else null
}
