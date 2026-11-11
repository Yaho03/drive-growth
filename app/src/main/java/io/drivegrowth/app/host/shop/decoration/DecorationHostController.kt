package io.drivegrowth.app.host.shop.decoration

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import java.util.Collections
import java.util.concurrent.Executor

/** 공개 호출/changed/complete는 UI 한 스레드, backend는 기존 직렬 worker다. 수명/gate는 조립자가 공급한다. */
class DecorationHostController(
    private val identity: GameIdentity,
    private val backend: DecorationBackend,
    private val mapper: DecorationMapper,
    private val worker: Executor,
    private val reply: Executor,
    private val complete: (ShopRequest, ContractResult<Unit>) -> Unit,
    private val completeRetry: () -> Unit = {},
    initialDisplayRevision: Revision = Revision(0),
    restoredRequest: ShopRequest? = null,
) {
    @Volatile private var enabled = false
    @Volatile private var released = false
    @Volatile private var epoch = 0L
    @Volatile private var known: DecorationCommand? = null
    @Volatile private var original: DecorationSettlement? = null
    private var busy = false
    private var display = initialDisplayRevision.value
    private var facts: DecorationFacts? = null
    private var active = restoredRequest
    private var activeCommand: DecorationCommand? = null
    var changed: ((DecorationHostState, Boolean) -> Unit)? = null
    var state = DecorationHostState(displayRevision = initialDisplayRevision)
        private set

    init { require(display < Int.MAX_VALUE) }
    private data class Outcome(val facts: DecorationFacts? = null, val foreign: Boolean = false, val unsupported: Boolean = false)
    private fun fail(error: ContractError) = ContractResult.Failure(error)
    private fun own(command: DecorationCommand) = command.profileId == identity.profileId && command.source == identity.source
    private fun publish(value: DecorationHostState) { state = value; changed?.invoke(state, enabled && !released) }
    private fun available() = enabled && !released && !busy

    fun setControlsEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value; epoch++; facts = null
        publish(state.copy(screen = ShopScreenState.Loading, current = null))
        if (value && !busy) refresh()
    }
    fun release() { released = true; enabled = false; epoch++; changed = null }

    private fun matches(request: ShopRequest, command: DecorationCommand): Boolean =
        request.requestId == command.requestId && request.selection.option.price == command.expectedPrice &&
            request.selection.option.ruleVersion == command.ruleVersion &&
            (request.selection.option.command as? ShopCommand.Decorate)?.decorationId == command.decorationId

    private fun completeActive(error: ContractError?) {
        val request = active ?: return
        active = null
        val expected = activeCommand
        activeCommand = null
        val receipt = original
        complete(request, if (receipt != null && (if (expected != null) receipt.command == expected else matches(request, receipt.command))) ContractResult.Success(Unit)
            else fail(error ?: ContractError.UX_RESTRICTED))
    }

    private fun run(action: (() -> Boolean) -> ContractResult<Outcome>): Boolean {
        if (!available()) return false
        busy = true; val token = epoch; facts = null
        publish(state.copy(screen = ShopScreenState.Loading, current = null, busy = true, error = null))
        worker.execute {
            val valid = { enabled && !released && epoch == token }
            val result = if (valid()) action(valid) else fail(ContractError.UX_RESTRICTED)
            reply.execute reply@{
                if (released) return@reply
                busy = false
                if (epoch != token) {
                    publish(state.copy(screen = ShopScreenState.Loading, current = null, busy = false, pending = known, receipt = original))
                    if (enabled) refresh()
                    return@reply
                }
                val adopted = when (result) {
                    is ContractResult.Failure -> result
                    is ContractResult.Success -> adopt(result.value)
                }
                if (adopted is ContractResult.Failure) {
                    facts = null
                    publish(state.copy(screen = ShopScreenState.Failure(message(adopted.error)), current = null,
                        pending = known, receipt = original, error = adopted.error, busy = false))
                }
                if (enabled && !released && epoch == token) completeActive((adopted as? ContractResult.Failure)?.error)
                if (enabled && !released && epoch == token) completeRetry()
            }
        }
        return true
    }

    private fun adopt(outcome: Outcome): ContractResult<Unit> {
        if (outcome.foreign) {
            publish(DecorationHostState(state.displayRevision, ShopScreenState.Unsupported("다른 기능의 보관 요청을 먼저 확인해 주세요."),
                pending = known, receipt = original, foreign = true))
            return ContractResult.Success(Unit)
        }
        val next = outcome.facts ?: return fail(ContractError.DATA_UNAVAILABLE)
        if (display == Int.MAX_VALUE) return fail(ContractError.INVALID_INPUT)
        val revision = Revision(++display)
        val mapped = when (val result = mapper.map(next, revision)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val screen = if (outcome.unsupported) mapped.copy(decorations = Collections.unmodifiableList(mapped.decorations.map {
            it.copy(availability = ShopAvailability.UNKNOWN, command = null)
        })) else mapped
        facts = next
        publish(DecorationHostState(revision, screen, next.state, known, original, unsupported = outcome.unsupported))
        return ContractResult.Success(Unit)
    }

    private fun read(valid: () -> Boolean, unsupported: Boolean = false): ContractResult<Outcome> {
        if (!valid()) return fail(ContractError.UX_RESTRICTED)
        return when (val result = backend.read()) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> {
                val value = result.value
                if (value.state.profileId != identity.profileId || value.state.source != identity.source) fail(ContractError.STATE_CONFLICT)
                else ContractResult.Success(Outcome(value.copy(shop = value.shop.copy(
                    vehicles = Collections.unmodifiableList(ArrayList(value.shop.vehicles)),
                    facilities = Collections.unmodifiableList(ArrayList(value.shop.facilities)),
                    decorations = Collections.unmodifiableList(ArrayList(value.shop.decorations)))), unsupported = unsupported))
            }
        }
    }

    fun refresh(): Boolean = run { valid ->
        when (val result = backend.pending()) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> when (val pending = result.value) {
                is DecorationPending.Own -> if (!own(pending.command) || (known != null && known != pending.command)) fail(ContractError.STATE_CONFLICT)
                    else { known = pending.command; execute(pending.command, valid) }
                DecorationPending.Foreign -> if (known != null) fail(ContractError.STATE_CONFLICT) else ContractResult.Success(Outcome(foreign = true))
                DecorationPending.Empty, DecorationPending.Unsupported -> if (known != null) fail(ContractError.STORAGE_FAILURE)
                    else read(valid, pending == DecorationPending.Unsupported)
            }
        }
    }

    fun submit(request: ShopRequest): Boolean {
        if (released || (busy && active == request)) return false
        val current = facts
        if (!available() || current == null || known != null || state.unsupported || state.foreign || state.error != null) {
            complete(request, fail(if (!enabled) ContractError.UX_RESTRICTED else ContractError.STATE_CONFLICT)); return false
        }
        val command = when (val checked = mapper.command(current, state.displayRevision, request)) {
            is ContractResult.Failure -> { complete(request, checked); return false }
            is ContractResult.Success -> checked.value
        }
        active = request
        activeCommand = command
        return run { valid ->
            known = command
            when (val saved = backend.save(command)) {
                is ContractResult.Failure -> {
                    if (saved.error !in setOf(ContractError.STORAGE_FAILURE, ContractError.DATA_UNAVAILABLE, ContractError.PERMISSION_DENIED)) known = null
                    saved
                }
                is ContractResult.Success -> execute(command, valid)
            }
        }
    }

    private fun execute(command: DecorationCommand, valid: () -> Boolean): ContractResult<Outcome> {
        if (!valid()) return fail(ContractError.UX_RESTRICTED)
        when (val result = backend.execute(command)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> when (val checked = replayDecorationChange(command, result.value)) {
                is ContractResult.Failure -> return checked
                is ContractResult.Success -> original = result.value
            }
        }
        if (!valid()) return fail(ContractError.UX_RESTRICTED)
        when (val finished = backend.finish(command)) {
            is ContractResult.Failure -> return finished
            is ContractResult.Success -> known = null
        }
        return read(valid)
    }

    /** 명시적으로 시작할 때만 호출한다. 조회/미리보기/취소에서 activate를 호출하지 않는다. */
    fun activate(): Boolean {
        if (known != null || state.foreign || state.unsupported) return false
        return run { valid -> when (val result = backend.activate()) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> read(valid)
        } }
    }

    fun cancelSaved(): Boolean {
        val command = known ?: return false
        if (original?.command == command) return false
        return run { valid -> when (val result = backend.cancelSaved(command)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> { known = null; read(valid) }
        } }
    }

    private fun message(error: ContractError): String = when (error) {
        ContractError.INSUFFICIENT_FUNDS -> "잔액이 부족해요. 현재 상태를 다시 확인해 주세요."
        ContractError.STATE_CONFLICT -> "표시 내용이 바뀌었어요. 현재 상태를 다시 확인해 주세요."
        ContractError.UX_RESTRICTED -> "주차 후 조작이 허용되면 다시 확인해 주세요."
        else -> "꾸미기 상태를 확인하지 못했어요. 보관한 요청과 현재 상태를 다시 확인해 주세요."
    }
}
