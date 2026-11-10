package io.drivegrowth.app.host.onboarding

import io.drivegrowth.app.host.GameIdentity

import io.drivegrowth.app.ui.onboarding.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

enum class OnboardingStep { INTRO, CHOOSING, CONFIRMING }
/** 화면 단계/선택만 복원한다. 지급·마커·프로필 권위는 DB에서 다시 읽는다. */
data class OnboardingHostUiState(val identity: GameIdentity? = null, val step: OnboardingStep = OnboardingStep.INTRO, val selected: VehicleId? = null, val pending: VehicleId? = null)

class OnboardingHostController(private val source: OnboardingHostSource, private val worker: Executor, private val main: Executor,
    initial: OnboardingHostUiState = OnboardingHostUiState()) {
    var uiState = initial; private set
    @Volatile private var enabled = false
    @Volatile private var released = false
    @Volatile private var generation = 0L
    private var inFlight = false
    private var refreshRequested = false
    private var bound: GameIdentity? = null
    private var snapshot: OnboardingSnapshot? = null
    private var screen: OnboardingScreenState = OnboardingScreenState.Loading
    private var listener: ((OnboardingScreenState, Boolean) -> Unit)? = null
    private var goToShop: (() -> Unit)? = null
    private var changed: (() -> Unit)? = null

    fun attach(listener: (OnboardingScreenState, Boolean) -> Unit, goToShop: () -> Unit, changed: () -> Unit = {}) {
        if (released) return
        detach(); this.listener = listener; this.goToShop = goToShop; this.changed = changed
        publish()
    }
    fun detach() { enabled = false; generation++; refreshRequested = false; listener = null; goToShop = null; changed = null }
    fun release() { detach(); released = true }
    fun setControlsEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value; generation++
        if (value) refresh() else { refreshRequested = false; publish() }
    }
    fun refresh() {
        if (!enabled || released || listener == null) return
        refreshRequested = true
        if (!inFlight) { refreshRequested = false; launch(null) { source.read() } }
    }
    fun onEvent(event: OnboardingEvent) {
        if (!enabled || released || listener == null || inFlight) return
        when (event) {
            OnboardingEvent.StartChoosing -> if (screen is OnboardingScreenState.Intro) updateStep(OnboardingStep.CHOOSING)
            is OnboardingEvent.SelectVehicle -> if (screen is OnboardingScreenState.Choosing && candidates().any { it.vehicle.vehicleId == event.vehicleId && it.selectable }) {
                uiState = uiState.copy(selected = event.vehicleId); publish()
            }
            is OnboardingEvent.ReviewSelection -> if (screen is OnboardingScreenState.Choosing && uiState.selected == event.vehicleId && candidates().any { it.vehicle.vehicleId == event.vehicleId && it.selectable })
                updateStep(OnboardingStep.CONFIRMING)
            is OnboardingEvent.ConfirmSelection -> if (screen is OnboardingScreenState.Confirming && uiState.selected == event.vehicleId) submit(event.vehicleId)
            is OnboardingEvent.Retry -> if (event.vehicleId == null) refresh()
                else if (screen is OnboardingScreenState.GrantError && uiState.selected == event.vehicleId) submit(event.vehicleId)
            OnboardingEvent.Previous -> when (screen) {
                is OnboardingScreenState.Choosing -> updateStep(OnboardingStep.INTRO)
                is OnboardingScreenState.Confirming -> updateStep(OnboardingStep.CHOOSING)
                else -> Unit
            }
            OnboardingEvent.GoToShop -> if (screen is OnboardingScreenState.Completed) goToShop?.invoke()
        }
    }
    private fun updateStep(step: OnboardingStep) { uiState = uiState.copy(step = step); snapshot?.let(::show); publish() }
    private fun submit(choice: VehicleId) {
        val identity = bound ?: return
        uiState = uiState.copy(pending = choice)
        screen = OnboardingScreenState.Submitting(choice, candidates().find { it.vehicle.vehicleId == choice }?.vehicle?.displayName, label(identity.source))
        launch(choice) {
            when (val committed = source.choose(identity, choice)) {
                is ContractResult.Failure -> committed
                is ContractResult.Success -> when (val read = source.read()) {
                    is ContractResult.Failure -> read
                    is ContractResult.Success -> if (read.value.identity != identity ||
                        (read.value.initialization.status as? ProfileInitializationStatus.Existing)?.original != committed.value)
                        ContractResult.Failure(ContractError.STORAGE_FAILURE) else read
                }
            }
        }
    }
    private fun launch(choice: VehicleId?, action: () -> ContractResult<OnboardingSnapshot>) {
        inFlight = true
        val started = generation
        val expected = bound
        if (choice == null && screen !is OnboardingScreenState.Completed) screen = OnboardingScreenState.Loading
        publish()
        try {
            worker.execute {
                var stored = choice != null
                val result = if (!active(started)) null else guarded {
                    when (val loaded = action()) {
                        is ContractResult.Failure -> loaded
                        is ContractResult.Success -> {
                            val gift = (loaded.value.gift as? ContractResult.Success)?.value
                            if (!valid(loaded.value) || (expected != null && expected != loaded.value.identity)) return@guarded ContractResult.Failure(ContractError.INVALID_INPUT)
                            val initialized = (loaded.value.initialization.status as? ProfileInitializationStatus.Existing)?.original
                            if (active(started) && initialized?.grant?.changedVehicle != null &&
                                gift != null && gift.granted == null && gift.firstCompletedRental != null) {
                                when (val claimed = source.claimGift(loaded.value.identity)) {
                                    is ContractResult.Failure -> ContractResult.Success(loaded.value.copy(gift = claimed))
                                    is ContractResult.Success -> { stored = true; source.read() }
                                }
                            } else loaded
                        }
                    }
                }
                main.execute {
                    inFlight = false
                    if (active(started) && result != null) {
                        when (result) {
                            is ContractResult.Failure -> fail(result.error, choice)
                            is ContractResult.Success -> {
                                if (!valid(result.value) || (bound != null && bound != result.value.identity)) fail(ContractError.INVALID_INPUT, choice)
                                else {
                                    if (uiState.identity != result.value.identity) uiState = OnboardingHostUiState(result.value.identity)
                                    bound = result.value.identity; snapshot = result.value; show(result.value)
                                    if (stored) changed?.invoke()
                                }
                            }
                        }
                        publish()
                    }
                    if (refreshRequested && enabled && !released) refresh()
                }
            }
        } catch (_: RejectedExecutionException) { inFlight = false; fail(ContractError.STORAGE_FAILURE, choice); publish() }
    }
    private fun active(started: Long) = enabled && !released && generation == started
    private fun valid(value: OnboardingSnapshot): Boolean {
        if (value.identity.profileId != value.initialization.profileId || value.identity.source != value.initialization.source) return false
        val original = (value.initialization.status as? ProfileInitializationStatus.Existing)?.original
        if (original != null && (original.command.profileId != value.identity.profileId || original.command.grant.source != value.identity.source ||
            original.marker.profileId != value.identity.profileId || original.grant.transaction.source != value.identity.source)) return false
        val gift = (value.gift as? ContractResult.Success)?.value
        return gift == null || (gift.profileId == value.identity.profileId && gift.source == value.identity.source)
    }
    private fun fail(error: ContractError, choice: VehicleId?) {
        screen = if (choice == null) OnboardingScreenState.LoadError("저장 상태를 확인하지 못했습니다. ${errorText(error)}", true)
        else OnboardingScreenState.GrantError(choice, null, "확정 결과를 확인하지 못했습니다. ${errorText(error)} 같은 요청으로 다시 확인해 주세요", true, false, label(bound?.source))
    }
    private fun candidates(): List<OnboardingCandidate> {
        val value = snapshot ?: return emptyList()
        val content = (value.content as? ContractResult.Success)?.value ?: return emptyList()
        val state = value.initialization
        if (state.status !is ProfileInitializationStatus.New || content.catalog.contentVersion != state.contentVersion || content.ruleVersion != state.ruleVersion) return emptyList()
        val ids = FirstGrant.CANDIDATES.filter { id -> id in state.availableCandidates && content.vehicles.any { it.vehicleId == id } }
        return ids.mapNotNull { id -> content.catalog.vehicles.singleOrNull { it.vehicleId == id }?.let { OnboardingCandidate(it, true) } }
    }
    private fun show(value: OnboardingSnapshot) {
        val state = value.initialization
        val source = label(value.identity.source)
        screen = when (val status = state.status) {
            is ProfileInitializationStatus.Unknown -> OnboardingScreenState.Unavailable("기존 프로필의 초기 지급 근거를 확인할 수 없습니다. 자동으로 다시 지급하지 않습니다")
            is ProfileInitializationStatus.Existing -> {
                uiState = uiState.copy(pending = null)
                val saved = status.original
                val car = saved?.grant?.changedVehicle
                if (saved == null || car == null || saved.command.profileId != value.identity.profileId || saved.command.grant.source != value.identity.source)
                    OnboardingScreenState.Unavailable("저장된 최초 지급 결과를 확인할 수 없습니다")
                else {
                    val name = (value.content as? ContractResult.Success)?.value?.catalog?.vehicles?.find { it.vehicleId == car.vehicleId }?.displayName ?: "저장된 첫 차량"
                    OnboardingScreenState.Completed(car, name,
                        listOf("첫 차량 지급 당시의 기록입니다", "최초 확정 게임머니 ${saved.grant.balance.units}", "최초 차량 공간 ${saved.garageCapacity}칸") + giftSummary(value.gift),
                        true, source)
                }
            }
            is ProfileInitializationStatus.New -> {
                val choices = candidates()
                val benefits = listOf("초기 게임머니 ${state.initialMoney.units}", "일반 희귀도의 첫 차량 1대", "차량 공간 ${state.initialGarageCapacity}칸", "개업 선물은 첫 영업 완료 뒤 지급됩니다")
                if (uiState.pending != null) OnboardingScreenState.GrantError(uiState.pending!!, null,
                    "이전 요청의 확정 여부를 다시 확인했습니다. 같은 선택으로 재시도해 주세요", true, false, source)
                else if (choices.isEmpty()) OnboardingScreenState.Unavailable("초기 차량 후보와 현재 콘텐츠를 확인할 수 없습니다")
                else {
                    if (uiState.selected != null && choices.none { it.vehicle.vehicleId == uiState.selected }) uiState = uiState.copy(step = OnboardingStep.CHOOSING, selected = null)
                    when (uiState.step) {
                        OnboardingStep.INTRO -> OnboardingScreenState.Intro(source, benefits)
                        OnboardingStep.CHOOSING -> OnboardingScreenState.Choosing(choices, source, benefits)
                        OnboardingStep.CONFIRMING -> choices.find { it.vehicle.vehicleId == uiState.selected }?.let { OnboardingScreenState.Confirming(it, source, benefits, true) }
                            ?: OnboardingScreenState.Choosing(choices, source, benefits)
                    }
                }
            }
        }
    }
    private fun giftSummary(gift: ContractResult<FirstRentalGiftState>?): List<String> = when (gift) {
        null -> listOf("개업 선물은 첫 영업 완료 뒤 확인합니다")
        is ContractResult.Failure -> listOf("개업 선물 확인 대기 · ${errorText(gift.error)}")
        is ContractResult.Success -> listOf(
            if (gift.value.granted != null) "개업 선물 1장 지급 기록을 확인했습니다" else "첫 영업 완료를 기다리고 있습니다",
            "현재 무료 뽑기권 ${gift.value.progress.freeTickets}장")
    }
    private fun errorText(error: ContractError) = when (error) {
        ContractError.PERMISSION_DENIED -> "필요한 접근 권한을 확인해 주세요."
        ContractError.DATA_UNAVAILABLE -> "필요한 정보가 아직 제공되지 않았습니다."
        ContractError.STORAGE_FAILURE -> "저장된 원본을 유지하고 다시 확인해 주세요."
        ContractError.STATE_CONFLICT, ContractError.INVALID_INPUT -> "프로필이나 요청 상태가 달라졌습니다."
        ContractError.POLICY_UNRESOLVED -> "처리 기준을 확인할 때까지 기다려 주세요."
        else -> "현재 상태에서는 요청을 처리할 수 없습니다."
    }
    private fun label(source: InputSource?) = when (source) { InputSource.VEHICLE -> "차량 모드 프로필"; InputSource.SIMULATION -> "모의 입력 프로필"; null -> "프로필 확인 중" }
    private fun publish() { listener?.invoke(screen, enabled && !released) }
    private fun <T> guarded(action: () -> ContractResult<T>): ContractResult<T> = try { action() }
        catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
        catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
}
