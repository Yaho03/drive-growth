package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityTransition
import io.drivegrowth.core.contract.*

/** 소유 어댑터의 main queue 안에서만 접근한다. 알려진 ID는 실패 뒤에도 release 전까지 보존한다. */
internal class PleosAreaSubscription(
    val definition: PleosAreaDefinition,
    val deliver: (PleosAreaObservation) -> Unit,
) {
    val registeredIds = linkedSetOf<String>()
    private var activeId: String? = null
    private var failed = false

    fun missing(reason: MissingReason, receipt: Observation<PleosAreaReceipt>) =
        PleosAreaObservation(definition, Observation.Missing(reason), Observation.Missing(reason), receipt)

    fun fail(reason: MissingReason, receipt: Observation<PleosAreaReceipt>): PleosAreaObservation {
        failed = true
        return missing(reason, receipt)
    }

    fun registered(id: String, permitted: Boolean, receipt: Observation<PleosAreaReceipt>): PleosAreaObservation? {
        if (id.isNotBlank()) registeredIds += id
        if (failed) return null
        if (!permitted) return fail(MissingReason.PERMISSION_DENIED, receipt)
        if (id.isBlank() || activeId?.let { it != id } == true) return fail(MissingReason.NO_DATA, receipt)
        if (activeId == id) return null
        activeId = id
        return PleosAreaObservation(definition, Observation.Available(id), Observation.Missing(MissingReason.NO_DATA), receipt)
    }

    fun transition(event: ProximityAlertEvent, permitted: Boolean, receipt: Observation<PleosAreaReceipt>): PleosAreaObservation? {
        if (failed || activeId == null || event.alertId != activeId) return null
        if (!permitted) return fail(MissingReason.PERMISSION_DENIED, receipt)
        val transition = when (event.transition) {
            ProximityTransition.ENTER -> PleosAreaTransition.ENTER
            ProximityTransition.EXIT -> PleosAreaTransition.EXIT
        }
        return PleosAreaObservation(definition, Observation.Available(event.alertId), Observation.Available(transition), receipt)
    }
}
