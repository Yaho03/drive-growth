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

/** 현재 속성 조회의 수신 시각으로 정규화한다. 차량 센서 측정 시각과는 다르다. */
@Suppress("DEPRECATION")
class VehicleInput(
    private val context: Context,
    private val onObservation: (VehicleObservation) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var running = false
    private var lastSignals: String? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                restrictions = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            } catch (error: RuntimeException) {
                onStatus("차량 서비스 연결 실패: ${error.javaClass.simpleName}")
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            properties = null
            restrictions = null
            unavailable("차량 연결이 끊겼습니다. 기록과 조작을 제한합니다.")
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            logGap()
            read()
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        val displayId = context.getSystemService(WindowManager::class.java)?.defaultDisplay?.displayId
        Log.i(TAG, "observation_start firstInProcess=$firstInProcess displayId=$displayId")
        firstInProcess = false
        try {
            car = Car.createCar(context, connection, handler)
            car?.connect()
        } catch (error: RuntimeException) {
            onStatus("차량 서비스 연결 실패: ${error.javaClass.simpleName}")
        }
        handler.post(tick)
    }

    fun stop() {
        if (running) coverage.stop(clock())
        running = false
        handler.removeCallbacks(tick)
        car?.disconnect()
        car = null
        properties = null
        restrictions = null
    }

    private fun read() {
        val manager = properties ?: return unavailable("차량 서비스 연결을 기다립니다.")
        val observation = observation(
            speed = readSignal {
                val value = manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                VehicleSignals.speed(value?.status == CarPropertyValue.STATUS_AVAILABLE, value?.value)
            },
            gear = readSignal {
                val value = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
                VehicleSignals.gear(value?.status == CarPropertyValue.STATUS_AVAILABLE, value?.value)
            },
            uxRestricted = readSignal {
                val ux = restrictions?.currentCarUxRestrictions
                if (ux == null) Observation.Missing(MissingReason.NO_DATA)
                else VehicleSignals.uxRestricted(ux.isRequiresDistractionOptimization, ux.activeRestrictions)
            },
        )
        onStatus(statusOf(observation))
        onObservation(observation)
    }

    private fun statusOf(observation: VehicleObservation): String {
        val reasons = listOf(observation.speedMps, observation.gear, observation.uxRestricted)
            .filterIsInstance<Observation.Missing>().map { it.reason }
        return when {
            reasons.isEmpty() -> "차량 속도·기어·주행 제한 정보 연결됨"
            MissingReason.PERMISSION_DENIED in reasons -> "차량 데이터 권한이 없습니다. 차량 속도·구동계 권한을 확인해 주세요."
            else -> "차량 데이터가 미지원이거나 사용할 수 없습니다."
        }
    }

    private fun unavailable(message: String) {
        onStatus(message)
        val missing = Observation.Missing(MissingReason.NO_DATA)
        onObservation(observation(missing, missing, missing))
    }

    private fun observation(speed: Observation<Double>, gear: Observation<Gear>, uxRestricted: Observation<Boolean>): VehicleObservation {
        val signals = "speed=${if (speed is Observation.Available) "OK" else label(speed)} gear=${label(gear)} ux=${label(uxRestricted)}"
        if (signals != lastSignals) Log.i(TAG, "signal_state $signals")
        lastSignals = signals
        return VehicleObservation(InputSource.VEHICLE, SystemClock.elapsedRealtime(),
            EpochMillis(System.currentTimeMillis()), speed, gear, uxRestricted)
    }

    /** 신호 상태가 바뀔 때만 남긴다. 속도 값은 로그에 남기지 않는다. */
    private fun label(signal: Observation<*>): String = when (signal) {
        is Observation.Available -> signal.value.toString()
        is Observation.Missing -> signal.reason.name
    }

    private fun logGap() {
        coverage.tick(clock())?.let { Log.i(TAG, "observation_gap kind=${it.kind} durationMs=${it.durationMs}") }
    }

    private fun clock() = ObservationClock(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())

    /** Activity가 복귀할 때마다 새 입력을 만들어도 숨김 구간을 잴 수 있도록 프로세스 단위로 유지한다. 메인 스레드에서만 사용한다. */
    private companion object {
        const val TAG = "DriveGrowthVehicle"
        const val MAX_TICK_GAP_MS = 2500L
        const val MIN_SLEEP_MS = 1000L
        val coverage = ObservationCoverage(MAX_TICK_GAP_MS, MIN_SLEEP_MS)
        var firstInProcess = true
    }
}
