package io.drivegrowth.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleObservation
import io.drivegrowth.core.driving.ObservationClock
import io.drivegrowth.core.driving.ObservationCoverage
import io.drivegrowth.core.driving.VehicleSignals
import java.util.concurrent.Executor

/** main은 관측 예약·전달만 수행한다. SDK 동기 호출은 프로세스 공통 worker에서 직렬화한다. */
@Suppress("DEPRECATION")
class VehicleInput(
    private val context: Context,
    private val onObservation: (VehicleObservation) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var session: VehicleInputQueue.Session? = null
    private var lastSignals: String? = null
    private val tick = object : Runnable {
        override fun run() {
            val current = session ?: return
            coverage.tick(clock())?.let { Log.i(TAG, "observation_gap kind=${it.kind} durationMs=${it.durationMs}") }
            queue.poll(current)
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (session != null) return
        val display = context.getSystemService(WindowManager::class.java)?.defaultDisplay
        // 막힌 SDK가 Activity를 보유하지 않게 하되 UXR 조회의 display 연결은 보존한다.
        val sdkContext = display?.let { context.applicationContext.createDisplayContext(it) } ?: context.applicationContext
        Log.i(TAG, "observation_start firstInProcess=$firstInProcess displayId=${display?.displayId}")
        firstInProcess = false
        session = queue.start({ owner ->
            CarReader(sdkContext, sdkHandler) { queue.invalidate(owner) }
        }) { result ->
            val observation = result.getOrElse { error ->
                val missing = readSignal<Double> {
                    if (error is LinkageError) Observation.Missing(MissingReason.UNSUPPORTED) else throw error
                }
                missingObservation((missing as Observation.Missing).reason)
            }
            deliver(observation)
        }
        handler.post(tick)
    }

    fun stop() {
        val current = session ?: return
        session = null
        coverage.stop(clock())
        handler.removeCallbacks(tick)
        queue.stop(current)
    }

    private fun deliver(observation: VehicleObservation) {
        val speed = observation.speedMps
        val signals = "speed=${if (speed is Observation.Available) "OK" else label(speed)} gear=${label(observation.gear)} ux=${label(observation.uxRestricted)}"
        if (signals != lastSignals) Log.i(TAG, "signal_state $signals")
        lastSignals = signals
        val reasons = listOf(observation.speedMps, observation.gear, observation.uxRestricted)
            .filterIsInstance<Observation.Missing>().map { it.reason }
        onStatus(when {
            reasons.isEmpty() -> "차량 속도·기어·주행 제한 정보 연결됨"
            MissingReason.PERMISSION_DENIED in reasons -> "차량 데이터 권한이 없습니다. 차량 속도·구동계 권한을 확인해 주세요."
            else -> "차량 데이터가 미지원이거나 사용할 수 없습니다."
        })
        onObservation(observation)
    }

    private fun label(signal: Observation<*>): String = when (signal) {
        is Observation.Available -> signal.value.toString()
        is Observation.Missing -> signal.reason.name
    }

    private fun clock() = ObservationClock(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())

    private companion object {
        const val TAG = "DriveGrowthVehicle"
        val coverage = ObservationCoverage(2500, 1000)
        var firstInProcess = true
        val sdkHandler = Handler(HandlerThread("DriveGrowthVehicle").apply { start() }.looper)
        val queue = VehicleInputQueue(Executor { sdkHandler.post(it) },
            Executor { Handler(Looper.getMainLooper()).post(it) }, SystemClock::elapsedRealtime,
            TRIAL_INTERACTION_POLICY.maxSignalAgeMs) { operation, error ->
                Log.w(TAG, "input_operation_failed operation=$operation kind=${error.javaClass.simpleName}")
            }
    }
}

/** SDK 자원은 worker 소유다. main 연결 콜백은 최신 상태만 저장하며 worker 작업을 적재하지 않는다. */
@Suppress("DEPRECATION")
private class CarReader(context: Context, handler: Handler, private val connectionChanged: () -> Unit) : VehicleReader {
    @Volatile private var connected = false
    @Volatile private var closed = false
    @Volatile private var revision = 0L
    private var managersRevision = -1L
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var car: Car? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = changed(true)
        override fun onServiceDisconnected(name: ComponentName?) = changed(false)
        override fun onBindingDied(name: ComponentName?) = changed(false)
        override fun onNullBinding(name: ComponentName?) = changed(false)
        private fun changed(ready: Boolean) {
            if (closed) return
            connected = ready
            revision++
            connectionChanged()
        }
    }

    init {
        car = Car.createCar(context, connection, handler)
        // connect 실패 뒤에도 queue가 자원을 보존해 같은 worker에서 해제하도록 read에서 연결한다.
    }
    private var connectRequested = false

    override fun read(): VehicleObservation {
        if (!connectRequested) {
            car?.connect()
            connectRequested = true
        }
        val version = revision
        if (closed || !connected) return missingObservation()
        if (managersRevision != version) {
            properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            restrictions = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            managersRevision = version
        }
        val manager = properties ?: return missingObservation()
        val speed = readSignal {
            val value = manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            VehicleSignals.speed(value?.status == CarPropertyValue.STATUS_AVAILABLE, value?.value)
        }
        val gear = readSignal {
            val value = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
            VehicleSignals.gear(value?.status == CarPropertyValue.STATUS_AVAILABLE, value?.value)
        }
        val ux = readSignal {
            val value = restrictions?.currentCarUxRestrictions
            if (value == null) Observation.Missing(MissingReason.NO_DATA)
            else VehicleSignals.uxRestricted(value.isRequiresDistractionOptimization, value.activeRestrictions)
        }
        if (closed || !connected || revision != version) return missingObservation()
        return VehicleObservation(InputSource.VEHICLE, SystemClock.elapsedRealtime(),
            EpochMillis(System.currentTimeMillis()), speed, gear, ux)
    }

    override fun close() {
        closed = true
        car?.disconnect()
        car = null
        properties = null
        restrictions = null
    }
}

private fun missingObservation(reason: MissingReason = MissingReason.NO_DATA): VehicleObservation {
    val missing = Observation.Missing(reason)
    return VehicleObservation(InputSource.VEHICLE, SystemClock.elapsedRealtime(),
        EpochMillis(System.currentTimeMillis()), missing, missing, missing)
}
