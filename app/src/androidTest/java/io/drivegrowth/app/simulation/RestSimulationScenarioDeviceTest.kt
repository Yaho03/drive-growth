package io.drivegrowth.app.simulation

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.SqliteObservedDrivingJournal
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.progression.FactoryProgressionSource
import io.drivegrowth.app.host.progression.ProgressionMapper
import io.drivegrowth.app.host.progression.readStoredRestProgression
import io.drivegrowth.app.ui.progress.BuffPhase
import io.drivegrowth.app.ui.progress.ProgressView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.time.ZoneId
import java.util.UUID

/** 실제 SQLite/표시 mapper 소비. 모의 입력이며 Main Activity 연결·실차 시험이 아니다. */
@Suppress("DEPRECATION")
class RestSimulationScenarioDeviceTest : InstrumentationTestCase() {
    fun testExplicitRunPaysOnceAndReopenedProgressionReadsConfirmedSimulation() {
        val context = instrumentation.targetContext
        val name = "rest-simulation-${UUID.randomUUID()}.db"
        val definition = ok(AssetTrialDefinitionProvider(context.assets).load())
        val utc = ZoneId.of("UTC")
        val now = { Observation.Available(EpochMillis(1_794_279_600_000L)) }
        fun factory() = GameSessionFactory(context, mapOf(InputSource.SIMULATION to name),
            InputSource.SIMULATION, { definition.questDefinitions(utc) })
        try {
            val games = factory()
            val onboarding = StoredOnboardingHostSource(games)
            val identity = ok(onboarding.read()).identity
            assertEquals(InputSource.SIMULATION, identity.source)
            ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
            val startingBalance = ok(games.open()).use { ok(it.store.balance()) }
            val run = ok(RestSimulationScenario.prepareRun(games))
            var sampleBoundaries = 0
            assertNull(ok(run.run(canContinue = { ++sampleBoundaries == 1 })))
            ok(games.open()).use { game ->
                val state = ok(game.store.atomic { db -> SqliteObservedDrivingJournal().read(db, identity) })
                assertEquals(0L, state.anchor?.receivedElapsedMs)
                assertTrue(state.outbox.isEmpty())
                assertEquals(0L, ok(game.store.restDriving.state(identity.profileId)).confirmedDrives)
                assertEquals(startingBalance, ok(game.store.balance()))
            }
            val remainingSamples = RestSimulationScenario.frames(definition.rest).count() - 1
            var issued = 0
            var allowed = true
            // 마지막 표본의 처리 권한을 받은 직후 이탈한다. 마지막 거래는 완료로 남아야 한다.
            val receipts = checkNotNull(ok(run.run(canContinue = {
                val allowedForThisSample = allowed
                if (++issued == remainingSamples) allowed = false
                allowedForThisSample
            })))
            assertEquals(remainingSamples, issued)
            assertEquals(receipts, ok(run.run(canContinue = { false })))
            assertEquals(2, receipts.size)
            val boosted = receipts.last()
            assertEquals(GameMoney(15), boosted.commit.amount)

            val reopened = factory()
            val read = ok(FactoryProgressionSource(reopened, now, ::readStoredRestProgression).read(emptySet()))
            assertEquals(identity, read.identity)
            val projection = ok(read.rest!!)
            assertEquals(2L, projection.confirmedDrives)
            assertEquals(0, projection.pendingDrives)
            assertEquals(1_100_000L, projection.confirmed.remainingMs)
            val screen = ProgressionMapper().content(read)
            assertEquals(BuffPhase.ACTIVE, screen.rest?.buffPhase)
            assertEquals("보너스 남은 시간 1100초", screen.rest?.remainingLabel)
            assertEquals("시연 모드", screen.sourceLabel)
            val questUnavailable = GameSessionFactory(context, mapOf(InputSource.SIMULATION to name),
                InputSource.SIMULATION, { ContractResult.Failure(ContractError.POLICY_UNRESOLVED) })
            val blocked = FactoryProgressionSource(questUnavailable, now, ::readStoredRestProgression).read(emptySet())
            assertEquals(ContractResult.Failure(ContractError.POLICY_UNRESOLVED), blocked)
            val restOnly = checkNotNull(ok(readRestDemo(questUnavailable)))
            assertEquals(projection, restOnly.projection)
            assertEquals(boosted, restOnly.original.observed)
            var labels = emptyList<String>()
            instrumentation.runOnMainSync {
                val view = ProgressView(context) {}
                view.render(screen.copy(sourceLabel = "${RestSimulationScenario.label} · ${screen.sourceLabel}"), true)
                labels = texts(view)
            }
            assertTrue(labels.any { it.contains("SIMULATION") && it.contains("시험 장소") })
            assertTrue(labels.contains("보너스 남은 시간 1100초"))

            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), RestSimulationScenario.run(reopened))
            ok(reopened.open()).use { game ->
                assertEquals(startingBalance.units + receipts.sumOf { it.commit.amount.units }, ok(game.store.balance()).units)
                val original = ok(game.store.restDriving.committed(identity.profileId, boosted.trip.id))!!
                assertEquals(boosted, original.observed)
                assertEquals(100_000L, original.price.boostedMs)
                assertEquals(projection.confirmed, original.remaining)
                assertEquals(original, ok(game.store.restDriving.committed(identity.profileId, boosted.trip.id)))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }
}
