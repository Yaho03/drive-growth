package io.drivegrowth.app.simulation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.RestClockDomain
import io.drivegrowth.app.data.RestDrivingCommit
import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostSimulation
import io.drivegrowth.app.host.HostSimulationProvider
import io.drivegrowth.app.host.SimulationRestSample
import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.app.host.driving.DrivingRecordingResult
import io.drivegrowth.app.host.rest.RestDrivingPricing
import io.drivegrowth.app.host.rest.RestPricingWindow
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleObservation
import io.drivegrowth.core.driving.DrivingDay
import io.drivegrowth.app.ui.progress.ProgressView
import java.io.File
import java.math.BigDecimal
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** The actual Debug Main/HostSession consumes explicit simulation frames and prices them in SQLite. */
@Suppress("DEPRECATION")
class UnifiedSimulationRestNegativeEvidenceTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {

    fun testOutsideAndMissingParkDoNotRefreshOrConsumeTheExistingRestGrant() =
        withPreparedSimulation { root, frames, games, baseline ->
            val context = instrumentation.targetContext
            val grant = requireNotNull(baseline.projection.activeGrant)
            val originalDepartures = departureIds(context)
            assertEquals(1, originalDepartures.size)
            val day = baseline.original.price.settlement.day

            val outsideDay = checkStop(frames, games, context, ParkPlace.OUTSIDE, day, grant.rules.bonusPercent)
            checkStop(frames, games, context, ParkPlace.MISSING, outsideDay, grant.rules.bonusPercent)
            assertEquals(originalDepartures, departureIds(context))
            val saved = projection(games)
            assertEquals(grant, saved.activeGrant)
            assertEquals(6L, saved.confirmedDrives)
            assertEquals(860_000L, saved.confirmed.remainingMs)
            assertEquals(0, saved.pendingDrives)

            chooseProgress(root)
            val seconds = BigDecimal.valueOf(saved.confirmed.remainingMs, 3).stripTrailingZeros().toPlainString()
            await("actual ProgressView exposes the stored active grant and the simulation source") {
                val card = descendants(root).filterIsInstance<ProgressView>().singleOrNull()
                val labels = card?.let(::descendants).orEmpty().filterIsInstance<TextView>().map { it.text.toString() }
                context.getString(R.string.progress_buff_active) in labels &&
                    "원장 잔여 ${seconds}초" in labels && "모의 입력 · 저장된 확정 상태" in labels
            }
            assertEquals(saved, projection(games))
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
            assertFalse(context.getDatabasePath(RestSimulationActivity.DATABASE).exists())
        }

