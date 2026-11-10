package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*
import java.util.concurrent.Executor

/** UI 조립만 소유한다. 모든 journal/source 호출은 worker이며 복구 중 새 추첨을 허용하지 않는다. */
class GachaLiveHost(private val source: CheckpointGachaSource, private val worker: Executor,
    private val main: Executor, private val changed: () -> Unit) {
    private var render: ((GachaFrame, Boolean) -> Unit)? = null
    private var complete: ((GachaRequest, ContractResult<Unit>) -> Unit)? = null
    private var completeReload: ((String) -> Unit)? = null
    private var resolve: ((RequestId) -> Unit)? = null
    @Volatile private var enabled = false
    @Volatile private var released = false
    private var busy = false
    private var generation = 0L
    private var frame = GachaFrame(0, GachaScreen.Loading)
    private var controller: GachaHostController? = null
    private var original: GachaCommand? = null
    private var request: GachaRequest? = null
    var uiState = GachaUiState()
        private set
    val failed: Boolean get() = frame.screen is GachaScreen.Failure
    fun remember(value: GachaUiState) { uiState = value }
    fun attach(render: (GachaFrame, Boolean) -> Unit, complete: (GachaRequest, ContractResult<Unit>) -> Unit,
        completeReload: (String) -> Unit, resolve: (RequestId) -> Unit) {
        detach(); this.render = render; this.complete = complete; this.completeReload = completeReload; this.resolve = resolve
        render(frame, false)
    }
    fun detach() {
        enabled = false; controller?.setControlsEnabled(false)
        render = null; complete = null; completeReload = null; resolve = null
        val memory = GachaInteraction({}, uiState)
        uiState.garagePending?.let(memory::completeGarageNavigation)
        uiState = memory.uiState
    }
    fun release() { released = true; detach(); controller?.release() }
    fun setControlsEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value; controller?.setControlsEnabled(value)
        render?.invoke(frame, value)
        if (value && controller == null && !busy && frame.screen !is GachaScreen.Result) refresh()
    }
    fun draw(value: GachaRequest): Boolean {
        val host = controller
        if (released || !enabled || busy || host == null || host.isBusy || value.generation != frame.generation) {
            deliverComplete(value, ContractResult.Failure(if (!enabled) ContractError.UX_RESTRICTED else ContractError.STATE_CONFLICT)); return false
        }
        request = value
        return host.draw(value.copy(generation = host.snapshot().generation))
    }
    fun refresh(token: String? = null) {
        if (released || !enabled || busy || controller?.isBusy == true) { token?.let { deliverReload(it) }; return }
        if (frame.screen is GachaScreen.Result) {
            acknowledge { if (it is ContractResult.Success) load(token) else token?.let { key -> deliverReload(key) } }
        } else load(token)
    }
    private fun load(token: String?) {
        if (released || !enabled) { token?.let { deliverReload(it) }; return }
        val expected = original ?: controller?.snapshot()?.pending?.command
        original = expected
        controller?.release(); controller = null; busy = true
        show(GachaScreen.Loading)
        worker.execute {
            val pending = safe { source.pending() }
            val result: ContractResult<GachaSettlement?> = when (pending) {
                is ContractResult.Failure -> pending
                is ContractResult.Success -> if (expected != null && pending.value != expected) ContractResult.Failure(ContractError.STORAGE_FAILURE)
                    else if (pending.value == null) ContractResult.Success(null)
                    else if (!enabled || released) ContractResult.Failure(ContractError.UX_RESTRICTED)
                    else safe { source.commit(pending.value!!) }
            }
            main.execute {
                busy = false
                if (!released) {
                    when (result) {
                        is ContractResult.Failure -> failure(result.error)
                        is ContractResult.Success -> if (result.value != null) {
                            original = result.value!!.command
                            when (val mapped = storedGachaResult(result.value!!.command, result.value!!)) {
                                is ContractResult.Failure -> failure(mapped.error)
                                is ContractResult.Success -> {
                                    request?.let { deliverComplete(it, ContractResult.Success(Unit)) }
                                    show(mapped.value); changed()
                                }
                            }
                        } else {
                            original = null
                            request?.let { previous ->
                                deliverComplete(previous, ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
                                previous.input.option.requestId?.let { deliverResolve(it) }
                            }
                            request = null
                            val host = GachaHostController(source, { worker.execute(it) }, { main.execute(it) },
                                { ContractResult.Success(Unit) }, { value, _ -> show(value.screen); if (value.screen is GachaScreen.Result) changed() },
                                { value, outcome -> deliverComplete(request?.takeIf { it.attemptId == value.attemptId } ?: value, outcome) },
                                { key -> deliverReload(key) })
                            controller = host; host.setControlsEnabled(enabled)
                            if (enabled) host.load(token ?: "host-load")
                        }
                    }
                    if (result is ContractResult.Failure || result is ContractResult.Success && result.value != null)
                        token?.let { deliverReload(it) }
                }
            }
        }
    }
    /** 실제 결과를 본 뒤 이동/새 조회를 선택했을 때만 finish한다. 실패/응답 유실은 보존한다. */
    fun acknowledge(done: (ContractResult<Unit>) -> Unit) {
        if (released || !enabled || busy || frame.screen !is GachaScreen.Result) {
            done(ContractResult.Failure(ContractError.STATE_CONFLICT)); return
        }
        val command = original ?: controller?.snapshot()?.pending?.command
        if (command == null) { done(ContractResult.Failure(ContractError.DATA_UNAVAILABLE)); return }
        val displayed = (frame.screen as GachaScreen.Result).value.settlement
        busy = true
        worker.execute {
            val result = if (!enabled || released) ContractResult.Failure(ContractError.UX_RESTRICTED) else safe { source.finish(command) }
            main.execute {
                busy = false
                if (!released) when (result) {
                    is ContractResult.Failure -> { failure(result.error); done(result) }
                    is ContractResult.Success -> if (result.value.command != command || result.value.settlement != displayed) {
                        failure(ContractError.STORAGE_FAILURE); done(ContractResult.Failure(ContractError.STORAGE_FAILURE))
                    } else {
                        original = null; controller?.release(); controller = null; request = null
                        show(GachaScreen.Missing("확인한 지급 결과는 저장돼 있어요. 새 조회로 다음 뽑기 조건을 확인해 주세요.", true))
                        done(ContractResult.Success(Unit))
                    }
                }
            }
        }
    }
    /** SAVED만 기존 ledger가 취소한다. STARTED/불명 결과는 명시 취소도 거절하고 보존한다. */
    fun cancelUnstarted() {
        if (released || !enabled || busy || controller?.isBusy == true || !failed) return
        val expected = original ?: controller?.snapshot()?.pending?.command
        busy = true
        worker.execute {
            val result = if (!enabled || released) ContractResult.Failure(ContractError.UX_RESTRICTED) else safe {
                when (val saved = source.pending()) {
                    is ContractResult.Failure -> saved
                    is ContractResult.Success -> if (expected != null && saved.value != expected) ContractResult.Failure(ContractError.STORAGE_FAILURE)
                        else saved.value?.let { source.cancelUnstarted(it) } ?: ContractResult.Success(Unit)
                }
            }
            main.execute {
                busy = false
                if (!released) when (result) {
                    is ContractResult.Failure -> failure(result.error)
                    is ContractResult.Success -> { original = null; controller?.release(); controller = null; load(null) }
                }
            }
        }
    }
    private fun show(value: GachaScreen) {
        if (released) return
        frame = GachaFrame(++generation, value)
        val memory = GachaInteraction({}, uiState); memory.render(frame, enabled); uiState = memory.uiState
        render?.invoke(frame, enabled)
    }
    private fun deliverComplete(value: GachaRequest, result: ContractResult<Unit>) {
        val memory = GachaInteraction({}, uiState); memory.complete(value, result); uiState = memory.uiState
        complete?.invoke(value, result)
    }
    private fun deliverReload(token: String) {
        val memory = GachaInteraction({}, uiState); memory.completeReload(token); uiState = memory.uiState
        completeReload?.invoke(token)
    }
    private fun deliverResolve(key: RequestId) {
        val memory = GachaInteraction({}, uiState); memory.resolveFailedRequest(key); uiState = memory.uiState
        resolve?.invoke(key)
    }
    private fun failure(error: ContractError) = show(GachaScreen.Failure(error,
        "지급 결과를 확인하지 못했어요. 보관한 원래 요청으로 다시 확인해 주세요. 다른 관리 요청이 보관 중이면 차량 관리에서 확인해 주세요.", true))
}
private fun <T> safe(action: () -> ContractResult<T>): ContractResult<T> = try { action() }
    catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
    catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
