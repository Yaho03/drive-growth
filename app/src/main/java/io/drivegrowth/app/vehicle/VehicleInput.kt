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
import io.drivegrowth.core.Gear
import io.drivegrowth.core.InputSource
import io.drivegrowth.core.Sample

/** 현재 속성 조회의 수신 시각으로 정규화한다. 차량 센서 측정 시각과는 다르다. */
@Suppress("DEPRECATION")
class VehicleInput(
    private val context: Context,
    private val onSample: (Sample) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var running = false
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
            read()
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        try {
            car = Car.createCar(context, connection, handler)
            car?.connect()
        } catch (error: RuntimeException) {
            onStatus("차량 서비스 연결 실패: ${error.javaClass.simpleName}")
        }
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        car?.disconnect()
        car = null
        properties = null
        restrictions = null
    }

    private fun read() {
        try {
            val manager = properties ?: return unavailable("차량 서비스 연결을 기다립니다.")
            val speed = manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            val gear = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
            val ux = restrictions?.currentCarUxRestrictions
            val valid = speed?.status == CarPropertyValue.STATUS_AVAILABLE &&
                gear?.status == CarPropertyValue.STATUS_AVAILABLE && ux != null
            val mappedGear = when (gear?.value) {
                4 -> Gear.PARK
                8 -> Gear.DRIVE
                2 -> Gear.REVERSE
                1 -> Gear.NEUTRAL
                else -> Gear.UNKNOWN
            }
            onStatus(if (valid) "차량 속도·기어·주행 제한 정보 연결됨" else "차량 데이터가 미지원이거나 사용할 수 없습니다.")
            onSample(Sample(InputSource.VEHICLE, SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                speed?.value?.toDouble(), mappedGear,
                ux?.let { it.isRequiresDistractionOptimization || it.activeRestrictions != 0 }, valid))
        } catch (error: SecurityException) {
            unavailable("차량 데이터 권한이 없습니다. 차량 속도·구동계 권한을 확인해 주세요.")
        } catch (error: RuntimeException) {
            unavailable("차량 데이터를 읽을 수 없습니다: ${error.javaClass.simpleName}")
        }
    }

    private fun unavailable(message: String) {
        onStatus(message)
        onSample(Sample(InputSource.VEHICLE, SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            null, Gear.UNKNOWN, null, false))
    }
}