    fun testQualifiedDrivingAndParkingGrantOnlyAtTheRecognizedPlace() =
        withPreparedSimulation { _, frames, games, baseline ->
            val context = instrumentation.targetContext
            val rules = requireNotNull(baseline.projection.activeGrant).rules
            assertEquals(3_600_000L, rules.minDrivingMs)
            assertEquals(300_000L, rules.minParkedMs)
            val originalIds = departureIds(context)
            val place = ParkPlace.valueOf(File(context.filesDir, "rest40-location.txt").readText().trim())
            val before = projection(games)
            send(frames, 1_000, moving = true)
            repeat(60) { send(frames, 60_000, moving = true) }
            val parkStart = requireNotNull(send(frames, 1_000, moving = false, place).state.anchor)
            val inbound = original(games,
                send(frames, 60_000, moving = false, place).committed.single().trip.id)
            assertEquals(rules.minDrivingMs, driveDuration(inbound))
            assertEquals(0L, projection(games).confirmed.remainingMs)
            var parkEnd = parkStart
            repeat(4) {
                val parked = send(frames, 60_000, moving = false, place)
                assertTrue(parked.committed.isEmpty())
                parkEnd = requireNotNull(parked.state.anchor)
            }
            assertEquals(rules.minParkedMs, parkEnd.receivedElapsedMs - parkStart.receivedElapsedMs)
            assertEquals(originalIds, departureIds(context))

            send(frames, 1_000, moving = true)
            send(frames, 60_000, moving = true)
            send(frames, 1_000, moving = false)
            val outbound = original(games,
                send(frames, 60_000, moving = false).committed.single().trip.id)
            val after = projection(games)
            assertEquals(before.confirmedDrives + 2, after.confirmedDrives)
            assertEquals(0, after.pendingDrives)
            assertEquals(60_000L, driveDuration(outbound))
            assertEquals(InputSource.SIMULATION, outbound.context.identity.source)
            if (place == ParkPlace.RECOGNIZED) {
                val granted = outbound.departures.single()
                assertEquals(originalIds + granted.requestId.value, departureIds(context))
                val proof = granted.packet.proof
                assertEquals(rules.minDrivingMs, proof.driving.sumOf { it.to.receivedElapsedMs - it.from.receivedElapsedMs })
                assertEquals(rules.minParkedMs, proof.parked.sumOf { it.to.receivedElapsedMs - it.from.receivedElapsedMs })
                assertEquals(proof.stopNumber, after.activeGrant?.stopNumber)
                assertEquals(rules.bonusDurationMs - 60_000L, after.confirmed.remainingMs)
                assertBoostedPrice(outbound, inbound.price.settlement.day, rules.bonusPercent)
                val unboosted = ok(RestDrivingPricing.price(outbound.context.trip, inbound.price.settlement.day, emptyList()))
                assertTrue(unboosted.settlement.amount.units < outbound.observed.commit.amount.units)
            } else {
                assertTrue(outbound.departures.isEmpty())
                assertEquals(originalIds, departureIds(context))
                assertEquals(0L, after.confirmed.remainingMs)
                val expected = ok(RestDrivingPricing.price(outbound.context.trip, inbound.price.settlement.day, emptyList()))
                assertEquals(expected, outbound.price)
                assertEquals(expected.settlement.amount, outbound.observed.commit.amount)
            }
            Log.i("RestNegativeMain", "eligible place=$place driveMs=${rules.minDrivingMs} " +
                "parkMs=${rules.minParkedMs} grants=${outbound.departures.size} " +
                "boostedMs=${outbound.price.boostedMs} amount=${outbound.observed.commit.amount}")
        }

