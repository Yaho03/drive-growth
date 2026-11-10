package io.drivegrowth.app.vehicle.navigation

/** SDK 관측과 앱 요청/상태를 구분한다. 내장 모의주행 여부는 guidance.mode를 별도로 확인한다. */
enum class NavigationSource { PLEOS_NAVI_SDK, APP_REQUEST, APP_STATE }
data class NavigationStamp(val receivedAtMillis: Long, val receivedElapsedMillis: Long, val connectionGeneration: Long,
    val sequence: Long, val source: NavigationSource = NavigationSource.PLEOS_NAVI_SDK)
enum class NavigationMissing { NOT_OBSERVED, INVALID_SDK_VALUE, NO_ROUTE_ID, REQUEST_CHANGED, DISCONNECTED, TIMEOUT, SDK_ERROR }
sealed interface NavigationField<out T> {
    data class Value<T>(val value: T, val stamp: NavigationStamp) : NavigationField<T>
    data class Missing(val reason: NavigationMissing, val stamp: NavigationStamp? = null) : NavigationField<Nothing>
}
data class NavigationDestination(val latitude: Double, val longitude: Double, val name: String?, val address: String?,
    val poiId: String?, val poiSubId: String?, val poiProvider: String?)
data class NavigationGuidance(val status: String?, val mode: String?, val isRouting: Boolean?)
enum class NavigationConnection { DISCONNECTED, CONNECTING, CONNECTED_OBSERVED, FAILED, TIMED_OUT }
enum class NavigationError { PERMISSION_NOT_DECLARED, PERMISSION_DENIED, SECURITY_EXCEPTION, SDK_UNSUPPORTED,
    INITIALIZATION_FAILED, SDK_CALL_FAILED, ASYNC_SDK_ERROR, TIMEOUT, NOT_CONNECTED, INVALID_REQUEST,
    REQUEST_ID_CONFLICT, RECONCILIATION_REQUIRED }
enum class NavigationOperation { CONNECT, QUERY, ROUTE, CANCEL, CLEANUP, SDK_EVENT }
enum class NavigationErrorOrigin { PREFLIGHT, SYNCHRONOUS, ASYNCHRONOUS, DEADLINE }
data class NavigationFailure(val error: NavigationError, val operation: NavigationOperation,
    val origin: NavigationErrorOrigin, val sdkCode: String? = null)

/** 명시적인 사용자 요청의 원문. POI 서버가 반환한 실제 목적지와 별도로 보관한다. */
data class NavigationRouteRequest(val id: String, val latitude: Double, val longitude: Double,
    val name: String, val poiId: String = "", val poiSubId: String? = null, val poiProvider: String = "",
    val address: String? = null, val option: NavigationRouteOption = NavigationRouteOption.RECOMMENDED)
enum class NavigationRouteOption { RECOMMENDED, SHORT_DISTANCE, SHORT_TIME, FREE, HIGHWAY, COMFORTABLE, SCHOOL_ZONE }
sealed interface NavigationCommand {
    val id: String
    data class Route(val request: NavigationRouteRequest) : NavigationCommand { override val id get() = request.id }
    data class Cancel(override val id: String) : NavigationCommand
}
enum class NavigationCommandStatus { NOT_SENT, SENT_UNCONFIRMED, OUTCOME_UNKNOWN }
data class NavigationCommandRecord(val command: NavigationCommand, val status: NavigationCommandStatus,
    val stamp: NavigationStamp, val failure: NavigationFailure? = null)

/** 필드들은 각각 수신 관측이며 하나의 경로/요청에 대한 원자 snapshot이나 응답 상관 증명이 아니다. */
data class NavigationSnapshot(val stamp: NavigationStamp, val connection: NavigationConnection,
    val destination: NavigationField<NavigationDestination>, val remainingEstimatedMeters: NavigationField<Long>,
    val routeId: NavigationField<String>, val guidance: NavigationField<NavigationGuidance>,
    val routeStartEstimatedMeters: NavigationField<Long>, val latestCommand: NavigationCommandRecord?,
    val failure: NavigationFailure?, val navigationStatus: NavigationField<String> = NavigationField.Missing(NavigationMissing.NOT_OBSERVED))
/** 수신 시각은 실제 도착 시각이 아니다. 이 타입은 DeliveryArrivalEvidence로 자동 변환하지 않는다. */
data class NavigationArrivalObservation(val stamp: NavigationStamp, val sdkName: String?, val sdkAddress: String?,
    val sdkDistanceMeters: Long, val sdkDurationSeconds: Long)
sealed interface NavigationArrivalCorrelation {
    data object AwaitingIndependentRouteEvidence : NavigationArrivalCorrelation
}
sealed interface NavigationEvent {
    data class State(val snapshot: NavigationSnapshot) : NavigationEvent
    data class Command(val record: NavigationCommandRecord) : NavigationEvent
    data class Failure(val failure: NavigationFailure) : NavigationEvent
    data class ArrivalObserved(val observation: NavigationArrivalObservation,
        val correlation: NavigationArrivalCorrelation = NavigationArrivalCorrelation.AwaitingIndependentRouteEvidence) : NavigationEvent
}
