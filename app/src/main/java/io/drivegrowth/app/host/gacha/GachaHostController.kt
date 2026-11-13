package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*
import java.util.UUID

/** command를 포함한 보존 입출력. 실제 디스크 직렬화/프로필 선택은 조립자가 제공한다. */
data class GachaHostPending(val request: GachaRequest, val command: GachaCommand?)
data class GachaHostState(val generation: Long = 0, val pending: GachaHostPending? = null) {
    init { require(generation >= 0 && generation < Long.MAX_VALUE) }
}

/**
 * 공개 메서드·reply/checkpoint/render는 같은 UI 스레드, source는 worker에서 호출한다.
 * checkpoint 성공은 조립자가 요구하는 수명 범위에 전체 상태를 보존했다는 응답이다. 실제 프로세스 영속화를 대신하지 않는다.
 * 명령 보존 성공 전에 commit하지 않는다. 작업을 시작한 뒤 제한돼도 이미 확정된 결과는 보존하되 gate를 풀지 않는다.
 */
class GachaHostController(
    private val source: GachaHostSource,
    private val worker: (() -> Unit) -> Unit,
    private val reply: (() -> Unit) -> Unit,
    private val checkpoint: (GachaHostState) -> ContractResult<Unit>,
    private val render: (GachaFrame, Boolean) -> Unit,
    private val complete: (GachaRequest, ContractResult<Unit>) -> Unit,
    private val completeReload: (String) -> Unit,
    restored: GachaHostState = GachaHostState(),
) {
    private var saved = restored.frozen()
    @Volatile private var allowed = false
    @Volatile private var released = false
    private var busy = false
    private var sequence = 0L
    private var confirmed = false
    private var frame = GachaFrame(saved.generation, GachaScreen.Missing("가챠 상태를 확인해 주세요.", true))

    val isBusy: Boolean get() = busy
    fun snapshot(): GachaHostState = saved.frozen()
    fun setControlsEnabled(enabled: Boolean) { if (!released) { allowed = enabled; render(frame, allowed) } }
    fun release() { released = true; allowed = false; sequence++ }

    /** 미확정 명령이 있으면 새 키를 만들지 않고 같은 명령을 재제출한다. */
    fun load(token: String = UUID.randomUUID().toString()): Boolean {
        if (saved.pending != null && !confirmed) return retry(token)
        if (released || !allowed || busy) return false
        saved = saved.copy(pending = null)
        if (checkpoint(saved) is ContractResult.Failure) { showFailure(ContractError.STORAGE_FAILURE); return false }
        confirmed = false; busy = true
        val serial = ++sequence
        show(GachaScreen.Loading)
        val paid = RequestId(UUID.randomUUID().toString()); val free = RequestId(UUID.randomUUID().toString())
        worker {
            val result = safely { source.read(paid, free) }
            reply {
                if (!released && sequence == serial) {
                    busy = false
                    when (result) {
                        is ContractResult.Success -> show(GachaScreen.Offers(result.value))
                        is ContractResult.Failure -> showFailure(result.error, result.detail)
                    }
                    completeReload(token)
                }
            }
        }
        return true
    }

    fun draw(request: GachaRequest): Boolean {
        if (released || !allowed || busy || confirmed) return false
        val fixed = request.frozen()
        val currentOffers = (frame.screen as? GachaScreen.Offers)?.value
        if (currentOffers != null && (fixed.generation != frame.generation || fixed.input.offers != currentOffers || fixed.input.option !in currentOffers.options)) {
            complete(fixed, ContractResult.Failure(ContractError.STATE_CONFLICT)); return false
        }
        val prior = saved.pending
        if (prior != null && prior.command == null) {
            complete(fixed, ContractResult.Failure(ContractError.DATA_UNAVAILABLE)); return false
        }
        if (prior != null && prior.request.input != fixed.input) {
            complete(fixed, ContractResult.Failure(ContractError.STATE_CONFLICT)); return false
        }
        if (fixed.input.offers.displayProblem() != null || fixed.input.option.displayProblem() != null) {
            complete(fixed, ContractResult.Failure(ContractError.INVALID_INPUT)); return false
        }
        saved = saved.copy(pending = GachaHostPending(fixed, prior?.command))
        if (checkpoint(saved) is ContractResult.Failure) { fail(fixed, ContractError.STORAGE_FAILURE, null); return false }
        run(fixed, null)
        return true
    }

    fun retry(token: String): Boolean {
        if (released || !allowed || busy) return false
        val pending = saved.pending ?: return load(token)
        if (pending.command == null) { fail(pending.request, ContractError.DATA_UNAVAILABLE, token); return false }
        run(pending.request, token)
        return true
    }

    /**
     * 명령이 한 번도 보존되지 않아 commit이 시작되지 않은 요청만 명시적으로 해소한다.
     * 반환 키는 조립자가 GachaView.resolveFailedRequest에 전달한 뒤 새 load를 수행한다.
     * 명령이 있거나 실행 중이면 원래 결과가 불명일 수 있으므로 해소하지 않는다.
     */
    fun clearUnprepared(): RequestId? {
        if (released || busy) return null
        val pending = saved.pending ?: return null
        if (pending.command != null) return null
        val cleared = saved.copy(pending = null)
        if (checkpoint(cleared) is ContractResult.Failure) return null
        saved = cleared
        complete(pending.request, ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
        return pending.request.input.option.requestId
    }

    private fun run(request: GachaRequest, reload: String?) {
        busy = true
        val serial = ++sequence
        val command = saved.pending!!.command
        if (command != null) { commit(serial, request, command, reload); return }
        worker {
            val result = if (!allowed || released) ContractResult.Failure(ContractError.UX_RESTRICTED) else safely { source.prepare(request.input) }
            reply {
                if (!released && sequence == serial) when (result) {
                    is ContractResult.Failure -> fail(request, result.error, reload, result.detail)
                    is ContractResult.Success -> {
                        saved = saved.copy(pending = GachaHostPending(request, result.value))
                        if (checkpoint(saved) is ContractResult.Failure) fail(request, ContractError.STORAGE_FAILURE, reload)
                        else if (!allowed) fail(request, ContractError.UX_RESTRICTED, reload)
                        else commit(serial, request, result.value, reload)
                    }
                }
            }
        }
    }

    private fun commit(serial: Long, request: GachaRequest, command: GachaCommand, reload: String?) {
        if (!matches(request.input, command)) { fail(request, ContractError.STATE_CONFLICT, reload); return }
        // 복원/이전 checkpoint 실패 뒤 재시도에서도 보존을 다시 확인한다.
        if (checkpoint(saved) is ContractResult.Failure) { fail(request, ContractError.STORAGE_FAILURE, reload); return }
        worker {
            val result = if (!allowed || released) ContractResult.Failure(ContractError.UX_RESTRICTED) else safely { source.commit(command) }
            reply {
                if (!released && sequence == serial) {
                    when (result) {
                        is ContractResult.Failure -> fail(request, result.error, reload, result.detail)
                        is ContractResult.Success -> when (val mapped = resultFrame(request.input, command, result.value)) {
                            is ContractResult.Failure -> fail(request, mapped.error, reload)
                            is ContractResult.Success -> {
                                busy = false; confirmed = true
                                complete(request, ContractResult.Success(Unit))
                                show(mapped.value)
                                reload?.let(completeReload)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fail(request: GachaRequest, error: ContractError, reload: String?, detail: ContractFailureDetail? = null) {
        busy = false
        complete(request, ContractResult.Failure(error, detail))
        showFailure(error, detail)
        reload?.let(completeReload)
    }
    private fun showFailure(error: ContractError, detail: ContractFailureDetail? = null) {
        val message = when (error) {
            ContractError.DATA_UNAVAILABLE -> if (detail == ContractFailureDetail.GACHA_NO_UNLOCKED_CANDIDATES)
                "현재 해금 후보가 없습니다." else "가챠 권위 상태나 진행 정보를 확인할 수 없습니다."
            ContractError.CAPACITY_EXCEEDED -> "차고 공간이 부족합니다."
            ContractError.INSUFFICIENT_FUNDS -> if (saved.pending?.request?.input?.option?.kind == GachaOptionKind.FREE) "무료권이 부족합니다." else "게임머니가 부족합니다."
            ContractError.STATE_CONFLICT -> "확인한 조건이 달라졌습니다. 보관한 요청의 결과를 확인해 주세요."
            ContractError.POLICY_UNRESOLVED -> "가챠 조건을 확인할 수 없습니다."
            ContractError.UX_RESTRICTED -> "현재 가챠 조작이 제한됩니다."
            else -> "결과를 확인하지 못했습니다. 같은 요청으로 다시 확인해 주세요."
        }
        show(if (error == ContractError.DATA_UNAVAILABLE) GachaScreen.Missing(message, true) else GachaScreen.Failure(error, message, true))
    }
    private fun show(screen: GachaScreen) { if (released) return; saved = saved.copy(generation = Math.addExact(saved.generation, 1)); frame = GachaInteraction({}).let { ui -> ui.render(GachaFrame(saved.generation, screen), false); ui.frame!! }; render(frame, allowed) }
}

private fun GachaRequest.frozen(): GachaRequest = GachaInteraction({}, GachaUiState(pending = this)).uiState.pending!!
private fun GachaHostState.frozen() = copy(pending = pending?.let { it.copy(request = it.request.frozen()) })
private fun <T> safely(action: () -> ContractResult<T>): ContractResult<T> = try { action() }
    catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }

/** 복원/비동기 공급 오류가 확인한 경제 키·가격·문맥을 다른 명령에 연결하지 못하게 한다. 재추첨하지 않는다. */
private fun matches(input: GachaDrawInput, command: GachaCommand): Boolean {
    val offers = input.offers
    val content = offers.content ?: return false
    if (offers.displayProblem() != null || input.option.displayProblem() != null || input.option !in offers.options) return false
    val ticket = if (input.option.kind == GachaOptionKind.PAID) GachaTicketUse.PURCHASED else GachaTicketUse.FREE
    return command.requestId == input.option.requestId && command.ticket == ticket &&
        command.contentVersion == content.catalog.contentVersion && command.ruleVersion == content.ruleVersion &&
        command.award.vehicle.acquisitionCost == input.option.moneyCost && command.garageCapacity == offers.space?.capacity &&
        command.drawsSinceGuarantee == content.gacha.pity - (offers.guarantee?.remaining ?: return false) &&
        command.award.guaranteed == offers.guarantee.nextGuaranteed && command.award.vehicle.vehicleId in (offers.candidates ?: return false)
}
