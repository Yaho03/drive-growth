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