    private fun withPreparedSimulation(run: (View, EdgeFrames, GameSessionFactory, RestDemoSaved) -> Unit) {
        val context = instrumentation.targetContext
        assertEquals("io.drivegrowth.verify.restnegativef", context.packageName)
        listOf("unified-simulation.db", "game-ledger-vehicle.db", RestSimulationActivity.DATABASE).forEach {
            assertFalse("fresh isolated database required: $it", context.getDatabasePath(it).exists())
        }
        lateinit var frames: EdgeFrames
        assertNull(HostSimulationProvider.instrumentationFactory)
        HostSimulationProvider.instrumentationFactory = { EdgeFrames(it).also { input -> frames = input } }
        try {
            val root = activity.window.decorView
            listOf(R.string.onboarding_start, VehicleId("hyundai_casper"), R.string.onboarding_review,
                R.string.onboarding_confirm, R.string.onboarding_to_shop).forEach { click(root, it) }
            choosePage(root, "탁송")
            click(root, "simulation:rest-start")

            val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"),
                InputSource.SIMULATION)
            await("existing positive flow has finished and released the Main controls", 90_000) {
                root.findViewWithTag<Spinner>("host:destination")?.isEnabled == true &&
                    root.findViewWithTag<TextView>("simulation:status")?.text?.contains("단계 72/72") == true
            }
            val baseline = requireNotNull(ok(readRestDemo(games)))
            assertEquals(InputSource.SIMULATION, baseline.original.context.identity.source)
            assertEquals(1_100_000L, baseline.projection.confirmed.remainingMs)
            onUi { frames.beginManual() }
            run(root, frames, games, baseline)
        } finally {
            HostSimulationProvider.instrumentationFactory = null
        }
    }

    private fun send(frames: EdgeFrames, delta: Long, moving: Boolean,
        place: ParkPlace = ParkPlace.OUTSIDE): DrivingRecordingResult {
        val done = CountDownLatch(1)
        val sample = onUi { frames.step(delta, moving, place) { done.countDown() } }
        assertTrue("Main observation worker did not finish", done.await(10, TimeUnit.SECONDS))
        assertNull(frames.rentalFailure)
        return ok(checkNotNull(frames.result)).also {
            assertNull(it.waiting)
            assertEquals("Main accepted the original vehicle sample", sample, it.state.anchor)
        }
    }

    private fun checkStop(frames: EdgeFrames, games: GameSessionFactory, context: Context,
        place: ParkPlace, previousDay: DrivingDay, percent: Int): DrivingDay {
        val before = projection(games)
        val beforeIds = departureIds(context)
        send(frames, 1_000, moving = true)
        send(frames, 60_000, moving = true)
        send(frames, 1_000, moving = false, place)
        val inbound = send(frames, 60_000, moving = false, place).committed.single()
        val inboundResult = original(games, inbound.trip.id)
        assertTrue(inboundResult.departures.isEmpty())
        assertBoostedPrice(inboundResult, previousDay, percent)
        val firstPark = projection(games)
        assertEquals(before.confirmed.remainingMs - 60_000L, firstPark.confirmed.remainingMs)

        repeat(4) { assertTrue(send(frames, 60_000, moving = false, place).committed.isEmpty()) }
        val endPark = projection(games)
        assertEquals(firstPark.confirmed, endPark.confirmed)
        assertEquals(firstPark.effective, endPark.effective)
        assertEquals(firstPark.confirmedDrives, endPark.confirmedDrives)
        assertEquals(firstPark.activeGrant, endPark.activeGrant)
        assertEquals(beforeIds, departureIds(context))

        send(frames, 1_000, moving = true)
        send(frames, 60_000, moving = true)
        send(frames, 1_000, moving = false)
        val outbound = send(frames, 60_000, moving = false).committed.single()
        val outboundResult = original(games, outbound.trip.id)
        assertTrue(outboundResult.departures.isEmpty())
        assertBoostedPrice(outboundResult, inboundResult.price.settlement.day, percent)
        val final = projection(games)
        assertEquals(before.confirmed.remainingMs - 120_000L, final.confirmed.remainingMs)
        assertEquals(before.activeGrant, final.activeGrant)
        assertEquals(beforeIds, departureIds(context))
        Log.i("RestNegativeMain", "place=$place " +
            "parkMs=300000 heldMs=${endPark.confirmed.remainingMs} departures=${beforeIds.size} " +
            "inbound=${inbound.trip.id} outbound=${outbound.trip.id} amount=${outbound.commit.amount}")
        return outboundResult.price.settlement.day
    }

    private fun driveDuration(receipt: RestDrivingCommit): Long = receipt.context.trip.segments.sumOf { segment ->
        if (segment.from.gear == Observation.Available(Gear.DRIVE) &&
            segment.to.gear == Observation.Available(Gear.DRIVE)) {
            segment.to.receivedElapsedMs - segment.from.receivedElapsedMs
        } else 0L
    }

    private fun assertBoostedPrice(receipt: RestDrivingCommit, previousDay: DrivingDay, percent: Int) {
        val windows = receipt.context.trip.segments.mapIndexedNotNull { index, segment ->
            val start = segment.from
            val end = segment.to
            if (start.gear != Observation.Available(Gear.DRIVE) || end.gear != Observation.Available(Gear.DRIVE)) null
            else RestPricingWindow(index, 0, end.receivedElapsedMs - start.receivedElapsedMs, percent)
        }
        assertEquals(60_000L, windows.sumOf { it.untilOffsetMs })
        val expected = ok(RestDrivingPricing.price(receipt.context.trip, previousDay, windows))
        assertEquals(expected, receipt.price)
        assertEquals(expected.settlement.amount, receipt.observed.commit.amount)
    }

    private fun projection(games: GameSessionFactory): RestDrivingProjection =
        ok(games.open()).use { ok(it.store.restDriving.state(it.identity.profileId)) }

    private fun original(games: GameSessionFactory, driveId: String): RestDrivingCommit =
        ok(games.open()).use { requireNotNull(ok(it.store.restDriving.committed(it.identity.profileId, driveId))) }

    private fun departureIds(context: Context): List<String> =
        SQLiteDatabase.openDatabase(context.getDatabasePath("unified-simulation.db").path, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT request_id FROM rest_departures ORDER BY stop", null).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        }

    private fun chooseProgress(root: View) = choosePage(root, "퀘스트·진행")

    private fun choosePage(root: View, name: String) {
        await("the currently attached Main navigation selects $name") {
            val menu = root.findViewWithTag<Spinner>("host:destination")
                ?.takeIf { it.isShown && it.isEnabled && it.isLaidOut && !it.isLayoutRequested } ?: return@await false
            if (menu.selectedItem?.toString() == name) return@await true
            menu.setSelection((0 until menu.count).single { menu.getItemAtPosition(it).toString() == name })
            false
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun click(root: View, tag: Any) {
        await("active button $tag") {
            root.findViewWithTag<Button>(tag)?.let { it.isShown && it.isEnabled && it.performClick() } == true
        }
    }

    private fun await(label: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < until) {
            if (onUi(condition)) return
            SystemClock.sleep(80)
        }
        fail(label)
    }

    private fun <T> onUi(action: () -> T): T = FutureTask(action).also(instrumentation::runOnMainSync).get()
    private fun <T> ok(result: ContractResult<T>): T = (result as? ContractResult.Success)?.value ?: error(result.toString())

    private enum class ParkPlace { OUTSIDE, MISSING, RECOGNIZED }

    private class EdgeFrames(private val regular: UnifiedSimulation) : HostSimulation by regular {
        private var accept: ((DrivingFrame, () -> Unit) -> Unit)? = null
        @Volatile private var point: Pair<DrivingFrame, SimulationRestSample>? = null
        @Volatile private var manualClock: RentalClock? = null
        private var manualControls = true
        @Volatile var result: ContractResult<DrivingRecordingResult>? = null
            private set
        @Volatile var rentalFailure: ContractResult.Failure? = null
            private set

        override val controlsAllowed get() = if (manualClock == null) regular.controlsAllowed else manualControls

        override fun attach(worker: Executor?, accept: (DrivingFrame, () -> Unit) -> Unit) {
            this.accept = accept
            if (manualClock == null) regular.attach(worker, accept)
        }

        override fun stop() {
            accept = null
            regular.stop()
        }

        fun beginManual() {
            checkNotNull(accept)
            manualClock = (regular.clock() as ContractResult.Success).value
            regular.stop()
        }

        fun step(delta: Long, moving: Boolean, place: ParkPlace, done: () -> Unit): VehicleObservation {
            val prior = checkNotNull(manualClock)
            val clock = prior.copy(epoch = EpochMillis(prior.epoch.value + delta), elapsedMs = prior.elapsedMs + delta)
            val sample = VehicleObservation(InputSource.SIMULATION, clock.elapsedMs, clock.epoch,
                Observation.Available(if (moving) 10.0 else 0.0),
                Observation.Available(if (moving) Gear.DRIVE else Gear.PARK), Observation.Available(moving))
            val frame = DrivingFrame(sample, Observation.Available(ZoneId.of("UTC")))
            val area = when (place) {
                ParkPlace.MISSING -> Observation.Missing(MissingReason.NO_DATA)
                ParkPlace.OUTSIDE, ParkPlace.RECOGNIZED ->
                    Observation.Available(RestSimulationScenario.testArea(sample, inside = place == ParkPlace.RECOGNIZED))
            }
            point = frame to SimulationRestSample(RestClockDomain("unified-simulation-elapsed", clock.bootId),
                area, restRun = true, ordinaryTerminal = false)
            manualClock = clock
            result = null
            rentalFailure = null
            manualControls = false
            checkNotNull(accept)(frame) {
                manualControls = !moving
                changed?.invoke()
                done()
            }
            return sample
        }

        override fun clock() = manualClock?.let { ContractResult.Success(it) } ?: regular.clock()

        override fun restSample(frame: DrivingFrame): SimulationRestSample? =
            if (manualClock == null) regular.restSample(frame) else point?.takeIf { it.first == frame }?.second

        override fun observed(frame: DrivingFrame, result: ContractResult<DrivingRecordingResult>): ContractResult<Unit> {
            if (manualClock == null) return regular.observed(frame, result)
            check(point?.first == frame)
            this.result = result
            return if (result is ContractResult.Failure) result else ContractResult.Success(Unit)
        }

        override fun rentalResult(result: ContractResult<RentalRuntimeSnapshot>) {
            if (manualClock == null) regular.rentalResult(result)
            else rentalFailure = when (result) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> result.value.deferred.values.firstOrNull()?.let { ContractResult.Failure(it) }
            }
        }

        override fun inputConsumed(frame: DrivingFrame, recorded: DrivingRecordingResult, rental: RentalRuntimeSnapshot) {
            if (manualClock == null) regular.inputConsumed(frame, recorded, rental)
        }
    }
}
