package io.drivegrowth.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.vehicle.ForegroundInteraction
import io.drivegrowth.app.vehicle.VehicleInput
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Page { HOME, GARAGE, RESULTS }
    private sealed interface Screen {
        data object Restricted : Screen
        data object Loading : Screen
        data object RecoverableError : Screen
        data class Content(val snapshot: GameContentSnapshot, val balance: Long,
            val vehicles: List<String>, val results: List<String>) : Screen
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val interaction = ForegroundInteraction()
    private var input: VehicleInput? = null
    private var page = Page.HOME
    private var screen: Screen = Screen.Restricted
    private var generation = 0
    private var active = false
    private lateinit var panel: LinearLayout
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            updateAccess()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = Page.entries.firstOrNull { it.name == savedInstanceState?.getString("page") } ?: Page.HOME
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(panel) })
        render()
        if (savedInstanceState == null) {
            val permissions = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        try {
            input = VehicleInput(this, { if (active) { interaction.accept(it); updateAccess() } }, {}).also { it.start() }
        } catch (_: LinkageError) {
            updateAccess()
        }
        handler.post(watchdog)
    }

    override fun onPause() {
        active = false
        handler.removeCallbacks(watchdog)
        interaction.reset()
        updateAccess()
        input?.stop()
        input = null
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        super.onSaveInstanceState(outState)
    }

    private fun allowed() = active &&
        interaction.state(SystemClock.elapsedRealtime()) == Observation.Available(VehicleInteraction.ALLOWED)

    private fun updateAccess() {
        if (!allowed()) {
            if (screen != Screen.Restricted) {
                generation++
                show(Screen.Restricted)
            }
        } else if (screen == Screen.Restricted) {
            refresh()
        }
    }

    private fun refresh() {
        if (!allowed() || screen == Screen.Loading) return
        val request = ++generation
        show(Screen.Loading)
        worker.execute {
            val result = readContent()
            handler.post {
                if (request == generation && allowed()) show(result)
            }
        }
    }

    private fun readContent(): Screen {
        val snapshot = (AssetGameContent(assets).load() as? ContentLoad.Loaded)?.snapshot
            ?: return Screen.RecoverableError
        return SqliteGameChangeStore(applicationContext, InputSource.SIMULATION).use { store ->
            val balance = (store.balance() as? ContractResult.Success)?.value ?: return Screen.RecoverableError
            val owned = (store.listOwnedVehicles() as? ContractResult.Success)?.value ?: return Screen.RecoverableError
            val settlements = (store.listUnviewedSettlements() as? ContractResult.Success)?.value
                ?: return Screen.RecoverableError
            val names = snapshot.catalog.vehicles.associate { it.vehicleId to it.displayName }
            Screen.Content(snapshot, balance.units, owned.map { names[it.vehicleId] ?: it.vehicleId.value },
                settlements.map { "정산 ${it.settlementId.value} · 잔액 ${it.balance.units}" })
        }
    }

    private fun show(value: Screen) {
        screen = value
        render()
    }

    private fun render() {
        panel.removeAllViews()
        when (val current = screen) {
            Screen.Restricted -> text("주차 후 조작이 허용되면 이용할 수 있습니다.")
            Screen.Loading -> text("콘텐츠를 불러오는 중입니다.")
            Screen.RecoverableError -> {
                text("콘텐츠 또는 저장 데이터를 읽지 못했습니다.")
                button("다시 시도", ::refresh)
            }
            is Screen.Content -> {
                text("드라이브 키우기", 24f)
                text("모의 원장 · ${if (current.snapshot.trial) "시험용 콘텐츠" else "게임 콘텐츠"} · ${current.snapshot.catalog.contentVersion.value}")
                button("가게") { select(Page.HOME) }
                button("보유 차량") { select(Page.GARAGE) }
                button("미확인 정산") { select(Page.RESULTS) }
                when (page) {
                    Page.HOME -> text("잔액 ${current.balance} · 보유 차량 ${current.vehicles.size}대")
                    Page.GARAGE -> rows(current.vehicles, "보유 차량이 없습니다.")
                    Page.RESULTS -> rows(current.results, "미확인 정산이 없습니다.")
                }
                button("새로고침", ::refresh)
            }
        }
    }

    private fun rows(values: List<String>, empty: String) {
        if (values.isEmpty()) text(empty) else values.forEach { text(it) }
    }

    private fun text(value: String, size: Float = 18f) {
        panel.addView(TextView(this).apply { text = value; textSize = size })
    }

    private fun button(label: String, action: () -> Unit) {
        panel.addView(Button(this).apply {
            text = label
            setOnClickListener { if (allowed()) action() else updateAccess() }
        })
    }

    private fun select(value: Page) {
        page = value
        render()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (allowed() && screen is Screen.Content && page != Page.HOME) select(Page.HOME)
        else super.onBackPressed()
    }
}
