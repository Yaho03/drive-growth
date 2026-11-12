package io.drivegrowth.app.vehicle.navigation

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** SDK 작업은 직렬 worker, 전달은 main. 공개 stop은 이미 main에 대기 중인 전달도 즉시 무효화한다. */
class NavigationAdapter(private val worker: Executor, private val main: Executor,
    private val clock: NavigationClock, private val timer: NavigationTimer,
    private val readPermission: () -> NavigationPermission, private val searchPermission: () -> NavigationPermission,
    private val createPort: () -> NavigationSdkPort, private val timeoutMillis: Long) : AutoCloseable {
    init { require(timeoutMillis > 0) }
    private val generation = AtomicLong()
    private val released = AtomicBoolean()
    // 작업 중 연결이 교체돼도 현재 worker의 표본을 새 연결 세대로 발행하지 않는다.
    private var workerGeneration = 0L
    private var sequence = 0L
    private var listener: ((NavigationEvent) -> Unit)? = null
    private var port: NavigationSdkPort? = null
    private var connection = NavigationConnection.DISCONNECTED
    private var destination: NavigationField<NavigationDestination> = missing()
    private var distance: NavigationField<Long> = missing()
    private var route: NavigationField<String> = missing()
    private var lastRouteId: String? = null
    // 조회·재연결 후에도 경로 ID 하나로 폐기한 견적을 다시 유효하게 만들 수 없다.
    private var routeInvalidated = false
    private var guidance: NavigationField<NavigationGuidance> = missing()
    private var startDistance: NavigationField<Long> = missing()
    private var navigationStatus: NavigationField<String> = missing()
    private var failure: NavigationFailure? = null
    private val commands = linkedMapOf<String, NavigationCommandRecord>()
    private var latestId: String? = null
    private var connectionDeadline: NavigationCancellation? = null
    private var queryDeadline: NavigationCancellation? = null
    private var commandDeadline: NavigationCancellation? = null
    private val pendingQuery = mutableSetOf<String>()

    fun connect(listener: (NavigationEvent) -> Unit) {
        if (released.get()) return
        val token = generation.incrementAndGet()
        worker.execute {
            if (!valid(token)) return@execute
            closePort()
            workerGeneration = token
            this.listener = listener
            clear(if (routeInvalidated) NavigationMissing.REQUEST_CHANGED else NavigationMissing.NOT_OBSERVED)
            failure = null
            connection = NavigationConnection.CONNECTING
            val denied = checkedPermission(readPermission, NavigationOperation.CONNECT)
            if (denied != null) { failConnection(denied); return@execute }
            publish()
            try {
                val sdk = createPort(); port = sdk
                connectionDeadline = timer.after(timeoutMillis) { worker.execute {
                    if (active(token, sdk) && connection == NavigationConnection.CONNECTING) timeout(NavigationOperation.CONNECT)
                } }
                sdk.initialize(
                    { receive(token, sdk) { connectionDeadline?.cancel(); connectionDeadline = null
                        connection = NavigationConnection.CONNECTED_OBSERVED; failure = null; publish() } },
                    { error -> receive(token, sdk) { failConnection(error(error, NavigationOperation.CONNECT, NavigationErrorOrigin.ASYNCHRONOUS)) } },
                    { event -> receive(token, sdk) { stamp -> observe(event, stamp) } },
                )
            } catch (error: LinkageError) { failConnection(error(error, NavigationOperation.CONNECT)) }
            catch (error: RuntimeException) { failConnection(error(error, NavigationOperation.CONNECT)) }
        }
    }

    /** 연결만 해제한다. 실제 내비 경로를 취소하지 않는다. */
    fun disconnect() {
        if (released.get()) return
        val token = generation.incrementAndGet()
        worker.execute { if (valid(token)) {
            workerGeneration = token
            closePort()
            connection = NavigationConnection.DISCONNECTED
            clear(NavigationMissing.DISCONNECTED); failure = null; publish()
        } }
    }
    override fun close() {
        if (released.getAndSet(true)) return
        generation.incrementAndGet()
        worker.execute { closePort(); listener = null; clear(NavigationMissing.DISCONNECTED) }
    }
    fun refresh() = dispatch {
        if (!ready(NavigationOperation.QUERY)) return@dispatch
        queryDeadline?.cancel(); pendingQuery.clear(); pendingQuery.addAll(listOf("destination", "route", "guidance"))
        clear(if (routeInvalidated) NavigationMissing.REQUEST_CHANGED else NavigationMissing.NOT_OBSERVED)
        failure = null; publish()
        val token = workerGeneration; val sdk = port!!
        queryDeadline = timer.after(timeoutMillis) { worker.execute { if (active(token, sdk) && pendingQuery.isNotEmpty()) timeout(NavigationOperation.QUERY) } }
        try { sdk.query() }
        catch (error: RuntimeException) { failConnection(error(error, NavigationOperation.QUERY)) }
        catch (error: LinkageError) { failConnection(error(error, NavigationOperation.QUERY)) }
    }
    fun requestRoute(request: NavigationRouteRequest) = submit(NavigationCommand.Route(request), false)
    fun cancelRoute(id: String) = submit(NavigationCommand.Cancel(id), false)
    /** NOT_SENT였고 이후 다른 명령이 없을 때만 원문 그대로 명시 재시도한다. 전송 불명은 재조정 필요다. */
    fun retry(command: NavigationCommand) = submit(command, true)

    private fun submit(command: NavigationCommand, retry: Boolean) = dispatch {
        val previous = commands[command.id]
        if (previous != null && previous.command != command) { reject(NavigationError.REQUEST_ID_CONFLICT, operation(command)); return@dispatch }
        if (retry) {
            if (previous == null || previous.status != NavigationCommandStatus.NOT_SENT || latestId != command.id) {
                reject(NavigationError.RECONCILIATION_REQUIRED, operation(command)); return@dispatch
            }
        } else if (previous != null) { emit(NavigationEvent.Command(previous)); return@dispatch }
        val op = operation(command)
        val invalid = when {
            !validCommand(command) -> NavigationFailure(NavigationError.INVALID_REQUEST, op, NavigationErrorOrigin.PREFLIGHT)
            connection != NavigationConnection.CONNECTED_OBSERVED || port == null -> NavigationFailure(NavigationError.NOT_CONNECTED, op, NavigationErrorOrigin.PREFLIGHT)
            else -> null
        }
        val readFailure = if (invalid == null) checkedPermission(readPermission, op) else null
        val preflight = invalid ?: readFailure ?: checkedPermission(searchPermission, op)
        latestId = command.id
        if (preflight != null) {
            if (readFailure != null) {
                queryDeadline?.cancel(); queryDeadline = null; pendingQuery.clear()
                clear(NavigationMissing.SDK_ERROR)
                failure = readFailure
            }
            record(command, NavigationCommandStatus.NOT_SENT, preflight)
            publish()
            return@dispatch
        }
        commandDeadline?.cancel(); queryDeadline?.cancel(); pendingQuery.clear()
        if (lastRouteId != null || commands.values.any { it.status != NavigationCommandStatus.NOT_SENT } ||
            command is NavigationCommand.Cancel) routeInvalidated = true
        clear(NavigationMissing.REQUEST_CHANGED)
        record(command, NavigationCommandStatus.SENT_UNCONFIRMED); failure = null; publish()
        val sdk = port!!; val token = workerGeneration
        commandDeadline = timer.after(timeoutMillis) { worker.execute {
            if (active(token, sdk) && latestId == command.id && commands[command.id]?.status == NavigationCommandStatus.SENT_UNCONFIRMED) {
                record(command, NavigationCommandStatus.OUTCOME_UNKNOWN, NavigationFailure(NavigationError.TIMEOUT, op, NavigationErrorOrigin.DEADLINE)); publish()
            }
        } }
        try { when (command) { is NavigationCommand.Route -> sdk.requestRoute(command.request); is NavigationCommand.Cancel -> sdk.cancelRoute() } }
        catch (error: RuntimeException) { commandException(command, error) }
        catch (error: LinkageError) { commandException(command, error) }
    }

    private fun observe(event: NavigationSdkEvent, stamp: NavigationStamp) {
        when (event) {
            is NavigationSdkEvent.Destination -> {
                if (!routeInvalidated) {
                    val d = event.destination
                    destination = if (coordinates(d.latitude, d.longitude)) NavigationField.Value(d, stamp)
                        else NavigationField.Missing(NavigationMissing.INVALID_SDK_VALUE, stamp)
                    distance = if (event.remainingMeters >= 0) NavigationField.Value(event.remainingMeters, stamp)
                        else NavigationField.Missing(NavigationMissing.INVALID_SDK_VALUE, stamp)
                }
                queried("destination")
            }
            is NavigationSdkEvent.RouteId -> {
                observeRouteId(event.value, stamp)
                queried("route")
            }
            is NavigationSdkEvent.Guidance -> {
                if (!routeInvalidated) {
                    // 두 다른 콜백의 status/mode를 합쳐 현재 하나의 상태라고 꾸미지 않는다.
                    if (event.status != null) navigationStatus = NavigationField.Value(event.status, stamp)
                    if (event.mode != null || event.isRouting != null)
                        guidance = NavigationField.Value(NavigationGuidance(null, event.mode, event.isRouting), stamp)
                }
                queried("guidance")
            }
            is NavigationSdkEvent.RouteStarted -> if (!routeInvalidated) {
                startDistance = if (event.estimatedMeters >= 0) NavigationField.Value(event.estimatedMeters, stamp)
                    else NavigationField.Missing(NavigationMissing.INVALID_SDK_VALUE, stamp)
            }
            is NavigationSdkEvent.Arrived -> emit(NavigationEvent.ArrivalObserved(NavigationArrivalObservation(stamp, event.name, event.address, event.distanceMeters, event.durationSeconds)))
            is NavigationSdkEvent.Error -> {
                failure = NavigationFailure(NavigationError.ASYNC_SDK_ERROR, NavigationOperation.SDK_EVENT, NavigationErrorOrigin.ASYNCHRONOUS, event.code)
                clear(NavigationMissing.SDK_ERROR)
                latestId?.let { commands[it] }?.takeIf { it.status == NavigationCommandStatus.SENT_UNCONFIRMED }?.let {
                    record(it.command, NavigationCommandStatus.OUTCOME_UNKNOWN, failure)
                }
            }
            NavigationSdkEvent.RouteCancelled, NavigationSdkEvent.RouteEnded, NavigationSdkEvent.DestinationChanged -> invalidateRoute()
        }
        publish()
    }
    private fun observeRouteId(value: String?, stamp: NavigationStamp) {
        val current = value?.takeIf { it.isNotBlank() }
        val previous = lastRouteId
        if (current == null) {
            invalidateRoute()
            route = NavigationField.Missing(NavigationMissing.NO_ROUTE_ID, stamp)
            return
        }
        if (previous != null && previous != current) invalidateRoute()
        lastRouteId = current
        // 위치 응답의 ID는 원래 목적지·안내 콜백과 연결되지 않는다.
        route = NavigationField.Value(current, stamp)
    }

    private fun invalidateRoute() {
        routeInvalidated = true
        clear(NavigationMissing.REQUEST_CHANGED)
    }

    private fun queried(field: String) { pendingQuery.remove(field); if (pendingQuery.isEmpty()) { queryDeadline?.cancel(); queryDeadline = null } }
    private fun commandException(command: NavigationCommand, thrown: Throwable) {
        val f = error(thrown, operation(command)); commandDeadline?.cancel()
        record(command, NavigationCommandStatus.OUTCOME_UNKNOWN, f)
        failConnection(f)
    }
    private fun record(command: NavigationCommand, status: NavigationCommandStatus, f: NavigationFailure? = null) {
        val record = NavigationCommandRecord(command, status, stamp(source = NavigationSource.APP_REQUEST), f); commands[command.id] = record
        emit(NavigationEvent.Command(record))
    }
    private fun ready(op: NavigationOperation): Boolean {
        val f = if (connection != NavigationConnection.CONNECTED_OBSERVED || port == null) NavigationFailure(NavigationError.NOT_CONNECTED, op, NavigationErrorOrigin.PREFLIGHT)
            else checkedPermission(readPermission, op)
        if (f != null) { failConnection(f); return false }; return true
    }
    private fun failConnection(f: NavigationFailure) { closePort(); clear(NavigationMissing.SDK_ERROR); connection = NavigationConnection.FAILED; failure = f; publish() }
    private fun timeout(op: NavigationOperation) {
        closePort(); clear(NavigationMissing.TIMEOUT); connection = NavigationConnection.TIMED_OUT
        failure = NavigationFailure(NavigationError.TIMEOUT, op, NavigationErrorOrigin.DEADLINE); publish()
    }
    private fun closePort() {
        connectionDeadline?.cancel(); queryDeadline?.cancel(); commandDeadline?.cancel(); pendingQuery.clear()
        connectionDeadline = null; queryDeadline = null; commandDeadline = null
        val sdk = port; port = null
        if (sdk != null) try { sdk.close() }
        catch (error: RuntimeException) { emit(NavigationEvent.Failure(error(error, NavigationOperation.CLEANUP))) }
        catch (error: LinkageError) { emit(NavigationEvent.Failure(error(error, NavigationOperation.CLEANUP))) }
    }
    private fun clear(reason: NavigationMissing) { destination = missing(reason); distance = missing(reason); route = missing(reason); guidance = missing(reason); startDistance = missing(reason); navigationStatus = missing(reason) }
    private fun publish() {
        val canQuote = route is NavigationField.Value && !routeInvalidated
        val quotedDestination = if (canQuote || destination is NavigationField.Missing) destination else missing()
        val quotedDistance = if (canQuote || distance is NavigationField.Missing) distance else missing()
        emit(NavigationEvent.State(NavigationSnapshot(stamp(source = NavigationSource.APP_STATE), connection,
            quotedDestination, quotedDistance, route, guidance, startDistance, latestId?.let { commands[it] }, failure, navigationStatus)))
    }
    private fun emit(event: NavigationEvent) { val token = workerGeneration; val consumer = listener ?: return
        main.execute { if (valid(token)) consumer(event) } }
    private fun stamp(at: Long = clock.utcMillis(), elapsed: Long = clock.elapsedMillis(), source: NavigationSource = NavigationSource.PLEOS_NAVI_SDK) = NavigationStamp(at, elapsed, workerGeneration, ++sequence, source)
    private fun receive(token: Long, sdk: NavigationSdkPort, action: (NavigationStamp) -> Unit) {
        if (!valid(token)) return
        val at = clock.utcMillis(); val elapsed = clock.elapsedMillis()
        try { worker.execute {
            if (active(token, sdk)) {
                val denied = checkedPermission(readPermission, NavigationOperation.QUERY)
                if (denied != null) failConnection(denied) else action(stamp(at, elapsed))
            }
        } } catch (error: RejectedExecutionException) { if (valid(token)) throw error }
    }
    private fun dispatch(action: () -> Unit) { val token = generation.get(); if (released.get()) return
        worker.execute { if (valid(token)) action() } }
    private fun valid(token: Long) = !released.get() && generation.get() == token
    private fun active(token: Long, sdk: NavigationSdkPort) = valid(token) && port === sdk
    private fun reject(error: NavigationError, op: NavigationOperation) = emit(NavigationEvent.Failure(NavigationFailure(error, op, NavigationErrorOrigin.PREFLIGHT)))
    private fun operation(command: NavigationCommand) = if (command is NavigationCommand.Route) NavigationOperation.ROUTE else NavigationOperation.CANCEL
    private fun validCommand(command: NavigationCommand) = command.id.isNotBlank() && (command !is NavigationCommand.Route || coordinates(command.request.latitude, command.request.longitude))
    private fun coordinates(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    private fun checkedPermission(check: () -> NavigationPermission, op: NavigationOperation): NavigationFailure? =
        try { permission(check(), op) }
        catch (error: RuntimeException) { error(error, op, NavigationErrorOrigin.PREFLIGHT) }
        catch (error: LinkageError) { error(error, op, NavigationErrorOrigin.PREFLIGHT) }
    private fun permission(value: NavigationPermission, op: NavigationOperation) = when (value) {
        NavigationPermission.GRANTED -> null
        NavigationPermission.NOT_DECLARED -> NavigationFailure(NavigationError.PERMISSION_NOT_DECLARED, op, NavigationErrorOrigin.PREFLIGHT)
        NavigationPermission.DENIED -> NavigationFailure(NavigationError.PERMISSION_DENIED, op, NavigationErrorOrigin.PREFLIGHT)
    }
    private fun error(error: Throwable, op: NavigationOperation, origin: NavigationErrorOrigin = NavigationErrorOrigin.SYNCHRONOUS) = NavigationFailure(
        when (error) { is SecurityException -> NavigationError.SECURITY_EXCEPTION; is LinkageError, is UnsupportedOperationException -> NavigationError.SDK_UNSUPPORTED
            else -> if (op == NavigationOperation.CONNECT) NavigationError.INITIALIZATION_FAILED else NavigationError.SDK_CALL_FAILED }, op, origin)
    private fun missing(reason: NavigationMissing = NavigationMissing.NOT_OBSERVED) = NavigationField.Missing(reason)
}
