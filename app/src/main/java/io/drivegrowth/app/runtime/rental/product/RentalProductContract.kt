package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalVisit
import io.drivegrowth.core.contract.*

/** 원래 보정 쌍/부팅에서 다음 방문을 예약한다. UI 진입·P 단일 표본으로 과거 시각을 만들지 않는다. */
data class RentalVisitCursor(val revision: Revision, val sequence: Long, val nextAt: RentalClock)

/** 누락된 슬롯에서 요청을 만들지 않고 같은 elapsed 일정의 다음 미래 슬롯으로 옮긴다. */
internal fun RentalVisitCursor.rescheduleAfter(observed: RentalClock, intervalMs: Long): RentalVisitCursor {
    val ahead = if (nextAt.elapsedMs > observed.elapsedMs) nextAt.elapsedMs - observed.elapsedMs
        else intervalMs - (observed.elapsedMs - nextAt.elapsedMs) % intervalMs
    return copy(revision = Revision(revision.value + 1), nextAt = observed.copy(
        epoch = EpochMillis(Math.addExact(observed.epoch.value, ahead)),
        elapsedMs = Math.addExact(observed.elapsedMs, ahead)))
}

/** E가 금융 제출 전 durable 저장할 전체 입력. 목록·요금은 RentalVisit가 방어 복사한다. */
data class PreparedRentalVisit(val identity: GameIdentity, val definitionVersion: ContentVersion, val trial: Boolean,
    val expected: RentalVisitCursor, val next: RentalVisitCursor, val visit: RentalVisit, val city: RentalCity? = null) {
    override fun equals(other: Any?): Boolean = other is PreparedRentalVisit && identity == other.identity &&
        definitionVersion == other.definitionVersion && trial == other.trial && expected == other.expected && next == other.next && city == other.city &&
        visit.source == other.visit.source && visit.request == other.visit.request && visit.catalog == other.visit.catalog &&
        visit.categories == other.visit.categories && visit.hourlyRates == other.visit.hourlyRates && visit.terms == other.visit.terms
    override fun hashCode(): Int = listOf(identity, definitionVersion, trial, expected, next, visit.source, visit.request,
        visit.catalog, visit.categories, visit.hourlyRates, visit.terms, city).hashCode()
}

/** 명시 앱 비활성 마커. 실제 차량 주차·주행 증거로 변환하지 않는다. */
data class RentalInactiveInterval(val marker: RequestId, val originalSince: RentalClock,
    val from: RentalClock, val to: RentalClock, val forwardWallJump: Boolean = false)

/** 종료 영업만 사용하는 양의 UTC 오프셋 변화. 원 부팅·elapsed의 순서는 별도로 검사한다. */
internal fun RentalClock.isForwardWallJumpFrom(since: RentalClock, maxSkewMs: Long = 0): Boolean =
    bootId.isNotBlank() && bootId == since.bootId && since.elapsedMs >= 0 && since.epoch.value >= 0 &&
        elapsedMs >= since.elapsedMs && epoch.value >= since.epoch.value &&
        epoch.value - since.epoch.value - (elapsedMs - since.elapsedMs) > maxSkewMs

/** 공통 저장 공급 포트. 구현은 E가 같은 실제 factory/profile/source/원자 경계에 연결한다. */
interface RentalProductJournal {
    fun pending(identity: GameIdentity): ContractResult<PreparedRentalVisit?>
    fun cursor(identity: GameIdentity): ContractResult<RentalVisitCursor>
    fun save(input: PreparedRentalVisit): ContractResult<PreparedRentalVisit>
    /** 기존 RentalLedger.assign과 방문 결과·다음 cursor를 함께 확정한다. null도 확정된 재방문 결과다. */
    fun execute(input: PreparedRentalVisit): ContractResult<OwnedVehicleId?>
    fun finish(input: PreparedRentalVisit): ContractResult<Unit>
    /** 실제 onStop 경계를 원자 보존한다. 미확정/없는 마커를 마지막 heartbeat로 복원하지 않는다. */
    fun beginInactive(identity: GameIdentity, marker: RequestId, since: RentalClock): ContractResult<Unit>
    /** 앱 비활성 정책으로만 계산한다. E가 겹침/cursor/30분·8h를 검사하며 Parked로 합성하지 않는다. */
    fun advanceInactive(identity: GameIdentity, interval: RentalInactiveInterval): ContractResult<io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot>
}
