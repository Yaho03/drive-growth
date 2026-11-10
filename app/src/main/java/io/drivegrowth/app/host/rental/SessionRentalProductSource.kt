package io.drivegrowth.app.host.rental

import android.util.Log
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
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
    private var previous: Pair<RentalClock, Observation<RentalMotion>>? = null
    private var latest: VehicleObservation? = null
    private var tripOpen = false
    private var canConfirmEnd = false
    private var parkCandidate: RentalClock? = null
    private var physicalEnd: RentalClock? = null
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
    })
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
    private val recovery = RentalInactiveRecoveryConsumer(journal::currentInactive, journal::advanceInactive) {
        resumeAt?.let { ContractResult.Success(it) } ?: failure(ContractError.DATA_UNAVAILABLE)
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
    private fun <T> withRuntime(timed: Boolean, action: (RentalRuntimeSource) -> ContractResult<T>): ContractResult<T> {
        val now = if (timed) when (val r = clock()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value } else null
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
    private fun recoverVisit(): ContractResult<Unit> {
        val id = bound ?: return failure(ContractError.DATA_UNAVAILABLE)
        val pending = when (val r = journal.pending(id)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (pending != null) when (val r = runtime.visit(ContractResult.Success(pending.visit))) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
        return ContractResult.Success(Unit)
    }
    private fun connect(canActivate: Boolean): ContractResult<Unit> {
        if (connected) return ContractResult.Success(Unit)
        val id = when (val r = identity()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val prior = when (val r = journal.currentInactive(id)) {
            is ContractResult.Failure -> if (r.error == ContractError.DATA_UNAVAILABLE && canActivate) null else return r
            is ContractResult.Success -> r.value
        }
        bound = id
        val cursor = when (val r = journal.activate(id)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        calibration.restore(cursor.nextAt)
        if (prior?.basis == RentalInactiveBasis.DRIVING_END)
            physicalEnd = ((prior.policyOrigin as? ContractResult.Success)?.value as? Observation.Available)?.value
        connected = true
        when (val restored = recoverVisit()) { is ContractResult.Failure -> return restored; is ContractResult.Success -> Unit }
        if (prior != null && prior.phase != RentalInactivePhase.RECORDED) {
            when (val restored = recovery.resume(id)) {
                is ContractResult.Failure -> return restored
                is ContractResult.Success -> restored.value?.let(::completed)
            }
            needsResume = false
        }
        return ContractResult.Success(Unit)
    }

    fun reconnect() { needsResume = true; inactiveRecorded = false; resumeAt = null; previous = null; parkCandidate = null; canConfirmEnd = false; giftAttempt = null }
    fun pauseObservations() { previous = null; parkCandidate = null; canConfirmEnd = false }

    fun observe(frame: DrivingFrame, recorded: ContractResult<DrivingRecordingResult>): ContractResult<RentalRuntimeSnapshot> {
        val state = when (recorded) { is ContractResult.Failure -> { previous = null; latest = null; return recorded }; is ContractResult.Success -> recorded.value.state }
        val input = frame.observation
        if (state.anchor != input || state.identity.source != input.source) { previous = null; latest = null; return failure(ContractError.DATA_UNAVAILABLE) }
        val moving = (input.gear as? Observation.Available)?.value in setOf(Gear.DRIVE, Gear.REVERSE) &&
            ((input.speedMps as? Observation.Available)?.value?.let { it.isFinite() && abs(it) > policy.movingMps } == true)
        when (val r = connect(moving || physicalEnd != null)) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
        when (val r = recoverVisit()) { is ContractResult.Failure -> if (r.error != ContractError.STATE_CONFLICT) return r; is ContractResult.Success -> Unit }
        if (state.identity != bound) return failure(ContractError.STATE_CONFLICT)
        val now = when (val r = clock()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (input.receivedElapsedMs > now.elapsedMs || now.elapsedMs - input.receivedElapsedMs > policy.maxAgeMs)
            return failure(ContractError.DATA_UNAVAILABLE)
        val at = when (val r = calibration.project(RentalClock(now.bootId, input.receivedAt, input.receivedElapsedMs))) {
            is ContractResult.Failure -> return r
            is ContractResult.Success -> r.value
        }
        val before = previous
        val continuous = before != null && at.elapsedMs > before.first.elapsedMs &&
            at.elapsedMs - before.first.elapsedMs <= policy.maxGapMs
        if (!continuous) { parkCandidate = null; canConfirmEnd = false }
        if (moving) { canConfirmEnd = true; parkCandidate = null; physicalEnd = null }
        if (canConfirmEnd && state.parkSince == input.receivedElapsedMs) parkCandidate = at
        if (tripOpen && state.trip == null && canConfirmEnd && parkCandidate != null && continuous) {
            physicalEnd = parkCandidate; parkCandidate = null; canConfirmEnd = false
        }
        latest = input; tripOpen = state.trip != null
        val motion: Observation<RentalMotion> = when {
            tripOpen -> Observation.Available(RentalMotion.Driving)
            (input.gear as? Observation.Available)?.value == Gear.PARK && physicalEnd != null -> Observation.Available(RentalMotion.Parked(physicalEnd!!.epoch))
            else -> Observation.Missing(MissingReason.NO_DATA)
        }
        visitFloor = before?.first ?: at
        if (continuous) append(RentalTick(input.source, before.first, at, before.second))
        previous = at to motion
        if (needsResume) {
            if (resumeAt == null) resumeAt = at // 실제 SDK 재접속의 첫 원시 frame 경계. 재시도에서 교체하지 않는다.
            val boundary = when (val r = journal.currentInactive(checkNotNull(bound))) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
            // 실제 stop 이전에 보존한 관측만 먼저 정산한다. 실패해도 E가 원래 resume to를 준비할 수 있게 한다.
            if (boundary != null) drain(boundary.since)
            when (val r = recovery.resume(checkNotNull(bound))) {
                is ContractResult.Failure -> return r
                is ContractResult.Success -> r.value?.let(::completed)
            }
            needsResume = false
        }
        return drain()
    }

    private fun append(tick: RentalTick) {
        val last = spans.peekLast()
        if (last != null && last.to == tick.from && last.motion == tick.motion) {
            spans.removeLast(); spans.addLast(last.copy(to = tick.to))
        } else if (spans.size < 64) spans.addLast(tick) // 기술 버퍼이며 고객 수/금액 규칙이 아니다.
        else issue = ContractError.DATA_UNAVAILABLE // 원래 범위를 버리고 현재부터 정상 재개하지 않는다.
    }
    private fun drain(through: RentalClock? = null): ContractResult<RentalRuntimeSnapshot> {
        if (!connected) return failure(ContractError.DATA_UNAVAILABLE)
        when (val r = recoverVisit()) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
        when (val r = free()) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
        var snapshot = when (val r = runtime.read()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        while (spans.isNotEmpty()) {
            val tick = spans.first
            if (through != null && (tick.to.bootId != through.bootId || tick.to.elapsedMs > through.elapsedMs)) break
            var from = tick.from
            var visits = 0
            while (tick.motion is Observation.Available) {
                val cursor = when (val r = journal.cursor(checkNotNull(bound))) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                if (cursor.nextAt.epoch.value < from.epoch.value || cursor.nextAt.epoch.value < (visitFloor?.epoch?.value ?: from.epoch.value)) {
                    issue = ContractError.DATA_UNAVAILABLE; break // 과거 고객을 현재 재고로 생성하지 않는다.
                }
                if (cursor.nextAt.epoch.value > tick.to.epoch.value) break
                val definition = when (val r = definitions()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                val limit = when (val r = definition.rentalReplayLimit()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                if (visits >= limit) { issue = ContractError.DATA_UNAVAILABLE; break }
                if (from != cursor.nextAt) {
                    snapshot = when (val r = runtime.advance(tick.copy(from = from, to = cursor.nextAt))) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
                    completed(snapshot); if (snapshot.deferred.isNotEmpty()) return ContractResult.Success(snapshot)
                }
                eligible = tick.to
                val prepared = when (val r = preparation.next()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value } ?: break
                when (val r = runtime.visit(ContractResult.Success(prepared.visit))) { is ContractResult.Failure -> return r; is ContractResult.Success -> Unit }
                from = cursor.nextAt; visits++
            }
            snapshot = when (val r = runtime.advance(tick.copy(from = from))) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
            completed(snapshot); if (snapshot.deferred.isNotEmpty()) return ContractResult.Success(snapshot)
            spans.removeFirst()
        }
        completed(snapshot)
        return ContractResult.Success(snapshot)
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
        // lifecycle 시각까지 SDK heartbeat를 합성하지 않는다. 앞선 미관측 gap은 E cursor 검사가 보류한다.
        drain()
        val mode = rentalExitMode(false, actor.source, since, latest, tripOpen, physicalEnd, policy)
            ?: return failure(ContractError.DATA_UNAVAILABLE)
        val marker = RequestId("rental-inactive-" + UUID.randomUUID())
        val result = when (mode) {
            RentalExitMode.DRIVING -> journal.beginInactiveWhileDriving(actor, marker, since)
            RentalExitMode.KNOWN_PARKED -> journal.beginInactive(actor, marker, since)
        }
        if (result is ContractResult.Success) inactiveRecorded = true
        return result
    }
    private fun failure(error: ContractError) = ContractResult.Failure(error)
}
