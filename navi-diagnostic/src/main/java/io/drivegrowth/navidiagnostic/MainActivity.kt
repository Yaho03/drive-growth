package io.drivegrowth.navidiagnostic

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 에뮬레이터 관측 전용. 게임·차량 신호·도착 판정·자동 경로 변경을 포함하지 않는다. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val runId = UUID.randomUUID().toString()
    private var sequence = 0L
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var connection = "미연결"
    private var initialized = false
    private var polling = false
    private var destroyed = false
    private data class RequestContext(
        val latestRequest: String = "없음",
        val routeRequest: String = "없음",
        val destination: String = "없음 — 내비 UI의 기존 경로일 수 있음",
    )
    @Volatile private var requestContext = RequestContext()
    private var storageError: String? = null
    private val observations = linkedMapOf<String, JSONObject>()
    private lateinit var status: TextView
    private lateinit var recent: TextView
    private lateinit var logFile: File
    private val recentLines = ArrayDeque<String>()
    private val destination = RouteInfo(
        latitude = 37.41289678, longitude = 127.09383464,
        displayLongitude = null, displayLatitude = null, poiName = "42dot",
        poiId = "36khp37hh8gqcm5nqhe5", poiSubId = "0",
        address = "수정구 시흥동",
    )
    private val poll = object : Runnable {
        override fun run() {
            if (!polling || destroyed) return
            query("사용자가 시작한 주기 조회")
            if (polling) handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logFile = File(filesDir, "navi-$runId.jsonl")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }
        status = TextView(this).apply { textSize = 17f }
        recent = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        layout.addView(status)
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("1. SDK 연결 (경로 변경 없음)", ::connect)
        button("2. 현재 목적지·경로·내비 위치 조회") { query("사용자 단발 조회") }
        button("3. 2초 조회 시작 / 중지") {
            polling = !polling && initialized
            handler.removeCallbacks(poll)
            event("TOOL", "polling", "enabled=$polling; 조회만 수행; 프로세스 생존 보장 없음")
            if (polling) handler.post(poll)
        }
        button("4. 시험 경로 요청: 42dot (시흥 POI 일치)") {
            if (!initialized) { event("TOOL", "requestBlocked", "먼저 SDK 연결 필요"); return@button }
            query("경로 요청 전 조회 — 응답은 비동기")
            requestContext = requestContext.copy(destination = destination.toString())
            request("requestRoute", destination.toString()) { it.requestRoute(destination) }
            query("경로 요청 후 조회 — 완료 결과가 아닌 즉시 관측")
        }
        button("5. 경로 취소 (내비 경로에 영향)") {
            query("취소 전 조회 — 응답은 비동기")
            request("cancelRoute", "사용자가 명시적으로 선택") { it.cancelRoute() }
            query("취소 후 조회 — onRouteCancelled와 구분")
        }
        button("6. SDK 해제 (경로 취소 없음)") { disconnect("사용자 선택") }
        button("7. 현재 활성 경로 재탐색 (사용자 선택)") {
            query("재탐색 전 조회 — 응답은 비동기")
            request("requestReRoute", "현재 활성 경로 재탐색") { it.requestReRoute() }
            query("재탐색 후 조회 — onReRouteEnded 및 경로 ID와 대조")
        }
        layout.addView(recent)
        setContentView(ScrollView(this).apply { addView(layout) })
        event("TOOL", "runStarted", "SDK=3.0.5; toolRevision=issue80-followup-1; 앱 시작은 경로 요청/연결을 실행하지 않음")
        event("TOOL", "units", "DestinationInfo.remainDistance=m, remainTime=s, tollFare=KRW; " +
            "CurrentLocationInfo.latitude/longitude=degree; heading=원값·단위 미확인; " +
            "DestinationArrivedInfo.distance=m, duration=s; RouteStartInfo.distance=m, duration=s; " +
            "DrivingState.distance=m(출발점부터 지점까지 거리, 남은 거리로 재명명하지 않음), duration=s; " +
            "기타 payload는 SDK 원값, 별도 명세 확인 전 환산하지 않음")
        event("TOOL", "unitSources", "https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/" +
            "navi-helper/ai.pleos.playground.navi.data/{current-location-info,destination-info," +
            "destination-arrived-info,route-start-info,driving-state}/entry; 확인일=2026-11-06")
    }

    private fun connect() {
        if (helper != null) { event("TOOL", "connectBlocked", "기존 연결/초기화 존재. 먼저 명시적 해제"); return }
        val sdk = NaviHelper(applicationContext)
        helper = sdk
        observations.clear()
        connection = "초기화 중"
        val callbacks = callbacks(sdk)
        listener = callbacks
        try {
            event("TOOL", "permissionSnapshot", listOf(
                "pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH",
            ).joinToString { "$it=${checkSelfPermission(it)} (0=GRANTED, -1=DENIED)" })
            sdk.initialize(
                onConnected = {
                    val time = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    val context = requestContext
                    handler.post {
                        event("SDK_INITIALIZE", "onConnected", "최초 연결 성공 통지", time, elapsed,
                            helper !== sdk, context, System.identityHashCode(sdk))
                        if (helper === sdk) {
                            try {
                                // 공식 초기화 가이드 순서. 같은 참조를 연결당 한 번만 등록한다.
                                if (!initialized) sdk.addListener(callbacks)
                                initialized = true
                                connection = "최근 연결 성공 — 현재 생존은 공개 API로 확정 불가"
                                event("TOOL", "listenerRegistered", "onConnected에서 등록; 이후 콜백 관측")
                            } catch (error: Exception) {
                                event("TOOL_ERROR", "addListener", Log.getStackTraceString(error))
                                disconnect("리스너 등록 실패")
                            }
                        } else event("TOOL", "lateInitializeCallback", "해제된 연결의 onConnected; 재등록하지 않음")
                    }
                },
                onFailed = { error ->
                    val time = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    val context = requestContext
                    handler.post {
                        event("SDK_INITIALIZE", "onFailed", Log.getStackTraceString(error), time, elapsed,
                            helper !== sdk, context, System.identityHashCode(sdk))
                        if (helper === sdk) disconnect("초기화 실패")
                    }
                },
            )
            event("TOOL", "initializeReturned", "비동기 onConnected/onFailed와 별개")
        } catch (error: Exception) {
            event("TOOL_ERROR", "initializeException", Log.getStackTraceString(error))
            disconnect("초기화 예외")
        }
    }

    private fun request(name: String, payload: String, action: (NaviHelper) -> Unit) {
        val sdk = helper
        if (!initialized || sdk == null) { event("TOOL", "requestBlocked", name); return }
        val id = UUID.randomUUID().toString()
        val requestLabel = "$id:$name"
        requestContext = requestContext.copy(
            latestRequest = requestLabel,
            routeRequest = if (name == "requestRoute" || name == "cancelRoute") requestLabel else requestContext.routeRequest,
        )
        event("REQUEST", name, "requestId=$id; $payload")
        try {
            action(sdk)
            event("REQUEST_RETURN", name, "requestId=$id; 메서드 반환은 설정 성공·탐색 완료·안내 시작을 보장하지 않음")
        } catch (error: Exception) {
            connection = "요청 예외 — 연결 해제 여부 미확인"
            polling = false
            handler.removeCallbacks(poll)
            event("TOOL_ERROR", name, "requestId=$id; ${Log.getStackTraceString(error)}")
        }
    }

    private fun query(reason: String) {
        request("getDestinationInfo", reason) { it.getDestinationInfo() }
        request("getRouteStateInfo", reason) { it.getRouteStateInfo() }
        request("getCurrentLocationInfo", reason) { it.getCurrentLocationInfo() }
    }

    private fun disconnect(reason: String) {
        polling = false
        initialized = false
        handler.removeCallbacks(poll)
        val sdk = helper
        helper = null
        connection = "명시적 해제: $reason — SDK 단절 콜백이 아님"
        if (sdk != null) {
            try { listener?.let { sdk.removeListener(it) }; event("TOOL", "removeListenerReturned", reason) }
            catch (error: Exception) { event("TOOL_ERROR", "removeListener", Log.getStackTraceString(error)) }
            try { sdk.release(); event("TOOL", "releaseReturned", reason) }
            catch (error: Exception) { event("TOOL_ERROR", "release", Log.getStackTraceString(error)) }
        }
        listener = null
        event("TOOL", "disconnectedLocally", reason)
    }

    private fun received(sdk: NaviHelper, name: String, payload: Any? = null) {
        val time = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        // SDK 스레드에서 payload를 복사하고, 상태와 파일 기록은 메인 스레드에서 직렬화한다.
        val context = requestContext
        val raw = payload?.toString() ?: "<no payload>"
        handler.post {
            if (helper === sdk) {
                if (name in setOf("onNavigationStatus", "onRouteStateInfo", "onCurrentLocationInfo", "onDestinationInfo", "onDrivingInfo", "onRouteStarted", "onDestinationArrived")) observations[name] = JSONObject().put("receivedEpochMs", time).put("payloadRaw", raw)
            }
            event("SDK_CALLBACK", name, raw, time, elapsed, helper !== sdk, context, System.identityHashCode(sdk))
        }
    }

    private fun event(
        source: String, name: String, payload: String,
        time: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime(),
        staleConnection: Boolean = false, context: RequestContext = requestContext,
        sdkInstance: Int? = helper?.let(System::identityHashCode),
    ) {
        val seq = ++sequence
        val record = JSONObject()
            .put("runId", runId).put("sequence", seq)
            .put("receivedEpochMs", time).put("receivedElapsedMs", elapsed)
            .put("recordedEpochMs", System.currentTimeMillis())
            .put("source", source).put("event", name).put("payloadRaw", payload)
            .put("connectionObservationAtRecord", connection).put("sdkInstance", sdkInstance ?: JSONObject.NULL).put("staleConnection", staleConnection)
            .put("requestedDestination", context.destination)
            .put("latestRequestContextOnly", context.latestRequest)
            .put("routeRequestContextOnly", context.routeRequest)
            .put("requestContextCapturedAt", if (source.startsWith("SDK_")) "callback_receipt" else "record")
            .put("correlation", "SDK 응답 ID 없음. 마지막 요청은 인과관계 보장이 아님")
            .put("observationsAtRecord", JSONObject(observations.toMap()))
        val line = record.toString()
        try { logFile.appendText(line + "\n", Charsets.UTF_8) }
        catch (error: Exception) {
            storageError = "파일 기록 실패. Logcat 확인: ${error.message}"
            Log.e(TAG, "run=$runId seq=$seq FILE_WRITE_FAILED", error)
        }
        val chunks = logChunks(line)
        chunks.forEachIndexed { index, chunk -> Log.i(TAG, "run=$runId seq=$seq source=$source event=$name part=${index + 1}/${chunks.size} $chunk") }
        recentLines.addLast("$seq $source $name ${payload.take(220)}")
        while (recentLines.size > 18) recentLines.removeFirst()
        if (!destroyed) render()
    }

    private fun render() {
        status.text = "Navi 콜백 진단 #80\nrun=$runId\n$connection\n2초 조회=$polling\n" +
            "단절 알림 API 없음. 무응답은 단절 확정이 아님\n" +
            "도착 자동 판정 없음: 실제 onDestinationArrived 수신과 payload를 확인\n" +
            "시험 목적지: 42dot 37.41289678,127.09383464\n" +
            "로그: files/${logFile.name}\n${storageError ?: "파일 기록 오류 관측 없음"}\n" +
            observations.filterKeys { it in setOf("onNavigationStatus", "onRouteStateInfo", "onCurrentLocationInfo", "onDestinationInfo", "onDestinationArrived") }
                .entries.joinToString("\n") { "${it.key}: ${it.value}" }
        recent.text = recentLines.joinToString("\n")
    }

    override fun onStop() { event("TOOL", "activityStopped", "Activity 생존 동안 리스너/사용자 시작 조회 유지. 프로세스 지속 보장 없음"); super.onStop() }
    override fun onDestroy() { disconnect("Activity 종료"); destroyed = true; super.onDestroy() }

    private fun callbacks(sdk: NaviHelper) = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = received(sdk, "onRouteStarted", routeStartInfo)
        @Deprecated("SDK 3.0.5의 레거시 콜백도 관측 로그에 보존")
        override fun onRouteEnded() = received(sdk, "onRouteEnded")
        override fun onRoutingCompleted() = received(sdk, "onRoutingCompleted")
        override fun onReRouteEnded() = received(sdk, "onReRouteEnded")
        override fun onRouteOptionChanged(routeOption: RouteOption) = received(sdk, "onRouteOptionChanged", routeOption)
        override fun onRouteCancelled() = received(sdk, "onRouteCancelled")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = received(sdk, "onWaypointChanged", waypointChangedInfo)
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = received(sdk, "onDestinationArrived", destinationArrivedInfo)
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = received(sdk, "onWayPointArrived", waypointArrivedInfo)
        override fun onBookMarkChanged() = received(sdk, "onBookMarkChanged")
        override fun onDestinationChanged() = received(sdk, "onDestinationChanged")
        override fun onError(errorCode: NaviErrorCode) = received(sdk, "onError", errorCode)
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = received(sdk, "onCurrentLocationInfo", currentLocationInfo)
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = received(sdk, "onRouteStateInfo", routeStateInfo)
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = received(sdk, "onDestinationInfo", destinationInfo)
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = received(sdk, "onWayPointInfo", waypointInfoList)
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = received(sdk, "onRecentDestinationInfo", recentDestinationInfoList)
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = received(sdk, "onBookmarkInfo", bookmarkInfoList)
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = received(sdk, "onCurrentRoadSpeedLimit", speedLimit)
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = received(sdk, "onCameraAlert", cameraAlertList)
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = received(sdk, "onDrivingInfo", drivingInfo)
        override fun onNavigationStatus(status: NavigationStatus) = received(sdk, "onNavigationStatus", status)
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = received(sdk, "onTBTInfo", tbtInfoList)
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = received(sdk, "onChargerOperatorInfo", chargerOperatorList)
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = received(sdk, "onCautionPointInfo", cautionPointList)
        override fun onTripInfo(tripInfo: TripInfo) = received(sdk, "onTripInfo", tripInfo)
    }

    companion object { private const val TAG = "DG80Navi" }
}
