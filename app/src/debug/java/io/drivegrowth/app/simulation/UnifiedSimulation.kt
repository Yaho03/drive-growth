package io.drivegrowth.app.simulation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.rest.RestAreaProof
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.app.ui.rest.RestAreaScene
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.RestPlace
import java.util.concurrent.Executor
import kotlin.random.Random

/** debug 전용 입력 어댑터. 진행 설정은 시나리오 위치이며 금융 권위는 기존 SQLite뿐이다. */
internal class UnifiedSimulation(private val context: Context, preferenceName: String = "unified-simulation-v1") : HostSimulation {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val restored = try {
        val recording = prefs.getBoolean("restRecording", false)
        val timeline = if (!prefs.contains("stage")) {
            if (recording || preferenceName == "unified-simulation-v1" &&
                context.getDatabasePath("unified-simulation.db").exists()) null
            else SimulationTimeline()
        } else SimulationTimeline(prefs.getLong("elapsed", -1), prefs.getInt("stage", -1),
            prefs.getBoolean("restRun", false))
        require(timeline?.restRun != true || recording)
        timeline to recording
    } catch (_: RuntimeException) { null }
    @Volatile override var restRecording = restored?.second == true
        private set
    @Volatile private var progress = restored?.first
    private val lostRestInput = progress?.let { it.restRun && !it.controlsAllowed } == true
    private var accept: ((DrivingFrame, () -> Unit) -> Unit)? = null
    private var generation = 0L
    private var inFlight = false
    @Volatile private var emittedFrame: DrivingFrame? = null
    private var restParking: RestParking? = null
    private var restValidatedForAttachment = false
    private var restPresentationFocused = false
    private var pendingRestAdvance: SimulationTimeline? = null
    private var lastRestMotion: VehicleObservation? = null
    private val requiredParkingMs by lazy {
        (AssetTrialDefinitionProvider(context.assets).load() as? ContractResult.Success)?.value?.rest?.minParkedMs
    }
    private var completedTerminal: SimulationTimeline? = null
    @Volatile private var failure: String? = when {
        progress == null -> "진행 기록을 읽지 못했습니다. 기존 원장은 보존합니다."
        lostRestInput ->
            "이전 프로세스의 미완료 휴식 시연 근거를 확인할 수 없습니다. 확정 원장은 보존합니다."
        else -> null
    }
    private var notice: String? = null
    private var nav: SimulationNavigation? = null
    private var terminalProof: SimulationTerminalProof? = null
    override var changed: (() -> Unit)? = null
    override val policy = RecordingPolicy(60_000, 60_000, 1000, 3000, 0.1, 0.5)
    override val controlsAllowed get() = failure == null && !inFlight && progress?.controlsAllowed == true
    override val parkedRestScene: RestAreaScene.Parked?
        get() = restParking?.takeIf { cached -> restValidatedForAttachment && failure == null && progress?.let {
            it.inRestArea && (inFlight || it.elapsedMs == cached.proof.elapsedMs)
        } == true }?.scene
    override fun takeRestArrival(stopId: String): Boolean {
        val current = restParking ?: return false
        if (current.scene.stopId != stopId || !current.animateArrival ||
            current.scene.phase != RestAreaScene.Phase.ARRIVED) return false
        restParking = current.copy(animateArrival = false)
        return true
    }
    override fun setRestPresentationFocused(focused: Boolean) {
        restPresentationFocused = focused
        if (!focused) return
        val pending = pendingRestAdvance ?: return
        pendingRestAdvance = null
        advanceAfter(pending, generation)
    }
    override val canRetry get() = progress != null && failure != null && !inFlight && !lostRestInput
    override val inputState: SimulationInputState
        get() = when {
            canRetry -> SimulationInputState.RETRYABLE_FAILURE
            failure != null -> SimulationInputState.UNAVAILABLE
            accept != null && (inFlight || progress?.controlsAllowed == false) -> SimulationInputState.PROCESSING
            else -> SimulationInputState.READY
        }
    override val status get() = failure ?: notice ?: when (inputState) {
        SimulationInputState.PROCESSING -> "모의주행을 진행하고 있어요."
        SimulationInputState.UNAVAILABLE -> "모의 입력을 확인할 수 없어요."
        SimulationInputState.RETRYABLE_FAILURE -> "진행을 멈췄어요. 다시 시도해 주세요."
        SimulationInputState.READY -> if (progress?.terminal == true) "모의주행을 마쳤어요." else "모의주행을 시작할 수 있어요."
    }
    override fun clock(): ContractResult<RentalClock> = progress?.let { ContractResult.Success(it.clock) }
        ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    override fun navigation(games: GameSessionFactory, main: Executor): HostDeliveryNavigation {
        terminalProof = SimulationTerminalProof(prefs, games)
        return SimulationNavigation(prefs, games, ::clock, main).also { nav = it }
    }

