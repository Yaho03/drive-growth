package io.drivegrowth.app.simulation

import android.app.Activity
import android.os.Bundle
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.RestAtomicStorage
import io.drivegrowth.app.data.RestDrivingCommit
import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.progression.readStoredRestProgression
import io.drivegrowth.app.simulation.RestSimulationWorkflow.Action
import io.drivegrowth.app.simulation.RestSimulationWorkflow.Screen
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.math.BigDecimal
import java.time.ZoneOffset
import java.util.concurrent.Executors

/** 명시 실행과 읽기만 제공하는 Debug 시연. 일반 시연/제품과 다른 DB를 쓴다. */
class RestSimulationActivity : Activity() {
    private val style by lazy { GarageStyle(this) }
    private val status by lazy { style.text("", 22) }
    private val start by lazy { style.button("휴식 시연 시작", primary = true) { load(nextAction) }
        .apply { tag = "rest-simulation:run" } }
    private val remaining by lazy { style.text("", 22) }
    private var nextAction = Action.START

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(style.scroll(style.column().apply {
            setBackgroundColor(style.base)
            addView(style.text(RestSimulationScenario.label, 22))
            addView(style.text("별도 시연 원장 · 시험 차량으로 한 번 실행"))
            addView(style.button("통합 시연으로 돌아가기") { leaveDemo() })
            addView(status)
            addView(remaining)
            addView(start)
        }))
        load(Action.READ)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = leaveDemo()

    override fun onStop() {
        if (!isChangingConfigurations) pauseRequested = true
        super.onStop()
    }

    private fun leaveDemo() {
        pauseRequested = true
        finish()
    }

    private fun load(action: Action) {
        if (action != Action.READ) pauseRequested = false
        start.isEnabled = false
        status.text = if (action == Action.READ) "시연 원장을 확인 중입니다." else "휴식 시연을 처리 중입니다."
        remaining.text = ""
        val context = applicationContext
        worker.execute {
            val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to DATABASE), InputSource.SIMULATION,
                {
                    when (val result = AssetTrialDefinitionProvider(context.assets).load()) {
                        is ContractResult.Failure -> result
                        is ContractResult.Success -> result.value.questDefinitions(ZoneOffset.UTC)
                    }
                }, { SimulationTimeline().clock.epoch.value })
            val result = workflow.execute(action,
                read = {
                    if (context.getDatabasePath(DATABASE).exists()) readRestDemo(games) else ContractResult.Success(null)
                },
                prepare = { prepareDemo(games) },
                canContinue = { !pauseRequested },
                onPhase = { phase ->
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) status.text = when (phase) {
                            RestSimulationScenario.Phase.DRIVE -> "논리 주행을 기록 중입니다."
                            RestSimulationScenario.Phase.REST -> "시험 장소의 정차 시간을 기록 중입니다."
                            RestSimulationScenario.Phase.DEPARTURE -> "시험 장소 출발과 다음 주행을 정산 중입니다."
                        }
                    }
                })
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                when (result) {
                    is Screen.Failure -> {
                        nextAction = result.next
                        status.text = "휴식 시연을 확인하지 못했습니다. (${result.error})"
                        start.text = when (nextAction) {
                            Action.START -> "시연 준비 재시도"
                            Action.RETRY_INPUT -> "같은 모의 입력 재시도"
                            Action.READ -> "저장 결과 다시 읽기"
                        }
                        start.isEnabled = true
                    }
                    Screen.Ready -> {
                        nextAction = Action.START
                        start.text = "휴식 시연 시작"
                        start.isEnabled = true
                        status.text = "논리 주행 → 시험 장소 주차 → 출발 → 원장 정산"
                    }
                    Screen.Paused -> {
                        nextAction = Action.RETRY_INPUT
                        start.text = "같은 시연 재개"
                        start.isEnabled = true
                        status.text = "시연을 일시 중지했습니다. 처리한 표본은 원장에 보존됩니다."
                    }
                    is Screen.Saved -> {
                        val saved = result.result
                        val original = saved.original
                        nextAction = Action.READ
                        start.text = "휴식 시연 완료"
                        status.text = "확정 주행 ${saved.projection.confirmedDrives}건 · 부스트 사용 ${seconds(original.price.boostedMs)}초 · " +
                            "다음 주행 정산 ${original.observed.commit.amount.units} 게임머니"
                        remaining.text = "원장 잔여 ${seconds(saved.projection.confirmed.remainingMs)}초"
                    }
                }
            }
        }
    }

    companion object {
        internal const val DATABASE = "rest-simulation-demo.db"
        private val worker = Executors.newSingleThreadExecutor()
        private val workflow = RestSimulationWorkflow<RestDemoSaved>()
        @Volatile private var pauseRequested = false
    }
}

private fun seconds(ms: Long): String = BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString()

private fun prepareDemo(games: GameSessionFactory): ContractResult<RestDemoOperation> {
    val onboarding = StoredOnboardingHostSource(games)
    val snapshot = when (val loaded = onboarding.read()) {
        is ContractResult.Failure -> return loaded
        is ContractResult.Success -> loaded.value
    }
    if (snapshot.initialization.status is ProfileInitializationStatus.New) {
        val initial = onboarding.choose(snapshot.identity, FirstGrant.CANDIDATES.first())
        if (initial is ContractResult.Failure) return initial
    }
    return when (val prepared = RestSimulationScenario.prepareRun(games)) {
        is ContractResult.Failure -> prepared
        is ContractResult.Success -> ContractResult.Success { onPhase, canContinue ->
            when (val recorded = prepared.value.run(onPhase, canContinue)) {
                is ContractResult.Failure -> recorded
                is ContractResult.Success -> ContractResult.Success(recorded.value != null)
            }
        }
    }
}

internal data class RestDemoSaved(val projection: RestDrivingProjection, val original: RestDrivingCommit)

/** 휴식 원장만 읽는다. Quest 정의나 다른 진행 화면 결과가 필요하지 않다. */
internal fun readRestDemo(games: GameSessionFactory): ContractResult<RestDemoSaved?> {
    val opened = when (val result = games.open()) {
        is ContractResult.Failure -> return result
        is ContractResult.Success -> result.value
    }
    return opened.use { game ->
        val projection = when (val result = readStoredRestProgression(game)) {
            is ContractResult.Failure -> return@use if (result.error == ContractError.DATA_UNAVAILABLE) ContractResult.Success(null) else result
            is ContractResult.Success -> result.value
        }
        if (projection.confirmedDrives != 2L || projection.pendingDrives != 0)
            return@use ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val driveId = when (val read = game.store.atomic { db ->
            ContractResult.Success(RestAtomicStorage.contexts(db, game.identity).lastOrNull()?.value?.trip?.id)
        }) {
            is ContractResult.Failure -> return@use read
            is ContractResult.Success -> read.value ?: return@use ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
        when (val receipt = game.store.restDriving.committed(game.identity.profileId, driveId)) {
            is ContractResult.Failure -> receipt
            is ContractResult.Success -> receipt.value?.let { ContractResult.Success(RestDemoSaved(projection, it)) }
                ?: ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
    }
}
