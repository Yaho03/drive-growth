package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.data.RentalInactiveRecovery
import io.drivegrowth.app.data.RentalInactivePhase
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.core.contract.*

/** 같은 serial worker의 복구 소비자다. 새 marker/원점/고객을 생성하거나 다른 명령을 취소하지 않는다. */
class RentalInactiveRecoveryConsumer(
    private val read: (GameIdentity) -> ContractResult<RentalInactiveRecovery?>,
    private val advance: (GameIdentity, RentalInactiveInterval) -> ContractResult<RentalRuntimeSnapshot>,
    private val clock: () -> ContractResult<RentalClock>,
) {
    private var waiting: Pair<RentalInactiveRecovery, RentalInactiveInterval>? = null

    fun resume(identity: GameIdentity): ContractResult<RentalRuntimeSnapshot?> =
        resumeWith(identity) { original, interval -> advance(original.identity, interval) }

    internal fun resumeWith(identity: GameIdentity, forwardWallAt: RentalClock? = null,
        advanceOriginal: (RentalInactiveRecovery, RentalInactiveInterval) -> ContractResult<RentalRuntimeSnapshot>,
    ): ContractResult<RentalRuntimeSnapshot?> {
        val old = waiting
        if (old != null && old.first.identity != identity) return failure(ContractError.STATE_CONFLICT)
        val current = when (val result = read(identity)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
                ?: return if (old == null) ContractResult.Success(null) else failure(ContractError.STORAGE_FAILURE)
        }
        if (current.identity != identity || old != null && !sameBoundary(old.first, current))
            return failure(ContractError.STATE_CONFLICT)
        val command = when (current.phase) {
            RentalInactivePhase.RECORDED -> old?.second ?: run {
                val now = forwardWallAt ?: when (val result = clock()) {
                    is ContractResult.Failure -> return result
                    is ContractResult.Success -> result.value
                }
                val forward = forwardWallAt != null
                if (forward) {
                    if (!current.verifiedExit || !now.isForwardWallJumpFrom(current.since))
                        return failure(ContractError.DATA_UNAVAILABLE)
                } else if (!continuous(current.since, now)) return failure(ContractError.DATA_UNAVAILABLE)
                RentalInactiveInterval(current.marker, current.since, current.since, now, forward)
            }
            RentalInactivePhase.PREPARED, RentalInactivePhase.COMPLETED ->
                current.interval ?: return failure(ContractError.STORAGE_FAILURE)
        }
        if (command.marker != current.marker || command.originalSince != current.since || command.from != current.since)
            return failure(ContractError.STORAGE_FAILURE)
        if (old != null && command != old.second) return failure(ContractError.STATE_CONFLICT)
        if (current.phase == RentalInactivePhase.COMPLETED) {
            waiting = null
            return ContractResult.Success(null)
        }
        waiting = current to command // 준비 쓰기 실패도 같은 최초 resume 입력을 유지한다.
        return when (val result = advanceOriginal(current, command)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> { waiting = null; result }
        }
    }

    internal fun discardUnverifiedForward(command: RentalInactiveInterval) {
        if (command.forwardWallJump && waiting?.second == command) waiting = null
    }

    private fun sameBoundary(a: RentalInactiveRecovery, b: RentalInactiveRecovery) =
        a.identity == b.identity && a.marker == b.marker && a.since == b.since &&
            a.basis == b.basis && a.policyOrigin == b.policyOrigin && a.observedThrough == b.observedThrough &&
            a.verifiedExit == b.verifiedExit && a.rebasedClock == b.rebasedClock

    private fun continuous(a: RentalClock, b: RentalClock) = a.bootId.isNotBlank() && a.bootId == b.bootId &&
        a.epoch.value >= 0 && a.elapsedMs >= 0 && b.epoch.value >= a.epoch.value && b.elapsedMs >= a.elapsedMs &&
        b.epoch.value - a.epoch.value == b.elapsedMs - a.elapsedMs

    private fun failure(error: ContractError) = ContractResult.Failure(error)
}
