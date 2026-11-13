package io.drivegrowth.app.vehicle.navigation

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.FNavigationDeliveryPort
import io.drivegrowth.app.host.delivery.NavigationDestinationConfirmation
import io.drivegrowth.app.host.delivery.deliveryEstimateSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DestinationKey
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class NavigationAdapterTest {
    private class Queue : Executor { val jobs=ArrayDeque<Runnable>();override fun execute(command:Runnable){jobs.add(command)};fun drain(){while(jobs.isNotEmpty())jobs.removeFirst().run()} }
    private class Time : NavigationClock, NavigationTimer {
        var now=1000L;val jobs=mutableListOf<Pair<Long,()->Unit>>()
        override fun utcMillis()=now+100000;override fun elapsedMillis()=now
        override fun after(delayMillis:Long,action:()->Unit):NavigationCancellation {val job=(now+delayMillis) to action;jobs+=job;return NavigationCancellation{jobs.remove(job)}}
        fun advance(ms:Long){now+=ms;val due=jobs.filter{it.first<=now};jobs.removeAll(due.toSet());due.forEach{it.second()}}
    }
    private class Port : NavigationSdkPort {
        lateinit var connected:()->Unit;lateinit var failed:(Throwable)->Unit;lateinit var events:(NavigationSdkEvent)->Unit
        var calls=0;var queries=0;var closes=0;var exception:RuntimeException?=null
        var closeFailure: RuntimeException? = null
        override fun initialize(connected:()->Unit,failed:(Throwable)->Unit,events:(NavigationSdkEvent)->Unit){this.connected=connected;this.failed=failed;this.events=events}
        override fun query(){queries++}
        override fun requestRoute(request:NavigationRouteRequest){calls++;exception?.let{throw it}}
        override fun cancelRoute(){calls++;exception?.let{throw it}}
        override fun close(){closes++; closeFailure?.let { throw it }}
    }
    private class Rig {
        val worker=Queue();val main=Queue();val time=Time();val ports=mutableListOf<Port>();val events=mutableListOf<NavigationEvent>()
        var read=NavigationPermission.GRANTED;var search=NavigationPermission.GRANTED;var permissionError:RuntimeException?=null;var factoryError:LinkageError?=null
        var onReadPermission: (() -> Unit)? = null
        val adapter=NavigationAdapter(worker,main,time,time,{permissionError?.let{throw it};onReadPermission?.invoke();read},{search},{factoryError?.let{throw it};Port().also{ports+=it}},100)
        fun drain(){worker.drain();main.drain()}
        fun connect():Port {adapter.connect(events::add);drain();return ports.last().also{it.connected();drain()}}
        fun state()=events.filterIsInstance<NavigationEvent.State>().last().snapshot
        fun command()=events.filterIsInstance<NavigationEvent.Command>().last().record
    }
    private fun request(id:String="one")=NavigationRouteRequest(id,37.0,127.0,"요청 목적지","requested-poi")
    @Test fun initializeReturnIsNotConnectionAndPermissionIsPreflight(){
        val r=Rig();r.read=NavigationPermission.NOT_DECLARED;r.adapter.connect(r.events::add);r.drain()
        assertTrue(r.ports.isEmpty());assertEquals(NavigationError.PERMISSION_NOT_DECLARED,r.state().failure!!.error)
        r.read=NavigationPermission.GRANTED;r.adapter.connect(r.events::add);r.drain()
        assertEquals(NavigationConnection.CONNECTING,r.state().connection)
        r.ports.last().connected();r.drain();assertEquals(NavigationConnection.CONNECTED_OBSERVED,r.state().connection)
    }
    @Test fun permissionDenialBeforeSendCanRetryOriginalButNeverChangedPayload(){
        val r=Rig();val p=r.connect();r.search=NavigationPermission.DENIED
        r.adapter.requestRoute(request());r.drain();assertEquals(0,p.calls);assertEquals(NavigationCommandStatus.NOT_SENT,r.command().status)
        r.search=NavigationPermission.GRANTED;r.adapter.retry(NavigationCommand.Route(request().copy(name="변조")));r.drain();assertEquals(0,p.calls)
        r.adapter.retry(NavigationCommand.Route(request()));r.drain();assertEquals(1,p.calls)
        r.adapter.requestRoute(request());r.drain();assertEquals(1,p.calls)
        r.adapter.retry(NavigationCommand.Route(request()));r.drain();assertEquals(1,p.calls)
        assertEquals(NavigationError.RECONCILIATION_REQUIRED,r.events.filterIsInstance<NavigationEvent.Failure>().last().failure.error)
    }
    @Test fun actualPoiIsSeparateAndObservationsNeverProveRequestOrArrival(){
        val r=Rig();val p=r.connect();r.adapter.requestRoute(request());r.drain()
        p.events(NavigationSdkEvent.Destination(NavigationDestination(38.0,128.0,"서버 실제목적지",null,"different",null,null),50));
        p.events(NavigationSdkEvent.RouteId("sdk-route"));p.events(NavigationSdkEvent.Guidance("ROUTE_DRIVE","SIMULATION_GUIDING",false));r.drain()
        assertEquals("different",(r.state().destination as NavigationField.Value).value.poiId)
        assertEquals("requested-poi",(r.state().latestCommand!!.command as NavigationCommand.Route).request.poiId)
        assertEquals(NavigationCommandStatus.SENT_UNCONFIRMED,r.state().latestCommand!!.status)
        p.events(NavigationSdkEvent.RouteEnded);p.events(NavigationSdkEvent.RouteId(""));p.events(NavigationSdkEvent.Destination(NavigationDestination(38.0,128.0,"실제",null,null,null,null),0));r.drain()
        assertTrue(r.events.none{it is NavigationEvent.ArrivalObserved})
        p.events(NavigationSdkEvent.Arrived("실제",null,3153,244));r.drain()
        assertEquals(NavigationArrivalCorrelation.AwaitingIndependentRouteEvidence,r.events.filterIsInstance<NavigationEvent.ArrivalObserved>().single().correlation)
    }
    @Test fun stopDiscardsQueuedAndOldCallbacksAndClosesOnlyItsPort(){
        val r=Rig();val p=r.connect();p.events(NavigationSdkEvent.RouteId("old"));r.worker.drain()
        r.adapter.disconnect();r.drain();assertEquals(1,p.closes)
        assertEquals(NavigationConnection.DISCONNECTED,r.state().connection)
        val q=r.connect();p.events(NavigationSdkEvent.Arrived("늦음",null,1,1));p.connected();r.drain()
        assertEquals(0,q.closes);assertTrue(r.events.none{it is NavigationEvent.ArrivalObserved})
        assertTrue(r.state().routeId is NavigationField.Missing)
    }
    @Test fun cancelThenNewRouteNeverAttributesLateOldArrivalToCurrentRoute(){
        val r=Rig();val p=r.connect();r.adapter.requestRoute(request("A"));r.adapter.cancelRoute("cancelA");r.adapter.requestRoute(request("B"));r.drain()
        val actualB = NavigationDestination(37.0, 127.0, "같은 이름", "같은 주소", null, null, null)
        p.events(NavigationSdkEvent.RouteId("B"))
        p.events(NavigationSdkEvent.Destination(actualB, 2_000))
        r.drain()
        assertTrue(r.state().destination is NavigationField.Missing)
        repeat(2) { p.events(NavigationSdkEvent.Arrived(actualB.name, actualB.address, 1, 1)) }
        r.drain()
        assertEquals("B",r.state().latestCommand!!.command.id)
        assertTrue(r.state().destination is NavigationField.Missing)
        val arrivals = r.events.filterIsInstance<NavigationEvent.ArrivalObserved>()
        assertEquals(2, arrivals.size)
        assertTrue(arrivals.all { it.observation.sdkName == actualB.name && it.observation.sdkAddress == actualB.address &&
            it.correlation == NavigationArrivalCorrelation.AwaitingIndependentRouteEvidence })
        assertEquals(3,p.calls)
    }
    @Test fun routeIdChangeOrLossClearsThePriorRouteEstimate() {
        val rig = Rig()
        val sdk = rig.connect()
        val destination = NavigationDestination(37.0, 127.0, "같은 목적지", null, null, null, null)
        sdk.events(NavigationSdkEvent.Destination(destination, 500L))
        sdk.events(NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
        sdk.events(NavigationSdkEvent.RouteId("A"))
        rig.drain()
        assertEquals(500L, (rig.state().remainingEstimatedMeters as NavigationField.Value).value)

        sdk.events(NavigationSdkEvent.RouteId("A"))
        rig.drain()
        assertEquals(500L, (rig.state().remainingEstimatedMeters as NavigationField.Value).value)

        sdk.events(NavigationSdkEvent.RouteId("B"))
        rig.drain()
        assertEquals("B", (rig.state().routeId as NavigationField.Value).value)
        assertEquals(NavigationMissing.REQUEST_CHANGED, (rig.state().destination as NavigationField.Missing).reason)
        assertTrue(rig.state().remainingEstimatedMeters is NavigationField.Missing)
        assertTrue(rig.state().guidance is NavigationField.Missing)

        sdk.events(NavigationSdkEvent.Destination(destination, 800L))
        sdk.events(NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
        rig.drain()
        assertTrue(rig.state().remainingEstimatedMeters is NavigationField.Missing)

        sdk.events(NavigationSdkEvent.RouteId(""))
        rig.drain()
        assertEquals(NavigationMissing.NO_ROUTE_ID, (rig.state().routeId as NavigationField.Missing).reason)
        assertTrue(rig.state().destination is NavigationField.Missing)
        assertTrue(rig.state().remainingEstimatedMeters is NavigationField.Missing)

        sdk.events(NavigationSdkEvent.Destination(destination, 800L))
        sdk.events(NavigationSdkEvent.RouteId("C"))
        rig.drain()
        assertEquals("C", (rig.state().routeId as NavigationField.Value).value)
        assertTrue(rig.state().remainingEstimatedMeters is NavigationField.Missing)
        assertTrue(rig.events.none { it is NavigationEvent.ArrivalObserved })
    }

    private class Quote(rig: Rig) {
        private val identity = GameIdentity(ProfileId("quote-profile"), InputSource.VEHICLE)
        val confirmation = NavigationDestinationConfirmation({ true }, { DestinationKey("confirmed") })
        private val port = FNavigationDeliveryPort({ confirmation.snapshot }, { owner, _ -> confirmation.selected(owner) },
            { owner, raw -> deliveryEstimateSource(owner, raw) { true } },
            { Observation.Missing(MissingReason.NO_DATA) }, { true }, { _, _ -> ContractResult.Success(Unit) })

        init { confirmation.setEnabled(true); confirmation.bind(identity); accept(rig) }
        fun accept(rig: Rig) { confirmation.accept(rig.state()) }
        fun estimate() = (port.read(identity) as ContractResult.Success).value.estimated
        fun assertBlocked() {
            assertTrue(confirmation.confirm() is ContractResult.Failure)
            assertTrue(estimate() is Observation.Missing)
        }
    }

    @Test fun emptyFirstRouteIdBlocksQuoteOnFreshConnectionAndReconnect() {
        for (reconnect in listOf(false, true)) {
            val rig = Rig()
            val original = rig.connect()
            val sdk = if (reconnect) {
                original.events(NavigationSdkEvent.RouteId("prior-connection"))
                rig.drain()
                rig.adapter.disconnect()
                rig.drain()
                rig.connect()
            } else original
            val quote = Quote(rig)
            val destination = NavigationDestination(37.0, 127.0, "이전 목적지", "이전 주소", null, null, null)
            val events = listOf(NavigationSdkEvent.Destination(destination, 500),
                NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"), NavigationSdkEvent.RouteId(""),
                NavigationSdkEvent.Destination(destination, 400), NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"),
                NavigationSdkEvent.RouteId("new"), NavigationSdkEvent.Destination(destination, 300),
                NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
            for (event in events) {
                sdk.events(event)
                rig.drain()
                quote.accept(rig)
                quote.assertBlocked()
            }
        }
    }

    @Test fun newIdCannotReauthorizeDestinationAndGuidanceAfterInvalidation() {
        val invalidations = listOf(NavigationSdkEvent.RouteId(""), NavigationSdkEvent.RouteEnded,
            NavigationSdkEvent.DestinationChanged, NavigationSdkEvent.RouteCancelled)
        for (invalidation in invalidations) for (newIdFirst in listOf(false, true)) {
            val rig = Rig()
            val sdk = rig.connect()
            val quote = Quote(rig)
            val destination = NavigationDestination(37.0, 127.0, "같은 목적지", "같은 주소", null, null, null)
            fun send(event: NavigationSdkEvent) {
                sdk.events(event)
                rig.drain()
                quote.accept(rig)
            }

            send(NavigationSdkEvent.RouteId("A"))
            send(NavigationSdkEvent.Destination(destination, 500))
            send(NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
            assertTrue(quote.confirmation.confirm() is ContractResult.Success)
            assertEquals(Observation.Available(EstimatedRouteDistance(500, InputSource.VEHICLE)), quote.estimate())

            val late = listOf(NavigationSdkEvent.Destination(destination, 450), NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
            val id = listOf(NavigationSdkEvent.RouteId("B"))
            for (event in listOf(invalidation) + if (newIdFirst) id + late else late + id) {
                send(event)
                quote.assertBlocked()
            }
            send(NavigationSdkEvent.RouteId("A"))
            quote.assertBlocked()
            rig.adapter.refresh()
            rig.drain()
            quote.accept(rig)
            for (event in id + late) {
                send(event)
                quote.assertBlocked()
            }
        }
    }

    @Test fun differentFirstIdAfterReconnectCannotAuthorizeLateOldDestination() {
        val rig = Rig()
        val oldPort = rig.connect()
        val quote = Quote(rig)
        val old = NavigationDestination(37.0, 127.0, "A", "A 주소", null, null, null)
        oldPort.events(NavigationSdkEvent.RouteId("A"))
        oldPort.events(NavigationSdkEvent.Destination(old, 500))
        oldPort.events(NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
        rig.drain()
        quote.accept(rig)
        assertTrue(quote.confirmation.confirm() is ContractResult.Success)
        rig.adapter.disconnect()
        rig.drain()
        val newPort = rig.connect()
        for (event in listOf(NavigationSdkEvent.RouteId("B"), NavigationSdkEvent.Destination(old, 450),
            NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))) {
            newPort.events(event)
            rig.drain()
            quote.accept(rig)
            quote.assertBlocked()
        }
    }

    @Test fun deniedReadForChangeOrCancelBlocksQuoteUntilFreshRead() {
        for (cancel in listOf(false, true)) {
            val rig = Rig()
            val sdk = rig.connect()
            val quote = Quote(rig)
            val destination = NavigationDestination(37.0, 127.0, "A", "A 주소", null, null, null)
            fun observeA(meters: Long) {
                sdk.events(NavigationSdkEvent.RouteId("A"))
                sdk.events(NavigationSdkEvent.Destination(destination, meters))
                sdk.events(NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
                rig.drain()
                quote.accept(rig)
                assertTrue(quote.confirmation.confirm() is ContractResult.Success)
                assertEquals(Observation.Available(EstimatedRouteDistance(meters, InputSource.VEHICLE)), quote.estimate())
            }

            observeA(500)
            rig.read = NavigationPermission.DENIED
            if (cancel) rig.adapter.cancelRoute("cancel") else rig.adapter.requestRoute(request("change"))
            rig.drain()
            quote.accept(rig)
            assertEquals(0, sdk.calls)
            assertEquals(0, sdk.queries)
            assertEquals(NavigationCommandStatus.NOT_SENT, rig.command().status)
            assertEquals(NavigationError.PERMISSION_DENIED, rig.state().failure?.error)
            assertTrue(quote.confirmation.confirm() is ContractResult.Failure)
            assertEquals(Observation.Missing(MissingReason.PERMISSION_DENIED), quote.estimate())

            rig.read = NavigationPermission.GRANTED
            rig.adapter.refresh()
            rig.drain()
            quote.accept(rig)
            quote.assertBlocked()
            assertEquals(1, sdk.queries)
            observeA(490)
        }
    }

    @Test fun deniedWriteForChangeOrCancelPreservesAnUnchangedRoute() {
        for (cancel in listOf(false, true)) {
            val rig = Rig()
            val sdk = rig.connect()
            val quote = Quote(rig)
            val destination = NavigationDestination(37.0, 127.0, "A", "A 주소", null, null, null)
            fun observeA(meters: Long) {
                sdk.events(NavigationSdkEvent.RouteId("A"))
                sdk.events(NavigationSdkEvent.Destination(destination, meters))
                sdk.events(NavigationSdkEvent.Guidance(mode = "ROUTE_GUIDING"))
                rig.drain()
                quote.accept(rig)
                assertTrue(quote.confirmation.confirm() is ContractResult.Success)
                assertEquals(Observation.Available(EstimatedRouteDistance(meters, InputSource.VEHICLE)), quote.estimate())
            }

            observeA(500)
            rig.search = NavigationPermission.DENIED
            if (cancel) rig.adapter.cancelRoute("cancel") else rig.adapter.requestRoute(request("change"))
            rig.drain()
            assertEquals(0, sdk.calls)
            assertEquals(NavigationCommandStatus.NOT_SENT, rig.command().status)
            assertEquals(NavigationError.PERMISSION_DENIED, rig.command().failure?.error)
            quote.accept(rig)
            assertNull(rig.state().failure)
            assertTrue(quote.confirmation.confirm() is ContractResult.Success)
            assertEquals(Observation.Available(EstimatedRouteDistance(500, InputSource.VEHICLE)), quote.estimate())
            rig.adapter.refresh()
            rig.drain()
            assertEquals(1, sdk.queries)
            observeA(490)
        }
    }

    @Test fun disconnectReportsCleanupFailureAndDropsQueuedOldArrival() {
        val rig = Rig()
        val sdk = rig.connect()
        sdk.events(NavigationSdkEvent.Arrived("구 연결 도착", null, 12, 3))
        rig.worker.drain()
        sdk.closeFailure = IllegalStateException("close failed")
        rig.adapter.disconnect()
        rig.drain()

        val failures = rig.events.filterIsInstance<NavigationEvent.Failure>()
        assertEquals(1, failures.size)
        assertEquals(NavigationOperation.CLEANUP, failures.single().failure.operation)
        assertEquals(NavigationConnection.DISCONNECTED, rig.state().connection)
        assertTrue(rig.events.none { it is NavigationEvent.ArrivalObserved })
        assertEquals(1, sdk.closes)
    }

    @Test fun callbackAlreadyOnWorkerCannotBePublishedUnderNewConnectionGeneration() {
        val rig = Rig()
        val oldSdk = rig.connect()
        rig.onReadPermission = {
            rig.onReadPermission = null
            rig.adapter.disconnect()
            rig.adapter.connect(rig.events::add)
        }
        oldSdk.events(NavigationSdkEvent.Arrived("이전 목적지", null, 300L, 60L))
        rig.drain()

        assertEquals(1, oldSdk.closes)
        assertEquals(2, rig.ports.size)
        assertTrue(rig.events.none { it is NavigationEvent.ArrivalObserved })
        assertEquals(NavigationConnection.CONNECTING, rig.state().connection)
    }

    @Test fun timeoutDoesNotBecomeFailureOrAutomaticRetryOfRoute(){
        val r=Rig();val p=r.connect();r.adapter.requestRoute(request());r.drain();r.time.advance(100);r.drain()
        assertEquals(NavigationCommandStatus.OUTCOME_UNKNOWN,r.command().status);assertEquals(1,p.calls)
        r.adapter.retry(NavigationCommand.Route(request()));r.drain();assertEquals(1,p.calls)
    }
    @Test fun queryTimeoutClosesUnresponsiveConnectionWithoutInventingServiceDeath(){
        val r=Rig();val p=r.connect();r.adapter.refresh();r.drain();r.time.advance(100);r.drain()
        assertEquals(NavigationConnection.TIMED_OUT,r.state().connection);assertEquals(1,p.closes)
        p.events(NavigationSdkEvent.RouteId("late"));r.drain();assertTrue(r.state().routeId is NavigationField.Missing)
    }
    @Test fun synchronousSecurityAndAsyncSdkErrorsStayDistinctAndUncertain(){
        val r=Rig();val p=r.connect();p.exception=SecurityException("secret");r.adapter.requestRoute(request());r.drain()
        assertEquals(NavigationError.SECURITY_EXCEPTION,r.command().failure!!.error)
        assertEquals(NavigationCommandStatus.OUTCOME_UNKNOWN,r.command().status)
        val q=r.connect();q.events(NavigationSdkEvent.Error("CAUSE_SEARCH_ROUTE"));r.drain()
        assertEquals(NavigationError.ASYNC_SDK_ERROR,r.state().failure!!.error)
        assertEquals(NavigationErrorOrigin.ASYNCHRONOUS,r.state().failure!!.origin)
    }
    @Test fun partialMissingAndReceivedClockRemainDistinctFromArrivalTime(){
        val r=Rig();val p=r.connect();p.events(NavigationSdkEvent.RouteId(null));p.events(NavigationSdkEvent.Destination(NavigationDestination(Double.NaN,128.0,null,null,null,null,null),-1));r.drain()
        assertEquals(NavigationMissing.NO_ROUTE_ID,(r.state().routeId as NavigationField.Missing).reason)
        assertTrue(r.state().remainingEstimatedMeters is NavigationField.Missing);assertTrue(r.state().destination is NavigationField.Missing)
        r.time.now=1234;p.events(NavigationSdkEvent.Arrived(null,null,0,0));r.time.now=9999;r.drain()
        val at=r.events.filterIsInstance<NavigationEvent.ArrivalObserved>().single().observation.stamp
        assertEquals(101234,at.receivedAtMillis);assertEquals(1234,at.receivedElapsedMillis)
    }
    @Test fun reconnectAndSupersessionPreventOldNotSentRetry(){
        val r=Rig();r.connect();r.search=NavigationPermission.DENIED;r.adapter.requestRoute(request("A"));r.drain()
        r.search=NavigationPermission.GRANTED;r.adapter.requestRoute(request("B"));r.drain();r.adapter.retry(NavigationCommand.Route(request("A")));r.drain()
        assertEquals(1,r.ports.last().calls)
        r.adapter.disconnect();r.drain();val p=r.connect();r.adapter.requestRoute(request("B"));r.drain();assertEquals(0,p.calls)
    }
    @Test fun connectFailureAndDeadlineAreNotSuccessfulInitialization(){
        val r=Rig();r.adapter.connect(r.events::add);r.drain();r.ports.last().failed(IllegalStateException());r.drain()
        assertEquals(NavigationError.INITIALIZATION_FAILED,r.state().failure!!.error)
        r.adapter.connect(r.events::add);r.drain();r.time.advance(100);r.drain()
        assertEquals(NavigationConnection.TIMED_OUT,r.state().connection)
    }
    @Test fun permissionLookupSecurityIsReportedInsteadOfEscapingWorker(){
        val r=Rig();r.permissionError=SecurityException("private");r.adapter.connect(r.events::add);r.drain()
        assertEquals(NavigationError.SECURITY_EXCEPTION,r.state().failure!!.error);assertTrue(r.ports.isEmpty())
    }
    @Test fun allPartialQueryObservationsCancelDeadlineWithoutInventingRouteCorrelation(){
        val r=Rig();val p=r.connect();r.adapter.refresh();r.drain()
        p.events(NavigationSdkEvent.Destination(NavigationDestination(37.0,127.0,null,null,null,null,null),0))
        p.events(NavigationSdkEvent.RouteId(null));p.events(NavigationSdkEvent.Guidance(mode="NOT_WORKING",isRouting=false));r.drain()
        r.time.advance(100);r.drain();assertEquals(NavigationConnection.CONNECTED_OBSERVED,r.state().connection)
        assertTrue(r.state().routeId is NavigationField.Missing);assertTrue(r.events.none{it is NavigationEvent.ArrivalObserved})
    }
    @Test fun independentStatusAndRouteStateKeepTheirOwnReceiptStamp(){
        val r=Rig();val p=r.connect();r.time.now=1001;p.events(NavigationSdkEvent.Guidance(status="ROUTE_DRIVE"));r.drain()
        r.time.now=1002;p.events(NavigationSdkEvent.Guidance(mode="SIMULATION_GUIDING",isRouting=false));r.drain()
        val status=r.state().navigationStatus as NavigationField.Value
        assertEquals("ROUTE_DRIVE",status.value);assertEquals(1001L,status.stamp.receivedElapsedMillis)
        assertEquals("SIMULATION_GUIDING",(r.state().guidance as NavigationField.Value).value.mode)
    }
    @Test fun invalidRequestAndReleaseDoNotTouchSdk(){
        val r=Rig();val p=r.connect();r.adapter.requestRoute(request().copy(latitude=Double.POSITIVE_INFINITY));r.drain()
        assertEquals(0,p.calls);assertEquals(NavigationError.INVALID_REQUEST,r.command().failure!!.error)
        r.adapter.close();r.drain();r.adapter.disconnect();r.adapter.connect(r.events::add);r.adapter.refresh();r.drain()
        assertEquals(1,p.closes);assertEquals(1,r.ports.size)
    }

    @Test fun callbackAfterCloseDoesNotEnqueueOnShutDownWorker(){
        val r=Rig();val p=r.connect();r.adapter.close();r.drain();val before=r.events.size
        p.events(NavigationSdkEvent.Arrived("늦음",null,0,0));p.connected();p.failed(IllegalStateException())
        assertTrue(r.worker.jobs.isEmpty());r.drain();assertEquals(before,r.events.size)
    }
    @Test fun unavailableSdkIsDifferentFromPermissionAndAsynchronousFailure(){
        val r=Rig();r.factoryError=NoClassDefFoundError("SDK absent");r.adapter.connect(r.events::add);r.drain()
        assertEquals(NavigationError.SDK_UNSUPPORTED,r.state().failure!!.error)
        assertEquals(NavigationErrorOrigin.SYNCHRONOUS,r.state().failure!!.origin);assertTrue(r.ports.isEmpty())
    }

    @Test fun revokedReadPermissionRejectsSdkDataAndArrivalBeforePublication(){
        val r=Rig();val p=r.connect();r.read=NavigationPermission.DENIED
        p.events(NavigationSdkEvent.Arrived("권한뒤응답",null,0,0));r.drain()
        assertTrue(r.events.none{it is NavigationEvent.ArrivalObserved})
        assertEquals(NavigationError.PERMISSION_DENIED,r.state().failure!!.error);assertEquals(1,p.closes)
    }

    @Test fun requestAndSnapshotMetadataNeverMasqueradeAsSdkObservation(){
        val r=Rig();val p=r.connect();r.adapter.requestRoute(request());r.drain()
        assertEquals(NavigationSource.APP_REQUEST,r.command().stamp.source)
        assertEquals(NavigationSource.APP_STATE,r.state().stamp.source)
        p.events(NavigationSdkEvent.RouteId("raw"));r.drain()
        assertEquals(NavigationSource.PLEOS_NAVI_SDK,(r.state().routeId as NavigationField.Value).stamp.source)
    }

}
