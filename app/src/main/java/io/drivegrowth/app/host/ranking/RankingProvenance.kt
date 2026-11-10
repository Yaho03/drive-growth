package io.drivegrowth.app.host.ranking

import io.drivegrowth.app.host.driving.ObservedDrivingReceipt
import io.drivegrowth.core.contract.*
import java.time.ZoneId

/** Observation hook의 명시 조회 오류를 지표 Failure로 전달한다. 누락 신호와 구분한다. */
internal class RankingProvenanceFailure(val error: ContractError) : RuntimeException()

/** 같은 store의 공개 검증 조회만 공급한다. 호출은 RankingReadSource의 읽기 transaction 안이다. */
internal class RankingProvenance(private val source: InputSource,
    private val profile: () -> ContractResult<ProfileId>,
    private val claims: (ProfileId) -> ContractResult<Set<RequestId>>,
    private val recordedDrive: (String) -> ContractResult<ObservedDrivingReceipt?>) {
    fun questCauses(): ContractResult<Set<RequestId>> = when (val actual = profile()) {
        is ContractResult.Failure -> actual
        is ContractResult.Success -> claims(actual.value)
    }

    fun departureZone(driveId: String): Observation<ZoneId> {
        when (val actual = profile()) {
            is ContractResult.Failure -> throw RankingProvenanceFailure(actual.error)
            is ContractResult.Success -> Unit
        }
        // 공개 E 조회가 같은 실제 profile/source와 원출발·원정산 연결을 검증한다.
        val receipt = when (val read = recordedDrive(driveId)) {
            is ContractResult.Failure -> {
                // 관측 journal 미활성인 legacy는 출발 지역 근거가 없다. 손상은 E가 STORAGE_FAILURE로 구분한다.
                if (read.error == ContractError.DATA_UNAVAILABLE) return Observation.Missing(MissingReason.NO_DATA)
                throw RankingProvenanceFailure(read.error)
            }
            is ContractResult.Success -> read.value ?: return Observation.Missing(MissingReason.NO_DATA)
        }
        if (receipt.trip.id != driveId || receipt.commit.driveId != driveId || receipt.trip.source != source ||
            receipt.commit.settlement?.transaction?.source?.let { it != source } == true)
            throw RankingProvenanceFailure(ContractError.STORAGE_FAILURE)
        return receipt.trip.zone
    }
}