    override fun observed(frame: DrivingFrame, result: ContractResult<DrivingRecordingResult>): ContractResult<Unit> {
        val handled: ContractResult<Unit> = try {
            val current = progress ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            val waiting = (result as? ContractResult.Success)?.value?.waiting
            when {
                frame != current.frame() -> ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                result is ContractResult.Failure -> if (current.stage == 0 && result.error == ContractError.DATA_UNAVAILABLE)
                    ContractResult.Success(Unit) else result
                restRecording && waiting != null -> ContractResult.Failure(waiting)
                current.restRun -> ContractResult.Success(Unit)
                else -> nav?.observed(current, result) ?: ContractResult.Success(Unit)
            }
        } catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
          catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
        if (handled is ContractResult.Failure) failed(handled.error)
        return handled
    }
    override fun attach(worker: Executor?, accept: (DrivingFrame, () -> Unit) -> Unit) {
        stop()
        this.accept = accept
        val current = progress ?: return
        if (failure != null) return
        val proof = terminalProof
        when {
            current == completedTerminal -> changed?.invoke()
            !current.terminal || worker == null || proof == null -> emit()
            else -> {
                val token = generation
                inFlight = true
                changed?.invoke()
                worker.execute {
                    val completed = proof.matches(current)
                    handler.post {
                        if (token != generation || progress != current) return@post
                        inFlight = false
                        if (completed) {
                            completedTerminal = current
                            Log.i("DriveGrowthSimulation", "simulation_terminal_restored source=SIMULATION elapsedMs=${current.elapsedMs} utcMs=${current.clock.epoch.value}")
                            changed?.invoke()
                        } else emit()
                    }
                }
            }
        }
    }

    override fun inputConsumed(frame: DrivingFrame, recorded: DrivingRecordingResult, rental: RentalRuntimeSnapshot) {
        val current = progress ?: return
        if (current.terminal && failure == null && frame == current.frame()) {
            terminalProof?.remember(current, recorded, rental)
        }
    }

    override fun restInputRecorded(frame: DrivingFrame, recorded: DrivingRecordingResult) {
        val current = progress ?: return
        if (!current.restRun || emittedFrame !== frame || recorded.waiting != null || recorded.state.anchor != frame.observation) return
        val proof = (restSample(frame)?.area as? Observation.Available)?.value
        val required = requiredParkingMs
        handler.post {
            if (emittedFrame !== frame || accept == null || failure != null) return@post
            updateRestParking(frame.observation, proof, required)
        }
    }

    private data class RestParking(val scene: RestAreaScene.Parked, val proof: RestAreaProof,
        val beganAt: Long, val animateArrival: Boolean)

    private fun updateRestParking(raw: VehicleObservation, area: RestAreaProof?, required: Long?) {
        val speed = (raw.speedMps as? Observation.Available)?.value
        val moving = raw.gear == Observation.Available(Gear.DRIVE) &&
            raw.uxRestricted is Observation.Available && speed != null && speed.isFinite() && speed >= policy.movingMps
        val fromDrive = lastRestMotion?.let { raw.receivedElapsedMs - it.receivedElapsedMs in 1..policy.maxGapMs } == true
        lastRestMotion = raw.takeIf { moving }
        val prior = restParking
        restParking = null
        if (raw.source != InputSource.SIMULATION || area == null || !area.inside || area.source != raw.source ||
            area.elapsedMs != raw.receivedElapsedMs || area.utc != raw.receivedAt ||
            area.kind !in setOf(RestPlace.SERVICE_AREA, RestPlace.DROWSY_SHELTER) ||
            raw.gear != Observation.Available(Gear.PARK) || raw.uxRestricted != Observation.Available(false) ||
            speed == null || !speed.isFinite() || kotlin.math.abs(speed) > policy.stationaryMps || required == null) return
        val continuous = prior?.takeIf { previous ->
            previous.proof.copy(elapsedMs = area.elapsedMs, utc = area.utc) == area &&
                area.elapsedMs - previous.proof.elapsedMs in 0..policy.maxGapMs &&
                area.utc.value - previous.proof.utc.value == area.elapsedMs - previous.proof.elapsedMs
        }
        val began = continuous?.beganAt ?: area.elapsedMs
        val scene = RestAreaScene.Parked(raw.source, "${area.areaId}:$began", area.kind, null,
            RestAreaScene.ObservedParking(area.elapsedMs - began, required))
        restParking = RestParking(scene, area, began, continuous?.animateArrival ?: fromDrive)
        restValidatedForAttachment = true
    }

