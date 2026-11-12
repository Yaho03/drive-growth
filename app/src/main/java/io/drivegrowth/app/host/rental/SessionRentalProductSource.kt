package io.drivegrowth.app.host.rental

import android.util.Log
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.rest.RestEvidenceValidator
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/** 직렬 worker 전용. 실제 원관측 범위만 기존 원장에 전달하며 새 지갑/가격/과거 고객을 만들지 않는다. */
class SessionRentalProductSource(private val games: GameSessionFactory,
    private val definitions: () -> ContractResult<TrialDefinition>, private val policy: RecordingPolicy,
    private val rawClock: () -> ContractResult<RentalClock>, private val random: () -> Random = { Random.Default },
    private val nextRequest: () -> RequestId = { RequestId("rental-visit-" + UUID.randomUUID()) }) {
    private val calibration = RentalClockCalibration(policy.maxUtcSkewMs)
    private var bound: GameIdentity? = null
    private var connected = false
    private var resumeAt: RentalClock? = null
    private var needsResume = true
    private var inactiveRecorded = false
    private data class AcceptedFrame(val at: RentalClock, val motion: Observation<RentalMotion>,
        val receiver: String?, val revision: Long, val unobservedSince: RentalClock?)
    private var previous: AcceptedFrame? = null
    private var parkedBeforeJump: AcceptedFrame? = null
    private var parkedJumpEnd: RentalClock? = null
    private var rebasedClock = false
    private var latest: VehicleObservation? = null
    private var tripOpen = false
    private var canConfirmEnd = false
    private var parkCandidate: RentalClock? = null
    private var physicalEnd: RentalClock? = null
    private var lastConfirmedEnd: RentalClock? = null
    private var observedThrough: RentalClock? = null
    private var eligible: RentalClock? = null
    private var visitFloor: RentalClock? = null
    private var giftAttempt: RequestId? = null
    private val spans = ArrayDeque<RentalTick>()
    private val gift = StoredOnboardingHostSource(games)
    var issue: ContractError? = null
        private set(value) {
            if (field != value && value != null) Log.w("DriveGrowthRental", "input_schedule_deferred code=$value")
            field = value
        }

    private fun clock() = when (val raw = rawClock()) {
        is ContractResult.Failure -> raw
        is ContractResult.Success -> calibration.project(raw.value)
    }
    private val journal = SqliteRentalProductJournal(games, {
        when (val loaded = definitions()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> loaded.value.rentalVisitDefinition()
        }
    }, ::clock, { id ->
        if (bound != id) failure(ContractError.STATE_CONFLICT)
        else ContractResult.Success(physicalEnd?.let { Observation.Available(it) } ?: Observation.Missing(MissingReason.NO_DATA))
    }, rawClock, policy.maxUtcSkewMs)
    private val observed = object : RentalRuntimeSource {
        override fun visit(input: ContractResult<RentalVisit>): ContractResult<OwnedVehicleId?> = failure(ContractError.STATE_CONFLICT)
        override fun read() = withRuntime(false) { it.read() }
        override fun advance(tick: RentalTick) = withRuntime(true) { it.advance(tick) }
    }
    private val runtime = PreparedRentalRuntimeSource(::identity, journal, observed)
    private val preparation = RentalVisitPreparation(::identity, journal, RentalProductInputs { id ->
        val through = eligible ?: return@RentalProductInputs failure(ContractError.DATA_UNAVAILABLE)
        val definition = when (val r = definitions()) { is ContractResult.Failure -> return@RentalProductInputs r; is ContractResult.Success -> r.value }
        val planned = when (val r = definition.rentalVisitDefinition()) { is ContractResult.Failure -> return@RentalProductInputs r; is ContractResult.Success -> r.value }
        when (val opened = games.open()) {
            is ContractResult.Failure -> opened
            is ContractResult.Success -> opened.value.use { game ->
                if (game.identity != id) failure(ContractError.STATE_CONFLICT)
                else when (val content = game.content) {
                    is ContractResult.Failure -> content
                    is ContractResult.Success -> ContractResult.Success(RentalPreparationInputs(content.value.catalog, planned, through))
                }
            }
        }
    }, random, nextRequest)
    private val recovery = RentalInactiveRecoveryConsumer(journal::currentInactive, ::advanceInactive) {
        resumeAt?.let { ContractResult.Success(it) } ?: failure(ContractError.DATA_UNAVAILABLE)
    }

    private fun advanceInactive(id: GameIdentity, interval: RentalInactiveInterval): ContractResult<RentalRuntimeSnapshot> =
        advanceInactive(id, interval, interval.forwardWallJump)

    private fun advanceInactive(id: GameIdentity, interval: RentalInactiveInterval,
        drainAtPreviousClock: Boolean, forwardScheduleAt: RentalClock? = null): ContractResult<RentalRuntimeSnapshot> =
        journal.advanceInactiveAfterDrain(id, interval,
            onUnverifiedForward = { recovery.discardUnverifiedForward(interval) }, forwardScheduleAt = forwardScheduleAt) {
            drainThrough(interval.originalSince, forwardWallJump = drainAtPreviousClock)
        }

    private fun identity(): ContractResult<GameIdentity> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use { game ->
            if (bound != null && bound != game.identity) failure(ContractError.STATE_CONFLICT)
            else when (val initialized = game.store.readInitialization(game.identity.profileId)) {
                is ContractResult.Failure -> initialized
                is ContractResult.Success -> if (initialized.value.status is ProfileInitializationStatus.Existing)
                    ContractResult.Success(game.identity) else failure(ContractError.DATA_UNAVAILABLE)
            }
        }
    }
    private fun <T> withRuntime(timed: Boolean, acceptedAt: RentalClock? = null,
        action: (RentalRuntimeSource) -> ContractResult<T>): ContractResult<T> {
        val now = acceptedAt ?: if (timed) when (val r = clock()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value } else null
        val game = when (val r = games.open()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (game.identity != bound) { game.close(); return failure(ContractError.STATE_CONFLICT) }
        // 기존 runtime이 반환받은 store를 use로 닫는다. 같은 factory의 실제 store만 한 번 전달한다.
        return action(SqliteRentalRuntimeSource(game.identity.source, { game.store }, { checkNotNull(now) }))
    }
    private fun free(): ContractResult<Unit> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use { game ->
            if (game.identity != bound) failure(ContractError.STATE_CONFLICT)
            else when (val saved = game.store.hostCommands.load(game.identity.profileId)) {
                is ContractResult.Failure -> saved
                is ContractResult.Success -> if (saved.value == null) ContractResult.Success(Unit) else failure(ContractError.STATE_CONFLICT)
            }
        }
    }
    private fun recoverVisit(acceptedThrough: RentalClock? = null): ContractResult<Unit> {
        val id = bound ?: return failure(ContractError.DATA_UNAVAILABLE)
        val original = if (acceptedThrough == null) preparation.recover(id)
            else preparation.recover(id) { journal.saveRetainedVisit(it, acceptedThrough) }
        val pending = when (val r = original) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (pending != null) when (val r = runtime.visit(ContractResult.Success(pending.visit))) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
        return ContractResult.Success(Unit)
    }
    private fun connect(canActivate: Boolean, replayIdentity: GameIdentity? = null): ContractResult<Unit> {
        if (connected) return ContractResult.Success(Unit)
        val id = when (val r = identity()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (replayIdentity != null && replayIdentity != id) return failure(ContractError.STATE_CONFLICT)
        val prior = when (val r = journal.currentInactive(id)) {
            is ContractResult.Failure -> if (r.error == ContractError.DATA_UNAVAILABLE && canActivate) null else return r
            is ContractResult.Success -> r.value
        }
        bound = id
        val cursor = when (val r = journal.activate(id)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        calibration.restore(cursor.nextAt)
        val completed = prior?.interval?.takeIf { prior.phase == RentalInactivePhase.COMPLETED && prior.verifiedExit }
        rebasedClock = prior?.rebasedClock == true || completed?.let {
            cursor.nextAt.isForwardWallJumpFrom(it.to, policy.maxUtcSkewMs) ||
                (it.forwardWallJump && calibration.project(it.to) is ContractResult.Success)
        } == true
        if (prior?.basis == RentalInactiveBasis.DRIVING_END) {
            physicalEnd = ((prior.policyOrigin as? ContractResult.Success)?.value as? Observation.Available)?.value
            lastConfirmedEnd = physicalEnd
        }
        connected = true
        return ContractResult.Success(Unit)
    }

    fun reconnect() { needsResume = true; inactiveRecorded = false; resumeAt = null; previous = null; parkedBeforeJump = null; parkedJumpEnd = null; parkCandidate = null; canConfirmEnd = false; giftAttempt = null }
    fun pauseObservations() { previous = null; parkedBeforeJump = null; parkedJumpEnd = null; parkCandidate = null; canConfirmEnd = false }

    fun observe(frame: DrivingFrame, recorded: ContractResult<DrivingRecordingResult>): ContractResult<RentalRuntimeSnapshot> =
        observe(frame, recorded, null, false)

    internal fun observeOrdinarySimulationReplay(
        frame: DrivingFrame,
        recorded: ContractResult<DrivingRecordingResult>,
        originalClock: RestClockDomain,
        ordinaryTerminal: Boolean = false,
    ): ContractResult<RentalRuntimeSnapshot> = observe(frame, recorded, originalClock, ordinaryTerminal)

    private fun observe(
        frame: DrivingFrame,
        recorded: ContractResult<DrivingRecordingResult>,
        originalClock: RestClockDomain?,
        ordinaryTerminal: Boolean,
    ): ContractResult<RentalRuntimeSnapshot> {
        val state = when (recorded) {
            is ContractResult.Failure -> { previous = null; latest = null; parkedBeforeJump = null; parkedJumpEnd = null; return recorded }
            is ContractResult.Success -> recorded.value.state
        }
        val input = frame.observation
        if (state.anchor != input || state.identity.source != input.source) {
            // 떨어진 표본을 새 anchor로 쓰지 않는다. 전경 주차 원점만 다음 실제 P까지 한 번 보존한다.
            parkedJumpEnd = null
            parkedBeforeJump = previous?.takeIf { before ->
                originalClock == null && !needsResume && state.identity == bound && state.trip == null &&
                    state.receiver == before.receiver && state.revision - before.revision == 1L &&
                    state.boundary?.elapsed == before.at.elapsedMs && state.boundary?.utc == latest?.receivedAt &&
                    before.motion is Observation.Available && before.motion.value is RentalMotion.Parked &&
                    stationaryPark(input) && input.receivedElapsedMs - before.at.elapsedMs in 1..policy.maxGapMs &&
                    RentalClock(before.at.bootId, input.receivedAt, input.receivedElapsedMs)
                        .isForwardWallJumpFrom(before.at, policy.maxUtcSkewMs)
            }
            previous = null; latest = null
            return failure(ContractError.DATA_UNAVAILABLE)
        }
        if (originalClock != null && input.source != InputSource.SIMULATION) return failure(ContractError.DATA_UNAVAILABLE)
        val moving = (input.gear as? Observation.Available)?.value in setOf(Gear.DRIVE, Gear.REVERSE) &&
            ((input.speedMps as? Observation.Available)?.value?.let { it.isFinite() && abs(it) > policy.movingMps } == true)
        val replayIdentity = if (originalClock == null) null else state.identity
        when (val r = connect(moving || physicalEnd != null, replayIdentity)) {
            is ContractResult.Failure -> return r
            is ContractResult.Success -> Unit
        }
        if (state.identity != bound) return failure(ContractError.STATE_CONFLICT)
        val rawNow = when (val r = rawClock()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val now = when (val r = calibration.project(rawNow)) {
            is ContractResult.Success -> r.value
            is ContractResult.Failure -> {
                if (originalClock != null) return r
                when (val recovered = recoverForwardClock(state, input, rawNow) ?: return r) {
                    is ContractResult.Failure -> return recovered
                    is ContractResult.Success -> when (val current = calibration.project(rawNow)) {
                        is ContractResult.Failure -> return current
                        is ContractResult.Success -> current.value
                    }
                }
            }
        }
        parkedBeforeJump = null
        parkedJumpEnd = null
        if (input.receivedElapsedMs > now.elapsedMs || now.elapsedMs - input.receivedElapsedMs > policy.maxAgeMs)
            return failure(ContractError.DATA_UNAVAILABLE)
        val at = when (val r = calibration.project(RentalClock(now.bootId, input.receivedAt, input.receivedElapsedMs))) {
            is ContractResult.Failure -> return r
            is ContractResult.Success -> r.value
        }
        if (originalClock != null) {
            val boundary = if (needsResume) when (val r = journal.currentInactive(state.identity)) {
                is ContractResult.Failure -> return r
                is ContractResult.Success -> r.value
            } else null
            if (boundary != null && boundary.phase != RentalInactivePhase.COMPLETED) {
                if (resumeAt == null) resumeAt = at
                when (val r = recovery.resumeWith(state.identity) { original, interval ->
                    journal.advanceInactiveAfterDrain(original.identity, interval) {
                        replayBeforeInactiveSettlement(state, frame, originalClock, ordinaryTerminal, original)
                    }.also { if (it is ContractResult.Success) resumeAt = interval.to }
                }) {
                    is ContractResult.Failure -> return r
                    is ContractResult.Success -> r.value?.let(::completed)
                }
                needsResume = false
            } else when (val r = recoverVisit()) {
                is ContractResult.Failure -> return r
                is ContractResult.Success -> Unit
            }
        }
        // 저장된 복귀 시각보다 앞선 시연 표본은 새 라이브 관측이나 방문으로 사용하지 않는다.
        if (!needsResume && resumeAt?.let { at.elapsedMs < it.elapsedMs } == true) return runtime.read()
        val replay = if (originalClock == null) null else when (val r = simulationReplayTick(state, frame, originalClock, ordinaryTerminal)) {
            is ContractResult.Failure -> return r
            is ContractResult.Success -> r.value
        }
        val before = previous
        // 같은 원표본의 금융/도착 재시도는 motion 원점과 이미 보존한 span을 다시 만들지 않는다.
        if (before?.at != at || latest != input) {
            val delta = before?.takeIf { it.receiver != null && it.receiver == state.receiver && it.at.bootId == at.bootId }
                ?.let { at.elapsedMs - it.at.elapsedMs }
            val continuous = delta != null && delta in 1..policy.maxGapMs
            if (!continuous) { parkCandidate = null; canConfirmEnd = false }
            if (moving) { canConfirmEnd = true; parkCandidate = null; physicalEnd = null }
            if (canConfirmEnd && state.parkSince == input.receivedElapsedMs) parkCandidate = at
            if (tripOpen && state.trip == null && canConfirmEnd && parkCandidate != null && continuous) {
                physicalEnd = parkCandidate
                lastConfirmedEnd = physicalEnd
                parkCandidate = null; canConfirmEnd = false
            }
            latest = input; tripOpen = state.trip != null
            val motion: Observation<RentalMotion> = when {
                tripOpen -> Observation.Available(RentalMotion.Driving)
                (input.gear as? Observation.Available)?.value == Gear.PARK && physicalEnd != null -> Observation.Available(RentalMotion.Parked(physicalEnd!!.epoch))
                else -> Observation.Missing(MissingReason.NO_DATA)
            }
            visitFloor = before?.at ?: at
            if (continuous && before != null) {
                append(RentalTick(input.source, before.at, at, before.motion, before.unobservedSince))
            }
            // 수락한 recorder revision이 건너뛰면 그 사이의 관측을 공백으로 단정하지 않는다.
            val unobservedSince = before?.takeIf {
                delta != null && delta > policy.maxGapMs && state.revision - it.revision == 1L
            }?.at
            previous = AcceptedFrame(at, motion, state.receiver, state.revision, unobservedSince)
        }
        if (needsResume) {
            if (resumeAt == null) resumeAt = at // 실제 SDK 재접속의 첫 원시 frame 경계. 재시도에서 교체하지 않는다.
            when (val r = recovery.resume(checkNotNull(bound))) {
                is ContractResult.Failure -> return r
                is ContractResult.Success -> r.value?.let(::completed)
            }
            needsResume = false
        }
        if (replay != null) {
            if (replay.to != at) return failure(ContractError.DATA_UNAVAILABLE)
            when (val r = advanceReplay(replay)) {
                is ContractResult.Failure -> return r
                is ContractResult.Success -> if (r.value.deferred.isNotEmpty()) return r
            }
        }
        return drain()
    }

    private fun stationaryPark(input: VehicleObservation): Boolean =
        (input.gear as? Observation.Available)?.value == Gear.PARK &&
            (input.uxRestricted as? Observation.Available)?.value == false &&
            (input.speedMps as? Observation.Available)?.value?.let { abs(it) <= policy.stationaryMps } == true

    private fun recoverForwardClock(state: RecordingState, input: VehicleObservation,
        now: RentalClock): ContractResult<Unit>? {
        val received = RentalClock(now.bootId, input.receivedAt, input.receivedElapsedMs)
        val age = now.elapsedMs - received.elapsedMs
        if (age !in 0..policy.maxAgeMs || now.epoch.value < received.epoch.value ||
            abs(now.epoch.value - received.epoch.value - age) > policy.maxUtcSkewMs) return null
        val id = state.identity
        if (!needsResume) {
            val before = parkedBeforeJump ?: return null
            val origin = physicalEnd
            val first = parkedJumpEnd
            val ageFrom = first ?: before.at
            if (origin == null || !stationaryPark(input) || state.trip != null ||
                state.receiver != before.receiver || (first == null && state.revision - before.revision != 2L) ||
                received.elapsedMs - ageFrom.elapsedMs !in 0..policy.maxGapMs ||
                !received.isForwardWallJumpFrom(before.at, policy.maxUtcSkewMs)) {
                parkedBeforeJump = null
                parkedJumpEnd = null
                return null
            }
            val end = first ?: received.also { parkedJumpEnd = it }
            when (val drained = drainThrough(before.at, forwardWallJump = true)) {
                is ContractResult.Failure -> return drained
                is ContractResult.Success -> Unit
            }
            return restartWithClock(journal.advanceParkedClock(id, origin, before.at, end, rebasedClock))
        }
        val marker = when (val saved = journal.currentInactive(id)) {
            is ContractResult.Failure -> return saved
            is ContractResult.Success -> saved.value ?: return null
        }
        val origin = (marker.policyOrigin as? ContractResult.Success)?.value
        if (!marker.verifiedExit || origin !is Observation.Available ||
            !received.isForwardWallJumpFrom(marker.since, policy.maxUtcSkewMs)) return null
        if (marker.phase == RentalInactivePhase.COMPLETED) {
            val saved = when (val result = journal.cursor(id)) {
                is ContractResult.Failure -> return result
                is ContractResult.Success -> result.value
            }
            // 완료 뒤 읽기 응답을 잃었으면 이미 저장된 기준만 복원한다.
            val storedClock = RentalClockCalibration(policy.maxUtcSkewMs).apply { restore(saved.nextAt) }
            return if (storedClock.project(received) is ContractResult.Success)
                restartWithClock(ContractResult.Success(saved)) else null
        }
        // 원래 exit 복구에만 raw UTC를 쓴다. 새 frame·방문·차량 종료를 발행하지 않는다.
        return when (val result = recovery.resumeWith(id, received) { original, interval ->
            advanceInactive(original.identity, interval, drainAtPreviousClock = true, forwardScheduleAt = received)
        }) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> {
                val restarted = restartWithClock(journal.cursor(id))
                if (restarted is ContractResult.Success) result.value?.let(::completed)
                restarted
            }
        }
    }

    private fun restartWithClock(result: ContractResult<RentalVisitCursor>): ContractResult<Unit> =
        when (result) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> {
                calibration.restore(result.value.nextAt)
                rebasedClock = true
                if (spans.isEmpty()) observedThrough = null
                needsResume = false
                pauseObservations()
                ContractResult.Success(Unit)
            }
        }

    private fun replayBeforeInactiveSettlement(state: RecordingState, frame: DrivingFrame,
        originalClock: RestClockDomain, ordinaryTerminal: Boolean, boundary: RentalInactiveRecovery): ContractResult<Unit> {
        // 저널이 최초 복귀 구간을 준비한 뒤, 원방문과 이탈 전 수락 원관측을 먼저 처리한다.
        when (val r = recoverVisit()) {
            is ContractResult.Failure -> return r
            is ContractResult.Success -> Unit
        }
        val through = boundary.observedThrough
        if (through != null) {
            val replay = when (val r = simulationReplayTick(state, frame, originalClock, ordinaryTerminal, through)) {
                is ContractResult.Failure -> return r
                is ContractResult.Success -> r.value
            }
            if (replay != null) when (val r = advanceReplay(replay)) {
                is ContractResult.Failure -> return r
                is ContractResult.Success -> r.value.deferred.values.firstOrNull()?.let { return failure(it) }
            }
        }
        return drainThrough(boundary.since)
    }

    // 이미 배정된 대여의 원관측만 소비한다. 과거 방문은 준비하지 않는다.
    private fun advanceReplay(tick: RentalTick): ContractResult<RentalRuntimeSnapshot> =
        when (val slot = free()) {
            is ContractResult.Failure -> slot
            is ContractResult.Success -> runtime.advance(tick)
        }

    private fun simulationReplayTick(
        state: RecordingState,
        frame: DrivingFrame,
        originalClock: RestClockDomain,
        ordinaryTerminal: Boolean,
        through: RentalClock? = null,
    ): ContractResult<RentalTick?> {
        val input = frame.observation
        val unavailable = failure(ContractError.DATA_UNAVAILABLE)
        val now = when (val result = rawClock()) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val current = RentalClock(originalClock.bootId, input.receivedAt, input.receivedElapsedMs)
        if (now != current) return unavailable
        val to = through ?: current
        if (to.bootId != current.bootId || to.elapsedMs > current.elapsedMs || to.epoch.value > current.epoch.value ||
            current.elapsedMs - to.elapsedMs != current.epoch.value - to.epoch.value) return unavailable
        fun isEnd(sample: VehicleObservation) = if (through == null) sample == input else
            sample.receivedElapsedMs == to.elapsedMs && sample.receivedAt == to.epoch
        val game = when (val opened = games.open()) {
            is ContractResult.Failure -> return opened
            is ContractResult.Success -> opened.value
        }
        return game.use {
            if (game.identity != state.identity) return@use failure(ContractError.STATE_CONFLICT)
            val pending = when (val result = game.store.rental.pending()) {
                is ContractResult.Failure -> return@use result
                is ContractResult.Success -> result.value
            }
            val cursor = pending.filter { it.phase == VehicleActivity.RENTED &&
                it.cursor.value < minOf(it.end.value, to.epoch.value) }.minOfOrNull { it.cursor.value }
            val due = pending.any {
                when (it.phase) {
                    VehicleActivity.RENTED -> it.cursor == it.end && it.end.value <= to.epoch.value
                    VehicleActivity.RETURNING -> it.returnEnd.value <= to.epoch.value
                    else -> false
                }
            }
            if (cursor == null && !due) return@use ContractResult.Success(null)
            game.store.atomic { db ->
                val observed = when (val result = game.store.observedDriving.read(db, game.identity)) {
                    is ContractResult.Failure -> return@atomic result
                    is ContractResult.Success -> result.value
                }
                if (observed != state) return@atomic failure(ContractError.STATE_CONFLICT)
                val trip = observed.trip ?: run {
                    if ((cursor != null && through == null) || !ordinaryTerminal || observed.outbox.isNotEmpty() ||
                        observed.boundary != RecordingClock(current.elapsedMs, current.epoch)) return@atomic unavailable
                    when (val original = game.store.observedDriving.committedEndingAt(db, game.identity, frame)) {
                        is ContractResult.Failure -> return@atomic original
                        is ContractResult.Success -> original.value?.trip ?: return@atomic unavailable
                    }
                }
                val context = RestAtomicStorage.contexts(db, game.identity).singleOrNull { it.value.trip.id == trip.id }?.value
                    ?: return@atomic unavailable
                if (context.trip != trip || context.departureClock != originalClock ||
                    context.segments.size != trip.segments.size) return@atomic unavailable
                if (cursor == null) {
                    val index = if (through == null) context.segments.lastIndex else
                        context.segments.indexOfLast { isEnd(it.span.to) }
                    val last = context.segments.getOrNull(index)
                    if (last == null) {
                        if (!isEnd(trip.departure)) return@atomic unavailable
                    } else {
                        val segment = trip.segments[index]
                        if (last.index != index || last.span.from != segment.from ||
                            last.span.to != segment.to || !isEnd(last.span.to) || last.clockDomain != originalClock.domain ||
                            last.span.bootId != originalClock.bootId) return@atomic unavailable
                    }
                    // 이미 확정된 관측 시간에는 손대지 않고 정산·반납만 기존 소비자에 맡긴다.
                    return@atomic ContractResult.Success(RentalTick(input.source, to, to, Observation.Missing(MissingReason.NO_DATA)))
                }
                val needed = context.segments.dropWhile { it.span.to.receivedAt.value <= cursor }
                    .takeWhile { through == null || it.span.to.receivedElapsedMs <= to.elapsedMs }
                val start = needed.firstOrNull()?.span?.from ?: return@atomic unavailable
                if (start.receivedAt.value > cursor || !isEnd(needed.last().span.to) ||
                    needed.zipWithNext().any { (a, b) -> a.span.to != b.span.from } ||
                    trip.gaps.any { gap ->
                        if (through == null) {
                            gap.to.elapsed > start.receivedElapsedMs && gap.to.elapsed <= to.elapsedMs ||
                                gap.from?.elapsed?.let { it >= start.receivedElapsedMs && it < to.elapsedMs } == true
                        } else {
                            gap.to.elapsed > start.receivedElapsedMs && (gap.from?.elapsed ?: gap.to.elapsed) < to.elapsedMs
                        }
                    }) return@atomic unavailable
                for (evidence in needed) {
                    val span = evidence.span
                    val elapsed = span.to.receivedElapsedMs - span.from.receivedElapsedMs
                    val segment = trip.segments.getOrNull(evidence.index) ?: return@atomic unavailable
                    if (evidence.clockDomain != originalClock.domain || span.bootId != originalClock.bootId ||
                        segment.from != span.from || segment.to != span.to || elapsed !in 1..policy.maxGapMs ||
                        span.to.receivedAt.value - span.from.receivedAt.value != elapsed ||
                        RestEvidenceValidator(evidence.policy).drivingMsForUse(state.identity, listOf(span))
                            !is ContractResult.Success) return@atomic unavailable
                }
                val from = RentalClock(originalClock.bootId, start.receivedAt, start.receivedElapsedMs)
                ContractResult.Success(RentalTick(input.source, from, to, Observation.Available(RentalMotion.Driving)))
            }
        }
    }

    private fun append(tick: RentalTick) {
        observedThrough = tick.to
        val last = spans.peekLast()
        // 재진입 표본이 이탈 직전과 같은 시각이어도 앞선 대기 구간과 합치지 않는다.
        if (last != null && last.to == tick.from && last.motion == tick.motion && resumeAt != tick.from) {
            spans.removeLast(); spans.addLast(last.copy(to = tick.to))
        } else if (spans.size < 64) spans.addLast(tick) // 기술 버퍼이며 고객 수/금액 규칙이 아니다.
        else issue = ContractError.DATA_UNAVAILABLE // 원래 범위를 버리고 현재부터 정상 재개하지 않는다.
    }
    private fun drain(through: RentalClock? = null, forwardWallJump: Boolean = false,
        planVisits: Boolean = true): ContractResult<RentalRuntimeSnapshot> {
        if (!connected) return failure(ContractError.DATA_UNAVAILABLE)
        val acceptedThrough = through.takeIf { forwardWallJump }
        when (val r = recoverVisit(acceptedThrough)) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
        when (val r = free()) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
        var snapshot = when (val r = runtime.read()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        while (spans.isNotEmpty()) {
            val tick = spans.first
            if (through != null && (tick.to.bootId != through.bootId || tick.to.elapsedMs > through.elapsedMs)) break
            var from = tick.from
            var visits = 0
            // 준비된 이탈 복구가 끝날 때까지 기존 대여만 진행한다.
            while (planVisits && tick.motion is Observation.Available) {
                val id = checkNotNull(bound)
                var cursor = when (val r = journal.cursor(id)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                val floor = visitFloor?.takeIf { it.epoch.value > from.epoch.value } ?: from
                if (cursor.nextAt.epoch.value < floor.epoch.value) {
                    when (val moved = journal.skipMissedVisitSlots(id, floor)) {
                        is ContractResult.Failure -> return moved
                        is ContractResult.Success -> cursor = moved.value
                    }
                }
                if (cursor.nextAt.epoch.value > tick.to.epoch.value) break
                val definition = when (val r = definitions()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                val limit = when (val r = definition.rentalReplayLimit()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                if (visits >= limit) { issue = ContractError.DATA_UNAVAILABLE; break }
                if (from != cursor.nextAt) {
                    snapshot = when (val r = runtime.advance(tick.slice(from, cursor.nextAt))) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                    completed(snapshot); if (snapshot.deferred.isNotEmpty()) return ContractResult.Success(snapshot)
                }
                eligible = tick.to
                val prepared = when (val r = preparation.next()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value } ?: break
                when (val r = runtime.visit(ContractResult.Success(prepared.visit))) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
                from = cursor.nextAt; visits++
            }
            val remainder = tick.slice(from, tick.to)
            val advanced = if (forwardWallJump) withRuntime(true, checkNotNull(through)) { it.advance(remainder) }
                else runtime.advance(remainder)
            snapshot = when (advanced) { is ContractResult.Failure -> return advanced; is ContractResult.Success -> advanced.value }
            completed(snapshot); if (snapshot.deferred.isNotEmpty()) return ContractResult.Success(snapshot)
            spans.removeFirst()
        }
        completed(snapshot)
        return ContractResult.Success(snapshot)
    }
    private fun RentalTick.slice(start: RentalClock, end: RentalClock) =
        copy(from = start, to = end, unobservedSince = unobservedSince.takeIf { start == from })

    private fun drainThrough(boundary: RentalClock, forwardWallJump: Boolean = false,
        planVisits: Boolean = false): ContractResult<Unit> =
        when (val result = drain(boundary, forwardWallJump, planVisits)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> result.value.deferred.values.firstOrNull()?.let { failure(it) }
                ?: ContractResult.Success(Unit)
        }

    private fun completed(snapshot: RentalRuntimeSnapshot) {
        val id = snapshot.firstCompleted?.rentalId ?: return
        if (giftAttempt == id) return
        giftAttempt = id
        if (free() is ContractResult.Failure) { issue = ContractError.STATE_CONFLICT; return }
        val actor = bound ?: return
        when (val r = gift.claimGift(actor)) {
            is ContractResult.Failure -> { issue = r.error; Log.w("DriveGrowthRental", "first_gift_deferred code=${r.error}") }
            is ContractResult.Success -> Log.i("DriveGrowthRental", "first_gift_original_confirmed source=${actor.source}")
        }
    }
    fun inactive(configurationChange: Boolean, raw: ContractResult<RentalClock>): ContractResult<Unit> {
        pauseObservations()
        if (configurationChange || !connected || inactiveRecorded) return ContractResult.Success(Unit)
        val since = when (raw) { is ContractResult.Failure -> return raw; is ContractResult.Success -> when (val r = calibration.project(raw.value)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value } }
        val actor = bound ?: return failure(ContractError.DATA_UNAVAILABLE)
        val prior = when (val r = journal.currentInactive(actor)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (prior != null && prior.phase != RentalInactivePhase.COMPLETED) return failure(ContractError.STATE_CONFLICT)
        val mode = rentalExitMode(false, actor.source, since, latest, tripOpen, physicalEnd, policy, rebasedClock)
            ?: return failure(ContractError.DATA_UNAVAILABLE)
        val marker = RequestId("rental-inactive-" + UUID.randomUUID())
        val drained = drainThrough(since, planVisits = true)
        val result = journal.beginVerifiedInactive(actor, marker, since, drivingExit = mode == RentalExitMode.DRIVING,
            observedThrough = observedThrough, lastConfirmedEnd = lastConfirmedEnd, rebasedClock = rebasedClock)
        if (result is ContractResult.Failure) return result
        inactiveRecorded = true
        return drained
    }
    private fun failure(error: ContractError) = ContractResult.Failure(error)
}
