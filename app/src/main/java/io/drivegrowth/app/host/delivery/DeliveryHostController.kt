package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import java.util.concurrent.Executor

/** 공개 메서드/표시는 UI thread, source는 직렬 worker에서 호출한다. */
class DeliveryHostController(private val source: DeliveryHostSource, private val worker: Executor, private val main: Executor) {
    private var render: ((DeliveryFrame, Boolean) -> Unit)? = null
    private var complete: ((DeliveryRequest, DeliveryFrame) -> Unit)? = null
    private var onDiscoverySaved: (() -> Unit)? = null
    private var onOriginalResolved: (() -> Unit)? = null
    private var pendingDiscovery: DeliveryHostRead? = null
    @Volatile private var allowed = false
    @Volatile private var released = false
    @Volatile private var epoch = 0L
    private var runningEpoch = 0L
    private var busy = false
    private var version = 0L
    private var frame = DeliveryFrame(0, DeliveryScreen.Loading)
    @Volatile private var identity: GameIdentity? = null
    @Volatile private var original: DeliveryHostOperation? = null
    @Volatile private var confirmed = false
    private var request: DeliveryRequest? = null
    var state: DeliveryHostRead? = null
        private set

    fun attach(render: (DeliveryFrame, Boolean) -> Unit, complete: (DeliveryRequest, DeliveryFrame) -> Unit,
        onDiscoverySaved: () -> Unit = {}, onOriginalResolved: () -> Unit = {}) {
        detach(); this.render = render; this.complete = complete; this.onDiscoverySaved = onDiscoverySaved
        this.onOriginalResolved = onOriginalResolved
        render(frame, false)
    }
    fun detach() { setControlsEnabled(false); render = null; complete = null; onDiscoverySaved = null; onOriginalResolved = null }
    fun release() { released = true; allowed = false; epoch++; render = null; complete = null; onDiscoverySaved = null; onOriginalResolved = null }
    fun setControlsEnabled(value: Boolean) {
        if (released || value == allowed) return
        allowed = value
        if (!value) {
            epoch++
            if (isSavingDisplayedOffers) {
                show(DeliveryScreen.Loading)
                return
            }
        }
        render?.invoke(frame, value && !busy)
        if (value && request != null && !busy) refresh()
    }
    fun refresh() {
        val discovery = pendingDiscovery
        if (discovery == null) launch { recover() }
        else launch(savingDiscovery = true) { saveDiscovery(discovery) }
    }
    private fun recover(): ContractResult<DeliveryHostRead> =
        when (val read = source.read()) {
            is ContractResult.Failure -> read
            is ContractResult.Success -> {
                val current = read.value
                when {
                    !inCurrentWork() -> ContractResult.Failure(ContractError.UX_RESTRICTED)
                    !bind(current.identity) -> ContractResult.Failure(ContractError.STATE_CONFLICT)
                    original != null && current.pending != original -> ContractResult.Failure(ContractError.STORAGE_FAILURE)
                    current.pending == null -> read
                    else -> {
                        if (!inCurrentWork()) ContractResult.Failure(ContractError.UX_RESTRICTED)
                        else {
                            original = current.pending
                            source.execute(current.identity, current.pending).also { if (it is ContractResult.Success) confirmed = true }
                        }
                    }
                }
            }
        }
    val canCancelUnstarted: Boolean get() = original != null && !confirmed && frame.screen is DeliveryScreen.Failure
    fun cancelUnstarted() {
        val command = original ?: return
        if (confirmed) return
        resolveOriginal { source.cancelUnstarted(identity!!, command) }
    }
    val canAcknowledge: Boolean get() = valid() && !busy && confirmed && original != null
    fun acknowledge() {
        if (!canAcknowledge) return
        val command = original ?: return
        resolveOriginal { source.finish(identity!!, command) }
    }
    private fun resolveOriginal(resolve: () -> ContractResult<Unit>) {
        val discovery = pendingDiscovery
        launch(preservingDiscovery = discovery != null) {
            when (val result = resolve()) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> {
                    originalResolved()
                    // 새 후보를 읽으면 일시 실패한 원 표시의 저장 근거가 교체된다.
                    if (discovery == null) recover()
                    else ContractResult.Success(discovery.copy(pending = null, receipt = null))
                }
            }
        }
    }
    private fun originalResolved() {
        original = null
        confirmed = false
        // 슬롯 해제는 후속 탁송 조회 결과와 별개다. 공통 요청 표시는 Main에서 원장을 다시 읽는다.
        main.execute { if (!released) onOriginalResolved?.invoke() }
    }
    fun onRequest(value: DeliveryRequest) {
        if (released || busy || identity == null) return
        val input = DeliveryInteraction(DeliveryInteractionState(frame = frame))
        input.render(frame, true)
        if (value.frameVersion != frame.version || !input.canRequest(value.command)) return
        if (!allowed) {
            show(DeliveryScreen.Failure(message(ContractError.UX_RESTRICTED), true), value)
            return
        }
        // UI 전체 입력은 기존 Interaction의 snapshot 경계를 재사용한다.
        request = DeliveryInteraction(DeliveryInteractionState(pending = value)).pending!!
        val command = request!!.command
        if (command == DeliveryCommand.Refresh) { refresh(); return }
        launch {
            val profile = identity!!
            val previous = original
            if (previous != null) {
                if (!confirmed) return@launch ContractResult.Failure(ContractError.STATE_CONFLICT)
                when (val finished = source.finish(profile, previous)) {
                    is ContractResult.Failure -> return@launch finished
                    is ContractResult.Success -> originalResolved()
                }
            }
            if (!inCurrentWork()) return@launch ContractResult.Failure(ContractError.UX_RESTRICTED)
            when (val prepared = source.prepare(profile, command)) {
                is ContractResult.Failure -> prepared
                is ContractResult.Success -> {
                    val operation = prepared.value.operation
                    if (operation == null) ContractResult.Success(DeliveryHostRead(profile, prepared.value.screen, state?.held.orEmpty(), navigation = prepared.value.navigation))
                    else {
                        if (!inCurrentWork()) ContractResult.Failure(ContractError.UX_RESTRICTED)
                        else {
                            original = operation
                            source.execute(profile, operation).also { if (it is ContractResult.Success) confirmed = true }
                        }
                    }
                }
            }
        }
    }
    val isSavingDisplayedOffers: Boolean get() = busy && frame.screen is DeliveryScreen.Offers

    /** 기존 View의 실제 render 성공 뒤 같은 frame을 한 번 확인한다. 생성만 한 후보는 기록하지 않는다. */
    fun acknowledgeDisplayed(frameVersion: Long) {
        val current = state ?: return
        if (!valid() || busy || pendingDiscovery != null || current.displayedProposal == null) return
        if (frameVersion != frame.version || frame.screen !is DeliveryScreen.Offers) return
        pendingDiscovery = current
        refresh()
    }
    private fun saveDiscovery(current: DeliveryHostRead): ContractResult<DeliveryHostRead> {
        val proposal = requireNotNull(current.displayedProposal)
        return when (val saved = source.displayed(current.identity, proposal)) {
            is ContractResult.Failure -> saved
            is ContractResult.Success -> ContractResult.Success(current.copy(displayedProposal = null))
        }
    }
    private fun bind(value: GameIdentity): Boolean {
        val current = identity
        if (current == null) identity = value
        return current == null || current == value
    }
    private fun valid() = !released && allowed
    private fun inCurrentWork() = valid() && epoch == runningEpoch
    private fun launch(
        savingDiscovery: Boolean = false,
        preservingDiscovery: Boolean = false,
        action: () -> ContractResult<DeliveryHostRead>,
    ) {
        if (!valid() || busy) return
        val token = epoch
        val keepDisplayedOffers = savingDiscovery && frame.screen is DeliveryScreen.Offers
        busy = true
        // 자동 발견 저장은 현재 후보를 바꾸지 않는다. 상세는 유지하고 확정은 응답까지 잠근다.
        if (keepDisplayedOffers) render?.invoke(frame, allowed)
        else show(DeliveryScreen.Loading)
        worker.execute {
            runningEpoch = token
            val result = if (!inCurrentWork()) null else try {
                action()
            } catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
                catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
            main.execute {
                busy = false
                if (released) return@execute
                val discoveryInvalid = savingDiscovery && result is ContractResult.Failure &&
                    (result.error == ContractError.STATE_CONFLICT || result.error == ContractError.INVALID_INPUT)
                // 저장 결과는 화면 세대가 달라져도 반영하고, 이전 화면은 아래에서 폐기한다.
                if (savingDiscovery && result is ContractResult.Success) {
                    pendingDiscovery = null
                    onDiscoverySaved?.invoke()
                } else if (discoveryInvalid) {
                    pendingDiscovery = null
                } else if (preservingDiscovery && result is ContractResult.Success) {
                    pendingDiscovery = result.value
                    state = result.value
                }
                if (epoch != token || result == null) {
                    // 옛 gate 세대의 응답은 표시하지 않고, 재허용 상태라면 원장에서 새로 읽는다.
                    if (allowed) refresh()
                    return@execute
                }
                when (result) {
                    is ContractResult.Failure -> {
                        val text = when {
                            discoveryInvalid -> "표시한 목록으로 발견을 저장할 수 없어요. 후보를 다시 조회해 주세요."
                            savingDiscovery -> "발견을 저장하지 못했어요. 표시한 목록으로 다시 시도해 주세요."
                            else -> message(result.error)
                        }
                        show(DeliveryScreen.Failure(text, true), request)
                    }
                    is ContractResult.Success -> if (preservingDiscovery)
                        show(DeliveryScreen.Failure("처리 결과를 확인했어요. 표시한 목록의 발견 저장을 다시 시도해 주세요.", true), request)
                    else if (!bind(result.value.identity))
                        show(DeliveryScreen.Failure("다른 프로필의 응답을 사용할 수 없어요.", true), request)
                    else {
                        state = result.value
                        if (keepDisplayedOffers) render?.invoke(frame, allowed)
                        else show(result.value.screen, request)
                    }
                }
                request = null
            }
        }
    }
    private fun show(screen: DeliveryScreen, response: DeliveryRequest? = null) {
        frame = DeliveryFrame(++version, screen)
        if (response != null) complete?.invoke(response, frame)
        render?.invoke(frame, allowed && !busy)
    }
    private fun message(error: ContractError) = when (error) {
        ContractError.INSUFFICIENT_FUNDS -> "게임머니가 부족해요. 원래 의뢰와 결제 상태를 확인해 주세요."
        ContractError.CAPACITY_EXCEEDED -> "진행 중인 의뢰나 차고 공간을 확인해 주세요."
        ContractError.UX_RESTRICTED -> "주차 후 조작이 허용되면 다시 확인해 주세요."
        ContractError.DATA_UNAVAILABLE -> "탁송에 필요한 정보를 확인할 수 없어요. 원래 요청은 유지돼요."
        ContractError.STATE_CONFLICT -> "확인한 조건이 달라졌어요. 원래 요청의 결과를 다시 확인해 주세요."
        else -> "탁송 결과를 확인하지 못했어요. 보관한 원래 요청으로 다시 확인해 주세요."
    }
}
