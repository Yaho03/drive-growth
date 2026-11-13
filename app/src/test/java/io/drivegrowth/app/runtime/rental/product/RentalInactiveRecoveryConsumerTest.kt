package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

/** 복구 순서와 원문 보존 시험이다. 이 콜백 fixture는 실제 SQLite/SDK 실행이 아니다. */
class RentalInactiveRecoveryConsumerTest {
    private val actor = GameIdentity(ProfileId("fixture-profile"), InputSource.SIMULATION)
    private val since = RentalClock("fixture-boot", EpochMillis(1000), 10)
    private val to = since.copy(epoch = EpochMillis(1100), elapsedMs = 110)
    private val original = RentalInactiveRecovery(actor, RequestId("original-stop"), since,
        RentalInactiveBasis.APPLICATION_EXIT_DRIVING, ContractResult.Success(Observation.Available(since)),
        RentalInactivePhase.RECORDED, null)
    private val interval = RentalInactiveInterval(original.marker, since, since, to)
    private val snapshot = RentalRuntimeSnapshot(emptyList(), null)
    private inner class Fixture {
        var stored: ContractResult<RentalInactiveRecovery?> = ContractResult.Success(original)
        var now = to
        var reads = 0
        val writes = mutableListOf<RentalInactiveInterval>()
        var result: ContractResult<RentalRuntimeSnapshot> = ContractResult.Failure(ContractError.STATE_CONFLICT)
        val consumer = RentalInactiveRecoveryConsumer({ stored }, { id, command ->
            assertEquals(actor, id); writes += command; result
        }, { reads++; ContractResult.Success(now) })
    }
    @Test fun recordedBoundaryUsesFirstActualResumeAndRetainsItAfterFailedPreparation() {
        val f = Fixture()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), f.consumer.resume(actor))
        f.now = to.copy(epoch = EpochMillis(1500), elapsedMs = 510)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), f.consumer.resume(actor))
        assertEquals(listOf(interval, interval), f.writes)
        assertEquals(1, f.reads)
    }
    @Test fun preparedWindowIsRecoveredBeforeCurrentClockAndDefinition() {
        val prepared = original.copy(phase = RentalInactivePhase.PREPARED, interval = interval)
        val calls = mutableListOf<RentalInactiveInterval>()
        val consumer = RentalInactiveRecoveryConsumer({ ContractResult.Success(prepared) }, { _, command ->
            calls += command; ContractResult.Success(snapshot)
        }, { error("원래 window 복구에서 현재clock 호출 금지") })
        assertEquals(ContractResult.Success(snapshot), consumer.resume(actor))
        assertEquals(listOf(interval), calls)
    }
    @Test fun completedAndTrueAbsenceDoNotCreateClockOrAdvance() {
        val f = Fixture()
        f.stored = ContractResult.Success(original.copy(phase = RentalInactivePhase.COMPLETED, interval = interval))
        assertEquals(ContractResult.Success<RentalRuntimeSnapshot?>(null), f.consumer.resume(actor))
        f.stored = ContractResult.Success(null)
        assertEquals(ContractResult.Success<RentalRuntimeSnapshot?>(null), f.consumer.resume(actor))
        assertEquals(0, f.reads); assertTrue(f.writes.isEmpty())
    }
    @Test fun readerFailureIsNotAbsenceAndDoesNotCallClock() {
        val f = Fixture()
        f.stored = ContractResult.Failure(ContractError.STORAGE_FAILURE)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), f.consumer.resume(actor))
        assertEquals(0, f.reads); assertTrue(f.writes.isEmpty())
    }
    @Test fun legacyMissingAndStoredAuthorityFailureDoNotBecomeNewOrigins() {
        for (origin in listOf(ContractResult.Success(Observation.Missing(MissingReason.NO_DATA)),
            ContractResult.Failure(ContractError.PERMISSION_DENIED))) {
            val f = Fixture()
            val old = original.copy(basis = RentalInactiveBasis.DRIVING_END, policyOrigin = origin)
            f.stored = ContractResult.Success(old); f.result = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), f.consumer.resume(actor))
            assertEquals(listOf(interval), f.writes)
            assertEquals(ContractResult.Success(old), f.stored)
        }
    }
    @Test fun foreignIdentityNeverSubmitsAWindow() {
        val f = Fixture()
        f.stored = ContractResult.Success(original.copy(identity = actor.copy(profileId = ProfileId("foreign"))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), f.consumer.resume(actor))
        assertTrue(f.writes.isEmpty()); assertEquals(0, f.reads)
    }
    @Test fun bootClockReversalAndSkewDoNotPrepareFinancialInput() {
        for (now in listOf(to.copy(bootId = "other"), since.copy(elapsedMs = 9),
            to.copy(epoch = EpochMillis(1101)))) {
            val f = Fixture(); f.now = now
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), f.consumer.resume(actor))
            assertTrue(f.writes.isEmpty())
        }
    }
    @Test fun forwardWallJumpOnlyUsesVerifiedExitAndReplaysItsFirstCommand() {
        val jumped = to.copy(epoch = EpochMillis(to.epoch.value + 9 * 3_600_000))
        val command = interval.copy(to = jumped, forwardWallJump = true)
        val f = Fixture()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            f.consumer.resumeWith(actor, jumped) { _, _ -> error("Unverified exit") })
        f.stored = ContractResult.Success(original.copy(verifiedExit = true))
        for (invalid in listOf(jumped.copy(bootId = "other"), jumped.copy(elapsedMs = 9), to)) {
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
                f.consumer.resumeWith(actor, invalid) { _, _ -> error("Invalid clock") })
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
            f.consumer.resumeWith(actor, jumped) { _, actual ->
                assertEquals(command, actual)
                ContractResult.Failure(ContractError.STORAGE_FAILURE)
            })
        f.stored = ContractResult.Success(original.copy(verifiedExit = true,
            phase = RentalInactivePhase.PREPARED, interval = command))
        assertEquals(ContractResult.Success(snapshot), f.consumer.resumeWith(actor,
            jumped.copy(epoch = EpochMillis(jumped.epoch.value + 1_000), elapsedMs = jumped.elapsedMs + 1_000)) { _, actual ->
            assertEquals(command, actual)
            ContractResult.Success(snapshot)
        })
        assertEquals(0, f.reads)
    }
    @Test fun rawRejectedCandidateCanBeReplacedByCorrectedClockWhileStillRecorded() {
        val f = Fixture()
        f.stored = ContractResult.Success(original.copy(verifiedExit = true))
        val unverified = to.copy(epoch = EpochMillis(to.epoch.value + 9 * 3_600_000L))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            f.consumer.resumeWith(actor, unverified) { _, command ->
                f.consumer.discardUnverifiedForward(command)
                ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            })
        f.result = ContractResult.Success(snapshot)
        assertEquals(ContractResult.Success(snapshot), f.consumer.resume(actor))
        assertEquals(listOf(interval), f.writes)
    }
    @Test fun visitScheduleKeepsSequenceAndSkipsPastSlotsInBothClockDomains() {
        val minute = 60_000L
        val cursor = RentalVisitCursor(Revision(7), 3, since.copy(
            epoch = EpochMillis(since.epoch.value + 30 * minute), elapsedMs = 30 * minute))
        for (offset in listOf(0L, 9 * 3_600_000L)) {
            val at = cursor.nextAt.copy(epoch = EpochMillis(since.epoch.value + 35 * minute + offset),
                elapsedMs = 35 * minute)
            val updated = cursor.rescheduleAfter(at, 30 * minute)
            assertEquals(Revision(8), updated.revision)
            assertEquals(cursor.sequence, updated.sequence)
            assertEquals(at.copy(epoch = EpochMillis(since.epoch.value + 60 * minute + offset),
                elapsedMs = 60 * minute), updated.nextAt)
        }
    }
    @Test fun changedPreparedInputAndLostKnownWindowAreNotNewResumes() {
        val f = Fixture(); f.consumer.resume(actor)
        f.stored = ContractResult.Success(original.copy(phase = RentalInactivePhase.PREPARED,
            interval = interval.copy(to = to.copy(epoch = EpochMillis(1200), elapsedMs = 210))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), f.consumer.resume(actor))
        assertEquals(listOf(interval), f.writes)
        f.stored = ContractResult.Success(null)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), f.consumer.resume(actor))
        assertEquals(listOf(interval), f.writes)
    }
}
