package io.drivegrowth.app.vehicle.location

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.RestPlace

/** 원형 경계와 권위 metadata는 검증된 외부 공급자의 입력이다. 이 타입은 형식만 검사한다. */
data class PleosAreaDefinition(
    val areaId: String, val kind: RestPlace, val dataset: ContentVersion,
    val sourceUrl: String, val licence: String, val updatedOn: String,
    val latitude: Double, val longitude: Double, val radiusMeters: Float,
) {
    init {
        require(areaId.isNotBlank() && sourceUrl.isNotBlank() && licence.isNotBlank() && updatedOn.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusMeters.isFinite() && radiusMeters > 0)
    }
}

enum class PleosAreaTransition { ENTER, EXIT }
data class PleosAreaReceipt(val receivedElapsedMs: Long, val receivedAt: EpochMillis)

/** callback 수신 사실이다. ENTER/EXIT를 현재 inside 또는 시각이 확인된 RestAreaProof로 승격하지 않는다. */
data class PleosAreaObservation(
    val definition: PleosAreaDefinition,
    val alertId: Observation<String>, val transition: Observation<PleosAreaTransition>,
    val receipt: Observation<PleosAreaReceipt>,
) {
    val source: InputSource get() = InputSource.VEHICLE
    val initialInside: Observation<Boolean> get() = Observation.Missing(MissingReason.NO_DATA)
    val occurredAt: Observation<EpochMillis> get() = Observation.Missing(MissingReason.NO_DATA)
    val occurredElapsedMs: Observation<Long> get() = Observation.Missing(MissingReason.NO_DATA)
}
