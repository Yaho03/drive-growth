package io.drivegrowth.app.host

import io.drivegrowth.app.data.RestClockDomain
import io.drivegrowth.app.host.delivery.DeliveryNavigationPort
import io.drivegrowth.app.host.rest.RestAreaProof
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.app.runtime.rental.product.RentalPreparationInputs
import io.drivegrowth.app.vehicle.navigation.NavigationSnapshot
import io.drivegrowth.core.contract.*
import java.util.concurrent.Executor
import kotlin.random.Random

/** 구현은 debug에만 존재한다. 실제 차량 gate·SDK snapshot과 모의 조작 상태를 섞지 않는다. */
internal interface HostSimulation {
    val policy: RecordingPolicy
    val controlsAllowed: Boolean
    val canRetry: Boolean
    val inputState: SimulationInputState
    val status: String
    var changed: (() -> Unit)?
    fun clock(): ContractResult<RentalClock>
    fun navigation(games: GameSessionFactory, main: Executor): HostDeliveryNavigation
    fun observed(frame: DrivingFrame, result: ContractResult<DrivingRecordingResult>): ContractResult<Unit>
    fun rentalResult(result: ContractResult<RentalRuntimeSnapshot>)
    fun inputConsumed(frame: DrivingFrame, recorded: DrivingRecordingResult, rental: RentalRuntimeSnapshot)
    fun rentalRandom(games: GameSessionFactory, authority: RentalPreparationInputs): Random
    fun attach(worker: Executor? = null, accept: (DrivingFrame, () -> Unit) -> Unit)
    fun begin()
    fun beginRest()
    fun restSample(frame: DrivingFrame): SimulationRestSample?
    val restRecording: Boolean
    fun retry()
    fun rejected(error: ContractError)
    fun stop()
}
internal enum class SimulationInputState { READY, PROCESSING, RETRYABLE_FAILURE, UNAVAILABLE }

internal data class SimulationRestSample(val clock: RestClockDomain, val area: Observation<RestAreaProof>,
    val restRun: Boolean, val ordinaryTerminal: Boolean)

internal fun interface RentalClockReader { fun read(): ContractResult<RentalClock> }

/** 화면의 확인·재조회만 공유한다. SDK snapshot이 없는 모의 공급자가 SDK 값을 만들지 않는다. */
interface HostDeliveryNavigation : AutoCloseable {
    var changed: (() -> Unit)?
    val snapshot: NavigationSnapshot?
    val description: String? get() = null
    val port: DeliveryNavigationPort
    fun setControlsEnabled(value: Boolean)
    fun refresh()
    fun confirm(): ContractResult<Unit>
}
