package io.drivegrowth.app.simulation

import android.content.SharedPreferences
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.delivery.*
import io.drivegrowth.app.host.driving.DrivingRecordingResult
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.math.RoundingMode
import java.util.concurrent.Executor

/** SDK callback과 독립된 시나리오 도착. 원래 게임 route/source/profile에만 결합한다. */
internal class SimulationNavigation(private val prefs: SharedPreferences, private val games: GameSessionFactory,
    private val clock: () -> ContractResult<RentalClock>, private val main: Executor) : HostDeliveryNavigation {
    override var changed: (() -> Unit)? = null
    override val snapshot: io.drivegrowth.app.vehicle.navigation.NavigationSnapshot? get() = null
    override val description get() = "시연 종착지 · 예상 36,000m · 명시 모의 도착 사용\n실제 내비 조회/SDK 도착 수신이 아닙니다."
    private var enabled = false
    override val port = object : DeliveryNavigationPort {
        override fun chooseDestination(identity: GameIdentity, active: ActiveDelivery?): ContractResult<Unit> {
            if (identity.source != InputSource.SIMULATION) return ContractResult.Failure(ContractError.INVALID_INPUT)
            if (!prefs.edit().putBoolean("destinationConfirmed", false).commit()) return ContractResult.Failure(ContractError.STORAGE_FAILURE)
            main.execute { changed?.invoke() }; return ContractResult.Success(Unit)
        }
        override fun read(identity: GameIdentity): ContractResult<DeliveryNavigationSnapshot> {
            if (identity.source != InputSource.SIMULATION) return ContractResult.Failure(ContractError.INVALID_INPUT)
            val confirmed = prefs.getBoolean("destinationConfirmed", false)
            val arrivalRoute = prefs.getString("arrivalRoute", null)
            val same = prefs.getString("arrivalProfile", null) == identity.profileId.value
            val arrival: Observation<DeliveryArrivalEvidence> = if (same && arrivalRoute != null)
                Observation.Available(DeliveryArrivalEvidence(RouteId(arrivalRoute), InputSource.SIMULATION,
                    EpochMillis(prefs.getLong("arrivalAt", -1)))) else Observation.Missing(MissingReason.NO_DATA)
            val moved = if (prefs.contains("moved")) Observation.Available(ObservedDistance(prefs.getLong("moved", 0), InputSource.SIMULATION))
                else Observation.Missing(MissingReason.NO_DATA)
            return ContractResult.Success(DeliveryNavigationSnapshot(if (confirmed) DestinationKey("unified-simulation-destination") else null,
                "시연 종착지", Observation.Available(EstimatedRouteDistance(36_000, InputSource.SIMULATION)), moved,
                arrival, if (confirmed) {
                    if (!prefs.contains("destinationRevision")) return ContractResult.Failure(ContractError.STORAGE_FAILURE)
                    prefs.getLong("destinationRevision", -1)
                } else null))
        }
    }
    override fun setControlsEnabled(value: Boolean) { enabled = value }
    override fun refresh() { if (enabled) changed?.invoke() }
    override fun confirm(): ContractResult<Unit> = if (!enabled) ContractResult.Failure(ContractError.UX_RESTRICTED)
        else if (!prefs.edit().putBoolean("destinationConfirmed", true)
            .putLong("destinationRevision", if (prefs.getBoolean("destinationConfirmed", false)) prefs.getLong("destinationRevision", -1)
                else Math.addExact(prefs.getLong("destinationRevision", 0), 1)).commit()) ContractResult.Failure(ContractError.STORAGE_FAILURE)
        else { changed?.invoke(); ContractResult.Success(Unit) }
    override fun close() { enabled = false; changed = null }

    fun observed(timeline: SimulationTimeline, result: ContractResult<DrivingRecordingResult>): ContractResult<Unit> {
        val actual = (result as? ContractResult.Success)?.value ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (actual.state.identity.source != InputSource.SIMULATION || actual.state.anchor != timeline.frame().observation) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val actor = actual.state.identity
        if (timeline.stage == 1) {
            val start = timeline.elapsedMs - 1000
            if (prefs.getLong("routeRun", -1) != start) {
                val progress = when (val opened = games.open()) {
                    is ContractResult.Failure -> return opened
                    is ContractResult.Success -> opened.value.use { game ->
                        if (game.identity != actor) return ContractResult.Failure(ContractError.STATE_CONFLICT)
                        when (val read = game.store.delivery.inProgress()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
                    }
                }
                check(prefs.edit().putLong("routeRun", start).putString("runProfile", actor.profileId.value)
                    .putString("runRoute", progress?.route?.value).putString("runRequest", progress?.active?.requestId?.value).commit())
            }
        }
        val trip = actual.state.trip ?: actual.committed.lastOrNull()?.trip
        if (trip != null) check(prefs.edit().putLong("moved", trip.meters.setScale(0, RoundingMode.DOWN).longValueExact()).commit())
        if (!timeline.arrival || prefs.getString("runProfile", null) != actor.profileId.value) return ContractResult.Success(Unit)
        val route = prefs.getString("runRoute", null) ?: return ContractResult.Success(Unit) // 출발 당시 활성 탁송이 없으면 도착을 만들지 않는다.
        if (prefs.getString("arrivalRoute", null) == route) return ContractResult.Success(Unit) // 최초 원래 사건 시각을 보존한다.
        val current = when (val opened = games.open()) {
            is ContractResult.Failure -> return opened
            is ContractResult.Success -> opened.value.use { game ->
                if (game.identity != actor) return ContractResult.Failure(ContractError.STATE_CONFLICT)
                when (val read = game.store.delivery.inProgress()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
            }
        } ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val now = (clock() as? ContractResult.Success)?.value ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (current.route.value != route || current.active.requestId.value != prefs.getString("runRequest", null) ||
            current.startedAt.value > timeline.clock.epoch.value || now != timeline.clock) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        check(prefs.edit().putString("arrivalProfile", actor.profileId.value).putString("arrivalRoute", route)
            .putLong("arrivalAt", timeline.clock.epoch.value).commit())
        android.util.Log.i("DriveGrowthSimulation", "simulation_arrival source=SIMULATION route=$route utcMs=${timeline.clock.epoch.value}")
        main.execute { changed?.invoke() }
        return ContractResult.Success(Unit)
    }
}
