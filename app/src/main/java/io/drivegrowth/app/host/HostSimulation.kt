package io.drivegrowth.app.host

import io.drivegrowth.app.data.RestClockDomain
import io.drivegrowth.app.host.delivery.DeliveryNavigationPort
import io.drivegrowth.app.host.rest.RestAreaProof
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.app.runtime.rental.product.RentalPreparationInputs
import io.drivegrowth.app.ui.rest.RestAreaScene
import io.drivegrowth.app.vehicle.navigation.NavigationSnapshot
import io.drivegrowth.app.vehicle.navigation.NavigationStamp
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
    val parkedRestScene: RestAreaScene.Parked? get() = null
    fun takeRestArrival(stopId: String): Boolean = false
    fun setRestPresentationFocused(focused: Boolean) = Unit
    var changed: (() -> Unit)?
    fun clock(): ContractResult<RentalClock>
    fun navigation(games: GameSessionFactory, main: Executor): HostDeliveryNavigation
    fun observed(frame: DrivingFrame, result: ContractResult<DrivingRecordingResult>): ContractResult<Unit>
    fun rentalResult(result: ContractResult<RentalRuntimeSnapshot>)
    fun inputConsumed(frame: DrivingFrame, recorded: DrivingRecordingResult, rental: RentalRuntimeSnapshot)
    fun restInputRecorded(frame: DrivingFrame, recorded: DrivingRecordingResult) = Unit
    fun rentalRandom(games: GameSessionFactory, authority: RentalPreparationInputs): Random
    fun attach(worker: Executor? = null, accept: (DrivingFrame, () -> Unit) -> Unit)
    fun begin()
    fun beginRest()
    fun restSample(frame: DrivingFrame): SimulationRestSample?
    fun departureGapStart(frame: DrivingFrame): RentalClock? = null
    val restRecording: Boolean
    fun retry()
    fun rejected(reason: SimulationStartBlock)
    fun stop()
}
internal enum class SimulationStartBlock(val message: String) {
    OPENING_GIFT("첫 차량을 받은 뒤 모의주행을 시작할 수 있어요."),
    GACHA_RESULT("뽑기 화면에서 결과를 먼저 확인해 주세요."),
    SAVED_REQUEST("다른 화면에서 처리 중인 요청의 결과를 먼저 확인해 주세요."),
    ACTIVE_DELIVERY("진행 중인 탁송을 마친 뒤 휴식 포함 모의주행을 시작할 수 있어요."),
    REST_CONFIGURATION("휴식 시연에 필요한 설정을 확인할 수 없어요."),
    READ_FAILED("저장된 가게 상태를 읽지 못했어요. 잠시 후 다시 시도해 주세요."),
}

internal enum class SimulationInputState { READY, PROCESSING, RETRYABLE_FAILURE, UNAVAILABLE }

internal data class SimulationRestSample(val clock: RestClockDomain, val area: Observation<RestAreaProof>,
    val restRun: Boolean, val ordinaryTerminal: Boolean, val startsRestRun: Boolean = false)

internal const val SIMULATION_REST_RECEIVER = "unified-simulation-rest"

internal fun interface RentalClockReader { fun read(): ContractResult<RentalClock> }

/** 화면의 확인·재조회만 공유한다. SDK snapshot이 없는 모의 공급자가 SDK 값을 만들지 않는다. */
interface HostDeliveryNavigation : AutoCloseable {
    var changed: (() -> Unit)?
    val snapshot: NavigationSnapshot?
    val port: DeliveryNavigationPort
    fun setControlsEnabled(value: Boolean)
    fun refresh()
    fun confirm(displayedStamp: NavigationStamp? = null): ContractResult<Unit>
}
