package io.drivegrowth.app.simulation

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult

/** true는 전체 입력 완료, false는 현재 거래를 마친 뒤의 일시 중지다. */
internal typealias RestDemoOperation = ((RestSimulationScenario.Phase) -> Unit, () -> Boolean) -> ContractResult<Boolean>

/** 전용 worker에서만 호출한다. Activity를 다시 열어도 같은 원입력과 결과 읽기 경계를 유지한다. */
internal class RestSimulationWorkflow<T> {
    enum class Action { READ, START, RETRY_INPUT }

    sealed interface Screen<out T> {
        data object Ready : Screen<Nothing>
        data object Paused : Screen<Nothing>
        data class Saved<T>(val result: T) : Screen<T>
        data class Failure(val error: ContractError, val next: Action) : Screen<Nothing>
    }

    private var pending: RestDemoOperation? = null
    private var pendingError: ContractError? = null
    private var committed = false

    fun execute(
        action: Action,
        read: () -> ContractResult<T?>,
        prepare: () -> ContractResult<RestDemoOperation>,
        onPhase: (RestSimulationScenario.Phase) -> Unit = {},
        canContinue: () -> Boolean = { true },
    ): Screen<T> {
        val operation = pending
        if (operation != null) {
            if (action != Action.RETRY_INPUT) return pendingError?.let { Screen.Failure(it, Action.RETRY_INPUT) } ?: Screen.Paused
            return run(operation, read, onPhase, canContinue)
        }
        val stored = read()
        if (stored is ContractResult.Failure) return Screen.Failure(stored.error, Action.READ)
        val result = (stored as ContractResult.Success).value
        if (result != null) {
            committed = true
            return Screen.Saved(result)
        }
        if (committed) return Screen.Failure(ContractError.STORAGE_FAILURE, Action.READ)
        if (action != Action.START || !canContinue()) return Screen.Ready
        return when (val prepared = prepare()) {
            is ContractResult.Failure -> Screen.Failure(prepared.error, Action.START)
            is ContractResult.Success -> run(prepared.value, read, onPhase, canContinue)
        }
    }

    private fun run(
        operation: RestDemoOperation,
        read: () -> ContractResult<T?>,
        onPhase: (RestSimulationScenario.Phase) -> Unit,
        canContinue: () -> Boolean,
    ): Screen<T> = when (val result = operation(onPhase, canContinue)) {
        is ContractResult.Failure -> {
            pending = operation
            pendingError = result.error
            Screen.Failure(result.error, Action.RETRY_INPUT)
        }
        is ContractResult.Success -> {
            if (!result.value) {
                pending = operation
                pendingError = null
                Screen.Paused
            } else {
                pending = null
                pendingError = null
                committed = true
                when (val saved = read()) {
                    is ContractResult.Failure -> Screen.Failure(saved.error, Action.READ)
                    is ContractResult.Success -> saved.value?.let { Screen.Saved(it) }
                        ?: Screen.Failure(ContractError.STORAGE_FAILURE, Action.READ)
                }
            }
        }
    }
}