    private fun failed(error: ContractError) { android.util.Log.w("DriveGrowthSimulation", "simulation_input_deferred code=$error stage=${progress?.stage}"); failure = "모의주행을 잠시 멈췄어요. 같은 지점에서 다시 시도할 수 있어요." }
    override fun rentalResult(result: ContractResult<RentalRuntimeSnapshot>) {
        val current = progress ?: return
        if (current.stage == 0) return
        when (result) {
            is ContractResult.Failure -> failed(result.error)
            is ContractResult.Success -> result.value.deferred.values.firstOrNull()?.let(::failed)
        }
    }
    override fun rejected(error: ContractError) { notice = "시작 조건 미충족 ($error). 기존 요청/초기 지급을 먼저 확인해 주세요."; changed?.invoke() }
    override fun retry() { if (canRetry && accept != null) { failure = null; emit() } }
    override fun begin() {
        val current = progress ?: return
        if (!controlsAllowed || accept == null) return
        notice = null
        progress = current.begin() ?: return
        emit()
    }
    override fun beginRest() {
        val current = progress ?: return
        if (!controlsAllowed || accept == null) return
        val next = current.beginRest() ?: return
        restRecording = true
        notice = null
        progress = next
        emit()
    }

    override fun restSample(frame: DrivingFrame): SimulationRestSample? {
        val current = progress ?: return null
        if (!restRecording || frame != current.frame()) return null
        val clock = RestClockDomain("unified-simulation-elapsed", current.clock.bootId)
        val area = if (current.restRun) Observation.Available(RestSimulationScenario.testArea(frame.observation, current.inRestArea))
            else Observation.Missing(MissingReason.NO_DATA)
        return SimulationRestSample(clock, area, current.restRun, current.terminal && !current.restRun,
            startsRestRun = current.restRun && current.stage == 1)
    }

    private fun emit() {
        val current = progress ?: return
        val consumer = accept ?: return
        if (inFlight || failure != null) return
        val token = generation
        if (!prefs.edit().putLong("elapsed", current.elapsedMs).putInt("stage", current.stage)
                .putBoolean("restRun", current.restRun).putBoolean("restRecording", restRecording).commit()) {
            failure = "진행을 저장하지 못했어요. 기존 기록은 보존했어요."; changed?.invoke(); return
        }
        android.util.Log.i("DriveGrowthSimulation", "simulation_input source=SIMULATION stage=${current.stage} elapsedMs=${current.elapsedMs} utcMs=${current.clock.epoch.value}")
        inFlight = true; changed?.invoke()
        val frame = current.frame()
        emittedFrame = frame
        consumer(frame) {
            if (token != generation || accept == null) return@consumer
            inFlight = false
            if (failure == null && current.terminal) completedTerminal = current
            changed?.invoke()
            if (failure == null && current.next() != null) advanceAfter(current, token)
        }
    }

    private fun advanceAfter(current: SimulationTimeline, token: Long) {
        val delay = when {
            current.inRestArea && (current.stage == 62 || current.stage == 67) -> 1_450L
            current.inRestArea -> 550L
            else -> 250L
        }
        handler.postDelayed({
            if (token != generation || accept == null || progress != current) return@postDelayed
            if (current.inRestArea && !restPresentationFocused) {
                pendingRestAdvance = current
                return@postDelayed
            }
            progress = current.next()
            emit()
        }, delay) // 벽시계는 화면 재생 간격에만 사용한다.
    }
    override fun stop() {
        generation++; accept = null; inFlight = false
        emittedFrame = null; restValidatedForAttachment = false; lastRestMotion = null
        pendingRestAdvance = null
        handler.removeCallbacksAndMessages(null)
    }

    /** 입력용 seed를 기존 planner로 고른다. 가격/분포/사고 계산이나 지급을 복제하지 않는다. */
    override fun rentalRandom(games: GameSessionFactory, authority: RentalPreparationInputs): Random {
        val current = checkNotNull(progress)
        val run = current.runStartMs
        if (prefs.getLong("seedRun", -1) == run && prefs.contains("seed")) return Random(prefs.getInt("seed", 0))
        val seed = when (val opened = games.open()) {
            is ContractResult.Failure -> throw IllegalStateException(opened.toString())
            is ContractResult.Success -> opened.value.use { game ->
                val owned = when (val read = game.store.listOwnedVehicles()) {
                    is ContractResult.Failure -> error("보유 조회 실패: ${read.error}")
                    is ContractResult.Success -> read.value
                }
                val target = owned.firstOrNull {
                    it.lifecycle == VehicleLifecycle.OWNED && (authority.definition.demand[it.vehicleId] ?: 0) > 0
                }?.vehicleId
                if (target == null) { notice = "대여할 차량을 찾지 못했어요."; return Random.Default }
                val planner = RentalVisitPlanner()
                (0..100_000).first { candidate ->
                    val draft = (planner.prepare(game.identity, authority.catalog, authority.definition,
                        RentalVisitCursor(Revision(0), 0, current.clock), current.clock, Random(candidate),
                        { RequestId("simulation-seed-probe") }) as? ContractResult.Success)?.value
                    draft != null && draft.visit.request.desired == target && draft.visit.request.hours == 1 &&
                        draft.visit.terms.roll >= draft.visit.terms.customer.accidentPercent()
                }
            }
        }
        check(prefs.edit().putLong("seedRun", run).putInt("seed", seed).commit()) { "시연 seed 저장 실패" }
        return Random(seed)
    }
}
