package io.drivegrowth.app

internal class BackObservationBoundary {
    private var waitingForTopResumedLoss = false

    fun sentToBack() {
        waitingForTopResumedLoss = true
    }

    fun resumed() {
        waitingForTopResumedLoss = false
    }

    fun topResumedChanged(isTopResumed: Boolean): Boolean {
        if (!isTopResumed) waitingForTopResumedLoss = false
        return isTopResumed && !waitingForTopResumedLoss
    }
}
