package io.drivegrowth.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.drivegrowth.app.data.SnapshotStore
import io.drivegrowth.app.ui.UiModel
import io.drivegrowth.app.vehicle.VehicleInput
import io.drivegrowth.core.*
import io.drivegrowth.core.driving.toSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 프로세스당 하나의 기록 소유자. Activity 재생성은 기록을 초기화하지 않는다. */
class DriveRuntime(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val stores = InputSource.entries.associateWith { SnapshotStore(File(context.filesDir, "${it.name.lowercase()}.json")) }
    private var failure: String? = null
    private val engines = InputSource.entries.associateWith { source ->
        try {
            DriveEngine(stores.getValue(source).load()).also {
                it.recoverInterrupted()
                stores.getValue(source).save(it.snapshot)
            }
        } catch (error: Exception) {
            failure = "저장된 기록을 읽지 못했습니다. 원본을 보존하고 기록을 중지했습니다."
            DriveEngine()
        }
    }
    private var source = runCatching { InputSource.valueOf(preferences.getString("source", "VEHICLE")!!) }.getOrDefault(InputSource.VEHICLE)
    private var tab = "home"
    private var vehicleStatus = "차량 연결을 준비합니다."
    private var parkSince: Long? = null
    private var lastVehicle: Sample? = null
    private var demoRunning = false
    private var started = false
    private var input: VehicleInput? = null
    private val observers = mutableSetOf<(UiModel) -> Unit>()
    private val tutorial = preferences.getStringSet("tutorial", emptySet())!!.mapNotNull { it.toIntOrNull() }.toMutableSet()

    fun start() {
        if (started) return
        started = true
        try {
            input = VehicleInput(context, { observation ->
                val sample = observation.toSample()
                val previous = lastVehicle
                lastVehicle = sample
                val parked = sample.valid && sample.gear == Gear.PARK && sample.uxRestricted == false &&
                    sample.speedMps?.let { abs(it) <= 0.1 } == true
                parkSince = if (parked) {
                    if (previous != null && sample.elapsedMs - previous.elapsedMs in 1..5000) parkSince ?: sample.elapsedMs else sample.elapsedMs
                } else null
                if (!parked && demoRunning) {
                    demoRunning = false
                    engines.getValue(InputSource.SIMULATION).recoverInterrupted()
                    persist(InputSource.SIMULATION)
                }
                if (failure == null) {
                    engines.getValue(InputSource.VEHICLE).ingest(sample)
                    persist(InputSource.VEHICLE)
                }
                publish()
            }, { vehicleStatus = it }).also { it.start() }
        } catch (error: LinkageError) {
            vehicleStatus = "이 환경에서 차량 프레임워크를 사용할 수 없습니다."
            publish()
        }
    }

    fun stop() {
        input?.stop()
        input = null
        started = false
        demoRunning = false
        parkSince = null
        lastVehicle = null
        publish()
    }

    fun observe(observer: (UiModel) -> Unit) { observers.add(observer); observer(model()) }
    fun removeObserver(observer: (UiModel) -> Unit) { observers.remove(observer) }

    fun act(action: String) {
        if (!controlsEnabled()) return
        when {
            action.startsWith("tab:") -> tab = action.substringAfter(':')
            action == "source:vehicle" || action == "source:simulation" -> {
                source = if (action.endsWith("vehicle")) InputSource.VEHICLE else InputSource.SIMULATION
                preferences.edit().putString("source", source.name).apply()
                tab = "home"
            }
            action.startsWith("tutorial:") -> action.substringAfter(':').toIntOrNull()?.takeIf { it in 0..2 }?.let {
                tutorial.add(it)
                preferences.edit().putStringSet("tutorial", tutorial.map(Int::toString).toSet()).apply()
            }
            action == "demo:start" && source == InputSource.SIMULATION -> startDemo()
            action == "garage:badge" -> tab = "garage"
        }
        publish()
    }

    private fun realParked(): Boolean {
        val sample = lastVehicle ?: return false
        val since = parkSince ?: return false
        return sample.elapsedMs - since >= 3000 && SystemClock.elapsedRealtime() - sample.elapsedMs in 0..2500
    }

    private fun controlsEnabled() = failure == null && realParked() && !demoRunning

    private fun startDemo() {
        demoRunning = true
        val engine = engines.getValue(InputSource.SIMULATION)
        val elapsed = maxOf(SystemClock.elapsedRealtime(), (engine.snapshot.lastSample?.elapsedMs ?: 0) + 1000)
        val epoch = System.currentTimeMillis()
        var index = 0
        handler.post(object : Runnable {
            override fun run() {
                if (!demoRunning || failure != null) return
                if (!realParked()) {
                    demoRunning = false
                    engine.recoverInterrupted()
                    persist(InputSource.SIMULATION)
                    publish()
                    return
                }
                val speed = when {
                    index < 10 -> (index + 1).toDouble()
                    index < 60 -> 10.0
                    index < 70 -> (69 - index).toDouble()
                    else -> 0.0
                }
                engine.ingest(Sample(InputSource.SIMULATION, elapsed + index * 1000L, epoch + index * 1000L,
                    speed, if (index >= 70) Gear.PARK else Gear.DRIVE, index < 70))
                persist(InputSource.SIMULATION)
                if (index >= 73) demoRunning = false else { index++; handler.postDelayed(this, 150) }
                publish()
            }
        })
    }

    private fun persist(target: InputSource) {
        if (failure != null) return
        try { stores.getValue(target).save(engines.getValue(target).snapshot) }
        catch (error: Exception) { failure = "기록 저장에 실패했습니다. 추가 정산을 중지했습니다."; demoRunning = false }
    }

    fun model(): UiModel {
        val snapshot = engines.getValue(source).snapshot
        val result = snapshot.trips.lastOrNull()
        val active = snapshot.active
        val simulation = source == InputSource.SIMULATION
        val date = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA)
        val status = failure ?: if (simulation) "모의 입력 · 약 74초 기록을 빠르게 재생합니다. 실제 주행 기록과 별도 저장됩니다." else vehicleStatus
        return UiModel(
            state = if (!realParked() && active == null) "DATA_UNAVAILABLE" else snapshot.state.name,
            sourceLabel = if (simulation) "모의 입력" else "차량 입력",
            xp = snapshot.totalXp, level = snapshot.level,
            score = result?.score?.let { "$it 점" } ?: "평가 준비 중",
            distance = String.format(Locale.KOREA, "%.2f km", (active?.distanceMeters ?: result?.distanceMeters ?: 0.0) / 1000),
            duration = "${(active?.validDurationMs ?: result?.validDurationMs ?: 0) / 1000}초",
            statusMessage = status, badgeUnlocked = snapshot.firstBadgeUnlocked,
            controlsEnabled = controlsEnabled(), selectedTab = tab,
            records = snapshot.trips.asReversed().map { "${date.format(Date(it.endedEpochMs))} · ${if (it.interrupted) "중단 기록" else "정산"}\n${String.format(Locale.KOREA, "%.2f", it.distanceMeters / 1000)} km · ${it.xp} XP · ${it.score?.toString() ?: "평가 없음"}" },
            tutorialCompleted = tutorial.toSet(),
            ranking = snapshot.trips.filter { it.score != null }.sortedByDescending { it.score }.take(10).mapIndexed { i, trip ->
                "${i + 1}위 · 내 기록 ${date.format(Date(trip.endedEpochMs))} · ${trip.score}점"
            }, rewardXp = result?.xp ?: 0,
        )
    }

    private fun publish() { val state = model(); observers.toList().forEach { it(state) } }
}
