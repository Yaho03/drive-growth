package io.drivegrowth.app.host.delivery

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.garage.FirstGrant
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/** SDK callbacks come only from the production Pleos port. The active delivery is an isolated ledger fixture. */
@Suppress("DEPRECATION")
class Issue80NativeNavigationTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val dbName = "issue80-native-vehicle.db"
    private val evidence by lazy { context.filesDir.resolve("issue80-native.jsonl") }

    private data class Raw(val listener: Int, val value: NavigationSdkEvent)

    @Synchronized
    private fun event(kind: String, vararg values: Pair<String, Any?>) {
        val json = JSONObject().put("kind", kind)
            .put("utcMs", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime())
        for ((name, value) in values) json.put(name, value ?: JSONObject.NULL)
        evidence.appendText(json.toString() + "\n")
    }

    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }

    private fun await(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        event("not_observed", "boundary" to label)
        fail(label)
    }

    private fun permission(name: String): NavigationPermission {
        val manifest = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return when {
            name !in manifest.requestedPermissions.orEmpty() -> NavigationPermission.NOT_DECLARED
            context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED -> NavigationPermission.DENIED
            else -> NavigationPermission.GRANTED
        }
    }

    private fun fresh(stamp: NavigationStamp) =
        SystemClock.elapsedRealtime() - stamp.receivedElapsedMillis in 0..5_000L

    private fun stampEvidence(stamp: NavigationStamp?) = stamp?.let {
        JSONObject().put("utcMs", it.receivedAtMillis).put("elapsedMs", it.receivedElapsedMillis)
            .put("generation", it.connectionGeneration).put("sequence", it.sequence).put("source", it.source.name)
    }

    private fun atDestination(value: NavigationDestination, latitude: Double, longitude: Double) =
        abs(value.latitude - latitude) <= 0.001 && abs(value.longitude - longitude) <= 0.001

    private fun ledger(games: GameSessionFactory): JSONObject = ok(games.open()).use { game ->
        ok(game.store.read { db ->
            JSONObject().put("profile", game.identity.profileId.value).put("source", game.identity.source.name).also { json ->
                for (table in listOf("account", "vehicles", "settlements", "discoveries", "delivery_lists", "delivery_requests",
                    "delivery_active", "delivery_routes", "delivery_route_history", "delivery_outcomes", "delivery_held",
                    "delivery_hold_outcomes", "delivery_changes", "host_command_checkpoint")) {
                    val rows = org.json.JSONArray()
                    db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = org.json.JSONArray()
                            for (i in 0 until cursor.columnCount) row.put(if (cursor.isNull(i)) JSONObject.NULL else cursor.getString(i))
                            rows.put(row)
                        }
                    }
                    json.put(table, rows)
                }
            }
        })
    }

    fun testActualSdkRouteReplacementDoesNotPayUncorrelatedArrival() {
        assertEquals("io.drivegrowth.verify.issue80", context.packageName)
        assertFalse(context.getDatabasePath(dbName).exists())
        assertFalse(evidence.exists())
        val readPermission = PleosNavigationSource.READ_PERMISSION
        val searchPermission = PleosNavigationSource.SEARCH_PERMISSION
        event("permission", "read" to permission(readPermission).name, "search" to permission(searchPermission).name)
        assertEquals(NavigationPermission.GRANTED, permission(readPermission))
        assertEquals(NavigationPermission.GRANTED, permission(searchPermission))

        val now = { EpochMillis(System.currentTimeMillis()) }
        val today = { LocalDate.now(ZoneId.of("Asia/Seoul")) }
        val games = GameSessionFactory(context, mapOf(InputSource.VEHICLE to dbName), InputSource.VEHICLE)
        val onboarding = StoredOnboardingHostSource(games)
        val identity = ok(onboarding.read()).identity
        ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
        val definition = ok(AssetTrialDefinitionProvider(context.assets).load())
        val rules = DeliveryHostRules(ok(definition.deliveryRules()), ok(definition.deliveryChangeRules()),
            ok(definition.deliverySettlementRules()))
        val journal = SessionDeliveryJournal(games)
        val request = RequestId("issue80-native-fixture")
        val route = RouteId("issue80-ledger-fixture")
        val beforeCandidates = ledger(games)
        assertEquals(0, beforeCandidates.getJSONArray("delivery_active").length())
        assertEquals(0, beforeCandidates.getJSONArray("delivery_lists").length())
        lateinit var before: JSONObject

        fun prepareNoPayoutFixture() {
            val selected = ok(games.open()).use { game ->
                val content = ok(game.content)
                val offer = DeliveryOffer.Income(DeliveryOfferId("offer-0"), FirstGrant.CANDIDATES.first(), GameMoney(10))
                val list = ok(game.store.delivery.saveList(DeliveryList(0, identity.source, today(),
                    content.catalog.contentVersion, RuleVersion("issue80-no-payout-fixture"), listOf(offer), 1)))
                val proposed = DeliveryProposed(DestinationKey("issue80-fixture-destination"),
                    EstimatedRouteDistance(1_000, identity.source), list, list.offers)
                DeliveryHostOperation.Select(proposed, offer.id, request, route, now())
            }
            ok(journal.save(identity, selected))
            ok(journal.execute(identity, selected))
            ok(journal.finish(identity, selected))
            before = ledger(games)
            assertEquals(1, before.getJSONArray("delivery_active").length())
            assertEquals(0, before.getJSONArray("delivery_outcomes").length())
            assertEquals(0, before.getJSONArray("host_command_checkpoint").length())
            event("ledger", "phase" to "before", "rows" to before)
        }

        val confirmation = NavigationDestinationConfirmation(::fresh) { DestinationKey("issue80-confirmed-A") }
        confirmation.setEnabled(true)
        confirmation.bind(identity)
        val port = FNavigationDeliveryPort({ who -> confirmation.bind(who); confirmation.snapshot },
            { who, _ -> confirmation.selected(who) }, { who, snapshot -> deliveryEstimateSource(who, snapshot, ::fresh) },
            { Observation.Missing(MissingReason.NO_DATA) }, ::fresh, { _, _ -> ContractResult.Failure(ContractError.DATA_UNAVAILABLE) })
        val host = StoredDeliveryHostSource(FactoryDeliveryHostQueries(games) { it.terms() }, { ContractResult.Success(rules) },
            port, journal, today, now, { Random(80) }, { error("Unexpected new delivery") }, { error("Unexpected new route") })
        fun consumeCandidates(quote: DeliveryNavigationSnapshot, routeId: String) {
            val result = ok(host.read())
            assertEquals(identity, result.identity)
            assertNull(result.pending)
            val screen = result.screen as? DeliveryScreen.Offers
            assertNotNull("The actual host must produce Offers from the confirmed SDK quote", screen)
            val proposal = checkNotNull(screen).proposal.proposed
            assertEquals(proposal, result.displayedProposal)
            assertTrue("At least one candidate must be produced", proposal.offers.isNotEmpty())
            assertEquals(quote.destination, proposal.destination)
            assertEquals(Observation.Available(proposal.estimated), quote.estimated)
            assertEquals(identity.source, proposal.estimated.source)
            assertEquals(identity.source, proposal.list.source)
            assertEquals(rules.proposal.ruleVersion, proposal.list.ruleVersion)
            val consumed = result.navigation
            assertNotNull("The host must report the navigation evidence it consumed", consumed)
            assertEquals(quote.destination, consumed!!.destination)
            assertEquals(quote.estimated, consumed.estimated)
            assertEquals(quote.selectionRevision, consumed.selectionRevision)
            assertEquals(quote.metadata?.connectionGeneration, consumed.metadata?.connectionGeneration)
            for (field in listOf("destination", "remainingEstimatedMeters", "routeId")) {
                val original = quote.metadata?.fields?.get(field)
                assertNotNull("Missing original SDK field metadata: $field", original)
                assertEquals(original, consumed.metadata?.fields?.get(field))
            }
            ok(games.open()).use { game ->
                assertEquals(identity, game.identity)
                assertEquals(ok(game.content).catalog.contentVersion, proposal.list.contentVersion)
                assertEquals(listOf(proposal.list), ok(game.store.delivery.lists(proposal.list.createdOn)))
            }
            val after = ledger(games)
            assertEquals(1, after.getJSONArray("delivery_lists").length())
            for (key in beforeCandidates.keys()) {
                if (key != "delivery_lists") assertEquals(key, beforeCandidates.get(key).toString(), after.get(key).toString())
            }
            event("initial_candidates", "status" to "PASSED", "routeId" to routeId,
                "destinationKey" to proposal.destination.value, "estimatedMeters" to proposal.estimated.meters,
                "source" to proposal.estimated.source.name, "selectionRevision" to consumed.selectionRevision,
                "generation" to consumed.metadata?.connectionGeneration, "band" to proposal.list.band,
                "contentVersion" to proposal.list.contentVersion.value, "ruleVersion" to proposal.list.ruleVersion.value,
                "offerIds" to org.json.JSONArray(proposal.offers.map { it.id.value }), "storedListCount" to 1)
        }

        val raw = CopyOnWriteArrayList<Raw>()
        val releasedListeners = CopyOnWriteArrayList<Int>()
        val failedReleases = CopyOnWriteArrayList<Int>()
        val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val handler = Handler(Looper.getMainLooper())
        val clock = object : NavigationClock {
            override fun utcMillis() = System.currentTimeMillis()
            override fun elapsedMillis() = SystemClock.elapsedRealtime()
        }
        var listenerOrdinal = 0
        val adapter = NavigationAdapter(worker, Executor { handler.post(it) }, clock, NavigationTimer { delay, action ->
            val future = worker.schedule(action, delay, TimeUnit.MILLISECONDS)
            NavigationCancellation { future.cancel(false) }
        }, { permission(readPermission) }, { permission(searchPermission) }, {
            val listener = ++listenerOrdinal
            val sdk = PleosNavigationSdkPort(context, worker)
            object : NavigationSdkPort by sdk {
                override fun initialize(connected: () -> Unit, failed: (Throwable) -> Unit, events: (NavigationSdkEvent) -> Unit) {
                    sdk.initialize(connected, failed) { value ->
                        val detail = when (value) {
                            is NavigationSdkEvent.Destination -> JSONObject()
                                .put("latitude", value.destination.latitude.toString()).put("longitude", value.destination.longitude.toString())
                                .put("meters", value.remainingMeters).toString()
                            is NavigationSdkEvent.Arrived -> JSONObject()
                                .put("name", value.name ?: JSONObject.NULL).put("distance", value.distanceMeters)
                                .put("duration", value.durationSeconds).toString()
                            else -> value.toString()
                        }
                        events(value)
                        event("sdk_callback", "listener" to listener, "event" to value.javaClass.simpleName, "detail" to detail)
                        raw += Raw(listener, value)
                    }
                }

                override fun close() {
                    try {
                        sdk.close()
                        releasedListeners += listener
                        event("sdk_port_released", "listener" to listener)
                    } catch (error: Throwable) {
                        failedReleases += listener
                        event("sdk_port_release_failed", "listener" to listener, "error" to error.javaClass.simpleName)
                        throw error
                    }
                }
            }
        }, 12_000)
        val accept: (NavigationEvent) -> Unit = { next ->
            when (next) {
                is NavigationEvent.State -> {
                    confirmation.accept(next.snapshot)
                    val state = next.snapshot
                    event("adapter_state", "generation" to state.stamp.connectionGeneration, "seq" to state.stamp.sequence,
                        "connection" to state.connection.name, "meters" to state.remainingEstimatedMeters.toString(),
                        "route" to state.routeId.toString(), "mode" to state.guidance.toString(), "failure" to state.failure?.toString())
                }
                is NavigationEvent.ArrivalObserved -> event("adapter_arrival_observation",
                    "generation" to next.observation.stamp.connectionGeneration, "seq" to next.observation.stamp.sequence,
                    "correlation" to next.correlation.toString())
                is NavigationEvent.Command -> event("app_command", "record" to next.record.toString())
                is NavigationEvent.Failure -> event("adapter_failure", "failure" to next.failure.toString())
            }
        }

        fun consumePublishedEvents() {
            worker.submit(Runnable { }).get(3, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
        }

        fun noPayout(phase: String) {
            val nav = ok(port.read(identity))
            assertEquals(Observation.Missing(MissingReason.NO_DATA), nav.arrival)
            val state = ok(host.read())
            assertNull(state.pending)
            val active = state.screen as? DeliveryScreen.Active
            assertNotNull("Same active delivery must be retained", active)
            assertEquals(request, active!!.delivery.active.requestId)
            val after = ledger(games)
            event("ledger", "phase" to phase, "rows" to after)
            assertEquals(before.toString(), after.toString())
        }

        fun healthySnapshot(phase: String): NavigationSnapshot {
            val state = checkNotNull(confirmation.snapshot)
            event("adapter_boundary", "phase" to phase, "connection" to state.connection.name,
                "generation" to state.stamp.connectionGeneration, "failure" to state.failure?.toString(),
                "route" to state.routeId.toString())
            assertEquals("$phase requires a live SDK connection", NavigationConnection.CONNECTED_OBSERVED, state.connection)
            assertNull("$phase must not rely on an SDK failure or timeout", state.failure)
            return state
        }

        fun blocked(phase: String, generation: Long, routeId: String? = null) {
            synchronized(confirmation) {
                val state = healthySnapshot(phase)
                assertEquals("$phase adapter generation", generation, state.stamp.connectionGeneration)
                val published = state.routeId as? NavigationField.Value
                if (routeId != null) {
                    assertNotNull("$phase must publish a real B route ID", published)
                    assertEquals(routeId, published!!.value)
                    assertEquals(generation, published.stamp.connectionGeneration)
                    assertEquals(NavigationSource.PLEOS_NAVI_SDK, published.stamp.source)
                }
                val nav = ok(port.read(identity))
                assertNull(nav.destination)
                assertTrue(nav.estimated is Observation.Missing)
                assertTrue(confirmation.confirm() is ContractResult.Failure)
                event("quote_blocked", "phase" to phase, "generation" to generation,
                    "routeId" to published?.value, "estimate" to nav.estimated.toString())
            }
            noPayout(phase)
        }

        fun bCallbacks(start: Int, listener: Int): List<NavigationSdkEvent> =
            raw.drop(start).filter { it.listener == listener }.map { it.value }

        fun observedB(events: List<NavigationSdkEvent>) =
            events.filterIsInstance<NavigationSdkEvent.Destination>()
                .any { atDestination(it.destination, 37.3948, 127.1112) } &&
                events.filterIsInstance<NavigationSdkEvent.Guidance>().any { it.mode == "ROUTE_GUIDING" }

        var testFailure: Throwable? = null
        var candidateFailure: AssertionError? = null
        var candidatesConfirmed = false
        try {
            adapter.connect(accept)
            await("SDK connection A", 14_000) { confirmation.snapshot?.connection == NavigationConnection.CONNECTED_OBSERVED }
            adapter.refresh()
            await("SDK reports the prepared A destination and a route ID", 14_000) {
                val values = raw.filter { it.listener == 1 }.map { it.value }
                values.filterIsInstance<NavigationSdkEvent.Destination>().any { atDestination(it.destination, 37.41289678, 127.09383464) } &&
                    values.filterIsInstance<NavigationSdkEvent.RouteId>().any { !it.value.isNullOrBlank() } &&
                    values.filterIsInstance<NavigationSdkEvent.Guidance>().any { it.mode == "ROUTE_GUIDING" }
            }
            val aRoute = raw.filter { it.listener == 1 }.mapNotNull { (it.value as? NavigationSdkEvent.RouteId)?.value }
                .last { it.isNotBlank() }
            // Wait for the corresponding published state before deciding whether A could be quoted.
            consumePublishedEvents()
            val (initialGeneration, quotedA) = synchronized(confirmation) {
                val initial = healthySnapshot("A")
                val generation = initial.stamp.connectionGeneration
                val routeField = initial.routeId as? NavigationField.Value
                val destination = initial.destination as? NavigationField.Value
                val distance = initial.remainingEstimatedMeters as? NavigationField.Value
                val actualA = destination?.value
                val matchesPreparedA = actualA != null && atDestination(actualA, 37.41289678, 127.09383464)
                val selection = if (matchesPreparedA)
                    (confirmation.confirm() as? ContractResult.Success)?.value else null
                val navigation = ok(port.read(identity))
                val estimate = (navigation.estimated as? Observation.Available)?.value
                val sameDestination = selection != null && selection.actualDestination == actualA &&
                    selection.actualStamp == destination?.stamp && navigation.destination == selection.destination
                val ageMs = distance?.stamp?.let { SystemClock.elapsedRealtime() - it.receivedElapsedMillis }
                val currentRoute = routeField != null && routeField.value == aRoute &&
                    routeField.stamp.connectionGeneration == generation && routeField.stamp.source == NavigationSource.PLEOS_NAVI_SDK
                val freshSdkDistance = distance != null && ageMs != null && ageMs in 0L..5_000L &&
                    distance.stamp == destination?.stamp && distance.stamp.connectionGeneration == generation &&
                    distance.stamp.source == NavigationSource.PLEOS_NAVI_SDK
                val available = sameDestination && estimate != null
                val issue37Quote = sameDestination && currentRoute && freshSdkDistance &&
                    estimate != null && estimate.meters > 0 && estimate.meters == distance?.value && estimate.source == identity.source
                event("initial_quote", "available" to available, "issue37QuoteEvidence" to issue37Quote,
                    "rawSdkRouteId" to aRoute, "publishedRouteId" to routeField?.value,
                    "routeStamp" to stampEvidence(routeField?.stamp), "generation" to generation,
                    "destinationName" to actualA?.name, "latitude" to actualA?.latitude, "longitude" to actualA?.longitude,
                    "matchesPreparedA" to matchesPreparedA, "sameConfirmedDestination" to sameDestination,
                    "destinationStamp" to stampEvidence(destination?.stamp), "distanceStamp" to stampEvidence(distance?.stamp),
                    "distanceAgeMs" to ageMs, "sdkMeters" to distance?.value, "estimatedMeters" to estimate?.meters,
                    "estimateSource" to estimate?.source?.name, "selectionRevision" to selection?.selectionRevision,
                    "destinationKey" to navigation.destination?.value)
                if (issue37Quote) {
                    try {
                        consumeCandidates(navigation, aRoute)
                        candidatesConfirmed = true
                    } catch (error: AssertionError) {
                        candidateFailure = error
                        event("initial_candidates", "status" to "FAILED", "error" to error.message)
                    }
                } else {
                    event("initial_candidates", "status" to "NOT_ATTEMPTED", "reason" to "No matching fresh positive SDK quote")
                }
                generation to available
            }
            prepareNoPayoutFixture()
            noPayout("A")

            val cancelStart = raw.size
            adapter.cancelRoute("issue80-cancel-A")
            await("Native cancellation callback for the isolated A trial", 18_000) {
                raw.drop(cancelStart).any { it.listener == 1 && it.value == NavigationSdkEvent.RouteCancelled }
            }
            await("Adapter invalidates A", 4_000) { confirmation.snapshot?.destination is NavigationField.Missing }
            consumePublishedEvents()
            blocked("cancel", initialGeneration)

            val bStart = raw.size
            adapter.requestRoute(NavigationRouteRequest("issue80-request-B", 37.3948, 127.1112, "PangyoStation"))
            await("Native B route starts", 25_000) {
                raw.drop(bStart).any { it.listener == 1 && it.value is NavigationSdkEvent.RouteStarted }
            }
            val firstBQuery = raw.size
            adapter.refresh()
            await("Native B coordinates, guidance and a different route ID", 14_000) {
                val values = bCallbacks(firstBQuery, 1)
                observedB(values) &&
                    values.filterIsInstance<NavigationSdkEvent.RouteId>().any { !it.value.isNullOrBlank() && it.value != aRoute }
            }
            consumePublishedEvents()
            val bRoute = checkNotNull(bCallbacks(firstBQuery, 1).filterIsInstance<NavigationSdkEvent.RouteId>().last().value)
            assertTrue("B route ID must be distinct and nonblank", bRoute.isNotBlank() && bRoute != aRoute)
            event("native_b_sample", "phase" to "B", "listener" to 1, "routeId" to bRoute)
            blocked("B", initialGeneration, bRoute)

            adapter.disconnect()
            await("Adapter disconnect", 6_000) { confirmation.snapshot?.connection == NavigationConnection.DISCONNECTED }
            assertTrue("First SDK listener release must complete before reconnect", 1 in releasedListeners)
            confirmation.setEnabled(false)
            confirmation.setEnabled(true)
            confirmation.bind(identity)
            adapter.connect(accept)
            await("SDK reconnect", 14_000) {
                val state = confirmation.snapshot
                state != null && state.connection == NavigationConnection.CONNECTED_OBSERVED && state.stamp.connectionGeneration > initialGeneration
            }
            val reconnectedGeneration = healthySnapshot("reconnect_before_query").stamp.connectionGeneration
            val secondBQuery = raw.size
            adapter.refresh()
            await("Native B read after reconnect", 14_000) {
                val values = bCallbacks(secondBQuery, 2)
                observedB(values) && values.filterIsInstance<NavigationSdkEvent.RouteId>().any { it.value == bRoute }
            }
            consumePublishedEvents()
            event("native_b_sample", "phase" to "reconnected", "listener" to 2, "routeId" to bRoute)
            blocked("reconnected", reconnectedGeneration, bRoute)
            val observationUntil = SystemClock.elapsedRealtime() + 10_000L
            event("arrival_observation_window_started", "milliseconds" to 10_000)
            while (SystemClock.elapsedRealtime() < observationUntil) {
                SystemClock.sleep((observationUntil - SystemClock.elapsedRealtime()).coerceIn(1L, 250L))
            }
            consumePublishedEvents()
            blocked("observation_window_end", reconnectedGeneration, bRoute)
            val arrivals = raw.drop(cancelStart).count { it.value is NavigationSdkEvent.Arrived }
            event("boundaries_complete", "initialQuote" to quotedA, "arrivalsAfterCancelRequest" to arrivals,
                "arrivalStatus" to if (arrivals == 0) "NOT_OBSERVED_IN_WINDOW" else "OBSERVED_UNCORRELATED")
            assertTrue("Native safety checks completed, but initial A estimate was not available", quotedA)
            candidateFailure?.let { throw it }
            assertTrue("Native safety checks completed, but initial SDK candidate consumption was not confirmed", candidatesConfirmed)
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            adapter.close()
            worker.shutdown()
            val terminated = worker.awaitTermination(5, TimeUnit.SECONDS)
            if (!terminated) worker.shutdownNow()
            val released = terminated && failedReleases.isEmpty() &&
                releasedListeners.sorted() == (1..listenerOrdinal).toList()
            event("worker_release", "terminated" to terminated, "releasedListeners" to releasedListeners.sorted().joinToString(),
                "failedListeners" to failedReleases.sorted().joinToString(), "allPortsReleased" to released)
            if (!released) {
                val error = AssertionError("SDK listener or worker release was not confirmed")
                val prior = testFailure
                if (prior == null) throw error
                prior.addSuppressed(error)
            }
        }
    }
}
