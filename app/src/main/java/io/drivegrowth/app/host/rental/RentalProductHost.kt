package io.drivegrowth.app.host.rental

import android.util.Log
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.core.contract.ContractResult
import java.util.concurrent.Executor

/** source는 같은 worker, UI는 실제 receiver가 일치할 때만 응답한다. receiver는 boot 식별자가 아니다. */
class RentalProductHost(private val worker: Executor, private val main: Executor,
    private val observeSource: (DrivingFrame, ContractResult<DrivingRecordingResult>) -> ContractResult<RentalRuntimeSnapshot>,
    private val reconnectSource: () -> Unit, private val pauseSource: () -> Unit,
    private val beginInactive: (Boolean, ContractResult<RentalClock>) -> ContractResult<Unit>) {
    @Volatile private var receiver: String? = null
    private var listener: ((ContractResult<RentalRuntimeSnapshot>) -> Unit)? = null
    fun start(token: String, listener: (ContractResult<RentalRuntimeSnapshot>) -> Unit) {
        receiver = token; this.listener = listener
        worker.execute(reconnectSource)
    }
    /** 이미 수락된 SDK 관측은 UI pause 뒤에도 영속 처리한다. 호출자는 D와 같은 serial worker다. */
    fun observed(token: String, frame: DrivingFrame, recorded: ContractResult<DrivingRecordingResult>) {
        val result = observeSource(frame, recorded)
        main.execute { if (receiver == token) listener?.invoke(result) }
    }
    fun pause() {
        receiver = null; listener = null
        worker.execute(pauseSource)
    }
    fun inactive(configurationChange: Boolean, clock: ContractResult<RentalClock>) {
        worker.execute {
            when (val result = beginInactive(configurationChange, clock)) {
                is ContractResult.Failure -> Log.w("DriveGrowthRental", "inactive_deferred code=${result.error}")
                is ContractResult.Success -> Unit
            }
        }
    }
}
